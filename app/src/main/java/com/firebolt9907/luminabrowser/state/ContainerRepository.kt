package com.firebolt9907.luminabrowser.state

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.browser.state.action.ContainerAction
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.store.BrowserStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the set of **containers** (Firefox "contextual identities"): each container is an isolated
 * cookie/storage jar identified by a `contextId`. A tab opened in a container carries that
 * `contextId` ([mozilla.components.browser.state.state.TabSessionState.contextId]); GeckoView keeps
 * its cookies, localStorage, cache and logins fully separate from other containers and the default
 * context. This is real isolation — distinct from an organizational [LuminaTabGroup], which is only
 * a label.
 *
 * The canonical runtime state lives in `store.state.containers` (a `Map<contextId, ContainerState>`),
 * driven by [ContainerAction]. Because that map isn't part of the app's own tab persistence, this
 * repository also persists the container **registry** to `lumina_containers.json` and re-registers
 * it into the store on startup, so containers survive a process restart alongside the tabs that
 * reference them (persisted `contextId` — see [TabPersistence]).
 */
class ContainerRepository(
    private val store: BrowserStore,
    context: Context,
) {

    private val file = File(context.filesDir, "lumina_containers.json")

    private val _containers = MutableStateFlow<List<ContainerState>>(emptyList())
    /** All containers, in creation order. Mirrors `store.state.containers`. */
    val containers: StateFlow<List<ContainerState>> = _containers.asStateFlow()

    // `store.state.containers` is the source of truth; this keeps our StateFlow mirror in sync.
    // (Store.dispatch applies its reducer synchronously and notifies observers inline, so after a
    // dispatch below the store — and this mirror — already reflect the change.)
    private val storeSubscription = store.observeManually { state ->
        val fromStore = state.containers.values.toList()
        if (fromStore != _containers.value) _containers.value = fromStore
    }

    init {
        // Restore persisted containers into the store, then resume: resume() delivers the current
        // state immediately, so the mirror picks them up. Restored tabs' contextIds now resolve to a
        // known container from the first frame.
        val restored = loadRegistry()
        if (restored.isNotEmpty()) {
            store.dispatch(ContainerAction.AddContainersAction(restored))
        }
        storeSubscription.resume()
    }

    /**
     * Create a new container with a fresh cookie-store id and register it. Returns the new
     * `contextId`, which callers pass to `addTab(contextId = …)` to open tabs inside it.
     */
    fun createContainer(
        name: String,
        color: ContainerState.Color,
        icon: ContainerState.Icon = ContainerState.Icon.CIRCLE,
    ): String {
        val contextId = UUID.randomUUID().toString()
        store.dispatch(
            ContainerAction.AddContainerAction(
                ContainerState(contextId = contextId, name = name.trim(), color = color, icon = icon),
            ),
        )
        saveRegistry()
        return contextId
    }

    /**
     * Remove a container from the registry/store. Existing tabs keep their `contextId` and their
     * already-isolated data; they simply no longer resolve to a named container in the UI. (We don't
     * force-close a container's tabs here — that's a UI decision left to the caller.)
     */
    fun removeContainer(contextId: String) {
        store.dispatch(ContainerAction.RemoveContainerAction(contextId))
        saveRegistry()
    }

    fun containerFor(contextId: String?): ContainerState? =
        contextId?.let { id -> _containers.value.firstOrNull { it.contextId == id } }

    private fun loadRegistry(): List<ContainerState> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val contextId = obj.optString("contextId").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ContainerState(
                    contextId = contextId,
                    name = obj.optString("name"),
                    color = colorFromName(obj.optString("color")),
                    icon = iconFromName(obj.optString("icon")),
                )
            }
        }.getOrElse { emptyList() }
    }

    // Saves the current store containers (registry is a straight mirror of the store map). Called
    // after add/remove — the store dispatch above has already updated the map synchronously.
    private fun saveRegistry() {
        val snapshot = store.state.containers.values.toList()
        runCatching {
            val array = JSONArray()
            snapshot.forEach { c ->
                array.put(
                    JSONObject()
                        .put("contextId", c.contextId)
                        .put("name", c.name)
                        .put("color", c.color.name)
                        .put("icon", c.icon.name),
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

    private companion object {
        fun colorFromName(name: String?): ContainerState.Color =
            ContainerState.Color.entries.firstOrNull { it.name == name } ?: ContainerState.Color.BLUE

        fun iconFromName(name: String?): ContainerState.Icon =
            ContainerState.Icon.entries.firstOrNull { it.name == name } ?: ContainerState.Icon.CIRCLE
    }
}
