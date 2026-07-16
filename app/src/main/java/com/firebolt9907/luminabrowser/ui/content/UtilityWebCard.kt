package com.firebolt9907.luminabrowser.ui.content

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession

/**
 * What a [UtilityWebCard] should render. Both variants run on a **standalone** [EngineSession] +
 * [EngineView] that is never added to the BrowserStore — the same isolation the link-glance
 * ([LinkPeekCard]) uses. That isolation is the whole point here:
 *
 * - the user never leaves the page they're on (the popup / options page / sign-in floats over it);
 * - the current tab stays the *active* tab for web extensions, so an add-on's quick settings and
 *   options page act on **that** page rather than detecting the add-on's own config document as the
 *   active tab (which is what happened when these opened as real foreground tabs).
 */
sealed interface UtilityWebRequest {
    val title: String

    /**
     * An add-on browser/page action popup ("quick settings"). Gecko created [session] in
     * `onToggleActionPopup` and loads the popup document into it; we only render it.
     */
    data class Popup(override val title: String, val session: EngineSession) : UtilityWebRequest

    /**
     * A URL to load in a fresh standalone session — an add-on options page
     * (`moz-extension://…/options.html`) or the Firefox sign-in OAuth flow.
     */
    data class Url(override val title: String, val url: String) : UtilityWebRequest
}

/**
 * A floating card that hosts a standalone GeckoView, reusing the same isolation approach as the
 * link-glance preview (own [EngineSession] + [EngineView], never in the BrowserStore, never
 * snapshot-cached, never marked active for web extensions).
 *
 * Used for add-on quick settings, add-on options pages, and the Firefox Sync sign-in flow so none of
 * them yank the user off their current page — and so add-on APIs keep seeing the user's page as the
 * active tab. Deliberately **not** wired to the peek engine's low-memory drop: an options page or a
 * half-finished sign-in must survive a transient `onTrimMemory`, unlike a throwaway link glance.
 *
 * [onIntercept] is consulted on every navigation; returning true (an add-on `.xpi` install request,
 * or the FxA OAuth success redirect) both handles the URL and dismisses the card.
 */
@Composable
fun UtilityWebCard(
    request: UtilityWebRequest,
    engine: Engine,
    onIntercept: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember(request) { mutableStateOf(request.title) }
    var loaded by remember(request) { mutableStateOf(false) }

    // Fresh EngineView per request. Created eagerly (not inside the DisposableEffect) so the
    // AndroidView below can mount it in the same composition.
    val view = remember(request) {
        engine.createView(context).also { it.setActivityContext(context) }
    }

    DisposableEffect(request, view) {
        // Popup: adopt the Gecko-created session (it's already loading the popup doc). Url: spin up a
        // fresh session and load it ourselves.
        val session = when (request) {
            is UtilityWebRequest.Popup -> request.session
            is UtilityWebRequest.Url -> engine.createSession().also { it.loadUrl(request.url) }
        }
        val observer = object : EngineSession.Observer {
            override fun onTitleChange(t: String) {
                if (t.isNotBlank()) title = t
            }

            override fun onLoadingStateChange(loading: Boolean) {
                if (!loading) loaded = true
            }

            override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                if (onIntercept(url)) onDismiss()
            }
        }
        session.register(observer)
        view.render(session)
        onDispose {
            session.unregister(observer)
            // We own the whole standalone stack here (unlike the peek holder, which is reused across
            // glances): release the view and close the session — including the popup session, whose
            // real-tab equivalent was closed when its tab closed.
            runCatching { view.release() }
            runCatching { session.close() }
        }
    }

    // Simple scale + fade entrance (no link-morph — there's no source element to grow from).
    val appear = remember(request) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(request) {
        appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 450f))
    }

    // Predictive Back / back gesture dismisses the card.
    val back = remember(request) { androidx.compose.animation.core.Animatable(0f) }
    PredictiveBackHandler { progress ->
        try {
            progress.collect { event -> back.snapTo(event.progress.coerceIn(0f, 1f)) }
            onDismiss()
        } catch (_: CancellationException) {
            back.animateTo(0f)
        }
    }

    val enter by animateFloatAsState(targetValue = appear.value, label = "utilityAppear")
    val cardScale = lerp(0.92f, 1f, enter) * lerp(1f, 0.9f, back.value)
    val cardAlpha = enter * lerp(1f, 0.6f, back.value)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f * enter))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.92f)
                .fillMaxSize(0.85f)
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                    alpha = cardAlpha
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
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { view.asView() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!loaded) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
        }
    }
}
