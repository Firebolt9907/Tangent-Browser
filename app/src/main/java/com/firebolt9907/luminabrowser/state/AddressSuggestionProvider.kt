package com.firebolt9907.luminabrowser.state

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
    byUrl.values.take(max).toList()
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
