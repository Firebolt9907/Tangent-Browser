package com.firebolt9907.luminabrowser.state

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
    },
    DuckDuckGo("DuckDuckGo") {
        override fun searchUrl(query: String) = "https://duckduckgo.com/?q=${enc(query)}"
        override fun suggestUrl(query: String) =
            "https://duckduckgo.com/ac/?type=list&q=${enc(query)}"
    },
    Bing("Bing") {
        override fun searchUrl(query: String) = "https://www.bing.com/search?q=${enc(query)}"
        override fun suggestUrl(query: String) = "https://api.bing.com/osjson.aspx?query=${enc(query)}"
    };

    abstract fun searchUrl(query: String): String
    abstract fun suggestUrl(query: String): String

    companion object {
        val DEFAULT = Google

        /** Resolve a persisted enum name back to an engine, falling back to [DEFAULT]. */
        fun fromName(name: String?): SearchEngine =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        private fun enc(query: String): String = URLEncoder.encode(query, "UTF-8")
    }
}
