package com.firebolt9907.tangentbrowser.ui.chrome

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import com.firebolt9907.tangentbrowser.state.AddressSuggestion
import com.firebolt9907.tangentbrowser.ui.TangentMotion
import com.firebolt9907.tangentbrowser.ui.components.Favicon
import com.firebolt9907.tangentbrowser.state.AddressSuggestionKind
import com.firebolt9907.tangentbrowser.state.SearchEngine
import com.firebolt9907.tangentbrowser.state.addressSuggestions
import com.firebolt9907.tangentbrowser.state.canonicalUrlKey
import com.firebolt9907.tangentbrowser.state.compactHost
import com.firebolt9907.tangentbrowser.state.hostOf
import com.firebolt9907.tangentbrowser.state.looksLikeUrlInput
import com.firebolt9907.tangentbrowser.state.normalizeUrl
import com.firebolt9907.tangentbrowser.state.OpenTabRef
import com.firebolt9907.tangentbrowser.state.RemoteTabRef
import com.firebolt9907.tangentbrowser.state.openTabSuggestions
import com.firebolt9907.tangentbrowser.state.syncedTabSuggestions
import com.firebolt9907.tangentbrowser.state.searchSuggestions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
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
    onTopSuggestionChange: (String, AddressSuggestion?) -> Unit = { _, _ -> },
    onPreconnect: (String) -> Unit = {},
    onPreloadPage: (String) -> Unit = {},
    searchSuggestionsEnabled: Boolean = true,
    searchEngine: SearchEngine = SearchEngine.DEFAULT,
    // Open tabs (excluding the current one) offered as switch-to-tab suggestions, plus the callback
    // that switches to one. Empty list disables the feature.
    openTabs: List<OpenTabRef> = emptyList(),
    currentTabId: String? = null,
    onSwitchToTab: (String) -> Unit = {},
    // Tabs open on other Firefox-synced devices, offered as navigable suggestions. Empty list
    // disables the feature (e.g. signed out).
    syncedTabs: List<RemoteTabRef> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val imeController = rememberImeSheetController()
    val keyboardController = LocalSoftwareKeyboardController.current

    // `reveal` drives entry/exit slide when not dragging. `dragging` is true during a drag and its
    // release animation; `rawDragPx` is the finger's downward travel (drives the sheet's slide).
    val reveal = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var rawDragPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    // Guards against a second `endDrag` while the release animation is already running. A single
    // nested-scroll fling calls BOTH onPreFling and onPostFling; without this each would launch its
    // own settle coroutine and the two would fight over `rawDragPx` (visible as a down-slide that
    // rebounds up and then dismisses anyway).
    var settling by remember { mutableStateOf(false) }

    // Keyboard height: captured live from WindowInsets while not dragging (the controller freezes it
    // during manual control). Used as the sheet's resting bottom inset and the drag/dismiss metric.
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    var kbHeightPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(imeBottomPx, dragging) {
        // Capture the peak (keyboard height); don't let it shrink while the keyboard is collapsing.
        if (!dragging && imeBottomPx > kbHeightPx) kbHeightPx = imeBottomPx
    }

    // IME animation control is acquired lazily when a drag actually begins (onDragStart / the
    // nested-scroll onPreScroll), with pre-control drag deltas buffered by ImeSheetController. We do
    // NOT pre-acquire it while the keyboard is merely visible: grabbing control mid open/close
    // animation makes the IME visibly teleport between shown and hidden.

    // Note: we intentionally do NOT auto-dismiss the sheet when the keyboard collapses. Pressing
    // Back while editing hides the IME (the IME window consumes that first Back in onKeyPreIme,
    // before it reaches the app) and leaves the suggestions sheet open — the standard Android
    // two-stage pattern. A second Back then closes the sheet via TangentRoot's editing BackHandler.
    val fallbackTravelPx = with(density) { 320.dp.toPx() }
    val travelPx = if (kbHeightPx > 0f) kbHeightPx else fallbackTravelPx

    // Non-drag entry/exit. The sheet rises and falls with the keyboard — tapping the pill shows the
    // IME and this sheet together, submitting hides both — so it runs on the platform's own synced
    // IME curve and duration rather than a timer of its own (TangentMotion.ImeTrack; see
    // ImeSyncDurationMs for where those numbers come from). Previously 280 ms in / 220 ms out on
    // FastOutSlowIn, which drifted against the keyboard in both directions and worst on the way out,
    // where it was a full 65 ms shorter than the IME it was supposed to be travelling with.
    LaunchedEffect(visible) {
        if (visible) {
            reveal.animateTo(1f, TangentMotion.ImeTrack)
        } else {
            dragging = false
            rawDragPx = 0f
            reveal.animateTo(0f, TangentMotion.ImeTrack)
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
    var localResult by remember { mutableStateOf<Pair<String, List<AddressSuggestion>>?>(null) }
    var remoteResult by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    LaunchedEffect(rowsActive, trimmed, historyStorage, bookmarksStorage) {
        if (!rowsActive) return@LaunchedEffect
        // Local matches should appear as soon as Places responds, without waiting for the network.
        val matches = addressSuggestions(trimmed, historyStorage, bookmarksStorage, max = 4)
        if (!isActive) return@LaunchedEffect
        localResult = trimmed to matches
        // Pre-warm connections to the likeliest destinations (the top concrete-URL suggestions) so
        // tapping one lands on an already-open connection. Search-query rows have no origin, so the
        // preconnector's URL check ignores them.
        matches.take(2).forEach { onPreconnect(it.url) }
        // Go further for the single top hit: actually preload the page into cache so tapping it is
        // near-instant. Gated by the caller (preload policy) since this spends data. Only concrete
        // URL suggestions are preloadable — a raw search query isn't a page.
        matches.firstOrNull()?.let { onPreloadPage(it.url) }
    }
    LaunchedEffect(rowsActive, trimmed, searchSuggestionsEnabled, searchEngine) {
        if (!rowsActive || !searchSuggestionsEnabled) return@LaunchedEffect
        delay(120)
        val terms = searchSuggestions(trimmed, suggestClient, searchEngine)
        if (isActive) remoteResult = trimmed to terms
    }
    val localMatches = localResult?.takeIf { rowsActive && it.first == trimmed }?.second.orEmpty()
    val searchTerms = remoteResult?.takeIf { rowsActive && searchSuggestionsEnabled && it.first == trimmed }?.second.orEmpty()
    // Switch-to-tab matches are computed synchronously from the in-memory open-tab list (no IO), so
    // they can be derived inline rather than in the debounced fetch effect above.
    val tabMatches = if (rowsActive) {
        openTabSuggestions(trimmed, openTabs, currentTabId)
    } else {
        emptyList()
    }
    val syncedMatches = if (rowsActive) {
        syncedTabSuggestions(trimmed, syncedTabs)
    } else {
        emptyList()
    }
    val rows = if (rowsActive) {
        buildSuggestionRows(localMatches, searchTerms, trimmed, tabMatches, syncedMatches)
    } else {
        emptyList()
    }

    // Publish the autoselected top row so Go/Enter commits it (like most browsers), and so the
    // editing field can inline-complete a matching URL. `onSubmit`'s normalizeUrl re-decides
    // URL-vs-search, so a highlighted history URL navigates and a highlighted query searches.
    val topRow = if (visible) rows.firstOrNull() else null
    LaunchedEffect(trimmed, topRow) { onTopSuggestionChange(trimmed, topRow) }

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
        if (settling) return
        settling = true
        val dismiss = rawDragPx > travelPx * 0.5f || (rawDragPx > 30f && velocityY > 2200f)
        val currentFrac = if (imeController.isSupported && imeController.isActive) {
            imeController.currentFraction()
        } else if (imeBottomPx >= kbHeightPx - 5f && kbHeightPx > 0f) {
            1f
        } else {
            0f
        }
        val keepKeyboard = !dismiss && imeController.isSupported && (
            velocityY < -300f || (velocityY <= 600f && currentFrac >= 0.35f)
        )
        val target = if (dismiss) {
            containerHeightPx.takeIf { it > 0f } ?: (travelPx * 3f)
        } else 0f
        val easing = com.firebolt9907.tangentbrowser.ui.motion.releaseEasing(
            target - rawDragPx, velocityY, com.firebolt9907.tangentbrowser.ui.ImeSettleDurationMs,
        )
        // The exact same normalized curve drives both surfaces, including delayed IME control.
        if (imeController.isSupported && imeController.isActive) {
            imeController.animateToFinish(show = keepKeyboard, easing = easing) {}
        }
        scope.launch {
            val anim = Animatable(rawDragPx)
            // Same duration and curve ImeSheetController.animateToFinish settles the real keyboard
            // on. The sheet is glued to the keyboard for the whole drag; if the two settle on
            // different curves they visibly come unstuck the instant the finger lifts.
            anim.animateTo(target, androidx.compose.animation.core.tween(
                com.firebolt9907.tangentbrowser.ui.ImeSettleDurationMs, easing = easing)) { rawDragPx = value }
            // Pin the post-drag resting state BEFORE clearing `dragging` so the offset doesn't snap:
            // dismissed → keep it off-screen (reveal 0); settled → keep it open (reveal 1).
            reveal.snapTo(if (dismiss) 0f else 1f)
            dragging = false
            rawDragPx = 0f
            settling = false
            if (dismiss) onDismiss()
        }
    }

    val nestedScrollConnection = remember(visible, imeBottomPx, kbHeightPx, keyboardController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy > 0f) {
                    // Finger DOWN, phase 1 (BEFORE the list scrolls): collapse the keyboard,
                    // frame-locked. Only what the keyboard absorbs is consumed; once it's fully
                    // down the leftover falls through so the LazyColumn scrolls toward its end, and
                    // the sheet-pull takes over from there (onPostScroll).
                    val keyboardPresent = imeController.isSupported &&
                        (imeController.isActive || imeBottomPx > 0f)
                    if (keyboardPresent) {
                        if (!imeController.isActive) imeController.startControlRequest {}
                        val consumed = -imeController.insetByUp(-dy)
                        if (consumed > 0f) {
                            dragging = true
                            return Offset(0f, consumed)
                        }
                        // Control was just requested and is still pending (insetByUp buffered the
                        // delta, returning 0): keep consuming so the list doesn't scroll ahead of
                        // the keyboard — the buffered delta replays once control is granted.
                        if (!imeController.isReady) {
                            dragging = true
                            return Offset(0f, dy)
                        }
                    }
                    return Offset.Zero
                } else if (dy < 0f) {
                    // Finger UP, phase 1 (BEFORE the list scrolls back): undo the sheet-pull first
                    // (mirror of onPostScroll's down path). Once the sheet is back at rest the
                    // leftover falls through so the list scrolls back, then the keyboard rises
                    // (onPostScroll) — the exact reverse order of the way down.
                    if (rawDragPx > 0f) {
                        val newDrag = (rawDragPx + dy).coerceAtLeast(0f)
                        val used = rawDragPx - newDrag
                        rawDragPx = newDrag
                        dragging = true
                        return Offset(0f, -used)
                    }
                    return Offset.Zero
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val dy = available.y
                if (dy > 0f) {
                    // Finger DOWN, phase 3: the list has scrolled to its end (this delta is its
                    // overscroll) → pull the whole sheet down to dismiss. The keyboard is already
                    // fully collapsed here, so imePadding == 0 and the sheet slides as one piece.
                    dragging = true
                    rawDragPx += dy
                    return Offset(0f, dy)
                } else if (dy < 0f) {
                    // Finger UP, phase 3: the list has scrolled back to its bottom (this delta is
                    // its overscroll) → bring the keyboard back up.
                    if (imeController.isSupported && imeController.isActive &&
                        imeController.currentFraction() < 0.999f
                    ) {
                        // We still hold IME control (mid-collapse, same gesture) → raise it
                        // frame-locked to the finger.
                        val consumed = -imeController.insetByUp(-dy)
                        if (consumed < 0f) {
                            dragging = true
                            return Offset(0f, consumed)
                        }
                    } else if (imeBottomPx == 0f) {
                        // Keyboard is fully hidden and we no longer hold control (it was collapsed
                        // and the finger lifted, so control was released on settle). We can't grab
                        // interactive control of a hidden IME reliably, so ask the system to show
                        // it; imePadding then lifts the list up with the keyboard's own animation.
                        keyboardController?.show()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Settle only if THIS gesture actually engaged the keyboard/sheet drag (dragging);
                // a pure list scroll/fling (keyboard already down, no sheet pull) is left to the
                // LazyColumn so it flings naturally.
                if (dragging || rawDragPx > 0f) {
                    endDrag(available.y)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragging || rawDragPx > 0f) {
                    endDrag(available.y)
                    return available
                }
                return Velocity.Zero
            }
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
                                settling = false
                                if (imeController.isSupported) {
                                    imeController.startControlRequest {}
                                }
                            },
                            onVerticalDrag = { change, dy ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                // The grabber isn't part of the scrollable list, so it runs the same
                                // phase order without a list-scroll step in between: DOWN collapses
                                // the keyboard first, then pulls the sheet with the leftover; UP
                                // restores the sheet first, then raises the keyboard. Frame-locked.
                                if (dy > 0f) {
                                    var remaining = dy
                                    if (imeController.isSupported && imeController.isActive) {
                                        remaining -= -imeController.insetByUp(-remaining)
                                    }
                                    if (remaining > 0f) rawDragPx += remaining
                                } else if (dy < 0f) {
                                    var remaining = dy
                                    if (rawDragPx > 0f) {
                                        val newDrag = (rawDragPx + remaining).coerceAtLeast(0f)
                                        remaining += rawDragPx - newDrag
                                        rawDragPx = newDrag
                                    }
                                    if (remaining < 0f && imeController.isSupported && imeController.isActive) {
                                        imeController.insetByUp(-remaining)
                                    }
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

                val listState = rememberLazyListState()
                // Pin the autoselected top row (index 0, held at the list's bottom edge by
                // reverseLayout) back into view whenever the suggestion set changes. Without this a
                // stale scroll offset left over from a previous, longer list can leave that row
                // scrolled up behind the floating input pill.
                LaunchedEffect(rows.firstOrNull()?.let { it.kind.name + it.url }) {
                    if (rows.isNotEmpty()) listState.scrollToItem(0)
                }
                // Disable the list's edge overscroll. At rest the bottom-anchored list is pinned
                // just above the keyboard, so a downward pull must translate the whole sheet /
                // collapse the IME (handled by nestedScrollConnection) — NOT play Compose's
                // stretch-and-snap overscroll, which read as the drag "no longer being responsive,
                // replaced with a standard snapping animation".
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                LazyColumn(
                    state = listState,
                    // Fill the available height so reverseLayout anchors content at the BOTTOM (next
                    // to the keyboard/input pill). With `fill = false` a short list — e.g. a lone
                    // Paste & Go row — hugged the top of the sheet instead.
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(nestedScrollConnection)
                        // Keyboard clearance in the MEASURE phase (the same mechanism the floating
                        // editing pill uses), so the bottom-anchored list RIDES the keyboard: it
                        // descends glued to the pill while the keyboard collapses (phase 1) and
                        // rises with it on the way back up — smoothly, not a recomposition behind.
                        // During the sheet-pull phase the keyboard is already fully down
                        // (imePadding == 0), so the sheet translates as one piece with no
                        // double-move.
                        .imePadding()
                        // Clear the floating editing pill that sits at the keyboard's top (72dp tall
                        // + its 8dp bottom gap + 8dp breathing room). No nav-bar inset here: the
                        // keyboard already covers it, so adding `bottomInset` back only opened a dead
                        // gap between the pill and the top result.
                        .padding(bottom = 88.dp),
                    reverseLayout = true,
                ) {
                    // Paste & Go is only ever shown alone (it requires an empty/untouched field, where
                    // there are no suggestion rows). Emit it FIRST so reverseLayout places it at the
                    // bottom — next to the input pill, where the top suggestion would sit — rather than
                    // stranded at the top of the sheet.
                    if (pasteAvailable) {
                        item(key = "paste_row") {
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
                    }

                    itemsIndexed(
                        items = rows,
                        // Key by URL/title to maintain scroll position when query updates
                        key = { _, suggestion -> suggestion.kind.name + suggestion.url }
                    ) { index, suggestion ->
                        SuggestionRow(
                            suggestion = suggestion,
                            highlighted = index == 0,
                            onClick = {
                                val tabId = suggestion.tabId
                                if (suggestion.kind == AddressSuggestionKind.Tab && tabId != null) {
                                    onSwitchToTab(tabId)
                                } else {
                                    onSubmit(suggestion.url)
                                }
                            },
                        )
                    }
                }
                }
            }
        }

        // Floating Cancel — overlays the suggestions list (bottom-right) rather than occupying its
        // own row in the bar, so the list rows stay full-width and remain visible to its left. It
        // rides the sheet's drag translation and sits just above the input pill's zone (mirroring the
        // rows' `+ 64.dp` pill clearance, plus a small gap). Calls onDismiss — the caller wires that
        // to the same "leave editing" action the old inline Cancel button had.
        Surface(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer { translationY = sheetOffset() }
                .padding(
                    end = 24.dp,
                    top = with(density) {
                        statusBarTopPad + 24.dp + 24.dp
                    }
//                    bottom = with(density) {
//                        (if (dragging) kbHeightPx else imeBottomPx).toDp()
//                    } + bottomInset + 12.dp,
                ),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Text(
                text = "Cancel",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
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
    tabMatches: List<AddressSuggestion> = emptyList(),
    syncedMatches: List<AddressSuggestion> = emptyList(),
    max: Int = 8,
): List<AddressSuggestion> {
    val rows = LinkedHashMap<String, AddressSuggestion>()

    // Switch-to-tab rows come first: an already-open tab is a stronger destination than a stale
    // history visit or a fresh navigation, so it becomes the autoselected default. Keyed by the same
    // canonical URL key as the URL rows below, so a history/synthesised row for the same page is
    // suppressed (we'd rather switch than open a duplicate).
    for (t in tabMatches) {
        rows["u:" + canonicalUrlKey(t.url)] = t
    }

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
        val directKey = "u:" + canonicalUrlKey(direct)
        if (directHost != null && !alreadyRooted && !rows.containsKey(directKey)) {
            rows[directKey] = AddressSuggestion(
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
    // Tabs from other devices rank below local history/open tabs (a page you've actually visited or
    // have open here is the stronger local intent) but above raw search suggestions. Keyed by the same
    // canonical URL so an open/history row for the same page suppresses the synced duplicate.
    for (s in syncedMatches) {
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
                    // A switch-to-tab row labels itself so the arrow reads as "go to that tab", not
                    // "open this URL"; a synced-tab row names its source device ("From <device>") so
                    // it's distinguishable from a local history hit. Both otherwise show the raw URL.
                    text = when (suggestion.kind) {
                        AddressSuggestionKind.Tab -> "Switch to open tab"
                        AddressSuggestionKind.SyncedTab ->
                            suggestion.detail?.let { "From $it" } ?: suggestion.url
                        else -> suggestion.url
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (suggestion.kind == AddressSuggestionKind.Tab ||
                        suggestion.kind == AddressSuggestionKind.SyncedTab
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
