package com.proudvocab.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Storage Access Framework helpers. All reads go through the content resolver with
 * the grants the user gave in the picker, so the app never needs storage permissions.
 */
object DocumentAccess {
    /** Refuse to load subtitle/video blobs into memory beyond this size. */
    const val MAX_READ_BYTES = 64L * 1024 * 1024
    private const val MAX_LISTED_FILES = 2000

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "ogv", "avi", "wmv", "flv",
        "mpg", "mpeg", "ts", "m2ts", "3gp", "vob",
    )

    /** Keeps read access across restarts. Falls back to a transient grant when the provider refuses. */
    fun persist(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // The provider does not offer persistable grants; the file stays readable until the app restarts.
        } catch (e: IllegalArgumentException) {
            // Same as above for URIs that were not granted through the picker.
        }
    }

    fun displayName(context: Context, uri: Uri): String? =
        queryRow(context, uri, arrayOf(OpenableColumns.DISPLAY_NAME))?.get(0)

    /** Name of the folder picked with OPEN_DOCUMENT_TREE. */
    fun treeName(context: Context, treeUri: Uri): String? = try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        displayName(context, docUri)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun fileInfo(context: Context, virtualPath: String): JSONObject {
        val uri = contentUri(virtualPath) ?: return failure("bad path")
        val row = queryRow(context, uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            ?: return failure("missing")
        return JSONObject()
            .put("ok", true)
            .put("name", row[0] ?: VirtualPaths.nameOf(virtualPath))
            .put("size", row[1]?.toLongOrNull() ?: -1L)
    }

    fun readBase64(context: Context, virtualPath: String): JSONObject {
        val uri = contentUri(virtualPath) ?: return failure("bad path")
        val input = context.contentResolver.openInputStream(uri) ?: return failure("unavailable")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_READ_BYTES) return failure("file too large")
                out.write(chunk, 0, n)
            }
            return JSONObject()
                .put("ok", true)
                .put("base64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }
    }

    /** Lists the video files directly inside a folder picked with OPEN_DOCUMENT_TREE. */
    fun listVideos(context: Context, folderVirtualPath: String): JSONObject {
        val treeUriObj = contentUri(folderVirtualPath) ?: return failure("bad path")
        val folderId = try {
            DocumentsContract.getTreeDocumentId(treeUriObj)
        } catch (e: IllegalArgumentException) {
            return failure("not a folder")
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUriObj, folderId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val files = mutableListOf<Pair<String, String>>()
        try {
            context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && files.size < MAX_LISTED_FILES) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (!mime.startsWith("video/") && name.substringAfterLast('.', "").lowercase() !in VIDEO_EXTENSIONS) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUriObj, docId)
                    files += docUri.toString() to name
                }
            }
        } catch (e: SecurityException) {
            return failure("folder access revoked")
        }
        val list = JSONArray()
        files.sortedBy { it.second.lowercase() }.forEach { (uri, name) ->
            list.put(JSONObject().put("path", VirtualPaths.make(uri, name)).put("name", name))
        }
        return JSONObject().put("ok", true).put("files", list)
    }

    /** The content URI behind a virtual path, or null when the path is malformed. */
    private fun contentUri(virtualPath: String): Uri? =
        VirtualPaths.contentUriOf(virtualPath)?.let { Uri.parse(it) }

    private fun queryRow(context: Context, uri: Uri, columns: Array<String>): Array<String?>? = try {
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                Array(columns.size) { i -> if (cursor.isNull(i)) null else cursor.getString(i) }
            }
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun failure(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)
}
