package com.firebolt9907.luminabrowser.ui.grid

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firebolt9907.luminabrowser.ui.LuminaHaptics
import com.firebolt9907.luminabrowser.ui.rememberLuminaHaptics
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// During the drag/commit only cards within a couple of carousel slots of the finger can be on
// screen; composing all of a many-tab deck would pay layout and snapshot texture uploads for cards
// you can't see. The window is quantized to whole slots so it recomposes only when the drag crosses
// a slot boundary — never per frame.
private const val GestureVisibleSlots = 1

/**
 * The "all tabs" overview, rendered as a horizontal receding 3D deck (iOS app-switcher style):
 * the newest tab is the largest and furthest right (the front), older tabs shrink and tuck
 * behind-left, and only ~[DeckVisibleDepth] read as visible. Each card starts at its true carousel
 * position ([GridTab.browseBaseX] + the live carousel shift, matching the browse-layer overlay
 * pixel-for-pixel) and ends at its deck slot ([deckSlot]).
 *
 * Hot-path contract: [progress], [xProgress], [carouselShift], [dragOffsetPx], the live deck-scroll
 * position, and the per-card swipe-to-close offsets are read ONLY inside `graphicsLayer` /
 * `derivedStateOf` blocks. During a drag no recomposition or layout happens — every per-frame value
 * (position, scale, corner radius, page crop, alphas) is a draw-phase layer property. Composition
 * changes only at phase boundaries (gesture ↔ springs ↔ open) and when the quantized deck-scroll or
 * carousel-slot culling bucket slides.
 *
 * Recomposition contract: each card is a skippable composable ([GridTabCard] / [GridCellChrome])
 * whose inputs are all stable. The caller's lambdas are re-allocated on most of its recompositions,
 * so they are wrapped in remembered forwarders here. Snapshot bitmaps resolve inside each card via
 * [derivedStateOf] keyed on [snapshotVersion], so a thumbnail landing from the disk cache
 * recomposes only the card whose bitmap actually changed.
 */
@Composable
fun TabGridOverlay(
    progress: () -> Float,
    xProgress: () -> Float,
    /** Live horizontal pill-drag offset; added to each card's [GridTab.browseBaseX]. */
    carouselShift: () -> Float,
    /** Raw upward drag distance (px, uncapped) while [isGesture]; the finger-tracking Y. */
    dragOffsetPx: () -> Float,
    containerSize: IntSize,
    tabs: List<GridTab>,
    selectedTabId: String?,
    onTabSelected: (String) -> Unit,
    /** Full page stride (viewport width + inter-tab gap) in px; spaces carousel slots. */
    pageStridePx: Float,
    /** Static shift base of the selected card during a mid-switch handoff (else 0). */
    selectedShiftBase: Float = 0f,
    onNewTab: () -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    newTabSelected: Boolean = false,
    modifier: Modifier = Modifier,
    browseCornerDp: Dp = 22.dp,
    /** True while the finger is still down — cards track the finger via [dragOffsetPx]. */
    isGesture: Boolean = false,
    /**
     * Non-zero during the commit spring (finger released, deck opening). The spring animates
     * [progress] from this value to 1. Tabs lerp from [releasePositions] to deck slots using
     * a normalized t = (progress − commitFromProgress) / (1 − commitFromProgress).
     */
    commitFromProgress: Float = 0f,
    /**
     * Non-zero during the cancel spring (finger released, deck closing). Tabs lerp from
     * [releasePositions] back to their browse/fullscreen carousel positions as [progress]
     * returns to 0.
     */
    cancelFromProgress: Float = 0f,
    /** Per-index (tx, ty, scale) captured at finger release; start points for both springs. */
    releasePositions: List<Triple<Float, Float, Float>>? = null,
    /** Bumps when a snapshot lands; cards re-resolve their bitmap through [snapshotProvider]. */
    snapshotVersion: State<Int>,
    snapshotProvider: (String) -> Bitmap? = { null },
    /** Which overview layout to render. Selects the per-card slot function ([deckSlot]/[pixelSlot])
     * and the horizontal drag/scroll step; the gesture core stays layout-agnostic. */
    tabViewMode: TabViewMode = TabViewMode.DECK,
) {
    if (containerSize.width <= 0 || containerSize.height <= 0 || tabs.isEmpty()) return
    val density = LocalDensity.current
    val haptics = rememberLuminaHaptics()
    val coroutineScope = rememberCoroutineScope()
    // Per-card vertical swipe-to-close offset (px, negative = upward).
    val swipeOffsets = remember { mutableStateMapOf<String, Float>() }

    LaunchedEffect(tabs) {
        val currentIds = tabs.map { it.id }.toSet()
        swipeOffsets.keys.retainAll(currentIds)
    }

    val browseCornerPx = with(density) { browseCornerDp.toPx() }
    val deckCornerPx = with(density) { 46.dp.toPx() }
    val containerW = containerSize.width.toFloat()
    val containerH = containerSize.height.toFloat()
    val containerHDp = with(density) { containerH.toDp() }
    // Visible page slice of a fully-open deck card (its own unscaled coordinate space). Taller than
    // the old grid's 3:4 for a screen-like card, but never taller than the viewport.
    val croppedPageHeightPx = min(containerW * DeckCropAspect, containerH)

    // ── Deck scroll ────────────────────────────────────────────────────────────────────────────
    // Scroll is stored in PIXELS of finger travel; the card whose recencyRank == scrollPx / stepPx
    // sits at the front (p = 0). Storing pixels (not card-units) is what makes the fling feel right:
    // the spline decay's friction is calibrated for pixel-scale magnitudes, so a card-unit velocity
    // (~tens) would decay almost instantly and kill the inertia. Seeded to the selected tab so the
    // fullscreen selected page morphs continuously into the front slot. The overlay is only composed
    // while the deck is engaged, so each open re-enters here fresh.
    // Per-card slot function for the active mode — a drop-in swap read in the draw phase.
    val slotProvider: (Float) -> DeckSlot = remember(tabViewMode, containerW, containerH, croppedPageHeightPx) {
        when (tabViewMode) {
            TabViewMode.PIXEL -> { p -> pixelSlot(p, containerW, containerH, croppedPageHeightPx) }
            // GRID falls back to the deck placement until the legacy 2-column grid is wired in.
            else -> { p -> deckSlot(p, containerW, containerH, croppedPageHeightPx) }
        }
    }
    // Drag/scroll step matches the mode's visual centre-to-centre spacing so the deck tracks the
    // finger and snapping lands a card centred.
    val stepFrac = when (tabViewMode) {
        TabViewMode.PIXEL -> DeckPixelScale + DeckPixelGapFrac
        else -> DeckDragStepFrac
    }
    val stepPx = (stepFrac * containerW).coerceAtLeast(1f)
    val maxScroll = (tabs.size - 1).coerceAtLeast(0).toFloat()
    val maxScrollPx = maxScroll * stepPx
    val initialScrollPx = remember(tabs, selectedTabId, stepPx) {
        (tabs.firstOrNull { it.id == selectedTabId }?.recencyRank ?: 0).toFloat() * stepPx
    }
    // deckDragging + deckScrollDrag are the hot-path pair (written directly per pointer sample, no
    // coroutine); deckScrollAnim carries the seed and the release fling. Same split as the grid's
    // gridDragProgress / gridProgressState (invariant #4): never snapTo on the drag hot path.
    var deckDragging by remember { mutableStateOf(false) }
    var deckScrollDrag by remember { mutableFloatStateOf(0f) }
    val deckScrollAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { deckScrollAnim.snapTo(initialScrollPx) }
    // Re-clamp if tabs shrink out from under the current front (e.g. a card was closed).
    LaunchedEffect(maxScrollPx) {
        if (!deckDragging && deckScrollAnim.value > maxScrollPx) {
            deckScrollAnim.animateTo(maxScrollPx)
        }
    }
    val flingSpec = rememberSplineBasedDecay<Float>()
    // Settle spring: after the momentum is projected forward it lands, critically damped (no
    // bounce), on the nearest card so the deck always rests with a card cleanly focused.
    val settleSpring = remember { spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessLow) }

    // Cells become tappable / scrollable once the deck is essentially fully open. Derived so the
    // per-frame progress read flips a boolean instead of recomposing per frame. Built from
    // rememberUpdatedState holders (not remember keys) so the derived instance is stable across
    // recompositions and the per-card swipe pointerInput can read it at event time.
    val isGestureState = rememberUpdatedState(isGesture)
    val commitFromProgressState = rememberUpdatedState(commitFromProgress)
    val cancelFromProgressState = rememberUpdatedState(cancelFromProgress)
    val progressHolder = rememberUpdatedState(progress)
    val scrollEnabledState = remember {
        derivedStateOf {
            !isGestureState.value && commitFromProgressState.value == 0f &&
                cancelFromProgressState.value == 0f && progressHolder.value() >= 0.999f
        }
    }
    val scrollEnabled by scrollEnabledState

    // Stable forwarders. See class doc — the caller re-allocates these lambdas on most of its
    // recompositions, which would change every card's parameters and defeat skipping.
    val xProgressHolder = rememberUpdatedState(xProgress)
    val carouselShiftHolder = rememberUpdatedState(carouselShift)
    val dragOffsetHolder = rememberUpdatedState(dragOffsetPx)
    val snapshotProviderHolder = rememberUpdatedState(snapshotProvider)
    val onTabSelectedHolder = rememberUpdatedState(onTabSelected)
    val onCloseTabHolder = rememberUpdatedState(onCloseTab)
    val progressFwd: () -> Float = remember { { progressHolder.value() } }
    val xProgressFwd: () -> Float = remember { { xProgressHolder.value() } }
    val carouselShiftFwd: () -> Float = remember { { carouselShiftHolder.value() } }
    val dragOffsetFwd: () -> Float = remember { { dragOffsetHolder.value() } }
    // Live deck-scroll provider, read only in the draw phase. Converts the pixel-space scroll into
    // continuous card units (the front card index).
    val stackScrollFwd: () -> Float = remember(stepPx) {
        { (if (deckDragging) deckScrollDrag else deckScrollAnim.value) / stepPx }
    }
    val snapshotProviderFwd: (String) -> Bitmap? = remember { { id -> snapshotProviderHolder.value(id) } }
    val onTabSelectedFwd: (String) -> Unit = remember { { id -> onTabSelectedHolder.value(id) } }
    val onCloseTabFwd: (String) -> Unit = remember { { id -> onCloseTabHolder.value(id) } }
    val cellsClickableFwd: () -> Boolean = remember { { progressHolder.value() > 0.5f } }

    // Composition-level culling. During the gesture / cancel spring only cards within a couple of
    // carousel slots of the finger can be on screen. In the open/commit deck, only cards whose deck
    // position is in the visible depth window. Both windows are quantized to whole slots via
    // derivedStateOf so composition invalidates only when a card crosses a boundary — never per
    // frame; per-frame positioning still reads the exact live value in the draw phase.
    val gestureLike = isGesture || cancelFromProgress > 0f
    val shiftSlots by remember(pageStridePx, selectedShiftBase) {
        derivedStateOf {
            if (pageStridePx > 0f) {
                ((selectedShiftBase + carouselShift()) / pageStridePx).roundToInt()
            } else {
                0
            }
        }
    }
    val scrollBucket by remember { derivedStateOf { stackScrollFwd().roundToInt() } }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Go opaque very quickly (fully opaque at ~5% progress) so GeckoView never shows
                // through the squircle corners during the gesture ramp-up.
                .graphicsLayer { alpha = (progress() * 20f).coerceIn(0f, 1f) }
                // surfaceDim is the darkest surface tone — using it as the overview backdrop makes
                // every card read as raised against it, in both light and dark.
                .background(MaterialTheme.colorScheme.surfaceDim),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Horizontal drag scrolls the deck; per-card vertical drags (swipe-to-close) are
                // handled by the child cards and win on the vertical axis, so the two don't fight.
                .pointerInput(stepPx, maxScroll) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (scrollEnabledState.value) {
                                deckDragging = true
                                deckScrollDrag = deckScrollAnim.value
                                tracker.resetTracking()
                                coroutineScope.launch { deckScrollAnim.stop() }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (deckDragging) {
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                // Drag right → deck slides right, revealing older cards. Scroll is
                                // in px, and the focused card's centre also moves stepPx per card,
                                // so the front card tracks the finger 1:1.
                                deckScrollDrag =
                                    (deckScrollDrag + dragAmount).coerceIn(0f, maxScrollPx)
                            }
                        },
                        onDragEnd = {
                            if (deckDragging) {
                                deckDragging = false
                                val vPx = tracker.calculateVelocity().x
                                coroutineScope.launch {
                                    deckScrollAnim.snapTo(deckScrollDrag)
                                    // Project the momentum forward, then settle (critically damped,
                                    // carrying the release velocity) on the nearest card. This keeps
                                    // the flung distance/inertia while always resting card-centred.
                                    val projected =
                                        flingSpec.calculateTargetValue(deckScrollAnim.value, vPx)
                                    val targetCard = (projected / stepPx)
                                        .roundToInt()
                                        .coerceIn(0, maxScroll.toInt())
                                    deckScrollAnim.animateTo(
                                        targetValue = targetCard * stepPx,
                                        animationSpec = settleSpring,
                                        initialVelocity = vPx,
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            if (deckDragging) {
                                deckDragging = false
                                coroutineScope.launch { deckScrollAnim.snapTo(deckScrollDrag) }
                            }
                        },
                    )
                },
        ) {
            // Draw back-to-front: list order is oldest→newest, and recencyRank = size-1-index, so
            // iterating in index order draws the deepest (highest rank) card first and the front
            // (rank 0 / newest) last, on top. Cards scrolled in front of the front (p < 0) have the
            // lowest ranks and so also land on top — correct z-order in both directions.
            tabs.forEachIndexed { index, tab ->
                key(tab.id) {
                    val p = tab.recencyRank - scrollBucket
                    val composeCard = if (gestureLike) {
                        abs(tab.slotDelta + shiftSlots) <= GestureVisibleSlots
                    } else if (commitFromProgress > 0f) {
                        // Fan into the deck: compose cards near the finger at release OR whose deck
                        // slot is on-screen. One newer card (p=-1) sits right; the older left stack
                        // spans p up to ~DeckVisibleDepth before its slivers slide off the left.
                        abs(tab.slotDelta + shiftSlots) <= GestureVisibleSlots ||
                            (p >= -2 && p <= DeckVisibleDepth.toInt() + 1)
                    } else {
                        p >= -2 && p <= DeckVisibleDepth.toInt() + 1
                    }
                    if (composeCard) {
                        GridTabCard(
                            tab = tab,
                            containerW = containerW,
                            containerH = containerH,
                            containerHDp = containerHDp,
                            croppedPageHeightPx = croppedPageHeightPx,
                            browseCornerPx = browseCornerPx,
                            deckCornerPx = deckCornerPx,
                            pageStridePx = pageStridePx,
                            selectedShiftBase = selectedShiftBase,
                            isGesture = isGesture,
                            commitFromProgress = commitFromProgress,
                            cancelFromProgress = cancelFromProgress,
                            releasePosition = releasePositions?.getOrNull(index),
                            tabCount = tabs.size,
                            progress = progressFwd,
                            xProgress = xProgressFwd,
                            carouselShift = carouselShiftFwd,
                            dragOffsetPx = dragOffsetFwd,
                            stackScroll = stackScrollFwd,
                            slotProvider = slotProvider,
                            swipeOffsets = swipeOffsets,
                            scrollEnabled = scrollEnabledState,
                            snapshotVersion = snapshotVersion,
                            snapshotProvider = snapshotProviderFwd,
                            cellsClickable = cellsClickableFwd,
                            onTabSelected = onTabSelectedFwd,
                            onCloseTab = onCloseTabFwd,
                            haptics = haptics,
                            coroutineScope = coroutineScope,
                        )
                    }
                } // key(tab.id)
            }

            // Host label above each visible card. Rendered after the cards so it paints on top; its
            // position follows the card's deck slot. Suppressed while the finger is down (labels
            // appear only after release).
            if (!isGesture) {
                tabs.forEach { tab ->
                    key(tab.id) {
                        val p = tab.recencyRank - scrollBucket
                        // Only the prominently-visible cards (focus + the newer card + the nearest
                        // older card) carry a caption; the faded deep stack does not.
                        if (p >= -1 && p <= 1) {
                            GridCellChrome(
                                tab = tab,
                                containerW = containerW,
                                progress = progressFwd,
                                stackScroll = stackScrollFwd,
                                slotProvider = slotProvider,
                                swipeOffsets = swipeOffsets,
                            )
                        }
                    } // key(tab.id)
                }
            }

            // Animated new-tab card for the deck→new-tab transition: expands from the front deck
            // slot to fullscreen as the deck closes, identical to how a real tab animates. Real tabs
            // have browseBaseX offset left (newSelIdx = tabs.size in gridTabs) so they slide off
            // left while this card takes center.
            if (newTabSelected && !isGesture) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(containerHDp)
                        .graphicsLayer {
                            val p = progress().coerceIn(0f, 1f)
                            val front = slotProvider(0f)
                            transformOrigin = TransformOrigin(0f, 0f)
                            translationX = lerp(0f, front.tx, xProgress())
                            translationY = lerp(0f, front.ty, p)
                            val scale = lerp(1f, front.scale, p)
                            scaleX = scale
                            scaleY = scale
                            clip = true
                            shape = CardCropShape(
                                radiusPx = lerp(browseCornerPx, deckCornerPx, p),
                                cropHeightPx = lerp(containerH, croppedPageHeightPx, p),
                            )
                        }
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.graphicsLayer {
                            // Only show the "+" glyph while still card-sized, fading out as it fills.
                            alpha = (1f - progress() * 2f).coerceIn(0f, 1f)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One tab card: the morphing page layer. Skippable — all parameters are stable, and everything
 * per-frame (progress, carousel shift, drag position, deck scroll, swipe offset) is read only
 * inside the `graphicsLayer` block. The snapshot bitmap resolves through a [derivedStateOf] on
 * [snapshotVersion].
 */
@Composable
private fun GridTabCard(
    tab: GridTab,
    containerW: Float,
    containerH: Float,
    containerHDp: Dp,
    croppedPageHeightPx: Float,
    browseCornerPx: Float,
    deckCornerPx: Float,
    pageStridePx: Float,
    selectedShiftBase: Float,
    isGesture: Boolean,
    commitFromProgress: Float,
    cancelFromProgress: Float,
    releasePosition: Triple<Float, Float, Float>?,
    tabCount: Int,
    progress: () -> Float,
    xProgress: () -> Float,
    carouselShift: () -> Float,
    dragOffsetPx: () -> Float,
    stackScroll: () -> Float,
    slotProvider: (Float) -> DeckSlot,
    swipeOffsets: SnapshotStateMap<String, Float>,
    scrollEnabled: State<Boolean>,
    snapshotVersion: State<Int>,
    snapshotProvider: (String) -> Bitmap?,
    cellsClickable: () -> Boolean,
    onTabSelected: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    haptics: LuminaHaptics,
    coroutineScope: CoroutineScope,
) {
    val density = LocalDensity.current
    val padDp = with(density) { tab.browseTopPadPx.toDp() }
    val padPx = tab.browseTopPadPx.toFloat()
    val tabLabel = remember(tab.url, tab.title) {
        if (tab.url.isBlankTabUrl()) "New tab page"
        else tab.title.ifBlank { hostLabel(tab.url).ifEmpty { tab.url } }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Constant layout height: the fullscreen→deck page crop animates via the layer's crop
            // shape below, not via layout, so a drag frame never measures or lays anything out.
            .height(containerHDp)
            .padding(top = padDp)
            .graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                val scale: Float
                val tx: Float
                val ty: Float
                // Depth transparency for the deep left stack (iOS). 1 while the finger is down; in
                // the deck it lerps in from opaque with the open progress so it never pops.
                var deckAlpha = 1f
                when {
                    isGesture -> {
                        // Finger-tracked carousel: cards shrink together toward the drag floor and
                        // fan into the deck only on release (commit spring).
                        val liveScale =
                            (1f - p * (1f - DeckDragMinScale)).coerceIn(DeckDragMinScale, 1f)
                        val fingerX = containerW / 2f + selectedShiftBase + carouselShift()
                        val fingerY = containerH - dragOffsetPx()
                        scale = liveScale
                        tx = (fingerX - containerW * liveScale / 2f) +
                            tab.slotDelta * pageStridePx * liveScale
                        ty = fingerY - containerH * liveScale
                    }
                    commitFromProgress > 0f -> {
                        val from = releasePosition ?: Triple(0f, 0f, 1f)
                        val t = if (commitFromProgress < 1f) {
                            ((p - commitFromProgress) / (1f - commitFromProgress)).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                        val slot = slotProvider(tab.recencyRank - stackScroll())
                        scale = lerp(from.third, slot.scale, t)
                        tx = lerp(from.first, slot.tx, t)
                        ty = lerp(from.second, slot.ty, t)
                        deckAlpha = lerp(1f, slot.alpha, t)
                    }
                    cancelFromProgress > 0f -> {
                        val from = releasePosition ?: Triple(0f, 0f, 1f)
                        val t = (1f - p / cancelFromProgress).coerceIn(0f, 1f)
                        scale = lerp(from.third, 1f, t)
                        tx = lerp(from.first, tab.browseBaseX + carouselShift(), t)
                        ty = lerp(from.second, 0f, t)
                    }
                    else -> {
                        // Static open / close morph: browse fullscreen ↔ deck slot by progress.
                        val slot = slotProvider(tab.recencyRank - stackScroll())
                        scale = lerp(1f, slot.scale, p)
                        tx = lerp(tab.browseBaseX + carouselShift(), slot.tx, xProgress())
                        ty = lerp(0f, slot.ty, p)
                        deckAlpha = lerp(1f, slot.alpha, p)
                    }
                }
                // Swipe-to-close: an upward Y offset, read in the draw phase so dragging a card up
                // never recomposes — it only re-draws this layer.
                val swipeOffset = swipeOffsets[tab.id] ?: 0f
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = tx
                translationY = ty + swipeOffset
                scaleX = scale
                scaleY = scale
                clip = true
                // The only alpha is the iOS depth transparency of the deep left stack: cards up to
                // one slot left of the focus stay opaque, deeper ones fade. Cards still leave the
                // deck by sliding off the edges / flying up on close — never a motion cross-fade.
                alpha = deckAlpha
                shape = CardCropShape(
                    radiusPx = lerp(browseCornerPx, deckCornerPx, p),
                    // Local (pre-scale) visible height of the card: full viewport at progress 0 (so
                    // the open/close morph matches the browse layer), shrinking to the deck slice.
                    cropHeightPx = lerp(containerH, croppedPageHeightPx, p) - padPx,
                )
            }
            .background(
                if (tab.url.isBlankTabUrl()) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .semantics(mergeDescendants = true) { contentDescription = tabLabel }
            .clickable(
                onClickLabel = "Switch to this tab",
            ) {
                if (!cellsClickable()) return@clickable
                haptics.select()
                onTabSelected(tab.id)
            }
            .pointerInput(tabCount) {
                if (tabCount <= 1) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        coroutineScope.launch {
                            val offset = swipeOffsets[tab.id] ?: 0f
                            // Only an upward swipe past the threshold dismisses the card.
                            if (offset < -containerH * 0.18f) {
                                animate(
                                    initialValue = offset,
                                    targetValue = -containerH,
                                    animationSpec = tween(durationMillis = 200)
                                ) { value, _ -> swipeOffsets[tab.id] = value }
                                onCloseTab(tab.id)
                            } else {
                                animate(
                                    initialValue = offset,
                                    targetValue = 0f,
                                    animationSpec = tween(durationMillis = 200)
                                ) { value, _ -> swipeOffsets[tab.id] = value }
                            }
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            val offset = swipeOffsets[tab.id] ?: 0f
                            animate(
                                initialValue = offset,
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 200)
                            ) { value, _ -> swipeOffsets[tab.id] = value }
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        // Checked per event (not as a pointerInput key) so the enable flip never
                        // restarts the gesture coroutine.
                        if (scrollEnabled.value) {
                            change.consume()
                            val current = swipeOffsets[tab.id] ?: 0f
                            // Allow upward travel freely; resist downward so the card doesn't sink.
                            val next = current + dragAmount
                            swipeOffsets[tab.id] = if (next > 0f) next * 0.2f else next
                        }
                    }
                )
            },
    ) {
        if (tab.url.isBlankTabUrl()) {
            Unit
        } else {
            // Identity-invalidating snapshot lookup: recomputes on every version bump, but only
            // recomposes this card when the resolved bitmap instance changed.
            val snapshot by remember(tab.id) {
                derivedStateOf {
                    snapshotVersion.value
                    snapshotProvider(tab.id)
                }
            }
            val tracker = remember { SnapshotTracker(snapshot) }
            tracker.update(snapshot)
            val prev = tracker.previous
            val curr = tracker.current

            var lastSnapshot by remember { mutableStateOf(snapshot) }
            var animateOnLoad by remember { mutableStateOf(false) }

            if (snapshot != lastSnapshot) {
                animateOnLoad = lastSnapshot == null && snapshot != null
                lastSnapshot = snapshot
            }

            val animAlpha by key(snapshot) {
                var target by remember {
                    mutableStateOf(
                        if (isGesture) 1f
                        else if (animateOnLoad || prev != null) 0f
                        else 1f
                    )
                }
                LaunchedEffect(Unit) {
                    if (!isGesture) {
                        target = 1f
                    }
                }
                animateFloatAsState(
                    targetValue = target,
                    animationSpec = tween(180),
                    label = "thumbnail_crossfade"
                )
            }

            LaunchedEffect(animAlpha) {
                if (animAlpha >= 1f) {
                    tracker.previous = null
                }
            }

            val showPlaceholder = snapshot == null || (prev == null && animAlpha < 1f)

            Box(modifier = Modifier.fillMaxSize()) {
                if (showPlaceholder) {
                    val placeholderAlpha = if (snapshot == null) {
                        1f
                    } else if (!isGesture) {
                        1f - animAlpha
                    } else {
                        0f
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = placeholderAlpha }
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = tabLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (snapshot != null) {
                    val prevImg = remember(prev) { prev?.asImageBitmap() }
                    if (prevImg != null) {
                        Image(
                            bitmap = prevImg,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (!isGesture) 1f - animAlpha else 0f
                                },
                        )
                    }
                    val currImg = remember(curr) { curr?.asImageBitmap() }
                    if (currImg != null) {
                        Image(
                            bitmap = currImg,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (!isGesture) animAlpha else 1f
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The caption drawn above each prominently-visible deck card — the page title with the site host
 * beneath it, iOS-app-switcher style — following its slot in the draw phase. Skippable; the slot
 * position, the swipe-to-close offset, and the open-state alpha are all draw-phase layer reads.
 */
@Composable
private fun GridCellChrome(
    tab: GridTab,
    containerW: Float,
    progress: () -> Float,
    stackScroll: () -> Float,
    slotProvider: (Float) -> DeckSlot,
    swipeOffsets: SnapshotStateMap<String, Float>,
) {
    val host = remember(tab.url) { hostLabel(tab.url) }
    val title = remember(tab.url, tab.title) {
        when {
            tab.url.isBlankTabUrl() -> "New Tab"
            tab.title.isNotBlank() -> tab.title
            host.isNotEmpty() -> host
            else -> tab.url
        }
    }
    val density = LocalDensity.current
    // The caption tracks the widest (focused) card so it stays put and legible; text is centred.
    val labelWidthDp = with(density) { (containerW * DeckFrontScale).toDp() }
    Column(
        modifier = Modifier
            .graphicsLayer {
                val slot = slotProvider(tab.recencyRank - stackScroll())
                val swipeOffset = swipeOffsets[tab.id] ?: 0f
                // Sit above the card's top edge, centred over the card; rides up with the card on a
                // swipe-to-close. The Column grows downward from this origin, so offset up by roughly
                // its own height plus a small gap. Alpha follows the open progress (so it isn't drawn
                // over the live browse page) and the card's own depth transparency.
                val labelGap = with(density) { 52.dp.toPx() }
                translationX = slot.tx + (containerW * slot.scale - containerW * DeckFrontScale) / 2f
                translationY = slot.ty + swipeOffset - labelGap
                alpha = progress().coerceIn(0f, 1f) * slot.alpha
            }
            .width(labelWidthDp)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (host.isNotEmpty() && !tab.url.isBlankTabUrl()) {
            Text(
                text = host,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
