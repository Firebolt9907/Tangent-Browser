package com.firebolt9907.luminabrowser.ui

import android.graphics.Bitmap
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.lerp
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.core.view.WindowCompat
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
import com.firebolt9907.luminabrowser.state.addressBarLabel
import com.firebolt9907.luminabrowser.state.hostFromUrl
import com.firebolt9907.luminabrowser.ui.chrome.AddressSheet
import com.firebolt9907.luminabrowser.ui.chrome.FindInPageBar
import com.firebolt9907.luminabrowser.ui.chrome.SafariBar
import com.firebolt9907.luminabrowser.ui.chrome.WideBarLayout
import com.firebolt9907.luminabrowser.sync.FirefoxSyncEvent
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import com.firebolt9907.luminabrowser.ui.content.ContentHost
import com.firebolt9907.luminabrowser.ui.content.LinkPeekCard
import com.firebolt9907.luminabrowser.ui.content.LinkRectProbe
import com.firebolt9907.luminabrowser.ui.content.PeekEngineHolder
import com.firebolt9907.luminabrowser.ui.content.UtilityWebCard
import com.firebolt9907.luminabrowser.ui.content.UtilityWebRequest
import com.firebolt9907.luminabrowser.ui.content.ThemeColorProbe
import com.firebolt9907.luminabrowser.ui.content.loadJavascriptUrl
import com.firebolt9907.luminabrowser.ui.content.linkUrlFromHitResult
import com.firebolt9907.luminabrowser.ui.content.imageSrcFromHitResult
import com.firebolt9907.luminabrowser.ui.grid.DeckTravelFrac
import com.firebolt9907.luminabrowser.ui.grid.deckDragScale
import com.firebolt9907.luminabrowser.ui.grid.GridTab
import mozilla.components.browser.icons.IconRequest
import com.firebolt9907.luminabrowser.ui.grid.ClassicTabGridOverlay
import com.firebolt9907.luminabrowser.ui.grid.TabGridOverlay
import com.firebolt9907.luminabrowser.ui.grid.TabViewMode
import com.firebolt9907.luminabrowser.ui.reader.hydrateAppearanceFromPrefs
import com.firebolt9907.luminabrowser.ui.reader.openReaderLink
import com.firebolt9907.luminabrowser.ui.reader.toggleReader
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape
import com.firebolt9907.luminabrowser.ui.sync.HandoffCard
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
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

@OptIn(ExperimentalActivityApi::class)
@Composable
fun LuminaRoot(
    viewModel: LuminaViewModel = viewModel(),
    isInPipMode: Boolean = false,
    onFullScreenChanged: (Boolean) -> Unit = {},
    onRequestPipMode: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val activityContext = LocalContext.current
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
    // When on, a search-results page shows the typed query in the bar instead of the engine's host.
    // Persisted; toggled in Settings. Paired with the current search engine for label resolution.
    var showSearchTermsInBar by remember { mutableStateOf(components.globalPreferencesStore.showSearchTermsInBar) }
    var barSearchEngine by remember { mutableStateOf(components.globalPreferencesStore.searchEngine) }
    // How the bottom bar arranges itself on wide screens (tablets / landscape); persisted, picked in
    // Settings. Ignored on narrow/portrait phones (classic full-width bar).
    var wideBarLayout by remember {
        mutableStateOf(WideBarLayout.fromName(components.globalPreferencesStore.wideBarLayoutName))
    }
    // Wide = tablets (>=600dp width, even in portrait) OR any phone in landscape. LocalConfiguration
    // recomposes on rotation; MainActivity handles the config change in-place, so the EngineView is
    // never recreated. Drives the side-by-side / corner bottom-bar layouts and the depth-stack swipe.
    val configuration = LocalConfiguration.current
    val isWideLayout = configuration.screenWidthDp >= 600 ||
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
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

    // Link "glance" (Safari/Arc-style): a long-press on a link populates the tab's hitResult; we
    // capture the target URL, consume the hitResult (so the same link can be pressed again), and
    // show a LinkPeekCard. Cleared on dismiss / action.
    var peekUrl by remember { mutableStateOf<String?>(null) }
    // Long-press on a bare (non-linked) image opens a context sheet (save / copy / share / open in
    // new tab) instead of the link glance — Fenix's standard image context actions.
    var imageMenuUrl by remember { mutableStateOf<String?>(null) }
    // The finger-down position of the most recent press anywhere in the content region, used as the
    // hero-transition origin so the glance card grows out of the link the user held.
    var lastPressPos by remember { mutableStateOf(Offset.Zero) }
    var peekOrigin by remember { mutableStateOf(Offset.Zero) }
    // The measured on-screen rectangle of the pressed link (root px), resolved asynchronously via
    // LinkRectProbe's prompt() bridge. When present, the card morphs out of this exact element;
    // when null it falls back to a chip at [peekOrigin].
    var peekSourceRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // A full-view capture of the main GeckoView taken at long-press; cropped to [peekSourceRect] so
    // the card can morph out of the *actual pixels* of the pressed element, not a flat color. The
    // full ~10 MB capture is dropped the instant it's cropped (see the crop effect below) — retaining
    // it, plus the peek's second content process, is exactly the native-memory pressure that triggers
    // the GC hitches, so we keep only the small cropped region.
    var peekCaptureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var peekElementBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // The single reusable peek session/view (Option A): one bounded extra content process, reused via
    // loadUrl across peeks instead of created/closed each time (that churn fragments native memory).
    val peekEngine = remember { PeekEngineHolder(components.engine) }
    var peekSession by remember { mutableStateOf<EngineSession?>(null) }
    var peekView by remember { mutableStateOf<EngineView?>(null) }
    // When the device signals memory pressure we drop the live preview entirely (static glance only)
    // and release the held session. Requested from the ComponentCallbacks below; the release runs
    // once the card is gone so we never pull the view out from under a live AndroidView.
    var peekReleaseRequested by remember { mutableStateOf(false) }
    // Add-on quick settings / add-on options page / Firefox sign-in, rendered in a standalone
    // GeckoView card ([UtilityWebCard]) that floats over the current page instead of opening a real
    // tab — so the user never leaves their page and the current tab stays active for web extensions.
    // Deliberately independent of the peek engine's low-memory drop below: a sign-in or options page
    // must survive a transient onTrimMemory.
    var utilityWeb by remember { mutableStateOf<UtilityWebRequest?>(null) }
    val activityManager = remember {
        appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    }
    fun deviceLowOnMemory(): Boolean {
        val mi = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mi)
        // `lowMemory` is the system's own flag; the headroom check trips a bit earlier so we back off
        // before the OS starts killing/GC-thrashing.
        return mi.lowMemory || mi.availMem < mi.threshold * 2
    }
    DisposableEffect(peekEngine) {
        val callbacks = object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    peekUrl = null
                    peekReleaseRequested = true
                }
            }
            override fun onLowMemory() {
                peekUrl = null
                peekReleaseRequested = true
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        }
        appContext.registerComponentCallbacks(callbacks)
        onDispose {
            appContext.unregisterComponentCallbacks(callbacks)
            peekEngine.release()
        }
    }
    // Release the held peek session once any open glance is torn down (deferred so the AndroidView
    // has detached the view first).
    LaunchedEffect(peekReleaseRequested, peekUrl) {
        if (peekReleaseRequested && peekUrl == null) {
            peekSession = null
            peekView = null
            peekEngine.release()
            peekReleaseRequested = false
        }
    }
    // A pending "open the peeked page" request: (url, openInForeground). Set when the user taps Open /
    // New tab on the glance; the peek's already-loaded EngineSession is adopted into a real tab so the
    // page is not reloaded. Deferred until the glance card is gone (peekUrl == null) for the same
    // reason as peekReleaseRequested — the peek view must not be released under a live AndroidView.
    var pendingPeekAdoption by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(pendingPeekAdoption, peekUrl) {
        val (url, foreground) = pendingPeekAdoption ?: return@LaunchedEffect
        if (peekUrl != null) return@LaunchedEffect
        val adopted = peekEngine.detachSessionForAdoption()
        peekSession = null
        peekView = null
        if (adopted != null) {
            // Reuse the live, fully-loaded peek session — no network reload. createTab(engineSession=)
            // links with skipLoading=true and seeds correct initial content state from the url.
            components.tabRepository.addPreparedTab(
                url = url,
                engineSession = adopted,
                selectTab = foreground,
            )
        } else {
            // No live preview existed (low-memory static glance): fall back to a normal fresh load.
            viewModel.onOpenNewTab(url = url, title = "", selectTab = foreground)
        }
        pendingPeekAdoption = null
    }

    // Mirror WebExtensionSupport: tell the add-on controller which tab is "current" so it can attach
    // per-tab action handlers (the source of every action update) and mark it active for extensions.
    // Add-on popups now render in a standalone UtilityWebCard rather than a real tab, so no tab is
    // ever a popup tab — the current tab always stays the active extension tab.
    LaunchedEffect(actualSelectedTabId, selectedEngineSession) {
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
    // Transient "Send to device" prompt shown above the pill after copying a link; null = hidden.
    var sendLinkPrompt by remember { mutableStateOf<SendLinkPrompt?>(null) }
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

    // Link "glance" long-press → capture pixels + rect, then show the morphing LinkPeekCard. Placed
    // here (not with the peek state vars above) because it drives the shared snapshot-request state
    // declared just above.
    LaunchedEffect(selectedContent?.hitResult) {
        val link = linkUrlFromHitResult(selectedContent?.hitResult)
        if (link != null) {
            val origin = lastPressPos
            peekOrigin = origin
            peekSourceRect = null
            peekCaptureBitmap = null
            peekElementBitmap = null
            // Option A + low-memory guard: reuse one held session when memory is healthy; under
            // pressure, skip the live preview (static glance) and free the held session now — no peek
            // card is mounted at long-press time, so releasing the view here can't race an AndroidView.
            if (deviceLowOnMemory()) {
                peekSession = null
                peekView = null
                peekEngine.release()
            } else {
                val (s, v) = peekEngine.ensure(activityContext, selectedContent?.private == true)
                peekSession = s
                peekView = v
            }
            peekUrl = link
            luminaHaptics.longPress()
            selectedTabId?.let { components.store.dispatch(ContentAction.ConsumeHitResultAction(it)) }
            // Grab the current on-screen pixels of the page so we can crop the element region.
            currentSnapshotRequestPurpose = SnapshotRequestPurpose.LinkPeek
            currentSnapshotRequestId += 1
            // Ask the page for the pressed link's rectangle; the delegate routes the reply here.
            // The bridge fires on Gecko's thread, but snapshot-state writes are thread-safe.
            LinkRectProbe.pending = { rect -> peekSourceRect = rect }
            selectedEngineSession?.loadJavascriptUrl(
                LinkRectProbe.buildScript(link, origin.x, origin.y)
            )
        } else {
            // Not a navigable link — if the press landed on a bare image, surface the image context
            // sheet (save / copy / share / open). Consume the hit result so the same image can be
            // long-pressed again after the sheet is dismissed.
            val image = imageSrcFromHitResult(selectedContent?.hitResult)
            if (image != null) {
                imageMenuUrl = image
                luminaHaptics.longPress()
                selectedTabId?.let { components.store.dispatch(ContentAction.ConsumeHitResultAction(it)) }
            }
        }
    }
    // Crop the full capture to the element rect once both arrive, then immediately release the full
    // ~10 MB bitmap so it doesn't sit in the heap (native-memory pressure = the GC hitches). Runs off
    // the main thread's critical path; only the small cropped region is retained for the morph.
    LaunchedEffect(peekCaptureBitmap, peekSourceRect, contentSize) {
        val full = peekCaptureBitmap
        val rect = peekSourceRect
        if (full != null && rect != null && contentSize.width > 0 && contentSize.height > 0) {
            peekElementBitmap = cropBitmapToRect(
                full, rect, contentSize.width.toFloat(), contentSize.height.toFloat()
            )
            peekCaptureBitmap = null // drop the full-screen capture reference for prompt GC reclaim
        }
    }
    // Release the cropped bitmap when the glance is fully gone, so nothing lingers between peeks.
    LaunchedEffect(peekUrl) {
        if (peekUrl == null) {
            peekElementBitmap = null
            peekCaptureBitmap = null
        }
    }
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
    var showOnboarding by remember { mutableStateOf(false) }
    var showAa by remember { mutableStateOf(false) }
    var aaInitialTab by remember { mutableIntStateOf(0) }
    // Whether the selected tab is on the tracking-protection exception list (drives the aA sheet's
    // per-site "Block trackers" toggle). Queried asynchronously each time the sheet opens.
    var siteTrackingProtectionDisabled by remember { mutableStateOf(false) }
    LaunchedEffect(showAa, actualSelectedTabId) {
        if (showAa) {
            actualSelectedTabId?.let { id ->
                components.trackingProtectionUseCases.containsException(id) { excepted ->
                    siteTrackingProtectionDisabled = excepted
                }
            }
        }
    }
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
                    components.firefoxSyncController.verifyAccount()
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
                    // The sign-in ran in a UtilityWebCard which dismisses itself on the success
                    // redirect (via onIntercept → handleOAuthRedirect); reopen the synced-tabs sheet
                    // to show the now-signed-in state — but NOT while onboarding is up, where the user
                    // stays on the onboarding page to finish the remaining steps.
                    utilityWeb = null
                    if (!showOnboarding) showSyncedTabs = true
                }
                FirefoxSyncEvent.LoggedOutRemotely -> {
                    android.widget.Toast.makeText(
                        appContext,
                        "Firefox Sync was signed out on another device",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
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
            addressBarLabel(url, barSearchEngine, showSearchTermsInBar)
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
    // Per-host theme colors, sampled by ThemeColorProbe (the same `<meta name="theme-color">` tag
    // Safari reads) whenever a top-padding site is showing. Keyed by host so the tint stays stable
    // across pages of the same site — matching how the top-padding preference itself is scoped. A
    // missing entry means the site declared no theme color, so consumers fall back to the primary.
    val themeColorsByHost = remember { mutableStateMapOf<String, Color>() }
    DisposableEffect(Unit) {
        ThemeColorProbe.listener = { _, url, colorInt ->
            val host = hostFromUrl(url)
            if (host.isNotEmpty()) {
                // The probe fires on Gecko's thread; marshal the state write back to main.
                scope.launch {
                    if (colorInt != null) themeColorsByHost[host] = Color(colorInt)
                    else themeColorsByHost.remove(host)
                }
            }
        }
        onDispose { ThemeColorProbe.listener = null }
    }
    // The color to paint the exposed top strip / status bar for the current tab: the site's declared
    // theme color when top-padding-fix is on and one was found, otherwise the system primary color.
    val primaryColor = MaterialTheme.colorScheme.primary
    val topStripColorActive = currentSitePrefs.topPaddingFix && currentHost.isNotEmpty()
    val topStripColor = if (topStripColorActive) {
        themeColorsByHost[currentHost] ?: primaryColor
    } else {
        primaryColor
    }
    // Keep the status-bar icons legible over whatever fills the strip: dark icons on a light tint,
    // light icons on a dark one. When the strip is not active, restore the system-theme default.
    val systemDarkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    val statusBarController = remember(view) {
        var ctx: android.content.Context? = view.context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
            ctx = ctx.baseContext
        }
        (ctx as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
    }
    LaunchedEffect(statusBarController, topStripColorActive, topStripColor, systemDarkTheme) {
        val controller = statusBarController ?: return@LaunchedEffect
        controller.isAppearanceLightStatusBars = if (topStripColorActive) {
            topStripColor.luminance() > 0.5f
        } else {
            !systemDarkTheme
        }
    }
    // Resolves a tab's theme-color strip fill for the tab switcher: the site's sampled theme color,
    // or null when none was declared (the card falls back to the primary color).
    val gridThemeColorProvider: (String) -> Color? = remember {
        { tabId ->
            val url = components.store.state.tabs.firstOrNull { it.id == tabId }?.content?.url
            val host = hostFromUrl(url.orEmpty())
            if (host.isEmpty()) null else themeColorsByHost[host]
        }
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
            // Must match the finger-tracked drag scale drawn in TabGridOverlay's isGesture branch,
            // or the commit spring would start from a different scale than the drag showed.
            val releaseScale =
                deckDragScale(gridDragProgress, gridDragPx, containerH * DeckTravelFrac)
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
            // Seed the spring with velocity normalised to progress-units (velocity / travelPx), but
            // cap it against the range still left to open so a far/fast swipe still plays the settle
            // morph instead of snapping. fromProgress stays at the real release point, so the pill /
            // backdrop / corner-mask that read gridProgressState directly don't pop. See
            // GridOpenMaxVelocityPerRange.
            val remainingRange = (1f - gridDragProgress).coerceAtLeast(0.0001f)
            val seedVelocity = (upwardVelocity / travelPx)
                .coerceIn(0f, remainingRange * GridOpenMaxVelocityPerRange)
            openGrid(
                initialVelocityProgressPerSec = seedVelocity,
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
            // The popup document is loaded into engineSession by Gecko; render it in a standalone
            // card floating over the current page rather than a real tab. Because the card session is
            // never added to the store, the user's page stays the active tab for extensions, so the
            // popup (uBlock, Chrome Mask, …) acts on that page — no popupTabIds bookkeeping needed.
            utilityWeb = UtilityWebRequest.Popup(title = title, session = engineSession)
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
                    .onSizeChanged { contentSize = it }
                    // Record the finger-down of each gesture so a subsequent link long-press can grow
                    // its glance card out of that point. awaitEachGesture wakes once per gesture (on
                    // the down) then idles until it ends — it does NOT process every scroll MOVE, so
                    // it stays off the scroll hot path (the old Initial-pass loop woke on every move).
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            lastPressPos = down.position
                        }
                    },
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
                            SnapshotRequestPurpose.LinkPeek -> {
                                if (capturedTabId == selectedTabId || capturedTabId == actualSelectedTabId) {
                                    peekCaptureBitmap = bitmap
                                }
                                currentSnapshotRequestPurpose = null
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
                // Fill the status-bar-height gap the top-padding fix opens above the padded-down
                // EngineView with the site's declared theme color (Safari-style), falling back to
                // the system primary. Drawn here — right after the engine, before the horizontal-
                // switch / grid / cover overlays — so those opaque overlays paint over it naturally
                // and it only shows in the normal browse layer.
                if (topStripColorActive && shouldShowUi && topChromeInsetPx > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(topChromeInsetDp)
                            .align(Alignment.TopStart)
                            .background(topStripColor),
                    )
                }
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
                // Crashed-tab recovery: when Gecko kills the selected tab's content process (OOM /
                // renderer crash), the engine surface goes blank with no way back. Cover it with a
                // restore affordance (Fenix parity); the button re-links a fresh session and reloads
                // the tab's saved URL via SessionUseCases.crashRecovery.
                if (selectedTab?.engineState?.crashed == true && shouldShowUi) {
                    com.firebolt9907.luminabrowser.ui.content.CrashRestoreOverlay(
                        onRestore = {
                            selectedTabId?.let { id ->
                                components.sessionUseCases.crashRecovery.invoke(listOf(id))
                            }
                        },
                        topInset = with(density) { topChromeInsetPx.toDp() },
                    )
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
                    // Depth card-stack (wide screens only): as the swipe progresses the leaving page
                    // recedes + dims while the incoming page rises from a smaller, dimmed card to full
                    // size. All reads happen inside graphicsLayer/drawWithContent (draw phase) so the
                    // per-frame `offsetX` never recomposes this subtree — same discipline as the flat
                    // slide it enhances. `overlayActive` stays true through the commit spring, so the
                    // release animation inherits the identical curve (no visual pop). Portrait keeps
                    // the flat slide.
                    val depthSwipe = isWideLayout
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
                                    if (depthSwipe) {
                                        // Arrival progress: 0 fully off-screen, 1 centred.
                                        val f = (abs(offsetX) / pageStride).coerceIn(0f, 1f)
                                        val s = DeckSwipeMinScale + (1f - DeckSwipeMinScale) * f
                                        scaleX = s
                                        scaleY = s
                                    }
                                }
                                .then(
                                    if (depthSwipe) Modifier.drawWithContent {
                                        drawContent()
                                        val f = (abs(offsetX) / pageStride).coerceIn(0f, 1f)
                                        drawRect(Color.Black, alpha = DeckSwipeMaxDim * (1f - f))
                                    } else Modifier,
                                )
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
                                .graphicsLayer {
                                    translationX = offsetX
                                    if (depthSwipe) {
                                        // Departure progress: 0 centred, 1 fully swiped away.
                                        val f = (abs(offsetX) / pageStride).coerceIn(0f, 1f)
                                        val s = 1f - (1f - DeckSwipeMinScale) * f
                                        scaleX = s
                                        scaleY = s
                                    }
                                }
                                .then(
                                    if (depthSwipe) Modifier.drawWithContent {
                                        drawContent()
                                        val f = (abs(offsetX) / pageStride).coerceIn(0f, 1f)
                                        drawRect(Color.Black, alpha = DeckSwipeMaxDim * f)
                                    } else Modifier,
                                )
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
                            themeColorProvider = gridThemeColorProvider,
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
                            themeColorProvider = gridThemeColorProvider,
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
                                    val reopenableCount by components.tabRepository.reopenableCount
                                        .collectAsStateWithLifecycle()
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
                                        // Reopen the last closed tab (Safari/Arc). Disabled — not
                                        // hidden — when the stack is empty, so its slot is stable.
                                        DropdownMenuItem(
                                            text = { Text("Reopen Closed Tab") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.Undo,
                                                    contentDescription = null,
                                                )
                                            },
                                            enabled = reopenableCount > 0,
                                            onClick = {
                                                overflowOpen = false
                                                components.tabRepository.reopenLastClosedTab()
                                                luminaHaptics.create()
                                                closeGrid(animateBack = true)
                                            },
                                        )
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
                    // Most-recently-used other Sync device — the Send Tab target the copy banner offers.
                    val recentTargetDevice = remember(firefoxSyncState.targetDevices) {
                        firefoxSyncState.targetDevices.maxByOrNull { it.lastAccessTime ?: Long.MIN_VALUE }
                    }
                    // Shared fast-copy action: bottom-toolbar Copy button + address-pill long-press.
                    val copyCurrentUrl = {
                        val currentUrl = selectedContent?.url.orEmpty()
                        if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(currentUrl))
                            // When signed in with a reachable device, surface a transient "Send to
                            // <device>" banner above the pill instead of only the toast.
                            if (recentTargetDevice != null) {
                                sendLinkPrompt = SendLinkPrompt(
                                    url = currentUrl,
                                    title = selectedContent?.title.orEmpty(),
                                )
                            } else {
                                android.widget.Toast.makeText(appContext, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
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
                        onCopyTap = copyCurrentUrl,
                        // Long-press the address pill copies the URL — same fast-copy action as the
                        // bottom-toolbar Copy button, reachable without the toolbar enabled.
                        onUrlLongPress = copyCurrentUrl,
                        onForwardTap = { selectedTabId?.let { components.sessionUseCases.goForward.invoke(it, true) } },
                        onShareTap = {
                            shareUrl(appContext, selectedContent?.url.orEmpty(), selectedContent?.title)
                        },
                        onBookmarksTap = { showBookmarks = true },
                        onTabsTap = { openGrid(initialVelocityProgressPerSec = 0f) },
                        isWideLayout = isWideLayout,
                        wideBarLayout = wideBarLayout,
                        searchEngine = barSearchEngine,
                        showSearchTermsInBar = showSearchTermsInBar,
                    )

                    // Auto-retire the copy → send banner after a short window.
                    LaunchedEffect(sendLinkPrompt) {
                        if (sendLinkPrompt != null) {
                            kotlinx.coroutines.delay(6000L)
                            sendLinkPrompt = null
                        }
                    }
                    com.firebolt9907.luminabrowser.ui.sync.SendToDeviceBannerHost(
                        deviceName = if (sendLinkPrompt != null) recentTargetDevice?.name else null,
                        deviceType = recentTargetDevice?.type,
                        visible = sendLinkPrompt != null && recentTargetDevice != null,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        onSend = {
                            val prompt = sendLinkPrompt
                            val device = recentTargetDevice
                            if (prompt != null && device != null) {
                                components.firefoxSyncController.sendTab(device.id, prompt.title, prompt.url)
                            }
                            sendLinkPrompt = null
                        },
                        onDismiss = { sendLinkPrompt = null },
                    )

                    // In-app banner for a tab another device just pushed here via "Send tab".
                    // Auto-retire after a short window (the system notification persists longer).
                    val receivedTab = firefoxSyncState.receivedTab
                    LaunchedEffect(receivedTab?.receivedAt) {
                        if (receivedTab != null) {
                            kotlinx.coroutines.delay(8000L)
                            components.firefoxSyncController.dismissReceivedTab()
                        }
                    }
                    com.firebolt9907.luminabrowser.ui.sync.ReceivedTabBannerHost(
                        received = receivedTab,
                        visible = receivedTab != null,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        onOpen = { tab ->
                            components.firefoxSyncController.dismissReceivedTab()
                            viewModel.onOpenNewTab(url = tab.url, title = tab.title, selectTab = true)
                        },
                        onDismiss = { components.firefoxSyncController.dismissReceivedTab() },
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

        LuminaOverlaySheets(
            components = components,
            viewModel = viewModel,
            scope = scope,
            selectedContent = selectedContent,
            selectedTabId = selectedTabId,
            actualSelectedTabId = actualSelectedTabId,
            selectedEngineSession = selectedEngineSession,
            currentHost = currentHost,
            currentUrl = currentUrl,
            currentPageSnapshot = currentPageSnapshot,
            topChromeInsetPx = topChromeInsetPx,
            showBottomToolbar = showBottomToolbar,
            gridEngaged = gridEngaged,
            onCloseGrid = { animateBack -> closeGrid(animateBack = animateBack) },
            onOpenUtilityUrl = { title, url ->
                utilityWeb = UtilityWebRequest.Url(title = title, url = url)
            },
            showAddons = showAddons,
            onDismissAddons = { showAddons = false },
            showSettings = showSettings,
            onDismissSettings = { showSettings = false },
            onSettingChanged = {
                components.applyEngineSettings()
                showBottomToolbar = components.globalPreferencesStore.showBottomToolbar
                wideBarLayout = WideBarLayout.fromName(
                    components.globalPreferencesStore.wideBarLayoutName,
                )
                showSearchTermsInBar = components.globalPreferencesStore.showSearchTermsInBar
                barSearchEngine = components.globalPreferencesStore.searchEngine
                viewModel.refreshDisplayPrefs()
            },
            onReplayOnboarding = {
                showSettings = false
                showOnboarding = true
            },
            showOnboarding = showOnboarding,
            onFinishOnboarding = { showOnboarding = false },
            showSyncedTabs = showSyncedTabs,
            onDismissSyncedTabs = { showSyncedTabs = false },
            showPasswords = showPasswords,
            onDismissPasswords = { showPasswords = false },
            showHistory = showHistory,
            onDismissHistory = { showHistory = false },
            showBookmarks = showBookmarks,
            onDismissBookmarks = { showBookmarks = false },
            showDownloads = showDownloads,
            onDismissDownloads = { showDownloads = false },
            showAa = showAa,
            aaInitialTab = aaInitialTab,
            onDismissAa = { showAa = false },
            onAaEngineRefresh = { engineRefreshTick += 1 },
            onFindInPage = {
                findQuery = ""
                findActiveMatch = 0
                findTotalMatches = 0
                findInPageActive = true
            },
            siteTrackingProtectionDisabled = siteTrackingProtectionDisabled,
            onSiteTrackingProtectionDisabledChange = { siteTrackingProtectionDisabled = it },
            utilityWeb = utilityWeb,
            onDismissUtilityWeb = { utilityWeb = null },
            peekUrl = peekUrl,
            peekSession = peekSession,
            peekView = peekView,
            peekOrigin = peekOrigin,
            peekSourceRect = peekSourceRect,
            peekElementBitmap = peekElementBitmap,
            onPeekAdopt = { url, foreground ->
                pendingPeekAdoption = url to foreground
                peekUrl = null
            },
            onDismissPeek = { peekUrl = null },
            imageMenuUrl = imageMenuUrl,
            onDismissImageMenu = { imageMenuUrl = null },
        )
    }
}
