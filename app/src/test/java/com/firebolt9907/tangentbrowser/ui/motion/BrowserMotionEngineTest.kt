package com.firebolt9907.tangentbrowser.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class BrowserMotionEngineTest {
    @Test fun userInputStopsOldFramesAndNavigationCompletion() = runBlocking {
        val engine = BrowserMotionEngine()
        var position = 0f
        var navigated = false
        val flight = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate("back", 0f, 100f, animationSpec = BrowserPhysics.Pixels) { p, _ -> position = p }
            navigated = true
        }
        engine.tick(0)
        engine.tick(50_000_000)
        engine.track("back", 12f, 60_000_000)
        position = 12f
        engine.tick(1_000_000_000, 0f)
        flight.join()
        assertEquals(12f, position, 0f)
        assertFalse(navigated)
        assertTrue(flight.isCancelled)
    }

    @Test fun interruptedAxisRetainsMomentumAndSkipsCompletion() = runBlocking {
        val engine = BrowserMotionEngine()
        val axis = Animatable(0f)
        var completed = false
        var velocity = 0f
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate(axis, 100f, BrowserPhysics.Pixels) { velocity = this.velocity }
            completed = true
        }
        engine.tick(0); yield()
        engine.tick(50_000_000); yield()
        val position = axis.value
        val momentum = velocity
        assertTrue(momentum > 0f)
        engine.interrupt(axis)
        first.join()
        assertFalse(completed)
        assertEquals(position, axis.value, 0f)
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate(axis, 0f, BrowserPhysics.Pixels) { velocity = this.velocity }
        }
        engine.tick(60_000_000); yield()
        assertEquals(position, axis.value, 0.001f)
        assertEquals(momentum, velocity, 0.001f)
        second.cancelAndJoin()
    }

    @Test fun cancellingOldAxisCannotDetachItsReplacement() = runBlocking {
        val engine = BrowserMotionEngine()
        val axis = Animatable(0f)
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate(axis, 100f, BrowserPhysics.Pixels)
        }
        engine.tick(0); yield()
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate(axis, -100f, BrowserPhysics.Pixels)
        }
        first.join()
        engine.interrupt(axis)
        replacement.join()
        assertTrue(first.isCancelled)
        assertTrue(replacement.isCancelled)
    }

    @Test fun overviewAxesShareNormalizedPhase() {
        val fromProgress = 0.7f
        val releaseVelocity = 1.2f
        val y = androidx.compose.animation.core.TargetBasedAnimation(
            BrowserPhysics.Progress, Float.VectorConverter,
            fromProgress, 1f, releaseVelocity,
        )
        val x = androidx.compose.animation.core.TargetBasedAnimation(
            BrowserPhysics.Progress, Float.VectorConverter,
            0f, 1f, releaseVelocity / (1f - fromProgress),
        )
        for (millis in listOf(0L, 16L, 50L, 100L, 200L)) {
            assertEquals(
                x.getValueFromNanos(millis * 1_000_000),
                (y.getValueFromNanos(millis * 1_000_000) - fromProgress) / (1f - fromProgress),
                0.001f,
            )
        }
    }

    @Test fun retargetPreservesRenderedPositionAndVelocity() = runBlocking {
        val engine = BrowserMotionEngine()
        var position = 0f
        var velocity = 0f
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate("page", 0f, 100f, animationSpec = BrowserPhysics.Pixels) { p, v ->
                position = p; velocity = v
            }
        }
        engine.tick(0)
        engine.tick(50_000_000)
        val releasePosition = position
        val releaseVelocity = velocity
        assertTrue(releaseVelocity > 0f)
        first.cancelAndJoin()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate("page", position, 0f, animationSpec = BrowserPhysics.Pixels) { p, v ->
                position = p; velocity = v
            }
        }
        engine.tick(60_000_000)
        assertEquals(releasePosition, position, 0.001f)
        assertEquals(releaseVelocity, velocity, 0.001f)
        engine.tick(70_000_000)
        assertTrue(position > releasePosition) // momentum survives the target reversal
        second.cancelAndJoin()
    }

    @Test fun reducedMotionFinishesEveryCoordinateBeforeResuming() = runBlocking {
        val engine = BrowserMotionEngine()
        var x = 0f
        var y = 0f
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate("x", 0f, 100f, animationSpec = BrowserPhysics.Pixels) { p, _ -> x = p }
            assertEquals(200f, y, 0f)
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate("y", 0f, 200f, animationSpec = BrowserPhysics.Pixels) { p, _ -> y = p }
        }
        engine.tick(0, durationScale = 0f)
        first.join(); second.join()
        assertEquals(100f, x, 0f)
    }

    @Test fun replacementCancelsOldCompletionWithoutRemovingNewFlight() = runBlocking {
        val engine = BrowserMotionEngine()
        var oldCompleted = false
        var position = 0f
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate("page", 0f, 100f, animationSpec = BrowserPhysics.Pixels) { _, _ -> }
            oldCompleted = true
        }
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.animate("page", 0f, -100f, animationSpec = BrowserPhysics.Pixels) { p, _ -> position = p }
        }
        engine.tick(0, 0f)
        old.join(); replacement.join()
        assertFalse(oldCompleted)
        assertEquals(-100f, position, 0f)
    }

    @Test fun gestureVelocityIsSignedAndStaleSamplesDoNotCreateAFling() = runBlocking {
        val engine = BrowserMotionEngine()
        suspend fun release(expectedVelocity: Float) {
            val flight = launch(start = CoroutineStart.UNDISPATCHED) {
                engine.animate("back", 10f, 0f, animationSpec = BrowserPhysics.Pixels) { _, v ->
                    assertEquals(expectedVelocity, v, 0.01f)
                }
            }
            engine.tick(0)
            flight.cancelAndJoin()
        }
        engine.track("back", 20f, 0)
        engine.track("back", 10f, 10_000_000)
        release(-1000f)
        engine.track("back", 20f, 20_000_000)
        engine.track("back", 10f, 200_000_000)
        release(0f)
    }
}
