package com.firebolt9907.tangentbrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job

/**
 * Storage holder for the horizontal tab-switch / predictive-back drag gesture (invariant #5).
 * Third cluster of the TangentRoot state-holder refactor. Grouping these vars into one object lets
 * the horizontal-drag overlay render block be extracted from the composable.
 *
 * As with the sibling holders, fields are [MutableState]/[MutableFloatState] so TangentRoot keeps
 * local `var x by hdrag.x` delegate aliases and every read/write site is unchanged. Pure storage,
 * no behavior change. [offsetX] deliberately stays a plain float-state (written directly on the
 * drag hot path); the `predictiveBackActive` derived gate stays in the composable, reading the
 * [predictiveBackOffsetPx] alias.
 */
@Stable
internal class HorizontalGestureState {
    val offsetX: MutableFloatState = mutableFloatStateOf(0f)
    val predictiveBackOffsetPx: MutableFloatState = mutableFloatStateOf(0f)
    val dragCommitted: MutableState<Boolean> = mutableStateOf(false)
    val switchAnimationJob: MutableState<Job?> = mutableStateOf(null)
    val predictiveBackAnimationJob: MutableState<Job?> = mutableStateOf(null)
}

/** Remembers a single [HorizontalGestureState] for the lifetime of the host composable. */
@Composable
internal fun rememberHorizontalGestureState(): HorizontalGestureState =
    remember { HorizontalGestureState() }
