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

    private val mode = MutableStateFlow(BarMode.Expanded)
    private val editingText = MutableStateFlow("")

    val state: StateFlow<BarUiState> =
        combine(repo.tabs, repo.selectedTabId, mode, editingText) { tabs, selectedId, m, edit ->
            val selected = tabs.firstOrNull { it.id == selectedId } ?: tabs.firstOrNull()
            val pageUrl = selected?.url.orEmpty()
            val host = pageUrl.takeIf { it.isNotEmpty() }?.let(::compactHost).orEmpty()
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
            started = SharingStarted.Eagerly,
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

    fun onSwitchTab(direction: Int) {
        repo.selectAdjacent(direction)
    }

    fun dismissBlankTabAndReturn(): Boolean =
        (repo as? GeckoTabRepository)?.dismissBlankTabAndReturn() ?: false

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
