package com.firebolt9907.tangentbrowser.state

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.browser.state.store.BrowserStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the set of [TangentTabGroup]s: an organizational label over open tabs, independent of the
 * engine (grouping is not cookie isolation — see [ContainerRepository]).
 *
 * Two-part persistence, mirroring how the app already splits tab state:
 *  - The **registry** (each group's id / name / colour) is persisted here to `tangent_tab_groups.json`.
 *  - **Membership** (which tab is in which group) is persisted per-tab by [TabPersistence]/
 *    [GeckoTabRepository] as `PersistedTab.groupId`, because live tab ids are reassigned on restore.
 *    On restore, [GeckoTabRepository] re-links each tab via [assignRestored].
 *
 * A tab belongs to at most one group. The repository observes the [BrowserStore] so that when a tab
 * is closed it drops out of its group automatically (no ghost membership). Empty groups are kept —
 * they are user-managed and removed only via [deleteGroup] — so a group survives all its tabs being
 * closed and can be repopulated.
 */
class TabGroupsRepository(
    private val store: BrowserStore,
    context: Context,
) {

    private val file = File(context.filesDir, "tangent_tab_groups.json")

    private val _groups = MutableStateFlow<List<TangentTabGroup>>(loadRegistry())
    val groups: StateFlow<List<TangentTabGroup>> = _groups.asStateFlow()

    // Prune membership when tabs close so a group never references a dead tab id. Registry (name/
    // colour/existence) is untouched — an emptied group persists for the user to reuse or delete.
    private val storeSubscription = store.observeManually { state ->
        val live = state.tabs.mapTo(HashSet()) { it.id }
        val current = _groups.value
        if (current.any { g -> g.tabIds.any { it !in live } }) {
            _groups.value = current.map { g -> g.copy(tabIds = g.tabIds.filter { it in live }) }
        }
    }

    init {
        storeSubscription.resume()
    }

    /** Create a new (empty) group; returns its id. */
    fun createGroup(name: String, color: TabGroupColor = TabGroupColor.BLUE): String {
        val id = UUID.randomUUID().toString()
        _groups.value = _groups.value + TangentTabGroup(id = id, name = name.trim(), color = color)
        saveRegistry()
        return id
    }

    fun renameGroup(id: String, name: String) {
        mutateGroup(id) { it.copy(name = name.trim()) }
        saveRegistry()
    }

    fun setColor(id: String, color: TabGroupColor) {
        mutateGroup(id) { it.copy(color = color) }
        saveRegistry()
    }

    /** Set (or clear, with null) the container new tabs opened into this group default to. */
    fun setDefaultContainer(id: String, contextId: String?) {
        mutateGroup(id) { it.copy(defaultContextId = contextId) }
        saveRegistry()
    }

    /** The default container (contextId) for [groupId], or null. */
    fun defaultContainerFor(groupId: String?): String? =
        groupId?.let { id -> _groups.value.firstOrNull { it.id == id }?.defaultContextId }

    /** Delete a group; its member tabs simply become ungrouped (the tabs themselves are untouched). */
    fun deleteGroup(id: String) {
        _groups.value = _groups.value.filterNot { it.id == id }
        saveRegistry()
    }

    /**
     * Put [tabId] into group [groupId], removing it from any other group first (a tab is in at most
     * one group). No-op if the group doesn't exist.
     */
    fun addTabToGroup(tabId: String, groupId: String) {
        if (_groups.value.none { it.id == groupId }) return
        _groups.value = _groups.value.map { g ->
            when {
                g.id == groupId -> if (tabId in g.tabIds) g else g.copy(tabIds = g.tabIds + tabId)
                tabId in g.tabIds -> g.copy(tabIds = g.tabIds - tabId)
                else -> g
            }
        }
        // Membership isn't part of the registry file (it lives per-tab in TabPersistence); nothing to save.
    }

    /** Remove [tabId] from whatever group it's in (if any). */
    fun removeTabFromGroup(tabId: String) {
        if (_groups.value.none { tabId in it.tabIds }) return
        _groups.value = _groups.value.map { g ->
            if (tabId in g.tabIds) g.copy(tabIds = g.tabIds - tabId) else g
        }
    }

    /** The id of the group [tabId] belongs to, or null. Read at persist time by [GeckoTabRepository]. */
    fun groupIdForTab(tabId: String): String? =
        _groups.value.firstOrNull { tabId in it.tabIds }?.id

    /**
     * Re-link a restored tab to its persisted group. Only attaches when the group still exists in the
     * registry; unlike [addTabToGroup] this is used during startup restore where the group came from
     * the persisted registry. No-op for a null/blank group id or a group that was deleted.
     */
    fun assignRestored(tabId: String, groupId: String?) {
        if (groupId.isNullOrBlank()) return
        addTabToGroup(tabId, groupId)
    }

    private inline fun mutateGroup(id: String, transform: (TangentTabGroup) -> TangentTabGroup) {
        _groups.value = _groups.value.map { if (it.id == id) transform(it) else it }
    }

    private fun loadRegistry(): List<TangentTabGroup> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TangentTabGroup(
                    id = id,
                    name = obj.optString("name"),
                    color = TabGroupColor.fromName(obj.optString("color")),
                    defaultContextId = obj.optString("defaultContextId").takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun saveRegistry() {
        val snapshot = _groups.value
        runCatching {
            val array = JSONArray()
            snapshot.forEach { g ->
                array.put(
                    JSONObject()
                        .put("id", g.id)
                        .put("name", g.name)
                        .put("color", g.color.name)
                        .apply { g.defaultContextId?.let { put("defaultContextId", it) } },
                )
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
        }
    }
}
