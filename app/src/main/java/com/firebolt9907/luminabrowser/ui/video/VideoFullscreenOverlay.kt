package com.firebolt9907.luminabrowser.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import mozilla.components.concept.engine.EngineSession
import kotlin.math.roundToInt

/**
 * Material 3 overlay for fullscreen video mode. Replaces YouTube's own (now-hidden) player UI with
 * Lumina controls: exit-fullscreen and PiP at the top, and a bottom bar with play/pause, a scrubber
 * and time labels driven by [VideoPlayerManager] / [VideoPlayerController]. Auto-hides after
 * [autoHideDelayMs]; a tap toggles visibility. Taps that don't hit a control pass through to the
 * video beneath.
 */
@Composable
fun VideoFullscreenOverlay(
    engineSession: EngineSession?,
    onExitFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
    autoHideDelayMs: Long = 3500L,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    val playerState by VideoPlayerManager.state.collectAsStateWithLifecycle()

    // Volume controls the Android system media stream (STREAM_MUSIC), not the <video> element's own
    // volume — YouTube manages the element volume itself, so setting it is unreliable; the system
    // stream is what the user actually hears.
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    }
    fun systemVolumeFraction(): Float {
        val am = audioManager ?: return 0f
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        return if (max > 0) am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max else 0f
    }
    // Volume is held in the manager so the activity's hardware volume-key handler and this slider
    // stay in sync; a key press moves the thumb here in real time.
    val systemVolume by VideoPlayerManager.systemVolume.collectAsStateWithLifecycle()

    // Mark fullscreen active (so the activities intercept volume keys), seed the current volume, and
    // claim the media volume-control stream. Without this, an active REMOTE media session elsewhere
    // (e.g. a music-server bridge) captures VOLUME_UP and the browser only ever sees VOLUME_DOWN;
    // setVolumeControlStream(STREAM_MUSIC) makes this foreground activity the target for BOTH keys
    // (the same thing Snapchat/Instagram do), so our dispatchKeyEvent can consume them. Reset on exit.
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        VideoPlayerManager.setSystemVolume(systemVolumeFraction())
        VideoPlayerManager.setFullscreen(true)
        onDispose {
            activity?.volumeControlStream = android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
            VideoPlayerManager.setFullscreen(false)
        }
    }

    // The OS doesn't reliably deliver both volume keys to the app (on this device VOLUME_UP is
    // swallowed upstream), so instead of relying on key interception we observe the system media
    // volume directly: any change — key, or the system panel — updates the slider in real time.
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper()),
        ) {
            override fun onChange(selfChange: Boolean) {
                VideoPlayerManager.setSystemVolume(systemVolumeFraction())
                VideoPlayerManager.wakeControls()
            }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    // A hardware volume key was pressed: show the controls briefly so the slider is visible.
    val wakeTick by VideoPlayerManager.wakeTick.collectAsStateWithLifecycle()
    LaunchedEffect(wakeTick) {
        if (wakeTick > 0) {
            controlsVisible = true
            interactionTick++
        }
    }

    // Scrubbing state: while the user drags, we show the dragged position and stop extrapolating.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }
    // Smoothly-extrapolated playback position, resynced whenever a fresh read arrives.
    var displayPositionMs by remember { mutableLongStateOf(0L) }

    // Read true position/duration/paused/volume when the controls appear (and once on mount), then
    // extrapolate. We deliberately do NOT poll on a timer: each read goes through window.prompt(),
    // and repeated prompts resize the web viewport (pushing the video up). Reading only on show +
    // right after a command keeps prompts rare and user-action-masked while the scrubber stays live
    // via the frame loop below.
    LaunchedEffect(engineSession, controlsVisible) {
        if (controlsVisible) {
            engineSession?.let { VideoPlayerController.requestState(it) }
            VideoPlayerManager.setSystemVolume(systemVolumeFraction())
        }
    }

    // Resync the display position to each fresh read (unless the user is mid-scrub).
    LaunchedEffect(playerState.positionMs) {
        if (!scrubbing) displayPositionMs = playerState.positionMs
    }

    // Advance the display position ~1x while playing, so the thumb moves between the 1 Hz reads.
    // Only runs while the controls (hence the scrubber) are on screen: with them hidden nothing
    // displays displayPositionMs, so a per-frame withFrameNanos loop during hidden-control playback
    // would be pure battery drain. When the controls reappear, the read-on-show effect above
    // resyncs displayPositionMs from a fresh playerState.positionMs.
    LaunchedEffect(controlsVisible, playerState.paused, scrubbing) {
        if (!controlsVisible || playerState.paused || scrubbing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            displayPositionMs += (now - last) / 1_000_000L
            last = now
        }
    }

    // Auto-hide; resets whenever interactionTick changes.
    LaunchedEffect(interactionTick) {
        if (controlsVisible) {
            delay(autoHideDelayMs)
            controlsVisible = false
        }
    }

    fun bumpInteraction() {
        interactionTick++
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                controlsVisible = !controlsVisible
                if (controlsVisible) bumpInteraction()
            },
    ) {
        // Top scrim + exit / PiP buttons.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.25f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayCircleButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Exit fullscreen",
                        onClick = onExitFullscreen,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (systemVolume <= 0.01f) {
                                Icons.AutoMirrored.Filled.VolumeOff
                            } else {
                                Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = "Volume",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Slider(
                            value = systemVolume,
                            onValueChange = { v ->
                                VideoPlayerManager.setSystemVolume(v)
                                audioManager?.let { am ->
                                    val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                    am.setStreamVolume(
                                        android.media.AudioManager.STREAM_MUSIC,
                                        (v * max).roundToInt(),
                                        0,
                                    )
                                }
                                bumpInteraction()
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.width(100.dp),
                        )
                        OverlayCircleButton(
                            icon = Icons.Filled.PictureInPicture,
                            contentDescription = "Picture in Picture",
                            onClick = onEnterPip,
                        )
                    }
                }
            }
        }

        // Bottom scrim + play/pause + scrubber.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.25f),
                                Color.Black.copy(alpha = 0.6f),
                            ),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // -10s / play-pause / +10s, centered.
                val durationForSkip = playerState.durationMs.coerceAtLeast(0L)
                fun skipBy(deltaMs: Long) {
                    val session = engineSession ?: return
                    val base = if (scrubbing) scrubPositionMs else displayPositionMs
                    val target = (base + deltaMs).coerceIn(0L, if (durationForSkip > 0) durationForSkip else Long.MAX_VALUE)
                    VideoPlayerController.seekTo(session, target)
                    VideoPlayerManager.setPositionOptimistic(target)
                    displayPositionMs = target
                    bumpInteraction()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayCircleButton(
                        icon = Icons.Filled.Replay10,
                        contentDescription = "Back 10 seconds",
                        onClick = { skipBy(-10_000L) },
                    )
                    OverlayCircleButton(
                        icon = if (playerState.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (playerState.paused) "Play" else "Pause",
                        size = 56.dp,
                        iconSize = 30.dp,
                        onClick = {
                            val session = engineSession ?: return@OverlayCircleButton
                            if (playerState.paused) {
                                VideoPlayerController.play(session)
                                VideoPlayerManager.setPausedOptimistic(false)
                            } else {
                                VideoPlayerController.pause(session)
                                VideoPlayerManager.setPausedOptimistic(true)
                            }
                            bumpInteraction()
                        },
                    )
                    OverlayCircleButton(
                        icon = Icons.Filled.Forward10,
                        contentDescription = "Forward 10 seconds",
                        onClick = { skipBy(10_000L) },
                    )
                }

                // Scrubber + time labels.
                val durationMs = playerState.durationMs.coerceAtLeast(0L)
                val shownPositionMs = if (scrubbing) scrubPositionMs else displayPositionMs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeLabel(shownPositionMs)
                    Slider(
                        value = if (durationMs > 0) {
                            (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        onValueChange = { fraction ->
                            scrubbing = true
                            scrubPositionMs = (fraction * durationMs).toLong()
                            bumpInteraction()
                        },
                        onValueChangeFinished = {
                            val session = engineSession
                            if (session != null) {
                                VideoPlayerController.seekTo(session, scrubPositionMs)
                                VideoPlayerManager.setPositionOptimistic(scrubPositionMs)
                                displayPositionMs = scrubPositionMs
                            }
                            scrubbing = false
                            bumpInteraction()
                        },
                        enabled = durationMs > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    TimeLabel(durationMs)
                }
            }
        }
    }
}

@Composable
private fun TimeLabel(millis: Long) {
    Text(
        text = formatTime(millis),
        color = Color.White,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        // Min-width keeps M:SS labels a stable width (no jitter as the clock ticks) while letting
        // hour-long H:MM:SS timestamps ("1:23:45") grow instead of clipping/wrapping.
        modifier = Modifier.widthIn(min = 44.dp),
    )
}

@Composable
private fun OverlayCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.White.copy(alpha = 0.15f),
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
