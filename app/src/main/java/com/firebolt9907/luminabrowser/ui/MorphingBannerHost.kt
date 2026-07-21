package com.firebolt9907.luminabrowser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * Coordinates the vertical STACK shared by every [MorphingBannerHost] anchored above the pill, so
 * that when more than one banner is showing they pile up like the Samsung Now Bar (each banner
 * riding above the ones below it) instead of overlapping at the same spot. The bottom-most banner
 * (lowest [priority]) sits at the pill; each banner above is lifted by the summed height (+ a gap)
 * of every visible banner below it. When a lower banner leaves, the reported heights change and the
 * banners above spring down to close the gap.
 *
 * Created once per surface via [rememberBannerStackState] and passed to every banner host. Only the
 * banners registered against the SAME instance stack together.
 */
class BannerStackState {
    // id → last measured natural height (px) while the banner is visible; 0 when hidden/leaving.
    private val heights = mutableStateMapOf<String, Int>()
    // id → stacking priority (lower = nearer the pill / bottom of the stack).
    private val priorities = mutableStateMapOf<String, Int>()

    /** Publish a banner's current contribution to the stack. Height 0 = takes no space. */
    fun report(id: String, priority: Int, heightPx: Int) {
        priorities[id] = priority
        if (heights[id] != heightPx) heights[id] = heightPx
    }

    /** Drop a banner from the stack entirely (call on dispose). */
    fun remove(id: String) {
        heights.remove(id)
        priorities.remove(id)
    }

    /**
     * The upward offset (px) [id] must apply so it clears every currently-visible banner BELOW it —
     * the sum of their heights plus [gapPx] of breathing room per banner. Reads snapshot state, so
     * it recomposes the caller only when a banner appears, disappears, or is remeasured.
     */
    fun offsetBelowPx(id: String, gapPx: Int): Int {
        val myPriority = priorities[id] ?: return 0
        var sum = 0
        for ((otherId, h) in heights) {
            if (otherId == id || h <= 0) continue
            val otherPriority = priorities[otherId] ?: continue
            // Tie-break on id so two banners can never claim to be below each other.
            val below = otherPriority < myPriority ||
                (otherPriority == myPriority && otherId < id)
            if (below) sum += h + gapPx
        }
        return sum
    }
}

@Composable
fun rememberBannerStackState(): BannerStackState = remember { BannerStackState() }

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
 *  - **Now-Bar stacking.** When several banners show at once they pile up rather than overlap: pass a
 *    shared [stack] (+ a stable [stackId] and [stackPriority]) and each banner lifts above the ones
 *    below it, springing down to close the gap when a lower one leaves. Omit [stack] to opt out.
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
    // Shared Now-Bar stack. When non-null the banner joins the vertical pile keyed by [stackId] /
    // [stackPriority] (lower priority = nearer the pill). Null = solo banner, no stacking.
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
    // on — `LuminaMotion.BarCollapse` (near-critical, StiffnessMediumLow) — the single spring the pill
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
            morph.animateTo(1f, animationSpec = LuminaMotion.BarCollapse)
        } else {
            lastEnterKey = enterKey
            // Hold briefly before collapsing — the Now Bar's signature delayed tuck — then collapse on
            // the pill's spring, so the two feel like one connected motion.
            kotlinx.coroutines.delay(110)
            morph.animateTo(0f, animationSpec = LuminaMotion.BarCollapse)
            mounted = false
        }
    }

    // Stack bookkeeping: publish this banner's natural card height while it's intended-visible so the
    // banners above it lift clear, and drop it from the stack when it leaves composition.
    var cardHeightPx by remember { mutableIntStateOf(0) }
    if (stack != null) {
        LaunchedEffect(cardHeightPx, visible) {
            stack.report(stackId, stackPriority, if (visible) cardHeightPx else 0)
        }
        DisposableEffect(stack, stackId) { onDispose { stack.remove(stackId) } }
    }

    if (!mounted && morph.value <= 0.001f) return

    // The upward lift that keeps this banner above the ones below it, animated so the pile settles
    // smoothly when a lower banner appears or leaves. Read in the draw phase (below) — no per-frame
    // recomposition; the target only changes on a stack membership/size change.
    val stackGapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
    val stackTargetPx = (stack?.offsetBelowPx(stackId, stackGapPx) ?: 0).toFloat()
    val stackOffsetPx = remember { Animatable(stackTargetPx) }
    LaunchedEffect(stackTargetPx) { stackOffsetPx.animateTo(stackTargetPx, LuminaMotion.BarCollapse) }

    // Folds the springy enter/exit with the live scroll intent into one progress. Multiplying means
    // either one reaching "collapsed" tucks the banner away. Read in the draw phase only.
    val reveal = { morph.value * (1f - collapseProgress().coerceIn(0f, 1f)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = modifier
                .align(Alignment.BottomCenter)
                // 20.dp = the pill's own edge margin (BarExpanded uses maxWidth - 40.dp).
                .padding(horizontal = 20.dp)
                .padding(bottom = bottomPadding + 10.dp)
                // Pill-geometry tracking + Now-Bar stack lift, read in the draw phase (matches
                // BottomBarLayer) so per-frame drag/stack settle never recomposes the banner.
                .graphicsLayer {
                    translationX = horizontalOffsetX()
                    // Lift above the banners below this one (0 for the bottom-most / solo banner).
                    translationY = -stackOffsetPx.value
                    val gridAlpha = 1f - (gridProgress() * 1.5f).coerceIn(0f, 1f)
                    alpha = gridAlpha * (1f - switchFade())
                }
                // The morph layer — the Now-Bar dive/squash/fade, anchored to the bottom (pill) edge.
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
                },
        ) {
            // Staggered content reveal: the inner content fades and settles up starting at 30% of the
            // morph, so it "arrives" just after the capsule pops open. Its measured size (unaffected
            // by the draw-only graphicsLayers above) is the banner's natural height for stacking.
            Box(
                modifier = Modifier
                    .onSizeChanged { if (it.height > 0) cardHeightPx = it.height }
                    .graphicsLayer {
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
