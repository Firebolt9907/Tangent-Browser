package com.firebolt9907.luminabrowser.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
 * Storage holder for the tab-switch / predictive-back snapshot + sticky-cover state machine
 * (AGENTS invariant #7). Grouping this ~24-field cluster into one object is the first step of the
 * LuminaRoot state-holder refactor: once the snapshot/cover state travels as a single object rather
 * than a bag of closed-over locals, the gesture render blocks that consume it can be extracted from
 * the ~3500-line composable without threading two dozen individual parameters.
 *
 * Fields are exposed as [MutableState] (matching the existing `offsetXState` idiom in LuminaRoot)
 * so the composable keeps using local `var x by state.x` delegate aliases — every existing
 * read/write site is untouched and the Kotlin compiler strictly verifies none were missed.
 *
 * This is pure storage: it holds no logic and takes no dependencies, so relocating it changes no
 * behavior. The cover state-machine helpers (clearStickyCover, fadeOutStickyCover, …) remain in
 * LuminaRoot for now; they will migrate onto this holder in a later step.
 */
@Stable
internal class SnapshotCoverState {
    // Sticky incoming cover: the frozen bitmap that covers the incoming tab until Gecko repaints.
    val stickyIncomingSnapshot: MutableState<Bitmap?> = mutableStateOf(null)
    val stickyIncomingTabId: MutableState<String?> = mutableStateOf(null)
    val stickyIncomingTopPadPx: MutableIntState = mutableIntStateOf(0)

    // Predictive-back cover: the previous-history-page bitmap shown during a back-swipe.
    val stickyBackCoverSnapshot: MutableState<Bitmap?> = mutableStateOf(null)
    val stickyBackCoverUrl: MutableState<String?> = mutableStateOf(null)
    val gestureCurrentSnapshot: MutableState<Bitmap?> = mutableStateOf(null)
    val activePredictiveBackSnapshot: MutableState<Bitmap?> = mutableStateOf(null)

    // Horizontal tab-switch: frozen snapshots of the leaving / incoming tabs during a swipe.
    val tabSwitchCurrentSnapshot: MutableState<Bitmap?> = mutableStateOf(null)
    val leavingSnapshotBitmap: MutableState<Bitmap?> = mutableStateOf(null)
    val leavingSnapshotOffsetX: MutableFloatState = mutableFloatStateOf(0f)
    val leavingSnapshotTopPadPx: MutableIntState = mutableIntStateOf(0)
    val leavingSnapshotTabId: MutableState<String?> = mutableStateOf(null)
    val leavingSnapshotJob: MutableState<Job?> = mutableStateOf(null)
    val incomingSnapshotBitmap: MutableState<Bitmap?> = mutableStateOf(null)
    val incomingSnapshotOffsetX: MutableFloatState = mutableFloatStateOf(0f)
    val incomingSnapshotTopPadPx: MutableIntState = mutableIntStateOf(0)
    val incomingSnapshotTabId: MutableState<String?> = mutableStateOf(null)

    // Fade-out phase of the sticky incoming cover (cross-fades into the live page underneath).
    val fadingCoverBitmap: MutableState<Bitmap?> = mutableStateOf(null)
    val fadingCoverTopPadPx: MutableIntState = mutableIntStateOf(0)
    val fadingCoverActive: MutableState<Boolean> = mutableStateOf(false)
    val fadingCoverJob: MutableState<Job?> = mutableStateOf(null)
    val fadingCoverAlpha: Animatable<Float, AnimationVector1D> = Animatable(0f)

    // Shared single-slot snapshot request routed to EngineView.captureThumbnail (invariant #6).
    val currentSnapshotRequestPurpose: MutableState<SnapshotRequestPurpose?> = mutableStateOf(null)
    val currentSnapshotRequestId: MutableIntState = mutableIntStateOf(0)

    // False until Gecko fires its first paint callback for the current tab — gates cover clearing
    // (invariant #7) so a cover isn't dropped over the grey pre-composite GeckoView surface.
    val geckoHasPainted: MutableState<Boolean> = mutableStateOf(false)
    // Set by cancelLeavingSnapshot when a "+"-cell commit created a new tab; drives the
    // new-tab commit animation instead of the tab-switch one.
    val commitAnimNewTab: MutableState<Boolean> = mutableStateOf(false)
}

/** Remembers a single [SnapshotCoverState] for the lifetime of the host composable. */
@androidx.compose.runtime.Composable
internal fun rememberSnapshotCoverState(): SnapshotCoverState = remember { SnapshotCoverState() }
