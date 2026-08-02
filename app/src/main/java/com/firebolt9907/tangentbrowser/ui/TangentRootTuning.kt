package com.firebolt9907.tangentbrowser.ui

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

// Tuning constants, springs, and pure gesture-math helpers for [TangentRoot]. Extracted verbatim
// from TangentRoot.kt so the main composable file stays focused on state + UI. These are `internal`
// (were `private` when co-located) so TangentRoot and BottomBarLayer can share them across files.

internal const val SwitchDistanceThresholdPx = 120f
internal const val SwitchVelocityThresholdPxS = 800f
internal const val PredictiveBackVelocityThresholdPxS = 900f
internal const val PredictiveBackCommitProgressThreshold = 0.33f
internal const val PredictiveBackCoverFallbackMs = 900L
internal const val PredictiveBackCoverMaxMs = 2500L
// Scroll travel (px) that flips the pill between expanded and compact. Two thresholds give
// hysteresis so tiny scroll reversals don't flicker the state: scroll down past the snap threshold
// to collapse, up past the (lower) expand threshold to expand. The accumulator is clamped to
// [0, snap] so the pill stays responsive both ways — a compact pill expands after ~60 px of up-scroll
// no matter how far you'd scrolled down.
internal const val BarCollapseSnapThresholdPx = 180f
internal const val BarExpandSnapThresholdPx = 120f
// Pre-snap "indication": as the scroll accumulator approaches the threshold that would flip the
// pill's state, the collapse progress is nudged this far toward the opposite state — a small,
// eased *morph* preview (expanded starts shrinking toward compact, compact starts growing toward
// expanded). Because it lives on the same progress value the snap animates, the flip continues
// seamlessly from the indicated shape instead of popping back to the resting size.
internal const val BarCollapsePreviewMax = 0.12f
// Hoisted constant collapse-progress provider for the always-expanded blank new-tab ghost.
internal val ZeroCollapseProgress: () -> Float = { 0f }
// After scrolling stops mid-band, spring the preview back to its resting state so the indication
// nudge doesn't linger; this is the idle delay before that settle runs.
internal const val BarCollapseIdleSettleMs = 140L
// Min reader scroll travel (px) before the pill auto-hides/reveals, so tiny jitter doesn't toggle it.
internal const val ReaderBarHideSlopPx = 6f
// Cap on the URL-keyed in-memory snapshot map so a long browsing session can't retain a
// screen-sized bitmap for every page ever visited. Evicted entries still live in the disk cache.
internal const val MaxRecentPageSnapshots = 24
internal const val CompactInteractionThreshold = 0.5f
// Chrome Input Framer equivalent: after the VSync frame boundary, wait this long for late-arriving
// scroll events before committing the frame. At 120 Hz one refresh cycle is ~8.3 ms; 1/3 of that
// is ~2.8 ms. At 60 Hz one cycle is ~16.7 ms; 1/3 is ~5.6 ms. We use a fixed ~3 ms which works
// well for both: long enough to catch most late events, short enough to not delay the frame visibly.
internal const val ScrollFrameGracePeriodMs = 3L
// Maximum age (ms) of velocity samples used for input prediction. Samples older than this are
// discarded so a stale velocity from a prior scroll doesn't predict into a new gesture.
internal const val ScrollVelocityMaxAgeMs = 80L
// Decelerate-only curve for gesture-release slides (predictive back, grid dismiss): the motion
// continues from the finger and eases into the target without re-accelerating, matching iOS's
// interactive-pop settle. Default FastOutSlowIn eases in *and* out, which feels wrong mid-gesture.
internal val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
// [EaseOut] again, as a View-world interpolator, for the places a gesture release is settled by an
// android ValueAnimator rather than a Compose animation (the IME insets controller). Same four
// control points — kept adjacent so the two can't drift apart.
internal val EaseOutInterpolator: Interpolator = PathInterpolator(0f, 0f, 0.2f, 1f)
// How long the keyboard takes to settle to its resting state after a sheet-drag release, once the
// app hands manual IME control back. Shared by BOTH halves of that handoff — ImeSheetController's
// settle ValueAnimator and the suggestion sheet's own slide — because the sheet is glued to the
// keyboard through the drag and must not come unstuck at the moment the finger lifts.
internal const val ImeSettleDurationMs = 200

internal const val PredictiveBackDurationMs = 220
internal val TabSwitchGap = 64.dp
// Depth card-stack swipe (wide screens): how far the leaving/incoming page panes shrink at the
// extremes of a swipe, and the peak darkening applied to whichever pane is receding.
internal const val DeckSwipeMinScale = 0.90f
internal const val DeckSwipeMaxDim = 0.35f
internal val PullRefreshThreshold = 96.dp
internal val PullRefreshMaxDistance = 132.dp
internal const val GridCommitVelocityPxS = 1000f
// The open spring seeds the finger's release velocity so the settle carries momentum. But a big/fast
// swipe releases with gridDragProgress ≈ 1 (little range left) AND a high velocity, which rushes
// gridProgressState 0.95→1 in a couple frames — collapsing the card's commit morph into an instant
// SNAP (the "no hero on large swipes" report). Cap the seed velocity relative to the range still to
// travel so the open always keeps ~≥1/this-many seconds of settle regardless of how far you dragged;
// smaller swipes (lots of range left) keep their full natural finger velocity. Tuning this lower =
// gentler/longer open, higher = snappier.
internal const val GridOpenMaxVelocityPerRange = 5f

// Safari-style motion for the tab-overview (grid) transitions. iOS springs are authored as a
// "response" (seconds to settle) + damping fraction; Compose's stiffness maps as
// stiffness = (2*PI / response)^2. Safari's overview zoom is a soft, lightly-damped settle that
// carries the finger's release velocity rather than snapping. Tuned to iOS's grace: response
// ~0.46s (softer/longer than a snappy Material spring) with a lively-but-controlled damping so the
// motion eases in and settles crisply rather than crawling to rest. Centralised here so the
// open/settle/cancel/close phases stay one coherent feel.
internal object TangentMotion {
    // Overview zoom-in (carousel → grid). response ~0.46s. Damping 0.8 gives the barely-there
    // overshoot-and-settle that reads as "iOS smooth" instead of a hard stop.
    val GridOpen = spring<Float>(dampingRatio = 0.8f, stiffness = 190f)
    // Settle to a target and cancel-return. Same softness, a hair more damped so a cancel doesn't
    // visibly bounce back into the chrome.
    val GridSettle = spring<Float>(dampingRatio = 0.85f, stiffness = 200f)
    // Overview zoom-out (grid → fullscreen tab): tap-to-select / back / new-tab exit. Symmetric
    // softness with GridOpen so entry and exit feel like one motion. Damping 0.9 keeps it crisp
    // (no dead critical-damping crawl at the end) — the render clamps progress to [0,1], so the
    // sub-percent undershoot a light damping produces can't show as an overshoot past the tab.
    val GridClose = spring<Float>(dampingRatio = 0.9f, stiffness = 200f)
    // Toolbar collapse/expand — the single spring that drives both directions (Safari-style). No
    // finger-tracking preview: crossing the scroll threshold animates the pill smoothly between the
    // expanded and compact states with this one spring, so up and down feel identical. Near-critical
    // damping (0.9) + a medium-low stiffness give a quick, smooth glide with no visible bounce.
    val BarCollapse = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    // Material 3's ModalBottomSheet does NOT animate on a tween — it springs, and asymmetrically:
    // show (and drag-release settle) uses the motion scheme's "default spatial" spring, hide uses
    // the far snappier "fast effects" one. Anything that has to move *with* a sheet — the scaled-page
    // backdrop behind it, a companion overlay — must use the same two specs, chosen by direction, or
    // it visibly desyncs: a tween lags the sheet on the way up and leaves the page card lingering
    // scaled-down for a beat after the sheet has already gone on the way down.
    //
    // These mirror `MaterialTheme.motionScheme`'s standard values (material3
    // `StandardMotionTokens.SpringDefaultSpatial*` / `SpringFastEffects*`) because that accessor is
    // `internal` to material3 and can't be read from here. Re-check them when the Compose BOM moves;
    // the app uses the plain `MaterialTheme` (standard scheme), not `MaterialExpressiveTheme`.
    val SheetShow = spring<Float>(dampingRatio = 0.9f, stiffness = 700f)
    val SheetHide = spring<Float>(dampingRatio = 1f, stiffness = 3800f)

    // Release of a swipe-to-close on an overview card (deck/pixel up-swipe, classic-grid side-swipe).
    // Both outcomes take over from a finger that is still moving, so neither may ease *in* — and the
    // default FastOutSlowIn does exactly that: its curve leaves the origin at zero velocity, so the
    // card stalls for a frame at the instant the finger lifts and then re-accelerates. That hitch is
    // the whole reason [EaseOut] exists (its comment already names "grid dismiss"); the grid just
    // wasn't using it.
    //
    // Dismiss rides EaseOut — the card is leaving the screen, so a fixed decelerate-only slide is
    // predictable and can't overshoot. Snap-back springs instead, seeded by the caller with the
    // finger's release velocity where a tracker is available, so a card still travelling keeps
    // travelling and settles rather than replaying a fixed 200 ms ramp from a standstill.
    // visibilityThreshold is half a pixel because the animated value is a raw pixel offset: the
    // Float default of 0.01 would keep the spring emitting frames long past anything visible.
    val CardDismiss = tween<Float>(durationMillis = 200, easing = EaseOut)
    val CardSnapBack = spring<Float>(dampingRatio = 0.82f, stiffness = 420f, visibilityThreshold = 0.5f)

    // Release of a predictive-back drag, for BOTH activities' back gestures. Same reason as the card
    // specs above: the finger is mid-travel when the system hands the gesture over, so the settle
    // must be decelerate-only. These exist mainly to keep MainActivity and CustomTabActivity in
    // sync — the same back-swipe used to commit over 220 ms in one and 180 ms in the other, on two
    // different curves, so going back felt like a different gesture depending on how the page had
    // been opened.
    val PredictiveBackCommit = tween<Float>(durationMillis = PredictiveBackDurationMs, easing = EaseOut)
    val PredictiveBackCancel = tween<Float>(durationMillis = 180, easing = EaseOut)

    // The Custom Tab *close* commit is deliberately the one exception to [PredictiveBackCommit]: the
    // window's own exit animation (`overridePendingTransition`, see AGENTS.md → Custom Tab exit
    // animation) picks up from whatever frame this ends on, so the in-app slide is kept short to
    // reach that handoff quickly. Only the duration differs — the curve still must not ease in.
    val CustomTabCloseCommit = tween<Float>(durationMillis = 120, easing = EaseOut)

    // The suggestion sheet's half of the keyboard-settle handoff. Must stay the same duration and
    // curve as [EaseOutInterpolator] at [ImeSettleDurationMs], which is what ImeSheetController
    // drives the real IME with — the sheet tracks the keyboard 1:1 through the drag, so any
    // divergence here shows up as the two coming unstuck exactly when the finger lifts.
    val ImeSettle = tween<Float>(durationMillis = ImeSettleDurationMs, easing = EaseOut)
}

// Horizontal tab-switch commit spring: damping scales with the finger's release velocity (fast
// flicks settle looser for a livelier feel) while stiffness varies by destination — softer when
// committing to the adjacent tab, snappier when snapping back to the current one.
internal fun switchSpring(velocity: Float, stiffness: Float) =
    spring<Float>(dampingRatio = dampingForVelocity(velocity), stiffness = stiffness)

/** Fraction of a raw drag applied at the very edge of an overscroll; falls to 0 as you pull further. */
internal const val EdgeRubberBandTension = 0.55f

/**
 * iOS-style edge rubber-band. When there is no tab to swipe to, the page still follows the finger —
 * but with resistance that grows as you pull further from rest (asymptotically walling off), and it
 * tracks the finger 1:1 on the way back. This replaces a dead hard-stop with Safari's elastic pull,
 * and pairs with the existing spring-back in [commitHorizontalRelease] for the release bounce.
 * [current] and the result stay on the overscrolled side (the caller coerces to be safe).
 */
internal fun rubberBand(current: Float, delta: Float, dimension: Float): Float {
    if (dimension <= 0f) return current
    val outward = (current == 0f && delta != 0f) || sign(delta) == sign(current)
    if (!outward) return current + delta // returning toward rest: 1:1 finger tracking
    val pulled = (abs(current) / dimension).coerceIn(0f, 1f)
    return current + delta * (1f - pulled) * EdgeRubberBandTension
}

internal fun dampingForVelocity(velocity: Float): Float {
    val v = abs(velocity)
    return when {
        v < 400f -> 1.0f
        v >= 1500f -> 0.82f
        else -> {
            // Linearly ease from critically damped (slow flicks) to a light 0.82 settle at the
            // fast-flick threshold. Target must match the >=1500 branch so damping stays continuous
            // across the boundary — a mismatch there makes the tab-switch spring visibly jump feel.
            val t = (v - 400f) / 1100f
            1.0f + (0.82f - 1.0f) * t
        }
    }
}
