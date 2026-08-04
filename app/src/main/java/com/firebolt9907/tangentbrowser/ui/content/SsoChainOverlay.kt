package com.firebolt9907.tangentbrowser.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.state.SsoChainStats
import com.firebolt9907.tangentbrowser.state.SsoFlow
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import kotlinx.coroutines.delay

/**
 * A full-screen "Completing sign-in" cover held across the transient hops of a federated sign-in
 * chain, so an enterprise SSO login reads as one wait instead of ten pages flashing past.
 *
 * The chain itself is not shortened — it *can't* be, every hop carries a single-use server-minted
 * token (see [SsoFlow]). This only hides the churn.
 *
 * ## The uncover rule is the whole design
 *
 * A chain is not uniformly transient. Most hops are machine-to-machine redirects that paint for a
 * few hundred milliseconds, but two or three of them **stop and ask for something**: the password
 * prompt, and — since Microsoft Authenticator replaced Duo — a number-matching screen whose entire
 * purpose is that the user *reads a two-digit number off the page*. Covering those would turn a
 * cosmetic win into a broken login.
 *
 * So the cover is not driven by "is this host an IdP". It is driven by **whether the document
 * settles**: a hop that finishes loading and is still there [SettleGraceMs] later is asking for
 * input, and is uncovered and remembered ([interactiveUrl]) so it is never re-covered while it sits
 * idle. A hop that redirects onward inside the grace window never settles, so the cover rides
 * straight through it. That single rule separates the two cases without needing to inspect page
 * content, and it fails in the safe direction — an unrecognised interactive page uncovers itself.
 *
 * Two more escape hatches, because a stuck cover is far worse than a visible redirect:
 * [LandingGraceMs] bounds how long the cover survives after the chain exits onto a real page, and
 * [MaxVisibleMs] is an unconditional ceiling on the whole thing.
 */
internal const val SettleGraceMs = 420L
private const val LandingGraceMs = 2_500L
private const val MaxVisibleMs = 20_000L

/**
 * The sign-in hop that has **settled** — i.e. stopped loading and stayed, so it is asking the user for
 * something (the password prompt, the number-matching screen) rather than redirecting onward.
 *
 * Sign-in hops opt out of web dark mode while they are transient: the document_start pre-paint
 * extension inverts every page before we know what it is, so a chain would otherwise read as a burst
 * of half-recolored IdP pages. But a hop that stops and asks is a page the user *reads*, and leaving
 * exactly those pages glaring white in a dark browser is the one place that trade-off inverts. So
 * `ContentHost` marks a settled hop here and recolors it, using the same settle rule (and slightly
 * less than the same grace, so the recolor lands under the cover rather than after it lifts) that
 * [rememberSsoChainState] uses to uncover.
 *
 * A plain volatile field, matching [SsoAutofillHold]: only one browser activity is ever foreground,
 * and the readers sample it at launch rather than reacting to it.
 */
object SsoSettledPage {
    @Volatile
    var url: String? = null

    fun isSettled(candidate: String?): Boolean =
        !candidate.isNullOrBlank() && candidate == url
}

/**
 * Raised by `ProactiveLoginAutofill` around an unattended ("Always autofill") credential fill, so the
 * cover stays up while the fields populate and the form submits instead of uncovering on the settle
 * rule below and showing the user a form filling itself.
 *
 * A plain singleton holding Compose state, matching `AppLaunchRequests`: the writer and the reader
 * sit in different subtrees, and only one browser activity is ever foreground.
 */
object SsoAutofillHold {
    /** True while an unattended fill is in flight. Always cleared on a timeout — never latched. */
    var active: Boolean by mutableStateOf(false)
        internal set
}

/**
 * Drives [SsoChainOverlay]'s visibility from the selected tab's [url] and [loading] state.
 *
 * [enabled] should be false wherever a cover would be wrong regardless of navigation state (video
 * fullscreen, PiP, the tab overview) — it forces the cover down rather than merely refusing to
 * raise it.
 *
 * [holdForAutofill] ([SsoAutofillHold.active]) suspends the settle-based uncover: an IdP page that
 * has stopped loading normally reads as "waiting for the user", but if we are about to fill and
 * submit it unattended there is nothing for the user to do and nothing worth showing them.
 */
@Composable
fun rememberSsoChainState(
    url: String?,
    loading: Boolean,
    enabled: Boolean,
    // The engine's own 0..100 load percentage for the current document, used to subdivide each hop
    // so the bar moves continuously instead of stepping once per redirect.
    pageProgress: Int = 0,
    // The selected tab. The chrome stays usable above this cover, so the user can switch tabs
    // mid-chain — which must not be mistaken for the chain finishing. See the taint check below.
    tabId: String? = null,
    holdForAutofill: Boolean = SsoAutofillHold.active,
): SsoChainUiState {
    var visible by remember { mutableStateOf(false) }
    // An auth URL that settled and is therefore asking for input. Honoured only while that page is
    // idle: once it starts loading again (the user submitted the form) the chain is moving and the
    // cover is welcome back.
    var interactiveUrl by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val stats = remember(context) { SsoChainStats(context) }
    // Hop bookkeeping for the progress bar. `chainKey` is the host of the chain's first auth hop and
    // doubles as "are we in a chain"; `countedUrls` dedupes the several state emissions one hop
    // produces (loading true → false, plus any same-URL replace).
    var chainKey by remember { mutableStateOf<String?>(null) }
    // The tab the current chain belongs to, and whether it was interrupted (tab switched away and
    // back). A tainted chain still drives the cover, but its hop count is never recorded — an
    // undercount would drag the estimate down for every future login through that provider.
    var chainTabId by remember { mutableStateOf<String?>(null) }
    var chainTainted by remember { mutableStateOf(false) }
    var expectedHops by remember { mutableStateOf<Float?>(null) }
    var hops by remember { mutableIntStateOf(0) }
    val countedUrls = remember { mutableSetOf<String>() }
    // Monotonic within a chain. Gecko's per-document percentage is noisy — it can report a lower
    // value mid-load, and a progress bar that walks backwards reads as broken — so the displayed
    // value only ever ratchets up, and resets when the chain ends.
    var ratchetedProgress by remember { mutableStateOf<Float?>(null) }

    // Deliberately a SEPARATE effect from the visibility one below: pageProgress ticks constantly,
    // and adding it to those keys would cancel and restart the settle-grace delay on every tick, so
    // the cover would never uncover on an interactive page.
    LaunchedEffect(chainKey, hops, pageProgress, expectedHops) {
        if (chainKey == null) {
            ratchetedProgress = null
            return@LaunchedEffect
        }
        val raw = SsoChainStats.progressFor(hops, pageProgress, expectedHops)
        ratchetedProgress = if (raw == null) null else maxOf(raw, ratchetedProgress ?: 0f)
    }

    LaunchedEffect(url, loading, enabled, holdForAutofill, tabId) {
        if (!enabled || url.isNullOrBlank()) {
            visible = false
            return@LaunchedEffect
        }

        // Tab switched while a chain was running. Abandon it WITHOUT recording: the hop count so far
        // is a fragment, not a measurement. If the user comes back, a fresh chain starts here and is
        // marked tainted so its (also partial) count never reaches the estimate either.
        if (tabId != chainTabId) {
            if (chainKey != null) chainTainted = true
            chainTabId = tabId
            chainKey = null
            expectedHops = null
            hops = 0
            countedUrls.clear()
            visible = false
            interactiveUrl = null
        }

        if (SsoFlow.isAuthUrl(url)) {
            if (chainKey == null) {
                // Chain entry: this host identifies which SSO we are walking, and how long it took
                // last time.
                chainKey = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()
                expectedHops = chainKey?.let(stats::expectedHops)
                hops = 0
                countedUrls.clear()
            }
            // One hop produces several state emissions (loading true → false, same-URL replaces), so
            // count distinct URLs rather than callbacks.
            if (countedUrls.add(url)) hops = countedUrls.size

            if (url == interactiveUrl && !loading && !holdForAutofill) {
                visible = false
                return@LaunchedEffect
            }
            visible = true
            // An unattended fill is running on this page: stay covered until it navigates (which
            // re-enters the loading branch) or the hold times out. Note this re-covers even a page
            // already marked interactive, for the case where the credential lookup outran the
            // settle grace.
            if (holdForAutofill) return@LaunchedEffect
            if (!loading) {
                // Settled on an auth page. If a redirect fires inside the grace window this effect
                // is cancelled by the key change and the cover simply stays up; surviving the delay
                // means the page is waiting on the user.
                delay(SettleGraceMs)
                interactiveUrl = url
                visible = false
            }
        } else {
            // Out of the chain. Record what it cost so the next login on this SSO gets a calibrated
            // bar, then hold through the destination's own load so the landing doesn't flash white.
            chainKey?.let { key ->
                if (!chainTainted) stats.record(key, hops)
                chainKey = null
                chainTainted = false
                expectedHops = null
                countedUrls.clear()
                hops = 0
            }
            interactiveUrl = null
            if (visible) {
                if (loading) delay(LandingGraceMs)
                visible = false
            }
        }
    }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        delay(MaxVisibleMs)
        visible = false
    }

    return SsoChainUiState(visible = visible, progress = ratchetedProgress)
}

/**
 * @param progress 0..1 once this SSO's typical hop count is known, or null on the first login
 *   through it — the overlay then shows an indeterminate bar rather than inventing a number.
 */
@Immutable
data class SsoChainUiState(
    val visible: Boolean,
    val progress: Float?,
)

/**
 * The cover surface. Opaque [MaterialTheme] background rather than a scrim: the point is that the
 * pages behind it are never seen, and a translucent layer would still show the flashing.
 *
 * Swallows pointer input so a tap can't land on whichever redirect happens to be underneath.
 */
@Composable
fun SsoChainOverlay(
    state: SsoChainUiState,
    host: String?,
    modifier: Modifier = Modifier,
) {
    val visible = state.visible
    AnimatedVisibility(
        visible = visible,
        // Effects tier, not spatial: this is an alpha change on a large ambient surface, and it
        // must come up fast enough to catch the first redirect.
        enter = fadeIn(animationSpec = ExpressiveMotion.fastEffectsSpec()),
        exit = fadeOut(animationSpec = ExpressiveMotion.slowEffectsSpec()),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Determinate once we know how many hops this SSO usually takes, so the wait has
                    // a visible end. Animated rather than snapped: hops land in bursts, and a bar
                    // that jumps reads as broken. Falls back to indeterminate on a first login
                    // through this provider, where any number would be a guess.
                    val target = state.progress
                    val animated by animateFloatAsState(
                        targetValue = target ?: 0f,
                        animationSpec = ExpressiveMotion.slowSpatialSpec(),
                        label = "ssoChainProgress",
                    )
                    if (target != null) {
                        LinearProgressIndicator(
                            progress = { animated },
                            modifier = Modifier.width(200.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.width(200.dp))
                    }
                    Text(
                        text = "Completing sign-in",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    if (!host.isNullOrBlank()) {
                        Text(
                            text = host,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            }
        }
    }
}
