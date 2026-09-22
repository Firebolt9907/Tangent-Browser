package com.firebolt9907.tangentbrowser.ui.customtab

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import com.firebolt9907.tangentbrowser.ui.motion.MotionInterruptedException
import com.firebolt9907.tangentbrowser.ui.motion.rememberBrowserMotionEngine
import androidx.compose.animation.core.spring
import com.firebolt9907.tangentbrowser.ui.TangentMotion
import com.firebolt9907.tangentbrowser.ui.PageFeedbackHost
import com.firebolt9907.tangentbrowser.ui.rememberPageFeedback
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.firebolt9907.tangentbrowser.ui.groups.swatch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.firebolt9907.tangentbrowser.ui.PromptHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.TangentApplication
import com.firebolt9907.tangentbrowser.state.compactHost
import com.firebolt9907.tangentbrowser.state.hostFromUrl
import com.firebolt9907.tangentbrowser.ui.content.LinkPeekCard
import com.firebolt9907.tangentbrowser.ui.content.LinkRectProbe
import com.firebolt9907.tangentbrowser.ui.content.PeekEngineHolder
import com.firebolt9907.tangentbrowser.ui.content.linkUrlFromHitResult
import com.firebolt9907.tangentbrowser.ui.content.loadJavascriptUrl
import com.firebolt9907.tangentbrowser.ui.cropBitmapToRect
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import com.firebolt9907.tangentbrowser.ui.reader.ReaderManager
import com.firebolt9907.tangentbrowser.ui.reader.hydrateAppearanceFromPrefs
import com.firebolt9907.tangentbrowser.ui.reader.openReaderLink
import com.firebolt9907.tangentbrowser.ui.reader.toggleReader
import com.firebolt9907.tangentbrowser.ui.shareUrl
import com.firebolt9907.tangentbrowser.ui.SyncSystemBarsWithFullscreen
import com.firebolt9907.tangentbrowser.ui.theme.TangentBrowserTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView

/**
 * The slim Custom-Tab surface: a single [com.firebolt9907.tangentbrowser.ui.content.ContentHost]
 * bound to the external link's custom-tab session, an optional reader overlay, and a bottom bar
 * (domain, reload, share, "Open in Tangent", close). Deliberately not `TangentRoot` — there is no
 * tab grid, tab-switching, or address bar here.
 *
 * Back behaviour: while the page has web history we run an in-page predictive back (translate + go
 * back); with no history, Back closes the activity — via `finish()` when the CCT caller supplied
 * exit animations (so [com.firebolt9907.tangentbrowser.CustomTabActivity] plays the client's close
 * animation), otherwise with **no** back callback registered so the OS finishes the activity and
 * plays its native predictive-back animation to the calling app.
 */
/** A single row in the "Open in Tangent" group picker: a colour dot (null = no group) + label. */
@Composable
private fun OpenGroupRow(
    label: String,
    accent: androidx.compose.ui.graphics.Color?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(accent ?: androidx.compose.ui.graphics.Color.Transparent),
        )
        Spacer(Modifier.size(12.dp))
        Text(label)
    }
}

@Composable
fun CustomTabRoot(
    customTabId: String?,
    hasCloseAnimation: Boolean,
    // Migrate this custom tab into the main browser, optionally dropping it into a tab group
    // (Arc-style "open into a space"). A null groupId opens it ungrouped.
    onOpenInTangent: (customTabId: String, groupId: String?) -> Unit,
    // A link the page wants to open in a NEW tab (window.open / target=_blank, or the peek card's
    // "New tab" action): the current custom tab is migrated into the main browser and the new link
    // opens there as a fresh tab. Custom Tabs have no tab list of their own to hold a second tab.
    onOpenNewLink: (String) -> Unit,
    onClose: () -> Unit,
    isInPipMode: Boolean = false,
    onFullScreenChanged: (Boolean) -> Unit = {},
    onRequestPipMode: () -> Unit = {},
) {
    val context = LocalContext.current
    val components = (context.applicationContext as TangentApplication).components
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val motionEngine = rememberBrowserMotionEngine()
    // This surface's own feedback channel (see PageFeedback.kt) — the browser root's lives in a
    // different activity's composition entirely.
    val feedback = rememberPageFeedback(scope)

    val browserState by components.browserState.collectAsStateWithLifecycle()
    // Groups the "Open in Tangent" button can drop this page into (Arc-style). Empty = open directly.
    val tabGroups by components.tabGroupsRepository.groups.collectAsStateWithLifecycle()
    var showOpenPicker by remember { mutableStateOf(false) }
    val customTab = customTabId?.let { id -> browserState.customTabs.firstOrNull { it.id == id } }
    val session = customTab?.engineState?.engineSession
    val url = customTab?.content?.url.orEmpty()
    val canGoBack = customTab?.content?.canGoBack == true

    // Archive (Wayback) awareness for externally-opened links. The raw url is a web.archive.org
    // snapshot wrapper; surface the ORIGINAL site's identity in our chrome (domain, share, security
    // indicator) and mark the surface as an archived snapshot. The page itself is already de-uglified
    // and stripped of Wayback's own toolbar by ContentHost's shared injectArchiveMobilify. Migrating
    // into the main browser ("Open in Tangent") then auto-enters full archive mode with the
    // timeline scrubber (TangentRoot's auto-enter effect).
    val wayback = remember { com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance }
    val isArchiveUrl = wayback.isArchiveUrl(url)
    val displayUrl = if (isArchiveUrl) wayback.originalFromArchiveUrl(url) ?: url else url
    val archiveDate = if (isArchiveUrl) {
        formatArchiveTimestamp(wayback.timestampFromArchiveUrl(url))
    } else null

    val readerManager = remember { ReaderManager.instance }
    val activeReaderTabs by readerManager.activeReaderTabs.collectAsStateWithLifecycle()
    val eligibleReaderTabs by readerManager.eligibleReaderTabs.collectAsStateWithLifecycle()
    val tabArticles by readerManager.tabArticles.collectAsStateWithLifecycle()
    val readerTheme by readerManager.theme.collectAsStateWithLifecycle()
    val readerFontSize by readerManager.fontSize.collectAsStateWithLifecycle()
    val readerFontName by readerManager.fontName.collectAsStateWithLifecycle()
    val readerActive = customTabId != null && activeReaderTabs.contains(customTabId)
    val article = customTabId?.let { tabArticles[it] }

    // ---- Link glance (peek) ----
    // Long-pressing a link opens the same Safari/Arc-style floating preview the main browser uses,
    // including the shared-element morph: the card grows out of the pressed link's captured pixels.
    // Uses the same snapshot-capture + LinkRectProbe plumbing as TangentRoot (both surfaces share
    // ContentHost, which registers the prompt delegate that routes LinkRectProbe back here) plus a
    // live preview on a bounded reused session ([PeekEngineHolder]).
    val clipboard = LocalClipboardManager.current
    // Measured size of the content surface, used to scale the peek crop rect. Declared here (ahead of
    // the ContentHost that writes it) so the peek crop effect below can read it.
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val hitResult = customTab?.content?.hitResult
    var peekUrl by remember { mutableStateOf<String?>(null) }
    var peekOrigin by remember { mutableStateOf(Offset.Zero) }
    var lastPressPos by remember { mutableStateOf(Offset.Zero) }
    val peekEngine = remember { PeekEngineHolder(components.engine) }
    var peekSession by remember { mutableStateOf<EngineSession?>(null) }
    var peekView by remember { mutableStateOf<EngineView?>(null) }
    // The pressed link's measured rect (LinkRectProbe) and captured element pixels for the morph.
    // [peekCaptureBitmap] is the full-screen capture; the crop effect below trims it to the rect and
    // immediately drops the large full bitmap.
    var peekSourceRect by remember { mutableStateOf<Rect?>(null) }
    var peekCaptureBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var peekElementBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Bumped to ask ContentHost to capture exactly what's on screen right now (invariant #6 path).
    var currentSnapshotRequestId by remember { mutableIntStateOf(0) }

    fun deviceLowOnMemory(): Boolean {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.lowMemory || mi.availMem < mi.threshold * 2
    }

    LaunchedEffect(hitResult) {
        val link = linkUrlFromHitResult(hitResult) ?: return@LaunchedEffect
        val origin = lastPressPos
        peekOrigin = origin
        peekSourceRect = null
        peekCaptureBitmap = null
        peekElementBitmap = null
        if (deviceLowOnMemory()) {
            peekSession = null
            peekView = null
            peekEngine.release()
        } else {
            val (s, v) = peekEngine.ensure(context, customTab?.content?.private == true)
            peekSession = s
            peekView = v
        }
        peekUrl = link
        customTabId?.let { components.store.dispatch(ContentAction.ConsumeHitResultAction(it)) }
        // Grab the current on-screen pixels of the page (routed back via onCurrentSnapshotCaptured)
        // and ask the page for the pressed link's rect so the card can morph out of the element.
        currentSnapshotRequestId += 1
        LinkRectProbe.pending = { rect -> peekSourceRect = rect }
        session?.loadJavascriptUrl(LinkRectProbe.buildScript(link, origin.x, origin.y))
    }
    // Crop the full capture to the element rect once both arrive, then drop the ~10 MB full bitmap.
    LaunchedEffect(peekCaptureBitmap, peekSourceRect, contentSize) {
        val full = peekCaptureBitmap
        val rect = peekSourceRect
        if (full != null && rect != null && contentSize.width > 0 && contentSize.height > 0) {
            peekElementBitmap = cropBitmapToRect(
                full, rect, contentSize.width.toFloat(), contentSize.height.toFloat()
            )
            peekCaptureBitmap = null
        }
    }
    // Free the reused peek session once the glance is gone (deferred by keying on peekUrl so the
    // card's AndroidView has already detached the view — same rule as TangentRoot).
    LaunchedEffect(peekUrl) {
        if (peekUrl == null) {
            peekSession = null
            peekView = null
            peekElementBitmap = null
            peekCaptureBitmap = null
            peekEngine.release()
        }
    }
    DisposableEffect(peekEngine) {
        onDispose { peekEngine.release() }
    }

    var isVideoFullscreen by remember { mutableStateOf(false) }

    SyncSystemBarsWithFullscreen(isVideoFullscreen)

    // Reader appearance (theme / font size / font) lives in the Context-less ReaderManager
    // singleton, which the main browser hydrates from GlobalPreferencesStore. A Custom Tab runs in
    // its own task and may launch without MainActivity ever having done that, so the reader overlay
    // here would otherwise fall back to the defaults (White / 100% / System). Load the persisted
    // values so the auto-reader honors the settings chosen in the main window. Read-only: this
    // surface has no reader-appearance controls, so there is nothing to mirror back to disk.
    LaunchedEffect(Unit) {
        readerManager.hydrateAppearanceFromPrefs(components.globalPreferencesStore)
    }

    // Guarantee the custom tab session has a live engine session. Dispatching CreateEngineSessionAction
    // can be lost or delayed, so retry until the session actually exists in the live store state.
    LaunchedEffect(customTabId) {
        val id = customTabId ?: return@LaunchedEffect
        var attempts = 0
        while (attempts < 6) {
            val tab = components.store.state.customTabs.firstOrNull { it.id == id }
            if (tab != null) {
                if (tab.engineState.engineSession != null) return@LaunchedEffect
                components.store.dispatch(EngineAction.CreateEngineSessionAction(id))
            }
            attempts++
            delay(300)
        }
    }

    // Tracks whether the currently-active reader overlay was engaged automatically (on launch,
    // because the page is a readable article) rather than by the user tapping the reader button.
    // When it was automatic, Back closes the whole Custom Tab (native exit to the caller) instead of
    // just dismissing the overlay — the user opened a link, so the natural "back" is out of the app,
    // and the taskbar button remains the way to drop into the underlying web page.
    //
    // Defaults to true: launch always arms auto-activation, and the dominant path fulfills it inside
    // ReaderManager.setReaderEligible (via the pending request) before the eligibility effect below
    // observes it. Only a manual bottom-bar turn-on clears the flag.
    var readerAutoActivated by remember { mutableStateOf(true) }

    // Custom tabs opened from another app always jump straight into reader mode when the page is a
    // readable article — regardless of domain (the per-host reader opt-out that the main browser
    // honors is intentionally ignored here). Requesting activation up front also covers the case
    // where eligibility is reported before this effect gets a chance to observe it.
    LaunchedEffect(customTabId) {
        customTabId?.let { readerManager.requestReaderActivation(it) }
    }
    LaunchedEffect(eligibleReaderTabs) {
        val id = customTabId ?: return@LaunchedEffect
        val substantial = readerManager.getArticle(id)?.hasSubstantialBody == true
        if (substantial && eligibleReaderTabs.contains(id) && !readerManager.isReaderActive(id)) {
            readerAutoActivated = true
            readerManager.setReaderActive(id, true)
        }
    }

    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    val barHeightDp = 52.dp
    val barHeightPx = with(density) { barHeightDp.roundToPx() }

    // In-page predictive back: 0 at rest, 1 fully committed. Drives a translate of the content so
    // the page slides away as the gesture progresses.
    var backSlide by remember { mutableFloatStateOf(0f) }

    // Activity-close predictive back: 0 at rest, 1 fully committed. Drives the Material 3
    // predictive-back treatment (opaque card shrinking to 90% with rounded corners, shifted in the
    // swipe direction, gently following the finger vertically); CustomTabActivity.finish() then
    // plays R.anim.custom_tab_exit from that frame once the gesture commits.
    var closeSlide by remember { mutableFloatStateOf(0f) }
    var closeSwipeDir by remember { mutableFloatStateOf(1f) } // +1 left-edge swipe, -1 right-edge
    var closeTouchDy by remember { mutableFloatStateOf(0f) }

    // Bottom-bar scroll linking: how many pixels the bar is currently pushed off the bottom edge
    // (0 = fully shown, [barTotalHeightPx] = fully hidden). This tracks the page scroll 1:1 rather
    // than springing between two states — scrolling the page down slides the bar off by the same
    // amount, scrolling up brings it straight back, so it stays glued to the finger.
    // [barTotalHeightPx] is the measured bar height including its navigation-bar padding.
    var barHiddenPx by remember { mutableFloatStateOf(0f) }
    var barTotalHeightPx by remember { mutableIntStateOf(barHeightPx) }
    // The top origin ribbon is a transient launch banner: 1 = shown, 0 = gone. It auto-dismisses a
    // few seconds after launch (see below) and on the first downward scroll, since it overlays the
    // top of the page content and would otherwise clip it. Once dismissed it stays gone.
    var ribbonHeightPx by remember { mutableIntStateOf(0) }
    val ribbonReveal = remember { Animatable(1f) }
    fun dismissRibbon() {
        if (ribbonReveal.targetValue != 0f) {
            scope.launch {
                ribbonReveal.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                )
            }
        }
    }
    // Auto-dismiss the ribbon ~3s after it appears so it never lingers over the content.
    LaunchedEffect(Unit) {
        delay(3000)
        dismissRibbon()
    }
    val barHideSlopPx = with(density) { 6.dp.toPx() }
    fun onPageScrollDelta(delta: Float) {
        // Any decisive downward scroll also retires the launch ribbon early.
        if (delta > barHideSlopPx) dismissRibbon()
        // Scroll-linked: push the bar off by exactly the scrolled distance (down hides, up reveals),
        // clamped to its own height so it never over- or under-shoots.
        barHiddenPx = (barHiddenPx + delta).coerceIn(0f, barTotalHeightPx.toFloat())
    }
    // Always reveal the bar when the reader overlay opens or closes so it never gets stuck hidden.
    LaunchedEffect(readerActive) {
        barHiddenPx = 0f
    }

    TangentBrowserTheme {
        BackHandler(enabled = isVideoFullscreen) {
            customTabId?.let { components.sessionUseCases.exitFullscreen.invoke(it) }
        }

        // Lowest-priority back handler (registered first, so the reader/history handlers below win
        // whenever they're enabled): when the activity resolved a close animation (CCT caller exit
        // bundle, or our own fallback for Google-app/Discover launches), Back must route through
        // Activity.finish() so CustomTabActivity can play it via overridePendingTransition — but
        // as a predictive drag, not a dead intercept: [closeSlide] makes the page track the finger
        // and the commit flows into the canned animation. Do NOT replace this with
        // overrideActivityTransition + no callback to get the system's predictive preview: tried,
        // and on this cross-task return to the icon-less, recents-excluded Discover task the
        // system plays nothing at all (hard cut, worse than the canned animation).
        PredictiveBackHandler(enabled = hasCloseAnimation && !isVideoFullscreen) { progress ->
            var startTouchY = Float.NaN
            try {
                progress.collect { backEvent ->
                    if (startTouchY.isNaN()) startTouchY = backEvent.touchY
                    closeSwipeDir =
                        if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                    closeTouchDy = backEvent.touchY - startTouchY
                    closeSlide = backEvent.progress.coerceIn(0f, 1f)
                }
                motionEngine.animate("close", closeSlide, 1f, animationSpec = TangentMotion.CustomTabCloseCommit) { v, _ -> closeSlide = v }
                // Leave closeSlide at 1: the window's exit animation continues from this frame,
                // resetting it would flash the full-size page before the window fades.
                onClose()
            } catch (_: CancellationException) {
                val cancelSlide = closeSlide
                val cancelDy = closeTouchDy
                motionEngine.animate("close-cancel", 1f, 0f, animationSpec = TangentMotion.PredictiveBackCancel) { v, _ ->
                    closeSlide = cancelSlide * v
                    closeTouchDy = cancelDy * v
                }
                closeSlide = 0f
                closeTouchDy = 0f
            }
        }
        // Reader takes priority — but only when the user opened it manually. Back then dismisses the
        // overlay before touching page history. When reader was auto-engaged on launch, we register
        // no handler so Back falls through to the system and finishes the activity (native exit to
        // the caller); the taskbar button is the way to reach the underlying web page.
        BackHandler(enabled = readerActive && !readerAutoActivated && !isVideoFullscreen) {
            customTabId?.let { readerManager.setReaderActive(it, false) }
        }
        // In-page history back with a predictive slide. Disabled at the first page (no history) and
        // while reader is up, so Back there falls through to the system → native exit to the caller.
        PredictiveBackHandler(enabled = canGoBack && !readerActive && !isVideoFullscreen) { progress ->
            try {
                progress.collect { backEvent ->
                    backSlide = backEvent.progress.coerceIn(0f, 1f)
                    motionEngine.track("back", backSlide)
                }
                motionEngine.animate("back", backSlide, 1f, animationSpec = TangentMotion.PredictiveBackCommit) { v, _ -> backSlide = v }
                customTabId?.let { components.sessionUseCases.goBack.invoke(it) }
                backSlide = 0f
            } catch (cancelled: CancellationException) {
                if (cancelled is MotionInterruptedException) throw cancelled
                motionEngine.animate("back", backSlide, 0f, animationSpec = TangentMotion.PredictiveBackCancel) { v, _ -> backSlide = v }
                backSlide = 0f
            }
        }

        val shouldShowUi = !isVideoFullscreen && !isInPipMode

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // M3 predictive-back preview: opaque card at 100→90% scale with rounded
                        // corners, nudged in the swipe direction and trailing the finger's Y.
                        // Kept fully opaque — custom_tab_exit does the fading after commit.
                        val closeScale = 1f - closeSlide * 0.1f
                        scaleX = closeScale
                        scaleY = closeScale
                        translationX = closeSwipeDir * closeSlide * size.width * 0.05f
                        translationY = closeTouchDy * 0.1f
                        if (closeSlide > 0f) {
                            clip = true
                            // Corners round in over the first ~20% of the drag, like the system's.
                            shape = RoundedCornerShape(28.dp * (closeSlide * 5f).coerceAtMost(1f))
                        }
                    }
                    .onSizeChanged { contentSize = it }
                    // Record each gesture's finger-down so a following link long-press can grow its
                    // glance card out of that point. Wakes once per gesture (on the down), then idles
                    // — it never processes scroll MOVEs, so it stays off the scroll hot path.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            lastPressPos = down.position
                        }
                    },
            ) {
                if (session != null && customTabId != null) {
                    com.firebolt9907.tangentbrowser.ui.content.ContentHost(
                        engine = components.engine,
                        engineSession = session,
                        modifier = Modifier.graphicsLayer {
                            translationX = backSlide * size.width * 0.5f
                            alpha = 1f - backSlide * 0.2f
                        },
                        topChromeInsetPx = if (isVideoFullscreen) 0 else statusBarTopPx,
                        bottomChromeInsetPx = if (isVideoFullscreen) 0 else barTotalHeightPx,
                        tabId = customTabId,
                        pageUrl = url,
                        snapshotCache = components.tabSnapshotCache,
                        sitePermissionController = components.sitePermissionController,
                        sitePreferencesStore = components.sitePreferencesStore,
                        onFirefoxAuthRedirect = components.firefoxSyncController::handleOAuthRedirect,
                        // Web dark mode: Tier 1 (native prefers-color-scheme) already applies
                        // engine-wide, so Custom Tabs get real site dark themes for free; thread the
                        // flag so the Tier-2 filter-invert fallback also engages on light pages here.
                        webDarkModeEnabled = components.sitePreferencesStore.get(hostFromUrl(url))
                            .darkMode.resolve(
                                components.globalPreferencesStore.webDarkModeTrigger
                                    .resolve(isSystemInDarkTheme()),
                            ),
                        webDarkModeAggressive = components.globalPreferencesStore.webDarkModeAggressive,
                        webDarkModeImageAnalysis = components.globalPreferencesStore.webDarkModeImageAnalysis,
                        webDarkModeGloballyActive = components.globalPreferencesStore.webDarkModeTrigger
                            .resolve(isSystemInDarkTheme()),
                        onPageScrollDelta = ::onPageScrollDelta,
                        // Drive the engine's vertical clipping from the bar's slide-off progress so the
                        // strip the pill vacates becomes LIVE, interactive web surface (not a stale
                        // clipped band). The pill hides completely, so the collapsed floor is 0 — the
                        // engine reclaims the whole reserve. Read as a lambda: per-frame clip updates
                        // via snapshotFlow, no ContentHost recomposition (invariant #10).
                        barCollapseProgress = {
                            if (barTotalHeightPx > 0) (barHiddenPx / barTotalHeightPx).coerceIn(0f, 1f)
                            else 0f
                        },
                        collapsedBottomReservePx = 0,
                        onFullScreenChange = { enabled ->
                            isVideoFullscreen = enabled
                            onFullScreenChanged(enabled)
                        },
                        // A page request to open a new tab (window.open / target=_blank) can't live
                        // in this single-page surface — hand it to the main browser (which also
                        // migrates the current tab there). Don't prepare()/start() the Gecko popup.
                        onOpenWindowRequest = { request -> onOpenNewLink(request.url) },
                        // The system PredictiveBackHandler above owns the back gesture here, so the
                        // ContentHost's own edge-drag predictive back stays off.
                        canStartPredictiveBack = false,
                        // Link-peek element capture: the bump above triggers this one-shot capture of
                        // the live page, cropped to the pressed link's rect for the morph.
                        currentSnapshotRequestId = currentSnapshotRequestId,
                        onCurrentSnapshotCaptured = { capturedTabId, bitmap ->
                            if (capturedTabId == customTabId) peekCaptureBitmap = bitmap
                        },
                    )
                }

                // Crashed content-process recovery (Fenix parity), mirroring MainActivity. A killed
                // renderer leaves the custom-tab surface blank; cover it with a restore affordance.
                // crashRecovery handles custom tabs too (it scans tabs + customTabs).
                if (customTab?.engineState?.crashed == true && shouldShowUi) {
                    com.firebolt9907.tangentbrowser.ui.content.CrashRestoreOverlay(
                        onRestore = {
                            customTabId?.let { id ->
                                components.sessionUseCases.crashRecovery.invoke(listOf(id))
                            }
                        },
                        topInset = with(density) { statusBarTopPx.toDp() },
                    )
                }

                PromptHandler(
                    engineSession = session,
                    loginAutofillEnabled = components.globalPreferencesStore.loginAutofillEnabled,
                    biometricAutofillRequired = components.globalPreferencesStore.biometricAutofillEnabled,
                    creditCardAutofillEnabled = components.globalPreferencesStore.creditCardAutofillEnabled,
                    addressAutofillEnabled = components.globalPreferencesStore.addressAutofillEnabled,
                )

                // Bitwarden-backed passkey picker + biometric gate (inert unless a ceremony is running).
                com.firebolt9907.tangentbrowser.ui.PasskeyUiHost()

                if (shouldShowUi) {
                    AnimatedVisibility(
                        visible = readerActive && article != null,
                        // Slide only, critically damped — see [TangentMotion.TakeoverSlide]. Kept
                        // identical to the reader takeover in TangentRoot: the same article opened
                        // from a link and from a tab must not enter differently.
                        enter = slideInVertically(TangentMotion.TakeoverSlide) { it },
                        exit = slideOutVertically(TangentMotion.TakeoverSlide) { it },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        article?.let {
                            com.firebolt9907.tangentbrowser.ui.reader.ReaderView(
                                article = it,
                                theme = readerTheme,
                                fontSizePercent = readerFontSize,
                                fontName = readerFontName,
                                modifier = Modifier.fillMaxSize(),
                                topPadding = with(density) { statusBarTopPx.toDp() },
                                bottomPadding = with(density) { barTotalHeightPx.toDp() },
                                // Hide the bottom taskbar on scroll-down (reveal on scroll-up), same as
                                // the web page — reader scroll deltas drive the same scroll-linked bar offset.
                                onScrollDelta = ::onPageScrollDelta,
                                onLinkClick = { href ->
                                    readerManager.openReaderLink(context, customTabId, session, href)
                                }
                            )
                        }
                    }

                    // Top identity ribbon: marks this as an external-link surface (never shown in the
                    // main browser). A transient launch banner — it slides up off-screen ~3s after
                    // appearing (or on first scroll) so it never clips the page content ([ribbonReveal]).
                    CustomTabOriginRibbon(
                        archiveDate = archiveDate,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .onSizeChanged { if (it.height > 0) ribbonHeightPx = it.height }
                            .graphicsLayer {
                                translationY = -(1f - ribbonReveal.value) * ribbonHeightPx
                                alpha = ribbonReveal.value
                            },
                    )

                    CustomTabBottomBar(
                        domain = compactHost(displayUrl),
                        isInsecure = displayUrl.startsWith("http://", ignoreCase = true),
                        isArchive = isArchiveUrl,
                        readerActive = readerActive,
                        readerEligible = customTabId != null && eligibleReaderTabs.contains(customTabId),
                        onToggleReader = {
                            customTabId?.let { id ->
                                // A manual turn-on means Back should dismiss the overlay rather
                                // than exit the activity (see readerAutoActivated above).
                                if (readerManager.toggleReader(id, url, session)) {
                                    readerAutoActivated = false
                                }
                            }
                        },
                        onShare = {
                            shareUrl(context, displayUrl, customTab?.content?.title) {
                                feedback.show(it)
                            }
                        },
                        onOpenInTangent = {
                            // No groups yet → open straight into the browser (the original behaviour).
                            // Otherwise ask which group to drop the page into, Arc-style.
                            if (tabGroups.isEmpty()) {
                                customTabId?.let { onOpenInTangent(it, null) }
                            } else {
                                showOpenPicker = true
                            }
                        },
                        onClose = onClose,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { if (it.height > 0) barTotalHeightPx = it.height }
                            .graphicsLayer { translationY = barHiddenPx },
                    )
                }

                // Arc-style "open into a space": ask which tab group to drop this page into when
                // migrating it into the main browser. Only shown when groups exist (see the Open button).
                if (showOpenPicker) {
                    AlertDialog(
                        onDismissRequest = { showOpenPicker = false },
                        title = { Text("Open in Tangent") },
                        text = {
                            Column {
                                OpenGroupRow(label = "No group", accent = null) {
                                    showOpenPicker = false
                                    customTabId?.let { onOpenInTangent(it, null) }
                                }
                                tabGroups.forEach { group ->
                                    OpenGroupRow(
                                        label = group.name.ifBlank { "Group" },
                                        accent = group.color.swatch(),
                                    ) {
                                        showOpenPicker = false
                                        customTabId?.let { onOpenInTangent(it, group.id) }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showOpenPicker = false }) { Text("Cancel") }
                        },
                    )
                }

                // Link glance: long-press preview. Rendered on top of the chrome (and reader), so it
                // sits outside the shouldShowUi block above. Its "New tab" action hands the link to
                // the main browser (migrating this tab there); "Open" navigates this tab in place.
                if (shouldShowUi) {
                    peekUrl?.let { link ->
                        LinkPeekCard(
                            url = link,
                            peekSession = peekSession,
                            peekView = peekView,
                            origin = peekOrigin,
                            sourceRect = peekSourceRect,
                            elementBitmap = peekElementBitmap?.asImageBitmap(),
                            onOpenHere = { u ->
                                customTabId?.let {
                                    components.sessionUseCases.loadUrl.invoke(u, sessionId = it)
                                }
                                peekUrl = null
                            },
                            onOpenNewTab = { u ->
                                peekUrl = null
                                onOpenNewLink(u)
                            },
                            onCopy = { u ->
                                clipboard.setText(AnnotatedString(u))
                                feedback.show("Link copied")
                                peekUrl = null
                            },
                            onShare = { u ->
                                shareUrl(context, u) { feedback.show(it) }
                                peekUrl = null
                            },
                            onDismiss = { peekUrl = null },
                        )
                    }
                }

                if (isVideoFullscreen && !isInPipMode) {
                    com.firebolt9907.tangentbrowser.ui.video.VideoFullscreenOverlay(
                        engineSession = session,
                        onExitFullscreen = {
                            customTabId?.let { id ->
                                components.sessionUseCases.exitFullscreen.invoke(id)
                            }
                        },
                        onEnterPip = {
                            onRequestPipMode()
                        }
                    )
                }

                // Page-level feedback, mounted last so it draws over the bar and the link glance
                // that raise it. The Custom Tab is a separate activity with its own composition, so
                // it owns its own PageFeedback rather than sharing the browser root's.
                PageFeedbackHost(feedback, with(density) { barTotalHeightPx.toDp() })
            }
        }
    }
}

// Squircle ripple/splash for the pill's icon chips — echoes the pill's own continuous-corner style
// so touch feedback reads as part of the pill (mirrors BarExpanded's chip splash in the main bar).
private val ChipSplashShape = SquircleShape(radius = 14.dp, exponent = 3f)

/**
 * The Custom-Tab bottom chrome, restyled to share the main browser's visual identity: a floating
 * continuous-corner ("squircle") pill on a tonal-elevated surface rather than the flat edge-to-edge
 * Chrome-style bar it replaced. The content still scrolls translucently under it. Unlike the main
 * browser's pill the address is display-only (no editable field) and the chip set is Custom-Tab
 * specific (close / reader / share / open-in-Tangent) — that, plus the top [CustomTabOriginRibbon],
 * keeps this recognizably NOT the main window.
 */
@Composable
private fun CustomTabBottomBar(
    domain: String,
    isInsecure: Boolean,
    isArchive: Boolean = false,
    readerActive: Boolean,
    readerEligible: Boolean,
    onToggleReader: () -> Unit,
    onShare: () -> Unit,
    onOpenInTangent: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Float above the gesture nav bar, matching the main browser's bottom pill inset.
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding().coerceAtLeast(24.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = SquircleShape(radius = 28.dp, exponent = 3f),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillChip(
                    icon = Icons.Default.Close,
                    contentDescription = "Close",
                    onClick = onClose,
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // Archive badge takes precedence over the (non-)secure indicator: this is a
                    // Wayback snapshot, so a History mark + primary tint signals "archived" while the
                    // domain reads as the real site.
                    if (isArchive) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Archived snapshot",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    } else if (isInsecure) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "Not secure",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = domain,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isArchive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PillChip(
                    icon = Icons.AutoMirrored.Filled.ChromeReaderMode,
                    contentDescription = if (readerActive) "Exit reader mode" else "Reader mode",
                    onClick = onToggleReader,
                    tint = when {
                        readerActive -> MaterialTheme.colorScheme.primary
                        readerEligible -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    },
                )
                PillChip(
                    icon = Icons.Default.Share,
                    contentDescription = "Share",
                    onClick = onShare,
                )
                PillChip(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open in Tangent",
                    onClick = onOpenInTangent,
                )
            }
        }
    }
}

/** A single squircle-splash icon chip inside the bottom pill (matches BarExpanded's chip feel). */
@Composable
private fun PillChip(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(ChipSplashShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(21.dp),
        )
    }
}

/**
 * Slim floating identity ribbon at the top of a Custom Tab. It has no counterpart in the main
 * browser, so its mere presence (plus its accent tint) tells the user at a glance that they are
 * viewing an externally-opened link inside Tangent rather than in their main window. Purely
 * informational — the actionable "Open in Tangent" lives in the bottom pill.
 */
@Composable
private fun CustomTabOriginRibbon(
    archiveDate: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // In archive mode the ribbon becomes the "archived snapshot" badge (History icon + capture
        // date, primary-tinted); otherwise it's the ordinary "opened from a link" origin marker.
        Surface(
            shape = SquircleShape(radius = 18.dp, exponent = 3f),
            color = if (archiveDate != null) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (archiveDate != null) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    // Institution glyph on the "Internet Archive" ribbon (matches the scrubber
                    // banner); the ordinary origin ribbon keeps the open-in-new mark.
                    imageVector = if (archiveDate != null) Icons.Default.AccountBalance
                    else Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (archiveDate != null) "Internet Archive · $archiveDate"
                    else "Opened from a link",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val ARCHIVE_MONTHS = arrayOf(
    "", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** "20200305…" → "Mar 5, 2020" for the archive badge; null when the stamp is unusable. */
private fun formatArchiveTimestamp(ts: String?): String? {
    if (ts == null || ts.length < 8) return null
    val year = ts.substring(0, 4)
    val month = ts.substring(4, 6).toIntOrNull() ?: return null
    val day = ts.substring(6, 8).toIntOrNull() ?: return null
    if (month !in 1..12) return null
    return "${ARCHIVE_MONTHS[month]} $day, $year"
}

