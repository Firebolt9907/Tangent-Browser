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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.AddressSuggestion
import com.firebolt9907.luminabrowser.state.AddressSuggestionKind
import com.firebolt9907.luminabrowser.state.addressSuggestions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.BookmarksStorage
import mozilla.components.concept.storage.HistoryStorage

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

    // Auto-dismiss when the keyboard collapses while the sheet is still open (e.g. the Back button
    // hides the IME on its first press) so a single Back closes both. Only after the keyboard has
    // actually been shown, and never mid-drag (the drag owns its own collapse/dismiss).
    var kbWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (!visible) kbWasShown = false }
    LaunchedEffect(imeBottomPx, dragging, visible) {
        if (!visible || dragging) return@LaunchedEffect
        if (imeBottomPx > 1f) {
            kbWasShown = true
        } else if (kbWasShown) {
            onDismiss()
        }
    }
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
    var matches by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    LaunchedEffect(rowsActive, trimmed) {
        if (!rowsActive) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(120)
        matches = addressSuggestions(trimmed, historyStorage, bookmarksStorage)
    }
    val rows = if (rowsActive) {
        matches + AddressSuggestion(title = trimmed, url = trimmed, kind = AddressSuggestionKind.Search)
    } else {
        emptyList()
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
                        .padding(bottom = with(density) { kbHeightPx.toDp() } + bottomInset + 64.dp),
                ) {
                    rows.forEach { suggestion ->
                        SuggestionRow(
                            suggestion = suggestion,
                            onClick = { onSubmit(suggestion.url) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: AddressSuggestion,
    onClick: () -> Unit,
) {
    val icon = when (suggestion.kind) {
        AddressSuggestionKind.Search -> Icons.Default.Search
        AddressSuggestionKind.History -> Icons.Default.History
        AddressSuggestionKind.Bookmark -> Icons.Outlined.BookmarkBorder
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
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
