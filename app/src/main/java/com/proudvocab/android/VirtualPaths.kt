package com.proudvocab.android

import android.util.Base64

/**
 * The renderer works with path-like strings, but Android never exposes real file
 * paths for files picked through the Storage Access Framework. Every picked file
 * therefore gets a *virtual path*:
 *
 *     /<base64url(contentUri)>/<display name>
 *
 * The last segment is the human-readable file name. The first segment is a
 * deterministic token that carries the content URI, so the path keeps working
 * after the app restarts (the persistable URI grant is still valid).
 *
 * The renderer turns a virtual path into a media URL with
 * `base64url(virtualPath)` (see pv-android-bridge.js), which the asset handler
 * decodes in two steps.
 */
object VirtualPaths {
    private const val ENCODE_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private const val DECODE_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP
    private val UNSAFE_NAME_CHARS = Regex("[/\\\\\\p{Cntrl}]")

    fun encode(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), ENCODE_FLAGS)

    fun decode(token: String): String? = try {
        String(Base64.decode(token, DECODE_FLAGS), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Builds the virtual path for a content URI and a display name. */
    fun make(contentUri: String, displayName: String?): String {
        val safeName = (displayName ?: "")
            .replace(UNSAFE_NAME_CHARS, "_")
            .trim()
            .ifEmpty { "file" }
        return "/" + encode(contentUri) + "/" + safeName
    }

    /** The content URI hidden in the first segment, or null if the path is malformed. */
    fun contentUriOf(virtualPath: String): String? {
        val parts = virtualPath.split('/')
        if (parts.size < 3 || parts[0].isNotEmpty() || parts[1].isEmpty()) return null
        return decode(parts[1])
    }

    fun nameOf(virtualPath: String): String = virtualPath.substringAfterLast('/')
}
