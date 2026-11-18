package com.firebolt9907.tangentbrowser.ui.content

import android.content.Context
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView

/**
 * Owns a **single reusable** peek [EngineSession] + [EngineView] for the link-glance preview.
 *
 * Reusing one session across long-presses (re-`loadUrl` instead of create-then-close every time)
 * avoids spinning up and tearing down a whole content process per peek. That churn is what fragments
 * native memory and provokes the `NativeAlloc` GC hitches seen while profiling — and it momentarily
 * pushes past the app's live-session cap. Here there is exactly one extra session, reused, and
 * [release] frees it entirely under memory pressure (it is lazily recreated on the next peek).
 *
 * Lifecycle ownership note: the [EngineView] is created/released **only** here — `LinkPeekCard`'s
 * `AndroidView` merely attaches/detaches it, and must NOT call `release()`/`close()` on dispose.
 */
class PeekEngineHolder(private val engine: Engine) {
    var session: EngineSession? = null
        private set
    var view: EngineView? = null
        private set
    private var isPrivate: Boolean = false
    private var currentContextId: String? = null

    /** Ensure a session + view exist for the requested privacy and container, recreating if changed. */
    fun ensure(
        context: Context,
        private: Boolean,
        contextId: String? = null,
    ): Pair<EngineSession, EngineView> {
        if (session != null && (isPrivate != private || currentContextId != contextId)) release()
        isPrivate = private
        currentContextId = contextId
        val s = session ?: engine.createSession(private, contextId).also { session = it }
        val v = view ?: engine.createView(context).also {
            it.setActivityContext(context)
            view = it
        }
        return s to v
    }

    /**
     * Hand the live peek session off to a caller that will adopt it into a real store tab (so the
     * already-loaded page is reused instead of reloaded). Releases the peek [EngineView] — which
     * detaches the `GeckoSession` from the peek `GeckoView` so it is free to bind to another view —
     * but deliberately does **not** `close()` the session. Clears the holder; the next peek lazily
     * recreates a fresh session + view. Returns null when there is no live session (e.g. the
     * low-memory static-glance path never created one), in which case the caller must fall back to a
     * normal load.
     *
     * Caller contract: only invoke this once the glance card's `AndroidView` has been detached
     * (peek URL cleared and the card removed), so the view isn't released out from under a live
     * `AndroidView` — the same rule the deferred [release] path in `TangentRoot` observes.
     */
    fun detachSessionForAdoption(): Pair<EngineSession, String?>? {
        val s = session ?: return null
        val ctxId = currentContextId
        view?.let { runCatching { it.release() } }
        view = null
        session = null
        isPrivate = false
        currentContextId = null
        return s to ctxId
    }

    /** Tear down the session + view (frees the content process). Safe to call when already empty. */
    fun release() {
        view?.let { runCatching { it.release() } }
        session?.let { runCatching { it.close() } }
        view = null
        session = null
        currentContextId = null
    }
}
