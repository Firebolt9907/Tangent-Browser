package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The three destinations of the multi-function overscroll pull (Chrome-iOS style): pull down past
 * the top of a page, then — without lifting — slide the same finger left or right to pick one.
 *
 * The order is the on-screen order, and [Refresh] is deliberately the centre: a straight-down pull
 * selects nothing sideways and therefore still just refreshes, so the gesture is a superset of plain
 * pull-to-refresh rather than a replacement for it.
 */
internal enum class PullAction(val label: String, val icon: ImageVector) {
    NewTab("New Tab", Icons.Default.Add),
    Refresh("Refresh", Icons.Default.Refresh),
    Reader("Reader", Icons.AutoMirrored.Filled.ChromeReaderMode),
}

/** Slot index of each action, left to right: the position it occupies on the horizontal axis. */
internal val PullAction.slot: Int
    get() = ordinal - 1

/**
 * Fraction of a slot the finger must travel *past* the current action's centre before the selection
 * moves on. Deliberately more than half: with a plain nearest-slot rounding the boundary sits exactly
 * at 0.5, so hand tremor at that point flips the selection back and forth many times a second —
 * strobing the chips and firing the selection haptic on every flip. Anything above 0.5 is hysteresis;
 * this value is the band that has to be crossed to commit, and the way back out is the same distance
 * on the other side.
 */
private const val SlotCommitFraction = 0.62f

/**
 * Advances the selection for the live horizontal finger travel (px, signed, measured from where the
 * pull began).
 *
 * Stateful on purpose — [current] is what gives the mapping hysteresis, so this is a step function
 * driven per touch sample, not a pure position lookup. The centre slot is [PullAction.Refresh], so a
 * pull with no sideways intent at all keeps the plain pull-to-refresh behaviour.
 *
 * An unavailable Reader is *skipped* rather than merely ignored on release: overshooting into a dead
 * band and getting nothing back reads as the gesture having failed, so the selection stays put on the
 * last reachable action instead.
 */
internal fun nextPullAction(
    current: PullAction,
    dx: Float,
    slotWidthPx: Float,
    readerAvailable: Boolean,
): PullAction {
    if (slotWidthPx <= 0f) return current
    val position = dx / slotWidthPx
    val from = current.slot
    val target = when {
        position > from + SlotCommitFraction -> from + 1
        position < from - SlotCommitFraction -> from - 1
        else -> from
    }.coerceIn(-1, 1)
    val next = PullAction.entries.first { it.slot == target }
    return if (next == PullAction.Reader && !readerAvailable) current else next
}

/**
 * Continuous position of the selection on the slot axis, in slot units (-1..1) — what the highlight
 * actually rides on, as opposed to the discrete [nextPullAction] the release commits to.
 *
 * Kept separate from the discrete selection so the highlight tracks the finger *between* slots
 * instead of teleporting at the commit boundary: the chips respond from the first pixel of sideways
 * movement, which is what makes the horizontal axis feel connected to the finger at all.
 */
internal fun pullSelectionPosition(dx: Float, slotWidthPx: Float, readerAvailable: Boolean): Float {
    if (slotWidthPx <= 0f) return 0f
    val upper = if (readerAvailable) 1f else 0f
    return (dx / slotWidthPx).coerceIn(-1f, upper)
}

/**
 * How far the selected chip's blob elongates toward the finger, signed and normalised to ±1 at the
 * commit boundary.
 *
 * This is the visual answer to hysteresis. Between the geometric midpoint and the commit band the
 * selection deliberately does *not* move, and without this the menu would simply sit still under a
 * finger that is plainly moving — indistinguishable from having gone unresponsive. Stretching the
 * current choice toward the finger says "still this one, but I can feel you" and reaches full
 * extension exactly where the neighbour takes over, so the snap lands at the end of the stretch
 * rather than out of nowhere. Only the chip holding the selection stretches — the others have
 * nothing to hold on with.
 */
internal fun pullStretchFor(position: Float, actionSlot: Int, selectedSlot: Int): Float =
    if (actionSlot != selectedSlot) 0f
    else ((position - actionSlot) / SlotCommitFraction).coerceIn(-1f, 1f)

/**
 * The reveal itself. Composed as a top-anchored overlay over the page (the page does not translate
 * on a pull here — see PullRefreshContainer — so the "overscroll space" is the strip under the
 * chrome inset that the refresh line already occupies).
 *
 * **Everything that moves with the finger is a lambda read in the draw phase**, never a `Float`
 * parameter and never an `animateAsState`. Two reasons, and both were visible before this shape:
 * a `Float` parameter recomposes this row (and the scope in TangentRoot that hands it over) on every
 * touch sample; and a spring animating *toward* a per-sample target always trails the finger by its
 * own settling time, so the chips arrive after the hand does. Reading the live position at draw time
 * instead means the menu is exactly where the finger is, with no composition work per frame.
 *
 * [presence] carries the whole reveal — position, scale and opacity — and is the caller's single
 * handle on the menu's life: it is written live while pulling and springs back to 0 on release,
 * *whatever* the release did. Nothing here is keyed to whether an action fired.
 *
 * [selected] is the only ordinary parameter — it changes at most once per slot crossing and is used
 * solely for the caption, whose text cannot be interpolated.
 */
@Composable
internal fun PullActionMenu(
    presence: () -> Float,
    selectionPosition: () -> Float,
    selected: PullAction,
    readerAvailable: Boolean,
    readerActive: Boolean,
    slidePx: Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // How "armed" the pull reads, derived from the reveal rather than tracked separately: the reveal
    // ramp is calibrated to reach 1 exactly at the trigger threshold, so full presence *is* the arm
    // point. Deriving it means the collapse desaturates the chips in lockstep with the fade instead
    // of dropping them to neutral on the first frame of a release.
    val armProgress = { ((presence() - ArmRampStart) / (1f - ArmRampStart)).coerceIn(0f, 1f) }
    val selectedSlot = selected.slot
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.graphicsLayer {
            val p = presence().coerceIn(0f, 1f)
            alpha = p
            // Slides down out of the top edge as it reveals. The travel is exactly the caller's gap
            // between the chrome inset and the rest position, so at p = 0 the menu sits *on* the
            // inset and never rides up under the status bar. See PullMenuTopGap.
            translationY = (p - 1f) * slidePx
            val s = 0.88f + 0.12f * p
            scaleX = s
            scaleY = s
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PullAction.entries.forEach { action ->
                // How much this chip "has" the selection right now: 1 when the finger is on its slot,
                // falling off linearly to 0 a slot away. Continuous, so a chip grows as the finger
                // approaches it rather than popping once a boundary is crossed.
                val weight = { (1f - abs(selectionPosition() - action.slot)).coerceIn(0f, 1f) }
                val stretch = { pullStretchFor(selectionPosition(), action.slot, selectedSlot) }
                PullActionChip(
                    action = action,
                    weight = weight,
                    stretch = stretch,
                    armProgress = armProgress,
                    enabled = action != PullAction.Reader || readerAvailable,
                    scheme = scheme,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (selected == PullAction.Reader && readerActive) "Exit Reader" else selected.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            // Dim until the pull would actually fire, so the caption reads as a preview rather than
            // as a promise the release wouldn't keep.
            modifier = Modifier.graphicsLayer { alpha = 0.5f + 0.5f * armProgress() },
        )
    }
}

/**
 * Fraction of the reveal after which the chips start saturating toward "releasing now fires this".
 * The reveal reaches 1 at the trigger threshold, so this is the last stretch before the pull arms.
 */
private const val ArmRampStart = 0.7f

@Composable
private fun PullActionChip(
    action: PullAction,
    weight: () -> Float,
    stretch: () -> Float,
    armProgress: () -> Float,
    enabled: Boolean,
    scheme: ColorScheme,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                // Grows toward the finger, with a little extra headroom once the pull is armed so
                // "releasing now does something" is legible without looking at the caption.
                val w = weight()
                val s = 0.82f + 0.18f * w + 0.06f * w * armProgress()
                scaleX = s
                scaleY = s
                // A disabled action stays drawn — the menu must not change shape under a finger
                // mid-gesture — but reads as unreachable; the selection skips it.
                alpha = if (enabled) 1f else 0.38f
            }
            .size(44.dp)
            .drawBehind {
                // Below the trigger threshold the accent is the muted container; it saturates to the
                // full primary as the pull arms. Interpolating here rather than through an animated
                // colour keeps this a pure draw-phase read.
                val accent = lerp(scheme.secondaryContainer, scheme.primary, armProgress())
                val color = lerp(scheme.surfaceVariant, accent, weight())
                // The blob elongates toward the finger while the selection has NOT yet moved on, so
                // the hysteresis band reads as the current choice being stretched rather than as the
                // menu having gone unresponsive — the one place hysteresis could feel like lag. It
                // snaps back to a circle the moment the neighbouring chip takes the selection.
                val reach = stretch() * PullMenuStretch.toPx()
                val left = min(0f, reach)
                val right = size.width + max(0f, reach)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            },
    ) {
        // Ride at the centre of the *stretched* blob rather than of the nominal chip, so the icon
        // leans toward the finger with the shape instead of sitting off to one side of it.
        val iconShift = Modifier.graphicsLayer { translationX = stretch() * PullMenuStretch.toPx() / 2f }
        Box(modifier = iconShift, contentAlignment = Alignment.Center) {
            // One icon per content colour the chip can be wearing, cross-faded by the same draw-phase
            // values that drive the shape beneath them. A tint is a composition value and an alpha is
            // not, so stacking is what keeps the colour transition off the recomposition path — and
            // the stack is what lets the *content* colour follow the background through both of its
            // transitions (neutral → selected, and selected → armed) instead of only the first.
            PullChipIcon(action, scheme.onSurfaceVariant) { 1f - weight() }
            PullChipIcon(action, scheme.onSecondaryContainer) { weight() * (1f - armProgress()) }
            PullChipIcon(action, scheme.onPrimary) { weight() * armProgress() }
        }
    }
}

@Composable
private fun PullChipIcon(action: PullAction, tint: Color, alpha: () -> Float) {
    Icon(
        imageVector = action.icon,
        // Decorative: three stacked copies would otherwise be announced three times over, and the
        // caption below the row already names the action the release would take.
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(22.dp)
            .graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) },
    )
}
