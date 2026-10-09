package com.proudvocab.android

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Performs the renderer's `fetch()` calls for external services (Google Translate,
 * dictionary API …). The WebView itself is confined to the app origin, so every
 * outbound request goes through here.
 *
 * Rules: HTTPS only, a small method allow-list, no cookies (HttpURLConnection has
 * no cookie handler), bounded timeouts and bounded response size.
 */
class NetFetcher(private val io: ExecutorService) {
    private val active = ConcurrentHashMap<String, HttpURLConnection>()
    private val aborted = ConcurrentHashMap.newKeySet<String>()

    /** Runs [req] on the worker pool and hands the JSON result to [done] (worker thread). */
    fun fetch(req: JSONObject, done: (JSONObject) -> Unit) {
        io.execute { done(run(req.optString("id"), req)) }
    }

    fun abort(id: String) {
        val conn = active.remove(id) ?: return
        aborted.add(id)
        conn.disconnect()
    }

    private fun run(id: String, req: JSONObject): JSONObject {
        val url = try {
            URL(req.optString("url"))
        } catch (e: MalformedURLException) {
            return failure("Invalid URL")
        }
        if (url.protocol != "https") return failure("Only HTTPS requests are allowed")

        val method = req.optString("method", "GET").uppercase()
        if (method !in ALLOWED_METHODS) return failure("Method not allowed")

        val timeoutMs = req.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS).toInt()
        var conn: HttpURLConnection? = null
        try {
            val c = url.openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true

            req.optJSONObject("headers")?.let { headers ->
                for (key in headers.keys()) {
                    if (key.lowercase() in BLOCKED_HEADERS) continue
                    c.setRequestProperty(key, headers.optString(key))
                }
            }

            active[id] = c
            if (aborted.contains(id)) return failure("aborted")

            if (!req.isNull("body") && method != "GET" && method != "HEAD") {
                c.doOutput = true
                c.outputStream.use { it.write(req.optString("body").toByteArray(Charsets.UTF_8)) }
            }

            val status = c.responseCode
            val statusText = c.responseMessage.orEmpty()
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { readBounded(it) } ?: ByteArray(0)

            val headers = JSONObject()
            for ((name, values) in c.headerFields) {
                if (name != null && values.isNotEmpty()) headers.put(name, values.joinToString(", "))
            }
            return JSONObject()
                .put("ok", true)
                .put("status", status)
                .put("statusText", statusText)
                .put("headers", headers)
                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (e: IOException) {
            return failure(if (aborted.contains(id)) "aborted" else (e.message ?: "Network request failed"))
        } catch (e: IllegalArgumentException) {
            return failure(e.message ?: "Invalid request")
        } finally {
            active.remove(id)
            aborted.remove(id)
            conn?.disconnect()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_RESPONSE_BYTES) throw IOException("Response too large")
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private fun failure(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST")
        // Hop-by-hop and transport headers that the renderer must not control.
        val BLOCKED_HEADERS = setOf(
            "host", "connection", "content-length", "accept-encoding",
            "transfer-encoding", "upgrade", "expect", "te", "trailer", "cookie",
        )
    }
}
