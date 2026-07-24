package com.firebolt9907.tangentbrowser.state

import android.net.Uri
import java.net.URLEncoder

/**
 * A user-selectable search engine. Owns both the results URL (used by `normalizeUrl` when the typed
 * text isn't a URL) and the suggestion endpoint (used by [searchSuggestions]).
 *
 * Every [suggestUrl] here is deliberately an endpoint that returns the OpenSearch/"Firefox client"
 * JSON array shape `["query", ["s1", "s2", …]]`, so [searchSuggestions] can parse them all with one
 * code path.
 */
enum class SearchEngine(val label: String) {
    Google("Google") {
        override fun searchUrl(query: String) = "https://www.google.com/search?q=${enc(query)}"
        override fun suggestUrl(query: String) =
            "https://www.google.com/complete/search?client=firefox&q=${enc(query)}"
        override val resultsHostMatch = "google."
    },
    DuckDuckGo("DuckDuckGo") {
        override fun searchUrl(query: String) = "https://duckduckgo.com/?q=${enc(query)}"
        override fun suggestUrl(query: String) =
            "https://duckduckgo.com/ac/?type=list&q=${enc(query)}"
        override val resultsHostMatch = "duckduckgo."
    },
    Bing("Bing") {
        override fun searchUrl(query: String) = "https://www.bing.com/search?q=${enc(query)}"
        override fun suggestUrl(query: String) = "https://api.bing.com/osjson.aspx?query=${enc(query)}"
        override val resultsHostMatch = "bing."
    };

    abstract fun searchUrl(query: String): String
    abstract fun suggestUrl(query: String): String

    /** Substring that identifies this engine's results host (e.g. `google.` matches google.co.uk). */
    protected abstract val resultsHostMatch: String

    /**
     * If [url] is a results page for this engine — its host matches [resultsHostMatch] and it carries
     * a non-blank `q` query param — returns the decoded search terms; otherwise null. All three
     * engines use `q`, so one param name covers them.
     */
    fun searchTermsFrom(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains(resultsHostMatch)) return null
        val q = runCatching { uri.getQueryParameter("q") }.getOrNull()
        return q?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        val DEFAULT = Google

        /** Resolve a persisted enum name back to an engine, falling back to [DEFAULT]. */
        fun fromName(name: String?): SearchEngine =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        private fun enc(query: String): String = URLEncoder.encode(query, "UTF-8")
    }
}

/**
 * The address-bar label for [url]: the search terms when [url] is a results page for [engine] and
 * [showSearchTerms] is on, otherwise the compact host. Centralizes the "show the query instead of
 * google.com" behavior shared by the SafariBar VM and TangentRoot's visual/ghost bar states.
 */
fun addressBarLabel(url: String, engine: SearchEngine, showSearchTerms: Boolean): String {
    if (showSearchTerms) {
        engine.searchTermsFrom(url)?.let { return it }
    }
    return compactHostWithPort(url)
}
