package com.firebolt9907.tangentbrowser.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Distinguishes new input from the system cancelling a predictive-back gesture. */
internal class MotionInterruptedException : CancellationException("Motion superseded by user input")

/**
 * One frame driver for browser-owned scalar transitions. A channel is a physical coordinate,
 * not an animation name: commit and cancel must use the same channel. Retargeting at the last
 * rendered position inherits momentum; gesture release can supply a measured velocity instead.
 * All layers attached to a coordinate are updated together before completion callbacks run.
 * Platform-owned IME/window animations must continue to follow their platform clocks.
 */
internal class BrowserMotionEngine {
    private data class Sample(val position: Float, val velocity: Float)
    private class Flight(
        val animation: TargetBasedAnimation<Float, androidx.compose.animation.core.AnimationVector1D>,
        val continuation: CancellableContinuation<Unit>,
        val update: (Float, Float) -> Unit,
        var startNanos: Long = Long.MIN_VALUE,
        var playNanos: Long = 0L,
    )

    private val flights = linkedMapOf<String, Flight>()
    private val samples = mutableMapOf<String, Sample>()
    private val gestureTimes = mutableMapOf<String, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val frameClock = BroadcastFrameClock { wake.trySend(Unit) }
    private val axisSamples = java.util.WeakHashMap<Animatable<Float, AnimationVector1D>, Sample>()

    private val axisOwners = java.util.WeakHashMap<Animatable<Float, AnimationVector1D>, Job>()

    /** Stop before the next input write; cancellation cannot execute the old completion action. */
    fun interrupt(channel: String) {
        flights.remove(channel)?.continuation?.cancel(MotionInterruptedException())
    }

    fun interrupt(axis: Animatable<Float, AnimationVector1D>) {
        axisOwners.remove(axis)?.cancel(MotionInterruptedException())
    }

    /** Existing Animatable holders keep their gesture/snapshot invariants, on this same clock. */
    suspend fun animate(
        axis: Animatable<Float, AnimationVector1D>,
        targetValue: Float,
        animationSpec: FiniteAnimationSpec<Float>,
        initialVelocity: Float? = null,
        block: (Animatable<Float, AnimationVector1D>.() -> Unit)? = null,
    ): Unit = coroutineScope {
        val owner = currentCoroutineContext().job
        val previous = axisSamples[axis]
        val velocity = initialVelocity ?: previous?.takeIf { it.position == axis.value }?.velocity ?: axis.velocity
        interrupt(axis)
        axisOwners[axis] = owner
        try {
            withContext(frameClock) {
                axis.animateTo(targetValue, animationSpec, velocity) {
                    axisSamples[axis] = Sample(value, this.velocity)
                    block?.invoke(this)
                }
            }
            axisSamples[axis] = Sample(axis.value, 0f)
        } finally {
            // An old continuation must never detach its replacement.
            if (axisOwners[axis] === owner) axisOwners.remove(axis)
        }
    }

    /** Feed the already-rendered gesture pose; never launches a coroutine on a drag delta. */
    fun track(channel: String, position: Float, timeNanos: Long = System.nanoTime()) {
        interrupt(channel)
        val previousTime = gestureTimes.put(channel, timeNanos)
        val previous = samples[channel]
        val elapsed = if (previousTime == null) 0L else timeNanos - previousTime
        val velocity = if (previous != null && elapsed in 1L..80_000_000L)
            (position - previous.position) * 1_000_000_000f / elapsed else 0f
        samples[channel] = Sample(position, velocity)
    }

    suspend fun animate(
        channel: String,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float? = null,
        animationSpec: FiniteAnimationSpec<Float>,
        block: (Float, Float) -> Unit,
    ) {
        val previous = samples[channel]
        gestureTimes.remove(channel)
        val velocity = initialVelocity ?: previous?.takeIf { it.position == initialValue }?.velocity ?: 0f
        interrupt(channel)
        suspendCancellableCoroutine<Unit> { continuation ->
            val flight = Flight(
                TargetBasedAnimation(animationSpec, Float.VectorConverter, initialValue, targetValue, velocity),
                continuation,
                block,
            )
            flights[channel] = flight
            continuation.invokeOnCancellation {
                if (flights[channel] === flight) flights.remove(channel)
            }
            wake.trySend(Unit)
        }
    }

    /** Called from the host's composition coroutine; there is no idle frame loop. */
    suspend fun run() {
        try {
            while (true) {
                wake.receive()
                while (flights.isNotEmpty() || frameClock.hasAwaiters) {
                    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                    withFrameNanos {
                        tick(it, scale)
                    }
                }
            }
        } finally {
            val pending = flights.values.toList()
            flights.clear()
            pending.forEach { it.continuation.cancel() }
            samples.clear()
            axisSamples.clear()
            val owners = axisOwners.values.toList()
            axisOwners.clear()
            owners.forEach { it.cancel() }
            gestureTimes.clear()
            frameClock.cancel()
        }
    }

    internal fun tick(frameNanos: Long, durationScale: Float = 1f) {
        val completed = mutableListOf<Pair<String, Flight>>()
        Snapshot.withMutableSnapshot {
            flights.toList().forEach { (channel, flight) ->
                if (flights[channel] !== flight) return@forEach
                if (flight.startNanos == Long.MIN_VALUE) flight.startNanos = frameNanos
                val delta = (frameNanos - flight.startNanos).coerceAtLeast(0L)
                flight.startNanos = frameNanos
                flight.playNanos = if (durationScale <= 0f) flight.animation.durationNanos else
                    (flight.playNanos + (delta / durationScale).toLong()).coerceAtMost(flight.animation.durationNanos)
                val finished = flight.animation.isFinishedFromNanos(flight.playNanos)
                val position = flight.animation.getValueFromNanos(flight.playNanos)
                val velocity = if (finished) 0f else flight.animation.getVelocityVectorFromNanos(flight.playNanos).value
                samples[channel] = Sample(position, velocity)
                flight.update(position, velocity)
                if (finished) completed += channel to flight
            }
        }
        frameClock.sendFrame(frameNanos)
        completed.forEach { (channel, flight) ->
            if (flights[channel] === flight) {
                flights.remove(channel)
                flight.continuation.resume(Unit)
            }
        }
    }
}

@Composable
internal fun rememberBrowserMotionEngine(): BrowserMotionEngine {
    val engine = remember { BrowserMotionEngine() }
    LaunchedEffect(engine) { engine.run() }
    return engine
}
