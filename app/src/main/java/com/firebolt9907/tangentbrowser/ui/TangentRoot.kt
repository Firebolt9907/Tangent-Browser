package com.firebolt9907.tangentbrowser.ui

import android.graphics.Bitmap
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import com.firebolt9907.tangentbrowser.ui.motion.MotionInterruptedException
import com.firebolt9907.tangentbrowser.ui.motion.BrowserPhysics
import com.firebolt9907.tangentbrowser.ui.motion.rememberBrowserMotionEngine
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.firebolt9907.tangentbrowser.TangentApplication
import com.firebolt9907.tangentbrowser.state.BarMode
import com.firebolt9907.tangentbrowser.state.BarUiState
import com.firebolt9907.tangentbrowser.state.TangentViewModel
import com.firebolt9907.tangentbrowser.state.SitePreferences
import com.firebolt9907.tangentbrowser.state.addressBarLabel
import com.firebolt9907.tangentbrowser.state.hostFromUrl
import com.firebolt9907.tangentbrowser.ui.archive.siteHostFromUrl
import com.firebolt9907.tangentbrowser.ui.chrome.AddressSheet
import com.firebolt9907.tangentbrowser.ui.chrome.FindInPageBar
import com.firebolt9907.tangentbrowser.ui.chrome.SafariBar
import com.firebolt9907.tangentbrowser.ui.chrome.WideBarLayout
import com.firebolt9907.tangentbrowser.sync.FirefoxSyncEvent
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import com.firebolt9907.tangentbrowser.ui.content.ContentHost
import com.firebolt9907.tangentbrowser.ui.content.LocalSiteTint
import com.firebolt9907.tangentbrowser.ui.content.LinkPeekCard
import com.firebolt9907.tangentbrowser.ui.content.LinkRectProbe
import com.firebolt9907.tangentbrowser.ui.content.PeekEngineHolder
import com.firebolt9907.tangentbrowser.ui.content.UtilityWebCard
import com.firebolt9907.tangentbrowser.ui.content.UtilityWebRequest
import com.firebolt9907.tangentbrowser.ui.content.ThemeColorProbe
import com.firebolt9907.tangentbrowser.ui.content.loadJavascriptUrl
import com.firebolt9907.tangentbrowser.ui.content.linkUrlFromHitResult
import com.firebolt9907.tangentbrowser.ui.content.imageSrcFromHitResult
import com.firebolt9907.tangentbrowser.ui.grid.DeckTravelFrac
import com.firebolt9907.tangentbrowser.ui.grid.deckDragScale
import com.firebolt9907.tangentbrowser.ui.grid.deckDragCardScale
import com.firebolt9907.tangentbrowser.ui.grid.deckDragCardPose
import com.firebolt9907.tangentbrowser.ui.grid.GridTab
import mozilla.components.browser.icons.IconRequest
import com.firebolt9907.tangentbrowser.ui.containers.swatch
import com.firebolt9907.tangentbrowser.ui.grid.ClassicTabGridOverlay
import com.firebolt9907.tangentbrowser.ui.grid.TabGridOverlay
import com.firebolt9907.tangentbrowser.ui.grid.TabViewMode
import com.firebolt9907.tangentbrowser.ui.reader.hydrateAppearanceFromPrefs
import com.firebolt9907.tangentbrowser.ui.reader.openReaderLink
import com.firebolt9907.tangentbrowser.ui.reader.toggleReader
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import com.firebolt9907.tangentbrowser.ui.sync.HandoffCard
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import com.firebolt9907.tangentbrowser.ui.theme.TangentBrowserTheme
import com.firebolt9907.tangentbrowser.ui.theme.rememberSystemAccentColor
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.window.WindowRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

@OptIn(ExperimentalActivityApi::class)
@Composable
fun TangentRoot(
    viewModel: TangentViewModel = viewModel(),
    isInPipMode: Boolean = false,
    onFullScreenChanged: (Boolean) -> Unit = {},
    onRequestPipMode: () -> Unit = {},
) {
    val appContext = LocalContext.current.applicationContext
    val activityContext = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val components = (appContext as TangentApplication).components
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Chrome Input Vizard equivalent: collect the most frequently-changing fields via derived flows
    // that only emit when their specific content changed. This prevents TangentRoot from recomposing
    // on every tab content update (URL change, loading state, title change) — only when the
    // selected tab changes or tabs are added/removed/reordered.
    val selectedTabIdFromStore by components.selectedTabId.collectAsStateWithLifecycle()
    // The full open-tab set from the store. `tabsList` below narrows it to the active Space so the tab
    // switcher AND horizontal pill tab-switching (both read `tabsList`) only ever see the current
    // space's tabs. See the Spaces block just below.
    val allTabs by components.tabsList.collectAsStateWithLifecycle()

    // ---- Spaces (local, per-space tab sets — the evolution of Tab Groups) ----
    // Each Space is a TangentTabGroup; the default "Personal" space (activeSpaceId == null) holds tabs
    // that belong to no group. The switcher strip at the bottom of the tab overview changes
    // activeSpaceId; while browsing (grid closed) activeSpaceId follows whatever tab is selected so the
    // visible space always contains the current tab (see the follow-effect after gridEngaged).
    val spaceGroups by components.tabGroupsRepository.groups.collectAsStateWithLifecycle()
    val gridContainers by components.containerRepository.containers.collectAsStateWithLifecycle()
    var activeSpaceId by rememberSaveable {
        mutableStateOf(components.globalPreferencesStore.activeSpaceId)
    }
    // A deleted space falls back to Personal.
    val effectiveSpaceId = activeSpaceId?.takeIf { id -> spaceGroups.any { it.id == id } }
    // Latest active Space, readable from deferred callback lambdas (addon/window.open handlers
    // registered in a LaunchedEffect) without capturing a stale value — new tabs opened while
    // browsing inside a Space must stay in it and inherit its default container.
    val currentSpaceId = rememberUpdatedState(effectiveSpaceId)
    val tabsList = remember(allTabs, effectiveSpaceId, spaceGroups) {
        if (effectiveSpaceId == null) {
            val grouped = spaceGroups.flatMapTo(HashSet()) { it.tabIds }
            allTabs.filter { it.id !in grouped }
        } else {
            val ids = spaceGroups.firstOrNull { it.id == effectiveSpaceId }?.tabIds?.toHashSet()
                ?: emptySet()
            allTabs.filter { it.id in ids }
        }
    }
    val addonsState by components.addonController.state.collectAsStateWithLifecycle()
    val firefoxSyncState by components.firefoxSyncController.state.collectAsStateWithLifecycle()

    var isVideoFullscreen by remember { mutableStateOf(false) }

    SyncSystemBarsWithFullscreen(isVideoFullscreen)

    val readerManager = remember { com.firebolt9907.tangentbrowser.ui.reader.ReaderManager.instance }
    val activeReaderTabs by readerManager.activeReaderTabs.collectAsStateWithLifecycle()
    val eligibleReaderTabs by readerManager.eligibleReaderTabs.collectAsStateWithLifecycle()
    val tabArticles by readerManager.tabArticles.collectAsStateWithLifecycle()
    val readerTheme by readerManager.theme.collectAsStateWithLifecycle()
    val readerFontSize by readerManager.fontSize.collectAsStateWithLifecycle()
    val readerFontName by readerManager.fontName.collectAsStateWithLifecycle()
    // Raised when a *manual* reader activation finds no article (see ReaderManager.reportReaderUnavailable).
    val readerUnavailableTab by readerManager.readerUnavailableTab.collectAsStateWithLifecycle()

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
    }

    // Find-in-page state. The match counts are pushed by the engine session's onFindResult.
    var findInPageActive by remember { mutableStateOf(false) }
    var showBottomToolbar by remember { mutableStateOf(components.globalPreferencesStore.showBottomToolbar) }
    // Multi-function pull-to-refresh: see pullMenuSelection below. Persisted; Settings → Appearance.
    var pullMenuEnabled by remember { mutableStateOf(components.globalPreferencesStore.pullMenuEnabled) }
    // When on, the chrome recolors to the current site's declared theme color (see ThemeColorProbe /
    // themeColorsByHost below). Persisted; toggled in Settings → Appearance.
    var matchSiteThemeColor by remember { mutableStateOf(components.globalPreferencesStore.matchSiteThemeColor) }
    // When on, web page content is tinted toward the system accent color (Arc-boost style). Persisted.
    var tintPagesToSystemColor by remember { mutableStateOf(components.globalPreferencesStore.tintPagesToSystemColor) }
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
    val actualSelectedTabId = selectedTabIdFromStore
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
    val selectedTab = tabsList.firstOrNull { it.id == selectedTabId }
    val actualSelectedTab = tabsList.firstOrNull { it.id == actualSelectedTabId }
    val selectedEngineSession = actualSelectedTab?.engineState?.engineSession
    val selectedContent = selectedTab?.content

    // ---- "Open in app" persistent banner ---------------------------------------------------------
    // A persistent banner like the Internet Archive scrubber: it sits at the BASE of the Now-Bar
    // stack (temporary banners pile in front of it) and reserves viewport space so the page scrolls
    // under it. Its state is hoisted here — before ContentHost — so the dvh inset is known at bind
    // time (mirrors the archive bar). Two triggers, one card:
    //  - requested: a page tried to open a native app (intent:// / custom scheme), caught by
    //    TangentRequestInterceptor and relayed via AppLaunchRequests.
    //  - passive: the current site simply HAS an associated app (nativeAppFor).
    // A requested launch takes priority and, when the `autoOpenAppLinks` pref is on, auto-opens.
    val pendingAppLaunch by com.firebolt9907.tangentbrowser.ui.content.AppLaunchRequests.pending
        .collectAsStateWithLifecycle()
    val requestedLaunch = pendingAppLaunch?.takeIf { it.tabId == selectedTabId }
    val associatedApp by produceState<com.firebolt9907.tangentbrowser.ui.chrome.NativeApp?>(
        initialValue = null,
        selectedContent?.url,
    ) {
        val url = selectedContent?.url
        value = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                com.firebolt9907.tangentbrowser.ui.chrome.nativeAppFor(appContext.packageManager, url)
            }
        }
    }
    // Hosts the user explicitly dismissed (X / swipe) the passive suggestion for — don't nag again
    // this session. Tapping "Open" does NOT land here (see openInAppActedKey).
    val dismissedAppHosts = remember { mutableStateSetOf<String>() }
    // Persistent per-host engagement stats + blacklist. After enough dismissals at a high rejection
    // rate for one host, we pop the "add to blacklist?" dialog (openInAppBlacklistPrompt). Blacklisted
    // hosts never show the passive suggestion again. Mirror the persisted set into snapshot state so
    // the gate below recomposes when a host is added.
    val appLinkStats = remember { com.firebolt9907.tangentbrowser.state.AppLinkBannerStats(appContext) }
    val blacklistedAppHosts = remember {
        mutableStateSetOf<String>().apply { addAll(appLinkStats.blacklist()) }
    }
    // host → appName for the pending "add to blacklist?" dialog; null when not shown.
    var openInAppBlacklistPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Transient hide after tapping "Open": suppresses the banner for the exact page acted on, but a
    // new tab or a fresh navigation to the site brings it back (unlike dismissedAppHosts).
    var openInAppActedKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val motionEngine = rememberBrowserMotionEngine()
    // The one page-level feedback channel (see PageFeedback.kt). Created here rather than inside
    // TangentOverlaySheets because the root's own confirmations — a remote sign-out, a copied link,
    // a saved blob file, an app link with no app left to open it — need it too; the host that draws
    // it still lives at the end of the sheets composable, so a message always draws over the
    // surfaces that raise it.
    val pageFeedback = rememberPageFeedback(scope)

    // A pending offer targets the previously-visible tab; drop it when the tab changes.
    LaunchedEffect(selectedTabId) {
        com.firebolt9907.tangentbrowser.ui.content.AppLaunchRequests.pending.value?.let {
            if (components.store.state.tabs.any { tab -> tab.id == it.tabId }) {
                com.firebolt9907.tangentbrowser.ui.content.AppLaunchRequests.clearForTab(it.tabId)
            }
        }
    }
    // Auto-open (pref): launch the requested app immediately, skipping the banner.
    LaunchedEffect(requestedLaunch) {
        val req = requestedLaunch ?: return@LaunchedEffect
        if (components.globalPreferencesStore.autoOpenAppLinks) {
            launchExternalApp(
                context = appContext,
                intent = req.intent,
                fallbackUrl = req.fallbackUrl,
                onFallback = { fb -> req.tabId?.let { components.tabRepository.loadUrl(fb, it) } },
                onMessage = { pageFeedback.show(it) },
            )
            com.firebolt9907.tangentbrowser.ui.content.AppLaunchRequests.clearForTab(selectedTabId)
        }
    }
    // Normalized the same way the stats/blacklist store keys hosts, so the gate below and the
    // persisted blacklist compare apples to apples.
    val openInAppPassiveHost = associatedApp?.let {
        appLinkStats.normalizeHost(siteHostFromUrl(selectedContent?.url.orEmpty()))
    }
    // Identity of the exact page a passive suggestion is about, so tapping "Open" hides only this
    // page's banner (not the whole host).
    val openInAppPassiveKey = "${selectedTabId}|${selectedContent?.url}"
    val openInAppShowPassive = requestedLaunch == null && associatedApp != null &&
        openInAppPassiveHost != null && openInAppPassiveHost !in dismissedAppHosts &&
        openInAppPassiveHost !in blacklistedAppHosts &&
        openInAppPassiveKey != openInAppActedKey
    val openInAppRequested = requestedLaunch != null
    val openInAppActive = openInAppRequested || openInAppShowPassive
    val openInAppName = when {
        openInAppRequested -> requestedLaunch?.appName?.takeIf { it.isNotBlank() } ?: "app"
        openInAppShowPassive -> associatedApp?.label
        else -> null
    }
    val openInAppPackage = if (openInAppRequested) {
        requestedLaunch?.packageName
    } else {
        associatedApp?.packageName
    }

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
    // Option A + low-memory guard: reuse one held session when memory is healthy; under pressure,
    // skip the live preview (static glance) and free the held session now — no peek card is mounted
    // at long-press time, so releasing the view here can't race an AndroidView. Shared by the web
    // long-press glance and the reader-mode link long-press.
    fun preparePeekEngine() {
        if (deviceLowOnMemory()) {
            peekSession = null
            peekView = null
            peekEngine.release()
        } else {
            val (s, v) = peekEngine.ensure(activityContext, selectedContent?.private == true)
            peekSession = s
            peekView = v
        }
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
                groupId = currentSpaceId.value,
            )
        } else {
            // No live preview existed (low-memory static glance): fall back to a normal fresh load.
            viewModel.onOpenNewTab(
                url = url,
                title = "",
                selectTab = foreground,
                groupId = currentSpaceId.value,
            )
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
    val selectedIndex = tabsList.indexOfFirst { it.id == selectedTabId }
    val previousTab = tabsList.getOrNull(selectedIndex - 1)
    val nextTab = tabsList.getOrNull(selectedIndex + 1)
    val previousTabId = previousTab?.id
    val nextTabId = nextTab?.id
    val tabCount = tabsList.size
    val canCreateTabFromRightEdge = selectedIndex == tabCount - 1 && selectedIndex >= 0

    val density = LocalDensity.current
    // Semantic haptics: each gesture gets a texture that matches its meaning (soft rumble when the
    // grid catches, a light tick when a release threshold arms, etc.) instead of one sharp tap.
    val tangentHaptics = rememberTangentHaptics()
    // Per-gesture build-up ramps: a gradually growing vibration as a drag nears its commit point.
    // (Grid open is intentionally excluded — it's a single soft rumble at the threshold, no ramp.)
    val newTabRamp = rememberDragRamp(tangentHaptics)
    val pullRamp = rememberDragRamp(tangentHaptics)
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
    val pullMenuRevealPx = with(density) { PullMenuRevealDistance.toPx() }
    val pullMenuSlotWidthPx = with(density) { PullMenuSlotWidth.toPx() }
    var bottomChromeInsetPx by remember { mutableIntStateOf(0) }
    // Transient "Send to device" prompt shown above the pill after copying a link; null = hidden.
    var sendLinkPrompt by remember { mutableStateOf<SendLinkPrompt?>(null) }
    // Manual "paste SMS code" fallback banner: shown only when a page reports an OTP field (see
    // OtpAutofill) AND the clipboard looks freshly changed (a code was likely just copied). Covers
    // codes SMS User Consent can't auto-read. `otpFieldPresent` is armed by page detection;
    // `clipWatcher` supplies the clipboard-changed signal without ever reading the clip contents
    // (no Android 12+ paste toast) — see ClipboardChangeWatcher.
    var otpPasteVisible by remember { mutableStateOf(false) }
    var otpFieldPresent by remember { mutableStateOf(false) }

    // "Save this file" offer for a blob document (see BlobCapture / BlobFileStore). Derived, not
    // event-driven: the banner is up whenever the page on screen IS a captured file and the user
    // hasn't waved it away, so it survives tab swaps and comes back with the tab after a restart.
    val capturedBlobFiles by com.firebolt9907.tangentbrowser.state.BlobFileStore.files.collectAsState()
    var blobBannerDismissedId by remember { mutableStateOf<String?>(null) }
    val currentBlobFile = remember(capturedBlobFiles, selectedContent?.url) {
        com.firebolt9907.tangentbrowser.state.BlobFileStore.lookup(selectedContent?.url)
    }

    // "Reader mode unavailable" banner — shown only when a manual reader activation on the FOREGROUND
    // tab comes up empty. Consume the manager signal immediately so it can re-fire on a later attempt.
    var readerUnavailableVisible by remember { mutableStateOf(false) }
    LaunchedEffect(readerUnavailableTab, selectedTabId) {
        val tab = readerUnavailableTab ?: return@LaunchedEffect
        if (tab == selectedTabId) {
            readerUnavailableVisible = true
            readerManager.clearReaderUnavailable(tab)
        }
    }
    LaunchedEffect(readerUnavailableVisible) {
        if (readerUnavailableVisible) {
            kotlinx.coroutines.delay(6000L)
            readerUnavailableVisible = false
        }
    }
    // A fresh manual toggle that succeeds (reader opens) should retire any lingering banner.
    LaunchedEffect(isReaderActiveOnSelectedTab) {
        if (isReaderActiveOnSelectedTab) readerUnavailableVisible = false
    }
    val clipWatcher = remember {
        com.firebolt9907.tangentbrowser.ui.content.ClipboardChangeWatcher(appContext)
    }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    // Horizontal tab-switch / predictive-back drag gesture (invariant #5) grouped in
    // HorizontalGestureState; local `var x by hdrag.x` delegate aliases keep every site unchanged.
    val hdrag = rememberHorizontalGestureState()
    val offsetXState = hdrag.offsetX
    var offsetX by offsetXState
    var predictiveBackOffsetPx by hdrag.predictiveBackOffsetPx
    // Boolean gate for the predictive-back overlay. Derived so the per-frame predictiveBackOffsetPx
    // updates during a back-swipe flip this only at the 0↔>0 boundary; the overlay's own transforms
    // already read the raw value inside graphicsLayer/drawBehind (draw phase), so the subtree no
    // longer recomposes every frame of the gesture.
    val predictiveBackActive by remember { derivedStateOf { predictiveBackOffsetPx > 0f } }
    var dragCommitted by hdrag.dragCommitted
    // Snapshot/cover state machine (invariant #7) is grouped in SnapshotCoverState so it can travel
    // to extracted gesture render blocks as one object. Local `var x by snap.x` delegate aliases
    // (the same idiom as offsetXState above) keep every existing read/write site unchanged.
    val snap = rememberSnapshotCoverState()
    var stickyIncomingSnapshot by snap.stickyIncomingSnapshot
    var stickyIncomingTabId by snap.stickyIncomingTabId
    // False until Gecko fires its first paint callback for the current tab. Covers the grey
    // surface that GeckoView shows before any content has been composited.
    var geckoHasPainted by snap.geckoHasPainted
    var pendingActiveBarState by remember { mutableStateOf<BarUiState?>(null) }
    var pendingGeckoSelectTabId by remember { mutableStateOf<String?>(null) }
    var pendingGeckoSelectCreatedTab by remember { mutableStateOf(false) }
    var pendingGeckoEditBlankTab by remember { mutableStateOf(false) }
    var pendingGeckoAfterSelect by remember { mutableStateOf<(() -> Unit)?>(null) }
    var stickyBackCoverSnapshot by snap.stickyBackCoverSnapshot
    var stickyBackCoverUrl by snap.stickyBackCoverUrl
    var gestureCurrentSnapshot by snap.gestureCurrentSnapshot
    var activePredictiveBackSnapshot by snap.activePredictiveBackSnapshot
    var tabSwitchCurrentSnapshot by snap.tabSwitchCurrentSnapshot
    var leavingSnapshotBitmap by snap.leavingSnapshotBitmap
    var leavingSnapshotOffsetX by snap.leavingSnapshotOffsetX
    var leavingSnapshotTopPadPx by snap.leavingSnapshotTopPadPx
    var leavingSnapshotTabId by snap.leavingSnapshotTabId
    var leavingSnapshotJob by snap.leavingSnapshotJob
    var incomingSnapshotBitmap by snap.incomingSnapshotBitmap
    var incomingSnapshotOffsetX by snap.incomingSnapshotOffsetX
    var incomingSnapshotTopPadPx by snap.incomingSnapshotTopPadPx
    var incomingSnapshotTabId by snap.incomingSnapshotTabId
    var stickyIncomingTopPadPx by snap.stickyIncomingTopPadPx
    // Fade-out phase of the sticky incoming cover (fadingCover* state + fadeOutStickyCover) now
    // lives entirely on SnapshotCoverState and renders inside SwitchSnapshotCovers.
    var commitAnimNewTab by snap.commitAnimNewTab
    // Grid open/close gesture state (invariants #4, #5, #5a) grouped in GridGestureState; local
    // `var x by grid.x` delegate aliases keep every existing read/write site unchanged.
    val grid = rememberGridGestureState()
    var gridDragPx by grid.gridDragPx
    var gridGestureActive by grid.gridGestureActive
    var gridOpen by grid.gridOpen
    var gridAnimationJob by grid.gridAnimationJob
    val gridProgressState = grid.gridProgressState
    // Decoupled X-axis progress for grid cell layout. While the gesture is active this stays at 0
    // so the tab being dragged keeps its carousel-relative X (under the finger). On release it
    // springs to its destination (1 for openGrid, 0 for cancel/closeGrid) in lockstep with
    // gridProgressState, so cells slide to their columns only after the user lets go — like the
    // iOS app switcher.
    val gridXProgressState = grid.gridXProgressState
    // Direct drag progress during gesture — avoids scope.launch per delta frame.
    // gridProgressState is only used for spring animations; this drives the render during drag.
    var gridDragProgress by grid.gridDragProgress
    // True once the upward drag has crossed the grid-commit threshold (50% of travel) so we fire
    // the confirmation haptic exactly once on crossing, and don't re-fire it on release.
    var gridThresholdHapticFired by grid.gridThresholdHapticFired
    // Set when a pill gesture is judged (once, at the vertical slop crossing) to be a horizontal
    // tab swipe; vertical motion is ignored for the rest of that gesture. Reset on drag start.
    var gridAxisLockedHorizontal by grid.gridAxisLockedHorizontal
    // Mid-switch handoff: when grid grabbed during a horizontal commit spring, the leaving and
    // incoming tabs' frozen X offsets seed the grid animation. Otherwise these stay null and the
    // grid derives live tabs dynamically from offsetX/selectedTabId/previousTabId/nextTabId.
    var gridHandoffSelectedTabId by grid.gridHandoffSelectedTabId
    var gridHandoffLeavingTabId by grid.gridHandoffLeavingTabId
    var gridHandoffSelectedOffsetX by grid.gridHandoffSelectedOffsetX
    var gridHandoffLeavingOffsetX by grid.gridHandoffLeavingOffsetX
    var gridHandoffSelectedTopPadPx by grid.gridHandoffSelectedTopPadPx
    var gridHandoffLeavingTopPadPx by grid.gridHandoffLeavingTopPadPx
    // Finger-tracking positions captured at drag release; drive the commit animation from
    // drag-end to grid cells. Cleared after the spring finishes or when the grid closes.
    var gridCommitFromPositions: List<Triple<Float, Float, Float>>? by grid.gridCommitFromPositions
    var gridCommitFromProgress by grid.gridCommitFromProgress
    var gridCancelFromProgress by grid.gridCancelFromProgress
    // Stays true from closeGrid(animateBack=true) until afterClose() completes, keeping gridEngaged
    // true so the tab cards at progress=0 (fullscreen position) cover the live Gecko view during
    // the gap between animation-end and the deferred tab selection in afterClose.
    var gridWaitingForAfterClose by grid.gridWaitingForAfterClose
    // True while the grid is closing after the user tapped the "+" cell. The "+" cell animates
    // to fullscreen instead of the previously-selected tab; real tabs animate off-screen left.
    var gridNewTabSelected by grid.gridNewTabSelected
    // Measured height of the grid's floating bottom controls (nav-bar inset + toolbar + the handoff
    // banner when present). Drives how far the last grid row clears the bottom, so the clearance
    // tracks button vs gesture navigation and the banner's visibility instead of a fixed guess.
    var gridBottomControlsHeightPx by grid.gridBottomControlsHeightPx
    var currentSnapshotRequestPurpose by snap.currentSnapshotRequestPurpose
    var currentSnapshotRequestId by snap.currentSnapshotRequestId

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
            preparePeekEngine()
            peekUrl = link
            tangentHaptics.longPress()
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
                tangentHaptics.longPress()
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
    var switchAnimationJob by hdrag.switchAnimationJob
    var predictiveBackAnimationJob by hdrag.predictiveBackAnimationJob
    var barCollapseAnimationJob by remember { mutableStateOf<Job?>(null) }
    var barCollapseIdleJob by remember { mutableStateOf<Job?>(null) }
    var pageScrollFrameJob by remember { mutableStateOf<Job?>(null) }
    // Scroll velocity tracking for input prediction (Chrome Input Prediction equivalent).
    // When GeckoView misses a frame, we predict the next delta from recent velocity so the bar
    // collapse stays visually smooth instead of stuttering for one refresh cycle (~8.3 ms at 120 Hz).
    val scrollVelocityWindow = remember { floatArrayOf(0f, 0f, 0f) }
    val scrollVelocityTimestamps = remember { longArrayOf(0L, 0L, 0L) }
    var scrollVelocityIndex by remember { mutableIntStateOf(0) }
    var scrollVelocitySampleCount by remember { mutableIntStateOf(0) }
    var showAddons by remember { mutableStateOf(false) }
    var showSyncedTabs by remember { mutableStateOf(false) }
    var showTabGroups by remember { mutableStateOf(false) }
    var showContainers by remember { mutableStateOf(false) }
    var showSpaces by remember { mutableStateOf(false) }
    var showPasswords by remember { mutableStateOf(false) }
    var showCreditCards by remember { mutableStateOf(false) }
    var showAddresses by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    var showAa by remember { mutableStateOf(false) }
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
    // Multi-function overscroll pull (Chrome-iOS style): once the pull is under way the same finger
    // can slide sideways to pick New Tab / Refresh / Reader. Refresh is the centre slot, so with the
    // menu on a plain straight-down pull still just refreshes. Only the discrete selection lives in
    // state — the horizontal magnitude is consumed as it arrives and never recomposes anything.
    var pullMenuSelection by remember { mutableStateOf(PullAction.Refresh) }
    // Live horizontal finger travel since the pull began. Written on the touch hot-path and read
    // ONLY from draw-phase lambdas (like pullRefreshDistancePx) — the menu's highlight rides this
    // continuously, while pullMenuSelection above is the discrete, hysteretic commit it snaps to.
    var pullMenuDxPx by remember { mutableFloatStateOf(0f) }
    // Recentring of the highlight after a release (see settlePullMenuDx). Held so a fresh pull can
    // pre-empt an in-flight recentre instead of fighting it for the value.
    var pullMenuRecentreJob by remember { mutableStateOf<Job?>(null) }
    // Reader is offered only where it would do something — a page reader can extract, or one already
    // in reader (so the gesture can leave it). Everywhere else the slot is dimmed and unselectable,
    // which is why it stays drawn: the menu must not change shape under a finger mid-gesture.
    val pullMenuReaderAvailable = selectedTabId != null &&
        (eligibleReaderTabs.contains(selectedTabId) || isReaderActiveOnSelectedTab)
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
    // Scroll-linked — the value tracks the reader scroll offset 1:1 (like the web-page
    // barCollapsePx path), so the pill slides off as you read down and back on as you read up,
    // proportional to distance travelled. Written directly on the scroll hot-path, read only in
    // the pill's layout/draw phase, so scrolling doesn't recompose the bar.
    var readerBarHidden by remember { mutableFloatStateOf(0f) }
    // Reader translates the pill away instead of morphing it into the web-page compact state.
    // Banners must follow that same reader hide progress, read only in their draw phase.
    val bannerCollapseProgressProvider = remember(isReaderActiveOnSelectedTab) {
        { if (isReaderActiveOnSelectedTab) readerBarHidden else barCollapseProgress }
    }
    // Tracks the settle animation launched on scroll-end so a new drag can pre-empt it.
    var readerBarSettleJob by remember { mutableStateOf<Job?>(null) }
    // Boolean gate for whether the pull-refresh line is shown. Derived so the per-frame
    // pullRefreshDistancePx updates (during a pull) flip this only at the 0↔>0 boundary instead of
    // recomposing TangentRoot on every drag frame; the live magnitude is passed to PullRefreshLine as
    // a lambda read in its draw phase.
    val pullRefreshActive by remember { derivedStateOf { pullRefreshDistancePx > 0f } }
    val pullRefreshProgressProvider = remember(pullRefreshThresholdPx) {
        { (pullRefreshDistancePx / pullRefreshThresholdPx).coerceIn(0f, 1f) }
    }
    // Reveal ramp for the overscroll action menu: 0 until the pull clears the reveal distance (so an
    // incidental bounce doesn't summon it), then 0..1 up to the trigger threshold — where the pull
    // arms, which is why the menu reads full presence as "releasing now fires this".
    fun pullMenuRevealFor(distancePx: Float): Float =
        ((distancePx - pullMenuRevealPx) / (pullRefreshThresholdPx - pullMenuRevealPx))
            .coerceIn(0f, 1f)
    // The menu's presence, 0..1 — reveal position, scale and opacity all at once.
    //
    // Deliberately its OWN value rather than a read of pullRefreshDistancePx. The distance is reset
    // in a single frame when a pull fires a refresh (the line hands over to its loading sweep), and
    // the tab-switch animation takes the screen when it opens a tab — so a menu driven off the pull
    // state cut out instantly on exactly the gestures that *did* something, while collapsing politely
    // on the ones that didn't. This is written live while pulling and springs to zero on release
    // whatever the release did, so the collapse is the same animation every time.
    var pullMenuPresence by remember { mutableFloatStateOf(0f) }
    val pullMenuPresenceProvider = remember { { pullMenuPresence } }
    // Composition gate, flipped false only once the collapse has actually reached zero — so the menu
    // is fully gone when it's gone, and never unmounts mid-fade.
    var pullMenuComposed by remember { mutableStateOf(false) }
    var pullMenuCollapseJob by remember { mutableStateOf<Job?>(null) }
    val pullMenuVisible = pullMenuEnabled && pullMenuComposed
    // Continuous highlight position in slot units. Reader availability clamps the upper bound rather
    // than being checked on release, so the highlight simply stops at Refresh instead of drifting
    // onto a chip that can't be committed to.
    val pullMenuPositionProvider = remember(pullMenuSlotWidthPx, pullMenuReaderAvailable) {
        { pullSelectionPosition(pullMenuDxPx, pullMenuSlotWidthPx, pullMenuReaderAvailable) }
    }
    // Retraction of a released-but-unconsummated pull (see settlePullRefresh). Held so a fresh pull
    // can pre-empt an in-flight retract instead of fighting it for the distance value.
    var pullRetractJob by remember { mutableStateOf<Job?>(null) }
    // The refresh line's own presence, kept separate from whether it has anything to show. Without
    // it the line pops in at full opacity on the first pull pixel and — worse — vanishes in a single
    // frame the moment the reload completes, cutting the sweep band off mid-travel. Composition is
    // gated on a plain bool flipped at the animation's ends so the alpha stays a draw-phase read.
    val pullLineAlpha = remember { Animatable(0f) }
    var pullLineComposed by remember { mutableStateOf(false) }
    val pullLineAlphaProvider = remember { { pullLineAlpha.value } }
    val pullLineShown = pullRefreshActive || pullRefreshing
    LaunchedEffect(pullLineShown) {
        if (pullLineShown) {
            pullLineComposed = true
            motionEngine.animate(pullLineAlpha, 1f, ExpressiveMotion.fastEffectsSpec())
        } else {
            motionEngine.animate(pullLineAlpha, 0f, ExpressiveMotion.defaultEffectsSpec())
            pullLineComposed = false
        }
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

    // Every Back commit goes through here so a federated sign-in chain is cleared in one step.
    // Stepping back INTO a spent IdP entry re-runs its redirect forward, so plain `goBack` looks
    // frozen after an SSO login; `goToHistoryIndex` jumps the whole contiguous run to the page the
    // user was on before signing in. Reads history from the LIVE store rather than the
    // composition-captured `selectedContent`, because the predictive-back callers below invoke this
    // from coroutines that can outlive the composition that created them. Falls back to a normal
    // one-step Back whenever there is no chain immediately behind the current entry.
    val commitGoBack: (String?) -> Unit = { tabId ->
        val history = tabId
            ?.let { id -> components.store.state.tabs.firstOrNull { it.id == id } }
            ?.content
            ?.history
        val collapsedIndex = history?.let(::ssoCollapsedBackIndex)
        if (tabId != null && collapsedIndex != null) {
            components.sessionUseCases.goToHistoryIndex.invoke(collapsedIndex, tabId)
        } else {
            components.sessionUseCases.goBack.invoke(tabId, true)
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
                // Don't auto-open pages with too little body text (landing pages, galleries, link
                // hubs) — they stay eligible for a manual toggle but shouldn't hijack the view.
                val substantial = readerManager.getArticle(tabId)?.hasSubstantialBody == true
                val url = tabsList.firstOrNull { it.id == tabId }?.content?.url.orEmpty()
                val host = siteHostFromUrl(url)
                if (substantial && host.isNotEmpty() && !components.sitePreferencesStore.isReaderAutoDisabled(host)) {
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
                    // Replay a clipboard change we couldn't observe live while backgrounded (the
                    // copy-code-from-Messages-then-return flow): if the clip timestamp advanced and
                    // an OTP field is still present, surface the paste banner. Metadata only.
                    if (clipWatcher.consumeChanged() && otpFieldPresent) otpPasteVisible = true
                    // Both halves of the data-saver signal (the OS Data Saver toggle and whether the
                    // active network is metered) can change freely while we're backgrounded, and
                    // neither raises a callback we listen for. Foreground is the moment that matters,
                    // so re-derive the policy here; it's a couple of set reads and one port message.
                    components.dataSaverController.recompute()
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
                    pageFeedback.show(
                        message = "Firefox Sync was signed out on another device",
                        actionLabel = "Sign in",
                        onAction = { showSyncedTabs = true },
                    )
                }
            }
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

    fun resetPredictiveBackGesture() {
        predictiveBackOffsetPx = 0f
        gestureCurrentSnapshot = null
        activePredictiveBackSnapshot = null
        if (currentSnapshotRequestPurpose == SnapshotRequestPurpose.PredictiveBack) {
            currentSnapshotRequestPurpose = null
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

    // Launcher app-shortcuts (long-press home icon). MainActivity records the request; act on it once
    // and clear. New Tab opens a fresh blank tab in editing mode; the others open a library sheet.
    // Voice-search shortcut can't run here — `onVoiceInput` is defined further down — so record it
    // and let a later effect (below its definition) launch the recognizer.
    var pendingVoiceSearch by remember { mutableStateOf(false) }
    val pendingShortcut by com.firebolt9907.tangentbrowser.state.AppShortcutSignals.pending.collectAsState()
    LaunchedEffect(pendingShortcut) {
        when (pendingShortcut) {
            com.firebolt9907.tangentbrowser.state.AppShortcutSignals.Action.NewTab -> {
                val newTabId =
                    viewModel.onCreateTabFromEdge(selectTab = false, groupId = currentSpaceId.value)
                stagePendingGeckoSelection(tabId = newTabId, createdTab = true, editBlankTab = true)
                commitPendingGeckoSelection()
            }
            com.firebolt9907.tangentbrowser.state.AppShortcutSignals.Action.Bookmarks -> showBookmarks = true
            com.firebolt9907.tangentbrowser.state.AppShortcutSignals.Action.History -> showHistory = true
            com.firebolt9907.tangentbrowser.state.AppShortcutSignals.Action.VoiceSearch -> pendingVoiceSearch = true
            null -> Unit
        }
        if (pendingShortcut != null) com.firebolt9907.tangentbrowser.state.AppShortcutSignals.consume()
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
    // TangentRoot per animation frame. During open, gridOpen short-circuits before the read; the
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

    // Count only continuous foreground viewing. Opening the overview or leaving this activity
    // cancels the dwell; selection changes also cancel it. Never move cards during a visible
    // gesture/cover transition, and never reselect/rebind the page just to update recency.
    LaunchedEffect(selectedTabIdFromStore, lifecycleOwner, gridEngaged) {
        val viewedId = selectedTabIdFromStore ?: return@LaunchedEffect
        if (gridEngaged) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            kotlinx.coroutines.delay(com.firebolt9907.tangentbrowser.state.TAB_RECENCY_DWELL_MS)
            androidx.compose.runtime.snapshotFlow {
                !gridEngaged && offsetX == 0f && !predictiveBackActive &&
                    pendingGeckoSelectTabId == null && pendingActiveBarState == null &&
                    leavingSnapshotBitmap == null && incomingSnapshotBitmap == null &&
                    stickyIncomingSnapshot == null && stickyBackCoverSnapshot == null
            }.first { it }
            components.tabRepository.promoteViewedTab(viewedId)
        }
    }

    // While browsing (grid closed), the active Space follows the selected tab so the switcher always
    // opens onto the space that contains the current tab — even when a tab is selected outside the
    // grid (a received/handoff tab, an "open in new tab" link, a synced-tab open). While the grid is
    // engaged the user drives activeSpaceId directly via the switcher strip, so we don't fight them.
    LaunchedEffect(selectedTabIdFromStore, spaceGroups, gridEngaged) {
        if (gridEngaged) return@LaunchedEffect
        val groupId = selectedTabIdFromStore
            ?.let { components.tabGroupsRepository.groupIdForTab(it) }
        if (groupId != activeSpaceId) {
            activeSpaceId = groupId
            components.globalPreferencesStore.activeSpaceId = groupId
        }
    }

    // Real favicons for the tab-overview captions, loaded (memory + disk cached) via BrowserIcons
    // while the overview is engaged. Keyed by URL so a navigation refreshes it; null is cached too
    // to avoid refetch storms, and the UI falls back to a letter tile. Feeds the iconProvider below.
    val faviconCache = remember { mutableStateMapOf<String, android.graphics.Bitmap?>() }
    // Favicons are keyed by the site's REAL URL: for an archived tab the address is a
    // web.archive.org snapshot, so load (and cache) the icon under the original page's URL instead —
    // the card then shows the live site's favicon, not the archive's.
    val faviconKeyFor: (String) -> String = remember {
        { url ->
            val wb = com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
            if (wb.isArchiveUrl(url)) wb.originalFromArchiveUrl(url) ?: url else url
        }
    }
    LaunchedEffect(gridEngaged, tabsList.size) {
        if (!gridEngaged) return@LaunchedEffect
        // Bound the cache to currently-open tabs so it can't grow without limit across a long
        // session of navigations/closes (BrowserIcons keeps its own bounded disk cache underneath).
        val currentUrls = tabsList.mapTo(HashSet()) { faviconKeyFor(it.content.url) }
        faviconCache.keys.retainAll(currentUrls)
        tabsList.forEach { tab ->
            val url = faviconKeyFor(tab.content.url)
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
    val currentHost = remember(currentUrl) { siteHostFromUrl(currentUrl.orEmpty()) }
    val sitePrefsStore = components.sitePreferencesStore
    val currentSitePrefs: SitePreferences = remember(currentHost, sitePrefsVersion) {
        sitePrefsStore.get(currentHost)
    }
    LaunchedEffect(sitePrefsStore) {
        sitePrefsStore.updates.collect { sitePrefsVersion += 1 }
    }

    // ---- Element zap (tap a page element to hide it for this site; see ui/content/ElementZap.kt) ----
    val zappedStore = components.zappedElementsStore
    var zapVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(zappedStore) {
        zappedStore.updates.collect { zapVersion += 1 }
    }
    // The selectors ContentHost re-applies as a stylesheet. Recomputed on host change and on any
    // store write, so a zap or a restore lands on the live page without a reload.
    val zappedSelectors = remember(currentHost, zapVersion) { zappedStore.selectors(currentHost) }
    // True while the in-page picker is running (it is swallowing the page's clicks).
    var zapPickerActive by remember { mutableStateOf(false) }
    // What the picker currently has under its crosshair, null until the user's first tap.
    var zapSelection by remember { mutableStateOf<com.firebolt9907.tangentbrowser.ui.content.ElementZap.Selection?>(null) }
    // Read through a holder rather than keying the effect below: re-keying mid-pick would tear the
    // live picker down and re-inject it, losing the user's current selection.
    val zapHost by rememberUpdatedState(currentHost)
    // Picker lifecycle: inject on entry, tear down on exit, and route the page's events into state.
    // ElementZap.onEvent fires on Gecko's thread, so every handler hops to the UI scope first.
    DisposableEffect(zapPickerActive, selectedEngineSession) {
        val session = selectedEngineSession
        if (!zapPickerActive || session == null) {
            com.firebolt9907.tangentbrowser.ui.content.ElementZap.onEvent = null
            onDispose { }
        } else {
            com.firebolt9907.tangentbrowser.ui.content.ElementZap.onEvent = { event ->
                scope.launch {
                    when (event) {
                        is com.firebolt9907.tangentbrowser.ui.content.ElementZap.Event.Selected -> {
                            zapSelection = event.selection
                            tangentHaptics.select()
                        }
                        is com.firebolt9907.tangentbrowser.ui.content.ElementZap.Event.Zapped -> {
                            val stored = zappedStore.add(
                                host = zapHost,
                                selector = event.selection.selector,
                                label = event.selection.label,
                            )
                            zapPickerActive = false
                            zapSelection = null
                            if (stored) {
                                // The store's update flow bumps zapVersion, which re-injects the
                                // stylesheet — that, not the picker, is what actually hides it.
                                tangentHaptics.toggle(true)
                            } else {
                                pageFeedback.show("Couldn't hide that element")
                            }
                        }
                        com.firebolt9907.tangentbrowser.ui.content.ElementZap.Event.Cancelled -> {
                            zapPickerActive = false
                            zapSelection = null
                        }
                    }
                }
            }
            session.loadJavascriptUrl(
                com.firebolt9907.tangentbrowser.ui.content.ElementZap.buildPickerScript(),
            )
            onDispose {
                com.firebolt9907.tangentbrowser.ui.content.ElementZap.onEvent = null
                // No-op when the picker already tore itself down (a committed zap); this covers every
                // other exit — cancel, navigation, tab switch — so the page never keeps swallowing
                // clicks after the banner is gone.
                session.loadJavascriptUrl(
                    com.firebolt9907.tangentbrowser.ui.content.ElementZap.buildCancelScript(),
                )
            }
        }
    }
    // A navigation replaces the DOM the picker was hit-testing, so leave picker mode with it.
    LaunchedEffect(currentUrl, selectedTabId) {
        if (zapPickerActive) {
            zapPickerActive = false
            zapSelection = null
        }
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
        val host = siteHostFromUrl(url.orEmpty())
        if (host.isEmpty()) return 0.dp
        return if (sitePrefsStore.get(host).topPaddingFix) topChromeInsetDp else 0.dp
    }
    // Per-host theme colors, sampled by ThemeColorProbe (the same `<meta name="theme-color">` tag
    // Safari reads) whenever a top-padding site is showing. Keyed by host so the tint stays stable
    // across pages of the same site — matching how the top-padding preference itself is scoped. A
    // missing entry means the site declared no theme color, so consumers fall back to the primary.
    val themeColorsByHost = remember { mutableStateMapOf<String, Color>() }
    // The real background color sampled from behind the status bar (top of the viewport), keyed by
    // host. Distinct from themeColorsByHost — this is the actual rendered content color, used only to
    // keep the status-bar icons legible on sites that declare no theme color; it must NOT feed the
    // strip fill or the chrome accent (which want the *declared* theme color, not a page's white body).
    val pageTopBgByHost = remember { mutableStateMapOf<String, Color>() }
    DisposableEffect(Unit) {
        ThemeColorProbe.listener = { _, url, themeInt, bgInt ->
            val host = siteHostFromUrl(url)
            if (host.isNotEmpty()) {
                // The probe fires on Gecko's thread; marshal the state write back to main.
                scope.launch {
                    if (themeInt != null) themeColorsByHost[host] = Color(themeInt)
                    else themeColorsByHost.remove(host)
                    if (bgInt != null) pageTopBgByHost[host] = Color(bgInt)
                    else pageTopBgByHost.remove(host)
                }
            }
        }
        onDispose { ThemeColorProbe.listener = null }
    }
    // Bind every consent result to the reporting tab, origin and captured session.
    LifecycleResumeEffect(Unit) {
        com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.onFieldDetected = { tabId ->
            scope.launch {
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    return@launch
                }
                val session = components.store.state.tabs.firstOrNull { it.id == tabId }
                    ?.engineState?.engineSession ?: return@launch
                val delivery = com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.createDelivery(
                    tabId = tabId,
                    selectedTabId = {
                        components.store.state.selectedTabId.takeIf {
                            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        }
                    },
                ) { code, origin ->
                    if (components.store.state.tabs.firstOrNull { it.id == tabId }
                            ?.engineState?.engineSession === session
                    ) {
                        session.loadJavascriptUrl(
                            com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.buildFillScript(code, origin),
                        )
                    }
                } ?: return@launch
                com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.codeFiller = delivery
                com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.smsListenStarter?.invoke(delivery)
                otpFieldPresent = true
                if (clipWatcher.consumeChanged()) otpPasteVisible = true
            }
        }
        if (otpFieldPresent) {
            components.store.state.selectedTabId?.let {
                com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.onFieldDetected?.invoke(it)
            }
        }
        onPauseOrDispose {
            com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.codeFiller = null
            com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.onFieldDetected = null
        }
    }
    // Live clipboard-change signal: while foreground, an in-app copy (or a paste from another app
    // that surfaces here) flips the banner on if an OTP field is present. No contents are read — the
    // listener carries no data; we only note that *something* changed. Background changes made by
    // other apps don't fire this (Android restricts it to the focused app); those are recovered by
    // the ON_RESUME timestamp comparison below.
    DisposableEffect(clipWatcher) {
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            if (clipWatcher.consumeChanged() && otpFieldPresent) otpPasteVisible = true
        }
        clipWatcher.addListener(listener)
        onDispose { clipWatcher.removeListener(listener) }
    }
    // Retire the paste banner when switching tabs, and clear the armed field flag (the new page may
    // have no OTP field; it re-arms if the detector fires again).
    LaunchedEffect(actualSelectedTabId, currentUrl) {
        otpPasteVisible = false
        otpFieldPresent = false
    }
    // Current site's declared theme color when the "Match website theme color" option is on — the
    // seed for the app-wide accent (TangentBrowserTheme) and the bottom-bar pill tint (BottomBarLayer).
    // Null when the option is off or the site declared no color, in which case the chrome stays on
    // its default Material colors.
    val activeSiteThemeColor: Color? = if (matchSiteThemeColor && currentHost.isNotEmpty()) {
        themeColorsByHost[currentHost]
    } else {
        null
    }
    // The color the "Tint pages to my system color" boost paints onto web content, or null when off.
    // Read from the platform accent (not the in-app primary) so the boost stays the true system color
    // even when the chrome is matching a site's theme color above.
    val systemAccentColor = rememberSystemAccentColor()
    val siteTintColor: Color? = if (tintPagesToSystemColor) systemAccentColor else null
    // The color to paint the exposed top strip / status bar for the current tab: the site's declared
    // theme color when top-padding-fix is on and one was found, otherwise the system primary color.
    val primaryColor = MaterialTheme.colorScheme.primary
    val topStripColorActive = currentSitePrefs.topPaddingFix && currentHost.isNotEmpty()
    val topStripColor = if (topStripColorActive) {
        themeColorsByHost[currentHost] ?: primaryColor
    } else {
        primaryColor
    }
    // Keep the status-bar icons legible over whatever sits behind them: dark icons on a light
    // backdrop, light icons on a dark one.
    //  - When the top strip is painting its own color, match that (icons sit over the painted strip).
    //  - Otherwise the status bar is transparent over live page content, so match the real background
    //    color sampled from behind it (ThemeColorProbe.bgColor). Android does NOT auto-tint the bar to
    //    content, so without this the icons would just follow the OS theme and vanish on, e.g., a white
    //    page in dark mode.
    //  - Fall back to the system theme only when nothing was sampled (blank/new-tab page, probe not
    //    yet returned).
    val systemDarkTheme = isSystemInDarkTheme()
    val sampledTopBgColor: Color? = if (currentHost.isNotEmpty()) pageTopBgByHost[currentHost] else null
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
    LaunchedEffect(statusBarController, topStripColorActive, topStripColor, sampledTopBgColor, systemDarkTheme) {
        val controller = statusBarController ?: return@LaunchedEffect
        controller.isAppearanceLightStatusBars = when {
            topStripColorActive -> topStripColor.luminance() > 0.5f
            sampledTopBgColor != null -> sampledTopBgColor.luminance() > 0.5f
            else -> !systemDarkTheme
        }
    }
    // Resolves a tab's theme-color strip fill for the tab switcher: the site's sampled theme color,
    // or null when none was declared (the card falls back to the primary color).
    val gridThemeColorProvider: (String) -> Color? = remember {
        { tabId ->
            val url = components.store.state.tabs.firstOrNull { it.id == tabId }?.content?.url
            val host = siteHostFromUrl(url.orEmpty())
            if (host.isEmpty()) null else themeColorsByHost[host]
        }
    }
    val currentSession by rememberUpdatedState(selectedEngineSession)
    // The address sheet's currently highlighted top suggestion (see AddressSheet.onTopSuggestionChange).
    // Held as a plain MutableState so the memoized callbacks below read its latest value at Go time.
    val topSuggestion = remember { mutableStateOf<String?>(null) }
    val topSuggestionQuery = remember { mutableStateOf<String?>(null) }
    // The top suggestion's URL when it is a concrete page (History/Bookmark) — fed to the editing
    // field for inline autocomplete. Search and switch-to-tab rows are excluded: there is no URL to
    // complete toward (a tab row switches rather than navigates).
    val autocompleteSource = remember { mutableStateOf<String?>(null) }
    // Set when the autoselected top row is an already-open tab: pressing Go/Enter then switches to it
    // instead of re-navigating (which would open a duplicate).
    val topSuggestionTabId = remember { mutableStateOf<String?>(null) }
    // Voice search: mic tap in the editing field → system recognizer → search/navigate the result.
    // Permission-less (Google's speech activity owns the mic); null when no recognizer is installed.
    val onVoiceInput = com.firebolt9907.tangentbrowser.ui.chrome.rememberVoiceSearchLauncher { spoken ->
        viewModel.onSubmitText(spoken)
    }
    // Home-screen search-widget mic tap (recorded above as pendingVoiceSearch): launch the recognizer
    // once the launcher exists. onVoiceInput is null if no speech recognizer is available.
    LaunchedEffect(pendingVoiceSearch, onVoiceInput) {
        if (pendingVoiceSearch && onVoiceInput != null) {
            pendingVoiceSearch = false
            onVoiceInput.invoke()
        }
    }
    val callbacks = remember(viewModel, currentUrl) {
        BarCallbacks(
            onUrlTap = viewModel::onUrlTap,
            onEditingTextChange = viewModel::onEditingTextChange,
            onEditingSubmit = {
                // Commit the autoselected top suggestion when one is present (desktop-browser
                // behavior); fall back to the raw typed text otherwise. onSubmitText and
                // onEditingSubmit both run the same normalizeUrl path.
                val currentText = viewModel.state.value.editingText.trim()
                val currentSuggestion = topSuggestionQuery.value == currentText
                val switchTab = topSuggestionTabId.value.takeIf { currentSuggestion }
                val top = topSuggestion.value.takeIf { currentSuggestion }
                if (switchTab != null) {
                    // Top row is an already-open tab — switch to it instead of navigating.
                    viewModel.onEditingCancel()
                    if (currentUrl == "about:blank") viewModel.dismissBlankTabAndReturn()
                    components.tabRepository.selectTab(switchTab)
                } else if (!top.isNullOrBlank()) {
                    viewModel.onSubmitText(top)
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
        motionEngine.interrupt(barCollapseProgressState)
        barCollapseAnimationJob = scope.launch {
            motionEngine.animate(barCollapseProgressState, 0f, TangentMotion.BarCollapse) {
                barCollapseProgress = value
            }
        }
    }

    // Retract and unmount the overscroll menu. Called on EVERY release, whether or not an action
    // fired: the collapse is the gesture's ending, not a consolation for a gesture that did nothing.
    // (A firing pull resets the refresh distance in one frame, so a menu that keyed off it would cut
    // out instantly on exactly the pulls that mattered.) The composition gate is dropped only once
    // the spring has actually reached zero, so the menu is never left hanging at partial opacity —
    // and never unmounted mid-fade either.
    fun collapsePullMenu() {
        pullMenuCollapseJob?.cancel()
        if (pullMenuPresence <= 0f) {
            pullMenuPresence = 0f
            pullMenuComposed = false
            pullMenuCollapseJob = null
            return
        }
        pullMenuCollapseJob = scope.launch {
            motionEngine.animate("pull-presence",
                initialValue = pullMenuPresence,
                targetValue = 0f,
                animationSpec = TangentMotion.PullRetract,
            ) { value, _ -> pullMenuPresence = value }
            pullMenuPresence = 0f
            pullMenuComposed = false
            pullMenuCollapseJob = null
        }
    }

    fun resetPullRefresh() {
        pullRetractJob?.cancel()
        pullRetractJob = null
        pullRefreshDistancePx = 0f
        pullThresholdHapticFired = false
        // Deliberately does NOT touch the menu. This also runs from the tab-switch and load-complete
        // cleanups, and the New Tab action switches tabs — collapsing from here would cancel and
        // restart the menu's spring partway through its own ending. The release handler owns that.
    }

    // The horizontal axis gets the same treatment as the vertical one on release: it tracked the
    // finger the whole way across, so snapping it home would end that tracking on a cut while the
    // menu is still visibly fading out. Springs on the same curve as the vertical retract so both
    // axes come to rest together.
    fun settlePullMenuDx() {
        if (pullMenuDxPx == 0f) return
        pullMenuRecentreJob?.cancel()
        pullMenuRecentreJob = scope.launch {
            motionEngine.animate("pull-x",
                initialValue = pullMenuDxPx,
                targetValue = 0f,
                animationSpec = TangentMotion.PullRetract,
            ) { value, _ -> pullMenuDxPx = value }
            pullMenuDxPx = 0f
        }
    }

    // Release below the refresh threshold: the pull tracked the finger 1:1 the whole way down, so
    // snapping the distance to zero here would end that tracking on a cut. Spring it back instead
    // (the finger has stopped, so a decelerate-only settle from the current offset is what the
    // rubber-band already implies), and let the line unmount when it actually reaches nothing.
    fun settlePullRefresh() {
        pullThresholdHapticFired = false
        if (pullRefreshDistancePx <= 0f) return
        pullRetractJob?.cancel()
        pullRetractJob = scope.launch {
            motionEngine.animate("pull-distance",
                initialValue = pullRefreshDistancePx,
                targetValue = 0f,
                animationSpec = TangentMotion.PullRetract,
            ) { value, _ -> pullRefreshDistancePx = value }
            pullRefreshDistancePx = 0f
        }
    }

    fun snapBarCollapseProgress(target: Float) {
        if (abs(barCollapseProgressTarget - target) < 0.003f) return
        barCollapseProgressTarget = target
        barCollapseAnimationJob?.cancel()
        barCollapseProgress = target
    }

    fun animateBarCollapseProgress(target: Float, releaseVelocity: Float? = null) {
        if (releaseVelocity == null && abs(barCollapseProgressTarget - target) < 0.003f) return
        barCollapseProgressTarget = target
        barCollapseAnimationJob?.cancel()
        barCollapseAnimationJob = scope.launch {
            barCollapseProgressState.snapTo(barCollapseProgress)
            motionEngine.animate(barCollapseProgressState,
                targetValue = target,
                animationSpec = TangentMotion.BarCollapse,
                initialVelocity = releaseVelocity,
            ) {
                barCollapseProgress = value
            }
        }
    }

    fun handlePageScrollDelta(delta: Float) {
        if (isReaderActiveOnSelectedTab || !userScrollInteractionActive) return
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

    // Predict the next scroll delta from recent velocity. Uses a weighted average of the last
    // up-to-3 samples, discarding any older than ScrollVelocityMaxAgeMs. The prediction is the
    // average velocity multiplied by one refresh cycle (~8.3 ms at 120 Hz). This is intentionally
    // conservative: a small predicted delta keeps the bar visually smooth during a frame drop
    // without overshooting (the real delta will correct on the next frame).
    fun predictScrollDelta(): Float {
        val now = android.os.SystemClock.uptimeMillis()
        var weightedSum = 0f
        var weightSum = 0f
        for (i in 0 until scrollVelocitySampleCount) {
            val idx = (scrollVelocityIndex - 1 - i + 3) % 3
            val age = now - scrollVelocityTimestamps[idx]
            if (age > ScrollVelocityMaxAgeMs) break
            // Exponential decay: most recent sample has weight 1, older samples decay.
            val weight = 1f / (1f + age / 16f)
            weightedSum += scrollVelocityWindow[idx] * weight
            weightSum += weight
        }
        if (weightSum <= 0f) return 0f
        val avgVelocity = weightedSum / weightSum
        // Predict one refresh cycle ahead. At 120 Hz, 8.3 ms; at 60 Hz, 16.7 ms. We use a
        // conservative 8 ms so the prediction doesn't overshoot when the next real event arrives.
        return avgVelocity
    }

    fun enqueuePageScrollDelta(delta: Float) {
        if (isReaderActiveOnSelectedTab) return
        pendingPageScrollDelta[0] += delta
        // Track scroll velocity for input prediction (Chrome Input Prediction equivalent).
        // Keep the last 3 samples in a ring buffer for a velocity estimate that's resilient to
        // single-sample outliers while remaining responsive to direction changes.
        val now = android.os.SystemClock.uptimeMillis()
        scrollVelocityWindow[scrollVelocityIndex] = delta
        scrollVelocityTimestamps[scrollVelocityIndex] = now
        scrollVelocityIndex = (scrollVelocityIndex + 1) % 3
        if (scrollVelocitySampleCount < 3) scrollVelocitySampleCount++
        if (pageScrollFrameJob != null) return
        pageScrollFrameJob = scope.launch {
            val frameStartNanos = withFrameNanos { }
            // Chrome Input Framer equivalent: wait a short grace period (1/3 refresh cycle) for
            // late-arriving scroll events so they can still be included in this frame instead of
            // being delayed to the next one. At 120 Hz that's ~2.8 ms; at 60 Hz ~5.6 ms.
            delay(ScrollFrameGracePeriodMs)
            // If a new delta arrived during the grace period, flush it too (it was accumulated
            // into pendingPageScrollDelta by the caller). We don't re-launch a frame job; the
            // accumulated delta will be picked up below.
            val pendingDelta = pendingPageScrollDelta[0]
            pendingPageScrollDelta[0] = 0f
            pageScrollFrameJob = null
            if (pendingDelta != 0f) {
                handlePageScrollDelta(pendingDelta)
            } else if (userScrollInteractionActive && scrollVelocitySampleCount >= 2) {
                // No real scroll event arrived this frame (frame drop / late delivery). Predict
                // the next delta from recent velocity so the bar collapse stays visually smooth
                // instead of stuttering for one refresh cycle.
                val predicted = predictScrollDelta()
                if (predicted != 0f) {
                    handlePageScrollDelta(predicted)
                }
            }
        }
    }

    // Reader-mode pill auto-hide. Scrolling the article down past a small slop hides the pill fully;
    // scrolling up reveals it again. A spring keeps it from snapping on tiny jitter.
    fun handleReaderScrollDelta(delta: Float) {
        if (delta == 0f) return
        // A fresh scroll delta pre-empts any in-flight settle so the pill tracks the finger again.
        readerBarSettleJob?.cancel()
        readerBarSettleJob = null
        // The full hide travel is the pill's measured bottom region plus a margin so it clears the
        // screen (mirrors pillReaderHideOffsetPx below). Map the px delta into that 0..1 range and
        // accumulate, clamped — positive delta (reading down) hides, negative (reading up) reveals.
        val hideDistancePx = bottomChromeInsetPx + with(density) { 48.dp.toPx() }
        if (hideDistancePx <= 0f) return
        readerBarHidden = (readerBarHidden + delta / hideDistancePx).coerceIn(0f, 1f)
    }

    // On finger-lift / fling-end the pill settles to whichever edge it's closest to, so it never
    // rests half-hidden. Animates the plain float directly (no Animatable) to keep the drag
    // hot-path above allocation-free.
    fun settleReaderBar() {
        val target = if (readerBarHidden >= 0.5f) 1f else 0f
        if (readerBarHidden == target) return
        readerBarSettleJob?.cancel()
        readerBarSettleJob = scope.launch {
            motionEngine.animate("reader-bar",
                initialValue = readerBarHidden,
                targetValue = target,
                animationSpec = BrowserPhysics.Progress,
            ) { value, _ -> readerBarHidden = value }
        }
    }

    // Reader owns its own hide/reveal gesture. Discard the webpage's pending scroll and
    // collapse state at the handoff, including automatic reader entry on a scrolled page.
    LaunchedEffect(isReaderActiveOnSelectedTab, selectedTabId) {
        readerBarSettleJob?.cancel()
        readerBarSettleJob = null
        motionEngine.interrupt("reader-bar")
        if (isReaderActiveOnSelectedTab) {
            pageScrollFrameJob?.cancel()
            pageScrollFrameJob = null
            pendingPageScrollDelta[0] = 0f
            userScrollInteractionActive = false
            scrollVelocitySampleCount = 0
            expandUrlBar()
        }
        readerBarSettleJob = scope.launch {
            motionEngine.animate("reader-bar", readerBarHidden, 0f,
                animationSpec = BrowserPhysics.Progress) { value, _ -> readerBarHidden = value }
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
                snap.requestTabSwitchSnapshot()
            }
            switchAnimationJob?.cancel()
            switchAnimationJob = null
            cancelLeavingSnapshot()
            snap.clearStickyCover()
            predictiveBackAnimationJob?.cancel()
            predictiveBackAnimationJob = null
            predictiveBackOffsetPx = 0f
            snap.clearPredictiveBackCover()
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
                    tangentHaptics.commitStrong()
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
            snap.resetTabSwitchSnapshot()
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
                pinnedIncomingTabId =
                    viewModel.onCreateTabFromEdge(selectTab = false, groupId = effectiveSpaceId)
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
            snap.resetTabSwitchSnapshot()
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
                motionEngine.animate("tab-switch",
                    initialValue = current,
                    targetValue = target,
                    initialVelocity = velocity,
                    animationSpec = BrowserPhysics.Pixels,
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
                motionEngine.animate("tab-switch",
                    initialValue = current,
                    targetValue = 0f,
                    initialVelocity = velocity,
                    animationSpec = BrowserPhysics.Pixels,
                ) { v, _ -> offsetX = v }
                snap.resetTabSwitchSnapshot()
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
                groupId = currentSpaceId.value,
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
            groupId = currentSpaceId.value,
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
        snap.clearStickyCover()
        snap.clearPredictiveBackCover()
        snap.resetTabSwitchSnapshot()
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
            motionEngine.animate("tab-switch",
                initialValue = 0f,
                targetValue = -pageStride,
                initialVelocity = 0f,
                animationSpec = BrowserPhysics.Pixels,
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

    fun closeGrid(animateBack: Boolean, releaseVelocity: Offset? = null, afterClose: () -> Unit = {}) {
        gridAnimationJob?.cancel()
        gridGestureActive = false
        gridDragPx = 0f
        gridDragProgress = 0f
        gridOpen = false
        gridCommitFromPositions = null
        gridCommitFromProgress = 0f
        gridCancelFromProgress = 0f
        GridFlickArc.arc = null
        // Keep gridEngaged true past the end of the animation so the tab cards (at progress=0,
        // fullscreen position, scale=1) cover the live Gecko view during the gap between the
        // animation finishing and the deferred tab selection in afterClose.
        if (animateBack) gridWaitingForAfterClose = true
        gridAnimationJob = scope.launch {
            if (animateBack) {
                // Spring the zoom-out closed so it matches the spring-driven open/settle (and the
                // pill) instead of a mechanical fixed-duration tween. Both axes share the one spec
                // so carousel→columns stays locked to the vertical progress, same as openGrid.
                val anim = TangentMotion.GridClose
                coroutineScope {
                    launch { motionEngine.animate(gridProgressState, 0f, animationSpec = anim, initialVelocity = releaseVelocity?.y) }
                    launch { motionEngine.animate(gridXProgressState, 0f, animationSpec = anim, initialVelocity = releaseVelocity?.x) }
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

    fun openGrid(initialVelocityProgressPerSec: Float, fromProgress: Float? = null) {
        // Button entry has no finger-release pose. Capture the visible carousel instead, so
        // snapshots and captions use the same hero/fan-in path as swipe entry, including when
        // the button interrupts a horizontal switch. Never request a bitmap on the first frame.
        val buttonEntry = fromProgress == null && gridProgressState.value <= 0.001f && !gridGestureActive
        if (buttonEntry) {
            val stride = contentSize.width.toFloat() + tabSwitchGapPx
            val midSwitch = leavingSnapshotJob != null
            val selectedX = if (midSwitch) incomingSnapshotOffsetX else offsetX
            val selectedIndex = tabsList.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
            gridCommitFromPositions = tabsList.mapIndexed { index, tab ->
                val x = if (midSwitch && tab.id == leavingSnapshotTabId) leavingSnapshotOffsetX
                    else selectedX + (index - selectedIndex) * stride
                Triple(x, 0f, 1f)
            }
            gridCommitFromProgress = 0.0001f // enables release geometry; its independent spring starts at 0
            gridCancelFromProgress = 0f
            GridFlickArc.arc = null
            tabSwitchCurrentSnapshot = if (midSwitch) incomingSnapshotBitmap
                ?: snapshotFor(selectedTabId, currentUrl) else currentPageSnapshot
            switchAnimationJob?.cancel()
            switchAnimationJob = null
            cancelLeavingSnapshot()
            offsetX = 0f
            gridHandoffSelectedTabId = null
            gridHandoffLeavingTabId = null
            gridHandoffSelectedOffsetX = 0f
            gridHandoffLeavingOffsetX = 0f
        }
        gridAnimationJob?.cancel()
        gridGestureActive = false
        gridOpen = true
        gridDragPx = 0f
        gridWaitingForAfterClose = false
        gridAnimationJob = scope.launch {
            // Seed the spring from the finger's release position so there's no pop.
            // Only a finger release supplies a new pose. A button reversing a close keeps
            // both rendered axes and lets the engine inherit their individual velocities.
            if (buttonEntry) {
                gridProgressState.snapTo(0f)
                gridXProgressState.snapTo(0f)
            } else if (fromProgress != null) {
                gridProgressState.snapTo(fromProgress)
                gridXProgressState.snapTo(0f)
            }
            // Smooth, lightly-damped deceleration that carries the finger's release velocity —
            // closer to Safari's settle than a stiff snap. Crucially, the X (carousel→columns)
            // spring gets the same spec and normalized velocity: X travels 0→1 while Y only
            // travels fromProgress→1. Equal raw velocities would make their phases drift.
            val launchVelocity = if (buttonEntry) 0f else fromProgress?.let { initialVelocityProgressPerSec }
            val s = TangentMotion.GridOpen
            coroutineScope {
                launch {
                    motionEngine.animate(gridProgressState,
                        1f,
                        initialVelocity = launchVelocity,
                        animationSpec = s,
                    )
                }
                launch {
                    motionEngine.animate(gridXProgressState,
                        1f,
                        initialVelocity = if (buttonEntry) 0f else launchVelocity?.let { it / (1f - fromProgress!!).coerceAtLeast(0.0001f) },
                        animationSpec = s,
                    )
                }
                GridFlickArc.arc?.let { arc ->
                    launch {
                        // All cards share elapsed time; background cards damp their throw by depth.
                        motionEngine.animate(arc.progress, 1f,
                            animationSpec = tween(
                                com.firebolt9907.tangentbrowser.ui.grid.FlickReleaseArc.DurationMillis,
                                easing = LinearEasing,
                            ),
                            initialVelocity = 0f,
                        )
                    }
                }
            }
            gridDragProgress = 0f
            gridCommitFromProgress = 0f
            gridCancelFromProgress = 0f
            gridCommitFromPositions = null
            GridFlickArc.arc = null
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
            val s = TangentMotion.GridSettle
            coroutineScope {
                launch { motionEngine.animate(gridProgressState, target, animationSpec = s) }
                launch { motionEngine.animate(gridXProgressState, target, animationSpec = s) }
            }
            gridDragProgress = 0f
            gridAnimationJob = null
        }
    }

    // Single 2D pill gesture: horizontal motion drives the carousel offsetX (existing behavior),
    // vertical-up motion simultaneously drives gridDragPx → gridProgressState. Both run together
    // so the user can blend swipe-to-switch and swipe-up-to-grid without an axis lock.
    val onPillDragStart: () -> Unit = {
        motionEngine.interrupt("tab-switch")
        switchAnimationJob?.cancel()
        switchAnimationJob = null
        motionEngine.interrupt(gridProgressState)
        motionEngine.interrupt(gridXProgressState)
        gridWaitingForAfterClose = false
        if (gridNewTabSelected) {
            // The new tab is created only after close completes; a re-grab cancels that action.
            gridNewTabSelected = false
            pendingActiveBarState = null
        }
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
            // Continue horizontal dragging in the incoming tab's coordinate system too.
            offsetX = incomingSnapshotOffsetX
            tabSwitchCurrentSnapshot = incomingSnapshotBitmap
                ?: snapshotFor(incomingSnapshotTabId, tabsList.firstOrNull { it.id == incomingSnapshotTabId }?.content?.url)
            commitPendingGeckoSelection()
            leavingSnapshotJob?.cancel()
            leavingSnapshotJob = null
            leavingSnapshotBitmap = null
            incomingSnapshotBitmap = null
            commitAnimNewTab = false
            snap.clearStickyCover()
            snap.clearPredictiveBackCover()
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
                snap.requestTabSwitchSnapshot()
            }
        }
    }

    val onPillDragDelta: (androidx.compose.ui.geometry.Offset) -> Unit = { delta ->
        if (!barCollapseDragActive && (tabSwitchingEnabled || gridGestureActive)) {
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
                            tangentHaptics.gridEngage()
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
            // A vertical chrome gesture owns the release. Discard lateral drift and invalidate
            // the pending capture purpose so a late thumbnail cannot restore the drag cover.
            offsetX = 0f
            snap.resetTabSwitchSnapshot()
            val releaseVelocity = (downwardVelocity / BarCollapseSnapThresholdPx).coerceIn(-8f, 8f)
            val commit = barCollapseDragPx >= BarCollapseSnapThresholdPx * 0.5f || downwardVelocity >= 800f
            if (commit) {
                barCollapseCompact = true
                animateBarCollapseProgress(1f, releaseVelocity)
                barCollapsePx = BarCollapseSnapThresholdPx
            } else {
                barCollapseCompact = false
                animateBarCollapseProgress(0f, releaseVelocity)
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
            val captureCarouselShift = if (gridHandoffSelectedTabId != null) {
                gridHandoffSelectedOffsetX + offsetX
            } else {
                offsetX
            }
            val fingerX = containerW / 2f + captureCarouselShift
            val selIdx = tabsList.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)
            return tabsList.mapIndexed { i, _ ->
                val pose = deckDragCardPose(gridDragProgress, gridDragPx, containerW, containerH,
                    fingerX, containerW + tabSwitchGapPx, i - selIdx, tabViewMode)
                Triple(pose.tx, pose.ty, pose.scale)
            }
        }
        if (gridWantsCommit) {
            // Subtle confirmation that the grid has "caught" and is opening (Safari-like). Skip it
            // if the crossing haptic already fired during the drag so a slow drag-past-threshold
            // doesn't double-buzz; a fast velocity flick that never crossed still gets one buzz.
            if (!gridThresholdHapticFired) {
                tangentHaptics.gridEngage()
            }
            gridThresholdHapticFired = false
            // Capture finger-tracking positions BEFORE resetting offsetX.
            // These seed the commit animation so tabs spring from wherever they were
            // under the finger to their grid cells, rather than from carousel positions.
            gridCommitFromPositions = captureGridReleasePositions()
            gridCommitFromProgress = gridDragProgress.coerceAtLeast(0.0001f)
            gridCancelFromProgress = 0f
            // Match the drag's bottom-edge and scale velocities, then coast into the slot.
            // Bound pathological tracker spikes by screen size, not a fixed pixel distance.
            GridFlickArc.arc = if (tabViewMode != TabViewMode.GRID &&
                contentSize.height > 0 && gridCommitFromPositions != null) {
                val speed = upwardVelocity.coerceIn(-contentSize.height * 3f, contentSize.height * 3f)
                val scale = deckDragScale(gridDragProgress, gridDragPx, contentSize.height * DeckTravelFrac)
                val nextScale = deckDragScale(
                    ((gridDragPx + 1f) / travelPx).coerceIn(0f, 1f),
                    gridDragPx + 1f, contentSize.height * DeckTravelFrac,
                )
                com.firebolt9907.tangentbrowser.ui.grid.FlickReleaseArc(
                    upwardVelocityPxS = speed,
                    scaleVelocityPerSec = ((nextScale - scale) * speed)
                        .coerceAtLeast(-(scale - 0.22f).coerceAtLeast(0f) / 0.15f),
                    containerWidth = contentSize.width.toFloat(),
                    containerHeight = contentSize.height.toFloat(),
                    carouselScaleAtRelease = scale,
                    backgroundScaleVelocityPerSec = if (tabViewMode == TabViewMode.DECK) {
                        val travel = contentSize.height * DeckTravelFrac
                        val current = deckDragCardScale(gridDragProgress, gridDragPx, travel, true)
                        val next = deckDragCardScale(((gridDragPx + 1f) / travelPx).coerceIn(0f, 1f),
                            gridDragPx + 1f, travel, true)
                        ((next - current) * speed)
                            .coerceAtLeast(-(current - 0.22f).coerceAtLeast(0f) / 0.15f)
                    } else ((nextScale - scale) * speed)
                        .coerceAtLeast(-(scale - 0.22f).coerceAtLeast(0f) / 0.15f),
                )
            } else null
            // Vertical wins: open grid. Snap any horizontal carousel motion back to 0 so the
            // selected tab stays selected — Safari's behavior.
            offsetX = 0f
            snap.resetTabSwitchSnapshot()
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
                GridFlickArc.arc = null
            }
            gridGestureActive = false
            gridDragPx = 0f
            gridDragProgress = 0f
            gridAnimationJob?.cancel()
            gridAnimationJob = scope.launch {
                if (cancelFromProgress > 0f) {
                    val travelPx = gridTravelPx()
                    val initialVelocityProgressPerSec =
                        if (travelPx > 0f) (-velocity.y / travelPx) else 0f
                    gridProgressState.snapTo(cancelFromProgress)
                    gridXProgressState.snapTo(0f)
                    val s = TangentMotion.GridSettle
                    motionEngine.animate(gridProgressState,
                        0f,
                        initialVelocity = initialVelocityProgressPerSec,
                        animationSpec = s,
                    )
                    gridCommitFromPositions = null
                    gridCancelFromProgress = 0f
                    GridFlickArc.arc = null
                } else {
                    // No grid progress accumulated — clear any stale animation state.
                    gridProgressState.snapTo(0f)
                    gridXProgressState.snapTo(0f)
                    gridCommitFromPositions = null
                    gridCommitFromProgress = 0f
                    gridCancelFromProgress = 0f
                    GridFlickArc.arc = null
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
            snap.clearStickyCover()
            snap.clearPredictiveBackCover()
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
                motionEngine.animate("back",
                    current,
                    width,
                    initialVelocity = velocity,
                    animationSpec = BrowserPhysics.Pixels,
                ) { value, _ ->
                    predictiveBackOffsetPx = value
                }
                stickyBackCoverSnapshot = activePredictiveBackSnapshot ?: predictiveBackSnapshot
                stickyBackCoverUrl = targetUrl
                resetPredictiveBackGesture()
                commitGoBack(selectedTabId)
            } else {
                // Cancel: spring back to rest with the same velocity continuity (critically damped so
                // any residual outward momentum settles without oscillating).
                motionEngine.animate("back",
                    current,
                    0f,
                    initialVelocity = velocity,
                    animationSpec = BrowserPhysics.Pixels,
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
        // NB: pendingActiveBarState is deliberately NOT cleared here. actualSelectedTabId (collected
        // straight from the store) flips a frame or more before viewModel.state re-derives BarUiState
        // for the new tab. Clearing on this signal would drop the bridge state while `state` still
        // holds the OUTGOING tab's domain, flashing the wrong URL in the pill during rapid successive
        // swipes. The dedicated effect below clears it only once `state` has actually caught up.
        predictiveBackAnimationJob?.cancel()
        predictiveBackAnimationJob = null
        resetPredictiveBackGesture()
        snap.resetTabSwitchSnapshot()
        snap.clearPredictiveBackCover()
        userScrollInteractionActive = false
        scrollVelocitySampleCount = 0
        expandUrlBar()
        resetPullRefresh()
    }

    // Clear the tab-switch bridge state (pendingActiveBarState) only once the real derived bar
    // `state` has caught up to the tab we switched to — i.e. the store's selection has settled
    // (visualSelectedTabId back to null) AND viewModel.state's pageUrl matches the now-selected
    // tab's live URL. Clearing any earlier reveals a `state` still describing the outgoing tab,
    // which is the "wrong domain on successive swipes" flash. Blank new-tab previews are exempt —
    // they are torn down by the editing-mode effect, not by state catch-up.
    LaunchedEffect(state.pageUrl, actualSelectedContent?.url, visualSelectedTabId, pendingActiveBarState) {
        val pending = pendingActiveBarState ?: return@LaunchedEffect
        if (pending.blankNewTabPreview) return@LaunchedEffect
        if (visualSelectedTabId != null) return@LaunchedEffect
        if (state.pageUrl == actualSelectedContent?.url.orEmpty()) {
            pendingActiveBarState = null
        }
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
        val liveTabIds = tabsList.mapTo(HashSet()) { it.id }
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
        scrollVelocitySampleCount = 0
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
                    groupId = currentSpaceId.value,
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
        val tab = tabsList.firstOrNull { it.id == actualSelectedTabId }
        val hasSnapshot = actualSelectedTabId != null &&
            snapshotFor(actualSelectedTabId, tab?.content?.url) != null
        geckoHasPainted = hasSnapshot || tab?.content?.loading == false
    }

    LaunchedEffect(actualSelectedTabId, stickyIncomingTabId) {
        if (actualSelectedTabId != null && actualSelectedTabId == stickyIncomingTabId) {
            delay(700)
            if (actualSelectedTabId == stickyIncomingTabId) {
                snap.fadeOutStickyCover(scope)
            }
        } else if (stickyIncomingTabId != null) {
            delay(250)
            if (actualSelectedTabId != stickyIncomingTabId) {
                snap.fadeOutStickyCover(scope)
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
            snap.clearPredictiveBackCover()
        }
    }

    LaunchedEffect(stickyBackCoverUrl) {
        val targetUrl = stickyBackCoverUrl ?: return@LaunchedEffect
        delay(PredictiveBackCoverMaxMs)
        if (stickyBackCoverUrl == targetUrl) {
            snap.clearPredictiveBackCover()
        }
    }

    TangentBrowserTheme(siteColor = activeSiteThemeColor) {
        BackHandler(enabled = isVideoFullscreen) {
            selectedTabId?.let { components.sessionUseCases.exitFullscreen.invoke(it) }
        }

        PredictiveBackHandler(enabled = predictiveBackGestureEnabled && !gridOpen && !isVideoFullscreen) { progress ->
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
                    snap.clearStickyCover()
                    snap.clearPredictiveBackCover()
                    predictiveBackOffsetPx = (backEvent.progress.coerceIn(0f, 1f) * width)
                    motionEngine.track("back", predictiveBackOffsetPx)
                }

                val width = contentSize.width.toFloat()
                val tabId = selectedTabId
                val targetUrl = previousHistoryUrl
                if (width > 0f && tabId != null) {
                    motionEngine.animate("back",
                        predictiveBackOffsetPx,
                        width,
                        animationSpec = TangentMotion.PredictiveBackCommit,
                    ) { value, _ ->
                        predictiveBackOffsetPx = value
                    }
                    stickyBackCoverSnapshot = activePredictiveBackSnapshot
                    stickyBackCoverUrl = targetUrl
                    resetPredictiveBackGesture()
                    commitGoBack(tabId)
                } else {
                    resetPredictiveBackGesture()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is MotionInterruptedException) throw cancelled
                val current = predictiveBackOffsetPx
                try {
                    if (current > 0f) {
                        motionEngine.animate("back",
                            current,
                            0f,
                            animationSpec = TangentMotion.PredictiveBackCancel,
                        ) { value, _ ->
                            predictiveBackOffsetPx = value
                        }
                    } else {
                        predictiveBackOffsetPx = 0f
                    }
                } finally {
                    resetPredictiveBackGesture()
                    snap.clearPredictiveBackCover()
                }
            }
        }

        // Predictive back in the tab switcher: pulling back zooms the deck toward the selected
        // card so you can see which tab you'll land on before committing, then closes into it on
        // release — or springs the deck back fully open if you let go early. On 3-button nav the
        // flow emits nothing and completes straight to the commit branch, so Back still closes the
        // grid. Both grid springs are driven directly by snapTo inside this single collect
        // coroutine (not a per-frame scope.launch), so invariant #4's "no snapTo on the drag hot
        // path" — which forbids per-frame coroutine launches — is respected. gridGestureActive
        // stays false so the render reads the Animatable progress/xProgress and the cards zoom from
        // their column layout into the selected card, rather than taking the carousel-morph path.
        PredictiveBackHandler(enabled = gridOpen && !isVideoFullscreen) { progress ->
            // How far the preview zoom goes at full drag: not all the way to 0 (a committed close),
            // so cancel still has somewhere to spring back from and the selected card stays legible.
            val gridBackPreviewFloor = 0.12f
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            fun releaseVelocity(): Offset {
                val velocity = tracker.calculateVelocity()
                return Offset(velocity.x.coerceIn(-5f, 5f), velocity.y.coerceIn(-5f, 5f))
            }
            try {
                gridAnimationJob?.cancel()
                gridAnimationJob = null
                motionEngine.interrupt(gridProgressState)
                motionEngine.interrupt(gridXProgressState)
                val startY = gridProgressState.value
                val startX = gridXProgressState.value
                tracker.addPosition(android.os.SystemClock.uptimeMillis(), Offset(startX, startY))
                progress.collect { backEvent ->
                    val p = backEvent.progress.coerceIn(0f, 1f)
                    // A back gesture can start while overview opening is still settling.
                    val remaining = 1f - (1f - gridBackPreviewFloor) * p
                    gridProgressState.snapTo(startY * remaining)
                    gridXProgressState.snapTo(startX * remaining)
                    tracker.addPosition(android.os.SystemClock.uptimeMillis(),
                        Offset(startX * remaining, startY * remaining))
                }
                // Committed: finish the zoom into the selected card (closeGrid springs from the
                // current preview position down to 0 and re-selects the live tab).
                tangentHaptics.gridEngage()
                closeGrid(animateBack = true, releaseVelocity = releaseVelocity())
            } catch (cancelled: CancellationException) {
                if (cancelled is MotionInterruptedException) throw cancelled
                val velocity = releaseVelocity()
                // Cancelled: spring the deck back fully open from wherever the preview left it.
                gridAnimationJob?.cancel()
                gridAnimationJob = scope.launch {
                    val s = TangentMotion.GridOpen
                    coroutineScope {
                        launch { motionEngine.animate(gridProgressState, 1f, animationSpec = s, initialVelocity = velocity.y) }
                        launch { motionEngine.animate(gridXProgressState, 1f, animationSpec = s, initialVelocity = velocity.x) }
                    }
                    gridAnimationJob = null
                }
            }
        }

        BackHandler(enabled = selectedContent?.canGoBack == true && !predictiveBackGestureEnabled && !gridOpen && !isVideoFullscreen) {
            commitGoBack(selectedTabId)
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
            tabsList.firstOrNull { it.id == selectedTabId }?.parentId
        val selectedTabHasLiveParent = selectedTabParentId != null &&
            selectedTabParentId != selectedTabId &&
            tabsList.any { it.id == selectedTabParentId }
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
          // Publish the system-tint color to every page-snapshot draw site (grid/deck cards,
          // tab-switch covers, scaled backdrop) so they overlay the same tint the live page shows.
          CompositionLocalProvider(LocalSiteTint provides siteTintColor) {
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
                // Reserve extra dynamic-viewport space for the persistent archive scrubber while it's
                // showing on the selected tab, so page content scrolls under it (like it does the
                // pill) instead of hiding behind it. Collected here (before ContentHost) so the inset
                // is known at bind time; the archive bar itself reads its own session lower down.
                val archiveSessionsForInset by com.firebolt9907.tangentbrowser.ui.archive
                    .WaybackManager.instance.sessions.collectAsStateWithLifecycle()
                val archiveInsetPx = if (
                    selectedTabId != null && archiveSessionsForInset[selectedTabId] != null
                ) with(density) { 72.dp.roundToPx() } else 0
                // The "Open in app" banner is persistent too: reserve the same footprint while it's
                // active so page content scrolls under it (summed with the archive scrubber's on the
                // rare chance both show at once).
                val openInAppInsetPx = if (openInAppActive) with(density) { 72.dp.roundToPx() } else 0
                val persistentBannerInsetPx = archiveInsetPx + openInAppInsetPx
                ContentHost(
                    engine = components.engine,
                    engineSession = selectedEngineSession,
                    topChromeInsetPx = if (isVideoFullscreen) 0 else topChromeInsetPx,
                    bottomChromeInsetPx = if (isVideoFullscreen || (isKeyboardVisible && state.mode != BarMode.Editing)) 0 else bottomChromeInsetPx,
                    // Extra dvh reserve for the persistent archive scrubber (0 when not in archive mode
                    // or when the pill/engine chrome is suppressed for fullscreen/keyboard).
                    persistentBannerInsetPx = if (
                        isVideoFullscreen || (isKeyboardVisible && state.mode != BarMode.Editing)
                    ) 0 else persistentBannerInsetPx,
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
                    zappedSelectors = zappedSelectors,
                    onTabPainted = { paintedTabId ->
                        if (paintedTabId == actualSelectedTabId) geckoHasPainted = true
                        if (paintedTabId == actualSelectedTabId && paintedTabId == stickyIncomingTabId) {
                            scope.launch {
                                delay(80)
                                if (paintedTabId == actualSelectedTabId && paintedTabId == stickyIncomingTabId) {
                                    snap.fadeOutStickyCover(scope)
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
                                    snap.clearPredictiveBackCover()
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
                        val liveTabIds = tabsList.mapTo(HashSet()) { it.id }
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
                    pullMaxDistancePx = pullRefreshMaxDistancePx,
                    onTopOverscrollPull = { distance, dx ->
                        // A new pull owns the distance value outright — kill any retract still
                        // running from the previous release before writing to it.
                        if (pullRetractJob != null) {
                            pullRetractJob?.cancel()
                            pullRetractJob = null
                        }
                        pullRefreshDistancePx = distance.coerceIn(0f, pullRefreshMaxDistancePx)
                        motionEngine.track("pull-distance", pullRefreshDistancePx)
                        if (pullMenuEnabled) {
                            // Same ownership rule as the vertical retract above: a live pull owns
                            // the value outright, so kill any recentre still running from the last
                            // release rather than letting the two write to it in alternation.
                            if (pullMenuRecentreJob != null) {
                                pullMenuRecentreJob?.cancel()
                                pullMenuRecentreJob = null
                            }
                            if (pullMenuCollapseJob != null) {
                                pullMenuCollapseJob?.cancel()
                                pullMenuCollapseJob = null
                            }
                            pullMenuDxPx = dx
                            motionEngine.track("pull-x", pullMenuDxPx)
                            pullMenuPresence = pullMenuRevealFor(pullRefreshDistancePx)
                            motionEngine.track("pull-presence", pullMenuPresence)
                            if (pullMenuPresence > 0f) pullMenuComposed = true
                            val picked = nextPullAction(
                                current = pullMenuSelection,
                                dx = dx,
                                slotWidthPx = pullMenuSlotWidthPx,
                                readerAvailable = pullMenuReaderAvailable,
                            )
                            if (picked != pullMenuSelection) {
                                pullMenuSelection = picked
                                tangentHaptics.detent()
                            }
                        }
                        // Buzz exactly once when the pull first crosses the refresh threshold, and
                        // re-arm the haptic if the user drags back below it. Below the threshold, a
                        // gently growing rumble tracks the pull so the arm tick feels earned.
                        if (pullRefreshDistancePx >= pullRefreshThresholdPx) {
                            if (!pullThresholdHapticFired) {
                                pullThresholdHapticFired = true
                                tangentHaptics.armThreshold()
                            }
                        } else {
                            pullThresholdHapticFired = false
                            pullRamp.update(pullRefreshDistancePx / pullRefreshThresholdPx)
                        }
                    },
                    onTopOverscrollRelease = { distance, _ ->
                        pullRamp.reset()
                        // Commit to what was actually highlighted, rather than re-deriving from the
                        // release position: the hysteretic mapping is path-dependent, so recomputing
                        // it here could fire a different action than the one under the finger.
                        val action = if (pullMenuEnabled) pullMenuSelection else PullAction.Refresh
                        var triggered = false
                        if (distance >= pullRefreshThresholdPx) {
                            when (action) {
                                PullAction.Refresh ->
                                    if (selectedContent?.loading != true) {
                                        selectedTabId?.let {
                                            pullRefreshing = true
                                            triggered = true
                                            components.sessionUseCases.reload.invoke(it)
                                        }
                                    }
                                PullAction.NewTab -> {
                                    tangentHaptics.create()
                                    openTabToRight(url = "about:blank", editBlankTab = true)
                                }
                                PullAction.Reader -> selectedTabId?.let { tabId ->
                                    val readerUrl = selectedContent?.url.orEmpty()
                                    val readerHost = siteHostFromUrl(readerUrl)
                                    val turnedOn =
                                        readerManager.toggleReader(tabId, readerUrl, selectedEngineSession)
                                    components.sitePreferencesStore.setReaderPreferred(readerHost, turnedOn)
                                    components.sitePreferencesStore.setReaderAutoDisabled(readerHost, !turnedOn)
                                    tangentHaptics.toggle(turnedOn)
                                }
                            }
                        }
                        // Only a consummated *refresh* hands the line over to its sweeping band, so
                        // the pull fill is replaced rather than retracted. Every other outcome —
                        // including the two non-refresh actions, which have their own UI response —
                        // leaves the line to travel back.
                        if (triggered) resetPullRefresh() else settlePullRefresh()
                        // The menu's own ending, run unconditionally and on the same spring whichever
                        // branch above was taken — this is the whole point of giving it a presence of
                        // its own. A pull that fired a refresh and a pull that fired nothing collapse
                        // identically; the difference is what the page does, not what the menu does.
                        collapsePullMenu()
                        // Let the highlight ride back to centre with the retracting menu instead of
                        // teleporting: the caption re-reads immediately, but the chips travel.
                        pullMenuSelection = PullAction.Refresh
                        settlePullMenuDx()
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
                    themeColorSamplingEnabled = matchSiteThemeColor,
                    siteTintColor = siteTintColor,
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
                    com.firebolt9907.tangentbrowser.ui.content.CrashRestoreOverlay(
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
                // The bar must be allowed to REACH the end before it leaves. Gecko reports progress
                // sparsely, so a bar hidden the instant `loading` goes false (or progress leaves
                // 1..99) is yanked off screen from wherever it happened to be — usually 60–80% — on
                // every single page load. Instead the completion runs it to 1f on the same linear
                // curve and only then fades it out, so the last thing you see is the bar finishing.
                val loadBarProgress = remember { Animatable(0f) }
                var loadBarShown by remember { mutableStateOf(false) }
                LaunchedEffect(selectedTabId) {
                    // A tab switch abandons the old tab's bar; no snap here, the next load start
                    // seeds the value, so the exit fade doesn't collapse to zero mid-dissolve.
                    loadBarShown = false
                }
                LaunchedEffect(pageIsLoading, pageLoadProgress) {
                    val target = (pageLoadProgress / 100f).coerceIn(0f, 1f)
                    when {
                        pageIsLoading && pageLoadProgress in 1..99 -> {
                            if (!loadBarShown || target < loadBarProgress.value) {
                                // Fresh load (or a navigation restarting the count): start from the
                                // reported value rather than sweeping backwards into it.
                                loadBarProgress.snapTo(target)
                                loadBarShown = true
                            } else {
                                // Linear between discrete Gecko progress reports so the bar advances
                                // at constant velocity; FastOutSlowIn would re-accelerate on every
                                // update and stutter.
                                motionEngine.animate(loadBarProgress,
                                    target,
                                    tween(PageLoadStepMs, easing = LinearEasing),
                                )
                            }
                        }
                        loadBarShown -> {
                            if (loadBarProgress.value < 1f) {
                                motionEngine.animate(loadBarProgress,
                                    1f,
                                    tween(PageLoadFinishMs, easing = LinearEasing),
                                )
                            }
                            loadBarShown = false
                        }
                    }
                }
                val showLoadBar = loadBarShown &&
                    !gridEngaged && !findInPageActive && shouldShowUi
                AnimatedVisibility(
                    visible = showLoadBar,
                    // Effects springs, not the fadeIn/fadeOut defaults: the bar is a 2.5 dp hairline
                    // that should be there the moment a load starts and gone the moment it finishes,
                    // not creep in and out over a medium-low spring.
                    enter = fadeIn(ExpressiveMotion.fastEffectsSpec()),
                    exit = fadeOut(ExpressiveMotion.defaultEffectsSpec()),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    LinearProgressIndicator(
                        progress = { loadBarProgress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(2.5.dp),
                        trackColor = Color.Transparent,
                    )
                }
                // Find-in-page bar, pinned under the status bar at the top of the content area.
                // It takes focus the frame it mounts (FindInPageBar's focusRequester), so the
                // keyboard rises on the platform's synced IME animation at the same moment — the
                // bar therefore enters on that same curve and duration (TangentMotion.ImeTrack)
                // rather than the fadeIn/fadeOut default spring, so the two halves of one action
                // arrive together instead of the bar materialising ahead of the keyboard. It also
                // slides out from behind the status bar rather than only fading: a bar that is
                // pinned to an edge should come from that edge.
                AnimatedVisibility(
                    visible = findInPageActive && shouldShowUi,
                    enter = slideInVertically(TangentMotion.ImeTrackOffset) { -it } +
                        fadeIn(TangentMotion.ImeTrack),
                    exit = slideOutVertically(TangentMotion.ImeTrackOffset) { -it } +
                        fadeOut(TangentMotion.ImeTrack),
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
                    // Slide only, critically damped — see [TangentMotion.TakeoverSlide].
                    enter = slideInVertically(TangentMotion.TakeoverSlide) { it },
                    exit = slideOutVertically(TangentMotion.TakeoverSlide) { it },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = offsetX },
                ) {
                    lastReaderArticle.value?.let { article ->
                    val context = LocalContext.current
                    com.firebolt9907.tangentbrowser.ui.reader.ReaderView(
                        article = article,
                        theme = readerTheme,
                        fontSizePercent = readerFontSize,
                        fontName = readerFontName,
                        modifier = Modifier.fillMaxSize(),
                        topPadding = with(density) { topChromeInsetPx.toDp() },
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        onScrollDelta = { delta -> handleReaderScrollDelta(delta) },
                        onScrollSettle = { settleReaderBar() },
                        onSnapshot = { bitmap ->
                            selectedTabId?.let { id ->
                                if (readerManager.isReaderActive(id)) readerTabSnapshots[id] = bitmap
                            }
                        },
                        onLinkClick = { href ->
                            readerManager.openReaderLink(context, selectedTabId, selectedEngineSession, href)
                        },
                        onLinkLongPress = { href, origin, srcRect, bmp ->
                            // Peek out of a long-pressed reader link. Only http(s) targets are
                            // peekable (LinkPeekCard requires them); other schemes fall through to a
                            // tap. Reader content isn't a live GeckoView, so the morph pixels come
                            // from the reader's own captured surface (bmp), cropped to the link's rect.
                            val low = href.trim().lowercase()
                            if (low.startsWith("http://") || low.startsWith("https://")) {
                                peekOrigin = origin
                                peekSourceRect = srcRect
                                peekCaptureBitmap = null
                                peekElementBitmap = if (bmp != null && srcRect != null) {
                                    cropBitmapToRect(
                                        bmp, srcRect, bmp.width.toFloat(), bmp.height.toFloat()
                                    )
                                } else null
                                preparePeekEngine()
                                peekUrl = href
                                tangentHaptics.longPress()
                            }
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
                    // Depth card-stack (wide screens only): as the swipe progresses the leaving page
                    // recedes + dims while the incoming page rises from a smaller, dimmed card to full
                    // size. All reads happen inside graphicsLayer/drawWithContent (draw phase) so the
                    // per-frame `offsetX` never recomposes this subtree — same discipline as the flat
                    // slide it enhances. `overlayActive` stays true through the commit spring, so the
                    // release animation inherits the identical curve (no visual pop). Portrait keeps
                    // the flat slide.
                    val adjacentUrl = if (adjacentDirection > 0) {
                        previousTab?.content?.url
                    } else {
                        nextTab?.content?.url
                    }
                    HorizontalSwitchOverlay(
                        offsetXProvider = { offsetX },
                        contentSize = contentSize,
                        tabSwitchGapPx = tabSwitchGapPx,
                        depthSwipe = isWideLayout,
                        adjacentSnapshot = adjacentSnapshot,
                        adjacentUrl = adjacentUrl,
                        showNewTabPane = showNewTabPane,
                        currentUrl = currentUrl,
                        movingCurrentSnapshot = movingCurrentSnapshot,
                        switchPaneShape = switchPaneShape,
                        topPadDpFor = ::topPadDpFor,
                    )
                }
                if (predictiveBackActive) {
                    PredictiveBackOverlay(
                        predictiveBackOffsetPxProvider = { predictiveBackOffsetPx },
                        contentWidth = contentWidth,
                        easing = predictiveBackEasing,
                        primarySnapshot = activePredictiveBackSnapshot ?: predictiveBackSnapshot,
                        gestureCurrentSnapshot = gestureCurrentSnapshot,
                    )
                }
                SwitchSnapshotCovers(
                    snap = snap,
                    contentSize = contentSize,
                    tabSwitchGapPx = tabSwitchGapPx,
                    switchPaneShape = switchPaneShape,
                )
                if (gridEngaged) {
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
                            val host = siteHostFromUrl(tab.content.url)
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
                        // full TangentRoot recomposition and GridTab allocation on every 120 Hz
                        // pointer sample.
                        tabsList.mapIndexed { i, tab ->
                            val isHandoffLeaving = gridHandoffSelectedTabId != null && tab.id == gridHandoffLeavingTabId
                            val browsePadPx = when {
                                isHandoffLeaving -> gridHandoffLeavingTopPadPx
                                gridHandoffSelectedTabId != null && i == newSelIdx -> gridHandoffSelectedTopPadPx
                                else -> tabPadPxMap[tab.id] ?: 0
                            }
                            // For an archived tab, present the ORIGINAL page's URL/title (and, via
                            // the same key, its real favicon) so the card reads like the live site;
                            // an archive badge marks it as time-travelled.
                            val wb = com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                            val isArchiveTab = wb.isArchiveUrl(tab.content.url)
                            val gridUrl = if (isArchiveTab) {
                                wb.originalFromArchiveUrl(tab.content.url) ?: tab.content.url
                            } else {
                                tab.content.url
                            }
                            GridTab(
                                id = tab.id,
                                url = gridUrl,
                                title = tab.content.title.ifBlank { hostFromUrl(gridUrl) },
                                isArchive = isArchiveTab,
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
                            // Keep the new tab inside the active Space (otherwise it lands in Personal
                            // and vanishes from the switcher it was created in) and inherit that Space's
                            // default container.
                            val newTabId =
                                viewModel.onCreateTabFromEdge(selectTab = false, groupId = effectiveSpaceId)
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
                        val tappedTab = tabsList.firstOrNull { it.id == tabId }
                        if (tabId != selectedTabId) {
                            visualSelectedTabId = tabId
                            // Clear the old tab's gesture-local snapshot so the close
                            // animation shows the tapped tab's own cached snapshot.
                            snap.resetTabSwitchSnapshot()
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
                    // Container swatch per tab, so the overview shows which cookie jar each tab uses.
                    // Personal/default (contextId == null) resolves to null → no ring/dot.
                    val gridContainerColorProvider: (String) -> Color? = { tabId ->
                        val ctx = tabsList.firstOrNull { it.id == tabId }?.contextId
                        ctx?.let { id ->
                            gridContainers.firstOrNull { it.contextId == id }?.color?.swatch()
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
                            containerColorProvider = gridContainerColorProvider,
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
                            flickReleaseArc = GridFlickArc.arc,
                            snapshotVersion = snapshotCacheVersionState,
                            snapshotProvider = gridSnapshotProvider,
                            themeColorProvider = gridThemeColorProvider,
                            iconProvider = { tabId ->
                                val tab = tabsList.firstOrNull { it.id == tabId }
                                tab?.content?.url?.let { faviconCache[faviconKeyFor(it)] }
                                    ?: tab?.content?.icon
                            },
                            containerColorProvider = gridContainerColorProvider,
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
                        // Space switcher — pick which local Space the tab overview shows. Sits just
                        // above the overview toolbar ("from the bottom of the tab switcher").
                        run {
                            val groupedIds = remember(spaceGroups) {
                                spaceGroups.flatMapTo(HashSet()) { it.tabIds }
                            }
                            val personalCount = allTabs.count { it.id !in groupedIds }
                            com.firebolt9907.tangentbrowser.ui.grid.SpaceSwitcherStrip(
                                spaces = spaceGroups,
                                activeSpaceId = effectiveSpaceId,
                                personalCount = personalCount,
                                countFor = { id ->
                                    spaceGroups.firstOrNull { it.id == id }?.tabIds?.size ?: 0
                                },
                                onSelectSpace = { spaceId ->
                                    tangentHaptics.select()
                                    activeSpaceId = spaceId
                                    components.globalPreferencesStore.activeSpaceId = spaceId
                                },
                                onNewSpace = {
                                    tangentHaptics.create()
                                    val palette = com.firebolt9907.tangentbrowser.state.TabGroupColor.entries
                                    val color = palette[spaceGroups.size % palette.size]
                                    val id = components.tabGroupsRepository.createGroup("New Space", color)
                                    activeSpaceId = id
                                    components.globalPreferencesStore.activeSpaceId = id
                                },
                                onManageSpaces = {
                                    tangentHaptics.select()
                                    showTabGroups = true
                                },
                            )
                        }
                        GridOverviewControls(
                            grid = grid,
                            syncState = firefoxSyncState,
                            components = components,
                            viewModel = viewModel,
                            haptics = tangentHaptics,
                            tabViewMode = tabViewMode,
                            onNewTab = { openNewTabFromGrid() },
                            onCloseGrid = { closeGrid(animateBack = true) },
                            onSelectTabViewMode = { mode ->
                                tabViewMode = mode
                                components.globalPreferencesStore.tabViewModeName = mode.name
                                tangentHaptics.select()
                            },
                            onShowSyncedTabs = { showSyncedTabs = true },
                            onShowTabGroups = { showTabGroups = true },
                            onShowContainers = { showContainers = true },
                            // Arc-style: swipe up on the overview toolbar to summon the spaces deck.
                            // Gated off until Zen ships tab-in-space sync (see ZenWorkspaces.kt);
                            // only meaningful when spaces have actually synced.
                            onSwipeUpSpaces = if (
                                com.firebolt9907.tangentbrowser.sync.zen.ZEN_SPACES_TABS_UI_ENABLED &&
                                firefoxSyncState.zenWorkspaces.spaces.isNotEmpty()
                            ) {
                                { tangentHaptics.select(); showSpaces = true }
                            } else null,
                            onShowBookmarks = { showBookmarks = true },
                            onShowHistory = { showHistory = true },
                            onShowDownloads = { showDownloads = true },
                            onShowPasswords = { showPasswords = true },
                            onShowCreditCards = { showCreditCards = true },
                            onShowAddresses = { showAddresses = true },
                            onShowSettings = { showSettings = true },
                            onFeedback = { pageFeedback.show(it) },
                        )
                    }
                }
                // "Completing sign-in" cover over a federated SSO chain. Composed HERE — after the
                // page content and the tab-switch/grid snapshot covers, but BEFORE the address sheet
                // and the bottom chrome below — so the pill and toolbar draw on top of it and stay
                // usable: an SSO chain can stall on a slow IdP, and being unable to switch tabs or
                // reach the address bar until it finishes would be a worse trap than the flashing
                // this cover exists to hide. Because the chrome is composed later it also wins hit
                // testing, so the cover's pointer-swallowing never blocks it.
                //
                // Never raised over the tab overview or a fullscreen video, and it uncovers itself
                // the moment a hop settles (the password and Authenticator number-matching screens
                // must stay readable) — see SsoChainOverlay for the full uncover rule.
                com.firebolt9907.tangentbrowser.ui.content.SsoChainOverlay(
                    state = com.firebolt9907.tangentbrowser.ui.content.rememberSsoChainState(
                        url = selectedContent?.url,
                        loading = selectedContent?.loading == true,
                        enabled = !gridEngaged && !isVideoFullscreen && !isInPipMode,
                        pageProgress = selectedContent?.progress ?: 0,
                        tabId = actualSelectedTabId,
                    ),
                    host = currentHost,
                )

                // Arc-style spaces deck, summoned by swiping up on the overview toolbar above.
                if (showSpaces) {
                    com.firebolt9907.tangentbrowser.ui.sync.SpacesSheet(
                        snapshot = firefoxSyncState.zenWorkspaces,
                        onDismiss = { showSpaces = false },
                        onOpenTab = { url ->
                            viewModel.onOpenNewTab(url = url, title = "", selectTab = true)
                            showSpaces = false
                            closeGrid(animateBack = true)
                        }
                    )
                }
                // Arc/Zen-style refresh line: a horizontal primary-coloured line that fills from the
                // centre as you pull (driven by the live distance from PullRefreshContainer's nested
                // scroll), snaps bright + buzzes at the threshold, then pulses while the reload runs.
                if (pullLineComposed) {
                    PullRefreshLine(
                        progress = pullRefreshProgressProvider,
                        alpha = pullLineAlphaProvider,
                        refreshing = pullRefreshing,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .offset(y = with(density) { topChromeInsetPx.toDp() }),
                    )
                }
                // Multi-function pull: the chips the pull reveals under the refresh line, which the
                // still-down finger slides across to choose between. Composed on the same presence
                // rule as the line (and, like it, fed live magnitudes as draw-phase lambdas).
                if (pullMenuVisible && shouldShowUi) {
                    PullActionMenu(
                        presence = pullMenuPresenceProvider,
                        selectionPosition = pullMenuPositionProvider,
                        selected = pullMenuSelection,
                        readerAvailable = pullMenuReaderAvailable,
                        readerActive = isReaderActiveOnSelectedTab,
                        // The menu's reveal slide travels exactly this gap, so it starts flush with
                        // the top chrome inset (status bar + refresh line) and never rides up into
                        // the system bar. One number for the rest position and the travel.
                        slidePx = with(density) { PullMenuTopGap.toPx() },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = with(density) { topChromeInsetPx.toDp() } + PullMenuTopGap),
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
                    RoundedCornerViewportMask(
                        cornerMaskDp = with(density) { (topChromeInsetPx * 1.6f).toDp() },
                        gridOpenFractionProvider = gridOpenFraction,
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
                        viewModel.onSubmitText(text)
                    },
                    onTopSuggestionChange = { query, row ->
                        topSuggestionQuery.value = query
                        topSuggestion.value = row?.url
                        topSuggestionTabId.value = row
                            ?.takeIf { it.kind == com.firebolt9907.tangentbrowser.state.AddressSuggestionKind.Tab }
                            ?.tabId
                        autocompleteSource.value = row
                            ?.takeIf {
                                it.kind != com.firebolt9907.tangentbrowser.state.AddressSuggestionKind.Search &&
                                    it.kind != com.firebolt9907.tangentbrowser.state.AddressSuggestionKind.Tab
                            }
                            ?.url
                    },
                    onPreconnect = { components.speculativePreconnector.preconnect(it) },
                    onPreloadPage = { url ->
                        if (com.firebolt9907.tangentbrowser.state.isPreloadAllowed(
                                appContext,
                                components.globalPreferencesStore.preloadMode,
                            )
                        ) {
                            components.externalLinkPreloader.preload(url)
                        }
                    },
                    searchSuggestionsEnabled = components.globalPreferencesStore.searchSuggestionsEnabled,
                    searchEngine = components.globalPreferencesStore.searchEngine,
                    openTabs = remember(tabsList) {
                        tabsList.map {
                            com.firebolt9907.tangentbrowser.state.OpenTabRef(
                                id = it.id,
                                url = it.content.url,
                                title = it.content.title,
                            )
                        }
                    },
                    currentTabId = selectedTabId,
                    onSwitchToTab = { tabId ->
                        viewModel.onEditingCancel()
                        if (currentUrl == "about:blank") viewModel.dismissBlankTabAndReturn()
                        components.tabRepository.selectTab(tabId)
                    },
                    syncedTabs = remember(firefoxSyncState.devices) {
                        firefoxSyncState.devices.flatMap { device ->
                            device.tabs.map { tab ->
                                com.firebolt9907.tangentbrowser.state.RemoteTabRef(
                                    url = tab.url,
                                    title = tab.title,
                                    deviceName = device.name,
                                )
                            }
                        }
                    },
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
                    // Horizontal offset the archive bar follows so it stays glued to the current
                    // tab's pill during a tab-switch swipe: the active pill while dragging (offsetX),
                    // then the leaving-tab ghost as the commit spring carries it off-screen.
                    val archivePillOffsetX = remember {
                        { if (leavingSnapshotJob != null) leavingSnapshotOffsetX else offsetX }
                    }
                    // 0 while dragging (bar just tracks, no fade); ramps to 1 only during the commit
                    // settle as the pill flies off, so the bar is already invisible by the time the
                    // switch finishes and its session clears — the removal goes unnoticed.
                    val archiveSwitchFade = remember {
                        {
                            val stride = contentSize.width.toFloat()
                            if (leavingSnapshotJob != null && stride > 0f) {
                                (kotlin.math.abs(leavingSnapshotOffsetX) / stride).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        }
                    }
                    val pillReaderHideOffsetPx = remember(density) {
                        // Translate the pill fully off the bottom edge as the reader hides it.
                        // Distance is the bar's measured bottom region plus a margin so it
                        // clears the screen entirely.
                        { readerBarHidden * (bottomChromeInsetPx + with(density) { 48.dp.toPx() }) }
                    }
                    // Most-recently-used other Sync device — the Send Tab target the copy banner offers.
                    val recentTargetDevice = remember(firefoxSyncState.targetDevices) {
                        firefoxSyncState.targetDevices.maxByOrNull { it.lastAccessTime ?: Long.MIN_VALUE }
                    }
                    // Shared copy action: bottom-toolbar button and pill shortcut menu.
                    val copyCurrentUrl = {
                        val currentUrl = selectedContent?.url.orEmpty()
                        if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(currentUrl))
                            // When signed in with a reachable device, surface a transient "Send to
                            // <device>" banner above the pill instead of only the confirmation.
                            if (recentTargetDevice != null) {
                                sendLinkPrompt = SendLinkPrompt(
                                    url = currentUrl,
                                    title = selectedContent?.title.orEmpty(),
                                )
                            } else {
                                pageFeedback.show("Link copied")
                            }
                        }
                    }
                    // While viewing a Wayback snapshot, show the ORIGINAL page's address in the pill
                    // (not the web.archive.org URL). Display only — reload/edit still use the real URL.
                    val effectiveBarState = run {
                        val base = pendingActiveBarState ?: state
                        val wb = com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                        val original = if (wb.isArchiveUrl(base.pageUrl)) {
                            wb.originalFromArchiveUrl(base.pageUrl)
                        } else null
                        if (original != null) {
                            val label = addressBarLabel(original, barSearchEngine, showSearchTermsInBar)
                            base.copy(displayUrl = label, compactDisplayUrl = label)
                        } else {
                            base
                        }
                    }
                    BottomBarLayer(
                        contentSize = contentSize,
                        tabSwitchGapPx = tabSwitchGapPx,
                        previousTab = previousTab,
                        nextTab = nextTab,
                        tabCount = tabCount,
                        activeState = effectiveBarState,
                        callbacks = callbacks,
                        inlineAutocomplete = autocompleteSource.value.takeIf {
                            topSuggestionQuery.value == state.editingText.trim()
                        },
                        onVoiceInput = onVoiceInput,
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
                        onAaTap = { showAa = true },
                        onAaLongPress = {
                            selectedTabId?.let { tabId ->
                                val currentUrl = selectedContent?.url.orEmpty()
                                val readerHost = siteHostFromUrl(currentUrl)
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
                        onForwardTap = { selectedTabId?.let { components.sessionUseCases.goForward.invoke(it, true) } },
                        onShareTap = {
                            shareUrl(
                                appContext,
                                selectedContent?.url.orEmpty(),
                                selectedContent?.title,
                            ) { pageFeedback.show(it) }
                        },
                        onBookmarksTap = { showBookmarks = true },
                        onTabsTap = { openGrid(initialVelocityProgressPerSec = 0f) },
                        isWideLayout = isWideLayout,
                        wideBarLayout = wideBarLayout,
                        searchEngine = barSearchEngine,
                        showSearchTermsInBar = showSearchTermsInBar,
                        barTintColor = activeSiteThemeColor,
                    )

                    // Auto-retire the copy → send banner after a short window.
                    LaunchedEffect(sendLinkPrompt) {
                        if (sendLinkPrompt != null) {
                            kotlinx.coroutines.delay(6000L)
                            sendLinkPrompt = null
                        }
                    }
                    // Shared Now-Bar stack: every banner above the pill (archive scrubber, OTP paste,
                    // send-to-device, received tab) registers here so simultaneous banners pile up
                    // instead of overlapping. Lower stackPriority sits nearer the pill.
                    val bannerStack = com.firebolt9907.tangentbrowser.ui.rememberBannerStackState()

                    // Site content-permission request (camera/mic/location/notifications…) — an
                    // interactive banner the user must answer, so it sits front-most in the stack.
                    // Replaces the old center-screen AlertDialog.
                    com.firebolt9907.tangentbrowser.ui.SitePermissionBannerHost(
                        controller = components.sitePermissionController,
                        tabId = selectedTabId,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        collapseProgress = bannerCollapseProgressProvider,
                        gridProgress = pillGridProgress,
                        horizontalOffsetX = archivePillOffsetX,
                        switchFade = archiveSwitchFade,
                        stack = bannerStack,
                    )

                    com.firebolt9907.tangentbrowser.ui.sync.SendToDeviceBannerHost(
                        deviceName = if (sendLinkPrompt != null) recentTargetDevice?.name else null,
                        deviceType = recentTargetDevice?.type,
                        visible = sendLinkPrompt != null && recentTargetDevice != null,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        // Dive toward the pill on scroll-down, pop back on scroll-up — like the pill.
                        collapseProgress = bannerCollapseProgressProvider,
                        stack = bannerStack,
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

                    // Manual SMS-code paste fallback (see OtpAutofill). Auto-retire after a window so
                    // it doesn't linger once the user has moved on.
                    LaunchedEffect(otpPasteVisible) {
                        if (otpPasteVisible) {
                            kotlinx.coroutines.delay(30000L)
                            otpPasteVisible = false
                        }
                    }
                    com.firebolt9907.tangentbrowser.ui.sync.OtpPasteBannerHost(
                        visible = otpPasteVisible,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        // Dive toward the pill on scroll-down, pop back on scroll-up — like the pill.
                        collapseProgress = bannerCollapseProgressProvider,
                        stack = bannerStack,
                        onPaste = {
                            val clip = clipboardManager.getText()?.text
                            val code = clip?.let {
                                com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.extractCode(it)
                            }
                            if (code != null) {
                                com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.deliverCode(code)
                            } else {
                                pageFeedback.show("No code found in clipboard")
                            }
                            otpPasteVisible = false
                        },
                        onDismiss = { otpPasteVisible = false },
                    )

                    // The page on screen is a file we captured out of a blob URL — offer to keep it.
                    // A local fun, not an inline lambda, so the failure snackbar's "Try again" can
                    // re-invoke the very save that failed.
                    fun saveBlobFile(
                        blob: com.firebolt9907.tangentbrowser.state.BlobFile,
                    ) {
                        scope.launch {
                            val saved = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.firebolt9907.tangentbrowser.state.BlobFileStore
                                    .saveToDownloads(appContext, blob)
                            }
                            if (saved != null) {
                                pageFeedback.show("Saved $saved to Downloads")
                            } else {
                                pageFeedback.show("Couldn't save this file", "Try again") {
                                    saveBlobFile(blob)
                                }
                            }
                        }
                    }
                    com.firebolt9907.tangentbrowser.ui.content.BlobFileBannerHost(
                        file = currentBlobFile,
                        visible = currentBlobFile != null &&
                            currentBlobFile.id != blobBannerDismissedId,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        collapseProgress = bannerCollapseProgressProvider,
                        stack = bannerStack,
                        onSave = { blob ->
                            blobBannerDismissedId = blob.id
                            saveBlobFile(blob)
                        },
                        onDismiss = { blobBannerDismissedId = currentBlobFile?.id },
                    )

                    // Element-zap picker controls. The highlight itself is drawn in the page (only it
                    // can hit-test its own DOM); this is the Widen/Hide/Cancel half.
                    com.firebolt9907.tangentbrowser.ui.content.ZapPickerBannerHost(
                        visible = zapPickerActive,
                        selection = zapSelection,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        collapseProgress = bannerCollapseProgressProvider,
                        stack = bannerStack,
                        onWiden = {
                            selectedEngineSession?.loadJavascriptUrl(
                                com.firebolt9907.tangentbrowser.ui.content.ElementZap.buildWidenScript(),
                            )
                        },
                        onConfirm = {
                            selectedEngineSession?.loadJavascriptUrl(
                                com.firebolt9907.tangentbrowser.ui.content.ElementZap.buildConfirmScript(),
                            )
                        },
                        onCancel = {
                            zapPickerActive = false
                            zapSelection = null
                        },
                    )

                    // "Reader mode unavailable" feedback for a manual reader request that found no
                    // article — same bottom-anchored morphing banner as the OTP/handoff cards.
                    com.firebolt9907.tangentbrowser.ui.reader.ReaderUnavailableBannerHost(
                        visible = readerUnavailableVisible,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        collapseProgress = bannerCollapseProgressProvider,
                        stack = bannerStack,
                        onDismiss = { readerUnavailableVisible = false },
                    )

                    // Internet Archive (Wayback) date scrubber — bottom-anchored above the pill while
                    // this tab is in archive mode. Picking a day loads that snapshot in place; Close
                    // returns to the live page.
                    run {
                        val archiveSessions by com.firebolt9907.tangentbrowser.ui.archive.WaybackManager
                            .instance.sessions.collectAsStateWithLifecycle()
                        val archiveSession = selectedTabId?.let { archiveSessions[it] }
                        // Auto-enter archive mode when the selected tab is showing a web.archive.org
                        // snapshot but has no session — e.g. an app restart restored an archived tab,
                        // or a Wayback link was opened directly. Restores the pill rewrite + selector
                        // (with the viewed date highlighted); the mobilify inject already de-uglified
                        // the page independently of mode.
                        LaunchedEffect(selectedTabId, selectedContent?.url) {
                            val tabId = selectedTabId ?: return@LaunchedEffect
                            val url = selectedContent?.url ?: return@LaunchedEffect
                            val wb = com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                            if (wb.isArchiveUrl(url) && !wb.isActive(tabId)) {
                                val original = wb.originalFromArchiveUrl(url) ?: return@LaunchedEffect
                                wb.enter(tabId, original, initialSelected = wb.timestampFromArchiveUrl(url))
                            }
                        }
                        // Drop the session if the user navigates off both the live page and the
                        // archive (e.g. taps a link inside the snapshot to a fresh site).
                        LaunchedEffect(selectedTabId, selectedContent?.url, archiveSession != null) {
                            val s = archiveSession ?: return@LaunchedEffect
                            val url = selectedContent?.url
                            val wb = com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                            if (url != null && url != s.originalUrl && !wb.isArchiveUrl(url) &&
                                url != "about:blank"
                            ) {
                                wb.exit(s.tabId)
                            }
                        }
                        com.firebolt9907.tangentbrowser.ui.archive.ArchiveTimelineBarHost(
                            session = archiveSession,
                            // Hide the bar once the pill collapses to compact (matches other banners).
                            visible = !barCollapseCompact,
                            bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                            // Track the SAME scroll-collapse progress the pill morphs on, so scrolling
                            // down dives the bar toward the pill (intent) and scrolling up pops it
                            // back — 1:1 with the pill, read per-frame in the bar's draw phase.
                            collapseProgress = bannerCollapseProgressProvider,
                            // Fade with the pill as the tab switcher opens (anchored to the pill).
                            gridProgress = pillGridProgress,
                            // Track the pill horizontally on a tab-switch swipe; fade only as the
                            // switch settles so its removal goes unnoticed.
                            horizontalOffsetX = archivePillOffsetX,
                            switchFade = archiveSwitchFade,
                            stack = bannerStack,
                            onSelectYear = { year ->
                                val s = archiveSession ?: return@ArchiveTimelineBarHost
                                scope.launch {
                                    com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                                        .selectYear(s.tabId, year)
                                }
                            },
                            onSelect = { ts ->
                                val s = archiveSession ?: return@ArchiveTimelineBarHost
                                val wb = com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                                wb.selectDate(s.tabId, ts)
                                components.tabRepository.loadUrl(
                                    wb.archiveUrl(s.originalUrl, ts), s.tabId,
                                )
                            },
                            onExit = {
                                val s = archiveSession ?: return@ArchiveTimelineBarHost
                                val wb = com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                                wb.exit(s.tabId)
                                // If a snapshot was being viewed, return to the live page.
                                if (wb.isArchiveUrl(selectedContent?.url)) {
                                    components.tabRepository.loadUrl(s.originalUrl, s.tabId)
                                }
                            },
                        )
                    }

                    // In-app banner for a tab another device just pushed here via "Send tab".
                    // Auto-retire after a short window (the system notification persists longer).
                    val receivedTab = firefoxSyncState.receivedTab
                    LaunchedEffect(receivedTab?.receivedAt) {
                        if (receivedTab != null) {
                            kotlinx.coroutines.delay(8000L)
                            components.firefoxSyncController.dismissReceivedTab()
                        }
                    }
                    com.firebolt9907.tangentbrowser.ui.sync.ReceivedTabBannerHost(
                        received = receivedTab,
                        visible = receivedTab != null,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        // Dive toward the pill on scroll-down, pop back on scroll-up — like the pill.
                        collapseProgress = bannerCollapseProgressProvider,
                        stack = bannerStack,
                        onOpen = { tab ->
                            components.firefoxSyncController.dismissReceivedTab()
                            viewModel.onOpenNewTab(url = tab.url, title = tab.title, selectTab = true)
                        },
                        onDismiss = { components.firefoxSyncController.dismissReceivedTab() },
                    )

                    // "Open in app" persistent banner — a base-of-stack card (temporary banners pile
                    // in front) that reserves viewport space. All state is hoisted to the top of the
                    // composable (so the dvh inset is reserved before ContentHost binds); this only
                    // renders it.
                    com.firebolt9907.tangentbrowser.ui.OpenInAppBannerHost(
                        appName = openInAppName,
                        packageName = openInAppPackage,
                        requested = openInAppRequested,
                        // Hide once the pill collapses to compact (matches the archive scrubber): a
                        // swipe up to scroll tucks the banner away with the pill.
                        visible = openInAppActive && !barCollapseCompact,
                        bottomPadding = with(density) { bottomChromeInsetPx.toDp() },
                        // Dive toward the pill on scroll-down, pop back on scroll-up — like the pill.
                        collapseProgress = bannerCollapseProgressProvider,
                        // Anchored to the pill: fade with the tab switcher, track the pill sideways on
                        // a tab-switch swipe, and fade only as the switch settles. Same as the archive
                        // scrubber so the banner stays glued to the current tab's pill.
                        gridProgress = pillGridProgress,
                        horizontalOffsetX = archivePillOffsetX,
                        switchFade = archiveSwitchFade,
                        stack = bannerStack,
                        onOpen = {
                            val req = requestedLaunch
                            if (req != null) {
                                launchExternalApp(
                                    context = appContext,
                                    intent = req.intent,
                                    fallbackUrl = req.fallbackUrl,
                                    onFallback = { fb ->
                                        req.tabId?.let { components.tabRepository.loadUrl(fb, it) }
                                    },
                                    onMessage = { pageFeedback.show(it) },
                                )
                                com.firebolt9907.tangentbrowser.ui.content.AppLaunchRequests.clearForTab(selectedTabId)
                            } else {
                                val app = associatedApp
                                val url = selectedContent?.url
                                if (app != null && !url.isNullOrBlank()) {
                                    launchAssociatedApp(appContext, url, app.packageName)
                                }
                                openInAppPassiveHost?.let { appLinkStats.recordOpen(it) }
                                // Hide for THIS page only; a new tab / navigation re-offers it.
                                openInAppActedKey = openInAppPassiveKey
                            }
                        },
                        onDismiss = {
                            if (requestedLaunch != null) {
                                com.firebolt9907.tangentbrowser.ui.content.AppLaunchRequests.clearForTab(selectedTabId)
                            } else {
                                openInAppPassiveHost?.let { host ->
                                    dismissedAppHosts.add(host)
                                    // Track the rejection; if this host has crossed the dismissal
                                    // count + rate thresholds, offer to blacklist it (once).
                                    if (appLinkStats.recordDismiss(host)) {
                                        openInAppBlacklistPrompt = host to (associatedApp?.label ?: "the app")
                                    }
                                }
                            }
                        },
                    )

                } else if (isVideoFullscreen && !isInPipMode) {
                    com.firebolt9907.tangentbrowser.ui.video.VideoFullscreenOverlay(
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
        }

        TangentOverlaySheets(
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
            bottomChromeInsetPx = bottomChromeInsetPx,
            feedback = pageFeedback,
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
                pullMenuEnabled = components.globalPreferencesStore.pullMenuEnabled
                wideBarLayout = WideBarLayout.fromName(
                    components.globalPreferencesStore.wideBarLayoutName,
                )
                showSearchTermsInBar = components.globalPreferencesStore.showSearchTermsInBar
                barSearchEngine = components.globalPreferencesStore.searchEngine
                matchSiteThemeColor = components.globalPreferencesStore.matchSiteThemeColor
                tintPagesToSystemColor = components.globalPreferencesStore.tintPagesToSystemColor
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
            showTabGroups = showTabGroups,
            onDismissTabGroups = { showTabGroups = false },
            showContainers = showContainers,
            onDismissContainers = { showContainers = false },
            showPasswords = showPasswords,
            onDismissPasswords = { showPasswords = false },
            showCreditCards = showCreditCards,
            onDismissCreditCards = { showCreditCards = false },
            showAddresses = showAddresses,
            onDismissAddresses = { showAddresses = false },
            showHistory = showHistory,
            onDismissHistory = { showHistory = false },
            showBookmarks = showBookmarks,
            onDismissBookmarks = { showBookmarks = false },
            showDownloads = showDownloads,
            onDismissDownloads = { showDownloads = false },
            showAa = showAa,
            onDismissAa = { showAa = false },
            onAaEngineRefresh = { engineRefreshTick += 1 },
            onFindInPage = {
                findQuery = ""
                findActiveMatch = 0
                findTotalMatches = 0
                findInPageActive = true
            },
            // Only offered with a live session to inject the picker into, and never over the grid.
            onZapElement = selectedEngineSession?.let {
                {
                    zapSelection = null
                    zapPickerActive = true
                }
            },
            zappedCount = zappedSelectors.size,
            onRestoreZapped = {
                // Clearing the host's entries re-injects an empty stylesheet, so everything reappears
                // on the page already on screen — no reload.
                zappedStore.clear(currentHost)
            },
            onOpenArchive = {
                val tabId = selectedTabId
                val url = selectedContent?.url
                if (tabId != null && !url.isNullOrBlank()) {
                    // Enter archive mode on the live URL — or, if already viewing a snapshot, on the
                    // original page behind it — so the scrubber and Close both target the real page.
                    val original =
                        com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                            .originalFromArchiveUrl(url) ?: url
                    scope.launch {
                        com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
                            .enter(tabId, original)
                    }
                }
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

        // "This site can't load over HTTPS — open it over HTTP?" prompt, raised when a navigation
        // fails under HTTPS-Only mode (e.g. a self-hosted immich.lab with no TLS).
        HttpsOnlyExceptionDialog(components)

        // "You keep dismissing this — stop suggesting the app for <host>?" Offered once per host after
        // it crosses the dismissal-count + rejection-rate thresholds (see AppLinkBannerStats). Adding
        // to the blacklist permanently suppresses the passive "Open in app" suggestion for that host.
        openInAppBlacklistPrompt?.let { (host, appLabel) ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    appLinkStats.markPrompted(host)
                    openInAppBlacklistPrompt = null
                },
                title = { androidx.compose.material3.Text("Stop suggesting apps for $host?") },
                text = {
                    androidx.compose.material3.Text(
                        "You've dismissed “Open in $appLabel” several times on $host. Want to stop " +
                            "showing the app suggestion for this site?",
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            appLinkStats.addToBlacklist(host)
                            blacklistedAppHosts.add(host)
                            openInAppBlacklistPrompt = null
                        },
                    ) { androidx.compose.material3.Text("Stop showing") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            appLinkStats.markPrompted(host)
                            openInAppBlacklistPrompt = null
                        },
                    ) { androidx.compose.material3.Text("Keep showing") }
                },
            )
        }
    }
}
