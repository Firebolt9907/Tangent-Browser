package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme

@Composable
internal fun BarExpanded(
    state: BarUiState,
    onUrlTap: () -> Unit,
    onAaTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: (totalDelta: Offset, velocity: Offset) -> Unit,
    onReload: () -> Unit,
    onAddonsClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    controlsEnabled: Boolean = true,
    collapseProgress: Float = 0f,
) {
    val progress = collapseProgress.coerceIn(0f, 1f)
    val controlsProgress = 1f - progress
    val controlsWidth = lerp(48.dp, 0.dp, progress)
    val tabChipWidth = lerp(36.dp, 0.dp, progress)
    val tabChipSpacing = lerp(8.dp, 0.dp, progress)
    val blankPreview = state.blankNewTabPreview

    BoxWithConstraints(modifier = modifier) {
        val expandedWidth = (maxWidth - 40.dp).coerceAtLeast(0.dp)
        val compactWidth = (((if (blankPreview) "+" else state.displayUrl).length * 8).dp + 56.dp)
            .coerceIn(96.dp, (maxWidth - 40.dp).coerceAtLeast(96.dp))

        BarPill(
            width = lerp(expandedWidth, compactWidth, progress),
            height = lerp(56.dp, 44.dp, progress),
            cornerRadius = lerp(28.dp, 22.dp, progress),
            onDragStart = onDragStart,
            onDragDelta = onDragDelta,
            onDragEnd = onDragEnd,
            interactive = interactive,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = lerp(8.dp, 12.dp, progress)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!blankPreview) {
                    Box(
                        modifier = Modifier
                            .width(controlsWidth)
                            .clipToBounds()
                            .graphicsLayer { alpha = controlsProgress },
                    ) {
                        IconButton(onClick = onAaTap, enabled = controlsEnabled) {
                            Text("aA", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(controlsWidth)
                            .clipToBounds()
                            .graphicsLayer { alpha = controlsProgress },
                    ) {
                        IconButton(onClick = onAddonsClick, enabled = controlsEnabled) {
                            Icon(Icons.Default.Extension, contentDescription = "Add-ons")
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = controlsEnabled, onClick = onUrlTap)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (blankPreview) "+" else state.displayUrl,
                        style = if (blankPreview) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (!blankPreview) {
                        Spacer(Modifier.width(tabChipSpacing))
                        Box(
                            modifier = Modifier
                                .width(tabChipWidth)
                                .clipToBounds()
                                .graphicsLayer { alpha = controlsProgress },
                        ) {
                            TabCountChip(count = state.tabCount)
                        }
                    }
                }
                if (!blankPreview) {
                    Box(
                        modifier = Modifier
                            .width(controlsWidth)
                            .clipToBounds()
                            .graphicsLayer { alpha = controlsProgress },
                    ) {
                        IconButton(onClick = onReload, enabled = controlsEnabled) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BarExpandedPreview() {
    LuminaBrowserTheme(darkTheme = true) {
        BarExpanded(
            state = BarUiState("apple.com", 4, BarMode.Expanded, ""),
            onUrlTap = {},
            onAaTap = {},
            onDragStart = {},
            onDragDelta = { _ -> },
            onDragEnd = { _, _ -> },
            onReload = {},
            onAddonsClick = {},
        )
    }
}
