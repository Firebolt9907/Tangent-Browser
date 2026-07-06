package com.firebolt9907.luminabrowser.ui.customtab

import android.content.Intent
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.firebolt9907.luminabrowser.ui.PromptHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.luminabrowser.LuminaApplication
import com.firebolt9907.luminabrowser.state.compactHost
import com.firebolt9907.luminabrowser.ui.reader.ReaderManager
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction

/**
 * The slim Custom-Tab surface: a single [com.firebolt9907.luminabrowser.ui.content.ContentHost]
 * bound to the external link's custom-tab session, an optional reader overlay, and a bottom bar
 * (domain, reload, share, "Open in Lumina", close). Deliberately not `LuminaRoot` — there is no
 * tab grid, tab-switching, or address bar here.
 *
 * Back behaviour: while the page has web history we run an in-page predictive back (translate + go
 * back); with no history, Back closes the activity — via `finish()` when the CCT caller supplied
 * exit animations (so [com.firebolt9907.luminabrowser.CustomTabActivity] plays the client's close
 * animation), otherwise with **no** back callback registered so the OS finishes the activity and
 * plays its native predictive-back animation to the calling app.
 */
@Composable
fun CustomTabRoot(
    customTabId: String?,
    hasCloseAnimation: Boolean,
    onOpenInLumina: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val components = (context.applicationContext as LuminaApplication).components
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val browserState by components.browserState.collectAsStateWithLifecycle()
    val customTab = customTabId?.let { id -> browserState.customTabs.firstOrNull { it.id == id } }
    val session = customTab?.engineState?.engineSession
    val url = customTab?.content?.url.orEmpty()
    val canGoBack = customTab?.content?.canGoBack == true

    val readerManager = remember { ReaderManager.instance }
    val activeReaderTabs by readerManager.activeReaderTabs.collectAsStateWithLifecycle()
    val eligibleReaderTabs by readerManager.eligibleReaderTabs.collectAsStateWithLifecycle()
    val tabArticles by readerManager.tabArticles.collectAsStateWithLifecycle()
    val readerTheme by readerManager.theme.collectAsStateWithLifecycle()
    val readerFontSize by readerManager.fontSize.collectAsStateWithLifecycle()
    val readerFontName by readerManager.fontName.collectAsStateWithLifecycle()
    val readerActive = customTabId != null && activeReaderTabs.contains(customTabId)
    val article = customTabId?.let { tabArticles[it] }

    // Reader appearance (theme / font size / font) lives in the Context-less ReaderManager
    // singleton, which the main browser hydrates from GlobalPreferencesStore. A Custom Tab runs in
    // its own task and may launch without MainActivity ever having done that, so the reader overlay
    // here would otherwise fall back to the defaults (White / 100% / System). Load the persisted
    // values so the auto-reader honors the settings chosen in the main window. Read-only: this
    // surface has no reader-appearance controls, so there is nothing to mirror back to disk.
    LaunchedEffect(Unit) {
        val prefs = components.globalPreferencesStore
        prefs.readerThemeName
            ?.let { name ->
                runCatching { com.firebolt9907.luminabrowser.ui.reader.ReaderTheme.valueOf(name) }.getOrNull()
            }
            ?.let { readerManager.setTheme(it) }
        readerManager.setFontSize(prefs.readerFontSize)
        readerManager.setFontName(prefs.readerFontName)
    }

    // A custom tab may arrive without a live engine session (lazy creation); ask the store to make
    // one so ContentHost can render it. Mirrors the CreateEngineSessionAction path in LuminaRoot.
    LaunchedEffect(customTabId, session == null) {
        val id = customTabId ?: return@LaunchedEffect
        if (customTab != null && customTab.engineState.engineSession == null) {
            components.store.dispatch(EngineAction.CreateEngineSessionAction(id))
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
        if (eligibleReaderTabs.contains(id) && !readerManager.isReaderActive(id)) {
            readerAutoActivated = true
            readerManager.setReaderActive(id, true)
        }
    }

    var contentSize by remember { mutableStateOf(IntSize.Zero) }
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

    // Bottom-bar auto-hide: 0 fully shown, 1 fully hidden (translated off the bottom of the screen).
    // Scrolling the page down past a small slop hides it; scrolling up reveals it. Same idea as the
    // main browser's reader-pill auto-hide. [barTotalHeightPx] is the measured bar height including
    // its navigation-bar padding, so a value of 1 slides it completely out of view.
    val barHidden = remember { Animatable(0f) }
    var barTotalHeightPx by remember { mutableIntStateOf(barHeightPx) }
    val barHideSlopPx = with(density) { 6.dp.toPx() }
    fun onPageScrollDelta(delta: Float) {
        val target = when {
            delta > barHideSlopPx -> 1f
            delta < -barHideSlopPx -> 0f
            else -> return
        }
        if (barHidden.targetValue == target) return
        scope.launch {
            barHidden.animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }
    // Always reveal the bar when the reader overlay opens or closes so it never gets stuck hidden.
    LaunchedEffect(readerActive) {
        barHidden.snapTo(0f)
    }

    LuminaBrowserTheme {
        // Lowest-priority back handler (registered first, so the reader/history handlers below win
        // whenever they're enabled): when the activity resolved a close animation (CCT caller exit
        // bundle, or our own fallback for Google-app/Discover launches), Back must route through
        // Activity.finish() so CustomTabActivity can play it via overridePendingTransition — but
        // as a predictive drag, not a dead intercept: [closeSlide] makes the page track the finger
        // and the commit flows into the canned animation. Do NOT replace this with
        // overrideActivityTransition + no callback to get the system's predictive preview: tried,
        // and on this cross-task return to the icon-less, recents-excluded Discover task the
        // system plays nothing at all (hard cut, worse than the canned animation).
        PredictiveBackHandler(enabled = hasCloseAnimation) { progress ->
            var startTouchY = Float.NaN
            try {
                progress.collect { backEvent ->
                    if (startTouchY.isNaN()) startTouchY = backEvent.touchY
                    closeSwipeDir =
                        if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                    closeTouchDy = backEvent.touchY - startTouchY
                    closeSlide = backEvent.progress.coerceIn(0f, 1f)
                }
                animate(closeSlide, 1f, animationSpec = tween(120)) { v, _ -> closeSlide = v }
                // Leave closeSlide at 1: the window's exit animation continues from this frame,
                // resetting it would flash the full-size page before the window fades.
                onClose()
            } catch (_: CancellationException) {
                val cancelSlide = closeSlide
                val cancelDy = closeTouchDy
                animate(1f, 0f, animationSpec = tween(200)) { v, _ ->
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
        BackHandler(enabled = readerActive && !readerAutoActivated) {
            customTabId?.let { readerManager.setReaderActive(it, false) }
        }
        // In-page history back with a predictive slide. Disabled at the first page (no history) and
        // while reader is up, so Back there falls through to the system → native exit to the caller.
        PredictiveBackHandler(enabled = canGoBack && !readerActive) { progress ->
            try {
                progress.collect { backEvent -> backSlide = backEvent.progress.coerceIn(0f, 1f) }
                animate(backSlide, 1f, animationSpec = tween(180)) { v, _ -> backSlide = v }
                customTabId?.let { components.sessionUseCases.goBack.invoke(it) }
                backSlide = 0f
            } catch (_: CancellationException) {
                animate(backSlide, 0f, animationSpec = tween(160)) { v, _ -> backSlide = v }
                backSlide = 0f
            }
        }

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
                    .onSizeChanged { contentSize = it },
            ) {
                if (session != null && customTabId != null) {
                    com.firebolt9907.luminabrowser.ui.content.ContentHost(
                        engine = components.engine,
                        engineSession = session,
                        modifier = Modifier.graphicsLayer {
                            translationX = backSlide * size.width * 0.5f
                            alpha = 1f - backSlide * 0.2f
                        },
                        topChromeInsetPx = statusBarTopPx,
                        bottomChromeInsetPx = barHeightPx,
                        tabId = customTabId,
                        pageUrl = url,
                        snapshotCache = components.tabSnapshotCache,
                        sitePermissionController = components.sitePermissionController,
                        onFirefoxAuthRedirect = components.firefoxSyncController::handleOAuthRedirect,
                        onPageScrollDelta = ::onPageScrollDelta,
                        // The system PredictiveBackHandler above owns the back gesture here, so the
                        // ContentHost's own edge-drag predictive back stays off.
                        canStartPredictiveBack = false,
                    )
                }

                PromptHandler(engineSession = session)

                AnimatedVisibility(
                    visible = readerActive && article != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    article?.let {
                        com.firebolt9907.luminabrowser.ui.reader.ReaderView(
                            article = it,
                            theme = readerTheme,
                            fontSizePercent = readerFontSize,
                            fontName = readerFontName,
                            modifier = Modifier.fillMaxSize(),
                            topPadding = with(density) { statusBarTopPx.toDp() },
                            bottomPadding = barHeightDp,
                            // Hide the bottom taskbar on scroll-down (reveal on scroll-up), same as
                            // the web page — reader scroll deltas drive the same barHidden animation.
                            onScrollDelta = ::onPageScrollDelta,
                        )
                    }
                }

                CustomTabBottomBar(
                    domain = compactHost(url),
                    barHeight = barHeightDp,
                    readerActive = readerActive,
                    onToggleReader = {
                        val id = customTabId
                        if (id != null) {
                            when {
                                // Already reading → back to the web page.
                                readerManager.isReaderActive(id) ->
                                    readerManager.setReaderActive(id, false)
                                // Extracted and ready → just switch it on. This is a deliberate user
                                // tap, so Back should now dismiss the overlay rather than close the tab.
                                eligibleReaderTabs.contains(id) -> {
                                    readerAutoActivated = false
                                    readerManager.setReaderActive(id, true)
                                }
                                // Not extracted yet → arm activation and force an extraction pass so
                                // pages that aren't auto-detected (SPAs, late-loading content) can
                                // still be opened in reader on demand.
                                else -> {
                                    readerAutoActivated = false
                                    readerManager.requestReaderActivation(id)
                                    session?.let {
                                        com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor
                                            .extractReaderContent(it, id)
                                    }
                                }
                            }
                        }
                    },
                    onShare = { shareUrl(context, url) },
                    onOpenInLumina = { customTabId?.let(onOpenInLumina) },
                    onClose = onClose,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { if (it.height > 0) barTotalHeightPx = it.height }
                        .graphicsLayer { translationY = barHidden.value * barTotalHeightPx },
                )
            }
        }
    }
}

@Composable
private fun CustomTabBottomBar(
    domain: String,
    barHeight: androidx.compose.ui.unit.Dp,
    readerActive: Boolean,
    onToggleReader: () -> Unit,
    onShare: () -> Unit,
    onOpenInLumina: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(barHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
            Text(
                text = domain,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = onToggleReader) {
                Icon(
                    Icons.Default.ChromeReaderMode,
                    contentDescription = if (readerActive) "Exit reader mode" else "Reader mode",
                    tint = if (readerActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            IconButton(onClick = onOpenInLumina) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Open in Lumina",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private fun shareUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, null))
}
