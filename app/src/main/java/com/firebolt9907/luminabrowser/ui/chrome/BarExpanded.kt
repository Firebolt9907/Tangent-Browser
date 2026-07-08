package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState
import com.firebolt9907.luminabrowser.ui.reader.ReaderTheme
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme

@Composable
internal fun BarExpanded(
    state: BarUiState,
    onUrlTap: () -> Unit,
    onAaTap: () -> Unit,
    onAaLongPress: () -> Unit = {},
    onDragStart: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: (totalDelta: Offset, velocity: Offset) -> Unit,
    onReload: () -> Unit,
    onReloadLongPress: () -> Unit = {},
    canGoForward: Boolean = false,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    controlsEnabled: Boolean = true,
    collapseProgress: Float = 0f,
    isReaderEligible: Boolean = false,
    isReaderActive: Boolean = false,
    readerTheme: ReaderTheme = ReaderTheme.System,
    onClick: (() -> Unit)? = null,
) {
    val progress = collapseProgress.coerceIn(0f, 1f)
    val controlsProgress = 1f - progress
    // Flag plain-HTTP pages as not secure (modern-browser convention). https / internal pages
    // (about:, blank new tab) show nothing — only http:// is called out.
    val isInsecure = !state.blankNewTabPreview &&
        state.pageUrl.startsWith("http://", ignoreCase = true)
    val controlsWidth = lerp(48.dp, 0.dp, progress)
    val tabChipWidth = lerp(36.dp, 0.dp, progress)
    val tabChipSpacing = lerp(8.dp, 0.dp, progress)
    val blankPreview = state.blankNewTabPreview

    val containerColor = if (isReaderActive) {
        when (readerTheme) {
            ReaderTheme.System -> MaterialTheme.colorScheme.surfaceContainerHigh
            ReaderTheme.White -> Color(0xFFF2F2F7)
            ReaderTheme.Sepia -> Color(0xFFEADFCA)
            ReaderTheme.Dark -> Color(0xFF2C2C2E)
        }
    } else {
        null
    }

    val aAColor = if (isReaderActive) {
        MaterialTheme.colorScheme.primary
    } else if (isReaderEligible) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }

    BoxWithConstraints(modifier = modifier) {
        val expandedWidth = (maxWidth - 40.dp).coerceAtLeast(0.dp)
        val compactWidth = (((if (blankPreview) "+" else state.displayUrl).length * 8).dp + 28.dp)
            .coerceIn(80.dp, (maxWidth - 40.dp).coerceAtLeast(80.dp))

        BarPill(
            width = lerp(expandedWidth, compactWidth, progress),
            height = lerp(56.dp, 36.dp, progress),
            cornerRadius = lerp(28.dp, 18.dp, progress),
            onDragStart = onDragStart,
            onDragDelta = onDragDelta,
            onDragEnd = onDragEnd,
            interactive = interactive,
            containerColor = containerColor,
            onClick = onClick,
            exponent = lerp(3.0f, 2.0f, progress),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!blankPreview) {
                    Box(
                        modifier = Modifier
                            .width(controlsWidth)
                            .clipToBounds()
                            .graphicsLayer { alpha = controlsProgress },
                    ) {
                        @OptIn(ExperimentalFoundationApi::class)
                        val aADescription = if (isReaderActive) {
                            "Reader and page settings"
                        } else {
                            "Page settings and reader view"
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .combinedClickable(
                                    onClick = onAaTap,
                                    onLongClick = onAaLongPress,
                                    enabled = controlsEnabled,
                                )
                                .semantics(mergeDescendants = true) {
                                    contentDescription = aADescription
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "aA",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isReaderEligible) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = aAColor
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            enabled = controlsEnabled,
                            onClickLabel = if (blankPreview) "Open a new tab" else "Edit address",
                            onClick = onUrlTap,
                        )
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isInsecure) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "Not secure",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { alpha = controlsProgress },
                        )
                        Spacer(Modifier.width(lerp(4.dp, 0.dp, progress)))
                    }
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
                        @OptIn(ExperimentalFoundationApi::class)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .combinedClickable(
                                    onClick = onReload,
                                    onLongClick = if (canGoForward) onReloadLongPress else null,
                                    enabled = controlsEnabled,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                            // Subtle chevron hints that long-press navigates forward.
                            if (canGoForward) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = "Go forward (long press reload)",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 6.dp, bottom = 6.dp)
                                        .size(9.dp),
                                )
                            }
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
        )
    }
}
