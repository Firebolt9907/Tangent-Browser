package com.firebolt9907.luminabrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize

/**
 * The tab-switch / predictive-back snapshot cover stack (invariant #7). Renders, in z-order: the
 * sticky-incoming cover, its cross-fade-out layer, the leaving/incoming frozen snapshots of a tab
 * swipe, the new-tab commit fade, the predictive-back cover, and the cold-start grey-surface cover
 * — all driven purely by [SnapshotCoverState] plus a few layout constants. Extracted verbatim from
 * LuminaRoot's composition tree; it reads no gesture callbacks and owns no state, so it is a pure
 * function of [snap]. Per-frame offset/alpha are read inside the `graphicsLayer` blocks (draw
 * phase) so a swipe does not recompose this subtree.
 *
 * Must be rendered only on the `!gridEngaged` path (invariant #5 — never simultaneously with the
 * grid overlay).
 */
@Composable
internal fun SwitchSnapshotCovers(
    snap: SnapshotCoverState,
    contentSize: IntSize,
    tabSwitchGapPx: Float,
    switchPaneShape: Shape,
) {
    val density = LocalDensity.current
    val leavingSnapshotBitmap by snap.leavingSnapshotBitmap
    val incomingSnapshotBitmap by snap.incomingSnapshotBitmap
    val commitAnimNewTab by snap.commitAnimNewTab
    val incomingSnapshotTopPadPx by snap.incomingSnapshotTopPadPx
    val incomingSnapshotOffsetX by snap.incomingSnapshotOffsetX
    val leavingSnapshotTopPadPx by snap.leavingSnapshotTopPadPx
    val leavingSnapshotOffsetX by snap.leavingSnapshotOffsetX
    val stickyBackCoverSnapshot by snap.stickyBackCoverSnapshot
    val stickyBackCoverUrl by snap.stickyBackCoverUrl
    val stickyIncomingSnapshot by snap.stickyIncomingSnapshot
    val stickyIncomingTabId by snap.stickyIncomingTabId
    val stickyIncomingTopPadPx by snap.stickyIncomingTopPadPx
    val fadingCoverActive by snap.fadingCoverActive
    val fadingCoverBitmap by snap.fadingCoverBitmap
    val fadingCoverTopPadPx by snap.fadingCoverTopPadPx
    val fadingCoverAlpha = snap.fadingCoverAlpha
    val geckoHasPainted by snap.geckoHasPainted

    stickyIncomingSnapshot?.let { bitmap ->
        val pad = with(density) { stickyIncomingTopPadPx.toDp() }
        SnapshotImage(
            bitmap = bitmap,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pad)
                .clip(switchPaneShape),
        )
    }
    // The dismissed sticky cover cross-fading into the live page (see
    // fadeOutStickyCover). Same layer as the sticky cover above; the alpha is read
    // in graphicsLayer so the fade animates in the draw phase only.
    if (fadingCoverActive) {
        val fadingBitmap = fadingCoverBitmap
        val pad = with(density) { fadingCoverTopPadPx.toDp() }
        if (fadingBitmap != null) {
            SnapshotImage(
                bitmap = fadingBitmap,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = pad)
                    .graphicsLayer { alpha = fadingCoverAlpha.value }
                    .clip(switchPaneShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fadingCoverAlpha.value }
                    .background(MaterialTheme.colorScheme.background),
            )
        }
    }

    if (leavingSnapshotBitmap != null || incomingSnapshotBitmap != null || commitAnimNewTab) {
        val width = contentSize.width.toFloat()
        val pageStride = width + tabSwitchGapPx
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        incomingSnapshotBitmap?.let { bitmap ->
            val pad = with(density) { incomingSnapshotTopPadPx.toDp() }
            SnapshotImage(
                bitmap = bitmap,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = pad)
                    .graphicsLayer { translationX = incomingSnapshotOffsetX }
                    .clip(switchPaneShape),
            )
        }
        if (commitAnimNewTab && pageStride > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Read the animating offset inside the graphicsLayer lambda so the
                    // per-frame alpha update runs in the draw phase only, instead of
                    // recomposing this block on every frame of the new-tab spring.
                    .graphicsLayer {
                        translationX = incomingSnapshotOffsetX
                        alpha = (1f - (incomingSnapshotOffsetX / pageStride))
                            .coerceIn(0f, 1f)
                    }
                    .clip(switchPaneShape)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
        val leavingPad = with(density) { leavingSnapshotTopPadPx.toDp() }
        if (leavingSnapshotBitmap == null && (incomingSnapshotBitmap != null || commitAnimNewTab)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = leavingPad)
                    .graphicsLayer { translationX = leavingSnapshotOffsetX }
                    .clip(switchPaneShape)
                    .background(MaterialTheme.colorScheme.background),
            )
        }
        leavingSnapshotBitmap?.let { bitmap ->
            SnapshotImage(
                bitmap = bitmap,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = leavingPad)
                    .graphicsLayer { translationX = leavingSnapshotOffsetX }
                    .clip(switchPaneShape),
            )
        }
    }
    stickyBackCoverSnapshot?.let { bitmap ->
        SnapshotImage(
            bitmap = bitmap,
            modifier = Modifier
                .fillMaxSize()
                .clip(switchPaneShape),
        )
    }
    if (stickyBackCoverSnapshot == null && stickyBackCoverUrl != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
    if (stickyIncomingSnapshot == null && stickyIncomingTabId != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
    // Cover the grey GeckoView surface until the first paint fires.
    // The sticky-cover system handles tab switches; this handles cold-start and
    // any case where no sticky cover is active but Gecko hasn't composited yet.
    if (!geckoHasPainted && stickyIncomingTabId == null && stickyBackCoverUrl == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}
