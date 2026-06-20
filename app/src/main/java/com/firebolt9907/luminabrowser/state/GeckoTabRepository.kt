package com.firebolt9907.luminabrowser.state

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases

/**
 * Production [TabRepository] backed by mozilla-components' [BrowserStore]. Also tracks
 * the previous selected-tab id so [dismissBlankTabAndReturn] can return there.
 */
class GeckoTabRepository(
    private val store: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val tabsUseCases: TabsUseCases,
    private val persistence: TabPersistence? = null,
    private val scope: CoroutineScope? = null,
) : TabRepository {

    private val _tabs = MutableStateFlow(store.state.toLuminaTabs())
    override val tabs: StateFlow<List<LuminaTab>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow(store.state.selectedTabId)
    override val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()

    private var previousSelectedTabId: String? = null

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
        persisted.forEach { tab ->
            val id = tabsUseCases.addTab.invoke(
                url = normalizeUrl(tab.url),
                selectTab = false,
                title = tab.title,
            )
            lastAccessById[id] = tab.lastAccess
            if (tab.selected) selectedId = id
        }
        (selectedId ?: store.state.tabs.firstOrNull()?.id)?.let(tabsUseCases.selectTab::invoke)
    }

    private fun schedulePersist() {
        val scope = scope ?: return
        val persistence = persistence ?: return
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(500)
            val selected = store.state.selectedTabId
            val now = System.currentTimeMillis()
            val snapshot = store.state.tabs
                .filter { it.content.url.isNotBlank() && it.content.url != "about:blank" }
                .map { tab ->
                    TabPersistence.PersistedTab(
                        url = tab.content.url,
                        title = tab.content.title,
                        lastAccess = lastAccessById[tab.id] ?: now,
                        selected = tab.id == selected,
                    )
                }
            persistence.save(snapshot)
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
        sessionUseCases.loadUrl.invoke(normalizeUrl(url), sessionId = selectedId)
    }

    override fun addTab(url: String, title: String): String =
        tabsUseCases.addTab.invoke(url = normalizeUrl(url), selectTab = true, title = title)

    fun addPreparedTab(
        url: String,
        title: String = "",
        engineSession: EngineSession? = null,
        parentId: String? = store.state.selectedTabId,
        selectTab: Boolean = true,
    ): String =
        tabsUseCases.addTab.invoke(
            url = normalizeUrl(url),
            selectTab = selectTab,
            parentId = parentId,
            title = title,
            engineSession = engineSession,
            source = SessionState.Source.Internal.NewTab,
        )

    override fun removeTab(id: String) {
        tabsUseCases.removeTab.invoke(id)
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
 * everything else → Google search URL.
 */
internal fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "about:blank"
    if (trimmed.hasExplicitScheme()) return trimmed
    if (trimmed.looksLikeWebsite()) return "https://$trimmed"
    val encodedQuery = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString())
    return "https://www.google.com/search?q=$encodedQuery"
}

private fun String.hasExplicitScheme(): Boolean =
    !containsWhitespace() && Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(this)

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
