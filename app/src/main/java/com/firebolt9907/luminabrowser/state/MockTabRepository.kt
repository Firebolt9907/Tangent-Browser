package com.firebolt9907.luminabrowser.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class MockTabRepository : TabRepository {

    private val _tabs = MutableStateFlow(
        listOf(
            LuminaTab(id = UUID.randomUUID().toString(), url = "apple.com", title = "Apple"),
            LuminaTab(id = UUID.randomUUID().toString(), url = "nytimes.com", title = "The New York Times"),
            LuminaTab(id = UUID.randomUUID().toString(), url = "wikipedia.org", title = "Wikipedia"),
        )
    )
    override val tabs: StateFlow<List<LuminaTab>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow(_tabs.value.firstOrNull()?.id)
    override val selectedTabId: StateFlow<String?> = _selectedTabId.asStateFlow()

    override fun selectTab(id: String) {
        if (_tabs.value.any { it.id == id }) _selectedTabId.value = id
    }

    override fun selectAdjacent(delta: Int) {
        val current = _tabs.value
        val currentId = _selectedTabId.value ?: return
        val idx = current.indexOfFirst { it.id == currentId }
        if (idx < 0) return
        _selectedTabId.value = current[(idx + delta).coerceIn(0, current.lastIndex)].id
    }

    override fun loadUrl(url: String, tabId: String?) {
        val id = tabId ?: _selectedTabId.value ?: return
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == id) tab.copy(url = url, title = url) else tab
        }
    }

    override fun addTab(url: String, title: String): String {
        val tab = LuminaTab(id = UUID.randomUUID().toString(), url = url, title = title)
        _tabs.value = _tabs.value + tab
        if (_selectedTabId.value == null) _selectedTabId.value = tab.id
        return tab.id
    }

    override fun removeTab(id: String) {
        val current = _tabs.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        val updated = current.toMutableList().apply { removeAt(idx) }
        _tabs.value = updated
        if (_selectedTabId.value == id) {
            _selectedTabId.value = (updated.getOrNull(idx) ?: updated.lastOrNull())?.id
        }
    }

    companion object {
        val instance: MockTabRepository by lazy { MockTabRepository() }
    }
}
