package com.firebolt9907.luminabrowser.state

import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
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
 * session is suspended via [EngineAction.SuspendEngineSessionAction], which flushes that tab's
 * navigation state into its `engineSessionState` and closes the underlying GeckoSession. Re-selecting
 * a suspended tab transparently recreates and restores it — [com.firebolt9907.luminabrowser.ui.LuminaRoot]
 * already guarantees the selected tab a live session, and the grid / tab-switch UI covers the
 * recreation gap with cached snapshots.
 *
 * With the default of [DEFAULT_KEEP_ALIVE_BACKGROUND] background tabs the engine behaves as if only
 * three tabs are open regardless of how many the user has accumulated.
 */
class BackgroundTabSuspender(
    private val store: BrowserStore,
    private val keepAliveBackground: Int = DEFAULT_KEEP_ALIVE_BACKGROUND,
) {
    /** Tab ids ordered most-recently-selected first; decides which background sessions survive. */
    private val recency = ArrayDeque<String>()

    /**
     * Tabs already asked to suspend whose session has not been torn down yet. Prevents re-dispatching
     * [EngineAction.SuspendEngineSessionAction] on every state emission while the middleware catches up.
     */
    private val pendingSuspend = HashSet<String>()

    private var lastSelectedId: String? = null

    private val subscription = store.observeManually(::onStateChange)

    fun start() {
        subscription.resume()
    }

    private fun onStateChange(state: BrowserState) {
        val selectedId = state.selectedTabId
        if (selectedId != null && selectedId != lastSelectedId) {
            recency.remove(selectedId)
            recency.addFirst(selectedId)
            lastSelectedId = selectedId
        }

        // Background tabs whose live session has finished initializing. Skip the foreground tab
        // (always kept — it is the one bound to the EngineView), tabs still loading (let background
        // loads finish; they get suspended on a later pass once idle), and crashed/initializing
        // sessions the engine layer is still mid-managing.
        val liveBackground = state.tabs.filter { tab ->
            tab.id != selectedId &&
                tab.engineState.engineSession != null &&
                !tab.engineState.initializing &&
                !tab.engineState.crashed &&
                !tab.content.loading
        }
        val liveBackgroundIds = liveBackground.mapTo(HashSet()) { it.id }

        // Forget recency/pending entries for tabs whose session is gone or that closed.
        val liveTabIds = state.tabs.mapTo(HashSet()) { it.id }
        recency.retainAll(liveTabIds)
        pendingSuspend.retainAll(liveBackgroundIds)

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

    companion object {
        /** Background tabs kept live in addition to the foreground tab (so ~3 live sessions total). */
        const val DEFAULT_KEEP_ALIVE_BACKGROUND = 2
    }
}
