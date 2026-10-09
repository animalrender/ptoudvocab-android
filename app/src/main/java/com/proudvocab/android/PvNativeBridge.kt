package com.proudvocab.android

import android.webkit.JavascriptInterface

/**
 * The object JavaScript sees as `window.PvNative` (see pv-android-bridge.js).
 *
 * Methods annotated with @JavascriptInterface run on a WebView background thread,
 * so they only hop to the UI thread and let [MainActivity] do the work.
 */
class PvNativeBridge(private val host: MainActivity) {

    @JavascriptInterface
    fun invoke(id: Int, channel: String, argsJson: String) {
        host.onBridgeInvoke(id, channel, argsJson)
    }

    @JavascriptInterface
    fun abort(id: String) {
        host.onBridgeAbort(id)
    }
}
