package com.firebolt9907.tangentbrowser.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches live query completions from [engine]'s suggestion endpoint. Every built-in engine's
 * [SearchEngine.suggestUrl] returns the OpenSearch/"Firefox client" JSON array
 * `["query",["s1","s2",…]]`, so this parses them uniformly; a custom engine may point at an endpoint
 * that answers with a bare array of strings or an object of them instead, which [parseSuggestions]
 * also accepts. Returns just the suggestion strings (excluding one identical to [query]); an empty
 * list on a blank query, an engine with no suggestion endpoint, or any network/parse failure, so the
 * address sheet silently falls back to local suggestions.
 *
 * Cookies are omitted and a short timeout is used so a slow network never stalls the suggestion row.
 * Runs on [Dispatchers.IO] since [Client.fetch] blocks.
 */
suspend fun searchSuggestions(
    query: String,
    client: Client,
    engine: SearchEngine = SearchEngine.DEFAULT,
    max: Int = 5,
): List<String> = withContext(Dispatchers.IO) {
    val q = query.trim()
    if (q.isEmpty()) return@withContext emptyList()
    val url = engine.suggestUrl(q) ?: return@withContext emptyList()
    val request = Request(
        url = url,
        connectTimeout = 3L to TimeUnit.SECONDS,
        readTimeout = 3L to TimeUnit.SECONDS,
        cookiePolicy = Request.CookiePolicy.OMIT,
    )
    try {
        client.fetch(request).use { response ->
            if (response.status !in 200..299) return@use emptyList()
            val body = response.body.string(Charsets.UTF_8)
            parseSuggestions(body)
                .filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) }
                .distinct()
                .take(max)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Pulls suggestion strings out of the three shapes a suggestion endpoint answers with in practice:
 * the OpenSearch pair `["query",["s1",…]]`, a bare `["s1",…]`, and an object wrapping either under a
 * `suggestions`/`results` key (whose entries may themselves be objects carrying the text in
 * `phrase`/`text`/`suggestion`/`value`). Built-ins only ever use the first; the others exist so a
 * user-supplied custom endpoint has a fair chance of working without per-engine parsing code.
 */
internal fun parseSuggestions(body: String): List<String> {
    val trimmed = body.trim()
    val array = when {
        trimmed.startsWith("[") -> {
            val outer = JSONArray(trimmed)
            outer.optJSONArray(1) ?: outer
        }
        trimmed.startsWith("{") -> {
            val obj = JSONObject(trimmed)
            obj.optJSONArray("suggestions") ?: obj.optJSONArray("results") ?: return emptyList()
        }
        else -> return emptyList()
    }
    return (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i)?.let { entry ->
            SUGGESTION_TEXT_KEYS.firstNotNullOfOrNull { key ->
                entry.optString(key).takeIf { it.isNotBlank() }
            }
        } ?: array.optString(i).takeIf { it.isNotBlank() }
    }
}

private val SUGGESTION_TEXT_KEYS = listOf("phrase", "text", "suggestion", "value")
