package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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

@OptIn(ExperimentalSharedTransitionApi::class)
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
    pillTranslationX: () -> Float = { 0f },
    pillScale: () -> Float = { 1f },
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
            SharedTransitionLayout {
              AnimatedContent(
                targetState = state.mode,
                modifier = Modifier
                    .graphicsLayer {
                        val toolbarHeightPx = toolbarHeight.toPx()
                        val scale = pillScale()
                        scaleX = scale
                        scaleY = scale
                        translationX = pillTranslationX()
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
                // The address text is a shared element: its bounds animate from the pill's centered
                // label into the editing field (and back), so the URL appears to physically glide
                // between the two surfaces instead of cross-fading in place. `this` here is the
                // AnimatedContentScope that drives the pairing across the Expanded/Editing swap.
                val addressShared = Modifier.sharedBounds(
                    rememberSharedContentState(key = "addressText"),
                    animatedVisibilityScope = this,
                )
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
                        addressSharedModifier = addressShared,
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
                        addressSharedModifier = addressShared,
                    )
                    BarMode.Editing -> BarEditing(
                        state,
                        onEditingTextChange,
                        onEditingSubmit,
                        onEditingCancel,
                        controlsEnabled = controlsEnabled,
                        addressSharedModifier = addressShared,
                    )
                }
              }
            }

            if (isToolbarVisible) {
                val hasPage = state.pageUrl.isNotBlank() && state.pageUrl != "about:blank"
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
                    BottomToolbarButton(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "Copy link",
                        onClick = onCopyTap,
                        enabled = controlsEnabled && hasPage,
                    )
                    BottomToolbarButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        onClick = onForwardTap,
                        enabled = controlsEnabled && canGoForward,
                    )
                    BottomToolbarButton(
                        icon = Icons.Default.Share,
                        contentDescription = "Share",
                        onClick = onShareTap,
                        enabled = controlsEnabled && hasPage,
                    )
                    BottomToolbarButton(
                        icon = Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmarks",
                        onClick = onBookmarksTap,
                        enabled = controlsEnabled,
                    )
                    BottomToolbarButton(
                        icon = Icons.Outlined.Layers,
                        contentDescription = "Tabs",
                        onClick = onTabsTap,
                        enabled = controlsEnabled,
                    )
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

/**
 * A single action in the SafariBar's bottom toolbar. Centralizes the enabled/disabled
 * tint so every button reads the same — active buttons use the accent color, disabled
 * ones fade to a muted onSurface tone (iOS-style, subtler than full-opacity gray).
 */
@Composable
private fun BottomToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            },
        )
    }
}
