package com.firebolt9907.luminabrowser.ui.content

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.net.toUri
import com.firebolt9907.luminabrowser.ui.components.Favicon
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.HitResult

/**
 * The navigable link URL carried by a long-press [HitResult], or null when the hit isn't a web link.
 * A plain `<a href>` reports [HitResult.UNKNOWN] (link in `src`); a linked image reports
 * [HitResult.IMAGE_SRC] (link in `uri`). Only http(s) targets are peekable.
 */
fun linkUrlFromHitResult(hit: HitResult?): String? {
    val raw = when (hit) {
        is HitResult.UNKNOWN -> hit.src
        is HitResult.IMAGE_SRC -> hit.uri
        else -> null
    } ?: return null
    return raw.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }
}

/**
 * Safari/Arc-style link "glance": long-pressing a link opens a floating card.
 *
 * ## Container-transform morph
 * The card doesn't fade in or zoom from a point — it performs a Material **container transform** out
 * of the link the user held. It begins as a small link-sized chip centred on the finger-down point
 * ([origin]) and simultaneously animates its **position, size and corner radius** into the full
 * centred card, while the card's contents cross-fade in as it grows (so you never see the text
 * squished into the chip). The net effect reads as the pressed element itself morphing into the
 * preview, rather than a popup appearing from thin air.
 *
 * (We synthesise the source chip at the touch point rather than reading the DOM element's exact
 * pixel rect: GeckoView only offers one-way `javascript:` injection here, with no return channel to
 * read `getBoundingClientRect`. The touch point is the closest anchor we have.)
 *
 * The live destination page renders in the body and fades in only **after the page finishes
 * loading**.
 *
 * ## Isolation from invariant #1 (single EngineView / snapshot cache)
 * The preview runs on a **standalone** [EngineSession] + [EngineView] supplied by the caller (the
 * shared [PeekEngineHolder]). It is never added to the BrowserStore tab list, never
 * `captureThumbnail`'d, and never touches the tab snapshot cache or the `ContentHost` rebind site —
 * so it can't corrupt thumbnails or fight the main view. The session/view are **reused** across
 * peeks (this card attaches + loads but never releases them), and the caller passes null under
 * memory pressure to fall back to a static glance with no content process at all.
 */
@Composable
fun LinkPeekCard(
    url: String,
    peekSession: EngineSession?,
    peekView: EngineView?,
    origin: Offset,
    sourceRect: Rect?,
    elementBitmap: ImageBitmap?,
    onOpenHere: (String) -> Unit,
    onOpenNewTab: (String) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val host = remember(url) {
        runCatching { url.toUri().host }.getOrNull()?.removePrefix("www.").orEmpty().ifBlank { url }
    }
    var pageTitle by remember(url) { mutableStateOf<String?>(null) }
    // Reveal the web body only once the page has actually finished loading (loading true → false),
    // not merely on first paint — the user asked for a settled page, not a half-drawn one.
    var startedLoading by remember(url) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }

    // Live preview is optional: under memory pressure the caller passes a null session/view and the
    // card shows a static glance (no content process spun up). When present, the session+view come
    // from the shared PeekEngineHolder and are REUSED across peeks — so we attach and load here, but
    // never release/close them (the holder owns their lifecycle).
    val hasLive = peekSession != null && peekView != null
    DisposableEffect(url, peekSession, peekView) {
        val session = peekSession
        val view = peekView
        if (session == null || view == null) return@DisposableEffect onDispose { }
        val observer = object : EngineSession.Observer {
            override fun onTitleChange(title: String) {
                if (title.isNotBlank()) pageTitle = title
            }

            override fun onLoadingStateChange(loading: Boolean) {
                if (loading) startedLoading = true
                else if (startedLoading) loaded = true
            }
        }
        session.register(observer)
        view.render(session)
        session.loadUrl(url)
        onDispose {
            session.unregister(observer)
        }
    }

    val bodyAlpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(260),
        label = "peekBodyAlpha",
    )

    // The morph driver: 0 = source (the pressed link's rect, or a chip at the finger), 1 = full
    // centred card. We wait a beat for LinkRectProbe to resolve the real element rect (same content
    // process, so it lands in a few ms) before starting, so the morph launches from the link itself;
    // if it doesn't arrive in time we start anyway from the finger-point fallback.
    val latestRect by rememberUpdatedState(sourceRect)
    var morphSource by remember(url) { mutableStateOf<Rect?>(null) }
    val morph = remember(url) { Animatable(0f) }
    LaunchedEffect(url) {
        morphSource = withTimeoutOrNull(180) {
            snapshotFlow { latestRect }.filterNotNull().first()
        }
        morph.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    // Dismissal reverses the morph — the card shrinks back into the link before it's removed. All
    // exit paths (Back, scrim tap, close button) route through this so the reverse always plays.
    val scope = rememberCoroutineScope()
    var dismissing by remember(url) { mutableStateOf(false) }
    val startDismiss: () -> Unit = {
        if (!dismissing) {
            dismissing = true
            scope.launch {
                morph.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
                onDismiss()
            }
        }
    }
    // Predictive Back (M3 style): while dragging, the card only scales down slightly and shifts away
    // from the swiped edge — a GPU-only preview that does NOT touch [morph], so the EngineView is
    // never resized. The full reverse morph (shrink back into the link) only runs once the gesture
    // is *committed*. Cancelling springs the preview back to rest. `back`: 0 = rest, 1 = full pull.
    val back = remember(url) { Animatable(0f) }
    var backEdgeSign by remember(url) { mutableStateOf(1f) }
    PredictiveBackHandler(enabled = !dismissing) { progress ->
        try {
            progress.collect { event ->
                backEdgeSign = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                back.snapTo(event.progress.coerceIn(0f, 1f))
            }
            // Committed: settle the preview back to rest while the card morphs into the link.
            dismissing = true
            scope.launch {
                back.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium))
            }
            morph.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
            )
            onDismiss()
        } catch (_: CancellationException) {
            // Cancelled — spring the preview back to the full card; morph stays put.
            back.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Scrim fades in with the morph.
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f * morph.value))
            // Tap outside the card dismisses; no ripple on the scrim itself.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = startDismiss,
            ),
    ) {
        val density = LocalDensity.current
        val p = morph.value

        val fullW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()

        // Destination: the full centred card (matches the previous 0.92 × 0.72 sizing).
        val targetW = fullW * 0.92f
        val targetH = fullH * 0.72f
        val targetLeft = (fullW - targetW) / 2f
        val targetTop = (fullH - targetH) / 2f

        // Source: the pressed link's measured rect when the probe resolved it, else a small
        // link-sized chip centred on the finger. Either way clamped on-screen.
        val probe = morphSource
        val rawSrcW = probe?.width ?: minOf(targetW, with(density) { 220.dp.toPx() })
        val rawSrcH = probe?.height ?: with(density) { 52.dp.toPx() }
        val srcW = rawSrcW.coerceIn(with(density) { 16.dp.toPx() }, fullW)
        val srcH = rawSrcH.coerceIn(with(density) { 16.dp.toPx() }, fullH)
        val srcCenterX = probe?.let { it.left + it.width / 2f } ?: origin.x
        val srcCenterY = probe?.let { it.top + it.height / 2f } ?: origin.y
        val srcLeft = (srcCenterX - srcW / 2f).coerceIn(0f, (fullW - srcW).coerceAtLeast(0f))
        val srcTop = (srcCenterY - srcH / 2f).coerceIn(0f, (fullH - srcH).coerceAtLeast(0f))

        // Interpolated container geometry for this frame (expressed as a transform of the full-size
        // centred card, so nothing is ever re-laid-out).
        val curLeft = lerp(srcLeft, targetLeft, p)
        val curTop = lerp(srcTop, targetTop, p)
        val curW = lerp(srcW, targetW, p)
        val curH = lerp(srcH, targetH, p)

        // Contents fade in during the second half of the growth so the chip never shows squished
        // text — this is what makes it read as a morph, not a zoom.
        val contentAlpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)

        // Predictive-back preview: scale the whole card down and push it away from the swiped edge.
        // Pure graphicsLayer, so the peek EngineView is never resized (that was the source of lag).
        val backProgress = back.value
        val backScale = lerp(1f, 0.78f, backProgress)
        val backShiftPx = with(density) { 64.dp.toPx() } * backProgress * backEdgeSign

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(with(density) { targetW.toDp() }, with(density) { targetH.toDp() })
                .graphicsLayer {
                    scaleX = backScale
                    scaleY = backScale
                    translationX = backShiftPx
                },
        ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Container-transform morph, pivoting from the layout top-left so scale + translate
                // place the card exactly over the source rect. graphicsLayer = GPU only, no relayout.
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = curW / targetW
                    scaleY = curH / targetH
                    translationX = curLeft - targetLeft
                    translationY = curTop - targetTop
                }
                // Consume taps on the card so they don't fall through to the dismiss scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 16.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // The captured element pixels sit behind the card content and cross-fade out as the
                // real header/webview fades in — so the morph appears to lift off the page itself.
                if (elementBitmap != null) {
                    Image(
                        bitmap = elementBitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = (1f - contentAlpha).coerceIn(0f, 1f) },
                    )
                }
            Column(modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
            ) {
                // ---- Header: favicon + title/host + close ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Favicon(url, size = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pageTitle ?: host,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = startDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close preview")
                    }
                }

                // ---- Body ----
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasLive && peekView != null) {
                        // Live preview: the reused peek EngineView, faded in once the page loads.
                        AndroidView(
                            factory = { peekView.asView() },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = bodyAlpha },
                        )
                        if (!loaded) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                            )
                        }
                    } else {
                        // Static glance: the device is low on memory, so we don't spin up a content
                        // process just to preview. The link's actions below still work.
                        Text(
                            text = "Live preview paused to save memory",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }

                // ---- Actions ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PeekAction(Icons.AutoMirrored.Filled.OpenInNew, "Open") { onOpenHere(url) }
                    PeekAction(Icons.Default.Add, "New tab") { onOpenNewTab(url) }
                    PeekAction(Icons.Default.ContentCopy, "Copy") { onCopy(url) }
                    PeekAction(Icons.Default.Share, "Share") { onShare(url) }
                }
            }
            }
        }
        }
    }
}

@Composable
private fun PeekAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
