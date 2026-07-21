package com.firebolt9907.luminabrowser.ui.grid

import android.graphics.Bitmap
import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firebolt9907.luminabrowser.ui.LuminaHaptics
import com.firebolt9907.luminabrowser.ui.content.LocalSiteTint
import com.firebolt9907.luminabrowser.ui.content.siteTintOverlay
import com.firebolt9907.luminabrowser.ui.rememberLuminaHaptics
import com.firebolt9907.luminabrowser.ui.shape.squirclePath
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
    /** Resolves a tab's declared theme color (`<meta name="theme-color">`) for the top-padding
     * strip drawn on padded-site cards; null → fall back to the primary color. */
    themeColorProvider: (String) -> Color? = { null },
    /** Resolves a tab's favicon (ContentState.icon) for the caption; null → no icon shown. */
    iconProvider: (String) -> Bitmap? = { null },
    /** Which overview layout to render. Selects the per-card slot function ([deckSlot]/[pixelSlot])
     * and the horizontal drag/scroll step; the gesture core stays layout-agnostic. */
    tabViewMode: TabViewMode = TabViewMode.DECK,
    /** Space (px) to reserve at the TOP of the overview (status-bar / display-cutout + the caption
     * band that hangs above each card) so the captions clear the hole punch. DECK/PIXEL only. */
    topInsetPx: Float = 0f,
    /** Space (px) to reserve at the bottom of the overview (for the floating controls). Cards ride
     * up off this band so they clear the controls. DECK/PIXEL only; GRID handles its own margin. */
    bottomInsetPx: Float = 0f,
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
    // Match the deck card's rounded corners to the device's PHYSICAL screen corner radius so each
    // card reads as a scaled-down phone (iOS app-switcher look). Query the real display corner
    // (RoundedCorner API, 31+) and divide by DeckFrontScale so the FRONT card's on-screen corner
    // equals the screen radius (the card is drawn at that scale). Fallback (older OS / device that
    // reports no corner) is a generous on-screen radius, still larger than the old flat 46dp.
    val view = LocalView.current
    val deckCornerPx = remember(view, density) {
        val screenRadiusPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.let { insets ->
                listOf(
                    RoundedCorner.POSITION_TOP_LEFT,
                    RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT,
                    RoundedCorner.POSITION_BOTTOM_RIGHT,
                ).mapNotNull { insets.getRoundedCorner(it)?.radius }.maxOrNull()?.toFloat()
            }
        } else {
            null
        }
        val onScreenRadius = screenRadiusPx ?: with(density) { 44.dp.toPx() }
        onScreenRadius / DeckFrontScale
    }
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
    val slotProvider: (Float) -> DeckSlot =
        remember(tabViewMode, containerW, containerH, croppedPageHeightPx, topInsetPx, bottomInsetPx) {
            when (tabViewMode) {
                TabViewMode.PIXEL -> { p ->
                    pixelSlot(p, containerW, containerH, croppedPageHeightPx, topInsetPx, bottomInsetPx)
                }
                // GRID falls back to the deck placement until the legacy 2-column grid is wired in.
                else -> { p ->
                    deckSlot(p, containerW, containerH, croppedPageHeightPx, topInsetPx, bottomInsetPx)
                }
            }
        }
    // Drag/scroll step matches the mode's visual centre-to-centre spacing so the deck tracks the
    // finger and snapping lands a card centred.
    val stepFrac = when (tabViewMode) {
        TabViewMode.PIXEL -> DeckPixelScale + DeckPixelGapFrac
        else -> DeckDragStepFrac
    }
    val stepPx = (stepFrac * containerW).coerceAtLeast(1f)
    // Composition cull window (deck position p). The deck's steep left decay hides cards by ~p=3, so
    // it needs only a tight window; Pixel mode's constant-scale cards stay on-screen across a much
    // wider p range (and rounding straddles slot boundaries during a scroll), so it gets a generous
    // symmetric window — a tight window there clips cards that are still visible while scrolling.
    val cullLow = if (tabViewMode == TabViewMode.PIXEL) -4 else -2
    val cullHigh = if (tabViewMode == TabViewMode.PIXEL) 4 else DeckVisibleDepth.toInt() + 1
    val maxScroll = (tabs.size - 1).coerceAtLeast(0).toFloat()
    val maxScrollPx = maxScroll * stepPx
    // Where the overview opens focused. In DECK (iOS app-switcher) seed one card OLDER than the
    // selected tab (recencyRank + 1) so opening slides the current tab to the RIGHT and centres the
    // previous tab ready to tap. In PIXEL (Android recents) there is no such offset — the current
    // tab opens dead-centre. Clamped, so an already-oldest current tab just stays centred.
    val initialScrollPx = remember(tabs, selectedTabId, stepPx, maxScrollPx, tabViewMode) {
        val rank = tabs.firstOrNull { it.id == selectedTabId }?.recencyRank ?: 0
        val seedRank = if (tabViewMode == TabViewMode.PIXEL) rank else rank + 1
        (seedRank.toFloat() * stepPx).coerceIn(0f, maxScrollPx)
    }
    // deckDragging + deckScrollDrag are the hot-path pair (written directly per pointer sample, no
    // coroutine); deckScrollAnim carries the seed and the release fling. Same split as the grid's
    // gridDragProgress / gridProgressState (invariant #4): never snapTo on the drag hot path.
    var deckDragging by remember { mutableStateOf(false) }
    var deckScrollDrag by remember { mutableFloatStateOf(0f) }
    val deckScrollAnim = remember { Animatable(0f) }
    // Seed once to centre the selected tab. On a LATER stepPx change (container resize / inset settle
    // after the overlay mounts) the stored scroll is in PIXELS of the OLD step, so RESCALE it into the
    // new units — otherwise the pixel value keeps meaning the old number of cards while the draw and
    // the cull (scrollBucket, below) start dividing by the new stepPx. The two then measure scroll in
    // different units and diverge proportionally to the scroll distance: every on-screen card gets
    // culled as you scroll away from the seed (the deck "thinks" the focused tab is far off to the
    // side). Rescaling keeps the SAME card centred and both readers in the same units.
    var lastStepPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(stepPx) {
        val prev = lastStepPx
        lastStepPx = stepPx
        when {
            prev <= 0f -> deckScrollAnim.snapTo(initialScrollPx)
            prev != stepPx && !deckDragging -> {
                val cardPos = deckScrollAnim.value / prev
                deckScrollAnim.snapTo((cardPos * stepPx).coerceIn(0f, maxScrollPx))
            }
        }
    }
    // Hard-bound the scroll to [0, maxScrollPx]. Without this the release fling re-applies the
    // release velocity on top of an already-projected target, so a decisive fling toward either
    // end SPRING-OVERSHOOTS past the last/first card: the front-card position (p) then lands off the
    // valid range, every card's slot slides clear off the viewport, and the whole overview flashes
    // to the bare surfaceDim backdrop (the "everything goes black" bug — most visible in the
    // constant-scale Pixel filmstrip). Bounding the Animatable clamps both the fling and any
    // animateTo, so the scroll always rests on a real card. (deckScrollDrag is already hand-coerced.)
    LaunchedEffect(maxScrollPx) {
        deckScrollAnim.updateBounds(0f, maxScrollPx)
        if (!deckDragging && deckScrollAnim.value > maxScrollPx) {
            deckScrollAnim.animateTo(maxScrollPx)
        }
    }
    val flingSpec = rememberSplineBasedDecay<Float>()
    // Settle spring: after the momentum is projected forward it lands, critically damped (no
    // bounce), on the nearest card so the deck always rests with a card cleanly focused.
    val settleSpring = remember { spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessLow) }

    // ── Close-gap animation ──────────────────────────────────────────────────────────────────────
    // When a tab is removed (swipe-to-close), every surviving card's recencyRank shifts, which would
    // otherwise SNAP the cards to their new deck slots the instant the model updates. Instead each
    // card whose slot position changed gets a per-card offset (closeShifts) seeded to its OLD relative
    // position and sprung back to 0, so the neighbours visibly slide in to fill the gap (iOS/Pixel
    // recents style). The deck scroll is snapped to keep the same card centred, so closing a *newer*
    // card doesn't shove the focus sideways. Read ONLY in the draw phase, like every per-card value.
    val closeShifts = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val prevRanks = remember { mutableMapOf<String, Int>() }
    LaunchedEffect(tabs) {
        val newRanks = HashMap<String, Int>(tabs.size)
        tabs.forEach { newRanks[it.id] = it.recencyRank }
        // First composition of this deck session: seed the baseline, animate nothing.
        if (prevRanks.isEmpty()) {
            prevRanks.putAll(newRanks)
            return@LaunchedEffect
        }
        val sOld = if (stepPx > 0f) deckScrollAnim.value / stepPx else 0f
        val anchorRankOld = sOld.roundToInt()
        val anchorId = prevRanks.entries.firstOrNull { it.value == anchorRankOld }?.key
        // Where should the scroll land so the same card stays centred after the ranks shift?
        val sNewTarget: Float = run {
            val anchorNew = anchorId?.let { newRanks[it] }
            if (anchorNew != null) {
                sOld + (anchorNew - anchorRankOld)
            } else {
                // The centred card itself was closed: centre the card that fills its slot (the
                // next-older card if it survives, else the next-newer one).
                val fillId = prevRanks.entries
                    .firstOrNull { it.value == anchorRankOld + 1 && newRanks.containsKey(it.key) }?.key
                    ?: prevRanks.entries
                        .firstOrNull { it.value == anchorRankOld - 1 && newRanks.containsKey(it.key) }?.key
                val fillNew = fillId?.let { newRanks[it] }
                if (fillNew != null) fillNew + (sOld - anchorRankOld) else sOld
            }
        }
        val sNew = if (stepPx > 0f) {
            (sNewTarget * stepPx).coerceIn(0f, maxScrollPx) / stepPx
        } else {
            0f
        }
        if (sNew != sOld) deckScrollAnim.snapTo(sNew * stepPx)
        // Seed each surviving card at where it was drawn a frame ago, then spring the offset to 0.
        tabs.forEach { tab ->
            val oldRank = prevRanks[tab.id] ?: return@forEach
            val delta = (oldRank - sOld) - (tab.recencyRank - sNew)
            if (abs(delta) > 0.001f) {
                val anim = closeShifts.getOrPut(tab.id) { Animatable(0f) }
                launch {
                    anim.snapTo(anim.value + delta)
                    anim.animateTo(
                        0f,
                        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                    )
                }
            }
        }
        prevRanks.clear()
        prevRanks.putAll(newRanks)
        closeShifts.keys.retainAll(newRanks.keys)
    }

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
    val themeColorProviderHolder = rememberUpdatedState(themeColorProvider)
    val iconProviderHolder = rememberUpdatedState(iconProvider)
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
    val themeColorProviderFwd: (String) -> Color? = remember { { id -> themeColorProviderHolder.value(id) } }
    val iconProviderFwd: (String) -> Bitmap? = remember { { id -> iconProviderHolder.value(id) } }
    val onTabSelectedFwd: (String) -> Unit = remember { { id -> onTabSelectedHolder.value(id) } }
    val onCloseTabFwd: (String) -> Unit = remember { { id -> onCloseTabHolder.value(id) } }
    val cellsClickableFwd: () -> Boolean = remember { { progressHolder.value() > 0.5f } }
    // Stable per-tab close-gap offset reader. Passed by identity (not a per-card lambda) so cards
    // stay skippable; each card calls it with its own id inside its draw-phase block.
    val closeShiftFwd: (String) -> Float = remember { { id -> closeShifts[id]?.value ?: 0f } }

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
    // Keyed on stepPx so the cull rebuilds against the CURRENT stackScrollFwd (itself remember(stepPx))
    // whenever the step changes. Without the key this permanently captures the first stackScrollFwd and
    // keeps dividing the pixel scroll by the OLD stepPx while the draw uses the new one — the cull then
    // drifts off the drawn centre and blanks the whole deck as you scroll (see the seed rescale above).
    val scrollBucket by remember(stepPx) { derivedStateOf { stackScrollFwd().roundToInt() } }

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
                    // Front card index at the last haptic tick. A featherweight tick fires each time
                    // the drag crosses into a new card (Pixel-recents style); tracked here in the
                    // gesture scope so it's finger-drag only — the release fling doesn't buzz.
                    var lastTickIndex = 0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (scrollEnabledState.value) {
                                deckDragging = true
                                deckScrollDrag = deckScrollAnim.value
                                lastTickIndex = (deckScrollDrag / stepPx).roundToInt()
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
                                // Subtle detent each time a new card takes the focus slot.
                                val idx = (deckScrollDrag / stepPx).roundToInt()
                                if (idx != lastTickIndex) {
                                    lastTickIndex = idx
                                    haptics.step()
                                }
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
                                    ) {
                                        // Keep ticking through the inertial settle: the fling
                                        // crosses cards after the finger lifts, so continue the
                                        // per-index detent (Pixel-recents style) off the same
                                        // counter so there's no double/skipped tick at hand-off.
                                        val idx = (value / stepPx).roundToInt()
                                        if (idx != lastTickIndex) {
                                            lastTickIndex = idx
                                            haptics.step()
                                        }
                                    }
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
                        // Fan into the deck: compose cards near the finger at release OR whose slot
                        // is on-screen (mode-aware cull window).
                        abs(tab.slotDelta + shiftSlots) <= GestureVisibleSlots ||
                            (p >= cullLow && p <= cullHigh)
                    } else {
                        p >= cullLow && p <= cullHigh
                    }
                    if (composeCard) {
                        GridTabCard(
                            tab = tab,
                            isSelected = tab.id == selectedTabId,
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
                            closeShiftProvider = closeShiftFwd,
                            swipeOffsets = swipeOffsets,
                            scrollEnabled = scrollEnabledState,
                            snapshotVersion = snapshotVersion,
                            snapshotProvider = snapshotProviderFwd,
                            themeColorProvider = themeColorProviderFwd,
                            cellsClickable = cellsClickableFwd,
                            onTabSelected = onTabSelectedFwd,
                            onCloseTab = onCloseTabFwd,
                            haptics = haptics,
                            coroutineScope = coroutineScope,
                        )
                    }
                } // key(tab.id)
            }

            // Caption above each visible card: every visible card shows its favicon just above its
            // top-left corner; only the centred card (p == 0) additionally shows the left-aligned
            // title + host text. Rendered after the cards so it paints on top; suppressed while the
            // finger is down (captions appear only after release).
            if (!isGesture) {
                tabs.forEach { tab ->
                    key(tab.id) {
                        val p = tab.recencyRank - scrollBucket
                        if (p >= -1 && p <= 2) {
                            GridCellChrome(
                                tab = tab,
                                icon = iconProviderFwd(tab.id),
                                // Compose the text for the near-centre cards so it can cross-fade;
                                // the actual alpha tracks distance-to-centre in the draw phase.
                                textCandidate = p >= -1 && p <= 1,
                                containerW = containerW,
                                progress = progressFwd,
                                stackScroll = stackScrollFwd,
                                slotProvider = slotProvider,
                                closeShiftProvider = closeShiftFwd,
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
    isSelected: Boolean,
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
    closeShiftProvider: (String) -> Float,
    swipeOffsets: SnapshotStateMap<String, Float>,
    scrollEnabled: State<Boolean>,
    snapshotVersion: State<Int>,
    snapshotProvider: (String) -> Bitmap?,
    themeColorProvider: (String) -> Color?,
    cellsClickable: () -> Boolean,
    onTabSelected: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    haptics: LuminaHaptics,
    coroutineScope: CoroutineScope,
) {
    val density = LocalDensity.current
    val padDp = with(density) { tab.browseTopPadPx.toDp() }
    val padPx = tab.browseTopPadPx.toFloat()
    // Fill of the top-padding strip on a padded-site card: the site's declared theme color, or the
    // primary color when none was declared — matching the browse-view status-bar tint.
    val topStripColor = themeColorProvider(tab.id) ?: MaterialTheme.colorScheme.primary
    val tabLabel = remember(tab.url, tab.title) {
        if (tab.url.isBlankTabUrl()) "New tab page"
        else tab.title.ifBlank { hostLabel(tab.url).ifEmpty { tab.url } }
    }
    // Primary-colored outline that marks the currently-selected tab in the deck (the one that
    // reopens on close / commits under the predictive-back preview). Drawn draw-phase only so it
    // costs nothing on non-selected cards and never recomposes on the drag hot path.
    val selectedOutlineColor = MaterialTheme.colorScheme.primary
    val selectedOutlineWidthPx = with(density) { 3.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Constant layout height: the fullscreen→deck page crop animates via the layer's crop
            // shape below, not via layout, so a drag frame never measures or lays anything out.
            // The top-padding inset is applied to the card's CONTENT below (a colored strip + the
            // inset snapshot), NOT as outer padding: outer padding would shift this transform's
            // origin down by padPx and float a padded-site card lower than its neighbours.
            .height(containerHDp)
            .graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                // Per-card offset that animates surviving cards into the gap left by a closed tab
                // (0 except briefly after a close). Applied to the deck position so the neighbours
                // slide rather than snap.
                val closeShift = closeShiftProvider(tab.id)
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
                        val liveScale = deckDragScale(p, dragOffsetPx(), containerH * DeckTravelFrac)
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
                        val slot = slotProvider(tab.recencyRank - stackScroll() + closeShift)
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
                        val slot = slotProvider(tab.recencyRank - stackScroll() + closeShift)
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
                    // The card is full-height including its top strip (the strip + snapshot are laid
                    // out inside), so the crop is NOT reduced by padPx — that keeps every card the
                    // same height and top-aligned regardless of the per-site top padding.
                    cropHeightPx = lerp(containerH, croppedPageHeightPx, p),
                )
                // Drop shadow behind every card so each reads as physically lifted off the
                // surfaceDim backdrop. The squircle `shape` above is reused as the shadow outline;
                // the elevation lives inside this transform layer, so it scales and moves with the
                // card for free. Ramped by `p` so it grows in as cards fan into the deck and there's
                // no halo during the near-fullscreen open/close morph.
                shadowElevation = DeckCardShadowDp.toPx() * p
            }
            .then(
                if (isSelected) {
                    Modifier.drawWithContent {
                        drawContent()
                        // Skip while the finger is dragging the carousel (no selection affordance
                        // mid-gesture); otherwise ramp with the open progress so the outline grows
                        // in as cards fan into the deck and fades out as the selected card fills the
                        // screen on commit — mirroring the card's own shadow ramp.
                        if (isGesture) return@drawWithContent
                        val p = progress().coerceIn(0f, 1f)
                        if (p <= 0.02f) return@drawWithContent
                        val closeShift = closeShiftProvider(tab.id)
                        val slot = slotProvider(tab.recencyRank - stackScroll() + closeShift)
                        // The card is scaled by the layer; divide the stroke by that visual scale so
                        // the outline keeps a constant on-screen weight regardless of deck depth.
                        val scale = lerp(1f, slot.scale, p).coerceAtLeast(0.05f)
                        val cropH = lerp(containerH, croppedPageHeightPx, p)
                            .coerceIn(1f, size.height)
                        val radius = lerp(browseCornerPx, deckCornerPx, p)
                        val sw = selectedOutlineWidthPx / scale
                        // Inset by half the stroke so the outer edge lands on the layer's clip
                        // boundary (same CardCropShape) and the full stroke stays visible.
                        val r = (radius - sw / 2f)
                            .coerceIn(0f, min(size.width - sw, cropH - sw).coerceAtLeast(0f) / 2f)
                        val path = squirclePath(
                            w = (size.width - sw).coerceAtLeast(0f),
                            h = (cropH - sw).coerceAtLeast(0f),
                            r = r,
                        )
                        translate(sw / 2f, sw / 2f) {
                            drawPath(
                                path = path,
                                color = selectedOutlineColor,
                                alpha = p,
                                style = Stroke(width = sw),
                            )
                        }
                    }
                } else {
                    Modifier
                }
            )
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
                val closeTracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { closeTracker.resetTracking() },
                    onDragEnd = {
                        coroutineScope.launch {
                            val offset = swipeOffsets[tab.id] ?: 0f
                            // Up-swipe closes the tab (card flies off the top). It commits either on
                            // distance (~22% of the screen) OR on an upward flick — feeding the pointer
                            // into a VelocityTracker means a quick decisive swipe dismisses without
                            // needing to drag most of the way up. Anything shorter/slower springs back.
                            val vY = closeTracker.calculateVelocity().y
                            val closeByDistance = offset < -containerH * 0.22f
                            val closeByFling = vY < -1200f && offset < 0f
                            if (closeByDistance || closeByFling) {
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
                            closeTracker.addPosition(change.uptimeMillis, change.position)
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

            // Inset the page content (snapshot / placeholder) below the top-padding strip, exactly
            // as the browse view pads the EngineView down. The captured snapshot already excludes
            // the strip region, so this keeps the card a faithful miniature of the padded page.
            Box(modifier = Modifier.fillMaxSize().padding(top = padDp)) {
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
                    // Overlay the live page's system-tint boost on the snapshot at draw time so the
                    // card matches the browse view (no-op when the boost is off).
                    val siteTint = LocalSiteTint.current
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
                                }
                                .siteTintOverlay(siteTint),
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
                                }
                                .siteTintOverlay(siteTint),
                        )
                    }
                }
            }
            // Theme-color strip over the reserved top-padding band (Safari-style tint), matching the
            // browse-view status bar. Only padded-site cards have a non-zero strip.
            if (padPx > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(padDp)
                        .align(Alignment.TopStart)
                        .background(topStripColor),
                )
            }
            // Archive-mode badge: marks the thumbnail as a Wayback (time-travelled) snapshot while
            // the caption/favicon still read as the live site (AGENTS.md — archive integration).
            if (tab.isArchive) {
                ArchiveThumbnailBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                )
            }
        }
    }
}

/**
 * The small "Archived" pill drawn in the bottom-right of a tab thumbnail when the tab is viewing a
 * Wayback snapshot. Shared by both overview layouts (deck/pixel [GridTabCard] and the classic grid).
 */
@Composable
internal fun ArchiveThumbnailBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Archived",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * The caption above a visible deck card, following its slot in the draw phase. EVERY visible card
 * shows its favicon just above its top-left corner; only the centred card ([showText]) additionally
 * shows the left-aligned page title + host. Anchored to the card's left edge with a fixed-height
 * bottom-aligned box, so the favicon-only and favicon+text variants both hug the card's top-left
 * corner without measuring. Skippable; the slot position, swipe offset, and alpha are draw-phase
 * layer reads.
 */
@Composable
private fun GridCellChrome(
    tab: GridTab,
    icon: Bitmap?,
    textCandidate: Boolean,
    containerW: Float,
    progress: () -> Float,
    stackScroll: () -> Float,
    slotProvider: (Float) -> DeckSlot,
    closeShiftProvider: (String) -> Float,
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
    val iconBitmap = remember(icon) { icon?.asImageBitmap() }
    val density = LocalDensity.current
    val labelWidthDp = with(density) { (containerW * DeckFrontScale).toDp() }
    val boxHeightDp = 60.dp
    Box(
        modifier = Modifier
            .graphicsLayer {
                val closeShift = closeShiftProvider(tab.id)
                val slot = slotProvider(tab.recencyRank - stackScroll() + closeShift)
                val swipeOffset = swipeOffsets[tab.id] ?: 0f
                // Left edge at the card's left edge; the box bottom sits a small gap above the card
                // top so the bottom-aligned content hugs the top-left corner. Rides up on a swipe.
                translationX = slot.tx
                translationY = slot.ty + swipeOffset - boxHeightDp.toPx() - 6.dp.toPx()
                alpha = progress().coerceIn(0f, 1f) * slot.alpha
            }
            .width(labelWidthDp)
            .height(boxHeightDp),
        contentAlignment = Alignment.BottomStart,
    ) {
        // Favicon on the left; the title + host stack to its right (centred against it), the whole
        // text block cross-fading with distance-to-centre.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!tab.url.isBlankTabUrl()) {
                FaviconTile(iconBitmap, host, size = 30.dp)
            }
            if (textCandidate) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .graphicsLayer {
                            alpha = (1f - abs(tab.recencyRank - stackScroll() + closeShiftProvider(tab.id)))
                                .coerceIn(0f, 1f)
                        },
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
        }
    }
}

/**
 * The favicon shown above a deck card: the real [ContentState.icon] when available, otherwise a
 * coloured letter tile (host initial). Guarantees every non-blank tab shows an identifying mark,
 * since [ContentState.icon] is null for tabs that never loaded a favicon this session.
 */
@Composable
private fun FaviconTile(icon: ImageBitmap?, host: String, size: Dp) {
    val shape = RoundedCornerShape(6.dp)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(shape),
        )
    } else {
        val initial = remember(host) {
            host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
        }
        val tileColor = remember(host) {
            if (host.isEmpty()) Color(0xFF7E7E7E)
            else Color.hsl((abs(host.hashCode()) % 360).toFloat(), 0.45f, 0.55f)
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(tileColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = Color.White,
                fontSize = (size.value * 0.52f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
