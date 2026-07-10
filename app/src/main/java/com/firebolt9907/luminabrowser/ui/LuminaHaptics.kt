package com.firebolt9907.luminabrowser.ui

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * A small vocabulary of *semantic* haptics for Lumina.
 *
 * Compose's [androidx.compose.ui.hapticfeedback.HapticFeedback] only exposes sharp taps
 * (`LongPress`/`TextHandleMove`), so every gesture ended up feeling identical — a crisp click.
 * This routes each interaction through the raw [Vibrator] so the *texture* of the buzz matches the
 * *meaning* of the gesture: a soft motor-like rumble when a continuous drag "catches", a light tick
 * when a threshold arms, a clean click when something is created, and a negating double-pulse when
 * something is removed.
 *
 * Prefer this over `performHapticFeedback(LongPress)` for anything the user can feel a metaphor in.
 */
class LuminaHaptics(
    private val vibrator: Vibrator?,
    private val resolver: ContentResolver?,
) {

    /** Mirrors what the user set in Settings → Sound → Touch feedback / haptics. */
    private fun systemHapticsEnabled(): Boolean {
        val r = resolver ?: return true
        return Settings.System.getInt(r, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }

    private fun play(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        // Respect the OS touch-feedback toggle, exactly like View.performHapticFeedback did — a user
        // who turned haptics off must never feel these.
        if (!systemHapticsEnabled()) return
        v.vibrate(effect)
    }

    /**
     * The grid gesture has "caught" — fired once, the moment the drag crosses its open threshold
     * (there is no build-up ramp on the way up; this is the whole event). A soft low rumble: a few
     * gentle ERM-style cycles at low amplitude (~12% peak over ~90ms). Much weaker than the original
     * and tuned to sit below the iPhone app-switcher rumble, but rich enough to still read as a
     * *rumble* rather than a single faint pulse. Degrades to one short buzz without amplitude control.
     */
    fun gridEngage() = play(
        VibrationEffect.createWaveform(
            longArrayOf(0, 12, 32, 12, 32, 12, 32),
            intArrayOf(0, 18, 12, 18, 12, 18, 12),
            -1,
        )
    )

    /**
     * One tick of a build-up ramp (see [DragRamp]). [intensity] 0..1 scales the amplitude so a drag
     * *feels* like it is winding tension up as it approaches a threshold. Kept light (peak ~25%) so a
     * real commit — [commitStrong] — clearly stands apart. On motors without amplitude control the
     * amplitude is ignored; the accelerating *density* of ticks still conveys the build-up.
     */
    fun rampTick(intensity: Float) {
        val i = intensity.coerceIn(0f, 1f)
        val amp = (10f + 55f * i).toInt().coerceIn(1, 255)
        play(VibrationEffect.createOneShot(8, amp))
    }

    /**
     * A decisive commit — a drag crossed the point of no return and something is being spawned (a new
     * tab from the edge). A firm, heavy tap that caps the [DragRamp] build-up with a clear "done".
     */
    fun commitStrong() = play(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            VibrationEffect.createOneShot(18, 255)
        }
    )

    /**
     * A drag has crossed a threshold that *arms* a release action (pull-to-refresh, swipe-to-new-
     * tab). A single feather-light tick says "let go now and this will happen" without the weight of
     * a confirmation — the commit itself carries that.
     */
    fun armThreshold() = play(tick())

    /** A discrete, positive selection (switch to a tab). Light and clean — an acknowledgment. */
    fun select() = play(tick())

    /** One detent of a stepped control (zoom +/-). A featherweight tick per notch, like a dial. */
    fun step() = play(tick())

    /**
     * A binary control flipped. Enabling feels firmer (a click) and disabling is a lighter tick —
     * turning something *on* is the more consequential direction, so it carries more weight.
     */
    fun toggle(on: Boolean) = play(if (on) click() else tick())

    /** Something was created (a new tab). A single clean click — more present than [select]. */
    fun create() = play(click())

    /**
     * Something was removed/dismissed (close a tab). A quick negating double-pulse — the universal
     * "undone / taken away" feel, distinct from the affirmative single click of [create].
     */
    fun remove() = play(doubleClick())

    private fun tick(): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(10, 55)
        }

    private fun click(): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(16, 120)
        }

    private fun doubleClick(): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        } else {
            VibrationEffect.createWaveform(
                longArrayOf(0, 16, 48, 16),
                intArrayOf(0, 140, 0, 140),
                -1,
            )
        }
}

/**
 * Turns a 0..1 drag `progress` into a gradually growing vibration — an accelerating, strengthening
 * series of [LuminaHaptics.rampTick]s as a gesture approaches its commit threshold. Detents are
 * spaced *quadratically* so ticks bunch up (accelerate) near the threshold, and their amplitude
 * rises with progress, so the drag feels like winding tension up.
 *
 * Stateful: create one per gesture (via [rememberDragRamp]) and [reset] it when the gesture starts.
 * Dragging backward silently re-arms the crossed detents so a back-and-forth wiggle re-ticks on the
 * next forward pass. Feed it progress toward the *commit* point (e.g. `gridProgress / 0.5f`), not
 * raw travel, so the build-up peaks exactly where the threshold is.
 */
class DragRamp(private val haptics: LuminaHaptics, private val steps: Int = 14) {
    // Starts at 0 (not -1) so the very start of a drag doesn't tick; the first detent lands once
    // progress passes sqrt(1/steps) (~27%), leaving the initial motion silent.
    private var lastStep = 0

    fun update(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val step = (steps * p * p).toInt()
        if (step > lastStep) {
            lastStep = step
            haptics.rampTick(p)
        } else if (step < lastStep) {
            lastStep = step // dragged back — re-arm without buzzing
        }
    }

    fun reset() {
        lastStep = 0
    }
}

@Composable
fun rememberDragRamp(haptics: LuminaHaptics): DragRamp =
    remember(haptics) { DragRamp(haptics) }

@Composable
fun rememberLuminaHaptics(): LuminaHaptics {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        LuminaHaptics(vibrator, context.contentResolver)
    }
}
