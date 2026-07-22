package com.firebolt9907.luminabrowser.state

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.BookmarksStorage
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.concept.storage.SearchResult

/** What a single address-bar suggestion was sourced from — drives the row's leading icon. */
enum class AddressSuggestionKind { Search, History, Bookmark }

/**
 * One row in the address-bar suggestion list. For [AddressSuggestionKind.Search] the [url] is the
 * raw query text (it is run through `normalizeUrl` → search on submit); for the other kinds it is a
 * concrete page URL.
 */
data class AddressSuggestion(
    val title: String,
    val url: String,
    val kind: AddressSuggestionKind,
)

/**
 * Builds the local-history + bookmarks suggestions for [query]. Bookmarks rank ahead of history
 * (higher intent), results are de-duplicated by URL, and the list is capped at [max]. Returns an
 * empty list for a blank query. Runs on [Dispatchers.IO] because `getSuggestions` hits the Places
 * SQLite DB synchronously. The caller adds the trailing "search for this" row itself, since that one
 * is always present and depends only on the live query text.
 */
suspend fun addressSuggestions(
    query: String,
    history: HistoryStorage,
    bookmarks: BookmarksStorage,
    max: Int = 6,
): List<AddressSuggestion> = withContext(Dispatchers.IO) {
    val q = query.trim()
    if (q.isEmpty()) return@withContext emptyList()

    // `BookmarksStorage.searchBookmarks` has an inline-class signature that doesn't resolve from
    // Kotlin here, so mirror `BookmarksSheet`'s proven path: read the mobile tree and filter it
    // locally. Bookmark counts are small, so this is cheap.
    val ql = q.lowercase()
    val bookmarkRoot = bookmarks.getTree(BookmarkRoot.Mobile.id, recursive = true).getOrNull()
    val bookmarkMatches = flattenBookmarkItems(bookmarkRoot)
        .mapNotNull { node ->
            val url = node.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = node.title?.takeIf { it.isNotBlank() } ?: url
            if (!url.lowercase().contains(ql) && !title.lowercase().contains(ql)) {
                return@mapNotNull null
            }
            AddressSuggestion(title = title, url = url, kind = AddressSuggestionKind.Bookmark)
        }
        .take(max)

    val historyResults: List<SearchResult> = try {
        history.getSuggestions(q, max)
    } catch (_: Exception) {
        emptyList()
    }
    val historyMatches = historyResults.mapNotNull { result ->
        val url = result.url.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        AddressSuggestion(
            title = result.title?.takeIf { it.isNotBlank() } ?: url,
            url = url,
            kind = AddressSuggestionKind.History,
        )
    }

    val byUrl = LinkedHashMap<String, AddressSuggestion>()
    for (suggestion in bookmarkMatches + historyMatches) {
        val key = suggestion.url.trimEnd('/').lowercase()
        if (!byUrl.containsKey(key)) byUrl[key] = suggestion
    }
    // Re-rank host-aware. Places `getSuggestions` returns a plain frecency order over any URL that
    // *contains* the query as a substring, so typing "apple.com" can surface a deep, frequently-opened
    // `apps.apple.com/us/app/…` link above the actual `apple.com` root. `rankScore` rewards matches at
    // a host boundary and the registrable-domain root, and penalises deep paths, so the destination the
    // user obviously meant wins. `sortedByDescending` is stable, so ties keep the original frecency order.
    byUrl.values
        .sortedByDescending { rankScore(q, it) }
        .take(max)
        .toList()
}

/**
 * Host-aware relevance score for one suggestion against the typed [query]. Higher = better. The big
 * levers, in order: the suggestion host equals the typed host (exact domain), the typed text is a
 * prefix of the host, or the host is a subdomain of the typed domain; the root path ("/" or empty)
 * beats a deep path; and deep paths are penalised by segment count. Bookmarks get a small base boost
 * over history (higher intent), preserving the prior bookmarks-first ordering as a tiebreak.
 */
internal fun rankScore(query: String, s: AddressSuggestion): Int {
    var score = if (s.kind == AddressSuggestionKind.Bookmark) 60 else 0

    val q = query.trim().lowercase()
    // Candidate host the user seems to be typing: strip any scheme/path/query so "https://apple.com/x"
    // and "apple.com" both reduce to "apple.com". `www.` is normalised away on both sides.
    val qHost = q.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .removePrefix("www.")
    val queryIsHostLike = qHost.contains('.') && !qHost.contains(' ')

    val uri = runCatching { URI(s.url) }.getOrNull()
    val sHost = (uri?.host ?: hostOf(s.url) ?: "").lowercase().removePrefix("www.")
    val path = uri?.path.orEmpty()
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    val isRoot = segments.isEmpty()

    if (queryIsHostLike && sHost.isNotEmpty()) {
        score += when {
            sHost == qHost -> 1000
            sHost.startsWith(qHost) -> 700
            sHost.endsWith(".$qHost") -> 500 // e.g. query "apple.com" → "store.apple.com"
            qHost.endsWith(".$sHost") -> 400 // query is a subdomain of a broader host we have
            sHost.contains(qHost) -> 150
            else -> 0
        }
        if (isRoot) score += 300
        score -= (segments.size * 40).coerceAtMost(240)
    } else {
        // Non-domain query (a word, a partial name): reward host/title prefix matches and shallow pages.
        if (sHost.startsWith(q)) score += 400
        if (s.title.lowercase().startsWith(q)) score += 250
        if (isRoot) score += 120
        score -= (segments.size * 25).coerceAtMost(150)
    }
    return score
}

/** Depth-first collection of every bookmark ITEM under [root] (skips folders/separators). */
private fun flattenBookmarkItems(root: BookmarkNode?): List<BookmarkNode> {
    if (root == null) return emptyList()
    val out = mutableListOf<BookmarkNode>()
    fun walk(node: BookmarkNode) {
        if (node.type == BookmarkNodeType.ITEM && !node.url.isNullOrBlank()) out += node
        node.children?.forEach(::walk)
    }
    walk(root)
    return out
}
