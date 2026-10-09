package com.proudvocab.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hosts the renderer (the desktop side panel, subtitles and player, bundled under
 * assets/web) inside a single WebView and provides the native services it needs.
 */
class MainActivity : ComponentActivity() {

    private val io: ExecutorService = Executors.newCachedThreadPool()
    private lateinit var store: AppStore
    private lateinit var net: NetFetcher
    private lateinit var assets: AppRequestHandler
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreen = false
    private var rendererReady = false
    private val pendingOpenFiles = mutableListOf<String>()

    /** The bridge call waiting for a SAF picker result (only one picker at a time). */
    private var pendingDialogId: Int? = null
    private var pendingDialogKind: String = "video"
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingDialogId ?: return@registerForActivityResult
        pendingDialogId = null
        if (uri == null) {
            resolve(id, canceledJson())
        } else {
            resolve(id, pickedJson(uri, DocumentAccess.displayName(this, uri)))
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val id = pendingDialogId ?: return@registerForActivityResult
        pendingDialogId = null
        if (uri == null) {
            resolve(id, canceledJson())
        } else {
            DocumentAccess.persist(this, uri)
            resolve(id, pickedJson(uri, DocumentAccess.treeName(this, uri)))
        }
    }

    private val pickJsonFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        callback.onReceiveValue(uri?.let { arrayOf(it) })
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> hideCustomView()
                fullscreen -> setFullscreen(false)
                webView.canGoBack() -> webView.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        store = AppStore(this)
        net = NetFetcher(io)
        assets = AppRequestHandler(this)

        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // Keep the renderer clear of the status bar, navigation bar and display cutout.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        webView = createWebView()
        root.addView(webView, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState == null) webView.loadUrl(START_URL)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        // Stop audio/video when the app leaves the foreground.
        if (::webView.isInitialized) {
            webView.evaluateJavascript(PAUSE_ALL_MEDIA_JS, null)
            webView.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            root.removeView(webView)
            webView.destroy()
        }
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        return WebView(this).apply {
            setBackgroundColor(BACKGROUND)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                setGeolocationEnabled(false)
            }
            addJavascriptInterface(PvNativeBridge(this@MainActivity), "PvNative")
            webViewClient = AppWebViewClient()
            webChromeClient = AppChromeClient()
        }
    }

    private fun recreateWebView() {
        if (customView != null) hideCustomView()
        rendererReady = false
        root.removeView(webView)
        webView.destroy()
        webView = createWebView()
        root.addView(webView, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        webView.loadUrl(START_URL)
    }

    private inner class AppWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assets.handle(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val isAppPage = uri.scheme == "https" && uri.host.equals(AppRequestHandler.APP_HOST, ignoreCase = true)
            if (!isAppPage) openExternal(uri.toString())
            return !isAppPage
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            rendererReady = false
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()}); recreating")
            runOnUiThread { recreateWebView() }
            return true
        }
    }

    private inner class AppChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: WebChromeClient.FileChooserParams,
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback
            return try {
                pickJsonFile.launch(JSON_MIME_TYPES)
                true
            } catch (e: ActivityNotFoundException) {
                fileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "[${message.sourceId()}:${message.lineNumber()}] ${message.message()}")
            return true
        }
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        webView.visibility = View.GONE
        setFullscreen(true)
    }

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        setFullscreen(false)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        emit("pv:fullscreen-changed", JSONObject().put("on", on).toString())
    }

    // ------------------------------------------------------------ JS → native

    /** Called on a WebView background thread by [PvNativeBridge]. */
    fun onBridgeInvoke(id: Int, channel: String, argsJson: String) {
        runOnUiThread { dispatch(id, channel, argsJson) }
    }

    fun onBridgeAbort(id: String) {
        net.abort(id)
    }

    private fun dispatch(id: Int, channel: String, argsJson: String) {
        val args = try {
            JSONArray(argsJson)
        } catch (e: JSONException) {
            JSONArray()
        }
        when (channel) {
            "pv:app-info" -> resolve(
                id,
                JSONObject()
                    .put("name", "ProudVocab")
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("platform", "android")
                    .toString(),
            )

            "pv:ready" -> {
                rendererReady = true
                flushOpenFiles()
                resolve(id, "null")
            }

            "pv:store-load" -> offload(id) {
                JSONObject().put("ok", true).put("data", store.load()).toString()
            }

            "pv:store-save" -> {
                val data = args.optJSONObject(0) ?: JSONObject()
                offload(id) {
                    store.save(data)
                    JSONObject().put("ok", true).toString()
                }
            }

            "pv:open-dialog" -> {
                val kind = args.optJSONObject(0)?.optString("kind", "video") ?: "video"
                if (pendingDialogId != null) {
                    resolve(id, canceledJson())
                } else {
                    pendingDialogId = id
                    pendingDialogKind = kind
                    try {
                        pickMedia.launch(if (kind == "subtitle") SUBTITLE_MIME_TYPES else VIDEO_MIME_TYPES)
                    } catch (e: ActivityNotFoundException) {
                        pendingDialogId = null
                        resolve(id, errorJson("No file picker available"))
                    }
                }
            }

            "pv:open-directory" -> {
                if (pendingDialogId != null) {
                    resolve(id, canceledJson())
                } else {
                    pendingDialogId = id
                    pendingDialogKind = "directory"
                    try {
                        pickFolder.launch(null)
                    } catch (e: ActivityNotFoundException) {
                        pendingDialogId = null
                        resolve(id, errorJson("No folder picker available"))
                    }
                }
            }

            "pv:file-info" -> {
                val path = args.optString(0)
                offload(id) { DocumentAccess.fileInfo(this, path).toString() }
            }

            "pv:read-bytes" -> {
                val path = args.optString(0)
                offload(id) { DocumentAccess.readBase64(this, path).toString() }
            }

            "pv:list-videos" -> {
                val path = args.optString(0)
                offload(id) { DocumentAccess.listVideos(this, path).toString() }
            }

            // Subtitle discovery next to a picked file needs folder access that single-file picks do not have.
            "pv:scan-subtitles" -> resolve(id, JSONObject().put("ok", false).put("files", JSONArray()).toString())

            "pv:net-fetch" -> {
                val req = args.optJSONObject(0) ?: JSONObject()
                net.fetch(req) { result -> runOnUiThread { resolve(id, result.toString()) } }
            }

            "pv:open-external" -> {
                openExternal(args.optString(0))
                resolve(id, "null")
            }

            // There is no file manager integration on Android; reported as not supported.
            "pv:show-in-folder" -> resolve(id, JSONObject().put("ok", false).put("error", "not supported").toString())

            "pv:window-action" -> {
                when (args.optString(0)) {
                    "fullscreen" -> setFullscreen(!fullscreen)
                    "close" -> finish()
                    else -> Unit // minimize/maximize/devtools have no Android equivalent
                }
                resolve(id, "null")
            }

            "pv:is-fullscreen" -> resolve(id, fullscreen.toString())

            "pv:set-progress" -> resolve(id, "null")

            else -> resolve(id, errorJson("Unknown channel: $channel"))
        }
    }

    // ------------------------------------------------------------ native → JS

    /** Runs [work] on the worker pool and resolves the bridge call with its JSON result. */
    private fun offload(id: Int, work: () -> String) {
        io.execute {
            val json = try {
                work()
            } catch (e: Exception) {
                Log.w(TAG, "native call $id failed", e)
                errorJson(e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { resolve(id, json) }
        }
    }

    private fun resolve(id: Int, json: String) {
        if (isDestroyed || !::webView.isInitialized) return
        webView.evaluateJavascript("window.__pvResolve($id, ${JSONObject.quote(json)})", null)
    }

    private fun emit(channel: String, json: String) {
        if (isDestroyed || !::webView.isInitialized) return
        webView.evaluateJavascript(
            "window.__pvEmit(${JSONObject.quote(channel)}, ${JSONObject.quote(json)})",
            null,
        )
    }

    // ------------------------------------------------------------ intents

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        DocumentAccess.persist(this, uri)
        pendingOpenFiles += VirtualPaths.make(uri.toString(), DocumentAccess.displayName(this, uri))
        flushOpenFiles()
    }

    private fun flushOpenFiles() {
        if (!rendererReady || pendingOpenFiles.isEmpty()) return
        val paths = JSONArray()
        pendingOpenFiles.forEach { paths.put(it) }
        pendingOpenFiles.clear()
        emit("pv:open-files", JSONObject().put("filePaths", paths).toString())
    }

    private fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No application can open $url")
        }
    }

    private fun pickedJson(uri: Uri, displayName: String?): String {
        DocumentAccess.persist(this, uri)
        val path = VirtualPaths.make(uri.toString(), displayName ?: uri.lastPathSegment)
        return JSONObject()
            .put("canceled", false)
            .put("filePaths", JSONArray().put(path))
            .toString()
    }

    private fun canceledJson(): String = JSONObject()
        .put("canceled", true)
        .put("filePaths", JSONArray())
        .toString()

    private fun errorJson(message: String): String = JSONObject().put("__pvError", message).toString()

    companion object {
        private const val TAG = "ProudVocab"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val BACKGROUND = Color.parseColor("#0E1116")
        private const val START_URL = "${AppRequestHandler.APP_ORIGIN}/index.html"
        private val VIDEO_MIME_TYPES = arrayOf("video/*")
        private val SUBTITLE_MIME_TYPES = arrayOf(
            "application/x-subrip", "text/vtt", "text/plain", "application/octet-stream",
        )
        private val JSON_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")
        private const val PAUSE_ALL_MEDIA_JS = """
            (function pause(doc) {
              doc.querySelectorAll('video, audio').forEach(function (m) { try { m.pause(); } catch (e) {} });
              doc.querySelectorAll('iframe').forEach(function (f) {
                try { if (f.contentDocument) pause(f.contentDocument); } catch (e) {}
              });
            })(document);
        """
    }
}
