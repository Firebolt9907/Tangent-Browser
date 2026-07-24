package com.firebolt9907.tangentbrowser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job

/**
 * Storage holder for the tab-grid open/close gesture state (invariants #4, #5, #5a). Second cluster
 * of the TangentRoot state-holder refactor: grouping the ~21 grid vars into one object lets the grid
 * overlay render block be extracted from the composable without threading them individually.
 *
 * As with [SnapshotCoverState], fields are [MutableState]/[Animatable] so TangentRoot keeps local
 * `var x by grid.x` delegate aliases and every existing read/write site is unchanged. Pure storage,
 * no behavior change.
 *
 * Invariant #4 is preserved: [gridProgressState]/[gridXProgressState] stay `Animatable` (spring
 * animations only — never `snapTo` on the drag hot path) and [gridDragProgress] stays a plain
 * float-state written directly on every drag delta.
 */
@Stable
internal class GridGestureState {
    val gridDragPx: MutableFloatState = mutableFloatStateOf(0f)
    val gridGestureActive: MutableState<Boolean> = mutableStateOf(false)
    val gridOpen: MutableState<Boolean> = mutableStateOf(false)
    val gridAnimationJob: MutableState<Job?> = mutableStateOf(null)
    val gridProgressState: Animatable<Float, AnimationVector1D> = Animatable(0f)
    val gridXProgressState: Animatable<Float, AnimationVector1D> = Animatable(0f)
    val gridDragProgress: MutableFloatState = mutableFloatStateOf(0f)
    val gridThresholdHapticFired: MutableState<Boolean> = mutableStateOf(false)
    val gridAxisLockedHorizontal: MutableState<Boolean> = mutableStateOf(false)

    // Mid-switch handoff seeds (leaving/incoming frozen offsets when grid grabs mid commit-spring).
    val gridHandoffSelectedTabId: MutableState<String?> = mutableStateOf(null)
    val gridHandoffLeavingTabId: MutableState<String?> = mutableStateOf(null)
    val gridHandoffSelectedOffsetX: MutableFloatState = mutableFloatStateOf(0f)
    val gridHandoffLeavingOffsetX: MutableFloatState = mutableFloatStateOf(0f)
    val gridHandoffSelectedTopPadPx: MutableIntState = mutableIntStateOf(0)
    val gridHandoffLeavingTopPadPx: MutableIntState = mutableIntStateOf(0)

    // Finger-tracking positions captured at drag release; drive the commit animation to grid cells.
    val gridCommitFromPositions: MutableState<List<Triple<Float, Float, Float>>?> = mutableStateOf(null)
    val gridCommitFromProgress: MutableFloatState = mutableFloatStateOf(0f)
    val gridCancelFromProgress: MutableFloatState = mutableFloatStateOf(0f)

    val gridWaitingForAfterClose: MutableState<Boolean> = mutableStateOf(false)
    val gridNewTabSelected: MutableState<Boolean> = mutableStateOf(false)
    val gridBottomControlsHeightPx: MutableIntState = mutableIntStateOf(0)
}

/** Remembers a single [GridGestureState] for the lifetime of the host composable. */
@Composable
internal fun rememberGridGestureState(): GridGestureState = remember { GridGestureState() }
