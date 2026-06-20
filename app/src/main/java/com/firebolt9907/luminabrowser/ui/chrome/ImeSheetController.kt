package com.firebolt9907.luminabrowser.ui.chrome

import android.animation.ValueAnimator
import android.os.Build
import android.os.CancellationSignal
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimationControlListener
import android.view.WindowInsetsAnimationController
import android.view.animation.LinearInterpolator
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.animation.doOnEnd

/**
 * Manual, frame-locked control of the soft-keyboard (IME) inset for the address sheet's
 * drag-to-dismiss gesture. Wraps the platform [WindowInsetsAnimationController] (API 30+) so a
 * downward drag pushes the keyboard down in lock-step with the finger; on release the keyboard
 * either settles back up or finishes hiding.
 *
 * While we hold control, the live `WindowInsets.ime` shrinks/grows as we move it, so any Compose
 * `Modifier.imePadding()` content (here: the address sheet and the editing pill) tracks the drag
 * for free — we only have to read [currentFraction] to drive the backdrop scale/scrim.
 *
 * Below API 30 the platform offers no manual control; [isSupported] is false and callers fall back
 * to a plain dismiss (the system hides the keyboard with its own animation).
 */
class ImeSheetController(private val view: View) {

    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private var controller: WindowInsetsAnimationController? = null
    private var requesting = false
    private var cancellationSignal: CancellationSignal? = null
    private var settleAnimator: ValueAnimator? = null
    /** Drag delta (px, positive = up) that arrived before [controller] was ready. */
    private var pendingUp = 0f
    private var onReadyCallback: (() -> Unit)? = null

    val isReady: Boolean get() = controller != null
    val isActive: Boolean get() = controller != null || requesting

    /**
     * Ask the platform for control of the IME animation. [onReady] fires once control is granted
     * (possibly a frame later); any drag applied via [insetByUp] in the meantime is buffered and
     * replayed.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun startControlRequest(onReady: () -> Unit) {
        if (controller != null || requesting) return
        settleAnimator?.cancel()
        settleAnimator = null
        requesting = true
        onReadyCallback = onReady
        pendingUp = 0f
        cancellationSignal = CancellationSignal()
        view.windowInsetsController?.controlWindowInsetsAnimation(
            WindowInsets.Type.ime(),
            -1L,
            LinearInterpolator(),
            cancellationSignal,
            object : WindowInsetsAnimationControlListener {
                override fun onReady(c: WindowInsetsAnimationController, types: Int) {
                    controller = c
                    requesting = false
                    onReadyCallback?.invoke()
                    if (pendingUp != 0f) {
                        insetByUp(pendingUp)
                        pendingUp = 0f
                    }
                }

                override fun onFinished(c: WindowInsetsAnimationController) = reset()
                override fun onCancelled(c: WindowInsetsAnimationController?) = reset()
            },
        )
    }

    /**
     * Move the keyboard by [dyUp] pixels (positive = toward shown/up, negative = toward hidden).
     * Returns the amount actually consumed after clamping to the [hidden, shown] range.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun insetByUp(dyUp: Float): Float {
        val c = controller ?: run {
            if (requesting) pendingUp += dyUp
            return 0f
        }
        val shown = c.shownStateInsets.bottom
        val hidden = c.hiddenStateInsets.bottom
        val current = c.currentInsets.bottom
        val target = (current + dyUp).coerceIn(hidden.toFloat(), shown.toFloat())
        val fraction = fractionOf(target, hidden, shown)
        c.setInsetsAndAlpha(
            android.graphics.Insets.of(0, 0, 0, target.toInt()),
            1f,
            fraction,
        )
        return target - current
    }

    /** Bottom inset (px) of the fully-shown keyboard — i.e. how far the sheet can travel down. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun shownInsetBottom(): Float = (controller?.shownStateInsets?.bottom ?: 0).toFloat()

    /**
     * How far (px, ≥ 0) the keyboard has descended from its fully-shown position right now. Compose
     * `imePadding()` does NOT track manual control, so the sheet translates itself by this amount to
     * stay glued to the keyboard top while dragging.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun descentPx(): Float {
        val c = controller ?: return 0f
        return (c.shownStateInsets.bottom - c.currentInsets.bottom).toFloat().coerceAtLeast(0f)
    }

    /** Current shown-fraction in [0,1]: 1 = keyboard fully up (sheet open), 0 = fully hidden. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun currentFraction(): Float {
        val c = controller ?: return 1f
        return fractionOf(
            c.currentInsets.bottom.toFloat(),
            c.hiddenStateInsets.bottom,
            c.shownStateInsets.bottom,
        )
    }

    /**
     * Animate the keyboard to its [show]n or hidden resting state, then release control. [onEnd]
     * fires after the settle completes (use it to flip app state, e.g. leave editing mode).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun animateToFinish(show: Boolean, onEnd: () -> Unit) {
        val c = controller ?: run { onEnd(); return }
        val shown = c.shownStateInsets.bottom
        val hidden = c.hiddenStateInsets.bottom
        val start = c.currentInsets.bottom
        val end = if (show) shown else hidden
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofInt(start, end).apply {
            duration = 200L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val ctrl = controller ?: return@addUpdateListener
                val value = anim.animatedValue as Int
                ctrl.setInsetsAndAlpha(
                    android.graphics.Insets.of(0, 0, 0, value),
                    1f,
                    fractionOf(value.toFloat(), hidden, shown),
                )
            }
            doOnEnd {
                controller?.finish(show)
                reset()
                onEnd()
            }
            start()
        }
    }

    /** Abort any in-flight control/request without animating (e.g. the sheet was force-dismissed). */
    fun cancel() {
        settleAnimator?.cancel()
        settleAnimator = null
        cancellationSignal?.cancel()
        controller?.finish(false)
        reset()
    }

    private fun reset() {
        controller = null
        requesting = false
        pendingUp = 0f
        onReadyCallback = null
        cancellationSignal = null
    }

    private fun fractionOf(value: Float, hidden: Int, shown: Int): Float {
        val span = (shown - hidden).toFloat()
        if (span <= 0f) return 1f
        return ((value - hidden) / span).coerceIn(0f, 1f)
    }
}

@Composable
fun rememberImeSheetController(): ImeSheetController {
    val view = LocalView.current
    val controller = remember(view) { ImeSheetController(view) }
    // If the sheet leaves composition mid-gesture, abort any in-flight settle animator and release
    // the held WindowInsetsAnimationController so neither leaks. cancel() is a no-op below API 30.
    DisposableEffect(controller) {
        onDispose { controller.cancel() }
    }
    return controller
}
