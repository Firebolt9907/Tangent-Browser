package com.firebolt9907.luminabrowser.state

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight on-disk persistence for the open tab set, so tabs survive app restarts.
 *
 * Each tab is stored with the last time it was viewed; [load] drops any tab whose last
 * access is older than [maxAgeMillis] (72h auto-discard). State is written as a small JSON
 * file in the app's private storage — no engine session bytes are serialized, tabs are
 * re-created lazily from their URL on restore.
 */
class TabPersistence(context: Context) {

    private val file = File(context.filesDir, "lumina_tabs.json")

    data class PersistedTab(
        val url: String,
        val title: String,
        val lastAccess: Long,
        val selected: Boolean,
    )

    /** Read persisted tabs, dropping any not accessed within [maxAgeMillis] of [now]. */
    fun load(maxAgeMillis: Long, now: Long = System.currentTimeMillis()): List<PersistedTab> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lastAccess = obj.optLong("lastAccess", 0L)
                if (now - lastAccess > maxAgeMillis) return@mapNotNull null
                PersistedTab(
                    url = url,
                    title = obj.optString("title"),
                    lastAccess = lastAccess,
                    selected = obj.optBoolean("selected", false),
                )
            }
        }.getOrElse { emptyList() }
    }

    fun save(tabs: List<PersistedTab>) {
        runCatching {
            val array = JSONArray()
            tabs.forEach { tab ->
                array.put(
                    JSONObject()
                        .put("url", tab.url)
                        .put("title", tab.title)
                        .put("lastAccess", tab.lastAccess)
                        .put("selected", tab.selected),
                )
            }
            file.writeText(array.toString())
        }
    }
}
