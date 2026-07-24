package com.firebolt9907.tangentbrowser.ui.reader

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.os.CancellationSignal
import android.view.ScrollCaptureCallback
import android.view.ScrollCaptureSession
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.function.Consumer

/**
 * Bridges reader mode into Android's system "long screenshot" (scroll capture) feature — the
 * "Capture more" button that appears after a screenshot on Pixel and other stock-Android devices.
 *
 * Web pages can't participate (GeckoView renders to an out-of-process Surface and exposes no
 * [ScrollCaptureCallback]), but reader mode is native Compose, so we can. The single source of
 * capture state lives here so both [ReaderView] (which hides its overlays and keeps its graphics
 * layer armed while capturing) and the callback below agree on when a capture is in progress.
 */
object ReaderScrollCapture {
    private val _capturing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val capturing: kotlinx.coroutines.flow.StateFlow<Boolean> = _capturing

    fun setCapturing(value: Boolean) {
        _capturing.value = value
    }
}

/**
 * Feeds the system scroll-capture engine one tile at a time by programmatically scrolling the
 * reader's [ScrollState] and blitting the freshly-rendered reader viewport into the capture surface.
 *
 * Coordinate model (all in device pixels):
 * - `scrollBounds` reported to the framework is the full host view rect. It fills the screen when
 *   reader mode is active, so this is also the reader viewport.
 * - `captureArea` from the framework is relative to that origin, but its top/bottom may run negative
 *   (content above the start position) or past the bottom (content below), i.e. the off-screen
 *   content the system wants stitched. We translate that into an absolute scroll offset, scroll
 *   there, and copy the matching band out of [captureViewport].
 *
 * The methods run on the view's UI thread, so touching [scrollState] and awaiting frames is safe.
 */
@RequiresApi(Build.VERSION_CODES.S)
class ReaderScrollCaptureCallback(
    private val hostView: View,
    private val scrollState: ScrollState,
    private val scope: CoroutineScope,
    /** Returns a bitmap of the current reader viewport (excludes the floating pill by design). */
    private val captureViewport: suspend () -> Bitmap?,
) : ScrollCaptureCallback {

    private val TAG = "ReaderScrollCapture"
    private var startScroll = 0
    private var viewportW = 0
    private var viewportH = 0

    override fun onScrollCaptureSearch(signal: CancellationSignal, onReady: Consumer<Rect>) {
        viewportW = hostView.width
        viewportH = hostView.height
        com.firebolt9907.tangentbrowser.util.TangentLog.debug(TAG) { "onScrollCaptureSearch w=$viewportW h=$viewportH max=${scrollState.maxValue}" }
        // Only worth offering if there is off-screen content to stitch.
        if (viewportW <= 0 || viewportH <= 0 || scrollState.maxValue <= 0) {
            onReady.accept(Rect())
        } else {
            onReady.accept(Rect(0, 0, viewportW, viewportH))
        }
    }

    override fun onScrollCaptureStart(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        onReady: Runnable,
    ) {
        com.firebolt9907.tangentbrowser.util.TangentLog.debug(TAG) { "onScrollCaptureStart startScroll=${scrollState.value}" }
        startScroll = scrollState.value
        ReaderScrollCapture.setCapturing(true)
        // Give the capturing flag a couple of frames to hide the reader overlays and arm the
        // graphics layer before the first tile is requested.
        scope.launch {
            withFrameNanos {}
            withFrameNanos {}
            onReady.run()
        }
    }

    override fun onScrollCaptureImageRequest(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        captureArea: Rect,
        onComplete: Consumer<Rect>,
    ) {
        scope.launch {
            val captured = runCatching { renderTile(session, captureArea) }.getOrNull() ?: Rect()
            onComplete.accept(captured)
        }
    }

    private suspend fun renderTile(session: ScrollCaptureSession, captureArea: Rect): Rect {
        val maxValue = scrollState.maxValue
        val totalHeight = maxValue + viewportH

        // Absolute content offsets the framework is asking for (relative to article top).
        val requestedTop = startScroll + captureArea.top
        val requestedBottom = startScroll + captureArea.bottom

        // Clamp to the content that actually exists.
        val availTop = requestedTop.coerceAtLeast(0)
        var availBottom = requestedBottom.coerceAtMost(totalHeight)
        if (availBottom <= availTop) return Rect()

        // Scroll so the requested band sits inside the viewport, then bound the band to what this
        // single viewport can show; the framework re-requests any remainder.
        val scroll = availTop.coerceIn(0, maxValue)
        availBottom = availBottom.coerceAtMost(scroll + viewportH)
        if (availBottom <= availTop) return Rect()

        scrollState.scrollTo(scroll)
        // Wait for Compose to lay out + draw (which re-records the reader graphics layer) at the
        // new scroll position before sampling it.
        withFrameNanos {}
        withFrameNanos {}

        val bitmap = captureViewport() ?: return Rect()

        // Source band within the viewport bitmap.
        val srcTop = (availTop - scroll).coerceIn(0, bitmap.height)
        val srcBottom = (availBottom - scroll).coerceIn(srcTop, bitmap.height)
        val srcLeft = captureArea.left.coerceIn(0, bitmap.width)
        val srcRight = captureArea.right.coerceIn(srcLeft, bitmap.width)
        if (srcBottom <= srcTop || srcRight <= srcLeft) return Rect()

        val src = Rect(srcLeft, srcTop, srcRight, srcBottom)
        // Destination inside the tile: captureArea.top maps to tile y 0.
        val dst = Rect(0, availTop - requestedTop, captureArea.width(), availBottom - requestedTop)

        val surface = session.surface
        // Hardware canvas: the layer bitmap may be Config.HARDWARE, which can't be drawn on a
        // software canvas.
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(bitmap, src, dst, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }

        // Report the captured region back in the framework's coordinate space.
        return Rect(
            captureArea.left,
            availTop - startScroll,
            captureArea.right,
            availBottom - startScroll,
        )
    }

    override fun onScrollCaptureEnd(onReady: Runnable) {
        scope.launch {
            // Restore the reader to where the user was reading, then reveal the overlays again.
            runCatching { scrollState.scrollTo(startScroll) }
            ReaderScrollCapture.setCapturing(false)
            onReady.run()
        }
    }
}
