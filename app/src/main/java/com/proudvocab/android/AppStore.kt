package com.proudvocab.android

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Persists the renderer's `chrome.storage`-style state (saved words, settings,
 * SRS stats …) as one JSON document in the app's private files directory.
 *
 * Writes go to a temp file first and are then renamed, so a crash mid-write
 * cannot leave a truncated store behind.
 */
class AppStore(context: Context) {
    private val file = File(context.filesDir, "pv-store.json")
    private val tmp = File(context.filesDir, "pv-store.json.tmp")
    private val lock = Any()

    fun load(): JSONObject = synchronized(lock) {
        if (!file.exists()) return emptyStore()
        try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // Keep the unreadable file for diagnosis instead of silently discarding it.
            file.renameTo(File(file.parentFile, "pv-store.corrupt.json"))
            emptyStore()
        }
    }

    fun save(data: JSONObject) = synchronized(lock) {
        tmp.writeText(data.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun emptyStore(): JSONObject = JSONObject()
        .put("local", JSONObject())
        .put("sync", JSONObject())
}
