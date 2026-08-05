package com.firebolt9907.tangentbrowser.state

import android.net.Uri
import java.net.URLEncoder

/**
 * A user-selectable search engine. Owns both the results URL (used by `normalizeUrl` when the typed
 * text isn't a URL) and the suggestion endpoint (used by [searchSuggestions]).
 *
 * Built-ins are the [BuiltIn] objects below; [Custom] carries a user-supplied URL template. Both are
 * persisted by [id], so a template change never needs a migration.
 *
 * Every built-in [suggestUrl] is deliberately an endpoint that returns the OpenSearch/"Firefox
 * client" JSON array shape `["query", ["s1", "s2", …]]`, so [searchSuggestions] can parse them all
 * with one code path. A `null` [suggestUrl] means the engine has no suggestion endpoint — the
 * address sheet then falls back to history and bookmarks only.
 */
sealed class SearchEngine {
    /** Stable key this engine is persisted under. */
    abstract val id: String

    abstract val label: String

    abstract fun searchUrl(query: String): String

    abstract fun suggestUrl(query: String): String?

    /** Substring that identifies this engine's results host (e.g. `google.` matches google.co.uk). */
    protected abstract val resultsHostMatch: String

    /** Query-string parameter carrying the search terms on a results page. */
    protected open val queryParam: String get() = "q"

    /**
     * If [url] is a results page for this engine — its host matches [resultsHostMatch] and it carries
     * a non-blank [queryParam] — returns the decoded search terms; otherwise null.
     */
    fun searchTermsFrom(url: String): String? {
        if (resultsHostMatch.isEmpty()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains(resultsHostMatch)) return null
        val q = runCatching { uri.getQueryParameter(queryParam) }.getOrNull()
        return q?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The engines offered in the picker, in order. [Custom] is added by the UI when configured. */
    sealed class BuiltIn(override val id: String, override val label: String) : SearchEngine() {
        data object Google : BuiltIn("Google", "Google") {
            override fun searchUrl(query: String) = "https://www.google.com/search?q=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://www.google.com/complete/search?client=firefox&q=${enc(query)}"
            override val resultsHostMatch = "google."
        }

        /**
         * Google with the "Web" filter (`udm=14`) — plain blue links, no AI Overview and no other
         * result blocks. It is an ordinary results URL parameter, not an account setting, so it
         * survives sign-out and applies per search.
         */
        data object GoogleWeb : BuiltIn("GoogleWeb", "Google (no AI)") {
            override fun searchUrl(query: String) =
                "https://www.google.com/search?q=${enc(query)}&udm=14"
            override fun suggestUrl(query: String) =
                "https://www.google.com/complete/search?client=firefox&q=${enc(query)}"
            override val resultsHostMatch = "google."
        }

        data object DuckDuckGo : BuiltIn("DuckDuckGo", "DuckDuckGo") {
            override fun searchUrl(query: String) = "https://duckduckgo.com/?q=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://duckduckgo.com/ac/?type=list&q=${enc(query)}"
            override val resultsHostMatch = "duckduckgo."
        }

        /** Startpage: Google results proxied anonymously. Results use `query`, not `q`. */
        data object Startpage : BuiltIn("Startpage", "Startpage") {
            override fun searchUrl(query: String) =
                "https://www.startpage.com/sp/search?query=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://www.startpage.com/suggestions?q=${enc(query)}&segment=startpage.udog&format=opensearch"
            override val resultsHostMatch = "startpage."
            override val queryParam = "query"
        }

        data object Brave : BuiltIn("Brave", "Brave Search") {
            override fun searchUrl(query: String) = "https://search.brave.com/search?q=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://search.brave.com/api/suggest?q=${enc(query)}&rich=false"
            override val resultsHostMatch = "search.brave.com"
        }

        /** Ecosia's suggest endpoint answers with `{"suggestions":[…]}`, not the OpenSearch pair. */
        data object Ecosia : BuiltIn("Ecosia", "Ecosia") {
            override fun searchUrl(query: String) = "https://www.ecosia.org/search?q=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://ac.ecosia.org/autocomplete?q=${enc(query)}&type=list"
            override val resultsHostMatch = "ecosia."
        }

        data object Qwant : BuiltIn("Qwant", "Qwant") {
            override fun searchUrl(query: String) = "https://www.qwant.com/?q=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://api.qwant.com/v3/suggest/?q=${enc(query)}&client=opensearch"
            override val resultsHostMatch = "qwant."
        }

        /** Yahoo carries the terms in `p`, and its suggest endpoint needs `output=fxjson`. */
        data object Yahoo : BuiltIn("Yahoo", "Yahoo") {
            override fun searchUrl(query: String) = "https://search.yahoo.com/search?p=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://search.yahoo.com/sugg/gossip/gossip-us-ura/?output=fxjson&command=${enc(query)}"
            override val resultsHostMatch = "search.yahoo."
            override val queryParam = "p"
        }

        /** Yandex carries the terms in `text`; `suggest-ff.cgi` is its Firefox-shaped endpoint. */
        data object Yandex : BuiltIn("Yandex", "Yandex") {
            override fun searchUrl(query: String) = "https://yandex.com/search/?text=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://suggest.yandex.com/suggest-ff.cgi?part=${enc(query)}"
            override val resultsHostMatch = "yandex."
            override val queryParam = "text"
        }

        /** Mojeek crawls its own index. It publishes no suggestion endpoint — hence the null. */
        data object Mojeek : BuiltIn("Mojeek", "Mojeek") {
            override fun searchUrl(query: String) = "https://www.mojeek.com/search?q=${enc(query)}"
            override fun suggestUrl(query: String): String? = null
            override val resultsHostMatch = "mojeek."
        }

        data object Bing : BuiltIn("Bing", "Bing") {
            override fun searchUrl(query: String) = "https://www.bing.com/search?q=${enc(query)}"
            override fun suggestUrl(query: String) =
                "https://api.bing.com/osjson.aspx?query=${enc(query)}"
            override val resultsHostMatch = "bing."
        }
    }

    /**
     * A user-supplied engine. [searchTemplate] is a full URL with [PLACEHOLDER] (or `{searchTerms}`)
     * standing in for the encoded query — the same convention Chrome and Firefox use for custom
     * engines. [suggestTemplate] is optional; without it the address sheet shows local suggestions
     * only, which is also the safe default for an endpoint whose response shape we can't know.
     */
    data class Custom(
        override val label: String,
        val searchTemplate: String,
        val suggestTemplate: String? = null,
    ) : SearchEngine() {
        override val id: String get() = ID

        override fun searchUrl(query: String) = fill(searchTemplate, query)

        override fun suggestUrl(query: String) = suggestTemplate?.let { fill(it, query) }

        /**
         * The template's own host, so "show search terms in the address bar" works for a custom
         * engine too. Empty when the template has no host, which disables the label rewrite.
         */
        override val resultsHostMatch: String
            get() = hostOf(searchTemplate).removePrefix("www.")

        /** The parameter the placeholder sits in, so a results page can be recognized. */
        override val queryParam: String
            get() = searchTemplate.substringAfter('?', "")
                .substringBefore('#')
                .split('&')
                .firstOrNull {
                    val value = it.substringAfter('=', "")
                    value.contains(PLACEHOLDER) || value.contains(PLACEHOLDER_ALT)
                }
                ?.substringBefore('=')
                ?.takeIf { it.isNotEmpty() }
                ?: "q"

        companion object {
            const val ID = "Custom"
        }
    }

    companion object {
        // Both are `by lazy`: the companion initializes with the SearchEngine class itself, which is
        // *before* the nested objects that extend it exist — an eager field here captures nulls.
        val DEFAULT: SearchEngine by lazy { BuiltIn.Google }

        /** Built-ins in picker order. */
        val builtIns: List<BuiltIn> by lazy {
            listOf(
                BuiltIn.Google,
                BuiltIn.GoogleWeb,
                BuiltIn.DuckDuckGo,
                BuiltIn.Brave,
                BuiltIn.Startpage,
                BuiltIn.Ecosia,
                BuiltIn.Qwant,
                BuiltIn.Mojeek,
                BuiltIn.Yahoo,
                BuiltIn.Yandex,
                BuiltIn.Bing,
            )
        }

        /**
         * The short list offered during onboarding. The full set lives in Settings — a first-run
         * page that scrolls past a dozen cards asks for a decision the user hasn't got yet.
         */
        val onboardingChoices: List<BuiltIn> by lazy {
            listOf(BuiltIn.Google, BuiltIn.GoogleWeb, BuiltIn.DuckDuckGo, BuiltIn.Brave, BuiltIn.Bing)
        }

        /** What a custom search/suggestion template substitutes the encoded query for. */
        const val PLACEHOLDER = "%s"

        /** Firefox spells the same thing this way; accepted so a template can be pasted from either. */
        internal const val PLACEHOLDER_ALT = "{searchTerms}"

        /**
         * Resolve a persisted [id] back to a built-in, falling back to [DEFAULT]. [Custom] is not
         * resolvable here (its templates live in preferences) — `GlobalPreferencesStore` builds it.
         */
        fun fromName(name: String?): SearchEngine =
            builtIns.firstOrNull { it.id == name } ?: DEFAULT

        /**
         * True when [template] is a usable custom template: an absolute http(s) URL carrying a
         * placeholder. Deliberately string-only (no `Uri`) — this gates the Save button and the
         * preference read, both of which must behave identically off-device in tests.
         */
        fun isValidTemplate(template: String): Boolean {
            val t = template.trim()
            if (!t.contains(PLACEHOLDER) && !t.contains(PLACEHOLDER_ALT)) return false
            val lower = t.lowercase()
            if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
            return hostOf(t).isNotEmpty()
        }

        /** The host of an absolute URL, lowercased; empty when there isn't one. */
        private fun hostOf(url: String): String =
            url.trim()
                .substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
                .lowercase()

        private fun fill(template: String, query: String): String =
            template.trim()
                .replace(PLACEHOLDER_ALT, PLACEHOLDER)
                .replace(PLACEHOLDER, enc(query))

        internal fun enc(query: String): String = URLEncoder.encode(query, "UTF-8")
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
