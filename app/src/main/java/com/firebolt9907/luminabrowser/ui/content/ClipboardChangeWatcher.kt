package com.firebolt9907.luminabrowser.ui.content

import android.content.ClipboardManager
import android.content.Context

/**
 * Detects that the clipboard's *primary clip* changed — **without reading its contents**, so no
 * Android 12+ "Expedition pasted from your clipboard" access toast fires. Only clip *metadata* is
 * touched: `hasPrimaryClip()` and [android.content.ClipDescription.getTimestamp], neither of which
 * counts as a clipboard read. The actual text is read (and the toast shown) only later, when the
 * user taps the paste banner.
 *
 * Two complementary signals feed the OTP paste banner (see [OtpAutofill]):
 *  - A live [ClipboardManager.OnPrimaryClipChangedListener] while the app is foreground. This fires
 *    the instant an in-app copy happens.
 *  - A timestamp comparison on return-to-foreground ([consumeChanged]). The live listener does NOT
 *    fire for changes made by *other* apps while we were backgrounded, and Android never replays
 *    those missed changes. Snapshotting the clip timestamp and comparing it when we resume recovers
 *    the common flow — copy the code from Messages, switch back — as if the change had been replayed.
 *
 * One instance per foreground activity; created via `remember` in the composable that owns it.
 */
class ClipboardChangeWatcher(context: Context) {

    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /** Timestamp (ms) of the clip we last accounted for. Seeded now so a pre-existing, stale clip
     *  present at startup is not mistaken for a fresh change. */
    private var lastTimestamp: Long = readTimestamp()

    /** Metadata-only read of the primary clip's placement time; 0 when empty or unavailable. */
    private fun readTimestamp(): Long = try {
        if (clipboard?.hasPrimaryClip() == true) clipboard.primaryClipDescription?.timestamp ?: 0L
        else 0L
    } catch (e: Exception) {
        0L
    }

    /**
     * Returns true if the primary clip looks like it holds something freshly copied — either it
     * changed since the last call, or it was placed within [recencyMs] (which also covers "copied
     * the code, *then* navigated to the OTP page", where no change happens after we start watching).
     * Advances the internal watermark, so a plain change reports true only once.
     */
    fun consumeChanged(recencyMs: Long = FRESH_WINDOW_MS): Boolean {
        val ts = readTimestamp()
        val now = System.currentTimeMillis()
        val changed = ts != 0L && (ts != lastTimestamp || now - ts <= recencyMs)
        lastTimestamp = ts
        return changed
    }

    fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        clipboard?.addPrimaryClipChangedListener(listener)
    }

    fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        clipboard?.removePrimaryClipChangedListener(listener)
    }

    companion object {
        /** A clip placed within this window is treated as "fresh" even absent an observed change. */
        const val FRESH_WINDOW_MS = 3 * 60_000L
    }
}
