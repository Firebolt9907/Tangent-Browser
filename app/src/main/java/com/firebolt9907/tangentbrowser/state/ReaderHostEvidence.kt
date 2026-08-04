package com.firebolt9907.tangentbrowser.state

import android.content.Context
import androidx.core.content.edit
import java.net.URI
import org.json.JSONArray

/**
 * Which hosts have actually produced a reader-mode article, and which have failed to.
 *
 * This is what lets the data saver's most aggressive tier (cancelling *first-party* script and
 * stylesheet — see [DataSaverPolicy.AGGRESSIVE_TYPES]) be applied without the double-load the
 * obvious design implies. The intuitive flow is "block everything, try to extract an article, and
 * reload with the assets if extraction comes up empty" — but that pays a second full navigation on
 * every page reader can't handle, which on a web full of client-rendered apps is most of them.
 *
 * Deciding *before* navigating removes that cost entirely. A host only enters the aggressive tier
 * once we have positive evidence reader works there, from two sources:
 * - **Seeded** ([seed]) from the per-site reader rules asset, whose `match` hosts are by construction
 *   sites with a verified article container. Free, and covers the common article destinations from
 *   the first launch.
 * - **Learned** ([recordSuccess]) whenever extraction yields a substantial article.
 *
 * [recordFailure] is the other half and is why this can't be seed-only: a host that stops extracting
 * (a redesign, a paywall, a move to client rendering) is demoted immediately and permanently outranks
 * its seed entry, so a stale rule can't strand the user on a page stripped of the scripts it needed.
 */
class ReaderHostEvidence(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("tangent_reader_evidence", Context.MODE_PRIVATE)

    /** Hosts from the reader-rules asset. In-memory only, so a rules update is picked up as-is. */
    @Volatile private var seeded: Set<String> = emptySet()
    @Volatile private var seededFrom: String? = null

    /**
     * Merge the `match` hosts out of the compact reader-rules JSON (the same array
     * `ReaderRules.cached()` hands the extractor). Tolerant by design: rules are data that can be
     * updated remotely, so anything unparseable is skipped rather than thrown.
     *
     * Callable repeatedly and cheap when nothing changed, which is what it's for: the rules asset is
     * loaded asynchronously at startup and can be replaced by a signed remote update mid-session, so
     * the seed can't be a one-shot at construction — it re-runs only when the JSON actually differs.
     */
    fun seed(compactRulesJson: String) {
        if (compactRulesJson == seededFrom) return
        seededFrom = compactRulesJson
        val hosts = mutableSetOf<String>()
        runCatching {
            val rules = JSONArray(compactRulesJson)
            for (i in 0 until rules.length()) {
                when (val match = rules.optJSONObject(i)?.opt("match")) {
                    is String -> normalizeSiteKey(match)?.let(hosts::add)
                    is JSONArray -> for (j in 0 until match.length()) {
                        normalizeSiteKey(match.optString(j))?.let(hosts::add)
                    }
                }
            }
        }
        seeded = hosts
    }

    private var learned: Set<String>
        get() = prefs.getStringSet(KEY_LEARNED, emptySet())?.toSet().orEmpty()
        set(value) { prefs.edit { putStringSet(KEY_LEARNED, value) } }

    private var failed: Set<String>
        get() = prefs.getStringSet(KEY_FAILED, emptySet())?.toSet().orEmpty()
        set(value) { prefs.edit { putStringSet(KEY_FAILED, value) } }

    /** Hosts the aggressive tier may be applied to. Failure always wins over seed and success. */
    fun provenHosts(): Set<String> = (seeded + learned) - failed

    /** Reader extraction produced a real article on [url]'s host — promote it. */
    fun recordSuccess(url: String?) {
        val host = siteKeyOf(url) ?: return
        if (failed.contains(host)) failed = failed - host
        val current = learned
        if (current.contains(host)) return
        // Bounded: this is a browsing-history-shaped set, and it is pushed to the extension on every
        // policy change. Drop the oldest arbitrary entries rather than let it grow without limit —
        // losing one is only a missed optimization, and the host re-learns on the next visit.
        learned = if (current.size >= MAX_LEARNED) current.drop(1).toSet() + host else current + host
    }

    /** Reader extraction found nothing usable on [url]'s host — demote it, overriding any seed. */
    fun recordFailure(url: String?) {
        val host = siteKeyOf(url) ?: return
        if (learned.contains(host)) learned = learned - host
        val current = failed
        if (current.contains(host)) return
        failed = if (current.size >= MAX_FAILED) current.drop(1).toSet() + host else current + host
    }

    private companion object {
        const val KEY_LEARNED = "learnedHosts"
        const val KEY_FAILED = "failedHosts"
        const val MAX_LEARNED = 500
        const val MAX_FAILED = 500
    }
}

/**
 * The key per-site data-saver state is stored under: [url]'s host, lowercased and `www.`-stripped,
 * or null when it has none.
 *
 * Deliberately **not** named `hostOf` — this package already has an `internal fun hostOf(String)`
 * ([com.firebolt9907.tangentbrowser.state.hostOf]) whose contract is the opposite on the one detail
 * that matters here: it *preserves* `www.`. A nullable-parameter `hostOf` alongside it compiles fine
 * and reads fine, but overload resolution hands every non-null call site the other function, so
 * `www.nytimes.com` and `nytimes.com` silently become different sites. A distinct name is the only
 * thing that makes that impossible.
 */
internal fun siteKeyOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = runCatching { URI(url).host }.getOrNull() ?: return null
    return normalizeSiteKey(host)
}

/** Normalize a bare host (or a rules `match` entry, which may carry a leading `*.`) to a site key. */
internal fun normalizeSiteKey(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val host = raw.trim().lowercase().removePrefix("*.").removePrefix("www.").trimEnd('.')
    return host.takeIf { it.isNotBlank() && it.contains('.') }
}
