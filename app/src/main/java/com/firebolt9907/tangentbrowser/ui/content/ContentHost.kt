package com.firebolt9907.tangentbrowser.ui.content

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
import androidx.compose.runtime.rememberUpdatedState
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
import com.firebolt9907.tangentbrowser.addons.AddonController
import com.firebolt9907.tangentbrowser.ui.reader.ReaderManager
import com.firebolt9907.tangentbrowser.state.TabSnapshotCache
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

/**
 * How long a finished sign-in hop must stay put before it counts as interactive and gets web dark
 * mode. Deliberately a little under the "Completing sign-in" cover's own [SettleGraceMs], so the
 * recolor completes while the cover is still up rather than flashing light-to-dark once it lifts.
 */
private const val AuthSettleDarkModeDelayMs = SettleGraceMs - 120L

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ContentHost(
    engine: Engine,
    engineSession: EngineSession?,
    modifier: Modifier = Modifier,
    topChromeInsetPx: Int = 0,
    bottomChromeInsetPx: Int = 0,
    // Extra bottom reserve for a PERSISTENT floating banner above the pill (the archive scrubber).
    // Added to the dynamic-toolbar max height so the dynamic viewport (dvh / visualViewport) shrinks
    // by the banner's footprint while it's showing — fixed/sticky bottom page content then sits above
    // the banner and the page scrolls under it, just like it does for the pill. Lerps away with the
    // pill collapse (the banner hides as the pill compacts). 0 = no persistent banner.
    persistentBannerInsetPx: Int = 0,
    tabId: String? = null,
    pageUrl: String = "",
    snapshotCache: TabSnapshotCache? = null,
    addonController: AddonController? = null,
    sitePermissionController: com.firebolt9907.tangentbrowser.permissions.SitePermissionController? = null,
    sitePreferencesStore: com.firebolt9907.tangentbrowser.state.SitePreferencesStore? = null,
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
    // When true, the high-performance web dark mode's Tier-2 filter-invert fallback is injected on
    // pages that stay light under the native prefers-color-scheme override. Tier 1 (the native
    // override itself) is an engine setting applied in Engine.applyEngineSettings. See WebDarkMode.kt.
    webDarkModeEnabled: Boolean = false,
    // When true (and webDarkModeEnabled), uses the dynamic per-section recoloring engine instead of
    // the composited filter — the DarkReader-dynamic path that fixes mixed light/dark pages. Ignores
    // webDarkModeImageAnalysis (a filter-tier concept). See WebDarkMode.kt.
    webDarkModeAggressive: Boolean = false,
    // Filter-tier only (dynamic mode ignores it): enables experimental Tier-3 pixel-content image
    // classification (photo-vs-graphic from a canvas decode). See WebDarkMode.kt.
    webDarkModeImageAnalysis: Boolean = false,
    // The GLOBAL resolved dark-mode state (before the per-site override folds in). When this is true
    // but webDarkModeEnabled is false, the site is per-site-Off while dark mode is globally on: the
    // document_start pre-paint extension (installed globally) still inverted this page, so its
    // provisional filter must be stripped per-load. See injectSpacer / WebDarkModeExtension.
    webDarkModeGloballyActive: Boolean = false,
    // CSS selectors the user has zapped (tap-to-hide) on this page's host, from ZappedElementsStore.
    // Re-applied as a `display:none` stylesheet on every load and whenever the list changes, so a zap
    // takes effect without a reload and "Restore hidden elements" un-hides just as immediately.
    // See ElementZap.kt.
    zappedSelectors: List<String> = emptyList(),
    pageZoomFactor: Float = 1.0f,
    // Live scroll-collapse progress (0 = pill expanded, 1 = compact), as a lambda so the per-frame
    // spring updates drive the engine's vertical clipping via snapshotFlow without recomposing
    // ContentHost. See the clipping effects below and BarPill.PillMorph.
    barCollapseProgress: () -> Float = { 0f },
    // Bottom reserve (px) that remains obscured when [barCollapseProgress] reaches 1. Defaults to the
    // main browser's compact-pill footprint (compactPill + nav bar), which never fully hides. Surfaces
    // whose chrome slides COMPLETELY off screen (the Custom Tab pill) pass 0 so the engine reclaims
    // the whole strip — the revealed region is then the live, interactive web surface, not a stale
    // clipped band.
    collapsedBottomReservePx: Int? = null,
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
    // System autofill (Android Autofill Framework): when enabled, expose the web-content view so the
    // device's autofill service (the official Bitwarden/1Password app, etc.) can enumerate and fill
    // fields. GeckoView already provides the virtual view structure the OS service reads; we only
    // gate whether the subtree participates. When disabled, exclude it so the OS overlay doesn't
    // compete with the in-browser login client's doorhangers. Read at view creation; a toggle change
    // takes effect on newly created content views.
    LaunchedEffect(engineView) {
        val systemAutofill = com.firebolt9907.tangentbrowser.state.GlobalPreferencesStore(context)
            .systemAutofillEnabled
        engineView.asView().importantForAutofill =
            if (systemAutofill) android.view.View.IMPORTANT_FOR_AUTOFILL_YES
            else android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
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
    // The full bottom reserve when the pill is expanded: the pill's own clip plus any persistent
    // banner floating above it. The banner space is present only at the expanded end of the collapse
    // lerp, so it vanishes as the pill compacts (and the banner hides with it).
    val maxReservePx = expandedClippingPx + persistentBannerInsetPx.coerceAtLeast(0)
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
    LaunchedEffect(engineView, bottomChromeInsetPx, maxReservePx) {
        if (bottomChromeInsetPx > 0) {
            engineView.setDynamicToolbarMaxHeight(maxReservePx)
        } else {
            // No bottom chrome reserved (video fullscreen passes bottomChromeInsetPx=0). Clear any
            // prior toolbar reservation, otherwise GeckoView keeps clipping space for the now-hidden
            // pill and the fullscreen video is pushed up, leaving a black gap at the bottom.
            engineView.setDynamicToolbarMaxHeight(0)
            engineView.setVerticalClipping(0)
            android.util.Log.i(
                "TangentVideo",
                "[clip] fullscreen bottomInset=0 set dtmh=0 clip=0 viewH=${engineView.asView().height} navBarPx=$navBarPx",
            )
        }
    }
    // The current clipping offset tracks the collapse spring on every frame. The pill morph updates
    // barCollapseProgress ~120x/s; observing it here via snapshotFlow drives setVerticalClipping
    // WITHOUT recomposing ContentHost — recomposing the engine host per frame was part of the
    // collapse-scroll frame drops. The obscured height shrinks from the full reserve (bar expanded)
    // toward the compact-pill footprint as the pill collapses.
    val collapsedFloorPx = collapsedBottomReservePx ?: (compactPillPx + navBarPx)
    LaunchedEffect(engineView, bottomChromeInsetPx, maxReservePx, collapsedFloorPx) {
        if (bottomChromeInsetPx <= 0) return@LaunchedEffect
        snapshotFlow { barCollapseProgress().coerceIn(0f, 1f) }.collect { p ->
            val currentObscuredPx = lerp(
                maxReservePx.toFloat(),
                collapsedFloorPx.toFloat(),
                p,
            ).roundToInt().coerceAtLeast(0)
            engineView.setVerticalClipping(currentObscuredPx - maxReservePx)
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
                com.firebolt9907.tangentbrowser.ui.reader.ReaderExtractor.registerPromptDelegate(session, tabId)
                // Route this session's reader-extractor content-script Port to this tab. No-op until
                // the extension finishes installing; triggerInitialReaderCheck re-registers per load,
                // so a session that renders before install completes still gets wired up.
                com.firebolt9907.tangentbrowser.ui.reader.ReaderExtension.registerFor(session, tabId)
                // Route this session's passkey-bridge Port too (no-op unless Bitwarden passkey mode
                // installed the extension). Lets the content script's navigator.credentials override
                // reach the vault.
                com.firebolt9907.tangentbrowser.sync.webauthn.PasskeyExtension.registerFor(session)
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
                maxReservePx.toFloat(),
                (compactPillPx + navBarPx).toFloat(),
                p,
            ).roundToInt().coerceAtLeast(0)
            engineView.setVerticalClipping(obscured - maxReservePx)
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



    // Deliberately NOT a key of the observer effect below: a zap must not tear down and re-register
    // the session observer (which would re-arm every per-load injection guard mid-load). The effect
    // reads the latest list through this holder; the live-apply effect further down handles the
    // "changed while the page is already open" case.
    val currentZappedSelectors by rememberUpdatedState(zappedSelectors)

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
            var archiveMobilifyInjectedForCurrentLoad = false
            var themeColorProbedForCurrentLoad = false
            var blobCaptureInjectedForCurrentLoad = false
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
            // Sample the page's declared theme-color AND the real background behind the status bar
            // (Safari-style). Deliberately NOT injected at first-contentful-paint like the viewport
            // spacers: the top of the page frequently paints its final header/background color only
            // late in the load (web fonts, hydration, a hero image swapping in), so an FCP-time sample
            // would latch a transient color (usually the bare body white) and the status bar / top
            // strip would keep it for the whole session. Instead it runs when the load FINISHES
            // (onLoadingStateChange:false) and after an SPA route settles, so it reads the color the
            // user actually ends up looking at. Cheap one-shot DOM read; falls back natively when no
            // theme color / background can be resolved. Guarded to once per (real or SPA) load.
            fun injectThemeColorProbe(force: Boolean = false) {
                if (themeColorProbedForCurrentLoad && !force) return
                themeColorProbedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                engineSession.loadJavascriptUrl(ThemeColorProbe.buildScript())
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
                    if (!realLoadInProgress) {
                        isSafeToSnapshot = true
                        // The SPA route has settled (no loading-state toggle fires for pushState navs),
                        // so re-sample the top color for the new route's final paint.
                        injectThemeColorProbe()
                    }
                }
                spaSettleRunnable = runnable
                hostView.postDelayed(runnable, SpaSettleCaptureDelayMs)
            }
            /**
             * @param suppressDarkMode strip the provisional inversion instead of applying web dark
             *   mode. Used on sign-in hops: the document_start pre-paint extension inverts every page
             *   before we know what it is, and on a chain that means a burst of half-recolored IdP
             *   pages flashing dark-then-light. Leaving those pages in their native light rendering
             *   is both cheaper and steadier, and it is what the "Completing sign-in" cover is
             *   drawn over. Not a per-site preference — nothing is persisted.
             */
            fun applyWebDarkMode() {
                DarkImageSegmenter.enabled = webDarkModeAggressive
                if (webDarkModeAggressive) DarkImageSegmenter.warmup()
                with(WebDarkMode) {
                    engineSession.injectWebDarkMode(
                        dynamic = webDarkModeAggressive,
                        analyze = webDarkModeImageAnalysis,
                        mlReady = DarkImageSegmenter.modelReady,
                    )
                }
            }
            fun injectSpacer(force: Boolean = false, suppressDarkMode: Boolean = false) {
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
                // Re-apply the system-color boost on every load: a fresh page has a fresh DOM with no
                // overlay yet. The LaunchedEffect below covers the already-loaded page and tab swaps.
                if (siteTintColor != null) {
                    engineSession.injectSiteTint(siteTintColor)
                }
                // Re-apply the web-dark-mode Tier-2 fallback on every load (fresh DOM). The
                // LaunchedEffect below covers the already-loaded page and tab swaps.
                if (suppressDarkMode) {
                    // Undo the document_start pre-paint inversion; do not run the Tier-2 recolor.
                    if (webDarkModeEnabled || webDarkModeGloballyActive) {
                        with(WebDarkMode) { engineSession.removeWebDarkModePrePaint() }
                    }
                } else if (webDarkModeEnabled) {
                    applyWebDarkMode()
                } else if (webDarkModeGloballyActive) {
                    // Site is per-site-Off but dark mode is globally on: the document_start pre-paint
                    // extension inverted this page before we could tell. Strip its provisional filter
                    // (lightweight — no dynamic recolors to undo on a freshly loaded page).
                    with(WebDarkMode) { engineSession.removeWebDarkModePrePaint() }
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
            fun injectArchiveMobilify(url: String) {
                // For any archived (web.archive.org) page: force device-width layout so pre-responsive
                // captures don't render zoomed-out, and hide the Wayback toolbar. Not gated on archive
                // *mode* so a restored archive tab is de-uglified even before archive mode re-activates.
                if (archiveMobilifyInjectedForCurrentLoad) return
                if (!com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance.isArchiveUrl(url)) return
                archiveMobilifyInjectedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                engineSession.injectArchiveMobilify()
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
            fun injectZaps() {
                // Re-hide this host's zapped elements on the fresh DOM. Skipped outright when the host
                // has none, which is the overwhelmingly common case — no injection, no cost. Read
                // through the updated-state holder so a zap made moments ago is included without this
                // (observer-owning) effect having to be keyed on the list and torn down.
                val selectors = currentZappedSelectors
                if (selectors.isEmpty()) return
                suppressJavascriptLoadCallback = true
                engineSession.loadJavascriptUrl(ElementZap.buildApplyScript(selectors))
            }
            fun injectBlobCapture() {
                // A blob: document is the ONLY place its own bytes can still be read (see
                // [BlobCapture]) — the handle dies with the document, so this is a now-or-never
                // copy into the blob cache. Nothing is injected on ordinary pages, and nothing is
                // re-read for a blob already captured (a revisit within the same session).
                if (!com.firebolt9907.tangentbrowser.state.isBlobUrl(currentUrl)) return
                if (blobCaptureInjectedForCurrentLoad) return
                if (com.firebolt9907.tangentbrowser.state.BlobFileStore.lookup(currentUrl) != null) return
                blobCaptureInjectedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                // Bind the capture to this tab's real current URL, so the bridge can't be talked
                // into copying a blob the tab isn't showing.
                BlobCapture.arm(tabId, currentUrl)
                engineSession.loadJavascriptUrl(BlobCapture.buildScript())
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
            // True while the current document is a hop of a federated sign-in chain (see [SsoFlow]).
            // Such a document usually lives a few hundred milliseconds before redirecting on, and is
            // never a page the user asked for, so most of the per-load injector set is pure waste on
            // it. Two of them are worse than waste: form-field memory would record values typed on an
            // identity provider's page, and reader extraction would run a full article walk over a
            // login form. See the callers for exactly what survives.
            fun isAuthHop(): Boolean =
                com.firebolt9907.tangentbrowser.state.SsoFlow.isAuthUrl(currentUrl)
            // Dark mode for a sign-in hop that stops and asks. Transient hops stay in their native
            // light rendering (recoloring a page that lives 300ms is churn for nobody), but a hop that
            // finishes loading and is STILL here a moment later is the password prompt or the
            // number-matching screen — a page the user reads, and the one place leaving it white in a
            // dark browser is worse than the flicker the suppression exists to avoid. Same settle rule
            // the "Completing sign-in" cover uncovers on, run slightly early so the recolor happens
            // underneath the cover instead of in front of the user.
            var authDarkModeRunnable: Runnable? = null
            fun cancelAuthDarkModeSettle() {
                authDarkModeRunnable?.let(hostView::removeCallbacks)
                authDarkModeRunnable = null
            }
            fun scheduleAuthDarkModeSettle() {
                cancelAuthDarkModeSettle()
                if (!webDarkModeEnabled) return
                val settlingUrl = currentUrl
                val runnable = Runnable {
                    authDarkModeRunnable = null
                    // A redirect, a new load, or a tab swap between scheduling and firing cancels this;
                    // the URL check catches anything that slipped past those.
                    if (settlingUrl != currentUrl) return@Runnable
                    SsoSettledPage.url = settlingUrl
                    applyWebDarkMode()
                }
                authDarkModeRunnable = runnable
                hostView.postDelayed(runnable, AuthSettleDarkModeDelayMs)
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
                        com.firebolt9907.tangentbrowser.util.TangentLog.debug("ReaderDbg") { "triggerInitialReaderCheck: reader is active and already extracted once for this load, skipping" }
                        return
                    }
                    readerExtractedForCurrentLoad = true
                    // Prefer the isolated-world content-script Port (works where window.prompt is
                    // CSP-sandbox-blocked — e.g. X). The content script picks article-vs-social
                    // extraction by host. Re-register in case the session rendered before the extension
                    // finished installing. Fall back to the matching javascript: prompt extractor
                    // (social vs article) only when no live port received the request.
                    com.firebolt9907.tangentbrowser.ui.reader.ReaderExtension.registerFor(engineSession, tabId)
                    if (!com.firebolt9907.tangentbrowser.ui.reader.ReaderExtension.requestExtract(engineSession)) {
                        if (com.firebolt9907.tangentbrowser.ui.reader.SocialExtractor.isSocialUrl(currentUrl)) {
                            com.firebolt9907.tangentbrowser.ui.reader.SocialExtractor.extract(engineSession, tabId)
                        } else {
                            com.firebolt9907.tangentbrowser.ui.reader.ReaderExtractor.extractReaderContent(engineSession, tabId)
                        }
                    }
                }
            }

            val observer = object : Observer {
                override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                    com.firebolt9907.tangentbrowser.util.TangentLog.debug("ReaderDbg") { "onLocationChange tab=$tabId url=$url" }
                    if (handleFirefoxAuthRedirect(url)) return
                    if (!url.startsWith("javascript:", ignoreCase = true)) {
                        val navigatedAway = url != currentUrl
                        if (navigatedAway) {
                            cancelAuthDarkModeSettle()
                            if (SsoSettledPage.isSettled(currentUrl)) SsoSettledPage.url = null
                        }
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
                        archiveMobilifyInjectedForCurrentLoad = false
                        themeColorProbedForCurrentLoad = false
                        blobCaptureInjectedForCurrentLoad = false
                        // Leaving the blob document ends the only window in which its bytes are
                        // readable; abandon a capture still streaming rather than keep a partial file.
                        if (navigatedAway) BlobCapture.disarm(tabId)
                        suppressJavascriptLoadCallback = false
                        injectAmoInstaller(url)
                        injectCleanPlayer(url)
                        injectArchiveMobilify(url)
                        if (navigatedAway) {
                            if (tabId != null) {
                                com.firebolt9907.tangentbrowser.ui.reader.ReaderManager.instance.clearReaderState(tabId)
                            }
                        }
                        scheduleSpaSnapshotRearm()
                    }
                }

                override fun onFirstContentfulPaint() {
                    isSafeToSnapshot = true
                    // Transient sign-in hop: arm only the two credential detectors and let the rest
                    // wait for load-complete. The detectors stay because the pages a chain actually
                    // *stops* on are precisely the password prompt and the MFA-code prompt they
                    // exist for — gating them off is what would make autofill look dead mid-login.
                    // A hop that does settle still picks up its spacers (invariant #11) and top-strip
                    // color in onLoadingStateChange below, so an interactive auth page is never left
                    // rendering under the chrome.
                    if (isAuthHop()) {
                        injectOtpDetector()
                        injectLoginDetector()
                        notifyPainted()
                        return
                    }
                    injectZaps()
                    injectSpacer()
                    injectAmoInstaller(currentUrl)
                    injectCleanPlayer(currentUrl)
                    injectArchiveMobilify(currentUrl)
                    injectOtpDetector()
                    injectLoginDetector()
                    injectFormFieldMemory()
                    injectBlobCapture()
                    captureSnapshot(capturingUrl = currentUrl, deduped = true)
                    notifyPainted()
                    if (tabId != null) {
                        suppressJavascriptLoadCallback = true
                        triggerInitialReaderCheck()
                    }
                }

                override fun onLoadingStateChange(loading: Boolean) {
                    com.firebolt9907.tangentbrowser.util.TangentLog.debug("ReaderDbg") { "onLoadingStateChange tab=$tabId loading=$loading suppress=$suppressJavascriptLoadCallback url=$currentUrl" }
                    if (suppressJavascriptLoadCallback) {
                        if (!loading) suppressJavascriptLoadCallback = false
                        return
                    }
                    if (loading) {
                        realLoadInProgress = true
                        isSafeToSnapshot = false
                        // The chain is moving again — whatever settled is no longer on screen.
                        cancelAuthDarkModeSettle()
                        if (SsoSettledPage.isSettled(currentUrl)) SsoSettledPage.url = null
                        captureLeavingPage()
                        injectedForCurrentLoad = false
                        amoInstallerInjectedForCurrentLoad = false
                        readerExtractedForCurrentLoad = false
                        otpDetectorInjectedForCurrentLoad = false
                        loginDetectorInjectedForCurrentLoad = false
                        formFieldMemoryInjectedForCurrentLoad = false
                        archiveMobilifyInjectedForCurrentLoad = false
                        themeColorProbedForCurrentLoad = false
                        blobCaptureInjectedForCurrentLoad = false
                    } else if (isAuthHop()) {
                        // A settled sign-in hop is a real page the user looks at — the password
                        // prompt, the "approve this sign-in" number-matching screen — so it gets the
                        // viewport spacers and the top-strip color like anything else, plus the
                        // credential detectors. It does NOT get form-field memory (never record what
                        // is typed on an identity provider), reader extraction, zaps, or a thumbnail
                        // capture (single-use URLs would just churn the v2 snapshot cache).
                        realLoadInProgress = false
                        isSafeToSnapshot = true
                        injectSpacer(suppressDarkMode = true)
                        injectOtpDetector()
                        injectLoginDetector()
                        injectThemeColorProbe()
                        // ...and, if it is still here a beat later, recolor it: see
                        // scheduleAuthDarkModeSettle.
                        scheduleAuthDarkModeSettle()
                        notifyPainted()
                    } else {
                        realLoadInProgress = false
                        isSafeToSnapshot = true
                        injectZaps()
                        injectSpacer()
                        injectAmoInstaller(currentUrl)
                        injectCleanPlayer(currentUrl)
                        injectArchiveMobilify(currentUrl)
                        injectOtpDetector()
                        injectLoginDetector()
                        injectFormFieldMemory()
                        injectBlobCapture()
                        // Sample the status-bar / top-strip color now that the load has finished, so it
                        // reflects the page's final painted top rather than a transient mid-load color.
                        injectThemeColorProbe()
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

                override fun onLaunchIntentRequest(
                    url: String,
                    appIntent: android.content.Intent?,
                    appName: String?,
                    fallbackUrl: String?,
                ) {
                    // The page tried to open a native app (intent:// or custom scheme). Gecko already
                    // denied the load (we returned AppIntent from the interceptor); relay it so
                    // TangentRoot can auto-open or offer the "Open in <app>" banner. Foreground-scoped:
                    // this observer is only registered on the visible tab's session.
                    if (appIntent != null) {
                        // Resolve the target package so the banner can show the real app icon (the
                        // intent's component was intentionally stripped by the interceptor for safety).
                        val pkg = runCatching {
                            context.packageManager.resolveActivity(appIntent, 0)
                                ?.activityInfo?.packageName
                        }.getOrNull()
                        AppLaunchRequests.offer(
                            tabId = tabId,
                            url = url,
                            intent = appIntent,
                            appName = appName,
                            packageName = pkg,
                            fallbackUrl = fallbackUrl,
                        )
                    }
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
            // Sample the top color at bind time ONLY when the page has already finished loading (tab
            // swap, config change, or the sampling pref toggling on a settled page). If a load is
            // still in flight, do nothing here and let onLoadingStateChange(false) sample it once the
            // page's final top has painted — the whole point of deferring the probe.
            if (!tabIsLoading) injectThemeColorProbe(force = true)
            injectAmoInstaller(currentUrl, force = true)
            // A blob tab that was bound mid-load (or swapped away from before its bytes were read)
            // gets another chance here; the script's own guard makes a second attempt a no-op.
            injectBlobCapture()
            triggerInitialReaderCheck()
            onDispose {
                spaSettleRunnable?.let(hostView::removeCallbacks)
                cancelAuthDarkModeSettle()
                engineSession.unregister(observer)
                BridgeGuard.forget(tabId)
            }
        }
    }

    LaunchedEffect(engineSession, pageZoomFactor, tabId) {
        if (engineSession == null) return@LaunchedEffect
        engineSession.injectPageZoom(pageZoomFactor)
    }

    // Element zap: apply the host's hide-stylesheet to the page already on screen, so a fresh zap
    // takes effect the instant it's stored and a restore un-hides without a reload. An empty list
    // still injects — that's the removal path. Skipped entirely when there is nothing to do (no zaps
    // now and none a moment ago), so ordinary browsing never pays for this. Per-load re-application
    // on a new DOM is injectZaps() above; same split as the site-tint / web-dark-mode effects.
    var hadZaps by remember(tabId) { mutableStateOf(false) }
    LaunchedEffect(engineSession, zappedSelectors, tabId) {
        if (engineSession == null) return@LaunchedEffect
        if (zappedSelectors.isEmpty() && !hadZaps) return@LaunchedEffect
        hadZaps = zappedSelectors.isNotEmpty()
        engineSession.loadJavascriptUrl(ElementZap.buildApplyScript(zappedSelectors))
    }

    // System-color boost: apply to the current page immediately when toggled on / tab changes, and
    // remove it when toggled off. Per-load re-application (new DOM after a navigation) is handled in
    // injectSpacer above. Mirrors injectPageZoom's immediate-apply LaunchedEffect.
    LaunchedEffect(engineSession, siteTintColor, tabId) {
        if (engineSession == null) return@LaunchedEffect
        if (siteTintColor != null) engineSession.injectSiteTint(siteTintColor)
        else engineSession.removeSiteTint()
    }

    // Web dark mode Tier-2 fallback: apply to the current page immediately when toggled on / tab
    // changes, and remove it when toggled off. Per-load re-application (new DOM) is handled in
    // injectSpacer above. Mirrors the system-color boost effect. Tier 1 (the native
    // prefers-color-scheme override) is an engine-wide setting and needs no per-tab injection.
    LaunchedEffect(engineSession, webDarkModeEnabled, webDarkModeAggressive, webDarkModeImageAnalysis, tabId) {
        if (engineSession == null) return@LaunchedEffect
        // TRANSIENT sign-in hops opt out of dark mode (see injectSpacer's suppressDarkMode):
        // recoloring a chain of IdP pages that each live a few hundred ms is visible churn for no
        // benefit, and this effect would otherwise re-apply it behind the per-load suppression. A hop
        // that SETTLED is the exception — it is a page the user is reading (the password prompt), so
        // it keeps the dark mode ContentHost's settle pass gave it rather than having it stripped here
        // on the next tab swap. Deliberately NOT keyed on pageUrl — that would re-run the whole Tier-2
        // injection on every navigation, paying invariant #20's dynamic walk a second time on top of
        // the per-load path in injectSpacer. Read once at launch as a guard instead; the page the
        // chain lands on gets dark mode from that per-load path.
        val darkModeForThisPage = webDarkModeEnabled &&
            (!com.firebolt9907.tangentbrowser.state.SsoFlow.isAuthUrl(pageUrl) ||
                SsoSettledPage.isSettled(pageUrl))
        DarkImageSegmenter.enabled = darkModeForThisPage && webDarkModeAggressive
        if (darkModeForThisPage && webDarkModeAggressive) DarkImageSegmenter.warmup()
        with(WebDarkMode) {
            if (darkModeForThisPage) engineSession.injectWebDarkMode(
                dynamic = webDarkModeAggressive,
                analyze = webDarkModeImageAnalysis,
                mlReady = DarkImageSegmenter.modelReady,
            )
            else engineSession.removeWebDarkMode()
        }
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
