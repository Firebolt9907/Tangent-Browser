package com.firebolt9907.luminabrowser.state

import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.recover.RecoverableTab
import mozilla.components.browser.state.state.recover.TabState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSessionState
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import com.firebolt9907.luminabrowser.ui.content.extractCacheHtml

/** A closed tab remembered for the "Reopen Closed Tab" action — URL + title, no session history. */
private data class ClosedTab(val id: String, val url: String, val title: String)

/** Upper bound on the recently-closed-tab stack so a long session can't grow it without limit. */
private const val MAX_CLOSED_TABS = 25

/**
 * Production [TabRepository] backed by mozilla-components' [BrowserStore]. Also tracks
 * the previous selected-tab id so [dismissBlankTabAndReturn] can return there.
 */
class GeckoTabRepository(
    private val store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val tabsUseCases: TabsUseCases,
    // Deserializes persisted EngineSessionState blobs on restore. Nullable so tests that don't
    // exercise persistence can omit it; when null, restored tabs fall back to a URL-only load.
    private val engine: Engine? = null,
    private val persistence: TabPersistence? = null,
    private val scope: CoroutineScope? = null,
    // Resolves the user's current search engine at call time, so a settings change takes effect
    // immediately for the next typed search without rebuilding the repository.
    private val searchEngine: () -> SearchEngine = { SearchEngine.DEFAULT },
    // Organizational tab groups. Nullable so tests can omit it. Membership is persisted per-tab
    // (group ids are stable; live tab ids are not) and re-linked on restore via [assignRestored].
    private val tabGroups: TabGroupsRepository? = null,
) : TabRepository {

    private val _tabs = MutableStateFlow(store.state.toLuminaTabs())
    override val tabs: StateFlow<List<LuminaTab>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow(store.state.selectedTabId)
    override val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()

    private var previousSelectedTabId: String? = null

    /**
     * Recently-closed tabs (most-recent last), for the Safari/Arc-style "Reopen Closed Tab" action.
     * Bounded so a long session can't grow it without limit; only real web pages are recorded
     * (blank/about: closes aren't worth reopening). Restored URL+title only — no session history.
     */
    private val closedTabs = ArrayDeque<ClosedTab>()
    private val _reopenableCount = MutableStateFlow(0)
    /** Number of reopenable closed tabs; UI enables "Reopen Closed Tab" when > 0. */
    override val reopenableCount: StateFlow<Int> = _reopenableCount.asStateFlow()
    private val _recentlyClosedTabs = MutableStateFlow<List<ClosedTabInfo>>(emptyList())
    /** Recently-closed tabs, most-recent first, for the "Recently closed" list. */
    override val recentlyClosedTabs: StateFlow<List<ClosedTabInfo>> = _recentlyClosedTabs.asStateFlow()

    /** Republish the public most-recent-first snapshot after any change to [closedTabs]. */
    private fun publishClosedTabs() {
        _reopenableCount.value = closedTabs.size
        _recentlyClosedTabs.value = closedTabs.asReversed().map {
            ClosedTabInfo(id = it.id, url = it.url, title = it.title)
        }
    }

    /** Last time each tab was the selected/foreground tab, keyed by tab id. */
    private val lastAccessById = mutableMapOf<String, Long>()
    private var persistJob: Job? = null

    private val storeSubscription = store.observeManually { state ->
        _tabs.value = state.toLuminaTabs()
        val newSelected = state.selectedTabId
        val oldSelected = _selectedTabId.value
        if (newSelected != oldSelected && oldSelected != null &&
            state.tabs.any { it.id == oldSelected }
        ) {
            previousSelectedTabId = oldSelected
        }
        if (newSelected != null) lastAccessById[newSelected] = System.currentTimeMillis()
        _selectedTabId.value = newSelected
        // Drop access timestamps for tabs that no longer exist, so the map can't grow unbounded
        // as tabs are opened and closed over a long session. Only scan when something was removed.
        if (lastAccessById.size > state.tabs.size) {
            val live = state.tabs.mapTo(HashSet()) { it.id }
            lastAccessById.keys.retainAll(live)
        }
        schedulePersist()
    }

    init {
        storeSubscription.resume()
    }

    /**
     * Restore previously-open tabs from disk, dropping any not accessed within the last
     * [maxAgeMillis] (72h auto-discard). Falls back to a single blank tab when there is
     * nothing to restore so the browser always has a foreground tab. No-op if the store
     * already has tabs.
     *
     * Tabs are restored through [TabsUseCases.RestoreUseCase] as [RecoverableTab]s carrying their
     * serialized [EngineSessionState], so each tab comes back with its back/forward history stack,
     * scroll position and form data intact rather than a fresh top-of-page reload. A tab with no
     * captured engine state (e.g. persisted before it was ever rendered, or the engine handle is
     * absent) restores from its URL alone — the previous behaviour. The engine session itself is
     * still created lazily: only the selected tab is rendered on launch; others restore their state
     * the first time they're selected.
     */
    fun restoreOrSeedBlank(maxAgeMillis: Long) {
        if (store.state.tabs.isNotEmpty()) return
        val persisted = persistence?.load(maxAgeMillis).orEmpty()
        if (persisted.isEmpty()) {
            val id = tabsUseCases.addTab.invoke(url = "about:blank", selectTab = true, title = "New Tab")
            lastAccessById[id] = System.currentTimeMillis()
            return
        }
        var selectedId: String? = null
        // Collected while mapping so group membership can be re-linked once the tabs exist in the store.
        val groupAssignments = mutableListOf<Pair<String, String>>()
        val recoverables = persisted.mapIndexed { index, tab ->
            val id = java.util.UUID.randomUUID().toString()
            if (tab.selected) selectedId = id
            lastAccessById[id] = tab.lastAccess
            tab.groupId?.let { groupAssignments += id to it }
            RecoverableTab(
                engineSessionState = tab.engineStateJson?.let(::engineStateFromJson),
                state = TabState(
                    id = id,
                    url = normalizeUrl(tab.url, searchEngine()),
                    title = tab.title,
                    lastAccess = tab.lastAccess,
                    index = index,
                    // Preserve the tab's container (cookie-store) so isolation survives a restart.
                    contextId = tab.contextId,
                ),
            )
        }
        tabsUseCases.restore.invoke(
            recoverables,
            selectTabId = selectedId ?: recoverables.firstOrNull()?.state?.id,
        )
        // Re-link restored tabs to their groups now that they exist in the store.
        groupAssignments.forEach { (tabId, groupId) -> tabGroups?.assignRestored(tabId, groupId) }
    }

    /**
     * Serialize an [EngineSessionState] to a JSON string for [TabPersistence], or null if it can't
     * be written. The inverse of [engineStateFromJson].
     */
    private fun engineStateToJson(state: EngineSessionState): String? = runCatching {
        val writer = java.io.StringWriter()
        android.util.JsonWriter(writer).use { state.writeTo(it) }
        writer.toString().takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()

    /**
     * Rebuild an [EngineSessionState] from a persisted JSON blob via the engine, or null if the
     * engine is absent or the blob can't be parsed (in which case the tab restores URL-only).
     */
    private fun engineStateFromJson(json: String): EngineSessionState? {
        val engine = engine ?: return null
        return runCatching {
            android.util.JsonReader(java.io.StringReader(json)).use(engine::createSessionStateFrom)
        }.getOrNull()
    }

    /**
     * Ask Gecko to write the selected tab's current history/scroll state into the store, so a
     * subsequent [schedulePersist] captures fresh state before the process can be killed. Guarded on
     * a live session — the engine middleware would otherwise recreate one just to flush it. Called
     * on app background; background tabs are already flushed as they leave the foreground by
     * [BackgroundTabSuspender].
     */
    fun flushActiveTabState() {
        val selectedId = store.state.selectedTabId ?: return
        val hasLiveSession = store.state.tabs.any {
            it.id == selectedId && it.engineState.engineSession != null
        }
        if (hasLiveSession) {
            store.dispatch(EngineAction.FlushEngineSessionStateAction(selectedId))
        }
    }

    private fun schedulePersist() {
        val scope = scope ?: return
        val persistence = persistence ?: return
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(500)
            // Snapshot store state + access times on the store/main thread, then serialize and write
            // on IO. EngineSessionState.writeTo can be non-trivial for pages with deep history, so it
            // must not run on the main thread; the immutable BrowserState snapshot is safe to read off it.
            val state = store.state
            val selected = state.selectedTabId
            val now = System.currentTimeMillis()
            val tabs = state.tabs
            val accessById = HashMap(lastAccessById)
            // Snapshot group membership on the store thread; groupIdForTab reads the groups StateFlow.
            val groupIdByTab = tabs.associate { it.id to tabGroups?.groupIdForTab(it.id) }
            withContext(Dispatchers.IO) {
                val snapshot = tabs
                    .filter { it.content.url.isNotBlank() && it.content.url != "about:blank" }
                    .map { tab ->
                        TabPersistence.PersistedTab(
                            url = tab.content.url,
                            title = tab.content.title,
                            lastAccess = accessById[tab.id] ?: now,
                            selected = tab.id == selected,
                            engineStateJson = tab.engineState.engineSessionState?.let(::engineStateToJson),
                            groupId = groupIdByTab[tab.id],
                            contextId = tab.contextId,
                        )
                    }
                persistence.save(snapshot)
            }
        }
    }

    override fun selectTab(id: String) {
        tabsUseCases.selectTab.invoke(id)
    }

    override fun selectAdjacent(delta: Int) {
        val current = store.state.tabs
        val currentId = store.state.selectedTabId ?: return
        val idx = current.indexOfFirst { it.id == currentId }
        if (idx < 0) return
        tabsUseCases.selectTab.invoke(current[(idx + delta).coerceIn(0, current.lastIndex)].id)
    }

    override fun loadUrl(url: String, tabId: String?) {
        val selectedId = tabId ?: store.state.selectedTabId ?: return
        if (url.trim().equals("debug:cache", ignoreCase = true)) {
            val session = store.state.tabs.firstOrNull { it.id == selectedId }?.engineState?.engineSession
            if (session != null) {
                session.extractCacheHtml()
                return
            }
        }
        sessionUseCases.loadUrl.invoke(normalizeUrl(url, searchEngine()), sessionId = selectedId)
    }

    override fun addTab(url: String, title: String): String =
        tabsUseCases.addTab.invoke(url = normalizeUrl(url, searchEngine()), selectTab = true, title = title)

    fun addPreparedTab(
        url: String,
        title: String = "",
        engineSession: EngineSession? = null,
        parentId: String? = store.state.selectedTabId,
        selectTab: Boolean = true,
        // Container (cookie-store) to open the tab in; null = the default context. GeckoView isolates
        // the tab's cookies/storage to this contextId for the life of the tab.
        contextId: String? = null,
    ): String =
        tabsUseCases.addTab.invoke(
            url = normalizeUrl(url, searchEngine()),
            selectTab = selectTab,
            parentId = parentId,
            title = title,
            engineSession = engineSession,
            contextId = contextId,
            source = SessionState.Source.Internal.NewTab,
        )

    /**
     * Open a new tab inside the container identified by [contextId] (a cookie-store id from
     * [ContainerRepository]), selecting it. Its cookies/storage are isolated to that container.
     * Returns the new tab id.
     */
    fun addTabInContainer(url: String, contextId: String, title: String = "New Tab"): String =
        tabsUseCases.addTab.invoke(
            url = normalizeUrl(url, searchEngine()),
            selectTab = true,
            title = title,
            contextId = contextId,
            source = SessionState.Source.Internal.NewTab,
        )

    /**
     * Open a new tab and place it in group [groupId], selecting it. The tab is opened in the group's
     * configured default container ([TabGroupsRepository.defaultContainerFor]) when one is set, so a
     * group like "Work" can transparently open its tabs in the Work container. Returns the new tab id.
     */
    fun addTabInGroup(url: String, groupId: String, title: String = "New Tab"): String {
        val contextId = tabGroups?.defaultContainerFor(groupId)
        val id = tabsUseCases.addTab.invoke(
            url = normalizeUrl(url, searchEngine()),
            selectTab = true,
            title = title,
            contextId = contextId,
            source = SessionState.Source.Internal.NewTab,
        )
        tabGroups?.addTabToGroup(id, groupId)
        return id
    }

    override fun removeTab(id: String) {
        recordClosedTab(id)
        tabsUseCases.removeTab.invoke(id)
    }

    /** Snapshot a tab's URL+title onto the closed-tab stack before it's removed (real pages only). */
    private fun recordClosedTab(id: String) {
        val tab = store.state.tabs.firstOrNull { it.id == id } ?: return
        val url = tab.content.url
        if (url.isBlank() || url == "about:blank" || url == "about:home") return
        closedTabs.addLast(ClosedTab(id = java.util.UUID.randomUUID().toString(), url = url, title = tab.content.title))
        while (closedTabs.size > MAX_CLOSED_TABS) closedTabs.removeFirst()
        publishClosedTabs()
    }

    /**
     * Reopen the most-recently-closed tab (Safari/Arc "Reopen Closed Tab"), selecting it. Returns
     * the new tab id, or null when the stack is empty. Reloads the URL fresh — page session history
     * is not restored.
     */
    override fun reopenLastClosedTab(): String? {
        val closed = closedTabs.removeLastOrNull() ?: return null
        publishClosedTabs()
        return addTab(url = closed.url, title = closed.title)
    }

    /**
     * Reopen a specific closed tab by its [ClosedTabInfo.id] (from the "Recently closed" list),
     * removing it from the stack and selecting the new tab. Returns the new tab id, or null when the
     * entry is no longer present.
     */
    override fun reopenClosedTab(id: String): String? {
        val index = closedTabs.indexOfLast { it.id == id }
        if (index < 0) return null
        val closed = closedTabs.removeAt(index)
        publishClosedTabs()
        return addTab(url = closed.url, title = closed.title)
    }

    /**
     * If the currently-selected tab was opened by another tab (e.g. via `window.open` or a
     * `target=_blank` link) and that parent tab still exists, close this child tab and return to
     * the parent. Returns true if it acted. Used by back-gesture handling so the hardware/gesture
     * Back closes a popped-open tab instead of exiting the browser.
     */
    fun closeChildTabAndReturnToParent(): Boolean {
        val state = store.state
        val currentId = state.selectedTabId ?: return false
        val current = state.tabs.firstOrNull { it.id == currentId } ?: return false
        val parentId = current.parentId?.takeIf { pid ->
            pid != currentId && state.tabs.any { it.id == pid }
        } ?: return false
        tabsUseCases.selectTab.invoke(parentId)
        tabsUseCases.removeTab.invoke(currentId)
        return true
    }

    /**
     * If the current tab is `about:blank` and a previously-selected tab still exists,
     * select that previous tab and remove the blank one. Returns true if it acted.
     * Used by back-gesture handling in `LuminaRoot` and by the Editing-mode cancel path.
     */
    fun dismissBlankTabAndReturn(): Boolean {
        val state = store.state
        val currentId = state.selectedTabId ?: return false
        val current = state.tabs.firstOrNull { it.id == currentId } ?: return false
        if (current.content.url != "about:blank") return false
        val previousId = previousSelectedTabId?.takeIf { id ->
            id != currentId && state.tabs.any { it.id == id }
        } ?: return false
        tabsUseCases.selectTab.invoke(previousId)
        tabsUseCases.removeTab.invoke(currentId)
        previousSelectedTabId = null
        return true
    }
}

/**
 * Resolve a user-typed address bar input to a loadable URL.
 * Empty → `about:blank`; explicit scheme → unchanged; host-like → `https://` prepended;
 * everything else → the [engine]'s search URL.
 */
internal fun normalizeUrl(input: String, engine: SearchEngine = SearchEngine.DEFAULT): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "about:blank"
    if (trimmed.hasExplicitScheme()) return trimmed
    if (trimmed.looksLikeWebsite()) return "https://$trimmed"
    return engine.searchUrl(trimmed)
}

// Compiled once: hasExplicitScheme runs per tab on every BrowserState emission (via
// toLuminaTabs → compactHost → normalizeUrl), not just on user-typed input.
private val explicitSchemeRegex = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

private fun String.hasExplicitScheme(): Boolean =
    !containsWhitespace() && explicitSchemeRegex.containsMatchIn(this)

private fun String.looksLikeWebsite(): Boolean {
    if (containsWhitespace()) return false
    val host = substringBefore('/').substringBefore('?').substringBefore('#')
    return host.equals("localhost", ignoreCase = true) ||
        host.contains('.') ||
        host.contains(':')
}

private fun String.containsWhitespace(): Boolean = any(Char::isWhitespace)

/** Short host label (e.g. `nytimes.com`) used by the compact pill. Strips `www.`. */
internal fun compactHost(url: String): String {
    val host = runCatching { URI(normalizeUrl(url)).host }.getOrNull().orEmpty().removePrefix("www.")
    return host.ifEmpty { url }
}

private fun BrowserState.toLuminaTabs(): List<LuminaTab> =
    tabs.map { tab ->
        LuminaTab(
            id = tab.id,
            url = tab.content.url,
            title = tab.content.title.ifBlank { compactHost(tab.content.url) },
        )
    }
