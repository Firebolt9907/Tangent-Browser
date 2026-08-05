package com.firebolt9907.tangentbrowser.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.firebolt9907.tangentbrowser.state.BarMode
import com.firebolt9907.tangentbrowser.state.BarUiState
import com.firebolt9907.tangentbrowser.state.SearchEngine
import com.firebolt9907.tangentbrowser.state.addressBarLabel
import com.firebolt9907.tangentbrowser.ui.chrome.BottomToolbarButton
import com.firebolt9907.tangentbrowser.ui.content.LocalSiteTint
import com.firebolt9907.tangentbrowser.ui.content.siteTintOverlay
import com.firebolt9907.tangentbrowser.ui.chrome.SafariBar
import com.firebolt9907.tangentbrowser.ui.chrome.WideBarLayout
import mozilla.components.browser.state.state.TabSessionState
import kotlin.math.abs

@Composable
internal fun BottomBarLayer(
    contentSize: IntSize,
    tabSwitchGapPx: Float,
    previousTab: TabSessionState?,
    nextTab: TabSessionState?,
    tabCount: Int,
    activeState: BarUiState,
    callbacks: BarCallbacks,
    // Top URL suggestion to inline-complete into the editing field, or null. Only the live/active
    // bar consumes it; the neighbor ghost bars never enter editing mode.
    inlineAutocomplete: String? = null,
    // Mic tap in the editing field → system voice search. Null hides the mic (no recognizer).
    onVoiceInput: (() -> Unit)? = null,
    onPrimaryUrlTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: (totalDelta: androidx.compose.ui.geometry.Offset, velocity: androidx.compose.ui.geometry.Offset) -> Unit,
    onReload: () -> Unit,
    onReloadLongPress: () -> Unit = {},
    canGoForward: Boolean = false,
    onAaTap: () -> Unit,
    onAaLongPress: () -> Unit = {},
    // Read only inside graphicsLayer blocks: these change on every frame of a drag, commit
    // spring, or grid gesture, and must never be read during composition (see the call site).
    activeOffsetX: () -> Float,
    onBottomInsetMeasured: (Int) -> Unit,
    barCollapseProgress: () -> Float = { 0f },
    pillInteractive: Boolean = true,
    controlsEnabled: Boolean = true,
    gridProgress: () -> Float = { 0f },
    showNewTabGhost: Boolean = false,
    selectedTabId: String? = null,
    eligibleReaderTabs: Set<String> = emptySet(),
    activeReaderTabs: Set<String> = emptySet(),
    readerTheme: com.firebolt9907.tangentbrowser.ui.reader.ReaderTheme = com.firebolt9907.tangentbrowser.ui.reader.ReaderTheme.System,
    readerHideOffsetPx: () -> Float = { 0f },
    onSelectPreviousTab: () -> Unit = {},
    onSelectNextTab: () -> Unit = {},
    showBottomToolbar: Boolean = false,
    onCopyTap: () -> Unit = {},
    onUrlLongPress: () -> Unit = {},
    onForwardTap: () -> Unit = {},
    onShareTap: () -> Unit = {},
    onBookmarksTap: () -> Unit = {},
    onTabsTap: () -> Unit = {},
    // Wide-screen (tablet / landscape) bottom-bar arrangement. On narrow/portrait phones the classic
    // full-width centred bar is used regardless of [wideBarLayout].
    isWideLayout: Boolean = false,
    wideBarLayout: WideBarLayout = WideBarLayout.SPLIT,
    searchEngine: SearchEngine = SearchEngine.DEFAULT,
    showSearchTermsInBar: Boolean = true,
    // Current site's theme color for the "Match website theme color" option, or null when off / the
    // site declares none. Tints the live pill only; reader mode and the neighbor ghosts stay neutral.
    barTintColor: androidx.compose.ui.graphics.Color? = null,
) {
    val activeReaderActive = selectedTabId != null && activeReaderTabs.contains(selectedTabId)
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isKeyboardVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    val hideForKeyboard = isKeyboardVisible && activeState.mode != BarMode.Editing

    // Fade the bar out ON the keyboard's own curve instead of blanking it. `isKeyboardVisible` flips
    // the moment the IME inset crosses zero — the FIRST frame of a 285 ms keyboard animation — so
    // switching alpha straight to 0 made the pill vanish instantly and then left the keyboard still
    // visibly sliding up into the hole where it had been. The bar is `imePadding`-lifted, so it now
    // rides up with the keyboard and dissolves as it goes.
    val keyboardHideAlpha = remember { Animatable(if (hideForKeyboard) 0f else 1f) }
    // Composition gate. Read instead of `hideForKeyboard` so the bar survives its own fade-out; it is
    // a plain boolean flipped at the ends of the animation rather than a derived read of
    // `keyboardHideAlpha`, so composition changes once per transition instead of once per frame —
    // the alpha itself is read only inside the graphicsLayer below (draw phase), matching how
    // gridProgress is handled.
    var barComposed by remember { mutableStateOf(!hideForKeyboard) }
    LaunchedEffect(hideForKeyboard) {
        if (hideForKeyboard) {
            keyboardHideAlpha.animateTo(0f, TangentMotion.ImeTrack)
            barComposed = false
        } else {
            barComposed = true
            keyboardHideAlpha.animateTo(1f, TangentMotion.ImeTrack)
        }
    }

    // Wide layout: pin the pill (and its ghosts/toolbar) into a capped-width band at the bottom-right
    // instead of stretching full-width. SPLIT moves the action buttons into a cluster left of the
    // pill (toolbar beside, not above); CORNER keeps the classic stacked pill+toolbar, just corner-
    // pinned. On phones both flags are inert.
    val splitMode = isWideLayout && wideBarLayout == WideBarLayout.SPLIT
    // Band width for the pill + its ghost neighbours in wide modes. Sized so a normal address pill
    // reads comfortably without swallowing the whole tablet width.
    val wideBandWidth = 400.dp
    // The stacked (toolbar-above-pill) treatment is used everywhere EXCEPT SPLIT, where the buttons
    // move beside the pill as a separate left cluster.
    val barStackedToolbar = showBottomToolbar && !splitMode

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .graphicsLayer {
                val baseAlpha = (1f - (gridProgress() * 1.5f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                alpha = baseAlpha * keyboardHideAlpha.value
            },
    ) {
        if (barComposed) {
            val barHeight = if (activeState.mode == BarMode.Editing) {
                72.dp
            } else {
                56.dp
            }
            val toolbarHeight = if (showBottomToolbar && activeState.mode != BarMode.Editing) 48.dp else 0.dp
            val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomPadding = navBarHeight.coerceAtLeast(24.dp)
            val totalHeight = barHeight + bottomPadding + toolbarHeight
            // We want the gradient to extend through the entire height of the bottom taskbar plus
            // a 150.dp extension above it.
            val gradientExtension = 120.dp
            val boxHeight = totalHeight + gradientExtension

            // To keep the system navigation buttons or gesture bar completely opaque, we sum
            // the system navigation bar height and a fixed opaque chrome height.
            val opaqueChromeHeight = 0.dp
            val boxHeightPx = with(density) { boxHeight.toPx() }
            val opaqueHeightPx = with(density) { (opaqueChromeHeight + navBarHeight).toPx() }
            val fadeStartFraction = if (boxHeightPx > 0f) {
                (1f - (opaqueHeightPx / boxHeightPx)).coerceIn(0f, 1f)
            } else {
                0.7f
            }
            // A lower tone than the pill (surfaceContainerHighest) so the pill has clear contrast
            // against the scrim instead of blending into it.
            val scrimColor = MaterialTheme.colorScheme.surfaceContainerLow

            // Phone: a full-width vertical gradient that goes fully opaque at the bottom (hides page
            // content behind the nav bar). Wide: the band is only a 400dp corner, so a vertical
            // gradient would leave a hard vertical seam where it stops at the band's left edge. Use a
            // radial glow centred on the bottom of the band instead — its radius equals half the band
            // width, so it fades to fully transparent exactly at every band edge (no seam) while still
            // giving the corner pill a soft backing for contrast.
            val scrimBrush = if (isWideLayout) {
                val bandWidthPx = with(density) { wideBandWidth.toPx() }
                // Soft pool, NOT a spotlight. Low peak alpha so there is no bright flat disc, but
                // the plateau is held out to ~0.8 of the radius so it still backs the OUTER toolbar
                // buttons (which sit near the band edges), then fades quickly to Transparent by the
                // edge. Radius stays bandWidthPx/2 so alpha reaches Transparent exactly at every band
                // edge — do not enlarge it or a vertical seam reappears. The excessive UPWARD reach
                // is trimmed by the bottom-anchored scaleY squash on the scrim Box below (wide only),
                // which flattens this circle into a wide, shallow ellipse.
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to scrimColor,
                        0.45f to scrimColor,
                        0.65f to scrimColor.copy(alpha = 0.6f),
                        0.82f to scrimColor.copy(alpha = 0.28f),
                        0.93f to scrimColor.copy(alpha = 0.1f),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(bandWidthPx / 2f, boxHeightPx),
                    radius = bandWidthPx / 2f,
                )
            } else {
                Brush.verticalGradient(
                    *arrayOf(
                        0.0f to Color.Transparent,
                        (fadeStartFraction * 0.35f) to scrimColor.copy(alpha = 0.2f),
                        (fadeStartFraction * 0.6f) to scrimColor.copy(alpha = 0.5f),
                        (fadeStartFraction * 0.82f) to scrimColor.copy(alpha = 0.8f),
                        fadeStartFraction to scrimColor,
                        1.0f to scrimColor,
                    )
                )
            }

            // In wide modes the pill/ghosts/scrim live in a capped-width band pinned bottom-right; on
            // phones they fill the screen (classic centred bar). Everything inside keeps using
            // `.align(BottomCenter)`, which now resolves within the band.
            // clipToBounds masks the neighbour/ghost pills to the capped band: at rest they sit a
            // `pillStride` (~368dp) away from the band-centred active pill, which on a wide screen is
            // NOT off-screen (unlike a phone, where pillStride ≈ full width). Without the clip the
            // previous-tab ghost floats out over the page content mid-screen. With it, ghosts stay
            // hidden at rest and slide cleanly in/out of the band's edge during a horizontal switch.
            // Scrim lives OUTSIDE the band's clipToBounds. The radial brush fades to Transparent on
            // its own, so it needs no clip and produces no seam — which lets the wide-mode oval be
            // stretched horizontally (scaleX) PAST the band edges to fully back the outer toolbar
            // buttons, and flattened vertically (scaleY) so it stays a shallow oval that doesn't
            // climb high above the pill. Bottom-centre pivot keeps it anchored at the nav edge and
            // centred on the pill. On phones (vertical gradient) both scales are a no-op.
            if (activeState.mode != BarMode.Editing) {
                val scrimModifier = if (isWideLayout) {
                    Modifier.align(Alignment.BottomEnd).width(wideBandWidth)
                } else {
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                }
                Box(
                    modifier = scrimModifier
                        .requiredHeight(boxHeight)
                        .graphicsLayer {
                            val collapse = barCollapseProgress()
                            val readerHide = readerHideOffsetPx()
                            alpha = (1f - collapse).coerceIn(0f, 1f)
                            translationY = (toolbarHeight.toPx() + bottomPadding.toPx()) * collapse + readerHide
                            if (isWideLayout) {
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                scaleX = 1.85f
                                scaleY = 0.9f
                                // Push the oval toward the right bezel so its right half runs off
                                // the screen edge (looks like the bezel clips it), while its left
                                // side still spreads behind the pill + toolbar buttons.
                                translationX += 110.dp.toPx()
                            }
                        }
                        .background(scrimBrush)
                )
            }

            val bandModifier = if (isWideLayout) {
                Modifier.align(Alignment.BottomEnd).width(wideBandWidth).clipToBounds()
            } else {
                Modifier.fillMaxSize()
            }
            Box(modifier = bandModifier) {

            val width = contentSize.width.toFloat()
        val pageStride = width + tabSwitchGapPx
        val density = LocalDensity.current
        // The pills are spaced by the band they occupy: the full screen on phones, the capped band on
        // wide screens. `pageStride` stays screen-width so the parallax still lands the neighbour pill
        // at 0 exactly when the (full-width) page panes complete their swipe.
        val pillBandPx = if (isWideLayout) with(density) { wideBandWidth.toPx() } else width
        val pillStride = pillBandPx - with(density) { 32.dp.toPx() }
        // Parallax: the full-width page panes slide by the full `pageStride` (width + inter-tab
        // gap), but the pills are spaced only `pillStride` apart. Scaling the pill
        // translations by pillStride/pageStride makes the tab content move slightly more than the pill
        // while dragging — Safari's depth effect — and still lands both at 0 on commit.
        val pillParallax = if (pageStride > 0f) pillStride / pageStride else 1f
        val pillWidthPx = pillBandPx - with(density) { 40.dp.toPx() }
        val minScale = 0.85f

        val getPillScale = { txRaw: Float ->
            if (pillStride > 0f) {
                val fraction = (abs(txRaw) / pillStride).coerceIn(0f, 1f)
                1f - (1f - minScale) * fraction
            } else {
                1f
            }
        }

        val activeTx = { activeOffsetX() * pillParallax }
        val activeScale = { getPillScale(activeTx()) }

        val prevTxRaw = { activeOffsetX() * pillParallax - pillStride }
        val prevScale = { getPillScale(prevTxRaw()) }
        val prevTx = {
            val raw = prevTxRaw()
            val scale = prevScale()
            raw + (1f - scale) * (pillWidthPx / 2f)
        }

        val nextTxRaw = { activeOffsetX() * pillParallax + pillStride }
        val nextScale = { getPillScale(nextTxRaw()) }
        val nextTx = {
            val raw = nextTxRaw()
            val scale = nextScale()
            raw - (1f - scale) * (pillWidthPx / 2f)
        }

        val ghostTxRaw = { activeOffsetX() * pillParallax + pillStride }
        val ghostScale = { getPillScale(ghostTxRaw()) }
        val ghostTx = {
            val raw = ghostTxRaw()
            val scale = ghostScale()
            raw - (1f - scale) * (pillWidthPx / 2f)
        }

        // Neighbor / ghost pills are the horizontal tab-switch peek preview. While editing the
        // address bar they must not render — the editing pill is a distinct input surface, and the
        // adjacent pills peeking at the screen edges are just visual noise behind the sheet.
        val showNeighborPills = activeState.mode != BarMode.Editing
        if (previousTab != null && width > 0f && !activeReaderActive && showNeighborPills) {
            GhostSafariBar(
                state = ghostBarState(previousTab, tabCount, activeState.mode, searchEngine, showSearchTermsInBar),
                barCollapseProgress = barCollapseProgress,
                pillTranslationX = prevTx,
                pillScale = prevScale,
                isReaderEligible = eligibleReaderTabs.contains(previousTab.id),
                isReaderActive = activeReaderTabs.contains(previousTab.id),
                readerTheme = readerTheme,
                onClick = {
                    if (controlsEnabled) {
                        onSelectPreviousTab()
                    }
                },
                showBottomToolbar = barStackedToolbar,
                canGoForward = previousTab.content.canGoForward,
            )
        }
        if (nextTab != null && width > 0f && !activeReaderActive && showNeighborPills) {
            GhostSafariBar(
                state = ghostBarState(nextTab, tabCount, activeState.mode, searchEngine, showSearchTermsInBar),
                barCollapseProgress = barCollapseProgress,
                pillTranslationX = nextTx,
                pillScale = nextScale,
                isReaderEligible = eligibleReaderTabs.contains(nextTab.id),
                isReaderActive = activeReaderTabs.contains(nextTab.id),
                readerTheme = readerTheme,
                onClick = {
                    if (controlsEnabled) {
                        onSelectNextTab()
                    }
                },
                showBottomToolbar = barStackedToolbar,
                canGoForward = nextTab.content.canGoForward,
            )
        }
        if (showNewTabGhost && nextTab == null && width > 0f && !activeReaderActive && showNeighborPills) {
            GhostSafariBar(
                state = BarUiState(
                    displayUrl = "",
                    tabCount = tabCount,
                    mode = BarMode.Expanded,
                    editingText = "",
                    blankNewTabPreview = true,
                ),
                barCollapseProgress = barCollapseProgress,
                collapseProgress = ZeroCollapseProgress,
                pillTranslationX = ghostTx,
                pillScale = ghostScale,
                onClick = {
                    if (controlsEnabled) {
                        onSelectNextTab()
                    }
                },
                showBottomToolbar = barStackedToolbar,
            )
        }
        val activeEligible = selectedTabId != null && eligibleReaderTabs.contains(selectedTabId)
        val activeActive = selectedTabId != null && activeReaderTabs.contains(selectedTabId)
        SafariBar(
            state = activeState,
            onUrlTap = onPrimaryUrlTap,
            onUrlLongPress = onUrlLongPress,
            onAaTap = onAaTap,
            onAaLongPress = onAaLongPress,
            onEditingTextChange = callbacks.onEditingTextChange,
            onEditingSubmit = callbacks.onEditingSubmit,
            onEditingCancel = callbacks.onEditingCancel,
            inlineAutocomplete = inlineAutocomplete,
            onVoiceInput = onVoiceInput,
            onDragStart = onDragStart,
            onDragDelta = onDragDelta,
            onDragEnd = onDragEnd,
            onReload = onReload,
            onReloadLongPress = onReloadLongPress,
            canGoForward = canGoForward,
            interactive = pillInteractive,
            controlsEnabled = controlsEnabled,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationY = readerHideOffsetPx()
                },
            pillTranslationX = activeTx,
            pillScale = activeScale,
            onBottomInsetMeasured = onBottomInsetMeasured,
            collapseProgress = barCollapseProgress,
            isReaderEligible = activeEligible,
            isReaderActive = activeActive,
            readerTheme = readerTheme,
            showBottomToolbar = barStackedToolbar,
            onCopyTap = onCopyTap,
            onForwardTap = onForwardTap,
            onShareTap = onShareTap,
            onBookmarksTap = onBookmarksTap,
            onTabsTap = onTabsTap,
            // Only the live pill morphs its address text into the editing field; ghosts opt out.
            enableAddressHero = true,
            // Reader mode owns the pill color, so the site tint only applies outside reader view.
            tintColor = if (activeReaderActive) null else barTintColor,
        )
        } // end band container

        // SPLIT: the action buttons live in a cluster to the LEFT of the pill band (toolbar beside
        // the pill, not stacked above it). It stays put during the horizontal tab-switch — only the
        // pill + its ghosts translate — and fades/slides with the same collapse + reader-hide curve
        // as the bar. Shown only when the user has the bottom toolbar enabled.
        if (splitMode && showBottomToolbar && activeState.mode != BarMode.Editing &&
            controlsEnabled && !activeReaderActive
        ) {
            val hasPage = activeState.pageUrl.isNotBlank() && activeState.pageUrl != "about:blank"
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = wideBandWidth)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp, end = 4.dp)
                    .height(56.dp)
                    .graphicsLayer {
                        val collapse = barCollapseProgress()
                        alpha = (1f - collapse).coerceIn(0f, 1f)
                        translationY = 56.dp.toPx() * collapse + readerHideOffsetPx()
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BottomToolbarButton(
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "Copy link",
                    onClick = onCopyTap,
                    enabled = hasPage,
                )
                BottomToolbarButton(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    onClick = onForwardTap,
                    enabled = canGoForward,
                )
                BottomToolbarButton(
                    icon = Icons.Default.Share,
                    contentDescription = "Share",
                    onClick = onShareTap,
                    enabled = hasPage,
                )
                BottomToolbarButton(
                    icon = Icons.Outlined.BookmarkBorder,
                    contentDescription = "Bookmarks",
                    onClick = onBookmarksTap,
                    enabled = true,
                )
                BottomToolbarButton(
                    icon = Icons.Outlined.Layers,
                    contentDescription = "Tabs",
                    onClick = onTabsTap,
                    enabled = true,
                )
            }
        }
        } // end if (!hideForKeyboard)
    }
}

/**
 * Arc/Zen-style refresh indicator: a thin horizontal line.
 *  - While pulling: a centre-out fill whose width and brightness track [progress]; at the
 *    threshold (progress == 1) it reaches full brightness (the haptic is fired by the caller).
 *  - While [refreshing]: a bright band sweeps horizontally across a faint full-width track.
 *
 * [alpha] is the caller's presence fade, applied to every stroke here rather than to a layer so it
 * stays a draw-phase read: the line arrives and leaves on it instead of cutting in on the first pull
 * pixel and disappearing mid-sweep the frame the reload finishes.
 */
@Composable
internal fun PullRefreshLine(
    progress: () -> Float,
    alpha: () -> Float,
    refreshing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val sweep by rememberInfiniteTransition(label = "refreshSweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    Canvas(modifier = modifier) {
        // Read the live pull magnitude in the draw phase so a pull frame repaints this line without
        // recomposing it (or TangentRoot).
        val progressValue = progress()
        val presence = alpha().coerceIn(0f, 1f)
        val w = size.width
        val cy = size.height / 2f
        val stroke = size.height
        // Faint full-width track.
        drawLine(
            color = color.copy(alpha = 0.15f * presence),
            start = Offset(0f, cy),
            end = Offset(w, cy),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (refreshing) {
            val band = w * 0.35f
            val start = -band + sweep * (w + band)
            drawLine(
                color = color.copy(alpha = color.alpha * presence),
                start = Offset(start.coerceAtLeast(0f), cy),
                end = Offset((start + band).coerceAtMost(w), cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        } else if (progressValue > 0f) {
            val fill = w * progressValue
            val left = (w - fill) / 2f
            drawLine(
                color = color.copy(alpha = lerp(0.45f, 1f, progressValue) * presence),
                start = Offset(left, cy),
                end = Offset(left + fill, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A page-snapshot overlay layer — the remember(asImageBitmap) + cropped Image idiom every
 * switch/predictive-back/sticky-cover overlay uses. Call sites pass their full modifier chain
 * (fillMaxSize/padding/graphicsLayer/clip) unchanged: the draw-phase transform contracts live at
 * the call sites, not here.
 */
@Composable
internal fun SnapshotImage(bitmap: android.graphics.Bitmap, modifier: Modifier = Modifier) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = img,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Match the live page's system-tint boost, overlaid at draw time (no-op when off).
        modifier = modifier.siteTintOverlay(LocalSiteTint.current),
    )
}

/**
 * A non-interactive SafariBar used for the tab-switch peek previews — the previous/next neighbor
 * pills and the blank new-tab ghost. All input callbacks are no-ops (the only interaction is
 * [onClick] on the pill itself), and the whole bar fades out with the collapse progress. Was
 * copy-pasted three times before being extracted; keep the ghosts on this one wrapper.
 */
@Composable
internal fun BoxScope.GhostSafariBar(
    state: BarUiState,
    barCollapseProgress: () -> Float,
    pillTranslationX: () -> Float,
    pillScale: () -> Float,
    onClick: () -> Unit,
    showBottomToolbar: Boolean,
    collapseProgress: () -> Float = barCollapseProgress,
    isReaderEligible: Boolean = false,
    isReaderActive: Boolean = false,
    readerTheme: com.firebolt9907.tangentbrowser.ui.reader.ReaderTheme =
        com.firebolt9907.tangentbrowser.ui.reader.ReaderTheme.System,
    canGoForward: Boolean = false,
) {
    SafariBar(
        state = state,
        onUrlTap = {},
        onAaTap = {},
        onEditingTextChange = {},
        onEditingSubmit = {},
        onEditingCancel = {},
        onDragStart = {},
        onDragDelta = { _ -> },
        onDragEnd = { _, _ -> },
        onReload = {},
        interactive = false,
        controlsEnabled = false,
        collapseProgress = collapseProgress,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .graphicsLayer {
                alpha = (1f - barCollapseProgress()).coerceIn(0f, 1f)
            },
        pillTranslationX = pillTranslationX,
        pillScale = pillScale,
        isReaderEligible = isReaderEligible,
        isReaderActive = isReaderActive,
        readerTheme = readerTheme,
        onClick = onClick,
        showBottomToolbar = showBottomToolbar,
        onCopyTap = {},
        canGoForward = canGoForward,
    )
}

internal fun ghostBarState(
    tab: TabSessionState,
    tabCount: Int,
    activeMode: BarMode,
    searchEngine: SearchEngine,
    showSearchTermsInBar: Boolean,
): BarUiState {
    val url = tab.content.url
    val host = if (url.isNotEmpty()) addressBarLabel(url, searchEngine, showSearchTermsInBar) else ""
    val mode = if (activeMode == BarMode.Editing) BarMode.Expanded else activeMode
    return BarUiState(
        displayUrl = host,
        tabCount = tabCount,
        mode = mode,
        editingText = "",
        compactDisplayUrl = host,
        pageUrl = url,
    )
}
