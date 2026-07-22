package com.firebolt9907.luminabrowser.ui.chrome

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.AddressSuggestion
import com.firebolt9907.luminabrowser.ui.components.Favicon
import com.firebolt9907.luminabrowser.state.AddressSuggestionKind
import com.firebolt9907.luminabrowser.state.SearchEngine
import com.firebolt9907.luminabrowser.state.addressSuggestions
import com.firebolt9907.luminabrowser.state.canonicalUrlKey
import com.firebolt9907.luminabrowser.state.compactHost
import com.firebolt9907.luminabrowser.state.hostOf
import com.firebolt9907.luminabrowser.state.looksLikeUrlInput
import com.firebolt9907.luminabrowser.state.normalizeUrl
import com.firebolt9907.luminabrowser.state.searchSuggestions
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.BookmarksStorage
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient

/**
 * iOS-style address sheet shown while the bar is in editing mode.
 *
 * Presentation:
 *  - An opaque backdrop fully hides the live engine; a [backgroundSnapshot] of the current page
 *    scales down over it like an iOS card, leaving the page's very top peeking below the status bar.
 *  - A rounded sheet (suggestions list) sits above the keyboard. The live editing pill (rendered by
 *    the bottom bar) floats above the sheet, pinned to the keyboard top by its own `imePadding`.
 *  - Dragging anywhere on the sheet down translates the whole sheet 1:1 with the finger so it slides
 *    all the way off-screen (no fade). In parallel the keyboard is driven down frame-for-frame via
 *    [ImeSheetController] (API 30+); the editing pill rides the collapsing keyboard down (via
 *    `imePadding`, which DOES track manual control here) and, once the keyboard is fully collapsed,
 *    comes to rest as the address-bar pill. Releasing past threshold dismisses; otherwise it
 *    settles back open.
 *
 * Note: the sheet positions itself with an explicit `kbHeightPx` bottom inset rather than
 * `imePadding`, because it must *translate* off-screen (top edge and all), whereas `imePadding`
 * would only grow/shrink the bottom edge.
 */
@Composable
fun AddressSheet(
    query: String,
    pageUrl: String,
    visible: Boolean,
    statusBarTopPad: Dp,
    bottomInset: Dp,
    backgroundSnapshot: Bitmap?,
    historyStorage: HistoryStorage,
    bookmarksStorage: BookmarksStorage,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    // Reports the currently highlighted top suggestion (the row pressing Go/Enter will commit), or
    // null when there is no actionable suggestion. Lets the editing field submit the autoselected
    // result the way desktop browsers do, and inline-complete a matching URL into the field.
    onTopSuggestionChange: (AddressSuggestion?) -> Unit = {},
    onPreconnect: (String) -> Unit = {},
    onPreloadPage: (String) -> Unit = {},
    searchSuggestionsEnabled: Boolean = true,
    searchEngine: SearchEngine = SearchEngine.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val imeController = rememberImeSheetController()

    // `reveal` drives entry/exit slide when not dragging. `dragging` is true during a drag and its
    // release animation; `rawDragPx` is the finger's downward travel (drives the sheet's slide).
    val reveal = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var rawDragPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    // Keyboard height: captured live from WindowInsets while not dragging (the controller freezes it
    // during manual control). Used as the sheet's resting bottom inset and the drag/dismiss metric.
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    var kbHeightPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(imeBottomPx, dragging) {
        // Capture the peak (keyboard height); don't let it shrink while the keyboard is collapsing.
        if (!dragging && imeBottomPx > kbHeightPx) kbHeightPx = imeBottomPx
    }

    // Note: we intentionally do NOT auto-dismiss the sheet when the keyboard collapses. Pressing
    // Back while editing hides the IME (the IME window consumes that first Back in onKeyPreIme,
    // before it reaches the app) and leaves the suggestions sheet open — the standard Android
    // two-stage pattern. A second Back then closes the sheet via LuminaRoot's editing BackHandler.
    val fallbackTravelPx = with(density) { 320.dp.toPx() }
    val travelPx = if (kbHeightPx > 0f) kbHeightPx else fallbackTravelPx

    LaunchedEffect(visible) {
        if (visible) {
            reveal.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        } else {
            dragging = false
            rawDragPx = 0f
            reveal.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
    }

    if (!visible && reveal.value <= 0.001f && !dragging) return

    // Backdrop fraction (1 = sheet open / page scaled down, 0 = dismissed) and the whole-sheet
    // vertical offset are both read lazily inside graphics layers below, so the per-frame drag
    // updates of `rawDragPx`/`reveal` invalidate only the render layers — not this composable.
    fun backdropFraction(): Float = if (dragging) {
        (1f - (rawDragPx / travelPx)).coerceIn(0f, 1f)
    } else {
        reveal.value
    }
    fun sheetOffset(): Float = if (dragging) {
        rawDragPx
    } else {
        (1f - reveal.value) * (containerHeightPx.takeIf { it > 0f } ?: travelPx)
    }

    // Suggestions: skip while the field still holds the untouched current-page URL (Safari-style).
    val trimmed = query.trim()
    val rowsActive = visible && trimmed.isNotEmpty() && trimmed != pageUrl.trim()
    val suggestClient = remember { HttpURLConnectionClient() }
    var localMatches by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var searchTerms by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(rowsActive, trimmed) {
        if (!rowsActive) {
            localMatches = emptyList()
            searchTerms = emptyList()
            return@LaunchedEffect
        }
        delay(120)
        // Local (history/bookmark) and remote (search-engine) suggestions in parallel; each keystroke
        // cancels the prior fetch via this LaunchedEffect's key. Remote suggestions are skipped
        // entirely when the user has turned them off (no keystrokes leave the device).
        val local = async { addressSuggestions(trimmed, historyStorage, bookmarksStorage, max = 4) }
        val remote = async {
            if (searchSuggestionsEnabled) {
                searchSuggestions(trimmed, suggestClient, searchEngine)
            } else {
                emptyList()
            }
        }
        localMatches = local.await()
        searchTerms = remote.await()
        // Pre-warm connections to the likeliest destinations (the top concrete-URL suggestions) so
        // tapping one lands on an already-open connection. Search-query rows have no origin, so the
        // preconnector's URL check ignores them.
        localMatches.take(2).forEach { onPreconnect(it.url) }
        // Go further for the single top hit: actually preload the page into cache so tapping it is
        // near-instant. Gated by the caller (preload policy) since this spends data. Only concrete
        // URL suggestions are preloadable — a raw search query isn't a page.
        localMatches.firstOrNull()?.let { onPreloadPage(it.url) }
    }
    val rows = if (rowsActive) {
        buildSuggestionRows(localMatches, searchTerms, trimmed)
    } else {
        emptyList()
    }

    // Publish the autoselected top row so Go/Enter commits it (like most browsers), and so the
    // editing field can inline-complete a matching URL. `onSubmit`'s normalizeUrl re-decides
    // URL-vs-search, so a highlighted history URL navigates and a highlighted query searches.
    val topRow = if (visible) rows.firstOrNull() else null
    LaunchedEffect(topRow) { onTopSuggestionChange(topRow) }

    // Paste & Go (Safari/Arc-style): while the field is empty or still holds the untouched current
    // URL, offer the clipboard as a one-tap destination. We only probe the clip *description* for a
    // text MIME type here — the content itself is read lazily on tap, so opening the address bar
    // never triggers Android's clipboard-access notification.
    val context = LocalContext.current
    val systemClipboard = remember(context) {
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }
    var pasteAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(visible, rowsActive) {
        pasteAvailable = visible && !rowsActive && run {
            val clip = systemClipboard ?: return@run false
            clip.hasPrimaryClip() && (clip.primaryClipDescription?.let {
                it.hasMimeType("text/plain") || it.hasMimeType("text/html")
            } ?: false)
        }
    }

    fun endDrag(velocityY: Float) {
        val dismiss = rawDragPx > travelPx * 0.5f || velocityY > 2200f
        // Ride the real keyboard to its resting state (preserves the frame-locked feel on release).
        if (imeController.isSupported && imeController.isReady) {
            imeController.animateToFinish(show = !dismiss) {}
        }
        scope.launch {
            val target = if (dismiss) {
                (containerHeightPx.takeIf { it > 0f } ?: (travelPx * 3f))
            } else {
                0f
            }
            val anim = Animatable(rawDragPx)
            anim.animateTo(target, tween(200, easing = LinearEasing)) { rawDragPx = value }
            // Pin the post-drag resting state BEFORE clearing `dragging` so the offset doesn't snap:
            // dismissed → keep it off-screen (reveal 0); settled → keep it open (reveal 1).
            reveal.snapTo(if (dismiss) 0f else 1f)
            dragging = false
            rawDragPx = 0f
            if (dismiss) onDismiss()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerHeightPx = it.height.toFloat() },
    ) {
        // ---- Backdrop: opaque cover (engine never shows) + scaled page card ----
        ScaledPageBackdrop(
            snapshot = backgroundSnapshot,
            fraction = ::backdropFraction,
            statusBarTopPad = statusBarTopPad,
            onTap = onDismiss,
        )

        // ---- The sheet ----
        Surface(
            // The Surface fills all the way to the screen bottom (its background extends behind the
            // opaque keyboard) so that as it translates down its painted edge never lifts above the
            // keyboard top and reveals the backdrop behind the pill. The keyboard clearance lives on
            // the content below instead.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(top = statusBarTopPad + 28.dp)
                .fillMaxSize()
                .graphicsLayer { translationY = sheetOffset() },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 12.dp,
        ) {
            // The whole sheet is a drag surface — taps still reach the rows (drag != tap).
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(visible) {
                        if (!visible) return@pointerInput
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                rawDragPx = 0f
                                dragging = true
                                if (imeController.isSupported) {
                                    imeController.startControlRequest {}
                                }
                            },
                            onVerticalDrag = { change, dy ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                rawDragPx = (rawDragPx + dy).coerceAtLeast(0f)
                                // Drive the keyboard down frame-for-frame; the pill rides it via its
                                // own imePadding. The sheet itself slides via rawDragPx above.
                                if (imeController.isSupported && imeController.isActive) {
                                    imeController.insetByUp(-dy)
                                }
                                change.consume()
                            },
                            onDragEnd = { endDrag(tracker.calculateVelocity().y) },
                            onDragCancel = { endDrag(0f) },
                        )
                    },
            ) {
                // Grabber handle.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Clear the keyboard plus the floating editing pill that sits at its top.
                        // While dragging, hold the frozen peak (the sheet translates as one piece);
                        // otherwise track the live IME height so that when Back hides the keyboard
                        // the list reflows down to the pill instead of leaving a keyboard-tall gap.
                        .padding(
                            bottom = with(density) {
                                (if (dragging) kbHeightPx else imeBottomPx).toDp()
                            } + bottomInset + 64.dp,
                        ),
                ) {
                    if (pasteAvailable) {
                        PasteRow(onClick = {
                            // Reading the clip item here (coerceToText) is the only place we touch
                            // the actual content — so the OS paste-access notification fires on the
                            // user's explicit tap, not merely on opening the address bar.
                            val text = systemClipboard
                                ?.primaryClip
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                ?.trim()
                            if (!text.isNullOrEmpty()) onSubmit(text)
                        })
                    }
                    rows.forEachIndexed { index, suggestion ->
                        SuggestionRow(
                            suggestion = suggestion,
                            // The first row is the autoselected default (what Go/Enter commits), so
                            // it carries a highlight the way a browser omnibox marks its top result.
                            highlighted = index == 0,
                            onClick = { onSubmit(suggestion.url) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Merges local URL matches with remote search-query suggestions into the final ordered row list:
 * history/bookmark hits first (strongest intent), then a guaranteed "search for exactly what I typed"
 * row, then the search engine's *other* query suggestions. De-duplicated (URLs by normalized URL,
 * queries by lowercased text) and capped at [max].
 *
 * Why the typed text ranks ABOVE the remote suggestions: the first row is the autoselected default
 * that Enter/Go commits (see [AddressSheet]'s `onTopSuggestionChange`). If a search *suggestion* like
 * "leetcode sql" sat first, typing "leetcode" and pressing Enter would silently search "leetcode sql"
 * instead of "leetcode". So when there's no URL match, the default is always to search exactly what
 * the user typed; the engine's expansions stay below as one-tap options.
 */
/** Path component of [url] ("" if it has none / can't be parsed) — used to detect a domain-root match. */
private fun URIPath(url: String): String =
    runCatching { java.net.URI(url).path }.getOrNull().orEmpty()

private fun buildSuggestionRows(
    local: List<AddressSuggestion>,
    searchTerms: List<String>,
    typed: String,
    max: Int = 8,
): List<AddressSuggestion> {
    val rows = LinkedHashMap<String, AddressSuggestion>()

    // When the typed text is itself a navigable domain (e.g. "apple.com"), guarantee a direct
    // "go to that site" row as the autoselected default — before any history rows. This fixes the
    // case where history only holds deep links under the domain (a one-off `apps.apple.com/us/app/…`):
    // without it, that stale deep link would be the highlighted default instead of the site the user
    // clearly meant. We skip synthesising when a local match already IS that domain root (it will have
    // ranked first via `rankScore`), so the site is never listed twice. `normalizeUrl` is the single
    // URL-vs-search authority (invariant #9); we only reuse it here, never re-implement it.
    if (looksLikeUrlInput(typed)) {
        val direct = normalizeUrl(typed)
        val directHost = hostOf(direct)?.removePrefix("www.")
        val alreadyRooted = local.any { s ->
            hostOf(s.url)?.removePrefix("www.") == directHost &&
                URIPath(s.url).let { it.isEmpty() || it == "/" }
        }
        if (directHost != null && !alreadyRooted) {
            rows["u:" + canonicalUrlKey(direct)] = AddressSuggestion(
                title = compactHost(direct),
                url = direct,
                kind = AddressSuggestionKind.History,
            )
        }
    }

    for (s in local) {
        val key = "u:" + canonicalUrlKey(s.url)
        if (!rows.containsKey(key)) rows[key] = s
    }
    // The "search for exactly what I typed" row goes before the remote suggestions so it is the
    // autoselected default (Enter searches the raw text, never a suggestion). Always present, even
    // when the network returned nothing.
    val typedKey = "q:" + typed.lowercase()
    rows[typedKey] = AddressSuggestion(title = typed, url = typed, kind = AddressSuggestionKind.Search)
    for (term in searchTerms) {
        val key = "q:" + term.lowercase()
        if (!rows.containsKey(key)) {
            rows[key] = AddressSuggestion(title = term, url = term, kind = AddressSuggestionKind.Search)
        }
    }
    return rows.values.take(max).toList()
}

/**
 * Top-of-list clipboard shortcut shown when the address field is empty/untouched. Deliberately shows
 * no preview of the clipboard text — the content is only read on tap (see the call site) to avoid the
 * OS clipboard-access notification firing every time the address bar is opened.
 */
@Composable
private fun PasteRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.ContentPaste,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text = "Paste and go",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SuggestionRow(
    suggestion: AddressSuggestion,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Uniform row height keeps the list rhythm even (search rows are one line, URL rows two)
            // and guarantees a comfortable iOS-sized tap target.
            .heightIn(min = 52.dp)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            // The autoselected top row gets a tonal pill behind it so it reads as pre-selected.
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A search row shows the magnifying glass; a concrete URL suggestion shows the site's
        // favicon (with a globe fallback), the way Safari renders history/bookmark completions.
        if (suggestion.kind == AddressSuggestionKind.Search) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Favicon(suggestion.url, size = 20.dp)
        }
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (suggestion.kind == AddressSuggestionKind.Search) {
                Text(
                    text = "Search for “${suggestion.title}”",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = suggestion.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = suggestion.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (suggestion.kind != AddressSuggestionKind.Search) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
