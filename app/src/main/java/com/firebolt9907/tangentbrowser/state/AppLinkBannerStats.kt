package com.firebolt9907.tangentbrowser.state

import android.content.Context
import androidx.core.content.edit

/**
 * Per-host engagement stats for the passive "Open in app" suggestion banner, plus the host blacklist
 * it feeds. Tracks, per website host, how often the user tapped **Open** vs **dismissed** (X / swipe)
 * the suggestion. Once a host's dismissals cross [MIN_DISMISS] **and** its rejection rate crosses
 * [MIN_REJECT_RATE], the UI offers a one-time popup to add that host to the blacklist (after which the
 * passive suggestion is never shown for it again).
 *
 * Backed by its own SharedPreferences file. Keyed entries per host (`o:`/`d:`/`p:` prefixes) keep the
 * store trivially small; the blacklist is a single string set.
 */
class AppLinkBannerStats(context: Context) {
    private val prefs = context.getSharedPreferences("tangent_applink_stats", Context.MODE_PRIVATE)

    /** Canonical host key: trimmed, lowercased, `www.`-stripped. Callers that compare a host against
     *  [blacklist] must normalize it the same way. */
    fun normalizeHost(host: String) = host.trim().lowercase().removePrefix("www.")

    private fun norm(host: String) = normalizeHost(host)

    fun openCount(host: String): Int = prefs.getInt("o:${norm(host)}", 0)
    fun dismissCount(host: String): Int = prefs.getInt("d:${norm(host)}", 0)
    private fun wasPrompted(host: String): Boolean = prefs.getBoolean("p:${norm(host)}", false)

    /** Record that the user acted on (tapped Open for) [host]'s suggestion. */
    fun recordOpen(host: String) {
        val h = norm(host)
        if (h.isEmpty()) return
        prefs.edit { putInt("o:$h", prefs.getInt("o:$h", 0) + 1) }
    }

    /**
     * Record a dismissal (X / swipe) of [host]'s suggestion. Returns true exactly once per host, when
     * this dismissal is the one that pushes the host over both thresholds — the caller then shows the
     * "add to blacklist?" popup. Returns false if already prompted, already blacklisted, or the
     * thresholds aren't met yet.
     */
    fun recordDismiss(host: String): Boolean {
        val h = norm(host)
        if (h.isEmpty()) return false
        val dismisses = prefs.getInt("d:$h", 0) + 1
        prefs.edit { putInt("d:$h", dismisses) }
        if (wasPrompted(h) || isBlacklisted(h)) return false
        val total = dismisses + prefs.getInt("o:$h", 0)
        val rejectRate = if (total == 0) 0.0 else dismisses.toDouble() / total
        return dismisses >= MIN_DISMISS && rejectRate >= MIN_REJECT_RATE
    }

    /** Mark that the blacklist popup has been shown for [host] so it's never re-offered. */
    fun markPrompted(host: String) {
        val h = norm(host)
        if (h.isEmpty()) return
        prefs.edit { putBoolean("p:$h", true) }
    }

    fun blacklist(): Set<String> =
        prefs.getStringSet("blacklist", emptySet())?.toSet().orEmpty()

    fun isBlacklisted(host: String): Boolean = norm(host) in blacklist()

    fun addToBlacklist(host: String) {
        val h = norm(host)
        if (h.isEmpty()) return
        prefs.edit {
            putStringSet("blacklist", blacklist() + h)
            putBoolean("p:$h", true)
        }
    }

    companion object {
        const val MIN_DISMISS = 5
        const val MIN_REJECT_RATE = 0.8
    }
}
