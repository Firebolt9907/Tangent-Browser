package com.firebolt9907.luminabrowser.ui.grid

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape

val GridSideMarginDp = 16.dp
val GridTopMarginDp = 64.dp
val GridBottomMarginDp = 32.dp
val GridGapDp = 14.dp
const val GridColumns = 2

data class GridTab(
    val id: String,
    val url: String,
    val title: String,
    val snapshot: Bitmap?,
    /**
     * Translation X (px) of this tab in the carousel/browse layer. Tabs not currently visible on
     * screen still have a real position — they're at +/- pageStride * (i - selectedIndex) plus the
     * current carousel shift. This means at progress=0 every tab sits at its carousel slot
     * (off-screen for far tabs), and as progress→1 each tab smoothly slides from its real position
     * to its grid cell rather than all popping out from the center of the screen.
     */
    val browseTranslationX: Float = 0f,
    /** Top padding (px) the browse layer applies (site-prefs top inset). */
    val browseTopPadPx: Int = 0,
    /** Finger-tracking position at drag release — used as commit animation start point. */
    val dragTx: Float = 0f,
    val dragTy: Float = 0f,
    val dragScale: Float = 1f,
)

data class GridRect(val left: Float, val top: Float, val width: Float, val height: Float)

fun gridCellRect(
    index: Int,
    containerSize: IntSize,
    columns: Int,
    horizontalGapPx: Float,
    verticalGapPx: Float,
    sideMarginPx: Float,
    topMarginPx: Float,
): GridRect {
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    if (w <= 0f || h <= 0f) return GridRect(0f, 0f, 0f, 0f)
    val cellW = (w - 2 * sideMarginPx - (columns - 1) * horizontalGapPx) / columns
    // Tab cards are a fixed 3:4 (portrait) aspect — the page is cropped to its top region rather
    // than showing the full (very tall) viewport. See [TabGridOverlay] for the crop/morph math.
    val cellH = cellW * 4f / 3f
    val col = index % columns
    val row = index / columns
    val left = sideMarginPx + col * (cellW + horizontalGapPx)
    val top = topMarginPx + row * (cellH + verticalGapPx)
    return GridRect(left, top, cellW, cellH)
}

/**
 * Renders every tab simultaneously. Each cell starts at its true carousel position
 * ([GridTab.browseTranslationX], with the existing pad+graphicsLayer+clip pipeline matching the
 * browse-layer overlay pixel-for-pixel) and ends at its grid cell. Lerp is uniform across all
 * tabs, so off-screen tabs slide in from where they "actually are" rather than from the center.
 */
@Composable
fun TabGridOverlay(
    progress: Float,
    xProgress: Float,
    containerSize: IntSize,
    tabs: List<GridTab>,
    selectedTabId: String?,
    onTabSelected: (String) -> Unit,
    onNewTab: () -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    newTabSelected: Boolean = false,
    modifier: Modifier = Modifier,
    browseCornerDp: androidx.compose.ui.unit.Dp = 22.dp,
    /** True while the finger is still down — each tab uses its [GridTab.dragTx/dragTy/dragScale]. */
    isGesture: Boolean = false,
    /**
     * Non-zero during the commit spring (finger released, grid opening). The spring animates
     * [progress] from this value to 1. Tabs lerp from their drag-end positions to grid cells using
     * a normalized t = (progress − commitFromProgress) / (1 − commitFromProgress).
     */
    commitFromProgress: Float = 0f,
    /**
     * Non-zero during the cancel spring (finger released, grid closing). Tabs lerp from their
     * drag-end positions back to their browse/fullscreen carousel positions as [progress] returns
     * to 0.
     */
    cancelFromProgress: Float = 0f,
) {
    if (containerSize.width <= 0 || containerSize.height <= 0 || tabs.isEmpty()) return
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val sideMarginPx = with(density) { GridSideMarginDp.toPx() }
    val topMarginPx = with(density) { GridTopMarginDp.toPx() }
    // Add the system navigation-bar inset so the last row scrolls clear of the gesture bar / taskbar
    // instead of being tucked under it.
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    val bottomMarginPx = with(density) { GridBottomMarginDp.toPx() } + navBarPx
    val gapPx = with(density) { GridGapDp.toPx() }
    // Cell clip radius is in the card's *unscaled* (full-container) space and is then shrunk by the
    // cell's scale (~0.44), so this is intentionally large to land at a generous on-screen radius.
    val gridCornerDp = 52.dp
    // Interpolate corner radius from the browse-mode value (matching the horizontal-switch overlay)
    // to the grid cell value. At progress=0 the card is pixel-identical to the browse layer so
    // the transition into grid mode has no visible corner-radius pop.
    val effectiveCornerDp = lerp(browseCornerDp.value, gridCornerDp.value, progress.coerceIn(0f, 1f)).toInt().dp
    val cellShape = remember(effectiveCornerDp) { SquircleShape(effectiveCornerDp) }
    val containerW = containerSize.width.toFloat()
    val containerH = containerSize.height.toFloat()
    // Go opaque very quickly (fully opaque at ~5% progress) so GeckoView never shows through the
    // squircle corners during the gesture ramp-up.
    val backgroundAlpha = (progress * 20f).coerceIn(0f, 1f)

    val totalItems = tabs.size + 1 // +1 for the new-tab cell
    val rows = (totalItems + GridColumns - 1) / GridColumns
    val cellW = (containerW - 2 * sideMarginPx - (GridColumns - 1) * gapPx) / GridColumns
    val cellH = cellW * 4f / 3f
    // Page height (in the tab's own unscaled coordinate space) that a fully-open 3:4 cell reveals.
    // At the grid cell, on-screen card height = cellH = cellW*4/3, and the card is drawn at
    // scale = cellW/containerW, so the visible page slice = cellH / scale = containerW*4/3.
    val croppedPageHeightPx = if (containerW > 0f) containerW * 4f / 3f else containerH
    val rawContentHeightPx = topMarginPx + rows * cellH + (rows - 1).coerceAtLeast(0) * gapPx + bottomMarginPx
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
            horizontalGapPx = gapPx,
            verticalGapPx = gapPx,
            sideMarginPx = sideMarginPx,
            topMarginPx = topMarginPx,
        )
        val maxScroll = (contentHeightPx - containerH).coerceAtLeast(0f)
        (cell.top + cell.height / 2f - containerH / 2f).coerceIn(0f, maxScroll).toInt()
    }
    val scrollState = rememberScrollState(initial = initialScroll)
    // Only allow scrolling once the grid is essentially fully open — during the open animation
    // the scroll offset must stay 0 so cells land in their target cells, not in scrolled positions.
    val scrollEnabled = !isGesture && commitFromProgress == 0f && cancelFromProgress == 0f &&
        progress >= 0.999f

    // Re-assert the seeded scroll for paths that enter at progress≈0 without a gesture (e.g. a
    // button-open whose spring hasn't moved yet): keep it on the same centered target the seed and
    // post-open centering use, so all three agree and the selected tab stays centered as it opens.
    // Only runs while progress≈0 with no gesture/spring, so the scroll change is invisible and the
    // ty lerp starts from the correct offset; setting it while progress>0 would teleport tabs.
    val gridIsClosed = progress < 0.001f && !isGesture &&
        commitFromProgress == 0f && cancelFromProgress == 0f
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
            horizontalGapPx = gapPx,
            verticalGapPx = gapPx,
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

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backgroundAlpha }
                .background(MaterialTheme.colorScheme.background),
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
                        horizontalGapPx = gapPx,
                        verticalGapPx = gapPx,
                        sideMarginPx = sideMarginPx,
                        topMarginPx = topMarginPx,
                    )
                    val cellScale = if (containerW > 0f) cell.width / containerW else 1f
                    val scale: Float
                    val tx: Float
                    val ty: Float
                    // The cells are inside a verticalScroll that offsets content by -scrollOffset.
                    // The finger-tracked drag positions (dragTy) are screen-space, so to keep them
                    // visually accurate (and the selected tab on-screen even when the grid is
                    // pre-scrolled to it) we express them in scroll-content space by adding the
                    // current scroll offset. The static `else` branch already does this via its
                    // lerp-from-scrollOffset.
                    val scrollOffset = scrollState.value.toFloat()
                    when {
                        isGesture -> {
                            scale = tab.dragScale
                            tx = tab.dragTx
                            ty = tab.dragTy + scrollOffset
                        }
                        commitFromProgress > 0f -> {
                            val t = if (commitFromProgress < 1f)
                                ((progress - commitFromProgress) / (1f - commitFromProgress)).coerceIn(0f, 1f)
                            else 1f
                            scale = lerp(tab.dragScale, cellScale, t)
                            tx = lerp(tab.dragTx, cell.left, t)
                            ty = lerp(tab.dragTy + scrollOffset, cell.top, t)
                        }
                        cancelFromProgress > 0f -> {
                            val t = if (cancelFromProgress > 0f) {
                                (1f - progress / cancelFromProgress).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                            scale = lerp(tab.dragScale, 1f, t)
                            tx = lerp(tab.dragTx, tab.browseTranslationX, t)
                            // Target is fullscreen (on-screen y = 0) → content-space y = scrollOffset.
                            ty = lerp(tab.dragTy + scrollOffset, scrollOffset, t)
                        }
                        else -> {
                            scale = lerp(1f, cellScale, progress)
                            tx = lerp(tab.browseTranslationX, cell.left, xProgress)
                            // cell.top is in scroll-content coordinates; visual y = ty - scrollOffset.
                            // Lerp from scrollOffset so that at progress=0 the tab lands at y=0
                            // (fullscreen), not at y=-scrollOffset (above the screen).
                            ty = lerp(scrollState.value.toFloat(), cell.top, progress)
                        }
                    }
                    // Cull cells fully off-screen so a many-tab grid doesn't pay to compose and draw
                    // snapshots you can't see.
                    //   • Horizontal: tx is a pure horizontal translation (never affected by the
                    //     vertical scroll), so this is always safe — and it's the big win during the
                    //     swipe-up, where far carousel tabs sit far off to the sides.
                    //   • Vertical: only cull in the static/open state, where ty is in scroll-content
                    //     coordinates so (ty - scroll) is the true on-screen y. During the gesture and
                    //     commit/cancel springs, ty is a screen-space drag position, so we skip the
                    //     vertical test to avoid mis-culling.
                    val cullMarginPx = containerH
                    val drawW = containerW * scale
                    val drawH = containerH * scale
                    val horizontallyVisible =
                        tx + drawW > -cullMarginPx && tx < containerW + cullMarginPx
                    val verticallyVisible =
                        if (!isGesture && commitFromProgress == 0f && cancelFromProgress == 0f) {
                            val visualTop = ty - scrollState.value
                            visualTop + drawH > -cullMarginPx && visualTop < containerH + cullMarginPx
                        } else true
                    if (horizontallyVisible && verticallyVisible) {
                    val padDp = with(density) { tab.browseTopPadPx.toDp() }
                    val tabLabel = remember(tab.url, tab.title) {
                        if (tab.url.isBlankTabUrl()) "New tab page"
                        else tab.title.ifBlank { hostLabel(tab.url).ifEmpty { tab.url } }
                    }
                    // Local (pre-scale) height of the card. At fullscreen (progress 0) it is the
                    // full viewport so the open/close morph matches the browse layer; as the grid
                    // opens it shrinks to the 3:4 page slice, cropping everything below the fold.
                    val cardLocalHeightPx = lerp(containerH, croppedPageHeightPx, progress.coerceIn(0f, 1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { cardLocalHeightPx.toDp() })
                            .padding(top = padDp)
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = tx
                                translationY = ty
                                scaleX = scale
                                scaleY = scale
                            }
                            .clip(cellShape)
                            .background(
                                if (tab.url.isBlankTabUrl()) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                }
                            )
                            .semantics(mergeDescendants = true) { contentDescription = tabLabel }
                            .clickable(
                                enabled = progress > 0.5f,
                                onClickLabel = "Switch to this tab",
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTabSelected(tab.id)
                            },
                    ) {
                        if (tab.url.isBlankTabUrl()) {
                            Unit
                        } else if (tab.snapshot != null) {
                            val img = remember(tab.snapshot) { tab.snapshot.asImageBitmap() }
                            Image(
                                bitmap = img,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                // Anchor to the top so the 3:4 crop keeps the top of the page and
                                // drops the bottom, rather than centering the crop.
                                alignment = Alignment.TopCenter,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // No snapshot yet — show a simple placeholder. Sizes are in the cell's
                            // unscaled space (~0.44x on screen), hence the large values.
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
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
                    }
                    } // if (cell visible)
                    } // key(tab.id)
                }

                // Domain pills — rendered after the cells in document order so they paint on top.
                // Positioned in unscaled grid-content coordinates (cell.left / cell.top) so they
                // appear at a consistent on-screen size rather than being shrunk by the cell's
                // graphicsLayer scale.
                // Suppress pills while the finger is still down — they should only fade in after
                // the user lifts and the commit spring starts.
                val pillAlpha = if (isGesture) 0f else progress.coerceIn(0f, 1f)
                if (pillAlpha > 0.01f) {
                    tabs.forEachIndexed { index, tab ->
                        key(tab.id) {
                        val cell = gridCellRect(
                            index = index,
                            containerSize = containerSize,
                            columns = GridColumns,
                            horizontalGapPx = gapPx,
                            verticalGapPx = gapPx,
                            sideMarginPx = sideMarginPx,
                            topMarginPx = topMarginPx,
                        )
                        // Skip overlay work (pill + close button) for rows scrolled off-screen.
                        val rowVisualTop = cell.top - scrollState.value
                        val rowOnScreen =
                            rowVisualTop + cell.height > -containerH && rowVisualTop < containerH * 2f
                        if (rowOnScreen) {
                        // Highlight ring around the currently-selected tab. Only shown once the
                        // grid is fully open and static (scrollEnabled) — never during the
                        // drag/open/close animation. Drawn in unscaled grid-content coordinates
                        // (like the pills) so the stroke stays a constant on-screen thickness.
                        if (tab.id == selectedTabId && scrollEnabled) {
                            val cellWidthDp = with(density) { cell.width.toDp() }
                            val cellHeightDp = with(density) { cell.height.toDp() }
                            // The cell's clip radius (effectiveCornerDp) is in unscaled card space
                            // and gets shrunk by the cell scale on screen. The highlight is drawn at
                            // the real cell size (unscaled), so multiply by that same scale to match
                            // the card's actual on-screen corner radius.
                            val cellScale = if (containerW > 0f) cell.width / containerW else 1f
                            val highlightShape = remember(effectiveCornerDp, cellScale) {
                                SquircleShape((effectiveCornerDp.value * cellScale).dp)
                            }
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        translationX = cell.left
                                        translationY = cell.top
                                    }
                                    .size(width = cellWidthDp, height = cellHeightDp)
                                    .border(3.dp, MaterialTheme.colorScheme.primary, highlightShape),
                            )
                        }
                        val host = remember(tab.url) { hostLabel(tab.url) }
                        if (host.isNotEmpty()) {
                            val pillLeftPx = cell.left + with(density) { 8.dp.toPx() }
                            val pillTopPx = cell.top + cell.height - with(density) { 32.dp.toPx() }
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        translationX = pillLeftPx
                                        translationY = pillTopPx
                                        alpha = pillAlpha
                                    }
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = host,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Close ("×") button at the top-right of each cell. Hidden when only one
                        // tab remains, so closing can never leave an empty browser. Positioned in
                        // unscaled grid-content coordinates (like the domain pills) for a constant
                        // on-screen size, and drawn after the cells so it receives the tap.
                        if (tabs.size > 1) {
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
                                        translationX = closeLeftPx - touchExtraPx
                                        translationY = closeTopPx - touchExtraPx
                                        alpha = pillAlpha
                                    }
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = progress > 0.5f) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onCloseTab(tab.id)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close tab",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        } // if (rowOnScreen)
                        } // key(tab.id)
                    }

                    // Fixed-position "+" cell — shown when grid is open and user has not yet tapped it.
                    if (!newTabSelected) {
                        val newTabCell = gridCellRect(
                            index = tabs.size,
                            containerSize = containerSize,
                            columns = GridColumns,
                            horizontalGapPx = gapPx,
                            verticalGapPx = gapPx,
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
                                    alpha = pillAlpha
                                }
                                .clip(cellShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable(enabled = progress > 0.5f) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                // Real tabs have browseTranslationX offsetted left (newSelIdx = tabs.size in gridTabs)
                // so they all slide off-screen left while this cell takes center.
                if (newTabSelected && !isGesture) {
                    val newTabCell = gridCellRect(
                        index = tabs.size,
                        containerSize = containerSize,
                        columns = GridColumns,
                        horizontalGapPx = gapPx,
                        verticalGapPx = gapPx,
                        sideMarginPx = sideMarginPx,
                        topMarginPx = topMarginPx,
                    )
                    val newTabCellScale = if (containerW > 0f) newTabCell.width / containerW else 1f
                    val scale = lerp(1f, newTabCellScale, progress)
                    val tx = lerp(0f, newTabCell.left, xProgress)
                    val ty = lerp(scrollState.value.toFloat(), newTabCell.top, progress)
                    val newTabLocalHeightPx = lerp(containerH, croppedPageHeightPx, progress.coerceIn(0f, 1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { newTabLocalHeightPx.toDp() })
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = tx
                                translationY = ty
                                scaleX = scale
                                scaleY = scale
                            }
                            .clip(cellShape)
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
    }
}

private fun hostLabel(url: String): String {
    if (url.isBlankTabUrl()) return ""
    return try {
        url.toUri().host.orEmpty().removePrefix("www.")
    } catch (_: Throwable) {
        ""
    }
}

private fun String.isBlankTabUrl(): Boolean = isBlank() || this == "about:blank"

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
