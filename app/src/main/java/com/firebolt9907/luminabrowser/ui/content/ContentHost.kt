package com.firebolt9907.luminabrowser.ui.content

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.firebolt9907.luminabrowser.addons.AddonController
import com.firebolt9907.luminabrowser.ui.reader.ReaderManager
import com.firebolt9907.luminabrowser.state.TabSnapshotCache
import kotlin.math.abs
import kotlin.math.roundToInt
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSession.Observer
import mozilla.components.concept.engine.window.WindowRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// How long to wait after an SPA (pushState) navigation before re-arming `isSafeToSnapshot`. Short
// enough that on-demand captures (grid open / tab-switch) work almost immediately, long enough that
// the re-arm lands after the transitional (outgoing) frame is gone rather than during it.
private const val SpaSettleCaptureDelayMs = 350L

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ContentHost(
    engine: Engine,
    engineSession: EngineSession?,
    modifier: Modifier = Modifier,
    topChromeInsetPx: Int = 0,
    bottomChromeInsetPx: Int = 0,
    tabId: String? = null,
    pageUrl: String = "",
    snapshotCache: TabSnapshotCache? = null,
    addonController: AddonController? = null,
    sitePermissionController: com.firebolt9907.luminabrowser.permissions.SitePermissionController? = null,
    sitePreferencesStore: com.firebolt9907.luminabrowser.state.SitePreferencesStore? = null,
    onTabPainted: (String) -> Unit = {},
    onSnapshotCaptured: (tabId: String, url: String, bitmap: android.graphics.Bitmap) -> Unit = { _, _, _ -> },
    onPageScrollDelta: (Float) -> Unit = {},
    onPageTouch: () -> Unit = {},
    canStartPredictiveBack: Boolean = false,
    onPredictiveBackProgress: (Float) -> Unit = {},
    onPredictiveBackEnd: (progressPx: Float, velocityPxPerSecond: Float) -> Unit = { _, _ -> },
    onPredictiveBackCancel: () -> Unit = {},
    currentSnapshotRequestId: Int = 0,
    engineRefreshTick: Int = 0,
    onCurrentSnapshotCaptured: (tabId: String, bitmap: android.graphics.Bitmap) -> Unit = { _, _ -> },
    onTopOverscrollPull: (Float) -> Unit = {},
    onTopOverscrollRelease: (Float) -> Unit = {},
    onOpenWindowRequest: (WindowRequest) -> Unit = {},
    onFullScreenChange: (Boolean) -> Unit = {},
    onFirefoxAuthRedirect: (String) -> Boolean = { false },
    topPaddingFixActive: Boolean = false,
    // When true, inject ThemeColorProbe for every page (not just top-padding sites) so the "Match
    // website theme color" option can recolor the chrome for any site.
    themeColorSamplingEnabled: Boolean = false,
    // When non-null, the "Tint pages to my system color" boost overlays this color on every page
    // (mix-blend-mode: color). Null disables/removes the boost. See SiteTint.kt.
    siteTintColor: androidx.compose.ui.graphics.Color? = null,
    pageZoomFactor: Float = 1.0f,
    // Live scroll-collapse progress (0 = pill expanded, 1 = compact), as a lambda so the per-frame
    // spring updates drive the engine's vertical clipping via snapshotFlow without recomposing
    // ContentHost. See the clipping effects below and BarPill.PillMorph.
    barCollapseProgress: () -> Float = { 0f },
    tabIsLoading: Boolean = false,
    keyboardInsetPx: Int = 0,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // The color GeckoView shows before a session's first paint (see the render DisposableEffect).
    val backgroundColor = MaterialTheme.colorScheme.background
    val firstPaintCoverColor = backgroundColor.toArgb()
    // The compositor "clear color" fills regions GeckoView hasn't painted yet — the bright band you
    // see flung past the display port on a fast scroll (default: opaque white). Match the app
    // background instead, tinted to the system boost when it's on so those flashes agree with the
    // tinted page rather than reading as a much brighter, untinted strip. Force opaque (the
    // checkerboard fill must not be translucent).
    val compositorClearColor = (backgroundColor.tintedForSite(siteTintColor).toArgb()) or 0xFF000000.toInt()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var isSafeToSnapshot by remember(tabId, engineSession) { mutableStateOf(!tabIsLoading && engineSession != null) }
    // Recovery net: the callback-driven toggles below can leave this stuck `false` even though the
    // page is fully loaded — e.g. when a load-complete callback is swallowed by the
    // `javascript:`-spacer suppression, or when re-binding a tab that finished loading in the
    // background (no fresh FCP / load-complete callback ever fires). That would block every future
    // snapshot for the tab. The store's `tabIsLoading` is the authoritative "done" signal, so
    // whenever it reports the tab is idle, snapshots are safe again.
    LaunchedEffect(tabId, tabIsLoading, engineSession) {
        if (!tabIsLoading && engineSession != null) isSafeToSnapshot = true
    }
    val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    val predictiveBackEdgeWidthPx = with(density) { 24.dp.toPx() }
    val topChromeInsetCssPx = with(density) {
        topChromeInsetPx.toDp().value.toInt().coerceAtLeast(0)
    }
    // CSS-px height of the bottom pill / SafariBar reserve, injected as page-bottom scroll room so
    // in-flow footer content can be scrolled clear of the (translucent, scrolled-under) pill. See
    // buildBottomSpacerScript / injectViewportSpacers.
    val bottomChromeInsetCssPx = with(density) {
        bottomChromeInsetPx.toDp().value.toInt().coerceAtLeast(0)
    }
    val engineView = remember(engine) {
        engine.createView(context).also { it.setActivityContext(context) }
    }
    // Wrap the EngineView in a PullRefreshContainer (a nested-scroll parent) so pull-to-refresh
    // works reliably over GeckoView, which cooperates as a NestedScrollingChild. canPull is true
    // only when the page is at the very top; the container reports live pull distance, which Compose
    // turns into the reactive refresh line + threshold haptic.
    val pullContainer = remember(engineView) {
        PullRefreshContainer(context).apply {
            canPull = { !engineView.canScrollVerticallyUp() }
            addView(engineView.asView())
        }
    }
    var lastTouchY by remember { mutableFloatStateOf(0f) }
    var pullingFromTop by remember { mutableStateOf(false) }
    var gestureStartX by remember { mutableFloatStateOf(0f) }
    var gestureStartY by remember { mutableFloatStateOf(0f) }
    var predictiveBackEligible by remember { mutableStateOf(false) }
    var predictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgressPx by remember { mutableFloatStateOf(0f) }
    var predictiveBackVelocityTracker by remember { mutableStateOf<VelocityTracker?>(null) }

    // Dedupe state for url-driven capture sites (post-bind delay, FCP, load-complete).
    // Tracks the last "$tabId|$url" key and timestamp so repeated captures within 750 ms are skipped.
    var lastCaptureKey: String? by remember { mutableStateOf(null) }
    var lastCaptureMs: Long by remember { mutableLongStateOf(0L) }
    val captureScope = rememberCoroutineScope()

    /**
     * Converts the ~10 MB ARGB_8888 capture to the resident RGB_565 config off the main thread,
     * then delivers it to the cache and callback back on main. Doing the copy inline on the
     * capture callback cost 10–20 ms of main-thread time per capture (and captures cluster
     * around tab switches), which read as hitches during swipes. The cache and the snapshot
     * maps both store 565, so converting once here means neither converts again.
     */
    fun storeSnapshotAsync(id: String, capturingUrl: String, bitmap: android.graphics.Bitmap) {
        val cache = snapshotCache ?: return
        captureScope.launch(Dispatchers.Default) {
            val resident = bitmap.toResident565()
            withContext(Dispatchers.Main) {
                cache.put(id, resident, capturingUrl)
                onSnapshotCaptured(id, capturingUrl, resident)
            }
        }
    }

    /**
     * @param deduped When true, skips the capture if the same (tabId, url) was captured
     *   less than 750 ms ago. Set to false (default) for captures that must always run
     *   (onDispose and currentSnapshotRequestId-driven captures).
     */
    fun captureSnapshot(
        capturingId: String? = tabId,
        capturingUrl: String = pageUrl,
        deduped: Boolean = false,
    ) {
        if (engineSession == null || !isSafeToSnapshot) return
        val id = capturingId ?: return
        if (snapshotCache == null) return
        if (deduped) {
            val key = "$id|$capturingUrl"
            val now = System.currentTimeMillis()
            if (key == lastCaptureKey && (now - lastCaptureMs) < 750L) return
            lastCaptureKey = key
            lastCaptureMs = now
        }
        engineView.captureThumbnail { bitmap: android.graphics.Bitmap? ->
            if (bitmap != null) storeSnapshotAsync(id, capturingUrl, bitmap)
        }
    }

    // SafariBar layout hardcodes 24dp bottom padding to clear the gesture nav bar, but the
    // actual nav bar height varies by device. Use the real inset so fixed-bottom web elements
    // land exactly at the pill's top edge rather than above it.
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    val barPaddingPx = navBarPx.coerceAtLeast(with(density) { 24.dp.roundToPx() })
    val compactPillPx = with(density) { 44.dp.roundToPx() }
    val expandedClippingPx = (bottomChromeInsetPx - barPaddingPx + navBarPx).coerceAtLeast(0)
    // GeckoView's dynamic-toolbar clipping baseline is `setDynamicToolbarMaxHeight` (the *max*
    // reserve). `setVerticalClipping` is the CURRENT offset from that baseline, and must be 0 when
    // the bar is fully shown and negative as it hides — passing the positive obscured height here
    // makes Gecko reserve the toolbar twice, so the dynamic viewport (dvh / visualViewport) ends up
    // one toolbar-height *shorter* than the small viewport (svh). See mozilla-components'
    // EngineViewClippingBehavior: "the values should be negative because the baseline for clipping
    // is bottom toolbar height."
    //
    // The baseline reserve only changes with layout insets, so set it (or clear it in fullscreen)
    // from an effect keyed on those inputs rather than on every recomposition.
    LaunchedEffect(engineView, bottomChromeInsetPx, expandedClippingPx) {
        if (bottomChromeInsetPx > 0) {
            engineView.setDynamicToolbarMaxHeight(expandedClippingPx)
        } else {
            // No bottom chrome reserved (video fullscreen passes bottomChromeInsetPx=0). Clear any
            // prior toolbar reservation, otherwise GeckoView keeps clipping space for the now-hidden
            // pill and the fullscreen video is pushed up, leaving a black gap at the bottom.
            engineView.setDynamicToolbarMaxHeight(0)
            engineView.setVerticalClipping(0)
            android.util.Log.i(
                "LuminaVideo",
                "[clip] fullscreen bottomInset=0 set dtmh=0 clip=0 viewH=${engineView.asView().height} navBarPx=$navBarPx",
            )
        }
    }
    // The current clipping offset tracks the collapse spring on every frame. The pill morph updates
    // barCollapseProgress ~120x/s; observing it here via snapshotFlow drives setVerticalClipping
    // WITHOUT recomposing ContentHost — recomposing the engine host per frame was part of the
    // collapse-scroll frame drops. The obscured height shrinks from the full reserve (bar expanded)
    // toward the compact-pill footprint as the pill collapses.
    LaunchedEffect(engineView, bottomChromeInsetPx, expandedClippingPx, compactPillPx, navBarPx) {
        if (bottomChromeInsetPx <= 0) return@LaunchedEffect
        snapshotFlow { barCollapseProgress().coerceIn(0f, 1f) }.collect { p ->
            val currentObscuredPx = lerp(
                expandedClippingPx.toFloat(),
                (compactPillPx + navBarPx).toFloat(),
                p,
            ).roundToInt().coerceAtLeast(0)
            engineView.setVerticalClipping(currentObscuredPx - expandedClippingPx)
        }
    }

    // A VelocityTracker is obtain()-ed on ACTION_DOWN and only recycled on ACTION_UP/CANCEL. If this
    // composable leaves while a predictive-back drag is in flight (e.g. a tab switch mid-gesture),
    // that ACTION_UP never arrives, so recycle here to return the tracker to the pool.
    DisposableEffect(Unit) {
        onDispose {
            predictiveBackVelocityTracker?.recycle()
            predictiveBackVelocityTracker = null
        }
    }

    DisposableEffect(engineView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> engineView.onCreate()
                Lifecycle.Event.ON_START -> engineView.onStart()
                Lifecycle.Event.ON_RESUME -> engineView.onResume()
                Lifecycle.Event.ON_PAUSE -> engineView.onPause()
                Lifecycle.Event.ON_STOP -> engineView.onStop()
                Lifecycle.Event.ON_DESTROY -> engineView.onDestroy()
                Lifecycle.Event.ON_ANY -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            engineView.release()
        }
    }

    DisposableEffect(engineView, engineSession, tabId) {
        engineSession?.let { session ->
            engineView.render(session)
            // Before this session paints its first frame, GeckoView covers the surface with a solid
            // color (white by default) — a jarring flash on dark pages/theme. Cover with the app's
            // background color instead so the pre-paint frame is unnoticeable rather than a white
            // flash. (The snapshot cover, when present, still draws on top for tab switches.)
            findGeckoView(engineView.asView())?.coverUntilFirstPaint(firstPaintCoverColor)
            if (tabId != null) {
                com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor.registerPromptDelegate(session, tabId)
            }
        }
        onDispose {
            if (engineSession != null) captureSnapshot(tabId)
        }
    }

    // Keep GeckoView's compositor clear color matched to the (optionally tinted) app background so a
    // fast fling past the painted display port doesn't flash a bright white band. Re-applied when the
    // session rebinds or the theme / tint changes; the CompositorController retains the value across
    // paints, so setting it once per change is enough.
    LaunchedEffect(engineView, engineSession, compositorClearColor) {
        if (engineSession == null) return@LaunchedEffect
        findGeckoView(engineView.asView())?.session?.compositorController?.setClearColor(compositorClearColor)
    }

    LaunchedEffect(engineView, engineSession, tabId) {
        if (engineSession == null || tabId == null || snapshotCache == null) return@LaunchedEffect
        delay(600)
        captureSnapshot(tabId, pageUrl, deduped = true)
    }

    LaunchedEffect(engineView, currentSnapshotRequestId, tabId, pageUrl, engineSession) {
        // Deliberately NOT gated on `isSafeToSnapshot`: this fires only for explicit, user-initiated
        // requests (predictive-back start, tab-switch drag, grid open) that need a snapshot of
        // exactly what is on screen right now. After a same-document (SPA) navigation
        // `isSafeToSnapshot` sits false until the delayed re-arm, and gating here on it meant the
        // current page's live capture was skipped — so the gesture/grid cover fell back to the tab's
        // previous (stale) bitmap, showing the previous page as if it were the current one.
        if (currentSnapshotRequestId <= 0 || tabId == null || engineSession == null) return@LaunchedEffect
        val capturedUrl = pageUrl
        engineView.captureThumbnail { bitmap: android.graphics.Bitmap? ->
            if (bitmap != null) {
                // The gesture cover needs the bitmap immediately (a drag is already in flight),
                // and keeps the full ARGB copy; the cache/map copies convert in the background.
                onCurrentSnapshotCaptured(tabId, bitmap)
                storeSnapshotAsync(tabId, capturedUrl, bitmap)
            }
        }
    }

    LaunchedEffect(engineRefreshTick) {
        // A full-screen overlay (the appearance sheet's opaque backdrop) was just dismissed. While
        // it covered the engine the GeckoView compositor paused, so its surface keeps presenting a
        // stale frame that visually covers the (still-composed) pill until an input event wakes it.
        // A scroll fixes it; nothing else (re-asserting clipping, capturing a thumbnail) does. So
        // replicate the wake a scroll provides by dispatching a no-op touch — ACTION_DOWN followed
        // immediately by ACTION_CANCEL — which kicks the compositor without moving the page or
        // registering as a tap on web content.
        if (engineRefreshTick <= 0) return@LaunchedEffect
        if (bottomChromeInsetPx > 0) {
            // Re-assert the current clipping offset for the live collapse progress (recomputed here
            // since it's no longer a composition value — see the clipping effects above).
            val p = barCollapseProgress().coerceIn(0f, 1f)
            val obscured = lerp(
                expandedClippingPx.toFloat(),
                (compactPillPx + navBarPx).toFloat(),
                p,
            ).roundToInt().coerceAtLeast(0)
            engineView.setVerticalClipping(obscured - expandedClippingPx)
        }
        // Wait a frame so the overlay is fully gone before we nudge the surface.
        withFrameNanos { }
        val view = engineView.asView()
        val cx = view.width / 2f
        val cy = view.height / 2f
        val now = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0)
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, cx, cy, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(cancel)
        down.recycle()
        cancel.recycle()
    }

    LaunchedEffect(engineView, engineSession, tabId) {
        if (engineSession == null) return@LaunchedEffect
        engineView.verticalScrollDelta.collect { delta ->
            onPageScrollDelta(delta)
        }
    }



    DisposableEffect(engineSession, topChromeInsetCssPx, bottomChromeInsetCssPx, tabId, topPaddingFixActive, themeColorSamplingEnabled, siteTintColor) {
        if (engineSession == null) {
            onDispose { }
        } else {
            var injectedForCurrentLoad = false
            var amoInstallerInjectedForCurrentLoad = false
            var suppressJavascriptLoadCallback = false
            var cacheTriggeredForCurrentLoad = false
            var readerExtractedForCurrentLoad = false
            var otpDetectorInjectedForCurrentLoad = false
            var loginDetectorInjectedForCurrentLoad = false
            var formFieldMemoryInjectedForCurrentLoad = false
            var currentUrl = pageUrl
            var capturedLeavingUrl: String? = null
            // True while a genuine document load is in flight (real navigation), so the delayed
            // SPA-settle pass below can tell a pushState navigation apart from a real load and stay
            // out of the latter's way. Set by onLoadRequest / onLoadingStateChange.
            var realLoadInProgress = false
            val hostView = engineView.asView()
            var spaSettleRunnable: Runnable? = null
            fun captureLeavingPage() {
                if (currentUrl.isBlank() || currentUrl == "about:blank") return
                if (capturedLeavingUrl == currentUrl) return
                capturedLeavingUrl = currentUrl
                captureSnapshot(capturingUrl = currentUrl)
            }
            // Re-arm snapshotting after an SPA (history.pushState) navigation. Such navigations fire
            // ONLY onLocationChange — no loading-state toggle, no FCP — so nothing else flips
            // `isSafeToSnapshot` back to true; left stuck false it would block the NEXT leaving-page
            // capture and break the predictive-back chain, and block the on-demand current-page
            // capture the grid/tab-switch relies on. We deliberately do NOT capture the arriving page
            // proactively here: the client-side route often hasn't repainted yet, so a speculative
            // capture stores the OUTGOING page's pixels under the new URL (the current tab then shows
            // the previous page's thumbnail). The current page is instead captured live, on demand,
            // when it's actually needed — grid open / tab-switch — by which point it has rendered.
            // Skips when a real load is in flight; that path re-arms via its own callbacks.
            fun scheduleSpaSnapshotRearm() {
                spaSettleRunnable?.let(hostView::removeCallbacks)
                val runnable = Runnable {
                    spaSettleRunnable = null
                    if (!realLoadInProgress) isSafeToSnapshot = true
                }
                spaSettleRunnable = runnable
                hostView.postDelayed(runnable, SpaSettleCaptureDelayMs)
            }
            fun injectSpacer(force: Boolean = false) {
                if (topChromeInsetCssPx <= 0 && bottomChromeInsetCssPx <= 0) return
                if (injectedForCurrentLoad && !force) return
                injectedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                engineSession.injectViewportSpacers(
                    topPx = if (topPaddingFixActive) 0 else topChromeInsetCssPx,
                    bottomPx = bottomChromeInsetCssPx,
                    skipHeaderInsets = topPaddingFixActive,
                )
                engineSession.injectPageZoom(pageZoomFactor)
                // Sample the page's declared theme-color (Safari-style). Done for top-padding sites
                // (to tint the exposed strip / status bar) and whenever the "Match website theme
                // color" option is on (to recolor the chrome for any site). Falls back to the system
                // primary color natively when none is declared.
                if (topPaddingFixActive || themeColorSamplingEnabled) {
                    engineSession.loadJavascriptUrl(ThemeColorProbe.buildScript())
                }
                // Re-apply the system-color boost on every load: a fresh page has a fresh DOM with no
                // overlay yet. The LaunchedEffect below covers the already-loaded page and tab swaps.
                if (siteTintColor != null) {
                    engineSession.injectSiteTint(siteTintColor)
                }
            }
            fun injectAmoInstaller(url: String, force: Boolean = false) {
                if (addonController == null || !url.isAmoPageUrl()) return
                if (amoInstallerInjectedForCurrentLoad && !force) return
                amoInstallerInjectedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                engineSession.injectAmoAddonInstaller()
            }
            fun injectCleanPlayer(url: String) {
                if (!url.isYouTubePageUrl()) return
                suppressJavascriptLoadCallback = true
                engineSession.injectYouTubeCleanPlayer()
            }
            fun injectOtpDetector() {
                // Detect SMS one-time-code fields so the host can arm SMS User Consent. Cheap DOM
                // scan + one focus listener; runs once per load like the other injectors.
                if (otpDetectorInjectedForCurrentLoad) return
                otpDetectorInjectedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                engineSession.loadJavascriptUrl(OtpAutofill.buildDetectScript())
            }
            fun injectLoginDetector() {
                // Detect a visible, empty password field so the host can offer saved logins *before*
                // the field is tapped (keyboard-free autofill). Same cheap once-per-load scan + focus
                // listener as the OTP detector.
                if (loginDetectorInjectedForCurrentLoad) return
                loginDetectorInjectedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                engineSession.loadJavascriptUrl(LoginFieldProbe.buildDetectScript())
            }
            fun injectFormFieldMemory() {
                // Generic (non-login/card/address) form-field memory: capture eligible values on
                // submit/blur and offer an in-page suggestion dropdown on focus. Behavior is gated at
                // the host by FormFieldMemory.enabled (the "Remember form fields" pref), so the script
                // is cheap and self-disarming when off. Once-per-load like the other detectors.
                if (formFieldMemoryInjectedForCurrentLoad) return
                formFieldMemoryInjectedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                // Bind the bridge's stored-data lookups to this tab's REAL origin, so a page can only
                // ever read/write its own form-memory bucket (never a host it claims in the payload).
                BridgeGuard.setTrustedOrigin(tabId, currentUrl)
                engineSession.loadJavascriptUrl(FormFieldMemory.buildScript())
            }
            fun installAddonRequest(url: String): Boolean {
                val handled = addonController?.installFromPageRequest(url) == true
                if (handled) engineSession.stopLoading()
                return handled
            }
            fun handleFirefoxAuthRedirect(url: String): Boolean {
                val handled = onFirefoxAuthRedirect(url)
                if (handled) engineSession.stopLoading()
                return handled
            }
            fun triggerInitialReaderCheck() {
                if (tabId != null) {
                    val isReaderActive = ReaderManager.instance.isReaderActive(tabId)
                    if (isReaderActive && readerExtractedForCurrentLoad) {
                        android.util.Log.d("ReaderDbg", "triggerInitialReaderCheck: reader is active and already extracted once for this load, skipping")
                        return
                    }
                    readerExtractedForCurrentLoad = true
                    com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor.extractReaderContent(engineSession, tabId)
                }
            }

            val observer = object : Observer {
                override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                    android.util.Log.d("ReaderDbg", "onLocationChange tab=$tabId url=$url")
                    if (handleFirefoxAuthRedirect(url)) return
                    if (!url.startsWith("javascript:", ignoreCase = true)) {
                        val navigatedAway = url != currentUrl
                        // Capture the page being left NOW, before `currentUrl` moves and before
                        // `isSafeToSnapshot` is cleared. For an SPA (pushState) navigation this is the
                        // only callback that fires, so it is the sole chance to snapshot the outgoing
                        // page under its own URL — without it the previous history entry has no snapshot
                        // and predictive-back reveals a blank cover (e.g. navigating through reddit web).
                        if (navigatedAway) captureLeavingPage()
                        isSafeToSnapshot = false
                        currentUrl = url
                        // Keep the form-memory origin binding in step with client-side (pushState)
                        // navigations: the already-injected script survives them and keeps querying.
                        BridgeGuard.setTrustedOrigin(tabId, url)
                        capturedLeavingUrl = null
                        injectedForCurrentLoad = false
                        amoInstallerInjectedForCurrentLoad = false
                        readerExtractedForCurrentLoad = false
                        suppressJavascriptLoadCallback = false
                        injectAmoInstaller(url)
                        injectCleanPlayer(url)
                        if (navigatedAway) {
                            if (tabId != null) {
                                com.firebolt9907.luminabrowser.ui.reader.ReaderManager.instance.clearReaderState(tabId)
                            }
                        }
                        scheduleSpaSnapshotRearm()
                    }
                }

                override fun onFirstContentfulPaint() {
                    isSafeToSnapshot = true
                    injectSpacer()
                    injectAmoInstaller(currentUrl)
                    injectCleanPlayer(currentUrl)
                    injectOtpDetector()
                    injectLoginDetector()
                    injectFormFieldMemory()
                    captureSnapshot(capturingUrl = currentUrl, deduped = true)
                    notifyPainted()
                    if (tabId != null) {
                        suppressJavascriptLoadCallback = true
                        triggerInitialReaderCheck()
                    }
                }

                override fun onLoadingStateChange(loading: Boolean) {
                    android.util.Log.d("ReaderDbg", "onLoadingStateChange tab=$tabId loading=$loading suppress=$suppressJavascriptLoadCallback url=$currentUrl")
                    if (suppressJavascriptLoadCallback) {
                        if (!loading) suppressJavascriptLoadCallback = false
                        return
                    }
                    if (loading) {
                        realLoadInProgress = true
                        isSafeToSnapshot = false
                        captureLeavingPage()
                        injectedForCurrentLoad = false
                        amoInstallerInjectedForCurrentLoad = false
                        readerExtractedForCurrentLoad = false
                        otpDetectorInjectedForCurrentLoad = false
                        loginDetectorInjectedForCurrentLoad = false
                        formFieldMemoryInjectedForCurrentLoad = false
                    } else {
                        realLoadInProgress = false
                        isSafeToSnapshot = true
                        injectSpacer()
                        injectAmoInstaller(currentUrl)
                        injectCleanPlayer(currentUrl)
                        injectOtpDetector()
                        injectLoginDetector()
                        injectFormFieldMemory()
                        captureSnapshot(capturingUrl = currentUrl, deduped = true)
                        notifyPainted()
                        if (tabId != null) {
                            suppressJavascriptLoadCallback = true
                            triggerInitialReaderCheck()
                        }
                    }
                }

                override fun onContentPermissionRequest(
                    permissionRequest: mozilla.components.concept.engine.permission.PermissionRequest,
                ) {
                    if (sitePermissionController != null) {
                        sitePermissionController.onContentPermissionRequest(permissionRequest)
                    } else {
                        permissionRequest.reject()
                    }
                }

                override fun onCancelContentPermissionRequest(
                    permissionRequest: mozilla.components.concept.engine.permission.PermissionRequest,
                ) {
                    sitePermissionController?.onCancelContentPermissionRequest(permissionRequest)
                }

                override fun onAppPermissionRequest(
                    permissionRequest: mozilla.components.concept.engine.permission.PermissionRequest,
                ) {
                    if (sitePermissionController != null) {
                        sitePermissionController.onAppPermissionRequest(permissionRequest)
                    } else {
                        permissionRequest.reject()
                    }
                }

                override fun onLoadRequest(
                    url: String,
                    triggeredByRedirect: Boolean,
                    triggeredByWebContent: Boolean,
                ) {
                    if (handleFirefoxAuthRedirect(url)) return
                    if (!triggeredByRedirect && currentUrl.isNotBlank() && currentUrl != url) {
                        realLoadInProgress = true
                        captureLeavingPage()
                    }
                    if (installAddonRequest(url)) return
                }

                override fun onExternalResource(
                    url: String,
                    fileName: String?,
                    contentLength: Long?,
                    contentType: String?,
                    cookie: String?,
                    userAgent: String?,
                    isPrivate: Boolean,
                    skipConfirmation: Boolean,
                    openInApp: Boolean,
                    response: mozilla.components.concept.fetch.Response?,
                ) {
                    installAddonRequest(url)
                }

                override fun onWindowRequest(windowRequest: WindowRequest) {
                    when (windowRequest.type) {
                        WindowRequest.Type.OPEN -> onOpenWindowRequest(windowRequest)
                        WindowRequest.Type.CLOSE -> Unit
                    }
                }

                override fun onFullScreenChange(enabled: Boolean) {
                    onFullScreenChange(enabled)
                }

                private fun notifyPainted() {
                    tabId?.let(onTabPainted)
                }
            }
            engineSession.register(observer)
            injectSpacer(force = true)
            injectAmoInstaller(currentUrl, force = true)
            triggerInitialReaderCheck()
            onDispose {
                spaSettleRunnable?.let(hostView::removeCallbacks)
                engineSession.unregister(observer)
                BridgeGuard.forget(tabId)
            }
        }
    }

    LaunchedEffect(engineSession, pageZoomFactor, tabId) {
        if (engineSession == null) return@LaunchedEffect
        engineSession.injectPageZoom(pageZoomFactor)
    }

    // System-color boost: apply to the current page immediately when toggled on / tab changes, and
    // remove it when toggled off. Per-load re-application (new DOM after a navigation) is handled in
    // injectSpacer above. Mirrors injectPageZoom's immediate-apply LaunchedEffect.
    LaunchedEffect(engineSession, siteTintColor, tabId) {
        if (engineSession == null) return@LaunchedEffect
        if (siteTintColor != null) engineSession.injectSiteTint(siteTintColor)
        else engineSession.removeSiteTint()
    }

    // When a web-content keyboard is showing, shrink the EngineView so its bottom edge sits at the
    // keyboard top. Edge-to-edge suppresses the window-level `adjustResize`, so GeckoView would
    // otherwise stay full-height and Gecko's own IME handling pushes the focused input off-screen
    // (the page shifts down by ~the keyboard height). Physically resizing the view gives Gecko the
    // correct viewport height, so it re-centers / scrolls the focused input into the visible area.
    val keyboardPad = with(density) { keyboardInsetPx.coerceAtLeast(0).toDp() }
    val engineModifier = if (topPaddingFixActive) {
        modifier
            .fillMaxSize()
            .padding(top = with(density) { topChromeInsetPx.toDp() }, bottom = keyboardPad)
    } else {
        modifier
            .fillMaxSize()
            .padding(bottom = keyboardPad)
    }
    AndroidView(
        factory = { pullContainer },
        modifier = engineModifier,
    ) { _ ->
        engineView.setActivityContext(context)
        pullContainer.onPull = onTopOverscrollPull
        pullContainer.onRelease = onTopOverscrollRelease
        pullContainer.onTouchDown = onPageTouch
        engineView.asView().setOnTouchListener { _, event ->
            predictiveBackVelocityTracker?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    predictiveBackVelocityTracker?.recycle()
                    predictiveBackVelocityTracker = VelocityTracker.obtain().apply {
                        addMovement(event)
                    }
                    gestureStartX = event.rawX
                    gestureStartY = event.rawY
                    lastTouchY = event.rawY
                    pullingFromTop = false
                    predictiveBackEligible = canStartPredictiveBack && event.x <= predictiveBackEdgeWidthPx
                    predictiveBackActive = false
                    predictiveBackProgressPx = 0f
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!predictiveBackActive && predictiveBackEligible) {
                        val deltaX = event.rawX - gestureStartX
                        val deltaY = event.rawY - gestureStartY
                        if (abs(deltaX) > touchSlopPx || abs(deltaY) > touchSlopPx) {
                            if (deltaX > 0f && abs(deltaX) > abs(deltaY)) {
                                predictiveBackActive = true
                                predictiveBackProgressPx = deltaX
                                pullingFromTop = false
                                onPredictiveBackProgress(deltaX)
                                return@setOnTouchListener true
                            }
                            predictiveBackEligible = false
                        }
                    }
                    if (predictiveBackActive) {
                        predictiveBackProgressPx = (event.rawX - gestureStartX).coerceAtLeast(0f)
                        onPredictiveBackProgress(predictiveBackProgressPx)
                        return@setOnTouchListener true
                    }
                    // Pull-to-refresh is handled by the enclosing SwipeRefreshLayout.
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val velocityTracker = predictiveBackVelocityTracker
                    if (predictiveBackActive) {
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                            onPredictiveBackCancel()
                        } else {
                            velocityTracker?.computeCurrentVelocity(1000)
                            onPredictiveBackEnd(
                                predictiveBackProgressPx,
                                velocityTracker?.xVelocity ?: 0f,
                            )
                        }
                        predictiveBackActive = false
                        predictiveBackEligible = false
                        predictiveBackProgressPx = 0f
                        velocityTracker?.recycle()
                        predictiveBackVelocityTracker = null
                        return@setOnTouchListener true
                    }
                    predictiveBackEligible = false
                    predictiveBackProgressPx = 0f
                    velocityTracker?.recycle()
                    predictiveBackVelocityTracker = null
                    false
                }
                else -> {
                    false
                }
            }
        }
    }
}

/**
 * A memory-light RGB_565 copy for resident snapshot storage (half the heap of the ARGB_8888
 * capture; opaque web pages lose nothing visually). Falls back to the original on OOM.
 */
private fun android.graphics.Bitmap.toResident565(): android.graphics.Bitmap {
    if (config == android.graphics.Bitmap.Config.RGB_565) return this
    return runCatching { copy(android.graphics.Bitmap.Config.RGB_565, false) }.getOrNull() ?: this
}

/**
 * Depth-first search for the [org.mozilla.geckoview.GeckoView] inside an [EngineView]'s view tree.
 * mozilla-components' `GeckoEngineView` is a FrameLayout wrapping a `NestedGeckoView` (a GeckoView
 * subclass); the child isn't publicly accessible, so we locate it by type to reach GeckoView's
 * public `coverUntilFirstPaint`.
 */
private fun findGeckoView(root: android.view.View): org.mozilla.geckoview.GeckoView? {
    if (root is org.mozilla.geckoview.GeckoView) return root
    if (root is android.view.ViewGroup) {
        for (i in 0 until root.childCount) {
            findGeckoView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
