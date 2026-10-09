package com.proudvocab.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Serves every request made by the WebView.
 *
 *  - `https://appassets.androidplatform.net/<path>` → the bundled renderer in `assets/web/`.
 *  - `https://appassets.androidplatform.net/media/<token>` → a picked video, read through
 *    the content resolver with HTTP Range support so the `<video>` element can seek.
 *  - Any other origin is refused here, so the page cannot pull in remote scripts
 *    that could reach the native bridge.
 */
class AppRequestHandler(private val context: Context) {

    fun handle(request: WebResourceRequest): WebResourceResponse? {
        val uri: Uri = request.url
        if (uri.scheme != "https" || !uri.host.equals(APP_HOST, ignoreCase = true)) {
            return notFound()
        }
        val path = uri.path ?: "/"
        return if (path.startsWith(MEDIA_PREFIX)) {
            media(request, path.removePrefix(MEDIA_PREFIX))
        } else {
            asset(path)
        }
    }

    private fun asset(path: String): WebResourceResponse {
        val rel = path.removePrefix("/").ifEmpty { "index.html" }
        if (rel.contains("..") || rel.contains('\\') || rel.startsWith("/")) return notFound()
        val ext = rel.substringAfterLast('.', "").lowercase()
        return try {
            val stream = context.assets.open("web/$rel")
            WebResourceResponse(
                mimeFor(ext),
                if (ext in TEXT_EXTENSIONS) "UTF-8" else null,
                200,
                "OK",
                mapOf("Cache-Control" to "no-cache"),
                stream,
            )
        } catch (e: IOException) {
            notFound()
        }
    }

    private fun media(request: WebResourceRequest, token: String): WebResourceResponse {
        val virtualPath = VirtualPaths.decode(token) ?: return notFound()
        val contentUri = VirtualPaths.contentUriOf(virtualPath)?.let { Uri.parse(it) } ?: return notFound()
        val resolver = context.contentResolver

        val pfd: ParcelFileDescriptor? = try {
            resolver.openFileDescriptor(contentUri, "r")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) return notFound()

        try {
            val mime = resolver.getType(contentUri) ?: mimeFor(
                VirtualPaths.nameOf(virtualPath).substringAfterLast('.', "").lowercase(),
            )
            var total = pfd.statSize
            if (total <= 0) total = sizeOf(contentUri)

            val rangeHeader = request.requestHeaders.entries
                .firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value

            if (total < 0) {
                // Size unknown: stream the whole file without range support.
                val stream = FileInputStream(pfd.fileDescriptor)
                return WebResourceResponse(mime, null, 200, "OK", emptyMap(), RangeStream(pfd, stream, Long.MAX_VALUE))
            }

            if (rangeHeader == null) {
                val stream = FileInputStream(pfd.fileDescriptor)
                return WebResourceResponse(
                    mime, null, 200, "OK",
                    mapOf("Accept-Ranges" to "bytes", "Content-Length" to total.toString()),
                    RangeStream(pfd, stream, total),
                )
            }

            val match = RANGE_RE.find(rangeHeader)
                ?: return rangeNotSatisfiable(pfd, total)
            val (startText, endText) = match.destructured
            val start: Long
            val end: Long
            if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: return rangeNotSatisfiable(pfd, total)
                start = maxOf(0L, total - suffix)
                end = total - 1
            } else {
                start = startText.toLongOrNull() ?: return rangeNotSatisfiable(pfd, total)
                end = if (endText.isEmpty()) total - 1 else minOf(endText.toLong(), total - 1)
            }
            if (start >= total || start > end) return rangeNotSatisfiable(pfd, total)

            val stream = FileInputStream(pfd.fileDescriptor)
            stream.channel.position(start)
            val length = end - start + 1
            return WebResourceResponse(
                mime, null, 206, "Partial Content",
                mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes $start-$end/$total",
                    "Content-Length" to length.toString(),
                ),
                RangeStream(pfd, stream, length),
            )
        } catch (e: IOException) {
            pfd.close()
            return notFound()
        }
    }

    private fun rangeNotSatisfiable(pfd: ParcelFileDescriptor, total: Long): WebResourceResponse {
        pfd.close()
        return WebResourceResponse(
            "text/plain", "UTF-8", 416, "Range Not Satisfiable",
            mapOf("Content-Range" to "bytes */$total"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    private fun sizeOf(uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "txt", "srt", "ass", "ssa" -> "text/plain"
        "vtt" -> "text/vtt"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    /** Reads at most [remaining] bytes from [input] and releases the file descriptor on close. */
    private class RangeStream(
        private val pfd: ParcelFileDescriptor,
        private val input: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            try {
                input.close()
            } finally {
                pfd.close()
            }
        }
    }

    companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val APP_ORIGIN = "https://$APP_HOST"
        private const val MEDIA_PREFIX = "/media/"
        private val RANGE_RE = Regex("""bytes=(\d*)-(\d*)""")
        private val TEXT_EXTENSIONS = setOf("html", "htm", "js", "mjs", "css", "json", "svg", "txt", "srt", "vtt", "ass", "ssa")
    }
}
