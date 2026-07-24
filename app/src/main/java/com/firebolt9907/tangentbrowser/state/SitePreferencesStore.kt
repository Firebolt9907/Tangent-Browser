package com.firebolt9907.tangentbrowser.state

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SitePreferences(
    val topPaddingFix: Boolean = false,
    val zoomFactor: Float = 1.0f,
    val desktopMode: Boolean = false,
    val darkMode: SiteDarkMode = SiteDarkMode.Default,
)

/**
 * Per-site override for the global "dark mode for every website" feature. [Default] follows the
 * global toggle; [On]/[Off] pin dark mode for this host regardless — the escape hatch for a site that
 * looks bad inverted, or one the user wants darkened even with the global switch off.
 */
enum class SiteDarkMode {
    Default, On, Off;

    /** Resolves the effective dark-mode state for this host given the [globalEnabled] toggle. */
    fun resolve(globalEnabled: Boolean): Boolean = when (this) {
        Default -> globalEnabled
        On -> true
        Off -> false
    }

    companion object {
        fun fromName(name: String?): SiteDarkMode =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * Per-host (`compactHost`) zoom + top-padding-fix, persisted in [android.content.SharedPreferences].
 * [updates] emits the changed host so listeners can react even for inactive tabs.
 */
class SitePreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("tangent_site_prefs", Context.MODE_PRIVATE)
    private val _updates = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    fun get(host: String): SitePreferences {
        if (host.isEmpty()) return SitePreferences()
        val topFix = prefs.getBoolean("${host}__topPaddingFix", false)
        val zoom = prefs.getFloat("${host}__zoomFactor", 1.0f).coerceIn(0.5f, 2.0f)
        val desktop = prefs.getBoolean("${host}__desktopMode", false)
        val dark = SiteDarkMode.fromName(prefs.getString("${host}__darkMode", null))
        return SitePreferences(topPaddingFix = topFix, zoomFactor = zoom, desktopMode = desktop, darkMode = dark)
    }

    fun set(host: String, prefs: SitePreferences) {
        if (host.isEmpty()) return
        this.prefs.edit {
            putBoolean("${host}__topPaddingFix", prefs.topPaddingFix)
            putFloat("${host}__zoomFactor", prefs.zoomFactor)
            putBoolean("${host}__desktopMode", prefs.desktopMode)
            putString("${host}__darkMode", prefs.darkMode.name)
        }
        _updates.tryEmit(host)
    }

    /**
     * Whether reader mode should auto-engage for this host. Set when the user turns reader on/off
     * for a site so that future pages on the same domain open straight into reader view.
     */
    fun isReaderPreferred(host: String): Boolean {
        if (host.isEmpty()) return false
        return prefs.getBoolean("${host}__readerPreferred", false)
    }

    fun setReaderPreferred(host: String, value: Boolean) {
        if (host.isEmpty()) return
        prefs.edit { putBoolean("${host}__readerPreferred", value) }
        _updates.tryEmit(host)
    }

    /**
     * Whether the user has explicitly opted *out* of reader mode for this host. Reader auto-engages
     * on every eligible page by default; turning reader off for a site sets this so we stop
     * auto-engaging there. Cleared when the user turns reader back on for the host.
     */
    fun isReaderAutoDisabled(host: String): Boolean {
        if (host.isEmpty()) return false
        return prefs.getBoolean("${host}__readerAutoDisabled", false)
    }

    fun setReaderAutoDisabled(host: String, value: Boolean) {
        if (host.isEmpty()) return
        prefs.edit { putBoolean("${host}__readerAutoDisabled", value) }
        _updates.tryEmit(host)
    }
}

internal fun hostFromUrl(url: String): String {
    if (url.isBlank() || url == "about:blank") return ""
    val host = compactHost(url)
    return if (host == url) "" else host
}
