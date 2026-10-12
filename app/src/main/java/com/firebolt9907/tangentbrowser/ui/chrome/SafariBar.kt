package com.firebolt9907.tangentbrowser.ui.chrome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import com.firebolt9907.tangentbrowser.ui.theme.EaseMotion
import androidx.compose.runtime.getValue
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.firebolt9907.tangentbrowser.state.BarMode
import com.firebolt9907.tangentbrowser.state.BarUiState
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics

private val BottomBarPadding = 24.dp
private val BottomBarMaxHeight = 56.dp
private val BottomBarEditingHeight = 72.dp

// Hoisted constant progress providers so the Compact branch and defaults don't allocate a lambda
// per recomposition.
private val CompactProgress: () -> Float = { 1f }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SafariBar(
    state: BarUiState,
    onUrlTap: () -> Unit,
    onUrlLongPress: () -> Unit = {},
    onAaTap: () -> Unit,
    onAaLongPress: () -> Unit = {},
    onEditingTextChange: (String) -> Unit,
    onEditingSubmit: () -> Unit,
    onEditingCancel: () -> Unit,
    // Top URL suggestion to inline-complete into the editing field, or null.
    inlineAutocomplete: String? = null,
    // Mic tap in the editing field → system voice search. Null hides the mic.
    onVoiceInput: (() -> Unit)? = null,
    onDragStart: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: (totalDelta: Offset, velocity: Offset) -> Unit,
    onReload: () -> Unit,
    onReloadLongPress: () -> Unit = {},
    canGoForward: Boolean = false,
    onBottomInsetMeasured: (Int) -> Unit = {},
    interactive: Boolean = true,
    controlsEnabled: Boolean = true,
    // Live scroll-collapse progress (0 = expanded, 1 = compact). A lambda, not a Float, so the
    // animation's per-frame updates are read in the layout/draw phases below and never recompose the
    // bar. See BarPill.PillMorph / BarExpanded.
    collapseProgress: () -> Float = { 0f },
    isReaderEligible: Boolean = false,
    isReaderActive: Boolean = false,
    readerTheme: com.firebolt9907.tangentbrowser.ui.reader.ReaderTheme = com.firebolt9907.tangentbrowser.ui.reader.ReaderTheme.System,
    onClick: (() -> Unit)? = null,
    showBottomToolbar: Boolean = false,
    onCopyTap: () -> Unit = {},
    onForwardTap: () -> Unit = {},
    onShareTap: () -> Unit = {},
    onBookmarksTap: () -> Unit = {},
    onTabsTap: () -> Unit = {},
    pillTranslationX: () -> Float = { 0f },
    pillScale: () -> Float = { 1f },
    // Only the live, interactive pill morphs its address text into the editing field. The neighbor
    // "ghost" pills never enter editing, so they skip the SharedTransitionLayout + sharedBounds
    // bookkeeping entirely (it was multiplying per-frame cost across every peeking pill).
    enableAddressHero: Boolean = false,
    // Site theme color for the "Match website theme color" option; tints the expanded/compact pill.
    // Null (default) leaves the pill on its neutral surface. Ghost neighbor pills leave this null.
    tintColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    // Animate the reserved toolbar space (0 = gone, 1 = shown) rather than toggling it on
    // `state.mode`. Entering editing used to snap this 48dp→0 instantly: the shared address text
    // morphed smoothly via its own boundsTransform, but the rest of the pill body — and the buttons
    // — jumped down by the toolbar's height and popped out, so the hero "didn't account for the
    // bottom buttons". Driving the pill's lift and the buttons' fade from this one animated fraction
    // keeps the whole bar moving with the morph. NOTE: this must NOT depend on `interactive` — the
    // neighbor ghost pills are non-interactive but still need the same lift so they sit beside the
    // live pill (above the toolbar), not dropped down next to the buttons. The `interactive` gate
    // belongs only on the button Row below.
    val haptics = rememberTangentHaptics()
    val reserveToolbarSpace = showBottomToolbar && state.mode != BarMode.Editing
    val toolbarFraction by animateFloatAsState(
        targetValue = if (reserveToolbarSpace) 1f else 0f,
        animationSpec = EaseMotion.resizeSpec(320),
        label = "bottomToolbarFraction",
    )
    val barHeight = if (state.mode == BarMode.Editing) {
        BottomBarEditingHeight
    } else {
        BottomBarMaxHeight
    }

    val toolbarHeight = 48.dp * toolbarFraction
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // At rest the bar clears the system nav bar (gesture pill / buttons). While editing, the whole
    // BottomBarLayer is `imePadding`-lifted above the keyboard and the nav bar is hidden behind it,
    // so that nav-bar clearance would just be a dead gap between the pill and the keyboard top —
    // collapse it to a small breathing space so the address bar sits snug above the keyboard.
    val bottomPadding = if (state.mode == BarMode.Editing) 8.dp else navBarHeight.coerceAtLeast(24.dp)
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
                    // Lift above the toolbar as a LAYOUT offset (not a graphicsLayer translation):
                    // the shared address element captures its bounds in layout space, so a draw-only
                    // translation was invisible to it — the hero targeted the un-lifted (bottom)
                    // position and the text slid back toward the buttons on exit. offset{} is
                    // placement-phase (still no recomposition/remeasure), so it stays cheap.
                    .offset {
                        IntOffset(0, (-toolbarHeight.toPx() * (1f - collapseProgress())).roundToInt())
                    }
                    .graphicsLayer {
                        val scale = pillScale()
                        scaleX = scale
                        scaleY = scale
                        translationX = pillTranslationX()
                    },
                transitionSpec = {
                    (fadeIn(EaseMotion.enterSpec(240)) togetherWith
                        fadeOut(EaseMotion.exitSpec(120)))
                        .using(SizeTransform(clip = false) { _, _ ->
                            EaseMotion.resizeSpec(320)
                        })
                },
                label = "SafariBarMode",
            ) { mode ->
                // The address text is a shared element: its bounds animate from the pill's centered
                // label into the editing field (and back), so the URL appears to physically glide
                // between the two surfaces instead of cross-fading in place. `this` here is the
                // AnimatedContentScope that drives the pairing across the Expanded/Editing swap.
                // Only the live pill opts in (enableAddressHero) — ghosts get a no-op modifier so
                // they don't run shared-element bookkeeping. The flag is constant per instance, so
                // the conditional `remember` never switches branches.
                val addressShared = if (enableAddressHero) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "addressText"),
                        animatedVisibilityScope = this,
                        boundsTransform = { _, _ -> EaseMotion.resizeSpec(320) },
                    )
                } else {
                    Modifier
                }
                when (mode) {
                    BarMode.Expanded -> BarExpanded(
                        state = state,
                        onUrlTap = onUrlTap,
                        onUrlLongPress = onUrlLongPress,
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
                        tintColor = tintColor,
                        haptics = haptics,
                    )
                    BarMode.Compact -> BarExpanded(
                        state = state,
                        onUrlTap = onUrlTap,
                        onUrlLongPress = onUrlLongPress,
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
                        collapseProgress = CompactProgress,
                        isReaderEligible = isReaderEligible,
                        isReaderActive = isReaderActive,
                        readerTheme = readerTheme,
                        onClick = onClick,
                        addressSharedModifier = addressShared,
                        tintColor = tintColor,
                        haptics = haptics,
                    )
                    BarMode.Editing -> BarEditing(
                        state,
                        onEditingTextChange,
                        onEditingSubmit,
                        onEditingCancel,
                        controlsEnabled = controlsEnabled,
                        addressSharedModifier = addressShared,
                        haptics = haptics,
                        onVoiceInput = onVoiceInput,
                        inlineAutocomplete = inlineAutocomplete,
                    )
                }
              }
            }

            if (interactive && toolbarFraction > 0.001f) {
                val hasPage = state.pageUrl.isNotBlank() && state.pageUrl != "about:blank"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 20.dp)
                        .graphicsLayer {
                            // Fade + slide the buttons out as the pill collapses (scroll) or the
                            // toolbar retracts on the way into editing, so they never pop.
                            val collapse = collapseProgress()
                            alpha = ((1f - collapse) * toolbarFraction).coerceIn(0f, 1f)
                            translationY = 48.dp.toPx() *
                                (collapse + (1f - toolbarFraction)).coerceIn(0f, 1f)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    BottomToolbarButton(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "Copy link",
                        onClick = { haptics.softClick(); onCopyTap() },
                        enabled = controlsEnabled && hasPage,
                    )
                    BottomToolbarButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        onClick = { haptics.softClick(); onForwardTap() },
                        enabled = controlsEnabled && canGoForward,
                    )
                    BottomToolbarButton(
                        icon = Icons.Default.Share,
                        contentDescription = "Share",
                        onClick = { haptics.softClick(); onShareTap() },
                        enabled = controlsEnabled && hasPage,
                    )
                    BottomToolbarButton(
                        icon = Icons.Outlined.BookmarkBorder,
                        contentDescription = "Bookmarks",
                        onClick = { haptics.softClick(); onBookmarksTap() },
                        enabled = controlsEnabled,
                    )
                    BottomToolbarButton(
                        icon = Icons.Outlined.Layers,
                        contentDescription = "Tabs",
                        onClick = { haptics.softClick(); onTabsTap() },
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
internal fun BottomToolbarButton(
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
