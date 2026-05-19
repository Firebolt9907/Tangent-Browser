package com.firebolt9907.luminabrowser.ui.grid

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
    val cellH = cellW * (h / w)
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
    val sideMarginPx = with(density) { GridSideMarginDp.toPx() }
    val topMarginPx = with(density) { GridTopMarginDp.toPx() }
    val bottomMarginPx = with(density) { GridBottomMarginDp.toPx() }
    val gapPx = with(density) { GridGapDp.toPx() }
    val gridCornerDp = 22.dp
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
    val cellH = if (containerW > 0f) cellW * (containerH / containerW) else 0f
    val rawContentHeightPx = topMarginPx + rows * cellH + (rows - 1).coerceAtLeast(0) * gapPx + bottomMarginPx
    val contentHeightPx = rawContentHeightPx.coerceAtLeast(containerH)
    val contentHeightDp = with(density) { contentHeightPx.toDp() }

    val scrollState = rememberScrollState()
    // Only allow scrolling once the grid is essentially fully open — during the open animation
    // the scroll offset must stay 0 so cells land in their target cells, not in scrolled positions.
    val scrollEnabled = !isGesture && commitFromProgress == 0f && cancelFromProgress == 0f &&
        progress >= 0.999f

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
                    when {
                        isGesture -> {
                            scale = tab.dragScale
                            tx = tab.dragTx
                            ty = tab.dragTy
                        }
                        commitFromProgress > 0f -> {
                            val t = if (commitFromProgress < 1f)
                                ((progress - commitFromProgress) / (1f - commitFromProgress)).coerceIn(0f, 1f)
                            else 1f
                            scale = lerp(tab.dragScale, cellScale, t)
                            tx = lerp(tab.dragTx, cell.left, t)
                            ty = lerp(tab.dragTy, cell.top, t)
                        }
                        cancelFromProgress > 0f -> {
                            val t = if (cancelFromProgress > 0f) {
                                (1f - progress / cancelFromProgress).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                            scale = lerp(tab.dragScale, 1f, t)
                            tx = lerp(tab.dragTx, tab.browseTranslationX, t)
                            ty = lerp(tab.dragTy, 0f, t)
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
                    val padDp = with(density) { tab.browseTopPadPx.toDp() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { containerH.toDp() })
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
                            .clickable(enabled = progress > 0.5f) { onTabSelected(tab.id) },
                    ) {
                        if (tab.url.isBlankTabUrl()) {
                            Unit
                        } else if (tab.snapshot != null) {
                            val img = remember(tab.snapshot) { tab.snapshot.asImageBitmap() }
                            Image(
                                bitmap = img,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = tab.title.ifBlank { tab.url },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
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
                                .height(with(density) { containerH.toDp() })
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
                                .clickable(enabled = progress > 0.5f) { onNewTab() },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Layout size is in the cell's unscaled coordinate space (~0.44x at runtime),
                            // so 96.dp in layout ≈ 42.dp visible on screen.
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New tab",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(96.dp),
                            )
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { containerH.toDp() })
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
        Uri.parse(url).host.orEmpty().removePrefix("www.")
    } catch (_: Throwable) {
        ""
    }
}

private fun String.isBlankTabUrl(): Boolean = isBlank() || this == "about:blank"

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
