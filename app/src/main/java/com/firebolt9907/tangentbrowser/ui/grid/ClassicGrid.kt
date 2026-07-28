package com.firebolt9907.tangentbrowser.ui.grid

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.firebolt9907.tangentbrowser.ui.TangentHaptics
import com.firebolt9907.tangentbrowser.ui.content.LocalSiteTint
import com.firebolt9907.tangentbrowser.ui.content.siteTintOverlay
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import kotlin.math.abs
import kotlin.math.roundToInt

// The drag morph layers are full viewport-sized bitmap textures until they reach the grid scale.
// At 120 Hz, drawing five of them simultaneously can exceed the GPU budget even though only the
// centred card and its immediate neighbours are useful visual context. The full grid is composed
// immediately after release, before cards fan into their final cells.
private const val GestureVisibleSlots = 1

// GridTab, GridRect, gridCellRect, the Grid* margin constants, CardCropShape, SnapshotTracker,
// and the hostLabel/isBlankTabUrl/lerp helpers live in GridModel.kt.

/**
 * Renders every (near-viewport) tab simultaneously. Each cell starts at its true carousel position
 * ([GridTab.browseBaseX] + the live carousel shift, matching the browse-layer overlay
 * pixel-for-pixel) and ends at its grid cell.
 *
 * Hot-path contract: [progress], [xProgress], [carouselShift], [dragOffsetPx], and the per-card
 * swipe-to-close offsets are read ONLY inside `graphicsLayer` / `derivedStateOf` blocks. During a
 * drag no recomposition or layout happens — every per-frame value (position, scale, corner radius,
 * page crop, alphas) is a draw-phase layer property. Composition changes only at phase boundaries
 * (gesture ↔ springs ↔ open) and when the quantized (half-screen) scroll-culling bucket slides.
 *
 * Recomposition contract: each card is a skippable composable ([GridTabCard] / [GridCellChrome])
 * whose inputs are all stable. The caller's lambdas are re-allocated on most of its recompositions
 * (they capture local functions), so they are wrapped in remembered forwarders here; a body
 * re-execution of this overlay then costs only the cell-geometry loop while unchanged cards skip.
 * Snapshot bitmaps resolve inside each card via [derivedStateOf] keyed on [snapshotVersion], so a
 * thumbnail landing from the disk cache recomposes only the card whose bitmap actually changed.
 */
@Composable
fun ClassicTabGridOverlay(
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
     * Non-zero during the commit spring (finger released, grid opening). The spring animates
     * [progress] from this value to 1. Tabs lerp from [releasePositions] to grid cells using
     * a normalized t = (progress − commitFromProgress) / (1 − commitFromProgress).
     */
    commitFromProgress: Float = 0f,
    /**
     * Non-zero during the cancel spring (finger released, grid closing). Tabs lerp from
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
    /** Resolves a tab's container swatch; null → Personal/default (no dot shown). */
    containerColorProvider: (String) -> Color? = { null },
    /**
     * Measured height (px) of the floating bottom controls — nav-bar inset + toolbar + the handoff
     * banner when shown. The last row's bottom clearance is derived from this so it tracks button vs
     * gesture navigation and the banner's presence. 0 until first measured; falls back to a static
     * estimate for that first frame.
     */
    bottomControlsHeightPx: Int = 0,
) {
    if (containerSize.width <= 0 || containerSize.height <= 0 || tabs.isEmpty()) return
    val density = LocalDensity.current
    val haptics = rememberTangentHaptics()
    val coroutineScope = rememberCoroutineScope()
    val swipeOffsets = remember { mutableStateMapOf<String, Float>() }

    LaunchedEffect(tabs) {
        val currentIds = tabs.map { it.id }.toSet()
        swipeOffsets.keys.retainAll(currentIds)
    }

    val sideMarginPx = with(density) { GridSideMarginDp.toPx() }
    val topMarginPx = with(density) { GridTopMarginDp.toPx() }
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    // Clear the last row of the floating bottom controls. `bottomControlsHeightPx` already includes
    // the nav-bar inset (the controls are laid out inside navigationBarsPadding), so it adapts to
    // button vs gesture navigation and grows when the handoff banner is showing — plus a small gap
    // for breathing room. Before the controls are first measured, fall back to a static estimate
    // (constant margin + nav bar) so the very first opened frame isn't under-padded.
    val bottomGapPx = with(density) { GridBottomMarginDp.toPx() }
    val bottomMarginPx = if (bottomControlsHeightPx > 0) {
        bottomControlsHeightPx + bottomGapPx
    } else {
        bottomGapPx + navBarPx
    }
    val gapPx = with(density) { GridGapDp.toPx() }
    val horizontalGapPx = gapPx
    val verticalGapPx = with(density) { 36.dp.toPx() } // extra space for domain name/label below the card
    // Cell clip radius is in the card's *unscaled* (full-container) space and is then shrunk by the
    // cell's scale (~0.44), so this is intentionally large to land at a generous on-screen radius.
    // The radius lerps from the browse-mode value (matching the horizontal-switch overlay) inside
    // each card's layer shape, so at progress=0 the card is pixel-identical to the browse layer.
    val browseCornerPx = with(density) { browseCornerDp.toPx() }
    val gridCornerPx = with(density) { 52.dp.toPx() }
    val containerW = containerSize.width.toFloat()
    val containerH = containerSize.height.toFloat()
    val containerHDp = with(density) { containerH.toDp() }

    val totalItems = tabs.size + 1 // +1 for the new-tab cell
    val rows = (totalItems + GridColumns - 1) / GridColumns
    val cellW = (containerW - 2 * sideMarginPx - (GridColumns - 1) * gapPx) / GridColumns
    val cellH = cellW * 4f / 3f
    // Scale floor during the gesture — must match the geometry TangentRoot uses to compute the
    // release positions, or the springs would start from a different scale than the drag showed.
    val minScale = (cellW / containerW).coerceIn(0.3f, 0.6f)
    // Page height (in the tab's own unscaled coordinate space) that a fully-open 3:4 cell reveals.
    // At the grid cell, on-screen card height = cellH = cellW*4/3, and the card is drawn at
    // scale = cellW/containerW, so the visible page slice = cellH / scale = containerW*4/3.
    val croppedPageHeightPx = if (containerW > 0f) containerW * 4f / 3f else containerH
    val rawContentHeightPx = topMarginPx + rows * cellH + (rows - 1).coerceAtLeast(0) * verticalGapPx + bottomMarginPx
    val contentHeightPx = rawContentHeightPx.coerceAtLeast(containerH)
    val contentHeightDp = with(density) { contentHeightPx.toDp() }

    // Seed the scroll position so the selected tab is already in view as the grid opens. The grid is
    // only composed while engaged, so every open re-enters this composable with a fresh scrollState;
    // without the seed the open animation lays cells out at scroll 0 and a tab deep in a long list
    // animates to an off-screen cell, then gets dragged back by the post-open centering scroll (the
    // jump we want to avoid). We seed the same centered target that post-open centering settles on,
    // so that effect finds the cell already fully visible and no-ops.
    val initialScroll = run {
        val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
        val cell = gridCellRect(
            index = selectedIndex,
            containerSize = containerSize,
            columns = GridColumns,
            horizontalGapPx = horizontalGapPx,
            verticalGapPx = verticalGapPx,
            sideMarginPx = sideMarginPx,
            topMarginPx = topMarginPx,
        )
        val maxScroll = (contentHeightPx - containerH).coerceAtLeast(0f)
        (cell.top + cell.height / 2f - containerH / 2f).coerceIn(0f, maxScroll).toInt()
    }
    val scrollState = rememberScrollState(initial = initialScroll)
    // Stable forwarders. The caller re-allocates the incoming lambdas on most of its
    // recompositions (they capture local functions with fresh identities), which would change
    // every card's parameters and defeat skipping. Each forwarder is remembered once and reads
    // the latest lambda through a rememberUpdatedState holder, so cards see a single stable
    // instance for the overlay's lifetime while still calling current code.
    val progressHolder = rememberUpdatedState(progress)
    val xProgressHolder = rememberUpdatedState(xProgress)
    val carouselShiftHolder = rememberUpdatedState(carouselShift)
    val dragOffsetHolder = rememberUpdatedState(dragOffsetPx)
    val snapshotProviderHolder = rememberUpdatedState(snapshotProvider)
    val themeColorProviderHolder = rememberUpdatedState(themeColorProvider)
    val containerColorProviderHolder = rememberUpdatedState(containerColorProvider)
    val onTabSelectedHolder = rememberUpdatedState(onTabSelected)
    val onCloseTabHolder = rememberUpdatedState(onCloseTab)
    val progressFwd: () -> Float = remember { { progressHolder.value() } }
    val xProgressFwd: () -> Float = remember { { xProgressHolder.value() } }
    val carouselShiftFwd: () -> Float = remember { { carouselShiftHolder.value() } }
    val dragOffsetFwd: () -> Float = remember { { dragOffsetHolder.value() } }
    val snapshotProviderFwd: (String) -> Bitmap? = remember { { id -> snapshotProviderHolder.value(id) } }
    val themeColorProviderFwd: (String) -> Color? = remember { { id -> themeColorProviderHolder.value(id) } }
    val containerColorProviderFwd: (String) -> Color? =
        remember { { id -> containerColorProviderHolder.value(id) } }
    val onTabSelectedFwd: (String) -> Unit = remember { { id -> onTabSelectedHolder.value(id) } }
    val onCloseTabFwd: (String) -> Unit = remember { { id -> onCloseTabHolder.value(id) } }
    // Cells become tappable once the grid is mostly open. Checked at click time rather than via a
    // composition-observed `enabled` flag — the 0.5 crossing otherwise recomposed every composed
    // card mid-spring, in both directions.
    val cellsClickableFwd: () -> Boolean = remember { { progressHolder.value() > 0.5f } }
    // Only allow scrolling once the grid is essentially fully open — during the open animation
    // the scroll offset must stay 0 so cells land in their target cells, not in scrolled positions.
    // Derived so the per-frame progress read flips a boolean instead of recomposing per frame.
    // Built from the rememberUpdatedState holders (not remember keys) so the derived instance is
    // stable across recompositions: the per-card swipe-to-close pointerInput reads it at event
    // time instead of keying on it, which used to cancel and relaunch every card's gesture
    // coroutine on the first frame of the close spring (and the last frame of the open spring).
    val isGestureState = rememberUpdatedState(isGesture)
    val commitFromProgressState = rememberUpdatedState(commitFromProgress)
    val cancelFromProgressState = rememberUpdatedState(cancelFromProgress)
    val scrollEnabledState = remember {
        derivedStateOf {
            !isGestureState.value && commitFromProgressState.value == 0f &&
                cancelFromProgressState.value == 0f && progressHolder.value() >= 0.999f
        }
    }
    val scrollEnabled by scrollEnabledState

    // Re-assert the seeded scroll for paths that enter at progress≈0 without a gesture (e.g. a
    // button-open whose spring hasn't moved yet): keep it on the same centered target the seed and
    // post-open centering use, so all three agree and the selected tab stays centered as it opens.
    // Only runs while progress≈0 with no gesture/spring, so the scroll change is invisible and the
    // ty lerp starts from the correct offset; setting it while progress>0 would teleport tabs.
    val gridIsClosed by remember(isGesture, commitFromProgress, cancelFromProgress) {
        derivedStateOf {
            progress() < 0.001f && !isGesture &&
                commitFromProgress == 0f && cancelFromProgress == 0f
        }
    }
    LaunchedEffect(gridIsClosed, selectedTabId) {
        if (gridIsClosed) {
            scrollState.scrollTo(initialScroll)
        }
    }

    // After the grid finishes opening, ensure the tab you came from is actually on-screen. The
    // open animation lays cells out at scroll 0, so a tab deep in a long list would otherwise be
    // left off-screen below the fold. Once scrolling is enabled (grid fully open) we smooth-scroll
    // to center the selected tab if it isn't already fully visible. This only touches scroll state,
    // not the open/close morph, so the entrance animation is unaffected.
    LaunchedEffect(scrollEnabled, selectedTabId) {
        if (!scrollEnabled) return@LaunchedEffect
        val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
        if (selectedIndex < 0) return@LaunchedEffect
        val cell = gridCellRect(
            index = selectedIndex,
            containerSize = containerSize,
            columns = GridColumns,
            horizontalGapPx = horizontalGapPx,
            verticalGapPx = verticalGapPx,
            sideMarginPx = sideMarginPx,
            topMarginPx = topMarginPx,
        )
        val current = scrollState.value.toFloat()
        val cellTopVisual = cell.top - current
        val cellBottomVisual = cellTopVisual + cell.height
        val fullyVisible = cellTopVisual >= topMarginPx && cellBottomVisual <= containerH
        if (!fullyVisible) {
            val maxScroll = (contentHeightPx - containerH).coerceAtLeast(0f)
            val centered = (cell.top + cell.height / 2f - containerH / 2f).coerceIn(0f, maxScroll)
            scrollState.animateScrollTo(centered.toInt())
        }
    }

    // Composition-level culling. During the gesture and the cancel spring, only cards within a
    // couple of carousel slots of the finger can be on screen; composing all of a many-tab grid
    // would pay layout and snapshot texture uploads for cards you can't see. The window is
    // quantized to whole slots so it recomposes only when the drag crosses a slot boundary —
    // never per frame.
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
    // Scroll offset for composition-level culling, quantized to half-screen buckets via
    // derivedStateOf so scrolling the open grid invalidates composition once per half-screen of
    // travel instead of on every scrolled pixel. The cull margins are at least a full screen on
    // each side, so the up-to-half-screen quantization error can never cull a visible row.
    // Per-frame card positioning still reads the exact scrollState.value in the draw phase.
    val scrollBucketPx = containerH / 2f
    val scrollBucket by remember(scrollBucketPx) {
        derivedStateOf { (scrollState.value / scrollBucketPx).toInt() }
    }
    val approxScrollPx = scrollBucket * scrollBucketPx

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Go opaque very quickly (fully opaque at ~5% progress) so GeckoView never shows
                // through the squircle corners during the gesture ramp-up.
                .graphicsLayer { alpha = (progress() * 20f).coerceIn(0f, 1f) }
                // surfaceDim is the darkest surface tone — using it as the overview backdrop makes
                // every card (surface / surfaceContainer) read as raised against it, in both light
                // and dark, without needing a cast shadow (the cards' CardCropShape is a generic
                // squircle path, on which elevation shadows don't render reliably).
                .background(MaterialTheme.colorScheme.surfaceDim),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState, enabled = scrollEnabled),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeightDp),
            ) {
                val drawOrder = remember(tabs, selectedTabId) {
                    tabs.indices.sortedWith(
                        compareBy<Int> { tabs[it].id == selectedTabId }
                            .thenBy { it },
                    )
                }
                drawOrder.forEach { index ->
                    val tab = tabs[index]
                    key(tab.id) {
                        val cell = gridCellRect(
                            index = index,
                            containerSize = containerSize,
                            columns = GridColumns,
                            horizontalGapPx = horizontalGapPx,
                            verticalGapPx = verticalGapPx,
                            sideMarginPx = sideMarginPx,
                            topMarginPx = topMarginPx,
                        )
                        val cellScale = if (containerW > 0f) cell.width / containerW else 1f
                        val composeCard = if (gestureLike) {
                            abs(tab.slotDelta + shiftSlots) <= GestureVisibleSlots
                        } else if (commitFromProgress > 0f) {
                            // Fan-out to cells. Only cards that can actually cross the viewport
                            // need composing: ones released near the finger, or whose destination
                            // cell lands within the (seeded) scroll window. Composing every card
                            // on the release frame paid layout + first-draw texture upload for
                            // rows that stay off-screen for the whole commit spring.
                            if (abs(tab.slotDelta + shiftSlots) <= GestureVisibleSlots) {
                                true
                            } else {
                                val visualTop = cell.top - approxScrollPx
                                visualTop + cell.height > -containerH && visualTop < containerH * 2f
                            }
                        } else {
                            // Static/open grid: cull rows scrolled far off-screen (bucketed
                            // scroll-time recomposition only; nothing animates per frame here).
                            val visualTop = cell.top - approxScrollPx
                            visualTop + cell.height > -containerH && visualTop < containerH * 2f
                        }
                        if (composeCard) {
                            GridTabCard(
                                tab = tab,
                                cell = cell,
                                cellScale = cellScale,
                                minScale = minScale,
                                containerW = containerW,
                                containerH = containerH,
                                containerHDp = containerHDp,
                                croppedPageHeightPx = croppedPageHeightPx,
                                browseCornerPx = browseCornerPx,
                                gridCornerPx = gridCornerPx,
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
                                scrollState = scrollState,
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

                // Domain pills — rendered after the cells in document order so they paint on top.
                // Positioned in unscaled grid-content coordinates (cell.left / cell.top) so they
                // appear at a consistent on-screen size rather than being shrunk by the cell's
                // graphicsLayer scale.
                // Suppress pills while the finger is still down — they should only fade in after
                // the user lifts and the commit spring starts. The fade itself is a draw-phase
                // layer alpha (progress()), so the springs don't recompose this section.
                if (!isGesture) {
                    tabs.forEachIndexed { index, tab ->
                        key(tab.id) {
                            val cell = gridCellRect(
                                index = index,
                                containerSize = containerSize,
                                columns = GridColumns,
                                horizontalGapPx = horizontalGapPx,
                                verticalGapPx = verticalGapPx,
                                sideMarginPx = sideMarginPx,
                                topMarginPx = topMarginPx,
                            )
                            // Skip overlay work (pill + close button) for rows scrolled off-screen.
                            val rowVisualTop = cell.top - approxScrollPx
                            val rowOnScreen =
                                rowVisualTop + cell.height > -containerH && rowVisualTop < containerH * 2f
                            if (rowOnScreen) {
                                GridCellChrome(
                                    tab = tab,
                                    cell = cell,
                                    isSelected = tab.id == selectedTabId,
                                    showClose = tabs.size > 1,
                                    containerW = containerW,
                                    gridCornerPx = gridCornerPx,
                                    containerColor = containerColorProviderFwd(tab.id),
                                    progress = progressFwd,
                                    cellsClickable = cellsClickableFwd,
                                    swipeOffsets = swipeOffsets,
                                    scrollEnabled = scrollEnabledState,
                                    onCloseTab = onCloseTabFwd,
                                    haptics = haptics,
                                )
                            }
                        } // key(tab.id)
                    }

                    // Fixed-position "+" cell — shown when grid is open and user has not yet tapped it.
                    if (!newTabSelected) {
                        val newTabCell = gridCellRect(
                            index = tabs.size,
                            containerSize = containerSize,
                            columns = GridColumns,
                            horizontalGapPx = horizontalGapPx,
                            verticalGapPx = verticalGapPx,
                            sideMarginPx = sideMarginPx,
                            topMarginPx = topMarginPx,
                        )
                        val newTabCellScale = if (containerW > 0f) newTabCell.width / containerW else 1f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(density) { croppedPageHeightPx.toDp() })
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    translationX = newTabCell.left
                                    translationY = newTabCell.top
                                    scaleX = newTabCellScale
                                    scaleY = newTabCellScale
                                    alpha = progress().coerceIn(0f, 1f)
                                    clip = true
                                    shape = CardCropShape(
                                        radiusPx = gridCornerPx,
                                        cropHeightPx = croppedPageHeightPx,
                                    )
                                }
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable {
                                    if (!cellsClickableFwd()) return@clickable
                                    haptics.create()
                                    onNewTab()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Content sits in the cell's unscaled coordinate space (~0.44x at
                            // runtime), so sizes here are ~2.3x their on-screen appearance:
                            // 96.dp icon ≈ 42.dp, 40.sp label ≈ 17.sp on screen.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(96.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "New Tab",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // Animated "+" cell for the grid→new-tab transition: expands from its grid cell
                // to fullscreen as the grid closes, identical to how a real tab would animate.
                // Real tabs have browseBaseX offsetted left (newSelIdx = tabs.size in gridTabs)
                // so they all slide off-screen left while this cell takes center.
                if (newTabSelected && !isGesture) {
                    val newTabCell = gridCellRect(
                        index = tabs.size,
                        containerSize = containerSize,
                        columns = GridColumns,
                        horizontalGapPx = horizontalGapPx,
                        verticalGapPx = verticalGapPx,
                        sideMarginPx = sideMarginPx,
                        topMarginPx = topMarginPx,
                    )
                    val newTabCellScale = if (containerW > 0f) newTabCell.width / containerW else 1f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(containerHDp)
                            .graphicsLayer {
                                val p = progress().coerceIn(0f, 1f)
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = lerp(0f, newTabCell.left, xProgress())
                                translationY = lerp(scrollState.value.toFloat(), newTabCell.top, p)
                                val scale = lerp(1f, newTabCellScale, p)
                                scaleX = scale
                                scaleY = scale
                                clip = true
                                shape = CardCropShape(
                                    radiusPx = lerp(browseCornerPx, gridCornerPx, p),
                                    cropHeightPx = lerp(containerH, croppedPageHeightPx, p),
                                )
                            }
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
    }
}

/**
 * One tab card: the morphing page layer. Skippable — all parameters are stable, and everything
 * per-frame (progress, carousel shift, drag position, swipe offset, scroll offset) is read only
 * inside the `graphicsLayer` block. The snapshot bitmap resolves through a [derivedStateOf] on
 * [snapshotVersion] whose structural (identity) policy means a cache version bump recomposes this
 * card only when *its* bitmap instance actually changed.
 */
@Composable
private fun GridTabCard(
    tab: GridTab,
    cell: GridRect,
    cellScale: Float,
    minScale: Float,
    containerW: Float,
    containerH: Float,
    containerHDp: Dp,
    croppedPageHeightPx: Float,
    browseCornerPx: Float,
    gridCornerPx: Float,
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
    scrollState: ScrollState,
    swipeOffsets: SnapshotStateMap<String, Float>,
    scrollEnabled: State<Boolean>,
    snapshotVersion: State<Int>,
    snapshotProvider: (String) -> Bitmap?,
    themeColorProvider: (String) -> Color?,
    cellsClickable: () -> Boolean,
    onTabSelected: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    haptics: TangentHaptics,
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Constant layout height: the fullscreen→3:4 page crop animates via
            // the layer's crop shape below, not via layout, so a drag frame never
            // measures or lays anything out. The top-padding inset is applied to the
            // card CONTENT below (strip + inset snapshot), NOT as outer padding —
            // outer padding would shift this transform's origin down by padPx and
            // float a padded-site card lower than its neighbours.
            .height(containerHDp)
            .graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                val scrollOffset = scrollState.value.toFloat()
                val scale: Float
                val tx: Float
                val ty: Float
                // The cells are inside a verticalScroll that offsets content by
                // -scrollOffset. The finger-tracked positions are screen-space, so
                // they're expressed in scroll-content space by adding the current
                // scroll offset. The static `else` branch does this via its
                // lerp-from-scrollOffset.
                when {
                    isGesture -> {
                        val liveScale = (1f - p * (1f - minScale)).coerceIn(minScale, 1f)
                        val fingerX = containerW / 2f + selectedShiftBase + carouselShift()
                        val fingerY = containerH - dragOffsetPx()
                        scale = liveScale
                        tx = (fingerX - containerW * liveScale / 2f) +
                            tab.slotDelta * pageStridePx * liveScale
                        ty = (fingerY - containerH * liveScale) + scrollOffset
                    }
                    commitFromProgress > 0f -> {
                        val from = releasePosition ?: Triple(0f, 0f, 1f)
                        val t = if (commitFromProgress < 1f) {
                            ((p - commitFromProgress) / (1f - commitFromProgress)).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                        scale = lerp(from.third, cellScale, t)
                        tx = lerp(from.first, cell.left, t)
                        ty = lerp(from.second + scrollOffset, cell.top, t)
                    }
                    cancelFromProgress > 0f -> {
                        val from = releasePosition ?: Triple(0f, 0f, 1f)
                        val t = (1f - p / cancelFromProgress).coerceIn(0f, 1f)
                        scale = lerp(from.third, 1f, t)
                        tx = lerp(from.first, tab.browseBaseX + carouselShift(), t)
                        // Target is fullscreen (on-screen y = 0) → content-space
                        // y = scrollOffset.
                        ty = lerp(from.second + scrollOffset, scrollOffset, t)
                    }
                    else -> {
                        scale = lerp(1f, cellScale, p)
                        tx = lerp(tab.browseBaseX + carouselShift(), cell.left, xProgress())
                        // cell.top is in scroll-content coordinates; visual
                        // y = ty - scrollOffset. Lerp from scrollOffset so that at
                        // progress=0 the tab lands at y=0 (fullscreen), not at
                        // y=-scrollOffset (above the screen).
                        ty = lerp(scrollOffset, cell.top, p)
                    }
                }
                // Swipe-to-close offset/fade are draw-phase reads too, so dragging a card
                // sideways never recomposes — it only re-draws this layer.
                val swipeOffset = swipeOffsets[tab.id] ?: 0f
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = tx + swipeOffset
                translationY = ty
                scaleX = scale
                scaleY = scale
                clip = true
                alpha = (1f - abs(swipeOffset) / (cell.width * 0.8f)).coerceIn(0f, 1f)
                shape = CardCropShape(
                    radiusPx = lerp(browseCornerPx, gridCornerPx, p),
                    // Local (pre-scale) visible height of the card. At fullscreen
                    // (progress 0) it is the full viewport so the open/close morph
                    // matches the browse layer; as the grid opens it shrinks to the
                    // 3:4 page slice, cropping everything below the fold. The card is
                    // full-height including its top strip (laid out inside), so the
                    // crop is NOT reduced by padPx — that keeps every cell the same
                    // height and top-aligned regardless of the per-site top padding.
                    cropHeightPx = lerp(containerH, croppedPageHeightPx, p),
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
                detectHorizontalDragGestures(
                    onDragStart = {},
                    onDragEnd = {
                        coroutineScope.launch {
                            val targetOffset = swipeOffsets[tab.id] ?: 0f
                            val threshold = cell.width * 0.4f
                            if (abs(targetOffset) > threshold) {
                                val endValue = if (targetOffset > 0) cell.width * 1.5f else -cell.width * 1.5f
                                animate(
                                    initialValue = targetOffset,
                                    targetValue = endValue,
                                    animationSpec = tween(durationMillis = 200)
                                ) { value, _ ->
                                    swipeOffsets[tab.id] = value
                                }
                                onCloseTab(tab.id)
                            } else {
                                animate(
                                    initialValue = targetOffset,
                                    targetValue = 0f,
                                    animationSpec = tween(durationMillis = 200)
                                ) { value, _ ->
                                    swipeOffsets[tab.id] = value
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            val targetOffset = swipeOffsets[tab.id] ?: 0f
                            animate(
                                initialValue = targetOffset,
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 200)
                            ) { value, _ ->
                                swipeOffsets[tab.id] = value
                            }
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        // Checked per event (not as a pointerInput key) so the
                        // enable flip never restarts the gesture coroutine.
                        if (scrollEnabled.value) {
                            change.consume()
                            val current = swipeOffsets[tab.id] ?: 0f
                            swipeOffsets[tab.id] = current + dragAmount
                        }
                    }
                )
            },
    ) {
        if (tab.url.isBlankTabUrl()) {
            Unit
        } else {
            // Identity-invalidating snapshot lookup: recomputes on every version bump, but only
            // recomposes this card when the resolved bitmap instance changed. The provider is
            // read through its holder so the lookup always uses current tab state.
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

            // Draw-phase-only crossfade: the Animatable lives outside any key() block so it
            // persists across snapshot changes. LaunchedEffect(snapshot) restarts the animation
            // when a new bitmap arrives; animAlpha is read ONLY inside graphicsLayer/drawBehind
            // blocks (draw phase), so the card never recomposes on animation frames.
            val crossfadeAlpha = remember { Animatable(if (isGesture) 1f else 1f) }
            LaunchedEffect(snapshot) {
                if (!isGesture && (animateOnLoad || prev != null)) {
                    crossfadeAlpha.snapTo(0f)
                    crossfadeAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(180),
                    )
                    tracker.previous = null
                } else {
                    crossfadeAlpha.snapTo(1f)
                }
            }
            val animAlpha by remember { derivedStateOf { crossfadeAlpha.value } }

            val showPlaceholder = snapshot == null || (prev == null && animAlpha < 1f)

            // Inset the page content (snapshot / placeholder) below the top-padding strip, exactly
            // as the browse view pads the EngineView down. The captured snapshot already excludes
            // the strip region, so this keeps the cell a faithful miniature of the padded page.
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
            // browse-view status bar. Only padded-site cells have a non-zero strip.
            if (padPx > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(padDp)
                        .align(Alignment.TopStart)
                        .background(topStripColor),
                )
            }
            // Archive-mode badge (shared with the deck/pixel overview) — marks a Wayback snapshot.
            if (tab.isArchive) {
                ArchiveThumbnailBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                )
            }
        }
    }
}

/**
 * The per-cell chrome drawn above the cards in unscaled grid-content coordinates: selected-tab
 * highlight ring, domain pill, and close button. Skippable; the swipe-to-close offset and the
 * open-progress fade are draw-phase layer reads.
 */
@Composable
private fun GridCellChrome(
    tab: GridTab,
    cell: GridRect,
    isSelected: Boolean,
    showClose: Boolean,
    containerW: Float,
    gridCornerPx: Float,
    containerColor: Color?,
    progress: () -> Float,
    cellsClickable: () -> Boolean,
    swipeOffsets: SnapshotStateMap<String, Float>,
    scrollEnabled: State<Boolean>,
    onCloseTab: (String) -> Unit,
    haptics: TangentHaptics,
) {
    val density = LocalDensity.current
    // Highlight ring around the currently-selected tab. Only shown once the
    // grid is fully open and static (scrollEnabled) — never during the
    // drag/open/close animation. Drawn in unscaled grid-content coordinates
    // (like the pills) so the stroke stays a constant on-screen thickness.
    if (isSelected && scrollEnabled.value) {
        val cellWidthDp = with(density) { cell.width.toDp() }
        val cellHeightDp = with(density) { cell.height.toDp() }
        // The cell's clip radius is in unscaled card space and gets shrunk by
        // the cell scale on screen. The highlight is drawn at the real cell
        // size (unscaled), so multiply by that same scale to match the card's
        // actual on-screen corner radius. (Fully open ⇒ radius = gridCornerPx.)
        val cellScale = if (containerW > 0f) cell.width / containerW else 1f
        val highlightShape = remember(cellScale) {
            SquircleShape(with(density) { (gridCornerPx * cellScale).toDp() })
        }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val swipeOffset = swipeOffsets[tab.id] ?: 0f
                    translationX = cell.left + swipeOffset
                    translationY = cell.top
                    alpha = (1f - abs(swipeOffset) / (cell.width * 0.8f)).coerceIn(0f, 1f)
                }
                .size(width = cellWidthDp, height = cellHeightDp)
                .border(3.dp, MaterialTheme.colorScheme.primary, highlightShape),
        )
    }
    val host = remember(tab.url) { hostLabel(tab.url) }
    if (host.isNotEmpty()) {
        val labelTopPx = cell.top + cell.height + with(density) { 6.dp.toPx() }
        val cellWidthDp = with(density) { cell.width.toDp() }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val swipeOffset = swipeOffsets[tab.id] ?: 0f
                    translationX = cell.left + swipeOffset
                    translationY = labelTopPx
                    alpha = progress().coerceIn(0f, 1f) * (1f - abs(swipeOffset) / (cell.width * 0.8f)).coerceIn(0f, 1f)
                }
                .width(cellWidthDp),
            contentAlignment = Alignment.Center
        ) {
            // A container dot precedes the host so the classic grid also shows which container each
            // tab uses; Personal/default (null) tabs show no dot.
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (containerColor != null) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(containerColor),
                    )
                }
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // Close ("×") button at the top-right of each cell. Hidden when only one
    // tab remains, so closing can never leave an empty browser. Positioned in
    // unscaled grid-content coordinates (like the domain pills) for a constant
    // on-screen size, and drawn after the cells so it receives the tap.
    if (showClose) {
        val closeSizePx = with(density) { 28.dp.toPx() }
        val closeMarginPx = with(density) { 6.dp.toPx() }
        val closeLeftPx = cell.left + cell.width - closeSizePx - closeMarginPx
        val closeTopPx = cell.top + closeMarginPx
        // The visible dot stays 28dp, but the tappable area is expanded to 44dp
        // (Material touch-target guideline) and centered on the dot so it's not
        // easy to mis-tap. The extra 8dp per side keeps the dot in the same spot.
        val touchExtraPx = with(density) { 8.dp.toPx() }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val swipeOffset = swipeOffsets[tab.id] ?: 0f
                    translationX = closeLeftPx - touchExtraPx + swipeOffset
                    translationY = closeTopPx - touchExtraPx
                    alpha = progress().coerceIn(0f, 1f) * (1f - abs(swipeOffset) / (cell.width * 0.8f)).coerceIn(0f, 1f)
                }
                .size(44.dp)
                .clip(CircleShape)
                .clickable {
                    if (!cellsClickable()) return@clickable
                    haptics.remove()
                    onCloseTab(tab.id)
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shadowElevation = 3.dp,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close tab",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
