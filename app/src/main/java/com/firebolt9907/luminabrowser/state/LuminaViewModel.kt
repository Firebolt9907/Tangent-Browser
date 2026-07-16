package com.firebolt9907.luminabrowser.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firebolt9907.luminabrowser.LuminaApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import mozilla.components.concept.engine.EngineSession

/**
 * Bottom-bar ViewModel. Combines the [TabRepository] flows with internal bar `mode`
 * and `editingText` into a single [BarUiState]. Note: `LuminaRoot` consumes the full
 * `BrowserState` directly — this ViewModel powers the SafariBar only.
 */
class LuminaViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val repo: TabRepository =
        (application as LuminaApplication).components.tabRepository

    private val prefs: GlobalPreferencesStore =
        (application as LuminaApplication).components.globalPreferencesStore

    private val mode = MutableStateFlow(BarMode.Expanded)
    private val editingText = MutableStateFlow("")

    // Search engine + "show search terms in bar" pref, snapshotted into a flow so a Settings toggle
    // (which mutates SharedPreferences, not a flow) can force `state` to re-emit with the new label
    // via refreshDisplayPrefs(). Without this, the active pill wouldn't update until the next tab event.
    private val displayPrefs =
        MutableStateFlow(prefs.searchEngine to prefs.showSearchTermsInBar)

    fun refreshDisplayPrefs() {
        displayPrefs.value = prefs.searchEngine to prefs.showSearchTermsInBar
    }

    val state: StateFlow<BarUiState> =
        combine(repo.tabs, repo.selectedTabId, mode, editingText, displayPrefs) {
                tabs, selectedId, m, edit, (engine, showTerms) ->
            val selected = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()
            val pageUrl = selected?.url.orEmpty()
            val host = pageUrl.takeIf { it.isNotEmpty() }
                ?.let { addressBarLabel(it, engine, showTerms) }
                .orEmpty()
            BarUiState(
                displayUrl = host,
                tabCount = tabs.size,
                mode = m,
                editingText = if (m == BarMode.Editing) edit else "",
                compactDisplayUrl = host,
                pageUrl = pageUrl,
            )
        }.stateIn(
            scope = viewModelScope,
            // WhileSubscribed (not Eagerly) so this combine — which re-derives BarUiState, incl.
            // compactHost string work, on every BrowserStore emission via repo.tabs — stops running
            // when nothing is collecting (app backgrounded / bar off-screen). The 5s grace keeps it
            // warm across config changes and brief backgrounding. Interaction handlers read
            // state.value only while the bar is on-screen (hence actively subscribed and fresh).
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BarUiState("", 0, BarMode.Expanded, ""),
        )

    fun onUrlTap() {
        editingText.value = state.value.pageUrl
        mode.value = BarMode.Editing
    }

    fun onEditingTextChange(text: String) {
        editingText.value = text
    }

    fun onEditingSubmit() {
        repo.loadUrl(state.value.editingText)
        mode.value = BarMode.Expanded
    }

    fun onEditingCancel() {
        editingText.value = ""
        mode.value = BarMode.Expanded
    }

    /**
     * Loads an address-bar suggestion (a history/bookmark URL or a raw search query) into the
     * selected tab and leaves editing mode. [repo.loadUrl] runs the same `normalizeUrl` path as
     * a manual submit, so a full URL loads directly and bare text becomes a search.
     */
    fun onSubmitText(text: String) {
        repo.loadUrl(text)
        editingText.value = ""
        mode.value = BarMode.Expanded
    }

    fun onSwitchTab(direction: Int) {
        repo.selectAdjacent(direction)
    }

    fun dismissBlankTabAndReturn(): Boolean =
        (repo as? GeckoTabRepository)?.dismissBlankTabAndReturn() ?: false

    fun closeChildTabAndReturnToParent(): Boolean =
        (repo as? GeckoTabRepository)?.closeChildTabAndReturnToParent() ?: false

    fun onCreateTabFromEdge(selectTab: Boolean = true): String {
        val id = repo.addTab(url = "about:blank", title = "New Tab")
        if (selectTab) {
            repo.selectTab(id)
            editingText.value = ""
            mode.value = BarMode.Editing
        }
        return id
    }

    fun selectCreatedTab(id: String, editBlankTab: Boolean) {
        repo.selectTab(id)
        editingText.value = ""
        mode.value = if (editBlankTab) BarMode.Editing else BarMode.Expanded
    }

    fun onOpenNewTab(
        url: String,
        title: String = "",
        engineSession: EngineSession? = null,
        editBlankTab: Boolean = false,
        selectTab: Boolean = true,
    ): String {
        val id = (repo as? GeckoTabRepository)?.addPreparedTab(
            url = url,
            title = title,
            engineSession = engineSession,
            selectTab = selectTab,
        ) ?: repo.addTab(url = url, title = title)
        if (selectTab) repo.selectTab(id)
        if (selectTab && editBlankTab) {
            editingText.value = ""
            mode.value = BarMode.Editing
        } else if (selectTab) {
            editingText.value = ""
            mode.value = BarMode.Expanded
        }
        return id
    }
}
