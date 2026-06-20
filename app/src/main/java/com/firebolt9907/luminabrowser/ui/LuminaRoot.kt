package com.firebolt9907.luminabrowser.ui

import android.graphics.Bitmap
import androidx.activity.ExperimentalActivityApi
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import com.firebolt9907.luminabrowser.ui.grid.GridColumns
import com.firebolt9907.luminabrowser.ui.grid.GridGapDp
import com.firebolt9907.luminabrowser.ui.grid.GridSideMarginDp
import com.firebolt9907.luminabrowser.ui.grid.GridTab
import com.firebolt9907.luminabrowser.ui.grid.GridTopMarginDp
import com.firebolt9907.luminabrowser.ui.grid.TabGridOverlay
import com.firebolt9907.luminabrowser.ui.grid.gridCellRect
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape
import com.firebolt9907.luminabrowser.ui.bookmarks.BookmarksSheet
import com.firebolt9907.luminabrowser.ui.downloads.DownloadsSheet
import com.firebolt9907.luminabrowser.ui.history.HistorySheet
import com.firebolt9907.luminabrowser.ui.passwords.PasswordsSheet
import com.firebolt9907.luminabrowser.ui.sync.SyncedTabsSheet
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.window.WindowRequest
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
private const val BarCollapseDistancePx = 360f
private const val BarCollapseSettleDelayMs = 120L
private const val BarCollapseSnapThresholdPx = 180f
private const val BarExpandSnapThresholdPx = 120f
private const val BarCollapsePreviewMaxProgress = 0.28f
private const val CompactInteractionThreshold = 0.5f
private val BarCollapseEasing = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)
private val TabSwitchGap = 64.dp
private val PullRefreshThreshold = 96.dp
private val PullRefreshMaxDistance = 132.dp
private const val GridCommitVelocityPxS = 1000f

@OptIn(ExperimentalActivityApi::class)
@Composable
fun LuminaRoot(viewModel: LuminaViewModel = viewModel()) {
    val components = (LocalContext.current.applicationContext as LuminaApplication).components
    val state by viewModel.state.collectAsStateWithLifecycle()
    val browserState by components.browserState.collectAsStateWithLifecycle()
    val addonsState by components.addonController.state.collectAsStateWithLifecycle()

    val readerManager = remember { com.firebolt9907.luminabrowser.ui.reader.ReaderManager.instance }
    val activeReaderTabs by readerManager.activeReaderTabs.collectAsStateWithLifecycle()
    val eligibleReaderTabs by readerManager.eligibleReaderTabs.collectAsStateWithLifecycle()
    val tabArticles by readerManager.tabArticles.collectAsStateWithLifecycle()
    val readerTheme by readerManager.theme.collectAsStateWithLifecycle()
    val readerFontSize by readerManager.fontSize.collectAsStateWithLifecycle()
    val readerFontName by readerManager.fontName.collectAsStateWithLifecycle()

    // Find-in-page state. The match counts are pushed by the engine session's onFindResult.
    var findInPageActive by remember { mutableStateOf(false) }
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
    val actualSelectedContent = actualSelectedTab?.content
    val selectedIndex = browserState.tabs.indexOfFirst { it.id == selectedTabId }
    val previousTab = browserState.tabs.getOrNull(selectedIndex - 1)
    val nextTab = browserState.tabs.getOrNull(selectedIndex + 1)
    val previousTabId = previousTab?.id
    val nextTabId = nextTab?.id
    val tabCount = browserState.tabs.size
    val canCreateTabFromRightEdge = selectedIndex == tabCount - 1 && selectedIndex >= 0

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
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
    var currentSnapshotRequestPurpose by remember { mutableStateOf<SnapshotRequestPurpose?>(null) }
    var currentSnapshotRequestId by remember { mutableIntStateOf(0) }
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
    var barCollapseProgressTarget by remember { mutableFloatStateOf(0f) }
    var pullRefreshDistancePx by remember { mutableFloatStateOf(0f) }
    // True from the moment a pull crosses the threshold and triggers a reload until that load
    // completes — drives the "pulsing while loading" phase of the refresh line.
    var pullRefreshing by remember { mutableStateOf(false) }
    // One-shot guard so the threshold haptic fires once per crossing, not every drag frame.
    var pullThresholdHapticFired by remember { mutableStateOf(false) }
    val pendingPageScrollDelta = remember { floatArrayOf(0f) }
    val recentTabSnapshots = remember { mutableStateMapOf<String, Bitmap>() }
    val recentPageSnapshots = remember { mutableStateMapOf<String, Bitmap>() }
    val lastObservedUrlByTab = remember { mutableStateMapOf<String, String>() }
    val previousObservedUrlByTab = remember { mutableStateMapOf<String, String>() }
    val barCollapseProgressState = remember { Animatable(0f) }
    val pullRefreshProgress = (pullRefreshDistancePx / pullRefreshThresholdPx).coerceIn(0f, 1f)
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

    fun clearStickyCover() {
        stickyIncomingSnapshot = null
        stickyIncomingTabId = null
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
    val snapshotCacheVersion by cache.version.collectAsStateWithLifecycle()
    fun snapshotFor(tabId: String?, url: String?): Bitmap? {
        val id = tabId ?: return null
        val pageUrl = url.orEmpty()
        return recentTabSnapshots[id]
            ?: pageUrl.takeIf { it.isNotBlank() && it != "about:blank" }?.let(recentPageSnapshots::get)
            ?: cache.get(id, pageUrl)
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
    // Pixels from the screen bottom to the bottom edge of the row-0 grid cell.
    // Always uses index 0 regardless of which tab is selected. Row-1+ cells sit higher on
    // screen, so their bottom-to-screen-edge distance is much shorter (~26 px on a typical
    // phone vs ~500 px for row 0). Using the selected tab's own cell caused tabs 3+ to reach
    // progress = 1.0 after just a few pixels of drag, producing a jarring instant scale-down.
    // Using a fixed row-0 reference gives every tab the same, consistent drag speed.
    fun gridTravelPx(): Float {
        if (contentSize.width <= 0 || contentSize.height <= 0) return 1f
        val sideMargin = with(density) { GridSideMarginDp.toPx() }
        val topMargin = with(density) { GridTopMarginDp.toPx() }
        val gap = with(density) { GridGapDp.toPx() }
        val cell = gridCellRect(
            index = 0,
            containerSize = contentSize,
            columns = GridColumns,
            horizontalGapPx = gap,
            verticalGapPx = gap,
            sideMarginPx = sideMargin,
            topMarginPx = topMargin,
        )
        return (contentSize.height.toFloat() - (cell.top + cell.height)).coerceAtLeast(1f)
    }

    val gridEngaged = gridGestureActive ||
        gridOpen ||
        gridProgressState.value > 0f ||
        gridCommitFromProgress > 0f ||
        gridCancelFromProgress > 0f ||
        gridWaitingForAfterClose
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
    val callbacks = remember(viewModel, currentUrl) {
        BarCallbacks(
            onUrlTap = viewModel::onUrlTap,
            onEditingTextChange = viewModel::onEditingTextChange,
            onEditingSubmit = viewModel::onEditingSubmit,
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
        barCollapseProgressTarget = 0f
        scope.launch { barCollapseProgressState.snapTo(0f) }
    }

    fun resetPullRefresh() {
        pullRefreshDistancePx = 0f
        pullThresholdHapticFired = false
    }

    fun previewCollapseProgress(px: Float): Float {
        val ratio = (px / BarCollapseSnapThresholdPx).coerceIn(0f, 1f)
        return BarCollapsePreviewMaxProgress * BarCollapseEasing.transform(ratio)
    }

    fun snapBarCollapseProgress(target: Float) {
        if (abs(barCollapseProgressTarget - target) < 0.003f) return
        barCollapseProgressTarget = target
        barCollapseAnimationJob?.cancel()
        barCollapseAnimationJob = scope.launch {
            barCollapseProgressState.snapTo(target)
        }
    }

    fun animateBarCollapseProgress(target: Float) {
        if (abs(barCollapseProgressTarget - target) < 0.003f) return
        barCollapseProgressTarget = target
        barCollapseAnimationJob?.cancel()
        barCollapseAnimationJob = scope.launch {
            barCollapseProgressState.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    fun handlePageScrollDelta(delta: Float) {
        val nextCollapsePx = (barCollapsePx + delta).coerceIn(0f, BarCollapseDistancePx)
        if (abs(nextCollapsePx - barCollapsePx) < 0.5f) return
        barCollapsePx = nextCollapsePx
        barCollapseIdleJob?.cancel()
        when {
            !barCollapseCompact && nextCollapsePx >= BarCollapseSnapThresholdPx -> {
                barCollapseCompact = true
                animateBarCollapseProgress(1f)
            }
            barCollapseCompact && nextCollapsePx > BarExpandSnapThresholdPx -> {
                Unit
            }
            barCollapseCompact && nextCollapsePx <= BarExpandSnapThresholdPx -> {
                barCollapseCompact = false
                animateBarCollapseProgress(previewCollapseProgress(nextCollapsePx))
                barCollapseIdleJob = scope.launch {
                    delay(BarCollapseSettleDelayMs)
                    if (!barCollapseCompact && barCollapsePx <= BarExpandSnapThresholdPx) {
                        barCollapsePx = 0f
                        animateBarCollapseProgress(0f)
                    }
                }
            }
            else -> {
                barCollapseCompact = false
                snapBarCollapseProgress(previewCollapseProgress(nextCollapsePx))
                barCollapseIdleJob = scope.launch {
                    delay(BarCollapseSettleDelayMs)
                    if (!barCollapseCompact && barCollapsePx < BarCollapseSnapThresholdPx) {
                        barCollapsePx = 0f
                        animateBarCollapseProgress(0f)
                    }
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
        val pageStride = width + tabSwitchGapPx
        val proposed = (offsetX + delta).coerceIn(-pageStride, pageStride)
        // During grid gesture, allow free movement in both directions so the user can wiggle
        // the card left/right without hitting the first/last-tab wall. The guards only apply
        // in pure tab-switch mode where they prevent overshooting into non-existent tabs.
        offsetX = when {
            !gridGestureActive && proposed > 0f && previousTabId == null -> offsetX.coerceAtMost(0f)
            !gridGestureActive && proposed < 0f && nextTabId == null && !canCreateTabFromRightEdge ->
                offsetX.coerceAtLeast(0f)
            else -> proposed
        }
    }

    fun commitHorizontalRelease(velocity: Float) {
        if (offsetX == 0f) {
            resetTabSwitchSnapshot()
            return
        }
        val width = contentSize.width.toFloat()
        val pageStride = width + tabSwitchGapPx
        val current = offsetX
        val pastDistance = abs(current) > SwitchDistanceThresholdPx
        val pastVelocity = abs(velocity) > SwitchVelocityThresholdPxS &&
            sign(velocity) == sign(current) && current != 0f
        val direction = when {
            current > 0f && previousTabId != null && (pastDistance || pastVelocity) -> -1
            current < 0f && nextTabId != null && (pastDistance || pastVelocity) -> 1
            else -> 0
        }
        val shouldCreateTab = current < 0f &&
            nextTabId == null &&
            canCreateTabFromRightEdge &&
            (pastDistance || pastVelocity)
        switchAnimationJob?.cancel()
        switchAnimationJob = null
        if ((direction != 0 || shouldCreateTab) && width > 0f) {
            val side = if (current > 0f) 1f else -1f
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
            val commitDampingRatio = dampingForVelocity(velocity)
            leavingSnapshotJob = scope.launch {
                animate(
                    initialValue = current,
                    targetValue = target,
                    initialVelocity = velocity,
                    animationSpec = spring(
                        dampingRatio = commitDampingRatio,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
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
                                    animationSpec = spring(
                                        dampingRatio = dampingForVelocity(velocity),
                                        stiffness = Spring.StiffnessMedium,
                                    ),
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
    ) {
        val width = contentSize.width.toFloat()
        val pageStride = width + tabSwitchGapPx
        if (width <= 0f) {
            viewModel.onOpenNewTab(
                url = url,
                title = title,
                engineSession = preparedEngineSession,
                editBlankTab = editBlankTab,
            )
            onStarted()
            return
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
                // Fast ease-out: critically damped (no bounce), decelerates into the target.
                val easeOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
                val anim = tween<Float>(durationMillis = 300, easing = easeOut)
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
            val s = spring<Float>(
                dampingRatio = 0.9f,
                stiffness = 300f,
            )
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
            gridAnimationJob = null
        }
    }

    fun springGridProgressTo(target: Float, fromProgress: Float = 0f) {
        gridAnimationJob?.cancel()
        gridAnimationJob = scope.launch {
            // Seed spring from drag release position so there's no pop.
            if (fromProgress > 0f) gridProgressState.snapTo(fromProgress)
            val s = spring<Float>(
                dampingRatio = 1.0f,
                stiffness = 220f,
            )
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
        // Horizontal: drives carousel. Honors tabSwitchingEnabled so collapse/back covers still
        // suppress horizontal switching, but vertical motion is allowed independently.
        if (tabSwitchingEnabled || gridGestureActive) {
            applyHorizontalDelta(delta.x)
        }
        // Vertical: only upward motion (negative dy) increases grid progress.
        val nextGridPx = (gridDragPx - delta.y).coerceAtLeast(0f)
        if (nextGridPx != gridDragPx) {
            gridDragPx = nextGridPx
            if (nextGridPx > 0f) gridGestureActive = true
            // Map drag distance to progress using the selected tab's grid cell travel distance so
            // that `progress = dragPx / travelPx` keeps the card's bottom literally under the
            // finger — the same geometry Safari uses.
            // Written directly to gridDragProgress (plain state) rather than through a coroutine
            // snapTo so there's zero scheduling overhead on the hot drag path.
            val travelPx = gridTravelPx()
            gridDragProgress = (gridDragPx / travelPx).coerceIn(0f, 1f)
            // Vibrate the moment the drag crosses the commit threshold (the point past which
            // releasing opens the all-tabs grid), and reset if the user drags back below it.
            if (gridDragProgress >= 0.5f) {
                if (!gridThresholdHapticFired) {
                    gridThresholdHapticFired = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            } else {
                gridThresholdHapticFired = false
            }
        }
    }

    val onPillDragEnd: (androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset) -> Unit = { _, velocity ->
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
            val sideMarginPx = with(density) { GridSideMarginDp.toPx() }
            val gapPx = with(density) { GridGapDp.toPx() }
            val cellW = (containerW - 2 * sideMarginPx - gapPx) / 2f
            val minScale = (cellW / containerW).coerceIn(0.3f, 0.6f)
            val releaseScale = (1f - gridDragProgress * (1f - minScale)).coerceIn(minScale, 1f)
            val captureCarouselShift = if (gridHandoffSelectedTabId != null) {
                gridHandoffSelectedOffsetX
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
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                    val s = spring<Float>(
                        dampingRatio = 1.0f,
                        stiffness = Spring.StiffnessMedium,
                    )
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
                animate(current, width, animationSpec = tween(durationMillis = 220)) { value, _ ->
                    predictiveBackOffsetPx = value
                }
                stickyBackCoverSnapshot = activePredictiveBackSnapshot ?: predictiveBackSnapshot
                stickyBackCoverUrl = targetUrl
                resetPredictiveBackGesture()
                components.sessionUseCases.goBack.invoke(selectedTabId, true)
            } else {
                animate(current, 0f, animationSpec = tween(durationMillis = 220)) { value, _ ->
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
    }

    LaunchedEffect(state.mode) {
        if (state.mode == BarMode.Editing && pendingActiveBarState?.blankNewTabPreview == true) {
            pendingActiveBarState = null
        }
    }

    LaunchedEffect(selectedContent?.loading) {
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
                clearStickyCover()
            }
        } else if (stickyIncomingTabId != null) {
            delay(250)
            if (actualSelectedTabId != stickyIncomingTabId) {
                clearStickyCover()
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
        PredictiveBackHandler(enabled = predictiveBackGestureEnabled) { progress ->
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
                        animationSpec = tween(durationMillis = 220),
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
                            animationSpec = tween(durationMillis = 180),
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

        BackHandler(enabled = gridOpen) {
            closeGrid(animateBack = true)
        }

        BackHandler(enabled = selectedContent?.canGoBack == true && !predictiveBackGestureEnabled && !gridOpen) {
            components.sessionUseCases.goBack.invoke(selectedTabId, true)
        }

        BackHandler(
            enabled = selectedContent?.canGoBack == false &&
                selectedContent.url == "about:blank",
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
                selectedTabHasLiveParent,
        ) {
            viewModel.closeChildTabAndReturnToParent()
        }

        // When reader mode is showing, Back should dismiss the reader overlay first (like closing
        // any overlay) rather than navigating the page. Composed last so it takes priority over the
        // page-navigation and predictive-back handlers above while reader is active.
        BackHandler(enabled = isReaderActiveOnSelectedTab && !gridOpen) {
            selectedTabId?.let { readerManager.setReaderActive(it, false) }
        }

        // Back closes the find-in-page bar first (highest priority overlay).
        BackHandler(enabled = findInPageActive) {
            selectedEngineSession?.clearFindMatches()
            findInPageActive = false
            findQuery = ""
            findActiveMatch = 0
            findTotalMatches = 0
        }

        // Back closes the address sheet (editing mode) instead of exiting the app — mirrors the
        // Cancel button, including discarding an untouched blank new tab.
        BackHandler(enabled = state.mode == BarMode.Editing && !gridEngaged) {
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
                // Easing hoisted out of the per-frame path; the predictive-back overlay reads the
                // animating offset inside draw-phase lambdas (graphicsLayer / drawBehind) so the
                // back-swipe doesn't recompose this subtree on every frame.
                val predictiveBackEasing = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
                ContentHost(
                    engine = components.engine,
                    engineSession = selectedEngineSession,
                    topChromeInsetPx = topChromeInsetPx,
                    bottomChromeInsetPx = bottomChromeInsetPx,
                    tabId = actualSelectedTabId,
                    pageUrl = actualSelectedContent?.url.orEmpty(),
                    snapshotCache = components.tabSnapshotCache,
                    addonController = components.addonController,
                    sitePermissionController = components.sitePermissionController,
                    onTabPainted = { paintedTabId ->
                        if (paintedTabId == actualSelectedTabId) geckoHasPainted = true
                        if (paintedTabId == actualSelectedTabId && paintedTabId == stickyIncomingTabId) {
                            scope.launch {
                                delay(80)
                                if (paintedTabId == actualSelectedTabId && paintedTabId == stickyIncomingTabId) {
                                    stickyIncomingSnapshot = null
                                    stickyIncomingTabId = null
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
                    onSnapshotCaptured = { capturedTabId, url, bitmap ->
                        recentTabSnapshots[capturedTabId] = bitmap
                        if (url.isNotBlank() && url != "about:blank") {
                            recentPageSnapshots[url] = bitmap
                        }
                    },
                    onPageScrollDelta = ::enqueuePageScrollDelta,
                    onTopOverscrollPull = { distance ->
                        pullRefreshDistancePx = distance.coerceIn(0f, pullRefreshMaxDistancePx)
                        // Buzz exactly once when the pull first crosses the refresh threshold, and
                        // re-arm the haptic if the user drags back below it.
                        if (pullRefreshDistancePx >= pullRefreshThresholdPx) {
                            if (!pullThresholdHapticFired) {
                                pullThresholdHapticFired = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        } else {
                            pullThresholdHapticFired = false
                        }
                    },
                    onTopOverscrollRelease = { distance ->
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
                    onCurrentSnapshotCaptured = { capturedTabId, bitmap ->
                        when (currentSnapshotRequestPurpose) {
                            SnapshotRequestPurpose.PredictiveBack -> {
                                if (capturedTabId == selectedTabId || capturedTabId == actualSelectedTabId) {
                                    gestureCurrentSnapshot = bitmap
                                }
                            }
                            SnapshotRequestPurpose.TabSwitch -> {
                                if (capturedTabId == selectedTabId || capturedTabId == actualSelectedTabId) {
                                    tabSwitchCurrentSnapshot = bitmap
                                }
                            }
                            null -> Unit
                        }
                    },
                    modifier = Modifier
                        .graphicsLayer { translationX = offsetX },
                    topPaddingFixActive = currentSitePrefs.topPaddingFix,
                    pageZoomFactor = currentSitePrefs.zoomFactor,
                    barCollapseProgressState = barCollapseProgressState,
                )
                if (selectedContent?.url.isBlankPageUrl()) {
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
                    !gridEngaged && !findInPageActive
                val animatedLoadProgress by animateFloatAsState(
                    targetValue = (pageLoadProgress / 100f).coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 150),
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
                    visible = findInPageActive,
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
                    visible = isReaderActiveOnSelectedTab && selectedTabArticle != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = offsetX },
                ) {
                    lastReaderArticle.value?.let { article ->
                    com.firebolt9907.luminabrowser.ui.reader.ReaderView(
                        article = article,
                        theme = readerTheme,
                        fontSizePercent = readerFontSize,
                        fontName = readerFontName,
                        modifier = Modifier.fillMaxSize(),
                        topPadding = with(density) { topChromeInsetPx.toDp() },
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
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
                        val img = remember(bitmap) { bitmap.asImageBitmap() }
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
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
                        val img = remember(movingCurrentSnapshot) { movingCurrentSnapshot.asImageBitmap() }
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = currentPad)
                                .graphicsLayer { translationX = offsetX }
                                .clip(switchPaneShape),
                        )
                    }
                }
                if (predictiveBackOffsetPx > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                    (activePredictiveBackSnapshot ?: predictiveBackSnapshot)?.let { bitmap ->
                        val img = remember(bitmap) { bitmap.asImageBitmap() }
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
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
                        val img = remember(bitmap) { bitmap.asImageBitmap() }
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
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
                    val img = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = pad)
                            .clip(switchPaneShape),
                    )
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
                        val img = remember(bitmap) { bitmap.asImageBitmap() }
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
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
                        val img = remember(bitmap) { bitmap.asImageBitmap() }
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = leavingPad)
                                .graphicsLayer { translationX = leavingSnapshotOffsetX }
                                .clip(switchPaneShape),
                        )
                    }
                }
                stickyBackCoverSnapshot?.let { bitmap ->
                    val img = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
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
                    // During drag: read gridDragProgress directly (no coroutine overhead).
                    // During animations: read from the Animatable spring.
                    val gridProgress = if (gridGestureActive) gridDragProgress else gridProgressState.value
                    // x stays at 0 (carousel positions) while the finger is down; only springs
                    // to grid-column positions after release.
                    val xProgress = if (gridGestureActive) 0f else gridXProgressState.value
                    val tabsList = browserState.tabs
                    val width = contentSize.width.toFloat()
                    val height = contentSize.height.toFloat()
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
                    val gridTabs by remember(
                        tabsList,
                        selectedTabId,
                        gridNewTabSelected,
                        offsetXState,
                        sitePrefsVersion,
                        snapshotCacheVersion,
                        tabSwitchGapPx,
                        contentSize,
                        gridHandoffSelectedTabId,
                        gridHandoffLeavingTabId,
                        gridHandoffSelectedOffsetX,
                        gridHandoffLeavingOffsetX,
                        gridHandoffSelectedTopPadPx,
                        gridHandoffLeavingTopPadPx,
                    ) {
                        derivedStateOf {
                            // Read all per-frame and event-driven state directly here so the lambda
                            // is tracked as a snapshot observer of each. Plain-float / plain-value
                            // captures from outside the lambda are not tracked by derivedStateOf.
                            val liveCommitPositions = gridCommitFromPositions
                            // Plain-float captures computed outside the lambda are not tracked by
                            // derivedStateOf, which caused drag positions to freeze mid-gesture.
                            val isGestureActive = gridGestureActive
                            val liveShift = if (gridHandoffSelectedTabId != null) gridHandoffSelectedOffsetX else offsetX
                            val liveScale: Float
                            val liveSelTx: Float
                            val liveSelTy: Float
                            val liveStride: Float
                            if (isGestureActive && width > 0f && height > 0f) {
                                val sideMarginPx = with(density) { GridSideMarginDp.toPx() }
                                val gapPx = with(density) { GridGapDp.toPx() }
                                val cellW = (width - 2 * sideMarginPx - gapPx) / 2f
                                val minScale = (cellW / width).coerceIn(0.3f, 0.6f)
                                liveScale = (1f - gridDragProgress * (1f - minScale)).coerceIn(minScale, 1f)
                                val fingerX = width / 2f + liveShift
                                val fingerY = height - gridDragPx
                                liveSelTx = fingerX - width * liveScale / 2f
                                liveSelTy = fingerY - height * liveScale
                                liveStride = (width + tabSwitchGapPx) * liveScale
                            } else {
                                liveScale = 1f
                                liveSelTx = 0f
                                liveSelTy = 0f
                                liveStride = pageStride
                            }
                            tabsList.mapIndexed { i, tab ->
                                val isHandoffLeaving = gridHandoffSelectedTabId != null && tab.id == gridHandoffLeavingTabId
                                val browseX = if (isHandoffLeaving) {
                                    gridHandoffLeavingOffsetX
                                } else {
                                    (i - newSelIdx) * pageStride + liveShift
                                }
                                val browsePadPx = when {
                                    isHandoffLeaving -> gridHandoffLeavingTopPadPx
                                    gridHandoffSelectedTabId != null && i == newSelIdx -> gridHandoffSelectedTopPadPx
                                    else -> tabPadPxMap[tab.id] ?: 0
                                }
                                val snapshot = if (tab.content.url.isBlankPageUrl()) {
                                    null
                                } else if (i == newSelIdx) {
                                    // Use the gesture-fresh capture for the live tab when available —
                                    // matches the existing horizontal-switch overlay behavior.
                                    tabSwitchCurrentSnapshot ?: snapshotFor(tab.id, tab.content.url)
                                } else {
                                    snapshotFor(tab.id, tab.content.url)
                                }
                                // dragTx/dragTy/dragScale: used during gesture and commit animation.
                                // During gesture: finger-tracking computed above.
                                // During commit spring: positions captured at release (gridCommitFromPositions).
                                val dragPos = when {
                                    isGestureActive -> Triple(
                                        liveSelTx + (i - newSelIdx) * liveStride,
                                        liveSelTy,
                                        liveScale,
                                    )
                                    liveCommitPositions != null && i < liveCommitPositions.size -> liveCommitPositions[i]
                                    else -> Triple(0f, 0f, 1f)
                                }
                                GridTab(
                                    id = tab.id,
                                    url = tab.content.url,
                                    title = tab.content.title.ifBlank {
                                        hostFromUrl(tab.content.url)
                                    },
                                    snapshot = snapshot,
                                    browseTranslationX = browseX,
                                    browseTopPadPx = browsePadPx,
                                    dragTx = dragPos.first,
                                    dragTy = dragPos.second,
                                    dragScale = dragPos.third,
                                )
                            }
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
                    TabGridOverlay(
                        progress = gridProgress,
                        xProgress = xProgress,
                        browseCornerDp = switchCornerRadiusDp,
                        containerSize = contentSize,
                        tabs = gridTabs,
                        selectedTabId = selectedTabId,
                        isGesture = gridGestureActive,
                        commitFromProgress = if (gridGestureActive) 0f else gridCommitFromProgress,
                        cancelFromProgress = if (gridGestureActive) 0f else gridCancelFromProgress,
                        newTabSelected = gridNewTabSelected,
                        onCloseTab = { tabId ->
                            components.tabRepository.removeTab(tabId)
                        },
                        onNewTab = {
                            openNewTabFromGrid()
                        },
                        onTabSelected = { tabId ->
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
                        },
                    )
                    // Floating action toolbar pinned to the bottom of the all-tabs page. zIndex
                    // lifts it above the (full-screen) bottom-bar layer that's composed after the
                    // grid, so its buttons actually receive taps instead of the invisible pill.
                    Surface(
                        modifier = Modifier
                            .zIndex(2f)
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
                            .graphicsLayer {
                                alpha = ((gridProgress - 0.35f) / 0.35f).coerceIn(0f, 1f)
                            },
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
                        val toolbarEnabled = !gridGestureActive && gridProgress > 0.65f
                        FilledTonalIconButton(
                            onClick = { openNewTabFromGrid() },
                            enabled = toolbarEnabled,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New Tab")
                        }
                        FilledTonalIconButton(
                            onClick = { showSyncedTabs = true },
                            enabled = toolbarEnabled,
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = "Synced Tabs")
                        }
                        FilledTonalIconButton(
                            onClick = { showPasswords = true },
                            enabled = toolbarEnabled,
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "Passwords")
                        }
                        FilledTonalIconButton(
                            onClick = { showHistory = true },
                            enabled = toolbarEnabled,
                        ) {
                            Icon(Icons.Default.History, contentDescription = "History")
                        }
                        FilledTonalIconButton(
                            onClick = { showBookmarks = true },
                            enabled = toolbarEnabled,
                        ) {
                            Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Bookmarks")
                        }
                        FilledTonalIconButton(
                            onClick = { showDownloads = true },
                            enabled = toolbarEnabled,
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Downloads")
                        }
                        FilledTonalIconButton(
                            onClick = { showSettings = true },
                            enabled = toolbarEnabled,
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                    }
                }
                // Arc/Zen-style refresh line: a horizontal primary-coloured line that fills from the
                // centre as you pull (driven by the live distance from PullRefreshContainer's nested
                // scroll), snaps bright + buzzes at the threshold, then pulses while the reload runs.
                if (pullRefreshDistancePx > 0f || pullRefreshing) {
                    PullRefreshLine(
                        progress = pullRefreshProgress,
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
                run {
                    val gridOpenFraction =
                        (if (gridGestureActive) gridDragProgress else gridProgressState.value)
                            .coerceIn(0f, 1f)
                    val maskAlpha = 1f - gridOpenFraction
                    if (maskAlpha > 0.001f) {
                        val cornerMaskDp = with(density) { (topChromeInsetPx * 1.6f).toDp() }
                        val maskColor = MaterialTheme.colorScheme.background
                        val maskShape = remember(cornerMaskDp) { SquircleShape(cornerMaskDp) }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = maskAlpha }
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
                }
                AddressSheet(
                    query = state.editingText,
                    pageUrl = state.pageUrl,
                    visible = state.mode == BarMode.Editing && !gridEngaged,
                    statusBarTopPad = with(density) { topChromeInsetPx.toDp() },
                    bottomInset = with(density) { bottomChromeInsetPx.toDp() },
                    backgroundSnapshot = currentPageSnapshot,
                    historyStorage = components.historyStorage,
                    bookmarksStorage = components.bookmarksStorage,
                    onSubmit = { viewModel.onSubmitText(it) },
                    onDismiss = {
                        // Mirror the Cancel button: dismissing the sheet on an untouched blank new
                        // tab must also discard that tab, otherwise it lingers in the tab list.
                        viewModel.onEditingCancel()
                        if (currentUrl == "about:blank") {
                            viewModel.dismissBlankTabAndReturn()
                        }
                    },
                )
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
                            val readerHost = hostFromUrl(selectedContent?.url.orEmpty())
                            if (activeReaderTabs.contains(tabId)) {
                                readerManager.setReaderActive(tabId, false)
                                components.sitePreferencesStore.setReaderPreferred(readerHost, false)
                                // Remember the opt-out so auto-engage stops for this host.
                                components.sitePreferencesStore.setReaderAutoDisabled(readerHost, true)
                            } else if (eligibleReaderTabs.contains(tabId)) {
                                readerManager.setReaderActive(tabId, true)
                                components.sitePreferencesStore.setReaderPreferred(readerHost, true)
                                components.sitePreferencesStore.setReaderAutoDisabled(readerHost, false)
                            } else {
                                readerManager.requestReaderActivation(tabId)
                                components.sitePreferencesStore.setReaderPreferred(readerHost, true)
                                components.sitePreferencesStore.setReaderAutoDisabled(readerHost, false)
                                selectedEngineSession?.let { session ->
                                    com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor.extractReaderContent(
                                        session, tabId
                                    )
                                }
                            }
                        }
                    },
                    offsetX = offsetXState,
                    activeOffsetX = if (leavingSnapshotJob != null || commitAnimNewTab) {
                        incomingSnapshotOffsetX
                    } else {
                        offsetX
                    },
                    onBottomInsetMeasured = { bottomChromeInsetPx = it },
                    barCollapseProgressState = barCollapseProgressState,
                    pillInteractive = !barCollapseCompact && !gridOpen,
                    controlsEnabled = !gridEngaged,
                    gridProgress = if (gridGestureActive) gridDragProgress else gridProgressState.value,
                    showNewTabGhost = showNewTabPane,
                    selectedTabId = selectedTabId,
                    eligibleReaderTabs = eligibleReaderTabs,
                    activeReaderTabs = activeReaderTabs,
                    readerTheme = readerTheme,
                )
            }
        }

        if (showAddons) {
            AddonsSheet(
                controller = components.addonController,
                onDismiss = { showAddons = false },
                bottomInsetDp = with(density) { bottomChromeInsetPx.toDp() },
            )
        }

        if (showSettings) {
            SettingsSheet(
                globalPrefsStore = components.globalPreferencesStore,
                onDismiss = { showSettings = false },
                onSettingChanged = { components.applyEngineSettings() },
                onClearSitePermissions = {
                    components.sitePermissionController.clearRememberedDecisions()
                },
                bottomInsetDp = with(density) { bottomChromeInsetPx.toDp() },
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
                bottomInsetDp = with(density) { bottomChromeInsetPx.toDp() },
            )
        }

        SitePermissionDialog(controller = components.sitePermissionController)

        if (showPasswords) {
            PasswordsSheet(
                loginsStorage = components.loginsStorage,
                onDismiss = { showPasswords = false },
                bottomInsetDp = with(density) { bottomChromeInsetPx.toDp() },
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
                bottomInsetDp = with(density) { bottomChromeInsetPx.toDp() },
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
                bottomInsetDp = with(density) { bottomChromeInsetPx.toDp() },
            )
        }

        if (showDownloads) {
            DownloadsSheet(
                onDismiss = { showDownloads = false },
                bottomInsetDp = with(density) { bottomChromeInsetPx.toDp() },
            )
        }

        if (showAa) {
            AaSheet(
                host = currentHost,
                store = sitePrefsStore,
                globalPrefsStore = components.globalPreferencesStore,
                addonController = components.addonController,
                onDismiss = { showAa = false },
                onOpenAddonSettings = { addon ->
                    addon.optionsPageUrl?.let { url ->
                        viewModel.onOpenNewTab(url = url, title = "${addon.name} Settings", selectTab = true)
                        showAa = false
                        if (gridEngaged) closeGrid(animateBack = false)
                    }
                },
                onFindInPage = {
                    findQuery = ""
                    findActiveMatch = 0
                    findTotalMatches = 0
                    findInPageActive = true
                },
                pageUrl = currentUrl.orEmpty(),
                pageTitle = selectedContent?.title.orEmpty(),
                bottomInsetDp = with(density) { bottomChromeInsetPx.toDp() },
                initialTab = aaInitialTab,
                tabId = selectedTabId,
                engineSession = selectedEngineSession,
                backgroundSnapshot = currentPageSnapshot,
                statusBarTopPad = with(density) { topChromeInsetPx.toDp() },
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
    offsetX: androidx.compose.runtime.FloatState,
    activeOffsetX: Float,
    onBottomInsetMeasured: (Int) -> Unit,
    barCollapseProgressState: Animatable<Float, AnimationVector1D>,
    pillInteractive: Boolean = true,
    controlsEnabled: Boolean = true,
    gridProgress: Float = 0f,
    showNewTabGhost: Boolean = false,
    selectedTabId: String? = null,
    eligibleReaderTabs: Set<String> = emptySet(),
    activeReaderTabs: Set<String> = emptySet(),
    readerTheme: com.firebolt9907.luminabrowser.ui.reader.ReaderTheme = com.firebolt9907.luminabrowser.ui.reader.ReaderTheme.White,
) {
    val barCollapseProgress = barCollapseProgressState.value
    val pillAlpha = (1f - (gridProgress * 1.5f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .graphicsLayer { alpha = pillAlpha },
    ) {
        val width = contentSize.width.toFloat()
        val pageStride = width + tabSwitchGapPx
        // Parallax: the full-width page panes slide by the full `pageStride` (width + inter-tab
        // gap), but the pills are spaced only `width` apart (flush, no gap). Scaling the pill
        // translations by width/pageStride makes the tab content move slightly more than the pill
        // while dragging — Safari's depth effect — and still lands both at 0 on commit.
        val pillParallax = if (pageStride > 0f) width / pageStride else 1f
        val pillStride = pageStride * pillParallax // == width
        if (previousTab != null && width > 0f) {
            val prevEligible = eligibleReaderTabs.contains(previousTab.id)
            val prevActive = activeReaderTabs.contains(previousTab.id)
            SafariBar(
                state = ghostBarState(previousTab, tabCount, activeState.mode),
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
                collapseProgress = barCollapseProgress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationX = offsetX.floatValue * pillParallax - pillStride },
                isReaderEligible = prevEligible,
                isReaderActive = prevActive,
                readerTheme = readerTheme,
            )
        }
        if (nextTab != null && width > 0f) {
            val nextEligible = eligibleReaderTabs.contains(nextTab.id)
            val nextActive = activeReaderTabs.contains(nextTab.id)
            SafariBar(
                state = ghostBarState(nextTab, tabCount, activeState.mode),
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
                collapseProgress = barCollapseProgress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationX = offsetX.floatValue * pillParallax + pillStride },
                isReaderEligible = nextEligible,
                isReaderActive = nextActive,
                readerTheme = readerTheme,
            )
        }
        if (showNewTabGhost && nextTab == null && width > 0f) {
            SafariBar(
                state = BarUiState(
                    displayUrl = "",
                    tabCount = tabCount,
                    mode = BarMode.Expanded,
                    editingText = "",
                    blankNewTabPreview = true,
                ),
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
                collapseProgress = 0f,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationX = offsetX.floatValue * pillParallax + pillStride },
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
                .graphicsLayer { translationX = activeOffsetX * pillParallax },
            onBottomInsetMeasured = onBottomInsetMeasured,
            collapseProgress = barCollapseProgress,
            isReaderEligible = activeEligible,
            isReaderActive = activeActive,
            readerTheme = readerTheme,
        )
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
    progress: Float,
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
        } else if (progress > 0f) {
            val fill = w * progress
            val left = (w - fill) / 2f
            drawLine(
                color = color.copy(alpha = lerp(0.45f, 1f, progress)),
                start = Offset(left, cy),
                end = Offset(left + fill, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
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

@Composable
private fun SitePermissionDialog(
    controller: com.firebolt9907.luminabrowser.permissions.SitePermissionController,
) {
    val request by controller.contentRequest.collectAsStateWithLifecycle()
    val current = request ?: return
    val host = remember(current.uri) {
        runCatching { android.net.Uri.parse(current.uri).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: current.uri?.takeIf { it.isNotBlank() }
            ?: "This site"
    }
    val what = remember(current) { describeContentPermissions(current.permissions) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { controller.rejectCurrent() },
        icon = {
            androidx.compose.material3.Icon(
                imageVector = permissionIcon(current.permissions),
                contentDescription = null,
            )
        },
        title = { androidx.compose.material3.Text("Allow $what?") },
        text = { androidx.compose.material3.Text("$host wants to use your $what.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { controller.grantCurrent() }) {
                androidx.compose.material3.Text("Allow")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { controller.rejectCurrent() }) {
                androidx.compose.material3.Text("Block")
            }
        },
    )
}

private fun permissionIcon(
    permissions: List<mozilla.components.concept.engine.permission.Permission>,
): androidx.compose.ui.graphics.vector.ImageVector {
    permissions.forEach { perm ->
        when (perm) {
            is mozilla.components.concept.engine.permission.Permission.ContentGeoLocation ->
                return Icons.Filled.LocationOn
            is mozilla.components.concept.engine.permission.Permission.ContentVideoCamera,
            is mozilla.components.concept.engine.permission.Permission.ContentVideoCapture ->
                return Icons.Filled.Videocam
            is mozilla.components.concept.engine.permission.Permission.ContentAudioCapture,
            is mozilla.components.concept.engine.permission.Permission.ContentAudioMicrophone ->
                return Icons.Filled.Mic
            is mozilla.components.concept.engine.permission.Permission.ContentNotification ->
                return Icons.Filled.Notifications
            is mozilla.components.concept.engine.permission.Permission.ContentPersistentStorage ->
                return Icons.Filled.Storage
            is mozilla.components.concept.engine.permission.Permission.ContentAutoPlayAudible,
            is mozilla.components.concept.engine.permission.Permission.ContentAutoPlayInaudible ->
                return Icons.Filled.PlayArrow
            else -> Unit
        }
    }
    return Icons.Filled.Lock
}

private fun describeContentPermissions(
    permissions: List<mozilla.components.concept.engine.permission.Permission>,
): String {
    val labels = permissions.map { perm ->
        when (perm) {
            is mozilla.components.concept.engine.permission.Permission.ContentGeoLocation -> "location"
            is mozilla.components.concept.engine.permission.Permission.ContentVideoCamera,
            is mozilla.components.concept.engine.permission.Permission.ContentVideoCapture -> "camera"
            is mozilla.components.concept.engine.permission.Permission.ContentAudioCapture,
            is mozilla.components.concept.engine.permission.Permission.ContentAudioMicrophone -> "microphone"
            is mozilla.components.concept.engine.permission.Permission.ContentNotification -> "notifications"
            is mozilla.components.concept.engine.permission.Permission.ContentPersistentStorage -> "persistent storage"
            is mozilla.components.concept.engine.permission.Permission.ContentAutoPlayAudible,
            is mozilla.components.concept.engine.permission.Permission.ContentAutoPlayInaudible -> "autoplay media"
            else -> "this device feature"
        }
    }.distinct()
    return if (labels.isEmpty()) "this device feature" else labels.joinToString(" and ")
}

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

private fun dampingForVelocity(velocity: Float): Float {
    val v = abs(velocity)
    return when {
        v < 400f -> 1.0f
        v >= 1500f -> 0.82f
        else -> {
            val t = (v - 400f) / 1100f
            1.0f + (0.88f - 1.0f) * t
        }
    }
}
