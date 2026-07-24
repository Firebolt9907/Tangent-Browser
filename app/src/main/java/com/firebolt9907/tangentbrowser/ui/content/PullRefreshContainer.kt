package com.firebolt9907.tangentbrowser.ui.content

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import kotlin.math.min

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
 * The visible indicator is drawn by Compose from [onPull]; this view owns only gesture math.
 */
class PullRefreshContainer(context: Context) : FrameLayout(context), NestedScrollingParent3 {

    /** True when the child is scrolled to the very top (so an overscroll is a refresh pull). */
    var canPull: () -> Boolean = { false }

    /** Live accumulated pull distance in px (post-resistance). Emits 0 when the pull resets. */
    var onPull: (Float) -> Unit = {}

    /** Final pull distance in px when the gesture ends. */
    var onRelease: (Float) -> Unit = {}

    /** Callback triggered on ACTION_DOWN touch events. */
    var onTouchDown: () -> Unit = {}

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            onTouchDown()
        }
        return super.dispatchTouchEvent(ev)
    }

    private val parentHelper = NestedScrollingParentHelper(this)
    private val reusableConsumed = IntArray(2)
    private var pullPx = 0f

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean =
        type == ViewCompat.TYPE_TOUCH && (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        parentHelper.onNestedScrollAccepted(child, target, axes, type)
        pullPx = 0f
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // Reversing the drag (page wants to scroll up, dy > 0) unwinds the pull before the page
        // moves, so the indicator tracks the finger back down to zero.
        if (type == ViewCompat.TYPE_TOUCH && dy > 0 && pullPx > 0f) {
            val appliedRaw = min(dy.toFloat(), pullPx / DRAG_RATE)
            pullPx -= appliedRaw * DRAG_RATE
            consumed[1] = appliedRaw.toInt()
            onPull(pullPx)
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
        // dyUnconsumed < 0 means the page couldn't scroll further up: a top overscroll. Convert it
        // into pull distance (with resistance) while pulling is allowed.
        if (type == ViewCompat.TYPE_TOUCH && dyUnconsumed < 0 && canPull()) {
            pullPx += -dyUnconsumed * DRAG_RATE
            consumed[1] += dyUnconsumed
            onPull(pullPx)
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
            if (pullPx > 0f) onRelease(pullPx)
            pullPx = 0f
            onPull(0f)
        }
    }

    private companion object {
        // Resistance applied to raw finger travel so the pull feels rubber-banded, matching the
        // feel of a typical pull-to-refresh.
        const val DRAG_RATE = 0.5f
    }
}
