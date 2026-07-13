package com.firebolt9907.luminabrowser.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ExperimentalActivityApi
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.util.lerp
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.zIndex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firebolt9907.luminabrowser.LuminaApplication
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState
import com.firebolt9907.luminabrowser.state.LuminaViewModel
import com.firebolt9907.luminabrowser.state.SitePreferences
import com.firebolt9907.luminabrowser.state.compactHost
import com.firebolt9907.luminabrowser.state.hostFromUrl
import com.firebolt9907.luminabrowser.ui.addons.AddonsSheet
import com.firebolt9907.luminabrowser.ui.chrome.AaSheet
import com.firebolt9907.luminabrowser.ui.chrome.AddressSheet
import com.firebolt9907.luminabrowser.ui.chrome.FindInPageBar
import com.firebolt9907.luminabrowser.ui.chrome.SafariBar
import com.firebolt9907.luminabrowser.ui.settings.SettingsSheet
import com.firebolt9907.luminabrowser.sync.FirefoxSyncEvent
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.HistoryState
import com.firebolt9907.luminabrowser.ui.content.ContentHost
import com.firebolt9907.luminabrowser.ui.grid.DeckDragMinScale
import com.firebolt9907.luminabrowser.ui.grid.DeckTravelFrac
import com.firebolt9907.luminabrowser.ui.grid.deckDragEase
import com.firebolt9907.luminabrowser.ui.grid.GridTab
import mozilla.components.browser.icons.IconRequest
import com.firebolt9907.luminabrowser.ui.grid.ClassicTabGridOverlay
import com.firebolt9907.luminabrowser.ui.grid.TabGridOverlay
import com.firebolt9907.luminabrowser.ui.grid.TabViewMode
import com.firebolt9907.luminabrowser.ui.reader.hydrateAppearanceFromPrefs
import com.firebolt9907.luminabrowser.ui.reader.openReaderLink
import com.firebolt9907.luminabrowser.ui.reader.toggleReader
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape
import com.firebolt9907.luminabrowser.ui.bookmarks.BookmarksSheet
import com.firebolt9907.luminabrowser.ui.downloads.DownloadsSheet
import com.firebolt9907.luminabrowser.ui.history.HistorySheet
import com.firebolt9907.luminabrowser.ui.passwords.PasswordsSheet
import com.firebolt9907.luminabrowser.ui.sync.HandoffCard
import com.firebolt9907.luminabrowser.ui.sync.SyncedTabsSheet
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.window.WindowRequest
import com.firebolt9907.luminabrowser.ui.content.extractCacheHtml
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

private const val SwitchDistanceThresholdPx = 120f
private const val SwitchVelocityThresholdPxS = 800f
private const val PredictiveBackVelocityThresholdPxS = 900f
private const val PredictiveBackCommitProgressThreshold = 0.33f
private const val PredictiveBackCoverFallbackMs = 900L
private const val PredictiveBackCoverMaxMs = 2500L
// Scroll travel (px) that flips the pill between expanded and compact. Two thresholds give
// hysteresis so tiny scroll reversals don't flicker the state: scroll down past the snap threshold
// to collapse, up past the (lower) expand threshold to expand. The accumulator is clamped to
// [0, snap] so the pill stays responsive both ways — a compact pill expands after ~60 px of up-scroll
// no matter how far you'd scrolled down.
private const val BarCollapseSnapThresholdPx = 180f
private const val BarExpandSnapThresholdPx = 120f
// Pre-snap "indication": as the scroll accumulator approaches the threshold that would flip the
// pill's state, the collapse progress is nudged this far toward the opposite state — a small,
// eased *morph* preview (expanded starts shrinking toward compact, compact starts growing toward
// expanded). Because it lives on the same progress value the snap animates, the flip continues
// seamlessly from the indicated shape instead of popping back to the resting size.
private const val BarCollapsePreviewMax = 0.12f
// Hoisted constant collapse-progress provider for the always-expanded blank new-tab ghost.
private val ZeroCollapseProgress: () -> Float = { 0f }
// After scrolling stops mid-band, spring the preview back to its resting state so the indication
// nudge doesn't linger; this is the idle delay before that settle runs.
private const val BarCollapseIdleSettleMs = 140L
// Min reader scroll travel (px) before the pill auto-hides/reveals, so tiny jitter doesn't toggle it.
private const val ReaderBarHideSlopPx = 6f
// Cap on the URL-keyed in-memory snapshot map so a long browsing session can't retain a
// screen-sized bitmap for every page ever visited. Evicted entries still live in the disk cache.
private const val MaxRecentPageSnapshots = 24
private const val CompactInteractionThreshold = 0.5f
// Decelerate-only curve for gesture-release slides (predictive back, grid dismiss): the motion
// continues from the finger and eases into the target without re-accelerating, matching iOS's
// interactive-pop settle. Default FastOutSlowIn eases in *and* out, which feels wrong mid-gesture.
private val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)

private const val PredictiveBackDurationMs = 220
private val TabSwitchGap = 64.dp
private val PullRefreshThreshold = 96.dp
private val PullRefreshMaxDistance = 132.dp
private const val GridCommitVelocityPxS = 1000f

// Safari-style motion for the tab-overview (grid) transitions. iOS springs are authored as a
// "response" (seconds to settle) + damping fraction; Compose's stiffness maps as
// stiffness = (2*PI / response)^2. Safari's overview zoom is a soft, lightly-damped settle that
// carries the finger's release velocity rather than snapping. Tuned to iOS's grace: response
// ~0.46s (softer/longer than a snappy Material spring) with a lively-but-controlled damping so the
// motion eases in and settles crisply rather than crawling to rest. Centralised here so the
// open/settle/cancel/close phases stay one coherent feel.
private object LuminaMotion {
    // Overview zoom-in (carousel → grid). response ~0.46s. Damping 0.8 gives the barely-there
    // overshoot-and-settle that reads as "iOS smooth" instead of a hard stop.
    val GridOpen = spring<Float>(dampingRatio = 0.8f, stiffness = 190f)
    // Settle to a target and cancel-return. Same softness, a hair more damped so a cancel doesn't
    // visibly bounce back into the chrome.
    val GridSettle = spring<Float>(dampingRatio = 0.85f, stiffness = 200f)
    // Overview zoom-out (grid → fullscreen tab): tap-to-select / back / new-tab exit. Symmetric
    // softness with GridOpen so entry and exit feel like one motion. Damping 0.9 keeps it crisp
    // (no dead critical-damping crawl at the end) — the render clamps progress to [0,1], so the
    // sub-percent undershoot a light damping produces can't show as an overshoot past the tab.
    val GridClose = spring<Float>(dampingRatio = 0.9f, stiffness = 200f)
    // Toolbar collapse/expand — the single spring that drives both directions (Safari-style). No
    // finger-tracking preview: crossing the scroll threshold animates the pill smoothly between the
    // expanded and compact states with this one spring, so up and down feel identical. Near-critical
    // damping (0.9) + a medium-low stiffness give a quick, smooth glide with no visible bounce.
    val BarCollapse = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
}

@OptIn(ExperimentalActivityApi::class)
@Composable
fun LuminaRoot(
    viewModel: LuminaViewModel = viewModel(),
    isInPipMode: Boolean = false,
    onFullScreenChanged: (Boolean) -> Unit = {},
    onRequestPipMode: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val components = (appContext as LuminaApplication).components
    val state by viewModel.state.collectAsStateWithLifecycle()
    val browserState by components.browserState.collectAsStateWithLifecycle()
    val addonsState by components.addonController.state.collectAsStateWithLifecycle()
    val firefoxSyncState by components.firefoxSyncController.state.collectAsStateWithLifecycle()

    var isVideoFullscreen by remember { mutableStateOf(false) }

    SyncSystemBarsWithFullscreen(isVideoFullscreen)

    val readerManager = remember { com.firebolt9907.luminabrowser.ui.reader.ReaderManager.instance }
    val activeReaderTabs by readerManager.activeReaderTabs.collectAsStateWithLifecycle()
    val eligibleReaderTabs by readerManager.eligibleReaderTabs.collectAsStateWithLifecycle()
    val tabArticles by readerManager.tabArticles.collectAsStateWithLifecycle()
    val readerTheme by readerManager.theme.collectAsStateWithLifecycle()
    val readerFontSize by readerManager.fontSize.collectAsStateWithLifecycle()
    val readerFontName by readerManager.fontName.collectAsStateWithLifecycle()

    // Reader appearance lives in the (Context-less) ReaderManager singleton, so bridge it to disk
    // here: load the persisted values into the manager once, then mirror later changes back to the
    // store. Loading synchronously before starting the collectors avoids a startup race where the
    // first (replayed) emission would overwrite the saved value with the default.
    LaunchedEffect(Unit) {
        val prefs = components.globalPreferencesStore
        readerManager.hydrateAppearanceFromPrefs(prefs)
        launch { readerManager.theme.collect { prefs.readerThemeName = it.name } }
        launch { readerManager.fontSize.collect { prefs.readerFontSize = it } }
        launch { readerManager.fontName.collect { prefs.readerFontName = it } }
        launch { readerManager.useGoogleCache.collect { prefs.useGoogleCache = it } }
    }

    // Find-in-page state. The match counts are pushed by the engine session's onFindResult.
    var findInPageActive by remember { mutableStateOf(false) }
    var showBottomToolbar by remember { mutableStateOf(components.globalPreferencesStore.showBottomToolbar) }
    // Which overview layout the all-tabs view renders; persisted, cycled from the overview toolbar.
    var tabViewMode by remember {
        mutableStateOf(TabViewMode.fromName(components.globalPreferencesStore.tabViewModeName))
    }
    var findQuery by remember { mutableStateOf("") }
    var findActiveMatch by remember { mutableIntStateOf(0) }
    var findTotalMatches by remember { mutableIntStateOf(0) }

    var visualSelectedTabId by remember { mutableStateOf<String?>(null) }
    val actualSelectedTabId = browserState.selectedTabId
    val selectedTabId = visualSelectedTabId ?: actualSelectedTabId
    val isReaderActiveOnSelectedTab = selectedTabId != null && activeReaderTabs.contains(selectedTabId)
    val selectedTabArticle = selectedTabId?.let { tabArticles[it] }
    // Retain the last non-null article so the reader's exit animation can keep rendering it:
    // AnimatedVisibility composes its content for the full exit transition, by which point
    // selectedTabArticle may already have cleared to null.
    val lastReaderArticle = remember { mutableStateOf(selectedTabArticle) }
    LaunchedEffect(selectedTabArticle) {
        if (selectedTabArticle != null) lastReaderArticle.value = selectedTabArticle
    }
    val selectedTab = browserState.tabs.firstOrNull { it.id == selectedTabId }
    val actualSelectedTab = browserState.tabs.firstOrNull { it.id == actualSelectedTabId }
    val selectedEngineSession = actualSelectedTab?.engineState?.engineSession
    val selectedContent = selectedTab?.content

    // Tabs that host an add-on action popup ("quick settings"). They must NOT become the active tab
    // for web extensions, or the popup (uBlock, Chrome Mask, …) would query itself (about:blank)
    // instead of the page the user opened it from.
    val popupTabIds = remember { mutableSetOf<String>() }
    // Mirror WebExtensionSupport: tell the add-on controller which tab is "current" so it can attach
    // per-tab action handlers (the source of every action update) and mark it active for extensions.
    LaunchedEffect(actualSelectedTabId, selectedEngineSession) {
        val current = actualSelectedTabId ?: return@LaunchedEffect
        if (current in popupTabIds) return@LaunchedEffect
        components.addonController.setActiveSession(selectedEngineSession)
    }
    val actualSelectedContent = actualSelectedTab?.content
    val selectedIndex = browserState.tabs.indexOfFirst { it.id == selectedTabId }
    val previousTab = browserState.tabs.getOrNull(selectedIndex - 1)
    val nextTab = browserState.tabs.getOrNull(selectedIndex + 1)
    val previousTabId = previousTab?.id
    val nextTabId = nextTab?.id
    val tabCount = browserState.tabs.size
    val canCreateTabFromRightEdge = selectedIndex == tabCount - 1 && selectedIndex >= 0

    val density = LocalDensity.current
    // Semantic haptics: each gesture gets a texture that matches its meaning (soft rumble when the
    // grid catches, a light tick when a release threshold arms, etc.) instead of one sharp tap.
    val luminaHaptics = rememberLuminaHaptics()
    // Per-gesture build-up ramps: a gradually growing vibration as a drag nears its commit point.
    // (Grid open is intentionally excluded — it's a single soft rumble at the threshold, no ramp.)
    val newTabRamp = rememberDragRamp(luminaHaptics)
    val pullRamp = rememberDragRamp(luminaHaptics)
    val imeInsets = WindowInsets.ime
    val isKeyboardVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    // Upward travel required before a pill drag engages the grid gesture. Without a slop, a
    // pixel of vertical wobble during a horizontal tab swipe flips gridGestureActive for the
    // rest of the gesture, silently switching rendering from the cheap two-pane overlay to the
    // per-frame grid path (and dropping the edge rubber-band walls).
    val gridEngageSlopPx = LocalViewConfiguration.current.touchSlop
    val topChromeInsetPx = max(
        WindowInsets.statusBars.getTop(density),
        WindowInsets.displayCutout.getTop(density),
    )
    val tabSwitchGapPx = with(density) { TabSwitchGap.toPx() }
    val pullRefreshThresholdPx = with(density) { PullRefreshThreshold.toPx() }
    val pullRefreshMaxDistancePx = with(density) { PullRefreshMaxDistance.toPx() }
    var bottomChromeInsetPx by remember { mutableIntStateOf(0) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    val offsetXState = remember { mutableFloatStateOf(0f) }
    var offsetX by offsetXState
    var predictiveBackOffsetPx by remember { mutableFloatStateOf(0f) }
    // Boolean gate for the predictive-back overlay. Derived so the per-frame predictiveBackOffsetPx
    // updates during a back-swipe flip this only at the 0↔>0 boundary; the overlay's own transforms
    // already read the raw value inside graphicsLayer/drawBehind (draw phase), so the subtree no
    // longer recomposes every frame of the gesture.
    val predictiveBackActive by remember { derivedStateOf { predictiveBackOffsetPx > 0f } }
    var dragCommitted by remember { mutableStateOf(false) }
    var stickyIncomingSnapshot by remember { mutableStateOf<Bitmap?>(null) }
    var stickyIncomingTabId by remember { mutableStateOf<String?>(null) }
    // False until Gecko fires its first paint callback for the current tab. Covers the grey
    // surface that GeckoView shows before any content has been composited.
    var geckoHasPainted by remember { mutableStateOf(false) }
    var pendingActiveBarState by remember { mutableStateOf<BarUiState?>(null) }
    var pendingGeckoSelectTabId by remember { mutableStateOf<String?>(null) }
    var pendingGeckoSelectCreatedTab by remember { mutableStateOf(false) }
    var pendingGeckoEditBlankTab by remember { mutableStateOf(false) }
    var pendingGeckoAfterSelect by remember { mutableStateOf<(() -> Unit)?>(null) }
    var stickyBackCoverSnapshot by remember { mutableStateOf<Bitmap?>(null) }
    var stickyBackCoverUrl by remember { mutableStateOf<String?>(null) }
    var gestureCurrentSnapshot by remember { mutableStateOf<Bitmap?>(null) }
    var activePredictiveBackSnapshot by remember { mutableStateOf<Bitmap?>(null) }
    var tabSwitchCurrentSnapshot by remember { mutableStateOf<Bitmap?>(null) }
    var leavingSnapshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var leavingSnapshotOffsetX by remember { mutableFloatStateOf(0f) }
    var leavingSnapshotTopPadPx by remember { mutableIntStateOf(0) }
    var leavingSnapshotTabId by remember { mutableStateOf<String?>(null) }
    var leavingSnapshotJob by remember { mutableStateOf<Job?>(null) }
    var incomingSnapshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var incomingSnapshotOffsetX by remember { mutableFloatStateOf(0f) }
    var incomingSnapshotTopPadPx by remember { mutableIntStateOf(0) }
    var incomingSnapshotTabId by remember { mutableStateOf<String?>(null) }
    var stickyIncomingTopPadPx by remember { mutableIntStateOf(0) }
    // Fade-out phase of the sticky incoming cover: when the cover is dismissed because the tab
    // underneath has appeared (paint callback / fallback timeout), it moves into these slots and
    // fades over the live page instead of popping off. Gesture takeovers bypass the fade.
    var fadingCoverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fadingCoverTopPadPx by remember { mutableIntStateOf(0) }
    var fadingCoverActive by remember { mutableStateOf(false) }
    var fadingCoverJob by remember { mutableStateOf<Job?>(null) }
    val fadingCoverAlpha = remember { Animatable(0f) }
    var commitAnimNewTab by remember { mutableStateOf(false) }
    var gridDragPx by remember { mutableFloatStateOf(0f) }
    var gridGestureActive by remember { mutableStateOf(false) }
    var gridOpen by remember { mutableStateOf(false) }
    var gridAnimationJob by remember { mutableStateOf<Job?>(null) }
    val gridProgressState = remember { Animatable(0f) }
    // Decoupled X-axis progress for grid cell layout. While the gesture is active this stays at 0
    // so the tab being dragged keeps its carousel-relative X (under the finger). On release it
    // springs to its destination (1 for openGrid, 0 for cancel/closeGrid) in lockstep with
    // gridProgressState, so cells slide to their columns only after the user lets go — like the
    // iOS app switcher.
    val gridXProgressState = remember { Animatable(0f) }
    // Direct drag progress during gesture — avoids scope.launch per delta frame.
    // gridProgressState is only used for spring animations; this drives the render during drag.
    var gridDragProgress by remember { mutableFloatStateOf(0f) }
    // True once the upward drag has crossed the grid-commit threshold (50% of travel) so we fire
    // the confirmation haptic exactly once on crossing, and don't re-fire it on release.
    var gridThresholdHapticFired by remember { mutableStateOf(false) }
    // Set when a pill gesture is judged (once, at the vertical slop crossing) to be a horizontal
    // tab swipe; vertical motion is ignored for the rest of that gesture. Reset on drag start.
    var gridAxisLockedHorizontal by remember { mutableStateOf(false) }
    // Mid-switch handoff: when grid grabbed during a horizontal commit spring, the leaving and
    // incoming tabs' frozen X offsets seed the grid animation. Otherwise these stay null and the
    // grid derives live tabs dynamically from offsetX/selectedTabId/previousTabId/nextTabId.
    var gridHandoffSelectedTabId by remember { mutableStateOf<String?>(null) }
    var gridHandoffLeavingTabId by remember { mutableStateOf<String?>(null) }
    var gridHandoffSelectedOffsetX by remember { mutableFloatStateOf(0f) }
    var gridHandoffLeavingOffsetX by remember { mutableFloatStateOf(0f) }
    var gridHandoffSelectedTopPadPx by remember { mutableIntStateOf(0) }
    var gridHandoffLeavingTopPadPx by remember { mutableIntStateOf(0) }
    // Finger-tracking positions captured at drag release; drive the commit animation from
    // drag-end to grid cells. Cleared after the spring finishes or when the grid closes.
    var gridCommitFromPositions: List<Triple<Float, Float, Float>>? by remember { mutableStateOf(null) }
    var gridCommitFromProgress by remember { mutableFloatStateOf(0f) }
    var gridCancelFromProgress by remember { mutableFloatStateOf(0f) }
    // Stays true from closeGrid(animateBack=true) until afterClose() completes, keeping gridEngaged
    // true so the tab cards at progress=0 (fullscreen position) cover the live Gecko view during
    // the gap between animation-end and the deferred tab selection in afterClose.
    var gridWaitingForAfterClose by remember { mutableStateOf(false) }
    // True while the grid is closing after the user tapped the "+" cell. The "+" cell animates
    // to fullscreen instead of the previously-selected tab; real tabs animate off-screen left.
    var gridNewTabSelected by remember { mutableStateOf(false) }
    // Measured height of the grid's floating bottom controls (nav-bar inset + toolbar + the handoff
    // banner when present). Drives how far the last grid row clears the bottom, so the clearance
    // tracks button vs gesture navigation and the banner's visibility instead of a fixed guess.
    var gridBottomControlsHeightPx by remember { mutableIntStateOf(0) }
    var currentSnapshotRequestPurpose by remember { mutableStateOf<SnapshotRequestPurpose?>(null) }
    var currentSnapshotRequestId by remember { mutableIntStateOf(0) }
    // Bumped when a full-screen overlay (e.g. the appearance sheet's opaque ScaledPageBackdrop) is
    // dismissed, to force the z-ordered GeckoView surface to recomposite. Without this the surface
    // keeps showing its stale pre-overlay frame — which covers the pill — until the user scrolls.
    var engineRefreshTick by remember { mutableIntStateOf(0) }
    var switchAnimationJob by remember { mutableStateOf<Job?>(null) }
    var predictiveBackAnimationJob by remember { mutableStateOf<Job?>(null) }
    var barCollapseAnimationJob by remember { mutableStateOf<Job?>(null) }
    var barCollapseIdleJob by remember { mutableStateOf<Job?>(null) }
    var pageScrollFrameJob by remember { mutableStateOf<Job?>(null) }
    var showAddons by remember { mutableStateOf(false) }
    var showSyncedTabs by remember { mutableStateOf(false) }
    var showPasswords by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var firefoxSignInTabId by remember { mutableStateOf<String?>(null) }
    var showAa by remember { mutableStateOf(false) }
    var aaInitialTab by remember { mutableIntStateOf(0) }
    var sitePrefsVersion by remember { mutableIntStateOf(0) }
    var barCollapsePx by remember { mutableFloatStateOf(0f) }
    var barCollapseCompact by remember { mutableStateOf(false) }
    var barCollapseDragActive by remember { mutableStateOf(false) }
    var barCollapseDragPx by remember { mutableFloatStateOf(0f) }
    var barCollapseProgressTarget by remember { mutableFloatStateOf(0f) }
    var userScrollInteractionActive by remember { mutableStateOf(false) }
    var pullRefreshDistancePx by remember { mutableFloatStateOf(0f) }
    // True from the moment a pull crosses the threshold and triggers a reload until that load
    // completes — drives the "pulsing while loading" phase of the refresh line.
    var pullRefreshing by remember { mutableStateOf(false) }
    // One-shot guard so the threshold haptic fires once per crossing, not every drag frame.
    var pullThresholdHapticFired by remember { mutableStateOf(false) }
    var newTabThresholdHapticFired by remember { mutableStateOf(false) }
    val pendingPageScrollDelta = remember { floatArrayOf(0f) }
    val recentTabSnapshots = remember { mutableStateMapOf<String, Bitmap>() }
    val recentPageSnapshots = remember { mutableStateMapOf<String, Bitmap>() }
    // Bitmaps rendered from the native ReaderView, keyed by tab id. Preferred over the engine
    // capture for reader-active tabs so grid/tab-switch reflect the reader content, not the hidden
    // web page. Pruned to the active-reader set (see LaunchedEffect below).
    val readerTabSnapshots = remember { mutableStateMapOf<String, Bitmap>() }
    val lastObservedUrlByTab = remember { mutableStateMapOf<String, String>() }
    val previousObservedUrlByTab = remember { mutableStateMapOf<String, String>() }
    val barCollapseProgressState = remember { Animatable(0f) }
    var barCollapseProgress by remember { mutableFloatStateOf(0f) }
    // Stable lambda that reads the live collapse progress. Passing this (instead of the raw Float)
    // down through BottomBarLayer/SafariBar/ContentHost keeps the per-frame collapse spring out of
    // composition: the value is read only inside layout/draw phases, so the pill morph runs without
    // recomposing the bar or the engine host. See BarPill.PillMorph.
    val barCollapseProgressProvider = remember { { barCollapseProgress } }
    // Reader-mode pill auto-hide: 0 = pill shown, 1 = pill fully translated off the bottom edge.
    // Driven by reader scroll direction (separate from the web-page compact collapse above).
    val readerBarHidden = remember { Animatable(0f) }
    // Boolean gate for whether the pull-refresh line is shown. Derived so the per-frame
    // pullRefreshDistancePx updates (during a pull) flip this only at the 0↔>0 boundary instead of
    // recomposing LuminaRoot on every drag frame; the live magnitude is passed to PullRefreshLine as
    // a lambda read in its draw phase.
    val pullRefreshActive by remember { derivedStateOf { pullRefreshDistancePx > 0f } }
    val pullRefreshProgressProvider = remember(pullRefreshThresholdPx) {
        { (pullRefreshDistancePx / pullRefreshThresholdPx).coerceIn(0f, 1f) }
    }
    val trackedPreviousUrl = selectedTabId?.let(previousObservedUrlByTab::get)
    val previousHistoryUrl = remember(
        selectedTabId,
        selectedContent?.canGoBack,
        selectedContent?.history,
        selectedContent?.loading,
        selectedContent?.url,
        trackedPreviousUrl,
    ) {
        val history = selectedContent?.history
        if (selectedContent?.canGoBack == true) {
            history?.let {
                predictiveBackTargetUrl(
                    history = it,
                    visibleUrl = selectedContent.url,
                    loading = selectedContent.loading,
                )
            } ?: trackedPreviousUrl
        } else {
            null
        }
    }

    LaunchedEffect(addonsState.pendingPrompt?.id) {
        if (addonsState.pendingPrompt != null) {
            showAddons = true
        }
    }

    // Clear the refresh-line "loading pulse" once the reload finishes (transition-driven, so it
    // stays on through the load and only clears when loading goes false).
    LaunchedEffect(selectedContent?.loading) {
        if (pullRefreshing && selectedContent?.loading == false) pullRefreshing = false
    }

    // Auto-engage reader mode whenever a tab becomes reader-eligible (after a navigation/load),
    // unless the user has explicitly turned reader off for that host. This makes every readable
    // article open straight into reader view by default; opting out on a site is remembered.
    LaunchedEffect(eligibleReaderTabs) {
        eligibleReaderTabs.forEach { tabId ->
            if (!readerManager.isReaderActive(tabId)) {
                val url = browserState.tabs.firstOrNull { it.id == tabId }?.content?.url.orEmpty()
                val host = hostFromUrl(url)
                if (host.isNotEmpty() && !components.sitePreferencesStore.isReaderAutoDisabled(host)) {
                    readerManager.setReaderActive(tabId, true)
                }
            }
        }
    }

    // Drop reader thumbnails once a tab exits reader mode (or closes) so its grid/switch cover
    // falls back to the live web-page snapshot again.
    LaunchedEffect(activeReaderTabs) {
        readerTabSnapshots.keys.retainAll(activeReaderTabs)
    }

    // Receive find-in-page match counts from the selected tab's engine session. Re-registers when
    // the selected session changes; the ordinal Gecko reports is 0-based, shown as 1-based.
    DisposableEffect(selectedEngineSession) {
        val session = selectedEngineSession
        val observer = object : mozilla.components.concept.engine.EngineSession.Observer {
            override fun onFindResult(activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) {
                findActiveMatch = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
                findTotalMatches = numberOfMatches
            }
        }
        session?.register(observer)
        onDispose { session?.unregister(observer) }
    }

    // Keep find results accurate after a navigation or tab switch: the old page's matches no longer
    // apply, so reset the counter and re-run the search on the now-current page if the bar is open.
    LaunchedEffect(selectedContent?.url) {
        if (findInPageActive) {
            findActiveMatch = 0
            findTotalMatches = 0
            if (findQuery.isNotBlank()) selectedEngineSession?.findAll(findQuery)
        }
    }

    // Handoff: on every foreground, quietly refresh synced tabs and surface a banner when a tab
    // was active on another device within the last five minutes (network sync is debounced inside
    // the controller). ON_RESUME also fires on first launch, covering the cold-start case.
    // Foreground periodic sync: while resumed, keep re-syncing on Firefox's ~10-min active cadence;
    // stop on pause so the background 4-hour WorkManager sync takes over.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    components.firefoxSyncController.checkForHandoffTab()
                    components.firefoxSyncController.startForegroundPeriodicSync()
                }
                Lifecycle.Event.ON_PAUSE ->
                    components.firefoxSyncController.stopForegroundPeriodicSync()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            components.firefoxSyncController.stopForegroundPeriodicSync()
        }
    }

    // Retire the banner on its own once the 5-minute freshness window lapses, even with no new
    // sync results coming in to displace it.
    LaunchedEffect(firefoxSyncState.handoffTab) {
        val handoff = firefoxSyncState.handoffTab ?: return@LaunchedEffect
        val remainingMs = handoff.lastUsed + 5L * 60L * 1000L - System.currentTimeMillis()
        if (remainingMs > 0) delay(remainingMs)
        components.firefoxSyncController.expireHandoff(handoff)
    }

    LaunchedEffect(components.firefoxSyncController) {
        components.firefoxSyncController.events.collect { event ->
            when (event) {
                FirefoxSyncEvent.AuthenticationCompleted -> {
                    firefoxSignInTabId?.let { tabId ->
                        components.tabRepository.removeTab(tabId)
                        firefoxSignInTabId = null
                    }
                    showSyncedTabs = true
                }
            }
        }
    }

    /**
     * Instantly removes the sticky incoming cover *and* any in-flight fade. Used by gesture
     * takeovers (a new drag / predictive back), where a lingering translucent cover would sit
     * on top of the freshly-rendered gesture overlay.
     */
    fun clearStickyCover() {
        stickyIncomingSnapshot = null
        stickyIncomingTabId = null
        fadingCoverJob?.cancel()
        fadingCoverJob = null
        fadingCoverBitmap = null
        fadingCoverActive = false
    }

    /**
     * Dismisses the sticky incoming cover by cross-fading it into the live page underneath,
     * instead of the instant pop [clearStickyCover] does. For the natural end of a cover's life:
     * the covered tab has painted (or the fallback timeout decided it must have). A null bitmap
     * cover (the plain background placeholder) fades the same way. Note this nulls the sticky
     * state immediately — all gating logic treats the cover as gone; only the visual lingers.
     */
    fun fadeOutStickyCover() {
        if (stickyIncomingSnapshot == null && stickyIncomingTabId == null) return
        fadingCoverBitmap = stickyIncomingSnapshot
        fadingCoverTopPadPx = stickyIncomingTopPadPx
        fadingCoverActive = true
        stickyIncomingSnapshot = null
        stickyIncomingTabId = null
        fadingCoverJob?.cancel()
        fadingCoverJob = scope.launch {
            fadingCoverAlpha.snapTo(1f)
            fadingCoverAlpha.animateTo(0f, animationSpec = tween(durationMillis = 220))
            fadingCoverBitmap = null
            fadingCoverActive = false
            fadingCoverJob = null
        }
    }

    fun visualBarStateForUrl(
        url: String,
        count: Int,
        mode: BarMode = state.mode,
        blankNewTabPreview: Boolean = false,
    ): BarUiState {
        val host = if (url.isBlank() || url == "about:blank") {
            "about:blank"
        } else {
            compactHost(url)
        }
        val visualMode = if (mode == BarMode.Editing) BarMode.Expanded else mode
        return BarUiState(
            displayUrl = host,
            tabCount = count,
            mode = visualMode,
            editingText = "",
            compactDisplayUrl = host,
            pageUrl = url,
            blankNewTabPreview = blankNewTabPreview,
        )
    }

    fun clearPredictiveBackCover() {
        stickyBackCoverSnapshot = null
        stickyBackCoverUrl = null
    }

    fun resetPredictiveBackGesture() {
        predictiveBackOffsetPx = 0f
        gestureCurrentSnapshot = null
        activePredictiveBackSnapshot = null
        if (currentSnapshotRequestPurpose == SnapshotRequestPurpose.PredictiveBack) {
            currentSnapshotRequestPurpose = null
        }
    }

    fun resetTabSwitchSnapshot() {
        tabSwitchCurrentSnapshot = null
        if (currentSnapshotRequestPurpose == SnapshotRequestPurpose.TabSwitch) {
            currentSnapshotRequestPurpose = null
        }
    }

    fun requestTabSwitchSnapshot() {
        if (currentSnapshotRequestPurpose != SnapshotRequestPurpose.TabSwitch) {
            currentSnapshotRequestPurpose = SnapshotRequestPurpose.TabSwitch
            currentSnapshotRequestId += 1
        }
    }

    fun stagePendingGeckoSelection(
        tabId: String?,
        createdTab: Boolean = false,
        editBlankTab: Boolean = false,
        afterSelect: (() -> Unit)? = null,
    ) {
        pendingGeckoSelectTabId = tabId
        pendingGeckoSelectCreatedTab = createdTab
        pendingGeckoEditBlankTab = editBlankTab
        pendingGeckoAfterSelect = afterSelect
    }

    fun commitPendingGeckoSelection() {
        val tabId = pendingGeckoSelectTabId ?: return
        val createdTab = pendingGeckoSelectCreatedTab
        val editBlankTab = pendingGeckoEditBlankTab
        val afterSelect = pendingGeckoAfterSelect
        pendingGeckoSelectTabId = null
        pendingGeckoSelectCreatedTab = false
        pendingGeckoEditBlankTab = false
        pendingGeckoAfterSelect = null
        if (createdTab) {
            viewModel.selectCreatedTab(tabId, editBlankTab = editBlankTab)
        } else {
            components.tabRepository.selectTab(tabId)
        }
        afterSelect?.invoke()
    }

    fun cancelLeavingSnapshot() {
        commitPendingGeckoSelection()
        leavingSnapshotJob?.cancel()
        leavingSnapshotJob = null
        leavingSnapshotBitmap = null
        leavingSnapshotOffsetX = 0f
        leavingSnapshotTabId = null
        incomingSnapshotBitmap = null
        incomingSnapshotOffsetX = 0f
        incomingSnapshotTabId = null
        commitAnimNewTab = false
    }

    val cache = components.tabSnapshotCache
    // The State instance itself is handed to TabGridOverlay so grid cards can observe version
    // bumps via derivedStateOf without the version being a recomposing parameter.
    val snapshotCacheVersionState = cache.version.collectAsStateWithLifecycle()
    val snapshotCacheVersion by snapshotCacheVersionState
    fun snapshotFor(tabId: String?, url: String?): Bitmap? {
        val id = tabId ?: return null
        // Reader-active tabs render their thumbnail from the native ReaderView; the engine capture
        // would show the (invisible) underlying page.
        if (activeReaderTabs.contains(id)) readerTabSnapshots[id]?.let { return it }
        val pageUrl = url.orEmpty()
        val urlKey = pageUrl.takeIf { it.isNotBlank() && it != "about:blank" }
        // Prefer the URL-keyed snapshot: it is the bitmap captured while THIS url was actually
        // visible, so its content always matches the page. `recentTabSnapshots` is keyed by tab id
        // and holds the tab's most recent capture regardless of url — after a same-document (SPA)
        // navigation it still holds the PREVIOUS page's bitmap for the new url, which is exactly the
        // "current tab shows the previous page" bug. Fall back to it (and the disk cache) only when
        // the url itself has no snapshot yet.
        urlKey?.let(recentPageSnapshots::get)?.let { return it }
        urlKey?.let { cache.get(id, it) }?.let { return it }
        recentTabSnapshots[id]?.let { return it }
        val loaded = cache.get(id, pageUrl)
        if (loaded != null) {
            recentTabSnapshots[id] = loaded
        }
        return loaded
    }
    val predictiveBackSnapshot: Bitmap? = remember(
        selectedTabId,
        previousHistoryUrl,
        snapshotCacheVersion,
        recentPageSnapshots[previousHistoryUrl],
    ) {
        val id = selectedTabId ?: return@remember null
        val url = previousHistoryUrl ?: return@remember null
        // Do NOT use recentTabSnapshots here — it is keyed by tab ID and returns the current
        // page's most recent bitmap, not the previous history page's bitmap. URL-keyed lookup
        // is the only way to find a snapshot that was captured while that URL was actually visible.
        recentPageSnapshots[url] ?: cache.get(id, url)
    }
    val currentPageSnapshot: Bitmap? = remember(
        selectedTabId,
        selectedContent?.url,
        snapshotCacheVersion,
        selectedTabId?.let(recentTabSnapshots::get),
        recentPageSnapshots[selectedContent?.url],
        activeReaderTabs,
        selectedTabId?.let(readerTabSnapshots::get),
    ) {
        snapshotFor(selectedTabId, selectedContent?.url)
    }
    val adjacentDirection by remember {
        derivedStateOf {
            when {
                offsetX > 0f -> 1
                offsetX < 0f -> -1
                else -> 0
            }
        }
    }
    val overlayActive by remember { derivedStateOf { offsetX != 0f } }
    val nextTabIdState = rememberUpdatedState(nextTabId)
    val canCreateTabFromRightEdgeState = rememberUpdatedState(canCreateTabFromRightEdge)
    val showNewTabPane by remember {
        derivedStateOf {
            offsetX < 0f && nextTabIdState.value == null && canCreateTabFromRightEdgeState.value
        }
    }
    val adjacentSnapshot: Bitmap? = remember(
        adjacentDirection,
        previousTabId,
        previousTab?.content?.url,
        nextTabId,
        nextTab?.content?.url,
        snapshotCacheVersion,
        activeReaderTabs,
        previousTabId?.let(readerTabSnapshots::get),
        nextTabId?.let(readerTabSnapshots::get),
    ) {
        when (adjacentDirection) {
            1 -> previousTab?.let { snapshotFor(it.id, it.content.url) }
            -1 -> nextTab?.let { snapshotFor(it.id, it.content.url) }
            else -> null
        }
    }
    val predictiveBackGestureEnabled = selectedTabId != null &&
        selectedContent?.canGoBack == true &&
        !overlayActive &&
        stickyIncomingSnapshot == null &&
        stickyIncomingTabId == null &&
        stickyBackCoverSnapshot == null
    // How far the finger pulls up for a full deck open. A fixed fraction of screen height gives
    // every tab the same, consistent drag speed regardless of which tab is selected (the deck is a
    // single-screen layout, so there is no per-row travel to normalise as the old grid had).
    fun gridTravelPx(): Float {
        if (contentSize.width <= 0 || contentSize.height <= 0) return 1f
        return (contentSize.height.toFloat() * DeckTravelFrac).coerceAtLeast(1f)
    }

    // Derived so the gridProgressState.value read flips a boolean instead of invalidating all of
    // LuminaRoot per animation frame. During open, gridOpen short-circuits before the read; the
    // close/cancel springs had no such guard — the whole tree recomposed at display rate for the
    // entire exit animation. Cheap plain-state clauses first so the animated value is read last.
    val gridEngaged by remember {
        derivedStateOf {
            gridGestureActive ||
                gridOpen ||
                gridWaitingForAfterClose ||
                gridCommitFromProgress > 0f ||
                gridCancelFromProgress > 0f ||
                gridProgressState.value > 0f
        }
    }
    // Real favicons for the tab-overview captions, loaded (memory + disk cached) via BrowserIcons
    // while the overview is engaged. Keyed by URL so a navigation refreshes it; null is cached too
    // to avoid refetch storms, and the UI falls back to a letter tile. Feeds the iconProvider below.
    val faviconCache = remember { mutableStateMapOf<String, android.graphics.Bitmap?>() }
    LaunchedEffect(gridEngaged, browserState.tabs.size) {
        if (!gridEngaged) return@LaunchedEffect
        // Bound the cache to currently-open tabs so it can't grow without limit across a long
        // session of navigations/closes (BrowserIcons keeps its own bounded disk cache underneath).
        val currentUrls = browserState.tabs.mapTo(HashSet()) { it.content.url }
        faviconCache.keys.retainAll(currentUrls)
        browserState.tabs.forEach { tab ->
            val url = tab.content.url
            if (url.isNotBlank() && !url.isBlankPageUrl() && !faviconCache.containsKey(url)) {
                launch {
                    val bmp = runCatching {
                        components.icons.loadIcon(IconRequest(url)).await().bitmap
                    }.getOrNull()
                    faviconCache[url] = bmp
                }
            }
        }
    }
    val tabSwitchingEnabled = !barCollapseCompact &&
        predictiveBackOffsetPx == 0f &&
        stickyBackCoverSnapshot == null &&
        !gridEngaged

    val currentUrl = selectedContent?.url
    val currentHost = remember(currentUrl) { hostFromUrl(currentUrl.orEmpty()) }
    val sitePrefsStore = components.sitePreferencesStore
    val currentSitePrefs: SitePreferences = remember(currentHost, sitePrefsVersion) {
        sitePrefsStore.get(currentHost)
    }
    LaunchedEffect(sitePrefsStore) {
        sitePrefsStore.updates.collect { sitePrefsVersion += 1 }
    }
    // Keep the selected session's desktop-mode UA in sync with the current host's saved preference,
    // so restored tabs and cross-host navigation honor a "Request desktop site" choice. reload=false
    // here (the page is already loading the new host); the aA toggle uses reload=true for intent.
    LaunchedEffect(selectedEngineSession, currentHost, sitePrefsVersion) {
        val session = selectedEngineSession ?: return@LaunchedEffect
        val wantDesktop = currentHost.isNotEmpty() && sitePrefsStore.get(currentHost).desktopMode
        session.toggleDesktopMode(wantDesktop, reload = false)
    }
    val topChromeInsetDp = with(density) { topChromeInsetPx.toDp() }
    fun topPadDpFor(url: String?): androidx.compose.ui.unit.Dp {
        @Suppress("UNUSED_EXPRESSION") sitePrefsVersion
        val host = hostFromUrl(url.orEmpty())
        if (host.isEmpty()) return 0.dp
        return if (sitePrefsStore.get(host).topPaddingFix) topChromeInsetDp else 0.dp
    }
    val currentSession by rememberUpdatedState(selectedEngineSession)
    val callbacks = remember(viewModel, currentUrl) {
        BarCallbacks(
            onUrlTap = viewModel::onUrlTap,
            onEditingTextChange = viewModel::onEditingTextChange,
            onEditingSubmit = {
                val text = viewModel.state.value.editingText.trim()
                if (text.equals("debug:cache", ignoreCase = true) &&
                    components.globalPreferencesStore.debugCacheHtml
                ) {
                    currentSession?.extractCacheHtml()
                    viewModel.onEditingCancel()
                } else {
                    viewModel.onEditingSubmit()
                }
            },
            onEditingCancel = {
                viewModel.onEditingCancel()
                if (currentUrl == "about:blank") {
                    viewModel.dismissBlankTabAndReturn()
                }
            },
        )
    }

    fun expandUrlBar() {
        barCollapseAnimationJob?.cancel()
        barCollapseAnimationJob = null
        barCollapseIdleJob?.cancel()
        barCollapseIdleJob = null
        barCollapsePx = 0f
        barCollapseCompact = false
        barCollapseDragActive = false
        barCollapseDragPx = 0f
        barCollapseProgressTarget = 0f
        barCollapseProgress = 0f
        scope.launch { barCollapseProgressState.snapTo(0f) }
    }

    fun resetPullRefresh() {
        pullRefreshDistancePx = 0f
        pullThresholdHapticFired = false
    }

    fun snapBarCollapseProgress(target: Float) {
        if (abs(barCollapseProgressTarget - target) < 0.003f) return
        barCollapseProgressTarget = target
        barCollapseAnimationJob?.cancel()
        barCollapseProgress = target
    }

    fun animateBarCollapseProgress(target: Float) {
        if (abs(barCollapseProgressTarget - target) < 0.003f) return
        barCollapseProgressTarget = target
        barCollapseAnimationJob?.cancel()
        barCollapseAnimationJob = scope.launch {
            barCollapseProgressState.snapTo(barCollapseProgress)
            barCollapseProgressState.animateTo(
                targetValue = target,
                animationSpec = LuminaMotion.BarCollapse,
            ) {
                barCollapseProgress = value
            }
        }
    }

    fun handlePageScrollDelta(delta: Float) {
        if (!userScrollInteractionActive) return
        // Accumulate scroll into a hysteresis window clamped to [0, snap]. Crossing a threshold
        // springs the pill to the opposite state; within the band we show a small morph "preview"
        // that tracks scroll and flows straight into that spring (see BarCollapsePreviewMax).
        val nextCollapsePx = (barCollapsePx + delta).coerceIn(0f, BarCollapseSnapThresholdPx)
        if (abs(nextCollapsePx - barCollapsePx) < 0.5f) return
        barCollapsePx = nextCollapsePx
        barCollapseIdleJob?.cancel()
        when {
            !barCollapseCompact && nextCollapsePx >= BarCollapseSnapThresholdPx -> {
                barCollapseCompact = true
                animateBarCollapseProgress(1f)
            }
            barCollapseCompact && nextCollapsePx <= BarExpandSnapThresholdPx -> {
                barCollapseCompact = false
                animateBarCollapseProgress(0f)
            }
            // Within the hysteresis band: nudge a small preview morph toward the state a further
            // scroll would flip to, so the pill visibly hints the impending change and the snap
            // continues seamlessly from it. Skip while a commit spring is mid-flight so a committed
            // flip is never yanked backward (that was the old "no animation on swipe up" jump).
            barCollapseAnimationJob?.isActive != true -> {
                val restProgress = if (barCollapseCompact) 1f else 0f
                val target = if (barCollapseCompact) {
                    val approach = ((BarCollapseSnapThresholdPx - barCollapsePx) /
                        (BarCollapseSnapThresholdPx - BarExpandSnapThresholdPx)).coerceIn(0f, 1f)
                    1f - BarCollapsePreviewMax * approach * approach
                } else {
                    val approach = (barCollapsePx / BarCollapseSnapThresholdPx).coerceIn(0f, 1f)
                    BarCollapsePreviewMax * approach * approach
                }
                snapBarCollapseProgress(target)
                // When scrolling stops mid-band, spring the preview back to its resting state.
                barCollapseIdleJob = scope.launch {
                    delay(BarCollapseIdleSettleMs)
                    barCollapsePx = if (barCollapseCompact) BarCollapseSnapThresholdPx else 0f
                    animateBarCollapseProgress(restProgress)
                }
            }
        }
    }

    fun enqueuePageScrollDelta(delta: Float) {
        pendingPageScrollDelta[0] += delta
        if (pageScrollFrameJob != null) return
        pageScrollFrameJob = scope.launch {
            withFrameNanos { }
            val pendingDelta = pendingPageScrollDelta[0]
            pendingPageScrollDelta[0] = 0f
            pageScrollFrameJob = null
            if (pendingDelta != 0f) {
                handlePageScrollDelta(pendingDelta)
            }
        }
    }

    // Reader-mode pill auto-hide. Scrolling the article down past a small slop hides the pill fully;
    // scrolling up reveals it again. A spring keeps it from snapping on tiny jitter.
    fun handleReaderScrollDelta(delta: Float) {
        val target = when {
            delta > ReaderBarHideSlopPx -> 1f
            delta < -ReaderBarHideSlopPx -> 0f
            else -> return
        }
        if (readerBarHidden.targetValue == target) return
        scope.launch {
            readerBarHidden.animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }

    // Always reveal the pill when entering or leaving reader mode (or switching tabs in reader).
    LaunchedEffect(isReaderActiveOnSelectedTab, selectedTabId) {
        readerBarHidden.snapTo(0f)
    }

    val onPrimaryUrlTap: () -> Unit = {
        if (barCollapseCompact || barCollapseProgressState.value > CompactInteractionThreshold) {
            expandUrlBar()
        } else {
            callbacks.onUrlTap()
        }
    }

    fun applyHorizontalDelta(delta: Float) {
        val width = contentSize.width.toFloat()
        if (width <= 0f) return
        if (delta != 0f) {
            if (offsetX == 0f && tabSwitchCurrentSnapshot == null) {
                tabSwitchCurrentSnapshot = currentPageSnapshot
                requestTabSwitchSnapshot()
            }
            switchAnimationJob?.cancel()
            switchAnimationJob = null
            cancelLeavingSnapshot()
            clearStickyCover()
            predictiveBackAnimationJob?.cancel()
            predictiveBackAnimationJob = null
            predictiveBackOffsetPx = 0f
            clearPredictiveBackCover()
        }
        // A drag that grabbed a mid-switch commit spring seeds gridHandoff* with the spring's
        // frozen offsets so a grab-and-lift continues into the grid without a pop. But once the
        // gesture turns clearly horizontal (a fresh tab swipe), those offsets no longer match
        // what's on screen — the horizontal overlay renders relative to 0 — so a later grid
        // engage would snap the cards back to the stale mid-spring position. Drop the handoff
        // before that can happen; the grid then derives live positions from offsetX as usual.
        if (!gridGestureActive && gridHandoffSelectedTabId != null && abs(offsetX) > gridEngageSlopPx) {
            gridHandoffSelectedTabId = null
            gridHandoffLeavingTabId = null
            gridHandoffSelectedOffsetX = 0f
            gridHandoffLeavingOffsetX = 0f
            gridHandoffSelectedTopPadPx = 0
            gridHandoffLeavingTopPadPx = 0
        }
        val pageStride = width + tabSwitchGapPx
        val proposed = (offsetX + delta).coerceIn(-pageStride, pageStride)
        // At the first/last tab there's no page to swipe to. Rather than a dead hard-stop, let the
        // page follow the finger with iOS-style rubber-band resistance (snaps back on release via
        // commitHorizontalRelease). During a grid gesture we allow free movement in both directions
        // so the user can wiggle the card without hitting a wall, so the rubber-band only applies in
        // pure tab-switch mode.
        val atLeftWall = !gridGestureActive && previousTabId == null
        val atRightWall = !gridGestureActive && nextTabId == null && !canCreateTabFromRightEdge
        offsetX = when {
            atLeftWall && (offsetX > 0f || (offsetX == 0f && delta > 0f)) ->
                rubberBand(offsetX, delta, width).coerceAtLeast(0f)
            atRightWall && (offsetX < 0f || (offsetX == 0f && delta < 0f)) ->
                rubberBand(offsetX, delta, width).coerceAtMost(0f)
            else -> proposed
        }
        if (canCreateTabFromRightEdge && nextTabId == null && offsetX < 0f) {
            val threshold = width * 0.35f
            if (abs(offsetX) >= threshold) {
                if (!newTabThresholdHapticFired) {
                    newTabThresholdHapticFired = true
                    // Strong tap the instant a new tab becomes committed — the payoff that caps the
                    // gradually-growing ramp below.
                    luminaHaptics.commitStrong()
                }
            } else {
                // Under the threshold: a vibration that grows and quickens as the edge pulls open,
                // so the commit tap feels earned rather than sudden.
                newTabThresholdHapticFired = false
                newTabRamp.update(abs(offsetX) / threshold)
            }
        } else {
            newTabRamp.reset()
        }
    }

    fun commitHorizontalRelease(velocity: Float, forceDirection: Int? = null) {
        newTabThresholdHapticFired = false
        newTabRamp.reset()
        if (offsetX == 0f && forceDirection == null) {
            resetTabSwitchSnapshot()
            return
        }
        val width = contentSize.width.toFloat()
        val pageStride = width + tabSwitchGapPx
        val current = offsetX
        val pastDistance = abs(current) > SwitchDistanceThresholdPx
        val pastVelocity = abs(velocity) > SwitchVelocityThresholdPxS &&
            sign(velocity) == sign(current) && current != 0f
        val direction = forceDirection ?: when {
            current > 0f && previousTabId != null && (pastDistance || pastVelocity) -> -1
            current < 0f && nextTabId != null && (pastDistance || pastVelocity) -> 1
            else -> 0
        }
        val newTabThreshold = width * 0.35f
        val shouldCreateTab = forceDirection == null && current < 0f &&
            nextTabId == null &&
            canCreateTabFromRightEdge &&
            (abs(current) >= newTabThreshold)
        switchAnimationJob?.cancel()
        switchAnimationJob = null
        if ((direction != 0 || shouldCreateTab) && width > 0f) {
            val side = if (shouldCreateTab) -1f else -direction.toFloat()
            val target = side * pageStride
            val leavingBitmap = tabSwitchCurrentSnapshot ?: snapshotFor(selectedTabId, currentUrl)
            val incomingBitmap: Bitmap?
            val pinnedIncomingTabId: String?
            val isNewTab: Boolean
            if (shouldCreateTab) {
                incomingBitmap = null
                isNewTab = true
                pinnedIncomingTabId = viewModel.onCreateTabFromEdge(selectTab = false)
            } else {
                val incomingTabId = if (direction == -1) {
                    previousTabId
                } else {
                    nextTabId
                }
                incomingBitmap = if (direction == -1) {
                    previousTab?.let { snapshotFor(it.id, it.content.url) }
                } else {
                    nextTab?.let { snapshotFor(it.id, it.content.url) }
                }
                isNewTab = false
                pinnedIncomingTabId = incomingTabId
            }
            val incomingUrl = if (isNewTab) null else {
                if (direction == -1) previousTab?.content?.url else nextTab?.content?.url
            }
            pendingActiveBarState = visualBarStateForUrl(
                url = incomingUrl ?: "about:blank",
                count = if (isNewTab) tabCount + 1 else tabCount,
                blankNewTabPreview = isNewTab,
            )
            visualSelectedTabId = pinnedIncomingTabId
            stagePendingGeckoSelection(
                tabId = pinnedIncomingTabId,
                createdTab = isNewTab,
                editBlankTab = isNewTab,
            )
            val incomingPadDp = topPadDpFor(incomingUrl)
            val leavingPadDp = topPadDpFor(currentUrl)
            dragCommitted = true
            offsetX = 0f
            resetTabSwitchSnapshot()
            leavingSnapshotJob?.cancel()
            commitAnimNewTab = isNewTab
            leavingSnapshotBitmap = leavingBitmap
            leavingSnapshotOffsetX = current
            leavingSnapshotTopPadPx = with(density) { leavingPadDp.toPx() }.toInt()
            leavingSnapshotTabId = selectedTabId
            incomingSnapshotBitmap = incomingBitmap
            incomingSnapshotOffsetX = current - side * pageStride
            incomingSnapshotTopPadPx = with(density) { incomingPadDp.toPx() }.toInt()
            incomingSnapshotTabId = pinnedIncomingTabId
            leavingSnapshotJob = scope.launch {
                animate(
                    initialValue = current,
                    targetValue = target,
                    initialVelocity = velocity,
                    animationSpec = switchSpring(velocity, Spring.StiffnessMediumLow),
                ) { v, _ ->
                    leavingSnapshotOffsetX = v
                    incomingSnapshotOffsetX = v - side * pageStride
                }
                stickyIncomingSnapshot = incomingBitmap
                stickyIncomingTabId = pinnedIncomingTabId
                stickyIncomingTopPadPx = with(density) { incomingPadDp.toPx() }.toInt()
                commitPendingGeckoSelection()
                leavingSnapshotBitmap = null
                leavingSnapshotOffsetX = 0f
                leavingSnapshotTabId = null
                incomingSnapshotBitmap = null
                incomingSnapshotOffsetX = 0f
                incomingSnapshotTabId = null
                commitAnimNewTab = false
                leavingSnapshotJob = null
            }
        } else {
            switchAnimationJob = scope.launch {
                animate(
                    initialValue = current,
                    targetValue = 0f,
                    initialVelocity = velocity,
                    animationSpec = switchSpring(velocity, Spring.StiffnessMedium),
                ) { v, _ -> offsetX = v }
                resetTabSwitchSnapshot()
                switchAnimationJob = null
            }
        }
    }

    fun openTabToRight(
        url: String,
        title: String = "",
        preparedEngineSession: EngineSession? = null,
        editBlankTab: Boolean = false,
        onStarted: () -> Unit = {},
    ): String {
        val width = contentSize.width.toFloat()
        val pageStride = width + tabSwitchGapPx
        if (width <= 0f) {
            val id = viewModel.onOpenNewTab(
                url = url,
                title = title,
                engineSession = preparedEngineSession,
                editBlankTab = editBlankTab,
            )
            onStarted()
            return id
        }
        val leavingBitmap = tabSwitchCurrentSnapshot ?: snapshotFor(selectedTabId, currentUrl)
        val leavingPadDp = topPadDpFor(currentUrl)
        val newTabId = viewModel.onOpenNewTab(
            url = url,
            title = title,
            engineSession = preparedEngineSession,
            editBlankTab = editBlankTab,
            selectTab = false,
        )
        pendingActiveBarState = visualBarStateForUrl(
            url = url,
            count = tabCount + 1,
            blankNewTabPreview = url.isBlank() || url == "about:blank",
        )
        visualSelectedTabId = newTabId
        stagePendingGeckoSelection(
            tabId = newTabId,
            createdTab = true,
            editBlankTab = editBlankTab,
            afterSelect = onStarted,
        )
        switchAnimationJob?.cancel()
        switchAnimationJob = null
        leavingSnapshotJob?.cancel()
        clearStickyCover()
        clearPredictiveBackCover()
        resetTabSwitchSnapshot()
        offsetX = 0f
        dragCommitted = true
        commitAnimNewTab = true
        leavingSnapshotBitmap = leavingBitmap
        leavingSnapshotOffsetX = 0f
        leavingSnapshotTopPadPx = with(density) { leavingPadDp.toPx() }.toInt()
        leavingSnapshotTabId = selectedTabId
        incomingSnapshotBitmap = null
        incomingSnapshotOffsetX = pageStride
        incomingSnapshotTopPadPx = 0
        incomingSnapshotTabId = newTabId
        leavingSnapshotJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = -pageStride,
                initialVelocity = 0f,
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { v, _ ->
                leavingSnapshotOffsetX = v
                incomingSnapshotOffsetX = v + pageStride
            }
            stickyIncomingSnapshot = null
            stickyIncomingTabId = newTabId
            stickyIncomingTopPadPx = 0
            commitPendingGeckoSelection()
            leavingSnapshotBitmap = null
            leavingSnapshotOffsetX = 0f
            leavingSnapshotTabId = null
            incomingSnapshotBitmap = null
            incomingSnapshotOffsetX = 0f
            incomingSnapshotTabId = null
            commitAnimNewTab = false
            leavingSnapshotJob = null
        }
        return newTabId
    }

    fun closeGrid(animateBack: Boolean, afterClose: () -> Unit = {}) {
        gridAnimationJob?.cancel()
        gridGestureActive = false
        gridDragPx = 0f
        gridDragProgress = 0f
        gridOpen = false
        gridCommitFromPositions = null
        gridCommitFromProgress = 0f
        gridCancelFromProgress = 0f
        // Keep gridEngaged true past the end of the animation so the tab cards (at progress=0,
        // fullscreen position, scale=1) cover the live Gecko view during the gap between the
        // animation finishing and the deferred tab selection in afterClose.
        if (animateBack) gridWaitingForAfterClose = true
        gridAnimationJob = scope.launch {
            if (animateBack) {
                // Spring the zoom-out closed so it matches the spring-driven open/settle (and the
                // pill) instead of a mechanical fixed-duration tween. Both axes share the one spec
                // so carousel→columns stays locked to the vertical progress, same as openGrid.
                val anim = LuminaMotion.GridClose
                coroutineScope {
                    launch { gridProgressState.animateTo(0f, animationSpec = anim) }
                    launch { gridXProgressState.animateTo(0f, animationSpec = anim) }
                }
            } else {
                gridProgressState.snapTo(0f)
                gridXProgressState.snapTo(0f)
            }
            gridHandoffSelectedTabId = null
            gridHandoffLeavingTabId = null
            gridHandoffSelectedOffsetX = 0f
            gridHandoffLeavingOffsetX = 0f
            afterClose()
            gridWaitingForAfterClose = false
            gridAnimationJob = null
        }
    }

    fun openGrid(initialVelocityProgressPerSec: Float, fromProgress: Float = 0f) {
        gridAnimationJob?.cancel()
        gridGestureActive = false
        gridOpen = true
        gridDragPx = 0f
        gridWaitingForAfterClose = false
        gridAnimationJob = scope.launch {
            // Seed the spring from the finger's release position so there's no pop.
            gridProgressState.snapTo(fromProgress)
            // x always starts from carousel positions (0) regardless of any interrupted animation.
            gridXProgressState.snapTo(0f)
            // Smooth, lightly-damped deceleration that carries the finger's release velocity —
            // closer to Safari's settle than a stiff snap. Crucially, the X (carousel→columns)
            // spring gets the SAME spec and initial velocity as the vertical progress so the two
            // axes stay locked together; desynced axes are what made the motion feel robotic.
            val launchVelocity = initialVelocityProgressPerSec.coerceAtLeast(0f)
            val s = LuminaMotion.GridOpen
            coroutineScope {
                launch {
                    gridProgressState.animateTo(
                        1f,
                        initialVelocity = launchVelocity,
                        animationSpec = s,
                    )
                }
                launch {
                    gridXProgressState.animateTo(
                        1f,
                        initialVelocity = launchVelocity,
                        animationSpec = s,
                    )
                }
            }
            gridDragProgress = 0f
            gridCommitFromProgress = 0f
            gridCancelFromProgress = 0f
            gridCommitFromPositions = null
            // captureThumbnail() can allocate/upload a full viewport bitmap. Do this only after
            // the overview spring has settled: requesting it at the release boundary competed
            // directly with the first grid frames and was visible as slow bitmap uploads.
            // Purpose stays null; ContentHost still refreshes its URL/tab-keyed cache.
            currentSnapshotRequestPurpose = null
            currentSnapshotRequestId += 1
            gridAnimationJob = null
        }
    }

    fun springGridProgressTo(target: Float, fromProgress: Float = 0f) {
        gridAnimationJob?.cancel()
        gridAnimationJob = scope.launch {
            // Seed spring from drag release position so there's no pop.
            if (fromProgress > 0f) gridProgressState.snapTo(fromProgress)
            val s = LuminaMotion.GridSettle
            coroutineScope {
                launch { gridProgressState.animateTo(target, animationSpec = s) }
                launch { gridXProgressState.animateTo(target, animationSpec = s) }
            }
            gridDragProgress = 0f
            gridAnimationJob = null
        }
    }

    // Single 2D pill gesture: horizontal motion drives the carousel offsetX (existing behavior),
    // vertical-up motion simultaneously drives gridDragPx → gridProgressState. Both run together
    // so the user can blend swipe-to-switch and swipe-up-to-grid without an axis lock.
    val onPillDragStart: () -> Unit = {
        gridAnimationJob?.cancel()
        gridAnimationJob = null
        gridAxisLockedHorizontal = false
        barCollapseDragActive = false
        barCollapseDragPx = 0f
        // If a horizontal commit spring is mid-flight, freeze it and pin both visible tabs as a
        // grid handoff. Their current X offsets seed the grid animation.
        val midSwitch = leavingSnapshotJob != null
        if (midSwitch) {
            gridHandoffLeavingTabId = leavingSnapshotTabId
            gridHandoffLeavingOffsetX = leavingSnapshotOffsetX
            gridHandoffLeavingTopPadPx = leavingSnapshotTopPadPx
            gridHandoffSelectedTabId = incomingSnapshotTabId ?: selectedTabId
            gridHandoffSelectedOffsetX = incomingSnapshotOffsetX
            gridHandoffSelectedTopPadPx = incomingSnapshotTopPadPx
            commitPendingGeckoSelection()
            leavingSnapshotJob?.cancel()
            leavingSnapshotJob = null
            leavingSnapshotBitmap = null
            incomingSnapshotBitmap = null
            commitAnimNewTab = false
            clearStickyCover()
            clearPredictiveBackCover()
            // Re-seed drag progress from the frozen animation value so the grid starts
            // visually where the spring left off, not at 0.
            val currentProgress = gridProgressState.value
            gridDragProgress = currentProgress
            gridDragPx = currentProgress * gridTravelPx()
        } else {
            gridHandoffSelectedTabId = null
            gridHandoffLeavingTabId = null
            // If an open/close animation was interrupted, seed drag progress from it so
            // the card continues from its current visual position without a pop.
            val currentProgress = gridProgressState.value
            if (currentProgress > 0f) {
                gridDragProgress = currentProgress
                gridDragPx = currentProgress * gridTravelPx()
                gridGestureActive = true
            }
            // Existing horizontal-switch snapshot prep (was previously inside onDragStart).
            if (tabSwitchCurrentSnapshot == null) {
                tabSwitchCurrentSnapshot = currentPageSnapshot
                requestTabSwitchSnapshot()
            }
        }
    }

    val onPillDragDelta: (androidx.compose.ui.geometry.Offset) -> Unit = { delta ->
        if (tabSwitchingEnabled || gridGestureActive) {
            applyHorizontalDelta(delta.x)
        }
        if (barCollapseDragActive) {
            val nextCollapseDragPx = (barCollapseDragPx + delta.y).coerceAtLeast(0f)
            barCollapseDragPx = nextCollapseDragPx
            val targetProgress = (barCollapseDragPx / BarCollapseSnapThresholdPx).coerceIn(0f, 1f)
            snapBarCollapseProgress(targetProgress)
        } else if (!gridGestureActive && !gridAxisLockedHorizontal && offsetX == 0f && delta.y > 0f) {
            barCollapseDragActive = true
            barCollapseDragPx = delta.y.coerceAtLeast(0f)
            val targetProgress = (barCollapseDragPx / BarCollapseSnapThresholdPx).coerceIn(0f, 1f)
            snapBarCollapseProgress(targetProgress)
        } else {
            val nextGridPx = (gridDragPx - delta.y).coerceAtLeast(0f)
            if (nextGridPx != gridDragPx && !gridAxisLockedHorizontal) {
                if (!gridGestureActive && nextGridPx <= gridEngageSlopPx) {
                    // Below the engage slop: keep accumulating but don't grab the gesture yet.
                    gridDragPx = nextGridPx
                } else if (!gridGestureActive && abs(offsetX) > gridEngageSlopPx * 2) {
                    // Axis decision, made once per gesture the moment vertical travel first crosses
                    // the slop: if the finger is already well into a horizontal tab swipe, this is a
                    // thumb arcing upward while swiping sideways, not a grid pull. Letting the arc
                    // engage the grid mid-swipe switches rendering to the grid path — measured at
                    // ~90 ms for its first composition frame plus an elevated per-frame cost after —
                    // so lock the gesture horizontal and ignore vertical motion until the finger lifts.
                    gridAxisLockedHorizontal = true
                    gridDragPx = 0f
                } else {
                    if (!gridGestureActive) {
                        gridGestureActive = true
                        // Swallow the slop so the card starts lifting from the finger's current
                        // position instead of popping up by a slop's height on engage. Skip the
                        // swallow when gridDragPx was pre-seeded above the slop (mid-switch grid
                        // handoff), where subtracting would pop the card downward instead.
                        gridDragPx = if (gridDragPx <= gridEngageSlopPx) {
                            (nextGridPx - gridEngageSlopPx).coerceAtLeast(0f)
                        } else {
                            nextGridPx
                        }
                    } else {
                        gridDragPx = nextGridPx
                    }
                    val travelPx = gridTravelPx()
                    gridDragProgress = (gridDragPx / travelPx).coerceIn(0f, 1f)
                    if (gridDragProgress >= 0.5f) {
                        if (!gridThresholdHapticFired) {
                            gridThresholdHapticFired = true
                            // A single soft rumble the moment the grid catches — no gradual build-up on
                            // the way up. Fires once per crossing; re-arms if the user drags back down.
                            luminaHaptics.gridEngage()
                        }
                    } else {
                        gridThresholdHapticFired = false
                    }
                }
            }
        }
    }

    val onPillDragEnd: (androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset) -> Unit = onPillDragEnd@{ _, velocity ->
        if (barCollapseDragActive) {
            val downwardVelocity = velocity.y
            val commit = barCollapseDragPx >= BarCollapseSnapThresholdPx * 0.5f || downwardVelocity >= 800f
            if (commit) {
                barCollapseCompact = true
                animateBarCollapseProgress(1f)
                barCollapsePx = BarCollapseSnapThresholdPx
            } else {
                barCollapseCompact = false
                animateBarCollapseProgress(0f)
                barCollapsePx = 0f
            }
            barCollapseDragActive = false
            barCollapseDragPx = 0f
            return@onPillDragEnd
        }
        val upwardVelocity = -velocity.y
        val travelPx = gridTravelPx()
        // Commit if the card has travelled past 50% of its grid cell travel distance, or if the
        // user flicked upward fast enough. Using 50% of travel (not a fixed px) means the
        // threshold is always halfway between full-screen and the grid cell regardless of screen
        // size or which row the selected tab is in.
        val pastGridDistance = gridDragPx >= travelPx * 0.5f
        val pastGridVelocity = upwardVelocity >= GridCommitVelocityPxS
        val gridWantsCommit = pastGridDistance || pastGridVelocity
        fun captureGridReleasePositions(): List<Triple<Float, Float, Float>>? {
            val containerW = contentSize.width.toFloat()
            val containerH = contentSize.height.toFloat()
            if (containerW <= 0f || containerH <= 0f) return null
            // Must match the finger-tracked drag floor drawn in TabGridOverlay's isGesture branch,
            // or the commit spring would start from a different scale than the drag showed.
            val minScale = DeckDragMinScale
            val releaseScale =
                (1f - deckDragEase(gridDragProgress) * (1f - minScale)).coerceIn(minScale, 1f)
            val captureCarouselShift = if (gridHandoffSelectedTabId != null) {
                gridHandoffSelectedOffsetX + offsetX
            } else {
                offsetX
            }
            val fingerX = containerW / 2f + captureCarouselShift
            val fingerY = containerH - gridDragPx
            val selectedTx = fingerX - containerW * releaseScale / 2f
            val selectedTy = fingerY - containerH * releaseScale
            val stride = (containerW + tabSwitchGapPx) * releaseScale
            val selIdx = browserState.tabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
            return browserState.tabs.mapIndexed { i, _ ->
                Triple(selectedTx + (i - selIdx) * stride, selectedTy, releaseScale)
            }
        }
        if (gridWantsCommit) {
            // Subtle confirmation that the grid has "caught" and is opening (Safari-like). Skip it
            // if the crossing haptic already fired during the drag so a slow drag-past-threshold
            // doesn't double-buzz; a fast velocity flick that never crossed still gets one buzz.
            if (!gridThresholdHapticFired) {
                luminaHaptics.gridEngage()
            }
            gridThresholdHapticFired = false
            // Capture finger-tracking positions BEFORE resetting offsetX.
            // These seed the commit animation so tabs spring from wherever they were
            // under the finger to their grid cells, rather than from carousel positions.
            gridCommitFromPositions = captureGridReleasePositions()
            gridCommitFromProgress = gridDragProgress
            gridCancelFromProgress = 0f
            // Vertical wins: open grid. Snap any horizontal carousel motion back to 0 so the
            // selected tab stays selected — Safari's behavior.
            offsetX = 0f
            resetTabSwitchSnapshot()
            cancelLeavingSnapshot()
            // Seed the spring with velocity normalised to progress-units (velocity / travelPx).
            openGrid(
                initialVelocityProgressPerSec = upwardVelocity / travelPx,
                fromProgress = gridDragProgress,
            )
        } else {
            // Horizontal gesture wins. Wipe grid state immediately — don't spring back.
            // springGridProgressTo would snapTo(gridDragProgress) making gridProgressState > 0,
            // which keeps gridEngaged true during the horizontal commit animation. With
            // gridGestureActive = false, xProgress would be 0 and the grid cells would sit at
            // center while the commit bitmaps animate sideways — two conflicting layers.
            // Snapping to 0 immediately keeps gridEngaged false so only the horizontal commit
            // overlay renders and the bitmaps track the finger cleanly.
            commitHorizontalRelease(velocity.x)
            gridThresholdHapticFired = false
            // Capture the live drag progress before we tear down the gesture state so the
            // spring-back animation starts from where the finger actually was, not 0.
            val cancelFromProgress = gridDragProgress
            val cancelFromPositions = captureGridReleasePositions()
            if (cancelFromProgress > 0f) {
                gridCommitFromPositions = cancelFromPositions
                gridCommitFromProgress = 0f
                gridCancelFromProgress = cancelFromProgress
            }
            gridGestureActive = false
            gridDragPx = 0f
            gridDragProgress = 0f
            gridAnimationJob?.cancel()
            gridAnimationJob = scope.launch {
                if (cancelFromProgress > 0f) {
                    val travelPx = gridTravelPx()
                    val initialVelocityProgressPerSec =
                        if (travelPx > 0f) (-velocity.y / travelPx).coerceAtLeast(0f) else 0f
                    gridProgressState.snapTo(cancelFromProgress)
                    gridXProgressState.snapTo(0f)
                    val s = LuminaMotion.GridSettle
                    gridProgressState.animateTo(
                        0f,
                        initialVelocity = initialVelocityProgressPerSec,
                        animationSpec = s,
                    )
                    gridCommitFromPositions = null
                    gridCancelFromProgress = 0f
                } else {
                    // No grid progress accumulated — clear any stale animation state.
                    gridProgressState.snapTo(0f)
                    gridXProgressState.snapTo(0f)
                    gridCommitFromPositions = null
                    gridCommitFromProgress = 0f
                    gridCancelFromProgress = 0f
                }
                gridAnimationJob = null
            }
        }
    }

    val onPredictiveBackProgress: (Float) -> Unit = onPredictiveBackProgress@{ progressPx ->
        if (!predictiveBackGestureEnabled) return@onPredictiveBackProgress
        val width = contentSize.width.toFloat()
        if (width <= 0f) return@onPredictiveBackProgress
        if (progressPx != 0f) {
            predictiveBackAnimationJob?.cancel()
            predictiveBackAnimationJob = null
            clearStickyCover()
            clearPredictiveBackCover()
            if (gestureCurrentSnapshot == null) {
                gestureCurrentSnapshot = currentPageSnapshot
            }
            if (activePredictiveBackSnapshot == null) {
                activePredictiveBackSnapshot = predictiveBackSnapshot
            }
        }
        predictiveBackOffsetPx = progressPx.coerceIn(0f, width)
    }

    val onPredictiveBackDragEnd: (Float, Float) -> Unit = onPredictiveBackDragEnd@{ _, velocity ->
        val width = contentSize.width.toFloat()
        val current = predictiveBackOffsetPx
        if (width <= 0f || current <= 0f) {
            predictiveBackOffsetPx = 0f
            return@onPredictiveBackDragEnd
        }
        val targetUrl = previousHistoryUrl
        val shouldCommit = (current / width) >= PredictiveBackCommitProgressThreshold ||
            velocity > PredictiveBackVelocityThresholdPxS
        predictiveBackAnimationJob?.cancel()
        predictiveBackAnimationJob = scope.launch {
            if (shouldCommit && selectedTabId != null && targetUrl != null) {
                // Carry the swipe's release velocity into the settle so a fast flick completes fast
                // and a slow drag eases in — the continuity that makes iOS back-swipe feel connected,
                // instead of a fixed-duration tween that ignores how hard you flung. Barely-damped so
                // it lands crisply on the page edge without a visible bounce past it.
                animate(
                    current,
                    width,
                    initialVelocity = velocity,
                    animationSpec = spring(dampingRatio = 0.95f, stiffness = Spring.StiffnessMedium),
                ) { value, _ ->
                    predictiveBackOffsetPx = value
                }
                stickyBackCoverSnapshot = activePredictiveBackSnapshot ?: predictiveBackSnapshot
                stickyBackCoverUrl = targetUrl
                resetPredictiveBackGesture()
                components.sessionUseCases.goBack.invoke(selectedTabId, true)
            } else {
                // Cancel: spring back to rest with the same velocity continuity (critically damped so
                // any residual outward momentum settles without oscillating).
                animate(
                    current,
                    0f,
                    initialVelocity = velocity,
                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
                ) { value, _ ->
                    predictiveBackOffsetPx = value
                }
                resetPredictiveBackGesture()
            }
            predictiveBackAnimationJob = null
        }
    }

    LaunchedEffect(actualSelectedTabId) {
        if (visualSelectedTabId == actualSelectedTabId) {
            visualSelectedTabId = null
        }
        if (dragCommitted) {
            dragCommitted = false
            offsetX = 0f
        }
        if (pendingActiveBarState?.blankNewTabPreview != true) {
            pendingActiveBarState = null
        }
        predictiveBackAnimationJob?.cancel()
        predictiveBackAnimationJob = null
        resetPredictiveBackGesture()
        resetTabSwitchSnapshot()
        clearPredictiveBackCover()
        userScrollInteractionActive = false
        expandUrlBar()
        resetPullRefresh()
    }

    LaunchedEffect(selectedTabId, selectedContent?.url) {
        val tabId = selectedTabId ?: return@LaunchedEffect
        val url = selectedContent?.url
            ?.takeUnless { it.isBlank() || it == "about:blank" }
            ?: return@LaunchedEffect
        val previousUrl = lastObservedUrlByTab[tabId]
        if (previousUrl != null && previousUrl != url) {
            previousObservedUrlByTab[tabId] = previousUrl
        }
        lastObservedUrlByTab[tabId] = url
        // Drop entries for tabs that no longer exist so these per-tab maps can't grow without
        // bound as tabs are opened and closed.
        val liveTabIds = browserState.tabs.mapTo(HashSet()) { it.id }
        lastObservedUrlByTab.keys.retainAll(liveTabIds)
        previousObservedUrlByTab.keys.retainAll(liveTabIds)
    }

    LaunchedEffect(state.mode) {
        if (state.mode == BarMode.Editing && pendingActiveBarState?.blankNewTabPreview == true) {
            pendingActiveBarState = null
        }
        // Focusing the address bar is a strong signal a navigation is imminent — warm the user's top
        // sites so the likely destination is already connected before they finish typing/tapping.
        if (state.mode == BarMode.Editing) {
            components.speculativePreconnector.warmTopSites(components.historyStorage)
        }
    }

    LaunchedEffect(selectedContent?.loading) {
        userScrollInteractionActive = false
        if (selectedContent?.loading == false) {
            resetPullRefresh()
        }
    }

    LaunchedEffect(components.addonController, contentSize, currentUrl, currentPageSnapshot) {
        components.addonController.onNewTabRequested = { url, engineSession, active ->
            if (active) {
                openTabToRight(
                    url = url.ifBlank { "about:blank" },
                    preparedEngineSession = engineSession,
                    editBlankTab = url.isBlank() || url == "about:blank",
                )
            } else {
                viewModel.onOpenNewTab(
                    url = url.ifBlank { "about:blank" },
                    engineSession = engineSession,
                    editBlankTab = false,
                    selectTab = false,
                )
            }
        }
        components.addonController.onActionPopupTabRequested = { title, engineSession ->
            // The popup document is loaded into engineSession by Gecko; open a foreground tab that
            // renders it. editBlankTab stays false so we show the popup, not the URL editor. Record
            // the id so the active-session effect leaves the originating page active for extensions.
            val popupTabId = openTabToRight(
                url = "about:blank",
                title = title,
                preparedEngineSession = engineSession,
                editBlankTab = false,
            )
            popupTabIds.add(popupTabId)
        }
    }

    // Guarantee the selected tab always has a live engine session. mozilla-components suspends and
    // trims background tab sessions to reclaim memory, and restored tabs start with none. When the
    // selected tab's engineSession is null, ContentHost cannot rebind the single EngineView, so the
    // view keeps showing the PREVIOUS tab while the pill shows the newly-selected tab's URL — the
    // desync the user sees, which only a reload (which lazily recreates the session via
    // CreateEngineSessionMiddleware) would otherwise fix. Dispatching CreateEngineSessionAction here
    // forces creation immediately, replicating feature-session's EngineViewPresenter.
    //
    // A SINGLE dispatch is not enough: it can be lost if the middleware is concurrently trimming or
    // closing the session, or if creation completes but the link doesn't land in engineState before
    // the next state emission — leaving the tab session-less with no further trigger to retry. That
    // is the intermittent desync the user hits. So retry until a session actually exists in the live
    // store state (not the composition-captured snapshot, which would never update inside this
    // coroutine). Keyed on the null-ness too, so a mid-session trim of the selected tab re-arms it.
    LaunchedEffect(actualSelectedTabId, selectedEngineSession == null) {
        val tabId = actualSelectedTabId ?: return@LaunchedEffect
        var attempts = 0
        while (attempts < 6) {
            val tab = components.store.state.tabs.firstOrNull { it.id == tabId }
            // Tab gone (closed mid-switch) or session now present — nothing left to do.
            if (tab == null || tab.engineState.engineSession != null) return@LaunchedEffect
            components.store.dispatch(EngineAction.CreateEngineSessionAction(tabId))
            attempts++
            delay(300)
        }
    }

    LaunchedEffect(actualSelectedTabId) {
        // Pre-set to true if the tab has a cached snapshot (previously rendered) or is not loading.
        // Already-loaded sessions won't fire a new paint callback after rebind, so leaving this
        // false would block the page behind the background overlay indefinitely.
        val tab = browserState.tabs.firstOrNull { it.id == actualSelectedTabId }
        val hasSnapshot = actualSelectedTabId != null &&
            snapshotFor(actualSelectedTabId, tab?.content?.url) != null
        geckoHasPainted = hasSnapshot || tab?.content?.loading == false
    }

    LaunchedEffect(actualSelectedTabId, stickyIncomingTabId) {
        if (actualSelectedTabId != null && actualSelectedTabId == stickyIncomingTabId) {
            delay(700)
            if (actualSelectedTabId == stickyIncomingTabId) {
                fadeOutStickyCover()
            }
        } else if (stickyIncomingTabId != null) {
            delay(250)
            if (actualSelectedTabId != stickyIncomingTabId) {
                fadeOutStickyCover()
            }
        }
    }

    LaunchedEffect(stickyBackCoverUrl, selectedContent?.url, selectedContent?.loading) {
        val targetUrl = stickyBackCoverUrl ?: return@LaunchedEffect
        if (selectedContent?.url != targetUrl || selectedContent.loading) return@LaunchedEffect
        delay(PredictiveBackCoverFallbackMs)
        if (
            stickyBackCoverUrl == targetUrl &&
            selectedContent?.url == targetUrl &&
            selectedContent.loading != true
        ) {
            clearPredictiveBackCover()
        }
    }

    LaunchedEffect(stickyBackCoverUrl) {
        val targetUrl = stickyBackCoverUrl ?: return@LaunchedEffect
        delay(PredictiveBackCoverMaxMs)
        if (stickyBackCoverUrl == targetUrl) {
            clearPredictiveBackCover()
        }
    }

    LuminaBrowserTheme {
        BackHandler(enabled = isVideoFullscreen) {
            selectedTabId?.let { components.sessionUseCases.exitFullscreen.invoke(it) }
        }

        PredictiveBackHandler(enabled = predictiveBackGestureEnabled && !isVideoFullscreen) { progress ->
            try {
                gestureCurrentSnapshot = currentPageSnapshot
                activePredictiveBackSnapshot = predictiveBackSnapshot
                currentSnapshotRequestPurpose = SnapshotRequestPurpose.PredictiveBack
                currentSnapshotRequestId += 1
                progress.collect { backEvent ->
                    val width = contentSize.width.toFloat()
                    if (width <= 0f) return@collect
                    predictiveBackAnimationJob?.cancel()
                    predictiveBackAnimationJob = null
                    clearStickyCover()
                    clearPredictiveBackCover()
                    predictiveBackOffsetPx = (backEvent.progress.coerceIn(0f, 1f) * width)
                }

                val width = contentSize.width.toFloat()
                val tabId = selectedTabId
                val targetUrl = previousHistoryUrl
                if (width > 0f && tabId != null) {
                    animate(
                        predictiveBackOffsetPx,
                        width,
                        animationSpec = tween(PredictiveBackDurationMs, easing = EaseOut),
                    ) { value, _ ->
                        predictiveBackOffsetPx = value
                    }
                    stickyBackCoverSnapshot = activePredictiveBackSnapshot
                    stickyBackCoverUrl = targetUrl
                    resetPredictiveBackGesture()
                    components.sessionUseCases.goBack.invoke(tabId, true)
                } else {
                    resetPredictiveBackGesture()
                }
            } catch (_: CancellationException) {
                val current = predictiveBackOffsetPx
                try {
                    if (current > 0f) {
                        animate(
                            current,
                            0f,
                            animationSpec = tween(durationMillis = 180, easing = EaseOut),
                        ) { value, _ ->
                            predictiveBackOffsetPx = value
                        }
                    } else {
                        predictiveBackOffsetPx = 0f
                    }
                } finally {
                    resetPredictiveBackGesture()
                    clearPredictiveBackCover()
                }
            }
        }

        BackHandler(enabled = gridOpen && !isVideoFullscreen) {
            closeGrid(animateBack = true)
        }

        BackHandler(enabled = selectedContent?.canGoBack == true && !predictiveBackGestureEnabled && !gridOpen && !isVideoFullscreen) {
            components.sessionUseCases.goBack.invoke(selectedTabId, true)
        }

        BackHandler(
            enabled = selectedContent?.canGoBack == false &&
                selectedContent.url == "about:blank" && !isVideoFullscreen,
        ) {
            viewModel.dismissBlankTabAndReturn()
        }

        // A tab opened by another tab (window.open / target=_blank) has a live parent. When it
        // can't navigate back any further, Back closes this child tab and returns to its parent
        // instead of exiting the browser.
        val selectedTabParentId =
            browserState.tabs.firstOrNull { it.id == selectedTabId }?.parentId
        val selectedTabHasLiveParent = selectedTabParentId != null &&
            selectedTabParentId != selectedTabId &&
            browserState.tabs.any { it.id == selectedTabParentId }
        BackHandler(
            enabled = !gridOpen &&
                selectedContent?.canGoBack == false &&
                selectedContent?.url != "about:blank" &&
                selectedTabHasLiveParent && !isVideoFullscreen,
        ) {
            viewModel.closeChildTabAndReturnToParent()
        }

        // When reader mode is showing, Back should dismiss the reader overlay first (like closing
        // any overlay) rather than navigating the page. Composed last so it takes priority over the
        // page-navigation and predictive-back handlers above while reader is active.
        BackHandler(enabled = isReaderActiveOnSelectedTab && !gridOpen && !isVideoFullscreen) {
            selectedTabId?.let { readerManager.setReaderActive(it, false) }
        }

        // Back closes the find-in-page bar first (highest priority overlay).
        BackHandler(enabled = findInPageActive && !isVideoFullscreen) {
            selectedEngineSession?.clearFindMatches()
            findInPageActive = false
            findQuery = ""
            findActiveMatch = 0
            findTotalMatches = 0
        }

        // Back closes the address sheet (editing mode) instead of exiting the app — mirrors the
        // Cancel button, including discarding an untouched blank new tab.
        BackHandler(enabled = state.mode == BarMode.Editing && !gridEngaged && !isVideoFullscreen) {
            viewModel.onEditingCancel()
            if (currentUrl == "about:blank") {
                viewModel.dismissBlankTabAndReturn()
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { contentSize = it },
            ) {
                val contentWidth = contentSize.width.toFloat()
                val shouldShowUi = !isVideoFullscreen && !isInPipMode
                // Easing hoisted out of the per-frame path; the predictive-back overlay reads the
                // animating offset inside draw-phase lambdas (graphicsLayer / drawBehind) so the
                // back-swipe doesn't recompose this subtree on every frame.
                val predictiveBackEasing = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
                ContentHost(
                    engine = components.engine,
                    engineSession = selectedEngineSession,
                    topChromeInsetPx = if (isVideoFullscreen) 0 else topChromeInsetPx,
                    bottomChromeInsetPx = if (isVideoFullscreen || (isKeyboardVisible && state.mode != BarMode.Editing)) 0 else bottomChromeInsetPx,
                    // Shrink the EngineView to sit above a web-content keyboard so Gecko keeps the
                    // focused input on-screen. Not applied while editing the URL bar (that keyboard
                    // belongs to the address sheet, and the engine is covered anyway).
                    keyboardInsetPx = if (!isVideoFullscreen && isKeyboardVisible && state.mode != BarMode.Editing) imeInsets.getBottom(density) else 0,
                    tabId = actualSelectedTabId,
                    pageUrl = actualSelectedContent?.url.orEmpty(),
                    snapshotCache = components.tabSnapshotCache,
                    addonController = components.addonController,
                    sitePermissionController = components.sitePermissionController,
                    sitePreferencesStore = components.sitePreferencesStore,
                    onTabPainted = { paintedTabId ->
                        if (paintedTabId == actualSelectedTabId) geckoHasPainted = true
                        if (paintedTabId == actualSelectedTabId && paintedTabId == stickyIncomingTabId) {
                            scope.launch {
                                delay(80)
                                if (paintedTabId == actualSelectedTabId && paintedTabId == stickyIncomingTabId) {
                                    fadeOutStickyCover()
                                }
                            }
                        }
                        if (paintedTabId == actualSelectedTabId && stickyBackCoverUrl != null) {
                            scope.launch {
                                delay(80)
                                if (
                                    paintedTabId == actualSelectedTabId &&
                                    stickyBackCoverUrl != null &&
                                    selectedContent?.url == stickyBackCoverUrl
                                ) {
                                    clearPredictiveBackCover()
                                }
                            }
                        }
                    },
                    onSnapshotCaptured = { capturedTabId, url, rawBitmap ->
                        // Keep the in-memory snapshots as RGB_565 (half the heap of the ARGB_8888
                        // capture; opaque web pages lose nothing). These maps are unbounded across
                        // live tabs, so holding full ARGB here was a big source of GC pressure —
                        // which both stutters the swipe and makes later captureThumbnail() calls
                        // fail under memory pressure, showing blank tab thumbnails on quick swipes.
                        val bitmap = rawBitmap.toResidentSnapshot()
                        recentTabSnapshots[capturedTabId] = bitmap
                        // Drop snapshots for tabs that no longer exist so closed-tab bitmaps aren't
                        // retained in memory indefinitely.
                        val liveTabIds = browserState.tabs.mapTo(HashSet()) { it.id }
                        recentTabSnapshots.keys.retainAll(liveTabIds)
                        if (url.isNotBlank() && url != "about:blank") {
                            recentPageSnapshots[url] = bitmap
                            // Bound the URL-keyed map; the disk cache still backs evicted entries.
                            while (recentPageSnapshots.size > MaxRecentPageSnapshots) {
                                val evict = recentPageSnapshots.keys.firstOrNull { it != url } ?: break
                                recentPageSnapshots.remove(evict)
                            }
                        }
                    },
                    onPageScrollDelta = ::enqueuePageScrollDelta,
                    onPageTouch = {
                        userScrollInteractionActive = true
                    },
                    onTopOverscrollPull = { distance ->
                        pullRefreshDistancePx = distance.coerceIn(0f, pullRefreshMaxDistancePx)
                        // Buzz exactly once when the pull first crosses the refresh threshold, and
                        // re-arm the haptic if the user drags back below it. Below the threshold, a
                        // gently growing rumble tracks the pull so the arm tick feels earned.
                        if (pullRefreshDistancePx >= pullRefreshThresholdPx) {
                            if (!pullThresholdHapticFired) {
                                pullThresholdHapticFired = true
                                luminaHaptics.armThreshold()
                            }
                        } else {
                            pullThresholdHapticFired = false
                            pullRamp.update(pullRefreshDistancePx / pullRefreshThresholdPx)
                        }
                    },
                    onTopOverscrollRelease = { distance ->
                        pullRamp.reset()
                        if (distance >= pullRefreshThresholdPx && selectedContent?.loading != true) {
                            selectedTabId?.let {
                                pullRefreshing = true
                                components.sessionUseCases.reload.invoke(it)
                            }
                        }
                        resetPullRefresh()
                    },
                    onOpenWindowRequest = { request: WindowRequest ->
                        val preparedSession = request.prepare()
                        val requestUrl = request.url.ifBlank { "about:blank" }
                        openTabToRight(
                            url = requestUrl,
                            preparedEngineSession = preparedSession,
                            editBlankTab = requestUrl == "about:blank",
                            onStarted = request::start,
                        )
                    },
                    onFirefoxAuthRedirect = components.firefoxSyncController::handleOAuthRedirect,
                    canStartPredictiveBack = false,
                    onPredictiveBackProgress = onPredictiveBackProgress,
                    onPredictiveBackEnd = onPredictiveBackDragEnd,
                    onPredictiveBackCancel = {
                        predictiveBackAnimationJob?.cancel()
                        predictiveBackAnimationJob = null
                        resetPredictiveBackGesture()
                    },
                    currentSnapshotRequestId = currentSnapshotRequestId,
                    engineRefreshTick = engineRefreshTick,
                    onCurrentSnapshotCaptured = { capturedTabId, bitmap ->
                        when (currentSnapshotRequestPurpose) {
                            SnapshotRequestPurpose.PredictiveBack -> {
                                if (capturedTabId == selectedTabId || capturedTabId == actualSelectedTabId) {
                                    gestureCurrentSnapshot = bitmap
                                }
                            }
                            SnapshotRequestPurpose.TabSwitch -> {
                                // For reader-active tabs the leaving cover comes from the ReaderView
                                // bitmap (already seeded into tabSwitchCurrentSnapshot); don't clobber
                                // it with the hidden page's engine capture.
                                if ((capturedTabId == selectedTabId || capturedTabId == actualSelectedTabId) &&
                                    !activeReaderTabs.contains(capturedTabId)
                                ) {
                                    tabSwitchCurrentSnapshot = bitmap
                                }
                            }
                            null -> Unit
                        }
                    },
                    onFullScreenChange = { enabled ->
                        isVideoFullscreen = enabled
                        onFullScreenChanged(enabled)
                    },
                    modifier = Modifier
                        .graphicsLayer {
                            // Whenever offsetX != 0 outside the grid, the opaque horizontal-switch
                            // overlay fully covers the viewport, so translating the SurfaceView-
                            // backed engine under it only costs a SurfaceControl transaction per
                            // drag frame. Keep it parked at 0 there; the grid path keeps the
                            // translation because its backdrop fades in and can briefly reveal it.
                            translationX = if (gridEngaged) offsetX else 0f
                        },
                    topPaddingFixActive = currentSitePrefs.topPaddingFix,
                    pageZoomFactor = currentSitePrefs.zoomFactor,
                    barCollapseProgress = barCollapseProgressProvider,
                    tabIsLoading = actualSelectedContent?.loading == true,
                )
                if (selectedContent?.url.isBlankPageUrl() && shouldShowUi) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            // Tapping the empty new-tab page opens the address bar to search/type.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onPrimaryUrlTap,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp),
                            )
                            Text(
                                text = "Search or enter an address",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Thin determinate page-load bar under the status bar, driven by Gecko's reported
                // load progress. Shown only while the selected tab is actively loading and the grid
                // isn't engaged. Independent of the bottom-bar gesture system.
                val pageLoadProgress = selectedContent?.progress ?: 0
                val pageIsLoading = selectedContent?.loading == true
                val showLoadBar = pageIsLoading && pageLoadProgress in 1..99 &&
                    !gridEngaged && !findInPageActive && shouldShowUi
                val animatedLoadProgress by animateFloatAsState(
                    targetValue = (pageLoadProgress / 100f).coerceIn(0f, 1f),
                    // Linear between discrete Gecko progress reports so the bar advances at constant
                    // velocity; FastOutSlowIn would re-accelerate on every update and stutter.
                    animationSpec = tween(durationMillis = 150, easing = LinearEasing),
                    label = "pageLoadProgress",
                )
                AnimatedVisibility(
                    visible = showLoadBar,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    LinearProgressIndicator(
                        progress = { animatedLoadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(2.5.dp),
                        trackColor = Color.Transparent,
                    )
                }
                // Find-in-page bar, pinned under the status bar at the top of the content area.
                AnimatedVisibility(
                    visible = findInPageActive && shouldShowUi,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    FindInPageBar(
                        query = findQuery,
                        activeMatch = findActiveMatch,
                        totalMatches = findTotalMatches,
                        onQueryChange = { text ->
                            findQuery = text
                            if (text.isBlank()) {
                                selectedEngineSession?.clearFindMatches()
                                findActiveMatch = 0
                                findTotalMatches = 0
                            } else {
                                selectedEngineSession?.findAll(text)
                            }
                        },
                        onNext = { selectedEngineSession?.findNext(true) },
                        onPrevious = { selectedEngineSession?.findNext(false) },
                        onClose = {
                            selectedEngineSession?.clearFindMatches()
                            findInPageActive = false
                            findQuery = ""
                            findActiveMatch = 0
                            findTotalMatches = 0
                        },
                        modifier = Modifier.statusBarsPadding(),
                    )
                }
                AnimatedVisibility(
                    visible = isReaderActiveOnSelectedTab && selectedTabArticle != null && shouldShowUi,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = offsetX },
                ) {
                    lastReaderArticle.value?.let { article ->
                    val context = LocalContext.current
                    com.firebolt9907.luminabrowser.ui.reader.ReaderView(
                        article = article,
                        theme = readerTheme,
                        fontSizePercent = readerFontSize,
                        fontName = readerFontName,
                        modifier = Modifier.fillMaxSize(),
                        topPadding = with(density) { topChromeInsetPx.toDp() },
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        onScrollDelta = { delta -> handleReaderScrollDelta(delta) },
                        onSnapshot = { bitmap ->
                            selectedTabId?.let { id ->
                                if (readerManager.isReaderActive(id)) readerTabSnapshots[id] = bitmap
                            }
                        },
                        onLinkClick = { href ->
                            readerManager.openReaderLink(context, selectedTabId, selectedEngineSession, href)
                        }
                    )
                    }
                }
                val movingCurrentSnapshot = tabSwitchCurrentSnapshot
                // Computed once; shared by horizontal overlay, commit animation, and grid overlay
                // so the corner radius is always in sync across all three rendering layers.
                val switchCornerRadiusDp = with(density) { (topChromeInsetPx * 0.8f * 2).toDp() }
                // Shared by every snapshot layer (horizontal overlay, commit anim, and the sticky
                // covers) so they all carry the same rounded corners the live viewport mask paints.
                // The sticky covers especially need their own clip: when a tab is selected from the
                // grid, the persistent corner mask is still faded out (grid is mid-close), so an
                // unclipped sticky cover would flash square corners.
                val switchPaneShape = remember(switchCornerRadiusDp) {
                    SquircleShape(switchCornerRadiusDp)
                }
                if (overlayActive && !gridEngaged) {
                    val width = contentSize.width.toFloat()
                    val pageStride = width + tabSwitchGapPx
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                    adjacentSnapshot?.let { bitmap ->
                        val adjacentUrl = if (adjacentDirection > 0) {
                            previousTab?.content?.url
                        } else {
                            nextTab?.content?.url
                        }
                        val adjacentPad = topPadDpFor(adjacentUrl)
                        SnapshotImage(
                            bitmap = bitmap,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = adjacentPad)
                                .graphicsLayer {
                                    val side = if (offsetX > 0f) 1f else -1f
                                    translationX = offsetX - side * pageStride
                                }
                                .clip(switchPaneShape),
                        )
                    }
                    if (showNewTabPane) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = offsetX + pageStride
                                }
                                .clip(switchPaneShape)
                                .background(MaterialTheme.colorScheme.surface),
                        )
                    }
                    val currentPad = topPadDpFor(currentUrl)
                    if (movingCurrentSnapshot == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = currentPad)
                                .graphicsLayer { translationX = offsetX }
                                .clip(switchPaneShape)
                                .background(MaterialTheme.colorScheme.background),
                        )
                    } else {
                        SnapshotImage(
                            bitmap = movingCurrentSnapshot,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = currentPad)
                                .graphicsLayer { translationX = offsetX }
                                .clip(switchPaneShape),
                        )
                    }
                }
                if (predictiveBackActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                    (activePredictiveBackSnapshot ?: predictiveBackSnapshot)?.let { bitmap ->
                        SnapshotImage(
                            bitmap = bitmap,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val reveal = predictiveBackEasing.transform(
                                        if (contentWidth > 0f) {
                                            (predictiveBackOffsetPx / contentWidth).coerceIn(0f, 1f)
                                        } else 0f
                                    )
                                    translationX =
                                        if (contentWidth > 0f) -contentWidth * 0.14f * (1f - reveal) else 0f
                                },
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawBehind {
                                    val reveal = predictiveBackEasing.transform(
                                        if (contentWidth > 0f) {
                                            (predictiveBackOffsetPx / contentWidth).coerceIn(0f, 1f)
                                        } else 0f
                                    )
                                    drawRect(Color.Black, alpha = 0.16f * (1f - reveal))
                                },
                        )
                    }
                    gestureCurrentSnapshot?.let { bitmap ->
                        SnapshotImage(
                            bitmap = bitmap,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = predictiveBackOffsetPx
                                    shadowElevation = 18f * (
                                        if (contentWidth > 0f) {
                                            (predictiveBackOffsetPx / contentWidth).coerceIn(0f, 1f)
                                        } else 0f
                                    )
                                    clip = true
                                },
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = predictiveBackOffsetPx - 10f }
                                .drawBehind {
                                    val p = if (contentWidth > 0f) {
                                        (predictiveBackOffsetPx / contentWidth).coerceIn(0f, 1f)
                                    } else 0f
                                    drawRect(Color.Black, alpha = 0.10f * p)
                                },
                        )
                    }
                }
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
                if (gridEngaged) {
                    val tabsList = browserState.tabs
                    val width = contentSize.width.toFloat()
                    val pageStride = width + tabSwitchGapPx
                    val newSelIdx = if (gridNewTabSelected) {
                        tabsList.size // "+" cell position — real tabs lerp left, "+" cell expands to fullscreen
                    } else {
                        tabsList.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
                    }
                    // Pre-compute host→padPx once per sitePrefsVersion / tabsList change so the
                    // hot-path mapIndexed below does not call sitePrefsStore.get() per tab per frame.
                    val tabPadPxMap = remember(sitePrefsVersion, tabsList) {
                        tabsList.associate { tab ->
                            val host = hostFromUrl(tab.content.url)
                            val padDp = if (host.isNotEmpty() && sitePrefsStore.get(host).topPaddingFix) {
                                topChromeInsetDp
                            } else {
                                0.dp
                            }
                            tab.id to with(density) { padDp.toPx() }.toInt()
                        }
                    }
                    val gridTabs = remember(
                        tabsList,
                        selectedTabId,
                        gridNewTabSelected,
                        sitePrefsVersion,
                        tabSwitchGapPx,
                        contentSize,
                        gridHandoffSelectedTabId,
                        gridHandoffLeavingTabId,
                        gridHandoffSelectedOffsetX,
                        gridHandoffLeavingOffsetX,
                        gridHandoffSelectedTopPadPx,
                        gridHandoffLeavingTopPadPx,
                    ) {
                        // This list is deliberately frame-stable. Drag values belong exclusively
                        // in TabGridOverlay graphicsLayer providers: observing them here caused a
                        // full LuminaRoot recomposition and GridTab allocation on every 120 Hz
                        // pointer sample.
                        tabsList.mapIndexed { i, tab ->
                            val isHandoffLeaving = gridHandoffSelectedTabId != null && tab.id == gridHandoffLeavingTabId
                            val browsePadPx = when {
                                isHandoffLeaving -> gridHandoffLeavingTopPadPx
                                gridHandoffSelectedTabId != null && i == newSelIdx -> gridHandoffSelectedTopPadPx
                                else -> tabPadPxMap[tab.id] ?: 0
                            }
                            GridTab(
                                id = tab.id,
                                url = tab.content.url,
                                title = tab.content.title.ifBlank { hostFromUrl(tab.content.url) },
                                browseBaseX = if (isHandoffLeaving) {
                                    gridHandoffLeavingOffsetX
                                } else {
                                    (i - newSelIdx) * pageStride
                                },
                                browseTopPadPx = browsePadPx,
                                slotDelta = i - newSelIdx,
                                // Deck recency: 0 = newest (front/largest/right), older = further back-left.
                                recencyRank = tabsList.size - 1 - i,
                            )
                        }
                    }
                    // Shared new-tab action: used by both the grid's "+" cell and the header
                    // "New Tab" button so you can create a tab without scrolling to the bottom.
                    val openNewTabFromGrid: () -> Unit = {
                        gridNewTabSelected = true
                        pendingActiveBarState = visualBarStateForUrl(
                            url = "about:blank",
                            count = tabCount + 1,
                            blankNewTabPreview = true,
                        )
                        closeGrid(animateBack = true) {
                            gridNewTabSelected = false
                            val newTabId = viewModel.onCreateTabFromEdge(selectTab = false)
                            stickyIncomingTabId = newTabId
                            stagePendingGeckoSelection(tabId = newTabId, createdTab = true, editBlankTab = true)
                            commitPendingGeckoSelection()
                        }
                    }
                    // Shared gesture-core callbacks, hoisted so the deck/pixel overlay and the
                    // legacy grid overlay (selected by tabViewMode) can both use them.
                    val onGridTabSelected: (String) -> Unit = onTabSelected@{ tabId ->
                        // Switch the selected tab so the closing animation lands on the
                        // tapped cell — its carousel slot then becomes the centered (offset=0)
                        // browse position, which the grid → fullscreen lerp uses as its
                        // target.
                        gridHandoffSelectedTabId = null
                        gridHandoffLeavingTabId = null
                        val tappedTab = browserState.tabs.firstOrNull { it.id == tabId }
                        if (tabId != selectedTabId) {
                            visualSelectedTabId = tabId
                            // Clear the old tab's gesture-local snapshot so the close
                            // animation shows the tapped tab's own cached snapshot.
                            resetTabSwitchSnapshot()
                            tappedTab?.let {
                                pendingActiveBarState = visualBarStateForUrl(
                                    url = it.content.url,
                                    count = tabCount,
                                )
                            }
                            stagePendingGeckoSelection(tabId = tabId)
                        }
                        offsetX = 0f
                        closeGrid(animateBack = true) {
                            if (pendingGeckoSelectTabId == tabId) {
                                stickyIncomingSnapshot = snapshotFor(tabId, tappedTab?.content?.url)
                                stickyIncomingTabId = tabId
                                stickyIncomingTopPadPx = with(density) {
                                    topPadDpFor(tappedTab?.content?.url).toPx()
                                }.toInt()
                            }
                            commitPendingGeckoSelection()
                        }
                    }
                    val gridSnapshotProvider: (String) -> Bitmap? = { tabId ->
                        val tabIndex = tabsList.indexOfFirst { it.id == tabId }
                        val tab = tabsList.getOrNull(tabIndex)
                        if (tab == null || tab.content.url.isBlankPageUrl()) {
                            null
                        } else if (tabIndex == newSelIdx) {
                            tabSwitchCurrentSnapshot ?: snapshotFor(tab.id, tab.content.url)
                        } else {
                            snapshotFor(tab.id, tab.content.url)
                        }
                    }
                    if (tabViewMode == TabViewMode.GRID) {
                        // Legacy 2-column vertical grid (recovered from jul-8). Same gesture-core
                        // params; it renders its own vertical-scroll layout + captions.
                        ClassicTabGridOverlay(
                            progress = { if (gridGestureActive) gridDragProgress else gridProgressState.value },
                            xProgress = { if (gridGestureActive) 0f else gridXProgressState.value },
                            carouselShift = { offsetX },
                            dragOffsetPx = { gridDragPx },
                            containerSize = contentSize,
                            tabs = gridTabs,
                            selectedTabId = selectedTabId,
                            onTabSelected = onGridTabSelected,
                            pageStridePx = pageStride,
                            selectedShiftBase = gridHandoffSelectedOffsetX,
                            onNewTab = { openNewTabFromGrid() },
                            onCloseTab = { tabId -> components.tabRepository.removeTab(tabId) },
                            newTabSelected = gridNewTabSelected,
                            browseCornerDp = switchCornerRadiusDp,
                            isGesture = gridGestureActive,
                            commitFromProgress = if (gridGestureActive) 0f else gridCommitFromProgress,
                            cancelFromProgress = if (gridGestureActive) 0f else gridCancelFromProgress,
                            releasePositions = gridCommitFromPositions,
                            snapshotVersion = snapshotCacheVersionState,
                            snapshotProvider = gridSnapshotProvider,
                            bottomControlsHeightPx = gridBottomControlsHeightPx,
                        )
                    } else {
                        TabGridOverlay(
                            progress = { if (gridGestureActive) gridDragProgress else gridProgressState.value },
                            xProgress = { if (gridGestureActive) 0f else gridXProgressState.value },
                            carouselShift = { offsetX },
                            dragOffsetPx = { gridDragPx },
                            containerSize = contentSize,
                            tabs = gridTabs,
                            selectedTabId = selectedTabId,
                            onTabSelected = onGridTabSelected,
                            pageStridePx = pageStride,
                            selectedShiftBase = gridHandoffSelectedOffsetX,
                            onNewTab = { openNewTabFromGrid() },
                            onCloseTab = { tabId -> components.tabRepository.removeTab(tabId) },
                            newTabSelected = gridNewTabSelected,
                            browseCornerDp = switchCornerRadiusDp,
                            isGesture = gridGestureActive,
                            commitFromProgress = if (gridGestureActive) 0f else gridCommitFromProgress,
                            cancelFromProgress = if (gridGestureActive) 0f else gridCancelFromProgress,
                            releasePositions = gridCommitFromPositions,
                            snapshotVersion = snapshotCacheVersionState,
                            snapshotProvider = gridSnapshotProvider,
                            iconProvider = { tabId ->
                                val tab = tabsList.firstOrNull { it.id == tabId }
                                tab?.content?.url?.let { faviconCache[it] } ?: tab?.content?.icon
                            },
                            tabViewMode = tabViewMode,
                            // Reserve the top cutout band (status bar / hole punch) plus the caption
                            // band that hangs ~66dp above each card, so the card titles/favicons sit
                            // below the hole punch instead of being clipped by it.
                            topInsetPx = topChromeInsetPx.toFloat() +
                                with(density) { 66.dp.toPx() },
                            // Reserve the floating-controls band at the bottom so the deck/pixel
                            // cards ride up clear of the overview toolbar (measured height incl. the
                            // nav-bar inset), like the classic grid's bottom margin.
                            bottomInsetPx = gridBottomControlsHeightPx.toFloat(),
                        )
                    }
                    // Controls never appear while a finger is down. Keeping them out of that
                    // phase prevents their alpha/enable state from observing every drag sample.
                    if (!gridGestureActive) Column(
                        modifier = Modifier
                            .zIndex(2f)
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { gridBottomControlsHeightPx = it.height }
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
                            .graphicsLayer {
                                alpha = ((gridProgressState.value - 0.35f) / 0.35f).coerceIn(0f, 1f)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val handoff = firefoxSyncState.handoffTab
                        AnimatedVisibility(
                            visible = handoff != null,
                            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                        ) {
                            var lastHandoff by remember { mutableStateOf(handoff) }
                            if (handoff != null) lastHandoff = handoff
                            lastHandoff?.let { tab ->
                                HandoffCard(
                                    tab = tab,
                                    onOpen = {
                                        components.firefoxSyncController.dismissHandoff()
                                        viewModel.onOpenNewTab(url = tab.url, title = tab.title, selectTab = true)
                                        closeGrid(animateBack = true)
                                    },
                                    onDismiss = {
                                        components.firefoxSyncController.dismissHandoff()
                                    }
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 3.dp,
                            shadowElevation = 6.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // This is a composition input, but it has only one transition
                                // per open/close animation instead of invalidating the toolbar on
                                // every animation frame.
                                val toolbarEnabled by remember {
                                    derivedStateOf { gridProgressState.value > 0.65f }
                                }
                                FilledTonalIconButton(
                                    onClick = { openNewTabFromGrid() },
                                    enabled = toolbarEnabled,
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "New Tab")
                                }
                                // Synced Tabs is a labeled button, not an anonymous cloud icon —
                                // it's the primary reason to open this hub on a second device, so it
                                // reads clearly instead of hiding behind mystery-meat iconography.
                                // A count badge signals there are actually tabs waiting from other
                                // devices, so it's discoverable rather than a shot in the dark.
                                val syncedTabCount = firefoxSyncState.devices.sumOf { it.tabs.size }
                                BadgedBox(
                                    badge = {
                                        if (syncedTabCount > 0) {
                                            Badge { Text(if (syncedTabCount > 99) "99+" else "$syncedTabCount") }
                                        }
                                    },
                                ) {
                                    FilledTonalButton(
                                        onClick = { showSyncedTabs = true },
                                        enabled = toolbarEnabled,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.CloudSync,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Synced")
                                    }
                                }
                                // Secondary library/settings destinations live in an overflow menu
                                // so the bar stays focused on tab actions (New Tab, layout, sync).
                                Box {
                                    var overflowOpen by remember { mutableStateOf(false) }
                                    FilledTonalIconButton(
                                        onClick = { overflowOpen = true },
                                        enabled = toolbarEnabled,
                                    ) {
                                        Icon(Icons.Default.MoreHoriz, contentDescription = "More")
                                    }
                                    DropdownMenu(
                                        expanded = overflowOpen,
                                        onDismissRequest = { overflowOpen = false },
                                    ) {
                                        // Tab layout is a display preference, not a primary action —
                                        // it lives here as three explicit, checkmarked choices rather
                                        // than a mystery-meat cycle button on the bar.
                                        TabViewMode.values().forEach { mode ->
                                            val (modeLabel, modeIcon) = when (mode) {
                                                TabViewMode.DECK -> "Deck" to Icons.Default.ViewCarousel
                                                TabViewMode.PIXEL -> "Filmstrip" to Icons.Default.ViewColumn
                                                TabViewMode.GRID -> "Grid" to Icons.Default.GridView
                                            }
                                            DropdownMenuItem(
                                                text = { Text(modeLabel) },
                                                leadingIcon = { Icon(modeIcon, contentDescription = null) },
                                                trailingIcon = if (mode == tabViewMode) {
                                                    { Icon(Icons.Default.Check, contentDescription = "Selected") }
                                                } else null,
                                                onClick = {
                                                    overflowOpen = false
                                                    if (mode != tabViewMode) {
                                                        tabViewMode = mode
                                                        components.globalPreferencesStore.tabViewModeName = mode.name
                                                        luminaHaptics.select()
                                                    }
                                                },
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Bookmarks") },
                                            leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                                            onClick = { overflowOpen = false; showBookmarks = true },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("History") },
                                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                            onClick = { overflowOpen = false; showHistory = true },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Downloads") },
                                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                            onClick = { overflowOpen = false; showDownloads = true },
                                        )
                                        // Divide browsing data (above) from app/account items (below).
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Passwords") },
                                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                            onClick = { overflowOpen = false; showPasswords = true },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Settings") },
                                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                            onClick = { overflowOpen = false; showSettings = true },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Arc/Zen-style refresh line: a horizontal primary-coloured line that fills from the
                // centre as you pull (driven by the live distance from PullRefreshContainer's nested
                // scroll), snaps bright + buzzes at the threshold, then pulses while the reload runs.
                if (pullRefreshActive || pullRefreshing) {
                    PullRefreshLine(
                        progress = pullRefreshProgressProvider,
                        refreshing = pullRefreshing,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .offset(y = with(density) { topChromeInsetPx.toDp() }),
                    )
                }
                // Persistent rounded-corner mask: the live GeckoView surface can't be clipped to a
                // rounded shape directly, so we paint the four corner cut-outs with the background
                // colour on top of the content. This keeps the browsing viewport's rounded corners
                // visible at all times (idle, scrolling, pill drag) — it only fades out as the
                // all-tabs grid opens, which draws its own rounded cards.
                if (!isVideoFullscreen && !isInPipMode) run {
                    // Keep progress in the layer block. Reading gridDragProgress here would
                    // invalidate this entire content Box for every pointer sample.
                    val gridOpenFraction = remember {
                        { if (gridGestureActive) gridDragProgress else gridProgressState.value }
                    }
                        val cornerMaskDp = with(density) { (topChromeInsetPx * 1.6f).toDp() }
                        val maskColor = MaterialTheme.colorScheme.background
                        val maskShape = remember(cornerMaskDp) { SquircleShape(cornerMaskDp) }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = (1f - gridOpenFraction()).coerceIn(0f, 1f)
                                }
                                .drawWithCache {
                                    // SquircleShape yields Outline.Rectangle when the radius/size
                                    // resolves to 0 (e.g. a zero-size first draw); only a Generic
                                    // outline has corners to mask.
                                    val inner = (maskShape.createOutline(size, layoutDirection, this)
                                        as? Outline.Generic)?.path
                                    val mask = if (inner != null) {
                                        val full = Path().apply {
                                            addRect(Rect(0f, 0f, size.width, size.height))
                                        }
                                        Path.combine(PathOperation.Difference, full, inner)
                                    } else {
                                        null
                                    }
                                    onDrawBehind { mask?.let { drawPath(it, color = maskColor) } }
                                },
                        )
                }
                AddressSheet(
                    query = state.editingText,
                    pageUrl = state.pageUrl,
                    visible = state.mode == BarMode.Editing && !gridEngaged && !isVideoFullscreen && !isInPipMode,
                    statusBarTopPad = with(density) { topChromeInsetPx.toDp() },
                    bottomInset = with(density) { bottomChromeInsetPx.toDp() },
                    backgroundSnapshot = currentPageSnapshot,
                    historyStorage = components.historyStorage,
                    bookmarksStorage = components.bookmarksStorage,
                    onSubmit = { text ->
                        if (text.trim().equals("debug:cache", ignoreCase = true) &&
                            components.globalPreferencesStore.debugCacheHtml
                        ) {
                            selectedEngineSession?.extractCacheHtml()
                            viewModel.onEditingCancel()
                        } else {
                            viewModel.onSubmitText(text)
                        }
                    },
                    onPreconnect = { components.speculativePreconnector.preconnect(it) },
                    onPreloadPage = { url ->
                        if (com.firebolt9907.luminabrowser.state.isPreloadAllowed(
                                appContext,
                                components.globalPreferencesStore.preloadMode,
                            )
                        ) {
                            components.externalLinkPreloader.preload(url)
                        }
                    },
                    searchSuggestionsEnabled = components.globalPreferencesStore.searchSuggestionsEnabled,
                    searchEngine = components.globalPreferencesStore.searchEngine,
                    onDismiss = {
                        // Mirror the Cancel button: dismissing the sheet on an untouched blank new
                        // tab must also discard that tab, otherwise it lingers in the tab list.
                        viewModel.onEditingCancel()
                        if (currentUrl == "about:blank") {
                            viewModel.dismissBlankTabAndReturn()
                        }
                    },
                )
                if (shouldShowUi) {
                    // Per-frame pill inputs, resolved inside graphicsLayer blocks (draw phase).
                    // Passing these as plain Floats recomposed this entire scope — and every
                    // SafariBar — on each frame of a drag or commit spring, which both dropped
                    // frames and let the pill visibly lag the draw-phase-translated snapshots.
                    // During the commit spring the pills follow the incoming pane's animated
                    // offset; the ghost-pill stride math then keeps the leaving tab's pill glued
                    // to its departing snapshot instead of teleporting to its rest slot.
                    val pillOffsetX = remember {
                        {
                            if (leavingSnapshotJob != null || commitAnimNewTab) {
                                incomingSnapshotOffsetX
                            } else {
                                offsetX
                            }
                        }
                    }
                    val pillGridProgress = remember {
                        { if (gridGestureActive) gridDragProgress else gridProgressState.value }
                    }
                    val pillReaderHideOffsetPx = remember(density) {
                        // Translate the pill fully off the bottom edge as the reader hides it.
                        // Distance is the bar's measured bottom region plus a margin so it
                        // clears the screen entirely.
                        { readerBarHidden.value * (bottomChromeInsetPx + with(density) { 48.dp.toPx() }) }
                    }
                    BottomBarLayer(
                        contentSize = contentSize,
                        tabSwitchGapPx = tabSwitchGapPx,
                        previousTab = previousTab,
                        nextTab = nextTab,
                        tabCount = tabCount,
                        activeState = pendingActiveBarState ?: state,
                        callbacks = callbacks,
                        onPrimaryUrlTap = onPrimaryUrlTap,
                        onDragStart = onPillDragStart,
                        onDragDelta = onPillDragDelta,
                        onDragEnd = onPillDragEnd,
                        onReload = {
                            selectedTabId?.let {
                                components.sessionUseCases.reload.invoke(it)
                            }
                        },
                        onReloadLongPress = {
                            selectedTabId?.let {
                                components.sessionUseCases.goForward.invoke(it, true)
                            }
                        },
                        canGoForward = selectedContent?.canGoForward == true,
                        onAaTap = { aaInitialTab = 0; showAa = true },
                        onAaLongPress = {
                            selectedTabId?.let { tabId ->
                                val currentUrl = selectedContent?.url.orEmpty()
                                val readerHost = hostFromUrl(currentUrl)
                                val turnedOn = readerManager.toggleReader(tabId, currentUrl, selectedEngineSession)
                                components.sitePreferencesStore.setReaderPreferred(readerHost, turnedOn)
                                components.sitePreferencesStore.setReaderAutoDisabled(readerHost, !turnedOn)
                            }
                        },
                        activeOffsetX = pillOffsetX,
                        onBottomInsetMeasured = { bottomChromeInsetPx = it },
                        barCollapseProgress = barCollapseProgressProvider,
                        pillInteractive = !barCollapseCompact && !gridOpen,
                        controlsEnabled = !gridEngaged,
                        gridProgress = pillGridProgress,
                        showNewTabGhost = showNewTabPane,
                        selectedTabId = selectedTabId,
                        eligibleReaderTabs = eligibleReaderTabs,
                        activeReaderTabs = activeReaderTabs,
                        readerTheme = readerTheme,
                        readerHideOffsetPx = pillReaderHideOffsetPx,
                        onSelectPreviousTab = { commitHorizontalRelease(0f, forceDirection = -1) },
                        onSelectNextTab = { commitHorizontalRelease(0f, forceDirection = 1) },
                        showBottomToolbar = showBottomToolbar,
                        onCopyTap = {
                            val currentUrl = selectedContent?.url.orEmpty()
                            if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(currentUrl))
                                android.widget.Toast.makeText(appContext, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onForwardTap = { selectedTabId?.let { components.sessionUseCases.goForward.invoke(it, true) } },
                        onShareTap = {
                            shareUrl(appContext, selectedContent?.url.orEmpty(), selectedContent?.title)
                        },
                        onBookmarksTap = { showBookmarks = true },
                        onTabsTap = { openGrid(initialVelocityProgressPerSec = 0f) },
                    )

                } else if (isVideoFullscreen && !isInPipMode) {
                    com.firebolt9907.luminabrowser.ui.video.VideoFullscreenOverlay(
                        engineSession = selectedEngineSession,
                        onExitFullscreen = {
                            selectedTabId?.let { id ->
                                components.sessionUseCases.exitFullscreen.invoke(id)
                            }
                        },
                        onEnterPip = {
                            onRequestPipMode()
                        }
                    )
                }
            }
        }

        if (showAddons) {
            AddonsSheet(
                controller = components.addonController,
                onDismiss = { showAddons = false },
            )
        }

        if (showSettings) {
            SettingsSheet(
                globalPrefsStore = components.globalPreferencesStore,
                onDismiss = { showSettings = false },
                onSettingChanged = {
                    components.applyEngineSettings()
                    showBottomToolbar = components.globalPreferencesStore.showBottomToolbar
                },
                onClearSitePermissions = {
                    components.sitePermissionController.clearRememberedDecisions()
                },
            )
        }

        if (showSyncedTabs) {
            SyncedTabsSheet(
                controller = components.firefoxSyncController,
                onDismiss = { showSyncedTabs = false },
                onOpenTab = { url ->
                    viewModel.onOpenNewTab(url = url, title = "", selectTab = true)
                    if (gridEngaged) closeGrid(animateBack = true)
                },
                onSignInUrl = { url ->
                    firefoxSignInTabId = viewModel.onOpenNewTab(
                        url = url,
                        title = "Firefox Sign In",
                        selectTab = true,
                    )
                    showSyncedTabs = false
                    if (gridEngaged) closeGrid(animateBack = false)
                },
                currentPageUrl = selectedContent?.url.orEmpty(),
                currentPageTitle = selectedContent?.title.orEmpty(),
            )
        }

        SitePermissionDialog(controller = components.sitePermissionController)

        PromptHandler(engineSession = selectedEngineSession)

        if (showPasswords) {
            PasswordsSheet(
                loginsStorage = components.loginsStorage,
                onDismiss = { showPasswords = false },
            )
        }

        if (showHistory) {
            HistorySheet(
                historyStorage = components.historyStorage,
                onOpenUrl = { url ->
                    viewModel.onOpenNewTab(url = url, title = "", selectTab = true)
                    showHistory = false
                    if (gridEngaged) closeGrid(animateBack = true)
                },
                onDismiss = { showHistory = false },
            )
        }

        if (showBookmarks) {
            BookmarksSheet(
                bookmarksStorage = components.bookmarksStorage,
                currentPageUrl = selectedContent?.url.orEmpty(),
                currentPageTitle = selectedContent?.title.orEmpty(),
                onOpenUrl = { url ->
                    viewModel.onOpenNewTab(url = url, title = "", selectTab = true)
                    showBookmarks = false
                    if (gridEngaged) closeGrid(animateBack = true)
                },
                onDismiss = { showBookmarks = false },
            )
        }

        if (showDownloads) {
            DownloadsSheet(
                onDismiss = { showDownloads = false },
            )
        }

        if (showAa) {
            AaSheet(
                host = currentHost,
                store = sitePrefsStore,
                globalPrefsStore = components.globalPreferencesStore,
                addonController = components.addonController,
                onDismiss = {
                    showAa = false
                    // The appearance sheet's opaque backdrop pauses the GeckoView compositor while
                    // it covers the engine; on dismiss the surface keeps showing a stale frame that
                    // covers the pill until the user scrolls. Bump this to wake the compositor now.
                    engineRefreshTick += 1
                },
                onOpenAddonSettings = { addon ->
                    addon.optionsPageUrl?.let { url ->
                        viewModel.onOpenNewTab(url = url, title = "${addon.name} Settings", selectTab = true)
                        showAa = false
                        if (gridEngaged) closeGrid(animateBack = false)
                    }
                },
                onOpenAddonQuickSettings = { addon ->
                    // Dismiss the sheet first; the popup arrives asynchronously via
                    // onActionPopupTabRequested, which opens a tab backed by the popup session. The
                    // originating page is already the active tab for extensions (setActiveSession),
                    // so the popup binds to it rather than the blank popup tab.
                    showAa = false
                    if (gridEngaged) closeGrid(animateBack = false)
                    components.addonController.openPopup(addon.id)
                },
                onFindInPage = {
                    findQuery = ""
                    findActiveMatch = 0
                    findTotalMatches = 0
                    findInPageActive = true
                },
                pageUrl = currentUrl.orEmpty(),
                pageTitle = selectedContent?.title.orEmpty(),
                initialTab = aaInitialTab,
                tabId = selectedTabId,
                engineSession = selectedEngineSession,
                backgroundSnapshot = currentPageSnapshot,
                statusBarTopPad = with(density) { topChromeInsetPx.toDp() },
                bookmarksStorage = components.bookmarksStorage,
                pageIcon = selectedContent?.icon,
                showBottomToolbar = showBottomToolbar,
            )
        }
    }
}

@Composable
private fun BottomBarLayer(
    contentSize: IntSize,
    tabSwitchGapPx: Float,
    previousTab: TabSessionState?,
    nextTab: TabSessionState?,
    tabCount: Int,
    activeState: BarUiState,
    callbacks: BarCallbacks,
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
    readerTheme: com.firebolt9907.luminabrowser.ui.reader.ReaderTheme = com.firebolt9907.luminabrowser.ui.reader.ReaderTheme.System,
    readerHideOffsetPx: () -> Float = { 0f },
    onSelectPreviousTab: () -> Unit = {},
    onSelectNextTab: () -> Unit = {},
    showBottomToolbar: Boolean = false,
    onCopyTap: () -> Unit = {},
    onForwardTap: () -> Unit = {},
    onShareTap: () -> Unit = {},
    onBookmarksTap: () -> Unit = {},
    onTabsTap: () -> Unit = {},
) {
    val activeReaderActive = selectedTabId != null && activeReaderTabs.contains(selectedTabId)
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isKeyboardVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    val hideForKeyboard = isKeyboardVisible && activeState.mode != BarMode.Editing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .graphicsLayer {
                val baseAlpha = (1f - (gridProgress() * 1.5f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                alpha = if (hideForKeyboard) 0f else baseAlpha
            },
    ) {
        if (!hideForKeyboard) {
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

            if (activeState.mode != BarMode.Editing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(boxHeight)
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            val collapse = barCollapseProgress()
                            val readerHide = readerHideOffsetPx()
                            alpha = (1f - collapse).coerceIn(0f, 1f)
                            translationY = (toolbarHeight.toPx() + bottomPadding.toPx()) * collapse + readerHide
                        }
                        .background(
                            Brush.verticalGradient(
                                *arrayOf(
                                    0.0f to Color.Transparent,
                                    (fadeStartFraction * 0.35f) to scrimColor.copy(alpha = 0.2f),
                                    (fadeStartFraction * 0.6f) to scrimColor.copy(alpha = 0.5f),
                                    (fadeStartFraction * 0.82f) to scrimColor.copy(alpha = 0.8f),
                                    fadeStartFraction to scrimColor,
                                    1.0f to scrimColor
                                )
                            )
                        )
                )
            }

            val width = contentSize.width.toFloat()
        val pageStride = width + tabSwitchGapPx
        val density = LocalDensity.current
        val pillStride = width - with(density) { 32.dp.toPx() }
        // Parallax: the full-width page panes slide by the full `pageStride` (width + inter-tab
        // gap), but the pills are spaced only `pillStride` apart. Scaling the pill
        // translations by pillStride/pageStride makes the tab content move slightly more than the pill
        // while dragging — Safari's depth effect — and still lands both at 0 on commit.
        val pillParallax = if (pageStride > 0f) pillStride / pageStride else 1f
        val pillWidthPx = width - with(density) { 40.dp.toPx() }
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
                state = ghostBarState(previousTab, tabCount, activeState.mode),
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
                showBottomToolbar = showBottomToolbar,
                canGoForward = previousTab.content.canGoForward,
            )
        }
        if (nextTab != null && width > 0f && !activeReaderActive && showNeighborPills) {
            GhostSafariBar(
                state = ghostBarState(nextTab, tabCount, activeState.mode),
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
                showBottomToolbar = showBottomToolbar,
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
                showBottomToolbar = showBottomToolbar,
            )
        }
        val activeEligible = selectedTabId != null && eligibleReaderTabs.contains(selectedTabId)
        val activeActive = selectedTabId != null && activeReaderTabs.contains(selectedTabId)
        SafariBar(
            state = activeState,
            onUrlTap = onPrimaryUrlTap,
            onAaTap = onAaTap,
            onAaLongPress = onAaLongPress,
            onEditingTextChange = callbacks.onEditingTextChange,
            onEditingSubmit = callbacks.onEditingSubmit,
            onEditingCancel = callbacks.onEditingCancel,
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
            showBottomToolbar = showBottomToolbar,
            onCopyTap = onCopyTap,
            onForwardTap = onForwardTap,
            onShareTap = onShareTap,
            onBookmarksTap = onBookmarksTap,
            onTabsTap = onTabsTap,
            // Only the live pill morphs its address text into the editing field; ghosts opt out.
            enableAddressHero = true,
        )
        }
    }
}

/**
 * Arc/Zen-style refresh indicator: a thin horizontal line.
 *  - While pulling: a centre-out fill whose width and brightness track [progress]; at the
 *    threshold (progress == 1) it reaches full brightness (the haptic is fired by the caller).
 *  - While [refreshing]: a bright band sweeps horizontally across a faint full-width track.
 */
@Composable
private fun PullRefreshLine(
    progress: () -> Float,
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
        // recomposing it (or LuminaRoot).
        val progressValue = progress()
        val w = size.width
        val cy = size.height / 2f
        val stroke = size.height
        // Faint full-width track.
        drawLine(
            color = color.copy(alpha = 0.15f),
            start = Offset(0f, cy),
            end = Offset(w, cy),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (refreshing) {
            val band = w * 0.35f
            val start = -band + sweep * (w + band)
            drawLine(
                color = color,
                start = Offset(start.coerceAtLeast(0f), cy),
                end = Offset((start + band).coerceAtMost(w), cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        } else if (progressValue > 0f) {
            val fill = w * progressValue
            val left = (w - fill) / 2f
            drawLine(
                color = color.copy(alpha = lerp(0.45f, 1f, progressValue)),
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
private fun SnapshotImage(bitmap: android.graphics.Bitmap, modifier: Modifier = Modifier) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = img,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * A non-interactive SafariBar used for the tab-switch peek previews — the previous/next neighbor
 * pills and the blank new-tab ghost. All input callbacks are no-ops (the only interaction is
 * [onClick] on the pill itself), and the whole bar fades out with the collapse progress. Was
 * copy-pasted three times before being extracted; keep the ghosts on this one wrapper.
 */
@Composable
private fun BoxScope.GhostSafariBar(
    state: BarUiState,
    barCollapseProgress: () -> Float,
    pillTranslationX: () -> Float,
    pillScale: () -> Float,
    onClick: () -> Unit,
    showBottomToolbar: Boolean,
    collapseProgress: () -> Float = barCollapseProgress,
    isReaderEligible: Boolean = false,
    isReaderActive: Boolean = false,
    readerTheme: com.firebolt9907.luminabrowser.ui.reader.ReaderTheme =
        com.firebolt9907.luminabrowser.ui.reader.ReaderTheme.System,
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

private fun ghostBarState(
    tab: TabSessionState,
    tabCount: Int,
    activeMode: BarMode,
): BarUiState {
    val url = tab.content.url
    val host = if (url.isNotEmpty()) compactHost(url) else ""
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

private class BarCallbacks(
    val onUrlTap: () -> Unit,
    val onEditingTextChange: (String) -> Unit,
    val onEditingSubmit: () -> Unit,
    val onEditingCancel: () -> Unit,
)

private enum class SnapshotRequestPurpose {
    PredictiveBack,
    TabSwitch,
}

private fun predictiveBackTargetUrl(
    history: HistoryState,
    visibleUrl: String?,
    loading: Boolean,
): String? {
    val currentHistoryUrl = history.items.getOrNull(history.currentIndex)
        ?.uri
        ?.takeUnless(::isBlankHistoryUrl)
    if (loading && currentHistoryUrl != null && currentHistoryUrl != visibleUrl) {
        return currentHistoryUrl
    }
    val previousIndex = history.currentIndex - 1
    return history.items.getOrNull(previousIndex)?.uri?.takeUnless(::isBlankHistoryUrl)
}

private fun isBlankHistoryUrl(url: String): Boolean {
    return url.isBlankPageUrl()
}

private fun String?.isBlankPageUrl(): Boolean = isNullOrBlank() || this == "about:blank"

// Horizontal tab-switch commit spring: damping scales with the finger's release velocity (fast
// flicks settle looser for a livelier feel) while stiffness varies by destination — softer when
// committing to the adjacent tab, snappier when snapping back to the current one.
private fun switchSpring(velocity: Float, stiffness: Float) =
    spring<Float>(dampingRatio = dampingForVelocity(velocity), stiffness = stiffness)

/**
 * A memory-light RGB_565 copy for the in-memory snapshot maps — half the heap of the ARGB_8888
 * captureThumbnail output, and opaque web pages lose nothing visually (the disk cache stores 565
 * for the same reason). Falls back to the original if the copy fails (OOM).
 */
private fun Bitmap.toResidentSnapshot(): Bitmap {
    if (config == Bitmap.Config.RGB_565) return this
    return runCatching { copy(Bitmap.Config.RGB_565, false) }.getOrNull() ?: this
}

/** Fraction of a raw drag applied at the very edge of an overscroll; falls to 0 as you pull further. */
private const val EdgeRubberBandTension = 0.55f

/**
 * iOS-style edge rubber-band. When there is no tab to swipe to, the page still follows the finger —
 * but with resistance that grows as you pull further from rest (asymptotically walling off), and it
 * tracks the finger 1:1 on the way back. This replaces a dead hard-stop with Safari's elastic pull,
 * and pairs with the existing spring-back in [commitHorizontalRelease] for the release bounce.
 * [current] and the result stay on the overscrolled side (the caller coerces to be safe).
 */
private fun rubberBand(current: Float, delta: Float, dimension: Float): Float {
    if (dimension <= 0f) return current
    val outward = (current == 0f && delta != 0f) || sign(delta) == sign(current)
    if (!outward) return current + delta // returning toward rest: 1:1 finger tracking
    val pulled = (abs(current) / dimension).coerceIn(0f, 1f)
    return current + delta * (1f - pulled) * EdgeRubberBandTension
}

private fun dampingForVelocity(velocity: Float): Float {
    val v = abs(velocity)
    return when {
        v < 400f -> 1.0f
        v >= 1500f -> 0.82f
        else -> {
            // Linearly ease from critically damped (slow flicks) to a light 0.82 settle at the
            // fast-flick threshold. Target must match the >=1500 branch so damping stays continuous
            // across the boundary — a mismatch there makes the tab-switch spring visibly jump feel.
            val t = (v - 400f) / 1100f
            1.0f + (0.82f - 1.0f) * t
        }
    }
}
