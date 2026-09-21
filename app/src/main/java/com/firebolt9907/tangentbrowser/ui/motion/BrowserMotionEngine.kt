package com.firebolt9907.tangentbrowser.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    private val wake = Channel<Unit>(Channel.CONFLATED)

    suspend fun animate(
        channel: String,
        initialValue: Float,
        targetValue: Float,
        initialVelocity: Float? = null,
        animationSpec: FiniteAnimationSpec<Float>,
        block: (Float, Float) -> Unit,
    ) {
        val previous = samples[channel]
        val velocity = initialVelocity ?: previous?.takeIf { it.position == initialValue }?.velocity ?: 0f
        flights.remove(channel)?.continuation?.cancel()
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
                while (flights.isNotEmpty()) {
                    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                    withFrameNanos { tick(it, scale) }
                }
            }
        } finally {
            val pending = flights.values.toList()
            flights.clear()
            pending.forEach { it.continuation.cancel() }
            samples.clear()
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
