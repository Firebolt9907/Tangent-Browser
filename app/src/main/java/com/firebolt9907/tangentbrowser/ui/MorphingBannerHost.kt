package com.firebolt9907.tangentbrowser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Coordinates the **Z-axis card stack** shared by every [MorphingBannerHost] anchored above the pill,
 * exactly like the Samsung Now Bar: when more than one banner shows at once they don't sit side by
 * side — they pile front-to-back. The front-most banner (highest [priority]) is full size at the
 * pill; each banner behind it scales down a touch and slides up a few dp so its top edge peeks out
 * above the front card, signalling "there's another one under here." When the front banner leaves,
 * the one behind animates forward to full size.
 *
 * Created once per surface via [rememberBannerStackState] and passed to every banner host; only
 * banners registered against the SAME instance stack together.
 */
class BannerStackState {
    // Priority of each currently-visible banner, keyed by id. Absent = not visible.
    private val visible = mutableStateMapOf<String, Int>()

    /** Publish whether [id] is currently occupying the stack (and at what [priority]). */
    fun report(id: String, priority: Int, isVisible: Boolean) {
        if (isVisible) {
            if (visible[id] != priority) visible[id] = priority
        } else if (visible.containsKey(id)) {
            visible.remove(id)
        }
    }

    /** Drop [id] from the stack entirely (call on dispose). */
    fun remove(id: String) {
        visible.remove(id)
    }

    /**
     * How many visible banners stack in FRONT of [id] — its depth into the pile (0 = front-most).
     * "In front" = higher [priority]; ties broken by id so the ordering is total and stable. Reads
     * snapshot state, so callers recompose only when a banner appears or disappears.
     */
    fun depthOf(id: String, priority: Int): Int {
        var count = 0
        for ((otherId, otherPriority) in visible) {
            if (otherId == id) continue
            if (otherPriority > priority || (otherPriority == priority && otherId > id)) count++
        }
        return count
    }
}

@Composable
fun rememberBannerStackState(): BannerStackState = remember { BannerStackState() }

/**
 * The one place the banner Z-stack order is decided. Every `MorphingBannerHost` call site must take
 * its `stackPriority` from here — higher = front-most (full size, on top), lower = further back
 * (scaled down, lifted so its top edge peeks out).
 *
 * These MUST be distinct. Two banners sharing a priority is a silent bug: [BannerStackState.depthOf]
 * breaks the tie alphabetically by id and pushes one of them back, but `zIndex(stackPriority)` is
 * then equal for both, so which one actually *draws* on top falls to declaration order in the
 * overlay — the demoted card can end up painted over the front one. Values are spaced by 10 so a new
 * banner can slot between two existing ones without renumbering.
 *
 * Ordering rationale, back to front: passive suggestions sit behind the surfaces the user is
 * actively operating, which sit behind transient notifications, which sit behind anything demanding
 * a decision.
 */
object BannerPriority {
    /** Passive "this page has a native app" suggestion — nothing is waiting on it. */
    const val OpenInApp = 10

    /** Persistent context bar for archive mode, and an interactive one (the timeline scrubber). */
    const val Archive = 20

    const val OtpPaste = 30
    const val SendToDevice = 40
    const val ReceivedTab = 50
    const val Handoff = 60
    const val ReaderUnavailable = 70

    /** Front-most: the page is blocked on the user granting or denying a capability. */
    const val SitePermission = 100
}

/**
 * Follows the finger while the user drags a banner card away (downward, since the cards live at the
 * bottom, or sideways) and dismisses it when the drag passes a distance/velocity threshold; otherwise
 * springs the card back to rest. Keeps the tap-to-act / X-to-dismiss affordances intact — a small
 * touch-slop of travel is required before the drag takes over. Shared by every banner card above the
 * pill.
 */
internal fun Modifier.bannerSwipeToDismiss(onDismiss: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    // Distance past which a release commits the dismissal.
    val dismissThresholdPx = with(density) { 96.dp.toPx() }
    // Far enough that the card is fully off-screen whichever way it was thrown; the banner sits at
    // the bottom, so the screen's longest edge clears it in either axis.
    val configuration = LocalConfiguration.current
    val exitTravelPx = with(density) {
        maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.toPx()
    }

    this
        .graphicsLayer {
            translationX = offsetX.value
            translationY = offsetY.value
            // Fade as the card is flung away for a softer exit.
            val travel = maxOf(abs(offsetX.value), abs(offsetY.value))
            alpha = (1f - (travel / (dismissThresholdPx * 2.5f))).coerceIn(0.15f, 1f)
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDrag = { change, delta ->
                    change.consume()
                    scope.launch {
                        offsetX.snapTo(offsetX.value + delta.x)
                        // Bottom banners resist upward travel — dismiss is a downward gesture.
                        offsetY.snapTo(offsetY.value + delta.y)
                    }
                },
                onDragEnd = {
                    val flungDown = offsetY.value > dismissThresholdPx
                    val flungSide = abs(offsetX.value) > dismissThresholdPx
                    if (flungDown || flungSide) {
                        // Commit immediately, exactly as before, so the Z-stack learns this card is
                        // leaving and the one behind it starts coming forward without waiting.
                        onDismiss()
                        // …but keep the throw going. The card stays composed through its exit morph
                        // (the host holds it until `morph` reaches 0, after the Now Bar's 110 ms
                        // delayed tuck), and it used to spend that time frozen at whatever offset the
                        // finger left it at before tucking straight *down* — so a card flung sideways
                        // stopped dead in mid-air and then went somewhere else entirely. Carrying it
                        // off-screen along the axis it was actually thrown lets the tuck happen after
                        // it is already gone. CardDismiss rather than a spatial spring: this is the
                        // established house spec for "card leaves the screen on gesture release", and
                        // an overshooting spring is meaningless once the card is out of view.
                        scope.launch {
                            if (flungSide) {
                                val dir = if (offsetX.value > 0f) 1f else -1f
                                offsetX.animateTo(dir * exitTravelPx, TangentMotion.CardDismiss)
                            } else {
                                offsetY.animateTo(exitTravelPx, TangentMotion.CardDismiss)
                            }
                        }
                    } else {
                        // Snap-back is app-owned spatial motion, so it takes the expressive spring —
                        // the bare `spring()` here was Compose's critically-damped default, which
                        // returned the card to rest with no character at all. The px variant matters:
                        // these are raw pixel offsets, and the stock 0.01 threshold would run the
                        // spring for frames past anything visible.
                        val snapBack = ExpressiveMotion.defaultSpatialPxSpec()
                        scope.launch { offsetX.animateTo(0f, snapBack) }
                        scope.launch { offsetY.animateTo(0f, snapBack) }
                    }
                },
                onDragCancel = {
                    val snapBack = ExpressiveMotion.defaultSpatialPxSpec()
                    scope.launch { offsetX.animateTo(0f, snapBack) }
                    scope.launch { offsetY.animateTo(0f, snapBack) }
                },
            )
        }
}

/** Per-depth geometry of the card stack — how much each banner behind the front shrinks / lifts. */
private const val StackScalePerDepth = 0.07f
private const val StackMinScale = 0.80f
private val StackPeekPerDepth = 22.dp
// Drop shadow cast by the whole card at the stack-transform layer. The front card (depth 0) casts the
// strongest shadow so its lower edge lifts off the page; each card behind adds a little more so its
// peeking top edge reads as a separate surface rather than a flat seam. Rendered on the stack layer
// (not the inner Surface) so it survives the scale-down/lift that would otherwise bury the 6dp inner
// shadow behind the front card.
private val StackShadowBase = 20.dp
private val StackShadowPerDepth = 6.dp

/**
 * The shared container for every banner that floats above the address pill (Internet Archive scrubber,
 * Firefox handoff / send-to-device / received-tab / OTP-paste). It gives them all one consistent
 * behavior and look:
 *
 *  - **Samsung Now-Bar-style morph.** Instead of a flat slide, the banner springs *out of* the pill on
 *    enter (a bouncy overshoot) and, on exit, *dives down toward the pill* while squashing and
 *    narrowing to a pill-like footprint, fully fading out **before** it reaches the pill line — so it
 *    reads as tucking in behind the collapsed pill (it's invisible by the time it would overlap, so no
 *    z-reorder of the overlay stack is needed).
 *  - **Now-Bar Z-stack.** When several banners show at once they pile front-to-back rather than
 *    overlapping flat: pass a shared [stack] (+ a stable [stackId] and [stackPriority]) and each
 *    banner draws at a z set by its priority, with the ones behind scaled down and lifted so they
 *    peek out the top. Everything animates as the pile grows/shrinks. Omit [stack] to opt out.
 *  - **Scroll-collapse intent.** [collapseProgress] is the SAME 0 (pill-expanded) → 1 (pill-compact)
 *    signal the pill morphs on. Read per-frame in the draw phase, it dives the banner toward the pill
 *    as the page scrolls down and pops it back on scroll up — 1:1 with the pill, no recomposition.
 *  - **Pill geometry.** [horizontalOffsetX] tracks the pill sideways during a tab-switch swipe;
 *    [gridProgress] fades it out as the tab switcher opens; [switchFade] fades it only as a switch
 *    settles. All optional (default no-op) for banners that don't wire them.
 *  - **Pill-matched padding.** 20.dp horizontal inset (the pill's own `maxWidth - 40.dp` edge margin).
 *
 * The banner stays mounted through the exit morph (while the spring unwinds), so callers can clear the
 * backing state immediately; hold-last-value logic lives in each caller's [content].
 *
 * [content] receives `contentReveal` — the folded morph+collapse progress — but the container already
 * applies a staggered fade/slide to the content for you, so most callers can ignore it.
 */
@Composable
fun MorphingBannerHost(
    visible: Boolean,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    // Identity of what the banner is currently *about* (e.g. the archive session's tab id). When this
    // changes while the banner is already shown — you switch to a DIFFERENT tab that also has a banner
    // — the enter morph replays so the spring-out is felt again, instead of the banner sitting static.
    // Null (the default) disables the replay, so banners that don't wire it keep their old behavior.
    enterKey: Any? = null,
    // Shared Now-Bar Z-stack. When non-null the banner joins the front-to-back pile keyed by
    // [stackId] / [stackPriority] (higher priority = front-most). Null = solo banner, no stacking.
    stack: BannerStackState? = null,
    stackId: String = "",
    stackPriority: Int = 0,
    collapseProgress: () -> Float = { 0f },
    gridProgress: () -> Float = { 0f },
    horizontalOffsetX: () -> Float = { 0f },
    switchFade: () -> Float = { 0f },
    content: @Composable (contentReveal: () -> Float) -> Unit,
) {
    // Enter/exit progress: 0 hidden, 1 shown. Both directions ride the *exact* spring the pill morphs
    // on — `TangentMotion.BarCollapse` (near-critical, StiffnessMediumLow) — the single spring the pill
    // uses for both expand and collapse, so the banner and pill share one identical motion curve.
    val morph = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    var lastEnterKey by remember { mutableStateOf(enterKey) }
    LaunchedEffect(visible, enterKey) {
        if (visible) {
            // A key change while the banner was already open (morph settled) = arrived at a different
            // banner-bearing tab: snap back to a collapsed capsule so the spring-out replays.
            val keyChanged = enterKey != null && enterKey != lastEnterKey
            lastEnterKey = enterKey
            if (keyChanged && morph.value > 0.2f) morph.snapTo(0.08f)
            mounted = true
            morph.animateTo(1f, animationSpec = TangentMotion.BarCollapse)
        } else {
            lastEnterKey = enterKey
            // Hold briefly before collapsing — the Now Bar's signature delayed tuck — then collapse on
            // the pill's spring, so the two feel like one connected motion.
            kotlinx.coroutines.delay(110)
            morph.animateTo(0f, animationSpec = TangentMotion.BarCollapse)
            mounted = false
        }
    }

    // Register this banner's presence in the shared Z-stack so the cards fan out front-to-back. Keyed
    // on `visible` (intent) so a banner relinquishes the front the instant it starts leaving, letting
    // the one behind animate forward without waiting for the exit morph to finish.
    if (stack != null) {
        LaunchedEffect(visible) { stack.report(stackId, stackPriority, visible) }
        DisposableEffect(stack, stackId) { onDispose { stack.remove(stackId) } }
    }

    if (!mounted && morph.value <= 0.001f) return

    // Card-stack geometry: depth 0 = front (full size), deeper = smaller + lifted so it peeks above
    // the front card's top edge. Animated so growing/shrinking the pile springs smoothly. Read in the
    // draw phase below — the target only changes when a banner appears or disappears.
    val depth = stack?.depthOf(stackId, stackPriority) ?: 0
    val targetScale = (1f - StackScalePerDepth * depth).coerceAtLeast(StackMinScale)
    val targetPeekPx = with(LocalDensity.current) { (StackPeekPerDepth * depth).toPx() }
    val targetShadowPx = with(LocalDensity.current) { (StackShadowBase + StackShadowPerDepth * depth).toPx() }
    val stackScale = animateFloatAsState(targetScale, TangentMotion.BarCollapse, label = "bannerStackScale")
    val stackPeek = animateFloatAsState(targetPeekPx, TangentMotion.BarCollapse, label = "bannerStackPeek")
    val stackShadow = animateFloatAsState(targetShadowPx, TangentMotion.BarCollapse, label = "bannerStackShadow")
    val bannerShape = SquircleShape(28.dp)

    // Folds the springy enter/exit with the live scroll intent into one progress. Multiplying means
    // either one reaching "collapsed" tucks the banner away. Read in the draw phase only.
    val reveal = { morph.value * (1f - collapseProgress().coerceIn(0f, 1f)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Draw order within the banner group: front-most (highest priority) on top. Only affects
            // sibling banners in this same overlay Box — peek card / dialogs live in later subtrees.
            .then(if (stack != null) Modifier.zIndex(stackPriority.toFloat()) else Modifier),
    ) {
        Box(
            modifier = modifier
                .align(Alignment.BottomCenter)
                // 20.dp = the pill's own edge margin (BarExpanded uses maxWidth - 40.dp).
                .padding(horizontal = 20.dp)
                .padding(bottom = bottomPadding + 10.dp)
                // Pill-geometry tracking + the Now-Bar card-stack transform (scale down + lift the
                // cards behind the front one), read in the draw phase so per-frame drag / stack
                // settle never recomposes the banner. Scales about the centre so a banner behind
                // shrinks symmetrically and its lifted top edge peeks above the front card.
                .graphicsLayer {
                    translationX = horizontalOffsetX()
                    translationY = -stackPeek.value
                    scaleX = stackScale.value
                    scaleY = stackScale.value
                    val gridAlpha = 1f - (gridProgress() * 1.5f).coerceIn(0f, 1f)
                    alpha = gridAlpha * (1f - switchFade())
                }
                // The morph layer — the Now-Bar dive/squash/fade, anchored to the bottom (pill) edge.
                // The card's drop shadow rides on THIS layer (not the stack layer above) so it dives,
                // squashes and fades in lockstep with the banner instead of hovering at full size/
                // position while the card animates in or out. clip = false keeps the inner content's
                // own shadow/overshoot from being cut; the shape only shapes the shadow.
                .graphicsLayer {
                    val p = reveal()
                    val pc = p.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    // Overshoot (p > 1) left uncoerced on scaleY so the expand bounce reads.
                    scaleY = lerp(0.12f, 1f, p)
                    scaleX = lerp(0.5f, 1f, pc)
                    // Dive the whole banner down past the pill line (its own height + the gap).
                    translationY = (1f - pc) * (size.height * 1.15f + 28.dp.toPx())
                    // Fully faded by ~45% of the travel — invisible before it overlaps the pill.
                    alpha = (pc / 0.45f).coerceIn(0f, 1f)
                    // Drop shadow so the pile reads as layered surfaces; sized by stack depth so each
                    // peeking top edge is legible. Fades out with the layer alpha as the card leaves.
                    shape = bannerShape
                    clip = false
                    shadowElevation = stackShadow.value
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                },
        ) {
            // Staggered content reveal: the inner content fades and settles up starting at 30% of the
            // morph, so it "arrives" just after the capsule pops open.
            Box(
                modifier = Modifier.graphicsLayer {
                    val r = reveal().coerceIn(0f, 1f)
                    alpha = ((r - 0.3f) / 0.7f).coerceIn(0f, 1f)
                    translationY = (1f - r) * 8.dp.toPx()
                },
            ) {
                content(reveal)
            }
        }
    }
}
