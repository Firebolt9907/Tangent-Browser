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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.firebolt9907.luminabrowser.addons.AddonController
import com.firebolt9907.luminabrowser.state.TabSnapshotCache
import kotlin.math.abs
import kotlin.math.roundToInt
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSession.Observer
import mozilla.components.concept.engine.window.WindowRequest
import kotlinx.coroutines.delay

private const val BottomSpacerExtraCssPx = 10

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
    onTabPainted: (String) -> Unit = {},
    onSnapshotCaptured: (tabId: String, url: String, bitmap: android.graphics.Bitmap) -> Unit = { _, _, _ -> },
    onPageScrollDelta: (Float) -> Unit = {},
    canStartPredictiveBack: Boolean = false,
    onPredictiveBackProgress: (Float) -> Unit = {},
    onPredictiveBackEnd: (progressPx: Float, velocityPxPerSecond: Float) -> Unit = { _, _ -> },
    onPredictiveBackCancel: () -> Unit = {},
    currentSnapshotRequestId: Int = 0,
    onCurrentSnapshotCaptured: (tabId: String, bitmap: android.graphics.Bitmap) -> Unit = { _, _ -> },
    onTopOverscrollPull: (Float) -> Unit = {},
    onTopOverscrollRelease: (Float) -> Unit = {},
    onOpenWindowRequest: (WindowRequest) -> Unit = {},
    onFirefoxAuthRedirect: (String) -> Boolean = { false },
    topPaddingFixActive: Boolean = false,
    pageZoomFactor: Float = 1.0f,
    barCollapseProgressState: Animatable<Float, AnimationVector1D>? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    val predictiveBackEdgeWidthPx = with(density) { 24.dp.toPx() }
    val bottomChromeInsetCssPx = with(density) {
        (bottomChromeInsetPx.toDp().value.toInt() + BottomSpacerExtraCssPx).coerceAtLeast(0)
    }
    val topChromeInsetCssPx = with(density) {
        topChromeInsetPx.toDp().value.toInt().coerceAtLeast(0)
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
        val id = capturingId ?: return
        val cache = snapshotCache ?: return
        if (deduped) {
            val key = "$id|$capturingUrl"
            val now = System.currentTimeMillis()
            if (key == lastCaptureKey && (now - lastCaptureMs) < 750L) return
            lastCaptureKey = key
            lastCaptureMs = now
        }
        engineView.captureThumbnail { bitmap: android.graphics.Bitmap? ->
            if (bitmap != null) {
                cache.put(id, bitmap, capturingUrl)
                onSnapshotCaptured(id, capturingUrl, bitmap)
            }
        }
    }

    // SafariBar layout hardcodes 24dp bottom padding to clear the gesture nav bar, but the
    // actual nav bar height varies by device. Use the real inset so fixed-bottom web elements
    // land exactly at the pill's top edge rather than above it.
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    val barPaddingPx = with(density) { 24.dp.roundToPx() }
    val compactPillPx = with(density) { 44.dp.roundToPx() }
    val collapseProgress = barCollapseProgressState?.value ?: 0f
    val expandedClippingPx = (bottomChromeInsetPx - barPaddingPx + navBarPx).coerceAtLeast(0)
    val currentClippingPx = lerp(
        expandedClippingPx.toFloat(),
        (compactPillPx + navBarPx).toFloat(),
        collapseProgress,
    ).roundToInt().coerceAtLeast(0)
    SideEffect {
        if (bottomChromeInsetPx > 0) {
            engineView.setDynamicToolbarMaxHeight(expandedClippingPx)
            engineView.setVerticalClipping(currentClippingPx)
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
            if (tabId != null) {
                com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor.registerPromptDelegate(session, tabId)
            }
        }
        onDispose {
            if (engineSession != null) captureSnapshot(tabId)
        }
    }

    LaunchedEffect(engineView, engineSession, tabId) {
        if (engineSession == null || tabId == null || snapshotCache == null) return@LaunchedEffect
        delay(600)
        captureSnapshot(tabId, pageUrl, deduped = true)
    }

    LaunchedEffect(engineView, currentSnapshotRequestId, tabId, pageUrl) {
        if (currentSnapshotRequestId <= 0 || tabId == null) return@LaunchedEffect
        val capturedUrl = pageUrl
        engineView.captureThumbnail { bitmap: android.graphics.Bitmap? ->
            if (bitmap != null) {
                snapshotCache?.put(tabId, bitmap, capturedUrl)
                onSnapshotCaptured(tabId, capturedUrl, bitmap)
                onCurrentSnapshotCaptured(tabId, bitmap)
            }
        }
    }

    LaunchedEffect(engineView, engineSession, tabId) {
        if (engineSession == null) return@LaunchedEffect
        engineView.verticalScrollDelta.collect { delta ->
            onPageScrollDelta(delta)
        }
    }

    DisposableEffect(engineSession, topChromeInsetCssPx, bottomChromeInsetCssPx, tabId, topPaddingFixActive) {
        if (engineSession == null) {
            onDispose { }
        } else {
            var injectedForCurrentLoad = false
            var amoInstallerInjectedForCurrentLoad = false
            var suppressJavascriptLoadCallback = false
            var currentUrl = pageUrl
            var capturedLeavingUrl: String? = null
            fun captureLeavingPage() {
                if (currentUrl.isBlank() || currentUrl == "about:blank") return
                if (capturedLeavingUrl == currentUrl) return
                capturedLeavingUrl = currentUrl
                captureSnapshot(capturingUrl = currentUrl)
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
            }
            fun injectAmoInstaller(url: String, force: Boolean = false) {
                if (addonController == null || !url.isAmoPageUrl()) return
                if (amoInstallerInjectedForCurrentLoad && !force) return
                amoInstallerInjectedForCurrentLoad = true
                suppressJavascriptLoadCallback = true
                engineSession.injectAmoAddonInstaller()
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
            val observer = object : Observer {
                override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                    if (handleFirefoxAuthRedirect(url)) return
                    if (!url.startsWith("javascript:", ignoreCase = true)) {
                        currentUrl = url
                        capturedLeavingUrl = null
                        injectedForCurrentLoad = false
                        amoInstallerInjectedForCurrentLoad = false
                        suppressJavascriptLoadCallback = false
                        injectAmoInstaller(url)
                        if (tabId != null) {
                            com.firebolt9907.luminabrowser.ui.reader.ReaderManager.instance.clearReaderState(tabId)
                        }
                    }
                }

                override fun onFirstContentfulPaint() {
                    injectSpacer()
                    injectAmoInstaller(currentUrl)
                    captureSnapshot(capturingUrl = currentUrl, deduped = true)
                    notifyPainted()
                    if (tabId != null && !suppressJavascriptLoadCallback) {
                        suppressJavascriptLoadCallback = true
                        com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor.extractReaderContent(engineSession, tabId)
                    }
                }

                override fun onLoadingStateChange(loading: Boolean) {
                    if (suppressJavascriptLoadCallback) {
                        if (!loading) suppressJavascriptLoadCallback = false
                        return
                    }
                    if (loading) {
                        captureLeavingPage()
                        injectedForCurrentLoad = false
                        amoInstallerInjectedForCurrentLoad = false
                    } else {
                        injectSpacer()
                        injectAmoInstaller(currentUrl)
                        captureSnapshot(capturingUrl = currentUrl, deduped = true)
                        notifyPainted()
                        if (tabId != null) {
                            suppressJavascriptLoadCallback = true
                            com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor.extractReaderContent(engineSession, tabId)
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
                        captureLeavingPage()
                    }
                    installAddonRequest(url)
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

                private fun notifyPainted() {
                    tabId?.let(onTabPainted)
                }
            }
            engineSession.register(observer)
            injectSpacer(force = true)
            injectAmoInstaller(currentUrl, force = true)
            onDispose { engineSession.unregister(observer) }
        }
    }

    LaunchedEffect(engineSession, pageZoomFactor, tabId) {
        if (engineSession == null) return@LaunchedEffect
        engineSession.injectPageZoom(pageZoomFactor)
    }

    val engineModifier = if (topPaddingFixActive) {
        modifier
            .fillMaxSize()
            .padding(top = with(density) { topChromeInsetPx.toDp() })
    } else {
        modifier.fillMaxSize()
    }
    AndroidView(
        factory = { pullContainer },
        modifier = engineModifier,
    ) { _ ->
        engineView.setActivityContext(context)
        pullContainer.onPull = onTopOverscrollPull
        pullContainer.onRelease = onTopOverscrollRelease
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
