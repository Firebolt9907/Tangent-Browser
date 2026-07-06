package com.firebolt9907.luminabrowser.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Fetches live query completions from [engine]'s suggestion endpoint. Every engine's [SearchEngine.suggestUrl]
 * returns the OpenSearch/"Firefox client" JSON array `["query",["s1","s2",…]]`, so this parses them
 * uniformly. Returns just the suggestion strings (excluding one identical to [query]); an empty list
 * on a blank query or any network/parse failure, so the address sheet silently falls back to local
 * suggestions.
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
    val url = engine.suggestUrl(q)
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
            val terms = JSONArray(body).optJSONArray(1) ?: return@use emptyList()
            (0 until terms.length())
                .map { terms.optString(it) }
                .filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) }
                .distinct()
                .take(max)
        }
    } catch (_: Exception) {
        emptyList()
    }
}
