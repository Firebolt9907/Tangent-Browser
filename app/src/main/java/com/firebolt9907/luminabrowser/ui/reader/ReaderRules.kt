package com.firebolt9907.luminabrowser.ui.reader

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

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
 * arbitrary code. That is exactly what makes this layer safe to update out-of-band (and safe for an
 * autonomous agent to propose): the blast radius is one site's reader view, not code execution on the
 * device. If/when rules are fetched remotely rather than bundled, verify a signature over the payload
 * before trusting it — TLS to a host (even a repo you own) authenticates the transport, not the bytes.
 *
 * Host matching happens inside the injected script against `location.hostname`, so the whole compact
 * ruleset is inlined once and Kotlin needs no per-URL plumbing. A bare `match` of "example.com" also
 * matches its subdomains; a leading "*." is stripped and treated the same.
 *
 * Bundled default: `assets/reader_rules.json`. [init] must run once at app start; until then (and in
 * unit tests) [cached] returns "[]" — no overrides, behaviour identical to the pure heuristic.
 */
object ReaderRules {
    private const val TAG = "ReaderRules"
    private const val ASSET = "reader_rules.json"

    @Volatile
    private var cachedJson: String = "[]"

    /**
     * User toggle ([GlobalPreferencesStore.readerSiteRules]). When off, [cached] reports no overrides
     * so extraction runs the pure heuristic — a privacy fallback (and the opt-out for any future
     * remote rules channel, which would otherwise reveal visited hosts). Mirrored here at app start
     * and whenever the setting changes, so a live toggle takes effect on the next extraction.
     */
    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Compact JSON array string, safe to inline verbatim into the extractor script. Never null. */
    fun cached(): String = if (enabled) cachedJson else "[]"

    /** Load and cache the bundled rules. Safe to call more than once; failures leave "[]" in place. */
    fun init(context: Context) {
        val raw = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val parsed = runCatching { parse(raw) }.getOrNull()
        if (parsed != null) {
            cachedJson = parsed
        } else {
            Log.w(TAG, "reader_rules.json failed to parse; continuing with no overrides")
        }
    }

    /**
     * Parse and sanitize the bundled rules into the compact array the script consumes. Visible for
     * unit tests. Every selector is trimmed and rejected if it contains a newline or "//": the
     * extractor script is flattened to a single line before injection, where a "//" would comment out
     * the remainder (see [ReaderExtractor.buildReaderExtractorScript]). A rule with no usable `match`
     * is dropped entirely.
     */
    internal fun parse(raw: String): String {
        val root = JSONObject(raw)
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
        return out.toString()
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
