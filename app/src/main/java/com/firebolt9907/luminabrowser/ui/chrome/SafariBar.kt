package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState

private val BottomBarPadding = 24.dp
private val BottomBarMaxHeight = 56.dp
private val BottomBarEditingHeight = 72.dp

@Composable
fun SafariBar(
    state: BarUiState,
    onUrlTap: () -> Unit,
    onAaTap: () -> Unit,
    onAaLongPress: () -> Unit = {},
    onEditingTextChange: (String) -> Unit,
    onEditingSubmit: () -> Unit,
    onEditingCancel: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: (totalDelta: Offset, velocity: Offset) -> Unit,
    onReload: () -> Unit,
    onReloadLongPress: () -> Unit = {},
    canGoForward: Boolean = false,
    onBottomInsetMeasured: (Int) -> Unit = {},
    interactive: Boolean = true,
    controlsEnabled: Boolean = true,
    collapseProgress: Float = 0f,
    isReaderEligible: Boolean = false,
    isReaderActive: Boolean = false,
    readerTheme: com.firebolt9907.luminabrowser.ui.reader.ReaderTheme = com.firebolt9907.luminabrowser.ui.reader.ReaderTheme.System,
    onClick: (() -> Unit)? = null,
    showBottomToolbar: Boolean = false,
    onCopyTap: () -> Unit = {},
    onForwardTap: () -> Unit = {},
    onShareTap: () -> Unit = {},
    onBookmarksTap: () -> Unit = {},
    onTabsTap: () -> Unit = {},
    pillTranslationX: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val isToolbarVisible = showBottomToolbar && interactive && state.mode != BarMode.Editing
    val barHeight = if (state.mode == BarMode.Editing) {
        BottomBarEditingHeight
    } else {
        BottomBarMaxHeight
    }
    
    val toolbarHeight = if (showBottomToolbar && state.mode != BarMode.Editing) 48.dp else 0.dp
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = navBarHeight.coerceAtLeast(24.dp)
    val totalHeight = barHeight + bottomPadding + toolbarHeight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .padding(bottom = bottomPadding),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedContent(
                targetState = state.mode,
                modifier = Modifier
                    .graphicsLayer {
                        val toolbarHeightPx = toolbarHeight.toPx()
                        translationX = pillTranslationX
                        translationY = -toolbarHeightPx * (1f - collapseProgress)
                    },
                transitionSpec = {
                    (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                        fadeOut(spring(stiffness = Spring.StiffnessMediumLow)))
                        .using(SizeTransform(clip = false) { _, _ ->
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        })
                },
                label = "SafariBarMode",
            ) { mode ->
                when (mode) {
                    BarMode.Expanded -> BarExpanded(
                        state = state,
                        onUrlTap = onUrlTap,
                        onAaTap = onAaTap,
                        onAaLongPress = onAaLongPress,
                        onDragStart = onDragStart,
                        onDragDelta = onDragDelta,
                        onDragEnd = onDragEnd,
                        onReload = onReload,
                        onReloadLongPress = onReloadLongPress,
                        canGoForward = canGoForward,
                        interactive = interactive,
                        controlsEnabled = controlsEnabled,
                        collapseProgress = collapseProgress,
                        isReaderEligible = isReaderEligible,
                        isReaderActive = isReaderActive,
                        readerTheme = readerTheme,
                        onClick = onClick,
                    )
                    BarMode.Compact -> BarExpanded(
                        state = state,
                        onUrlTap = onUrlTap,
                        onAaTap = onAaTap,
                        onAaLongPress = onAaLongPress,
                        onDragStart = onDragStart,
                        onDragDelta = onDragDelta,
                        onDragEnd = onDragEnd,
                        onReload = onReload,
                        onReloadLongPress = onReloadLongPress,
                        canGoForward = canGoForward,
                        interactive = interactive,
                        controlsEnabled = controlsEnabled,
                        collapseProgress = 1f,
                        isReaderEligible = isReaderEligible,
                        isReaderActive = isReaderActive,
                        readerTheme = readerTheme,
                        onClick = onClick,
                    )
                    BarMode.Editing -> BarEditing(
                        state,
                        onEditingTextChange,
                        onEditingSubmit,
                        onEditingCancel,
                        controlsEnabled = controlsEnabled,
                    )
                }
            }

            if (isToolbarVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 20.dp)
                        .graphicsLayer {
                            alpha = (1f - collapseProgress).coerceIn(0f, 1f)
                            translationY = 48.dp.toPx() * collapseProgress
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    IconButton(
                        onClick = onCopyTap,
                        enabled = controlsEnabled && state.pageUrl.isNotBlank() && state.pageUrl != "about:blank"
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy link",
                            tint = if (controlsEnabled && state.pageUrl.isNotBlank() && state.pageUrl != "about:blank") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(
                        onClick = onForwardTap,
                        enabled = controlsEnabled && canGoForward
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (controlsEnabled && canGoForward) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(
                        onClick = onShareTap,
                        enabled = controlsEnabled && state.pageUrl.isNotBlank() && state.pageUrl != "about:blank"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = if (controlsEnabled && state.pageUrl.isNotBlank() && state.pageUrl != "about:blank") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(
                        onClick = onBookmarksTap,
                        enabled = controlsEnabled
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkBorder,
                            contentDescription = "Bookmarks",
                            tint = if (controlsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(
                        onClick = onTabsTap,
                        enabled = controlsEnabled
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Layers,
                            contentDescription = "Tabs",
                            tint = if (controlsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .onGloballyPositioned { coordinates ->
                    onBottomInsetMeasured(coordinates.size.height.coerceAtLeast(0))
                },
        )
    }
}
