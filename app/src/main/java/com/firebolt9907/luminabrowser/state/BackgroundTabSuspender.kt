package com.firebolt9907.luminabrowser.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore

/**
 * Bounds the number of live GeckoSessions so a window with many tabs stays responsive.
 *
 * mozilla-components lazily creates an [mozilla.components.concept.engine.EngineSession] (a live
 * GeckoSession, backed by its own content process) the first time a tab is rendered, and never
 * tears it back down on its own. A long session that has visited 20 tabs therefore drives ~20 live
 * content processes at once — the source of the heavy lag the browser hits past a handful of tabs.
 *
 * This observer enforces an LRU policy: the foreground tab plus the [keepAliveBackground]
 * most-recently-selected background tabs keep their live session; every other live background
 * session is suspended via [EngineAction.SuspendEngineSessionAction], which closes the underlying
 * GeckoSession. Re-selecting a suspended tab transparently recreates and restores it —
 * [com.firebolt9907.luminabrowser.ui.LuminaRoot] already guarantees the selected tab a live
 * session, and the grid / tab-switch UI covers the recreation gap with cached snapshots.
 *
 * Two timing rules keep suspension from degrading the switch UX (both born from frame profiling —
 * see the tab-switch jank investigation):
 *
 * 1. **State is flushed as a tab leaves the foreground.** `SuspendMiddleware` just unlinks and
 *    closes the session; it never captures state. The tab's `engineSessionState` in [BrowserState]
 *    is only as fresh as Gecko's last periodic `GeckoView:StateUpdated` event, so suspending a
 *    recently-active tab could restore it later with stale or empty history ("No history entries
 *    found", losing back-history). Dispatching [EngineAction.FlushEngineSessionStateAction] on
 *    every foreground exit makes Gecko write current history/scroll state into the store while the
 *    session is still alive. (Guarded on a live session: the delegate middleware would otherwise
 *    *recreate* a session just to flush it.)
 *
 * 2. **Suspensions are debounced behind a quiet period.** Closing a GeckoSession is main-thread
 *    native work; dispatching it synchronously from the state observer landed it mid-gesture,
 *    measured as 25–60 ms main-thread stalls during rapid tab switching. Deferring by
 *    [quietPeriodMs] — restarted on every selection change — moves the teardown past the
 *    switching storm, and also gives the state flush in (1) time to round-trip before the
 *    session closes.
 *
 * With the default of [DEFAULT_KEEP_ALIVE_BACKGROUND] background tabs the engine behaves as if
 * only four tabs are open regardless of how many the user has accumulated.
 */
class BackgroundTabSuspender(
    private val store: BrowserStore,
    private val keepAliveBackground: Int = DEFAULT_KEEP_ALIVE_BACKGROUND,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val quietPeriodMs: Long = SUSPEND_QUIET_PERIOD_MS,
) {
    /** Tab ids ordered most-recently-selected first; decides which background sessions survive. */
    private val recency = ArrayDeque<String>()

    /**
     * Tabs already asked to suspend whose session has not been torn down yet. Prevents re-dispatching
     * [EngineAction.SuspendEngineSessionAction] on every state emission while the middleware catches up.
     */
    private val pendingSuspend = HashSet<String>()

    private var lastSelectedId: String? = null
    private var suspendJob: Job? = null

    private val subscription = store.observeManually(::onStateChange)

    fun start() {
        subscription.resume()
    }

    // Synchronized because the store observer runs on the store thread while the debounced
    // suspension fires on [scope]'s thread; both touch recency/pendingSuspend.
    @Synchronized
    private fun onStateChange(state: BrowserState) {
        val selectedId = state.selectedTabId
        val selectionChanged = selectedId != null && selectedId != lastSelectedId
        if (selectionChanged && selectedId != null) {
            val outgoing = lastSelectedId
            if (outgoing != null &&
                state.tabs.any { it.id == outgoing && it.engineState.engineSession != null }
            ) {
                store.dispatch(EngineAction.FlushEngineSessionStateAction(outgoing))
            }
            recency.remove(selectedId)
            recency.addFirst(selectedId)
            lastSelectedId = selectedId
        }

        // Forget recency/pending entries for tabs whose session is gone or that closed.
        val liveTabIds = state.tabs.mapTo(HashSet()) { it.id }
        recency.retainAll(liveTabIds)
        pendingSuspend.retainAll(liveBackgroundTabs(state).mapTo(HashSet()) { it.id })

        if (liveBackgroundTabs(state).size <= keepAliveBackground) return

        // Over budget: (re)start the quiet-period timer. A selection change restarts a running
        // timer so suspensions keep sliding past an active switching streak; other emissions
        // (e.g. a background load finishing) only start it when idle.
        if (selectionChanged || suspendJob?.isActive != true) {
            suspendJob?.cancel()
            suspendJob = scope.launch {
                delay(quietPeriodMs)
                suspendOverBudgetTabs()
            }
        }
    }

    @Synchronized
    private fun suspendOverBudgetTabs() {
        val state = store.state
        val liveBackground = liveBackgroundTabs(state)
        if (liveBackground.size <= keepAliveBackground) return

        // Most-recently-selected first; tabs never selected this session (no recency entry) sort last.
        val rank = recency.withIndex().associate { (index, id) -> id to index }
        liveBackground
            .sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            .drop(keepAliveBackground)
            .forEach { tab ->
                if (pendingSuspend.add(tab.id)) {
                    store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
                }
            }
    }

    /**
     * Background tabs whose live session has finished initializing. Skips the foreground tab
     * (always kept — it is the one bound to the EngineView), tabs still loading (let background
     * loads finish; they get suspended on a later pass once idle), and crashed/initializing
     * sessions the engine layer is still mid-managing.
     */
    private fun liveBackgroundTabs(state: BrowserState): List<TabSessionState> =
        state.tabs.filter { tab ->
            tab.id != state.selectedTabId &&
                tab.engineState.engineSession != null &&
                !tab.engineState.initializing &&
                !tab.engineState.crashed &&
                !tab.content.loading
        }

    companion object {
        /** Background tabs kept live in addition to the foreground tab (so ~4 live sessions total). */
        const val DEFAULT_KEEP_ALIVE_BACKGROUND = 3

        /**
         * Idle time after the last tab switch before over-budget sessions are torn down. Long
         * enough to outlast a flick-through-tabs streak and the async state flush; short enough
         * that memory is reclaimed promptly once the user settles.
         */
        const val SUSPEND_QUIET_PERIOD_MS = 3_000L
    }
}
