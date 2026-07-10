package com.firebolt9907.luminabrowser.ui.reader

import android.content.Context
import android.util.Log
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runtime, per-site reader overrides — the "filters and includes" layer over the generic extractor.
 *
 * The heuristic in [ReaderExtractor] handles most articles, but some sites need a nudge:
 *  - `include` — CSS selector(s) for the real article container. The first visible match becomes
 *    the content root, bypassing the heuristic. Fixes pages that fail to trigger reader mode at all,
 *    extract only images and no text, or where the heuristic grabs a too-broad wrapper.
 *  - `strip`   — CSS selector(s) whose subtrees are excluded before extraction. Fixes pages that
 *    pull in promos / related-content / navigation text from outside the article body.
 *
 * Rules are DATA, not code: they are interpreted by the fixed extractor, which can only *select* and
 * *drop* DOM. A bad or hostile rule can therefore at worst yield a poor extraction — it can never run
 * arbitrary code. That bounded blast radius is what makes this layer safe to update out-of-band.
 *
 * ## Remote update channel (ship reader fixes without an app update)
 *
 * On top of the bundled `assets/reader_rules.json`, [refreshIfDue] fetches the **whole** ruleset from
 * [REMOTE_URL] (a public GitHub raw file) and caches it in `filesDir`. Two properties matter:
 *  - **Bulk fetch, not per-host.** The entire file is downloaded on a throttled schedule regardless
 *    of what the user browses, so the request path is constant. GitHub/Fastly see the client IP and a
 *    timestamp, never which sites are visited — matching happens locally after download.
 *  - **Anti-rollback.** A fetched ruleset is applied only if its `version` is strictly greater than
 *    the one already loaded, so a stale/replayed copy can't downgrade a fix.
 *
 * Fetching is gated on the [enabled] toggle, so turning the setting off means *no network at all* for
 * this feature (as well as no overrides), which is the privacy-correct behaviour.
 *
 * ### SECURITY SEAM — sign the payload before you trust it
 * TLS authenticates the transport to GitHub, NOT the bytes: anyone who can write to the repo (a
 * stolen token, a bad maintainer, the update agent's own credentials) can serve a malicious ruleset,
 * and there is no signature check below. The damage is bounded to a poor extraction (data, not code),
 * but before relying on this channel add an Ed25519 signature over the payload — verify it at the
 * marked point in [fetchAndApply], against a public key embedded in the APK — so repo write-access is
 * no longer equivalent to controlling every user's reader view.
 *
 * Bundled default drives behaviour until [init] runs (once, at app start); until then (and in unit
 * tests) [cached] returns "[]" — no overrides, identical to the pure heuristic.
 */
object ReaderRules {
    private const val TAG = "ReaderRules"
    private const val ASSET = "reader_rules.json"
    private const val REMOTE_FILE = "reader_rules_remote.json"

    /**
     * Public GitHub raw URL of the rules file. **Point this at your own repo.** It is fetched in full
     * (bulk), so the path is the same for every user and never leaks browsing history.
     */
    const val REMOTE_URL =
        "https://raw.githubusercontent.com/firebolt9907/lumina-reader-rules/main/reader_rules.json"

    private const val MIN_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000 /* 12h */
    private const val MAX_PAYLOAD_BYTES = 512 * 1024
    private const val NET_TIMEOUT_MS = 15_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedJson: String = "[]"

    @Volatile
    private var loadedVersion: Int = -1

    /**
     * User toggle ([GlobalPreferencesStore.readerSiteRules]). When off, [cached] reports no overrides
     * (pure heuristic) and [refreshIfDue] performs no network I/O — a privacy fallback and the opt-out
     * for the remote channel. Mirrored at app start and on every change.
     */
    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Compact JSON array string, safe to inline verbatim into the extractor script. Never null. */
    fun cached(): String = if (enabled) cachedJson else "[]"

    /**
     * Load the active ruleset: whichever of the bundled asset and the cached remote file has the
     * higher `version`. Failures on either source fall back to the other (or to "[]"). Cheap and
     * synchronous; call once at app start before the first page loads.
     */
    fun init(context: Context) {
        val bundled = readAsset(context)?.let { parseOrNull(it) }
        val remote = readRemoteFile(context)?.let { parseOrNull(it) }
        val chosen = when {
            bundled != null && remote != null -> if (remote.version >= bundled.version) remote else bundled
            remote != null -> remote
            else -> bundled
        }
        if (chosen != null) {
            cachedJson = chosen.compactJson
            loadedVersion = chosen.version
        }
    }

    /**
     * Kick off a throttled background refresh from [REMOTE_URL]. No-ops when the toggle is off or the
     * last check was under [MIN_CHECK_INTERVAL_MS] ago (unless [force]). Never blocks the caller and
     * never throws — any failure leaves the last-good ruleset in place.
     */
    fun refreshIfDue(context: Context, prefs: GlobalPreferencesStore, force: Boolean = false) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.readerRulesLastCheckMs < MIN_CHECK_INTERVAL_MS) return
        val appContext = context.applicationContext
        scope.launch {
            runCatching { fetchAndApply(appContext, prefs) }
                .onFailure { Log.d(TAG, "reader rules refresh failed: ${it.message}") }
        }
    }

    private fun fetchAndApply(context: Context, prefs: GlobalPreferencesStore) {
        prefs.readerRulesLastCheckMs = System.currentTimeMillis()
        val body = httpGet(REMOTE_URL) ?: return

        /* SECURITY SEAM: verify an Ed25519 signature over `body` against an APK-embedded public key
           HERE, and return on failure, before parsing/applying. See the class doc. */

        val parsed = parseOrNull(body) ?: return
        /* Anti-rollback: only ever move forward. */
        if (parsed.version <= loadedVersion) return
        runCatching { File(context.filesDir, REMOTE_FILE).writeText(body) }
        cachedJson = parsed.compactJson
        loadedVersion = parsed.version
        Log.d(TAG, "applied remote reader rules v${parsed.version}")
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = NET_TIMEOUT_MS
            readTimeout = NET_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val r = input.read(chunk)
                    if (r < 0) break
                    total += r
                    if (total > MAX_PAYLOAD_BYTES) return null
                    out.write(chunk, 0, r)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readAsset(context: Context): String? =
        runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()

    private fun readRemoteFile(context: Context): String? =
        runCatching { File(context.filesDir, REMOTE_FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    private fun parseOrNull(raw: String): Ruleset? = runCatching { parseRuleset(raw) }.getOrNull()

    internal data class Ruleset(val version: Int, val compactJson: String)

    /** Compact rules array only — the shape inlined into the script. Kept for existing callers/tests. */
    internal fun parse(raw: String): String = parseRuleset(raw).compactJson

    /**
     * Parse and sanitize a ruleset document into its `version` and the compact rules array the script
     * consumes. Visible for unit tests. Every selector is trimmed and rejected if it contains a
     * newline or "//": the extractor script is flattened to a single line before injection, where a
     * "//" would comment out the remainder (see [ReaderExtractor.buildReaderExtractorScript]). A rule
     * with no usable `match` is dropped. Throws on malformed JSON (callers wrap in [parseOrNull]).
     */
    internal fun parseRuleset(raw: String): Ruleset {
        val root = JSONObject(raw)
        val version = root.optInt("version", 0)
        val rulesIn = root.optJSONArray("rules") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until rulesIn.length()) {
            val ruleIn = rulesIn.optJSONObject(i) ?: continue
            val match = cleanSelectors(ruleIn.opt("match"))
            if (match.isEmpty()) continue
            val obj = JSONObject()
            obj.put("match", JSONArray(match))
            val include = cleanSelectors(ruleIn.opt("include"))
            if (include.isNotEmpty()) obj.put("include", JSONArray(include))
            val strip = cleanSelectors(ruleIn.opt("strip"))
            if (strip.isNotEmpty()) obj.put("strip", JSONArray(strip))
            out.put(obj)
        }
        return Ruleset(version, out.toString())
    }

    /** Coerce a JSON string or string[] into a list of non-empty, flatten-safe entries. */
    private fun cleanSelectors(value: Any?): List<String> {
        val items = when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it, "").takeIf { s -> s.isNotEmpty() } }
            is String -> listOf(value)
            else -> emptyList()
        }
        return items
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("//") && !it.contains('\n') && !it.contains('\r') }
    }
}
