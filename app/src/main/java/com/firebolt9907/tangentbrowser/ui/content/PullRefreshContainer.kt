package com.firebolt9907.tangentbrowser.ui.content

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import kotlin.math.exp
import kotlin.math.min

/**
 * Rubber-band resistance for a pull: raw finger travel in, indicator distance out.
 *
 * The curve is `max * (1 - e^(-raw/max))`, whose slope is exactly **1 at the origin** and decays
 * smoothly to 0. That matters twice over:
 *
 * - **At the start the indicator tracks the finger 1:1**, so the pull begins the instant the finger
 *   does. A constant fraction (the old flat 0.5) moves the indicator at half finger speed from the
 *   very first pixel, which reads as lag rather than as resistance.
 * - **[maxPx] is an asymptote, not a clamp.** A hard `coerceAtMost` freezes the indicator dead while
 *   the finger keeps travelling — the single most "unresponsive"-feeling thing a pull can do. Here
 *   the pull always keeps moving, just less and less.
 *
 * [maxPx] <= 0 means "no band configured" and falls back to the plain linear resistance.
 */
internal fun rubberBandPull(rawPx: Float, maxPx: Float): Float = when {
    rawPx <= 0f -> 0f
    maxPx <= 0f -> rawPx * PullRefreshContainer.LINEAR_DRAG_RATE
    else -> maxPx * (1f - exp(-rawPx / maxPx))
}

/**
 * Pull-to-refresh host for the single [mozilla.components.concept.engine.EngineView], implemented
 * as a [NestedScrollingParent3].
 *
 * **Why nested scrolling and not touch interception:** GeckoView handles touch asynchronously and
 * calls `requestDisallowInterceptTouchEvent(true)` at touch-down, so a parent that relies on
 * `onInterceptTouchEvent` never sees the drag (and fighting for it breaks scrolling). GeckoView is
 * a `NestedScrollingChild`, though, so it *does* dispatch its unconsumed scroll to a nested-scroll
 * parent — which is exactly how `SwipeRefreshLayout` works. We implement that protocol ourselves so
 * we additionally get the live overscroll distance to drive a reactive indicator and a
 * threshold-crossing haptic (which SwipeRefreshLayout keeps private).
 *
 * **Nested scroll decides *whether* we are pulling; the touch stream decides *how far*.** Those are
 * deliberately different sources. Only the engine knows whether a downward drag is page scroll or a
 * top overscroll, so the pull is *entered* on an overscroll dispatch — but GeckoView's scrolling runs
 * through APZ on the compositor, so those dispatches arrive quantised and a frame or two behind the
 * finger. Once the pull is established the page is by definition pinned at the top, so every further
 * pixel of downward travel is unambiguously pull: the magnitude is then read straight off
 * `ACTION_MOVE` (which arrives at full input rate), and the protocol is used only to withhold the
 * deltas from the page. Both axes therefore track the finger on the same clock — mixing an APZ-paced
 * vertical with a touch-paced horizontal is visible as the two axes sliding against each other.
 *
 * The visible indicator is drawn by Compose from [onPull]; this view owns only gesture math.
 */
class PullRefreshContainer(context: Context) : FrameLayout(context), NestedScrollingParent3 {

    /** True when the child is scrolled to the very top (so an overscroll is a refresh pull). */
    var canPull: () -> Boolean = { false }

    /** Asymptote of the rubber band, in px. See [rubberBandPull]; 0 = plain linear resistance. */
    var maxPullPx: Float = 0f

    /**
     * Live pull state: vertical distance in px (post-resistance), plus the horizontal finger travel
     * since the pull began — the axis the multi-function overscroll menu selects on. Emits (0, 0)
     * when the pull resets.
     */
    var onPull: (distance: Float, dx: Float) -> Unit = { _, _ -> }

    /** Final pull distance and horizontal travel in px when the gesture ends. */
    var onRelease: (distance: Float, dx: Float) -> Unit = { _, _ -> }

    /** Callback triggered on ACTION_DOWN touch events. */
    var onTouchDown: () -> Unit = {}

    private val parentHelper = NestedScrollingParentHelper(this)
    private val reusableConsumed = IntArray(2)

    // The finger the gesture is following, tracked by pointer id rather than index: a second finger
    // touching down shifts every index, and reading pointer 0 blind then teleports the pull.
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var currentX = 0f
    private var currentY = 0f

    // Finger position at the moment the overscroll began. Meaningful only while [pulling].
    private var anchorX = 0f
    private var anchorY = 0f
    private var pulling = false

    /** Raw downward travel since the pull began, before resistance. */
    private val rawPullPx: Float
        get() = if (pulling) (currentY - anchorY).coerceAtLeast(0f) else 0f

    private val pullPx: Float get() = rubberBandPull(rawPullPx, maxPullPx)

    private fun emitPull() {
        onPull(pullPx, if (pulling) currentX - anchorX else 0f)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = ev.getPointerId(0)
                currentX = ev.x
                currentY = ev.y
                pulling = false
                onTouchDown()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The tracked finger lifted while another is still down: hand the gesture over and
                // shift the anchor by the gap between them, so the pull continues from where it is
                // instead of jumping by the distance between the two fingers.
                val goneIndex = ev.actionIndex
                if (ev.getPointerId(goneIndex) == activePointerId) {
                    val nextIndex = if (goneIndex == 0) 1 else 0
                    if (nextIndex < ev.pointerCount) {
                        activePointerId = ev.getPointerId(nextIndex)
                        anchorX += ev.getX(nextIndex) - currentX
                        anchorY += ev.getY(nextIndex) - currentY
                        currentX = ev.getX(nextIndex)
                        currentY = ev.getY(nextIndex)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = ev.findPointerIndex(activePointerId)
                if (index >= 0) {
                    currentX = ev.getX(index)
                    currentY = ev.getY(index)
                    if (pulling) {
                        // Reversed all the way past the anchor: the pull is spent and the page owns
                        // vertical scrolling again (onNestedPreScroll stops withholding deltas).
                        if (rawPullPx <= 0f) pulling = false
                        emitPull()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        // Observation only — nothing is consumed here, so the child's own gesture handling and the
        // nested-scroll protocol below are untouched.
        return super.dispatchTouchEvent(ev)
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean =
        type == ViewCompat.TYPE_TOUCH && (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        parentHelper.onNestedScrollAccepted(child, target, axes, type)
        pulling = false
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // Reversing the drag (page wants to scroll up, dy > 0) must unwind the pull before the page
        // moves. The touch stream already shrank the magnitude for this move, so nothing is
        // accumulated here — this only withholds the delta from the page until the pull is spent.
        if (type == ViewCompat.TYPE_TOUCH && dy > 0 && pulling) {
            consumed[1] = min(dy.toFloat(), rawPullPx).toInt()
        }
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray,
    ) {
        // dyUnconsumed < 0 means the page couldn't scroll further up: a top overscroll, and the one
        // signal that tells us a drag is a pull rather than a scroll. Enter the pull here and anchor
        // to where the finger was when it began (the dispatched overscroll is travel already made);
        // from here on the magnitude comes off the touch stream.
        if (type == ViewCompat.TYPE_TOUCH && dyUnconsumed < 0 && canPull()) {
            if (!pulling) {
                pulling = true
                anchorX = currentX
                anchorY = currentY + dyUnconsumed
            }
            consumed[1] += dyUnconsumed
            emitPull()
        }
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
    ) {
        reusableConsumed[0] = 0
        reusableConsumed[1] = 0
        onNestedScroll(
            target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, reusableConsumed,
        )
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        parentHelper.onStopNestedScroll(target, type)
        if (type == ViewCompat.TYPE_TOUCH) {
            val distance = pullPx
            val travel = if (pulling) currentX - anchorX else 0f
            pulling = false
            if (distance > 0f) onRelease(distance, travel)
            onPull(0f, 0f)
        }
    }

    internal companion object {
        // Fallback resistance when no rubber-band asymptote is configured. See rubberBandPull.
        const val LINEAR_DRAG_RATE = 0.5f
    }
}
