package com.firebolt9907.luminabrowser.state

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SitePreferences(
    val topPaddingFix: Boolean = false,
    val zoomFactor: Float = 1.0f,
)

/**
 * Per-host (`compactHost`) zoom + top-padding-fix, persisted in [android.content.SharedPreferences].
 * [updates] emits the changed host so listeners can react even for inactive tabs.
 */
class SitePreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("lumina_site_prefs", Context.MODE_PRIVATE)
    private val _updates = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    fun get(host: String): SitePreferences {
        if (host.isEmpty()) return SitePreferences()
        val topFix = prefs.getBoolean("${host}__topPaddingFix", false)
        val zoom = prefs.getFloat("${host}__zoomFactor", 1.0f).coerceIn(0.5f, 2.0f)
        return SitePreferences(topPaddingFix = topFix, zoomFactor = zoom)
    }

    fun set(host: String, prefs: SitePreferences) {
        if (host.isEmpty()) return
        this.prefs.edit()
            .putBoolean("${host}__topPaddingFix", prefs.topPaddingFix)
            .putFloat("${host}__zoomFactor", prefs.zoomFactor)
            .apply()
        _updates.tryEmit(host)
    }
}

internal fun hostFromUrl(url: String): String {
    if (url.isBlank() || url == "about:blank") return ""
    val host = compactHost(url)
    return if (host == url) "" else host
}
