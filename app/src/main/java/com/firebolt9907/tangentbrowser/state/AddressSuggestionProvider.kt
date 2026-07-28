package com.firebolt9907.tangentbrowser.state

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
enum class AddressSuggestionKind { Search, History, Bookmark, Tab, SyncedTab }

/**
 * One row in the address-bar suggestion list. For [AddressSuggestionKind.Search] the [url] is the
 * raw query text (it is run through `normalizeUrl` → search on submit); for the other kinds it is a
 * concrete page URL. [tabId] is set only for [AddressSuggestionKind.Tab] rows (an already-open tab):
 * committing that row switches to the existing tab instead of navigating. [detail] is an optional
 * subtitle override — used by [AddressSuggestionKind.SyncedTab] to show the source device name
 * ("From <device>") instead of the raw URL.
 */
data class AddressSuggestion(
    val title: String,
    val url: String,
    val kind: AddressSuggestionKind,
    val tabId: String? = null,
    val detail: String? = null,
)

/** Minimal open-tab descriptor fed to [openTabSuggestions] — decouples ranking from GeckoView types. */
data class OpenTabRef(val id: String, val url: String, val title: String)

/**
 * A tab open on another Firefox-synced device, fed to [syncedTabSuggestions]. [deviceName] is shown
 * as the row's subtitle so the user can tell which of their devices the tab came from.
 */
data class RemoteTabRef(val url: String, val title: String, val deviceName: String)

/**
 * Open tabs matching [query], as switch-to-tab suggestions ranked ahead of history (an open tab is a
 * stronger destination than a stale visit). Matches on host/title/URL substring, excludes the current
 * tab and blank/internal pages, de-duplicates by [canonicalUrlKey], and ranks with the same host-aware
 * [rankScore]. Pure/synchronous so it is unit-testable without GeckoView.
 */
fun openTabSuggestions(
    query: String,
    tabs: List<OpenTabRef>,
    currentTabId: String?,
    max: Int = 3,
): List<AddressSuggestion> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val seen = HashSet<String>()
    return tabs.asSequence()
        .filter { it.id != currentTabId }
        .filter { it.url.isNotBlank() && !it.url.startsWith("about:") }
        .filter { it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
        .map {
            AddressSuggestion(
                title = it.title.ifBlank { it.url },
                url = it.url,
                kind = AddressSuggestionKind.Tab,
                tabId = it.id,
            )
        }
        .filter { seen.add(canonicalUrlKey(it.url)) }
        .sortedByDescending { rankScore(q, it) }
        .take(max)
        .toList()
}

/**
 * Tabs open on other synced devices matching [query], offered as navigable suggestions. Unlike
 * [openTabSuggestions] these carry no [AddressSuggestion.tabId] — the tab lives on another device, so
 * committing the row navigates to its URL locally. Matches on host/title/URL substring, de-duplicates
 * by [canonicalUrlKey] (keeping the first/most-recent device that has the page), skips blank/internal
 * pages, and ranks with the shared host-aware [rankScore]. Pure/synchronous so it stays unit-testable
 * without GeckoView or a live account.
 */
fun syncedTabSuggestions(
    query: String,
    tabs: List<RemoteTabRef>,
    max: Int = 3,
): List<AddressSuggestion> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val seen = HashSet<String>()
    return tabs.asSequence()
        .filter { it.url.isNotBlank() && !it.url.startsWith("about:") }
        .filter { it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
        .map {
            AddressSuggestion(
                title = it.title.ifBlank { it.url },
                url = it.url,
                kind = AddressSuggestionKind.SyncedTab,
                detail = it.deviceName,
            )
        }
        .filter { seen.add(canonicalUrlKey(it.url)) }
        .sortedByDescending { rankScore(q, it) }
        .take(max)
        .toList()
}

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
        val key = canonicalUrlKey(suggestion.url)
        val existing = byUrl[key]
        when {
            existing == null -> byUrl[key] = suggestion
            // Same canonical page seen twice (e.g. http:// and https://, www and bare): keep the
            // more trustworthy variant — an https URL over a plain-http one — without reordering.
            existing.url.startsWith("http://") && suggestion.url.startsWith("https://") ->
                byUrl[key] = suggestion.copy(title = existing.title)
        }
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
 * Canonical de-duplication key for a page URL: drops the scheme, a leading `www.`, and any trailing
 * slash, and lowercases. So `http://apple.com/`, `https://apple.com`, and `https://www.apple.com/`
 * all collapse to `apple.com`, while distinct paths (`apple.com/mac`) stay separate. Shared by the
 * provider's local dedup and the UI's row merge so a synthesised domain row never duplicates a
 * history row for the same page.
 */
internal fun canonicalUrlKey(url: String): String {
    val trimmed = url.trim()
    val noScheme = trimmed.substringAfter("://", trimmed)
    return noScheme.removePrefix("www.").trimEnd('/').lowercase()
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
