package com.firebolt9907.luminabrowser.ui.video

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.firebolt9907.luminabrowser.ui.content.loadJavascriptUrl
import mozilla.components.concept.engine.EngineSession

/**
 * Snapshot of the `<video>` element currently playing on the page, as last read over the
 * [prompt bridge][VideoPlayerController]. Positions are in milliseconds. [available] is false until
 * the first successful read (so the overlay can hide the scrubber until it knows the duration).
 */
data class VideoPlayerState(
    val available: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val paused: Boolean = true,
    val volume: Float = 1f,
)

/**
 * Holds the latest [VideoPlayerState] read from the page. The read channel is one-shot (native
 * injects a `prompt('lumina-video', …)` read; the wrapping PromptDelegate routes the payload here),
 * so the overlay extrapolates position between reads for a smooth scrubber and resyncs periodically.
 * Single app-wide instance because only one video is interactively foreground at a time.
 */
object VideoPlayerManager {
    private val _state = MutableStateFlow(VideoPlayerState())
    val state: StateFlow<VideoPlayerState> = _state.asStateFlow()

    /** Called by the prompt-bridge delegate with `currentTime|duration|paused|volume`. */
    fun onStatePayload(payload: String) {
        android.util.Log.i("LuminaVideo", "state payload=$payload")
        val parts = payload.split('|')
        if (parts.size < 3) return
        val posSec = parts[0].toDoubleOrNull() ?: return
        val durSec = parts[1].toDoubleOrNull() ?: 0.0
        val paused = parts[2].toBooleanStrictOrNull() ?: parts[2] == "true"
        val volume = parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: _state.value.volume
        _state.value = VideoPlayerState(
            available = durSec > 0.0,
            positionMs = (posSec * 1000).toLong(),
            durationMs = (durSec * 1000).toLong(),
            paused = paused,
            volume = volume,
        )
    }

    /** Optimistically reflect a locally-issued play/pause so the UI reacts before the next read. */
    fun setPausedOptimistic(paused: Boolean) {
        _state.value = _state.value.copy(paused = paused)
    }

    /** Optimistically reflect a scrub so the thumb doesn't snap back before the next read. */
    fun setPositionOptimistic(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    /** Optimistically reflect a volume change so the slider doesn't snap back before the next read. */
    fun setVolumeOptimistic(volume: Float) {
        _state.value = _state.value.copy(volume = volume.coerceIn(0f, 1f))
    }

    // --- System media volume (STREAM_MUSIC), shared between the overlay slider and the activity's
    // hardware volume-key handler so a key press moves the on-screen slider in real time. ---

    private val _systemVolume = MutableStateFlow(1f)
    val systemVolume: StateFlow<Float> = _systemVolume.asStateFlow()

    fun setSystemVolume(fraction: Float) {
        _systemVolume.value = fraction.coerceIn(0f, 1f)
    }

    /** Incremented whenever the controls should be shown (e.g. a hardware volume key was pressed). */
    private val _wakeTick = MutableStateFlow(0)
    val wakeTick: StateFlow<Int> = _wakeTick.asStateFlow()

    fun wakeControls() {
        _wakeTick.value += 1
    }

    /** True while the native fullscreen video overlay is active, so activities know to intercept the
     *  hardware volume keys (and suppress the system volume panel) only then. */
    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    fun setFullscreen(active: Boolean) {
        android.util.Log.i("LuminaVideo", "[fullscreen] setFullscreen=$active")
        _fullscreen.value = active
    }

    fun reset() {
        _state.value = VideoPlayerState()
    }
}

/**
 * Handle a hardware volume key while the fullscreen video overlay is active: adjust the media stream
 * *without* the system volume panel (no `FLAG_SHOW_UI`), push the new level to [VideoPlayerManager]
 * so the overlay slider tracks it in real time, and wake the controls so they're briefly visible.
 * Returns true when the event was consumed (both down and up, so the panel never appears). No-op /
 * returns false when not in fullscreen video, letting normal volume behavior proceed.
 */
fun handleVolumeKeyForVideo(context: Context, event: KeyEvent): Boolean {
    val kc = event.keyCode
    if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN) {
        android.util.Log.i("LuminaVideo", "[volkey] kc=$kc action=${event.action} fullscreen=${VideoPlayerManager.fullscreen.value}")
    }
    if (!VideoPlayerManager.fullscreen.value) return false
    if (kc != KeyEvent.KEYCODE_VOLUME_UP && kc != KeyEvent.KEYCODE_VOLUME_DOWN) return false
    if (event.action == KeyEvent.ACTION_DOWN) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (kc == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        VideoPlayerManager.setSystemVolume(
            if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else 0f,
        )
        VideoPlayerManager.wakeControls()
    }
    return true
}

/**
 * Drives the page's `<video>` element from native. Commands are self-contained `javascript:` URLs
 * (Native→Web, no persistent script needed); the state read pushes back through the `prompt()`
 * bridge that [com.firebolt9907.luminabrowser.ui.reader] installs (message `lumina-video`, payload
 * in the prompt's default value).
 */
object VideoPlayerController {
    private fun run(session: EngineSession, body: String) {
        session.loadJavascriptUrl("javascript:(()=>{try{var v=document.querySelector('video');if(v){$body}}catch(e){}})()")
    }

    fun play(session: EngineSession) = run(session, "v.play();")

    fun pause(session: EngineSession) = run(session, "v.pause();")

    /** Seek to an absolute position in milliseconds. */
    fun seekTo(session: EngineSession, positionMs: Long) =
        run(session, "v.currentTime=${positionMs / 1000.0};")

    /** Set volume in the range 0f..1f. */
    fun setVolume(session: EngineSession, volume: Float) =
        run(session, "v.muted=false;v.volume=${volume.coerceIn(0f, 1f)};")

    /** Ask the page to report the video's current time/duration/paused/volume via the prompt bridge. */
    fun requestState(session: EngineSession) = run(
        session,
        "var r=v.getBoundingClientRect();var vv=window.visualViewport;window.prompt('lumina-video',v.currentTime+'|'+(isNaN(v.duration)?0:v.duration)+'|'+v.paused+'|'+v.volume+'|ih='+window.innerHeight+'|vt='+Math.round(r.top)+'|vh='+Math.round(r.height)+'|sh='+window.screen.height+'|oh='+window.outerHeight+'|vvh='+(vv?Math.round(vv.height):-1)+'|dpr='+window.devicePixelRatio);",
    )
}
