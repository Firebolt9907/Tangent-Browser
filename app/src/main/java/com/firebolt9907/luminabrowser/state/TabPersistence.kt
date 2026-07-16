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
 * file in the app's private storage.
 *
 * [PersistedTab.engineStateJson] is an opaque serialized GeckoView `EngineSessionState`
 * (back/forward history stack, scroll position, form data). It is produced/consumed by
 * [GeckoTabRepository] — this class treats it as a JSON blob and never interprets it — so a
 * restored tab comes back exactly where the user left it instead of a fresh top-of-page reload.
 * Tabs with no captured engine state (never rendered) persist url+title only and load from URL.
 */
class TabPersistence(context: Context) {

    private val file = File(context.filesDir, "lumina_tabs.json")

    data class PersistedTab(
        val url: String,
        val title: String,
        val lastAccess: Long,
        val selected: Boolean,
        /** Serialized `EngineSessionState` JSON, or null when none was captured for this tab. */
        val engineStateJson: String? = null,
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
                    engineStateJson = obj.optString("engineState").takeIf { it.isNotBlank() },
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
                        .put("selected", tab.selected)
                        .apply { tab.engineStateJson?.let { put("engineState", it) } },
                )
            }
            // Write to a temp file then atomically rename, so a crash or process kill
            // mid-write can never leave a half-written (corrupt) tab state behind.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                // renameTo can fail across some filesystems; fall back to a direct copy.
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            // Best-effort cleanup of a stale temp file so it doesn't accumulate.
            runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
        }
    }
}
