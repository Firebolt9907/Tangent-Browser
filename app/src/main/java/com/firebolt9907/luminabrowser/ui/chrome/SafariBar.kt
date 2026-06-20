package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    modifier: Modifier = Modifier,
    onBottomInsetMeasured: (Int) -> Unit = {},
    interactive: Boolean = true,
    controlsEnabled: Boolean = true,
    collapseProgress: Float = 0f,
    isReaderEligible: Boolean = false,
    isReaderActive: Boolean = false,
    readerTheme: com.firebolt9907.luminabrowser.ui.reader.ReaderTheme = com.firebolt9907.luminabrowser.ui.reader.ReaderTheme.White,
) {
    val barHeight = if (state.mode == BarMode.Editing) {
        BottomBarEditingHeight
    } else {
        BottomBarMaxHeight
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight + BottomBarPadding)
            .padding(bottom = BottomBarPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedContent(
            targetState = state.mode,
            modifier = Modifier,
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                    fadeOut(spring(stiffness = Spring.StiffnessMediumLow)))
                    .using(SizeTransform(clip = false) { _, _ ->
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight + BottomBarPadding)
                .onGloballyPositioned { coordinates ->
                    onBottomInsetMeasured(coordinates.size.height.coerceAtLeast(0))
                },
        )
    }
}
