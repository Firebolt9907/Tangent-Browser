package com.firebolt9907.tangentbrowser.ui

import com.firebolt9907.tangentbrowser.ui.motion.BrowserPhysics
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
// The platform's own IME show/hide animation, mirrored so app UI that has to rise and fall WITH the
// keyboard stays glued to it instead of running a parallel timer at a different speed.
//
// Verified against AOSP `android.view.InsetsController` (android-36 platform sources):
// `InternalAnimationControlListener.getInsetsInterpolator` returns `SYNC_IME_INTERPOLATOR` =
// `PathInterpolator(0.2f, 0f, 0f, 1f)` and `getDurationMs` returns `ANIMATION_DURATION_SYNC_IME_MS`
// = 285 — the SAME curve and duration in BOTH directions — whenever `mHasAnimationCallbacks` is set,
// i.e. the app has registered a `WindowInsetsAnimation.Callback`. Compose registers one for any
// window that reads `WindowInsets.ime` or uses `imePadding()`, which this app does throughout, so
// the synced branch is the one that applies here. (The unsynced fallback — 200 ms, asymmetric
// LINEAR_OUT_SLOW_IN / FAST_OUT_LINEAR_IN — applies only without that callback, and the separate
// `hasZeroInsetsIme` branch only to keyboards with no height at all, where nothing is visible to
// track anyway.)
internal const val ImeSyncDurationMs = 285
internal val ImeSyncEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// Page-load bar. Gecko reports progress in sparse jumps, so each report is swept linearly over
// [PageLoadStepMs] to read as one continuous advance. [PageLoadFinishMs] is the run-to-the-end that
// plays when loading completes, before the bar is allowed to fade — without it the bar is yanked
// off screen from wherever the last report left it.
internal const val PageLoadStepMs = 150
internal const val PageLoadFinishMs = 180

internal const val PredictiveBackDurationMs = 220
internal val TabSwitchGap = 64.dp
// Depth card-stack swipe (wide screens): how far the leaving/incoming page panes shrink at the
// extremes of a swipe, and the peak darkening applied to whichever pane is receding.
// Keep depth subtle: the page should feel attached to the finger, not shrink into a dark tile.
internal const val DeckSwipeMinScale = 0.93f
internal const val DeckSwipeMaxDim = 0.22f
internal val PullRefreshThreshold = 96.dp
// Asymptote of the pull's rubber band, NOT a clamp — see rubberBandPull. The indicator approaches
// this and never arrives, so the pull keeps responding however far the finger travels; the threshold
// above therefore sits at ~73% of the band, reached after ~171dp of actual finger travel.
internal val PullRefreshMaxDistance = 132.dp
// Multi-function overscroll menu (PullActionMenu). The menu fades in from PullMenuRevealDistance so
// it isn't summoned by an incidental bounce, and each action occupies a PullMenuSlotWidth-wide band
// of horizontal finger travel measured from where the pull started (centre band = Refresh, i.e. a
// straight-down pull keeps the plain pull-to-refresh behaviour).
internal val PullMenuRevealDistance = 36.dp
internal val PullMenuSlotWidth = 84.dp
// Gap between the top chrome inset (status bar + refresh line) and the menu, and the distance the
// menu slides through as it reveals. They are ONE constant on purpose: the slide starts a full gap
// above the rest position, so at zero reveal the menu sits exactly on the inset and never rides up
// under the status bar. Two independent numbers drift and the menu clips into the system bar.
internal val PullMenuTopGap = 16.dp
// How far the selected chip's blob stretches toward the finger before the selection commits to the
// next slot. Kept under the 18dp inter-chip spacing so a stretched blob never touches its neighbour.
internal val PullMenuStretch = 13.dp
internal const val GridCommitVelocityPxS = 1000f
// The open spring seeds the finger's release velocity so the settle carries momentum. But a big/fast
// swipe releases with gridDragProgress ≈ 1 (little range left) AND a high velocity, which rushes
// gridProgressState 0.95→1 in a couple frames — collapsing the card's commit morph into an instant
// SNAP (the "no hero on large swipes" report). Cap the seed velocity relative to the range still to
// travel so the open always keeps ~≥1/this-many seconds of settle regardless of how far you dragged;
// smaller swipes (lots of range left) keep their full natural finger velocity. Tuning this lower =
// gentler/longer open, higher = snappier.
internal const val GridOpenMaxVelocityPerRange = 5f

// App-owned movement shares one physical response. Named aliases describe destinations, not
// independent tuning; changing destination must not change the spring's physical properties.
internal object TangentMotion {
    val GridOpen = BrowserPhysics.Progress
    val GridSettle = BrowserPhysics.Progress
    val GridClose = BrowserPhysics.Progress
    val BarCollapse = BrowserPhysics.Progress

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

    // [SheetShow]/[SheetHide] for the *slide* half — `slideInVertically`/`slideOutVertically` animate
    // an IntOffset and cannot be handed a `tween<Float>`/`spring<Float>`. Used by the app's
    // hand-rolled sheet-styled surfaces (the reader's Table of Contents, which is a Dialog rather
    // than a ModalBottomSheet) so that everything the user reads as "a bottom sheet" arrives and
    // leaves on the same two springs, whether or not material3 is the thing drawing it.
    val SheetShowOffset =
        spring<IntOffset>(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntOffset(1, 1))
    val SheetHideOffset =
        spring<IntOffset>(dampingRatio = 1f, stiffness = 3800f, visibilityThreshold = IntOffset(1, 1))

    // Dismiss, commit and cancel carry momentum through the same spring. Critical damping
    // keeps opaque page edges and mounted-on-positive-distance indicators from bouncing open.
    val CardDismiss = BrowserPhysics.Pixels
    val CardSnapBack = BrowserPhysics.Pixels
    val PredictiveBackCommit = BrowserPhysics.Progress
    val PredictiveBackCancel = BrowserPhysics.Progress
    val PullRetract = BrowserPhysics.Pixels

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

    // For UI that enters and leaves alongside the keyboard on the *system's* animation (as opposed
    // to [ImeSettle], which is the app-driven settle after a manual-control drag). Symmetric in both
    // directions because the platform's synced IME animation is — see [ImeSyncDurationMs].
    val ImeTrack = tween<Float>(durationMillis = ImeSyncDurationMs, easing = ImeSyncEasing)

    // [ImeTrack] for the *slide* half of the same enter/exit — `slideInVertically`/`ExitTransition`
    // animate an IntOffset, which a `tween<Float>` cannot be handed to. Same duration and curve, so
    // a bar that both moves and fades alongside the keyboard stays one motion rather than two.
    val ImeTrackOffset = tween<IntOffset>(durationMillis = ImeSyncDurationMs, easing = ImeSyncEasing)

    // A full-screen OPAQUE takeover sliding in over the page and back off it — reader view, in both
    // MainActivity and the Custom Tab. Two reasons it is declared here rather than reaching for
    // `ExpressiveMotion.slowSpatialSpec()`, which is otherwise the right tier for a surface this big:
    //
    //  - Every expressive spatial tier is under-damped (dampingRatio 0.8). On a surface that covers
    //    the screen the overshoot carries the slide *past* offset 0, which uncovers a strip of the
    //    web page along the bottom edge at the end of the enter — the one place the expressive
    //    bounce is not free. Critically damped, using the shared browser response.
    //  - The takeover is a slide, NOT a cross-fade. Both call sites used to pair this with
    //    `fadeIn()`/`fadeOut()`, which held the reader translucent over the live page for the whole
    //    transition, so the article text and the page it was extracted from were legible through
    //    each other. An opaque card that moves needs no opacity change; the slide alone reads as
    //    one surface replacing another instead of two documents ghosting.
    val TakeoverSlide =
        BrowserPhysics.spring(visibilityThreshold = IntOffset(1, 1))

    // A card appearing INSIDE a stack that other layout is measured against — today the Firefox-Sync
    // handoff card in the tab overview's bottom controls. Animating only the card's own fade/slide is
    // not enough there: the controls column is bottom-anchored AND its measured height is what feeds
    // the deck's `bottomInsetPx`, so a card that takes its full height in one frame kicks the space
    // switcher upward and re-lays out every tab card in the overview in that same frame, while the
    // card itself is still gliding in. Pairing the fade with `expandVertically`/`shrinkVertically` on
    // these specs folds the column's height into the same motion, so the surroundings move with the
    // card instead of snapping ahead of it.
    //
    // Critically damped deliberately: an expressive spatial's 0.8 damping overshoots, and overshoot
    // on a *size* means the container grows taller than its content and flashes a gap before
    // settling — the same reason [TakeoverSlide] is damped flat. Both directions share the
    // browser response so a reversal feels like the same surface changing direction.
    val RevealExpand =
        BrowserPhysics.spring(visibilityThreshold = IntSize(1, 1))
    val RevealCollapse =
        BrowserPhysics.spring(visibilityThreshold = IntSize(1, 1))
}

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
