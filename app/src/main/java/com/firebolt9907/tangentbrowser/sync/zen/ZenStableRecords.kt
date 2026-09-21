package com.firebolt9907.tangentbrowser.sync.zen

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Zen 1.22.1b / Spaces engine v3 reader, based on upstream ZenSpacesSyncModel.sys.mjs.
 * Folders and splits are flattened in sidebar order, with folder labels retained for display.
 * Never uploads records: opening a remote pin creates an independent local tab.
 */
internal object ZenStableRecords {
    private data class Record(val kind: String, val data: JSONObject)

    fun parse(records: List<Sync15Record>): ZenWorkspacesSnapshot {
        // Last server version wins, including tombstones. Ignore legacy v2 records once v3 exists.
        val nodes = linkedMapOf<String, Record>()
        records.sortedBy { it.modified }.forEach { record ->
            val cleartext = record.cleartext
            if (cleartext.optBoolean("deleted")) {
                nodes.remove(record.id)
            } else {
                val data = cleartext.optJSONObject("data") ?: return@forEach
                val kind = cleartext.string("kind") ?: return@forEach
                nodes[record.id] = Record(kind, data)
            }
        }
        val layout = nodes.values.firstOrNull { it.kind == "layout" }?.data
        val containers = nodes.values.filter { it.kind == "container" }.mapNotNull { node ->
            val guid = node.data.string("guid") ?: return@mapNotNull null
            val name = node.data.string("name") ?: return@mapNotNull null
            ZenContainer(0, name, node.data.string("icon"), node.data.string("color"), guid)
        }
        val spaces = nodes.values.filter { it.kind == "space" }.mapNotNull { node ->
            val data = node.data
            val uuid = data.string("uuid") ?: return@mapNotNull null
            val containerGuid = data.string("containerGuid")
            val tabs = mutableListOf<ZenSyncedTab>()
            val visited = mutableSetOf<String>()

            // Iterative traversal tolerates missing references and cyclic/deep folder graphs.
            fun append(ids: List<String>) {
                val pending = java.util.ArrayDeque<String>()
                ids.asReversed().forEach(pending::addLast)
                while (pending.isNotEmpty()) {
                    val id = pending.removeLast()
                    if (!visited.add(id)) continue
                    val child = nodes[id] ?: continue
                    val item = child.data
                    val essential = child.kind == "tab" && item.optBoolean("essential")
                    if (!essential && item.string("workspaceUuid") != uuid) continue
                    when (child.kind) {
                        "folder", "split" -> {
                            val key = if (child.kind == "split") "tabs" else "children"
                            item.optJSONArray(key).strings().asReversed().forEach(pending::addLast)
                        }
                        "tab" -> {
                            val url = item.string("url") ?: continue
                            // Desktop-internal or executable URLs cannot be opened as mobile pages.
                            if (!isWebUrl(url)) continue
                            val folderId = item.string("folderId")
                            tabs += ZenSyncedTab(
                                title = item.string("staticLabel") ?: item.string("title") ?: url,
                                url = url,
                                workspaceUuid = item.string("workspaceUuid"),
                                groupId = folderId,
                                essential = essential,
                                // 1.22b omitted pinned because every synced tab was a pin.
                                pinned = essential || item.optBoolean("pinned", true),
                                position = tabs.size,
                                icon = item.string("icon"),
                                folderName = nodes[folderId]?.takeIf { it.kind == "folder" }
                                    ?.data?.string("name"),
                            )
                        }
                    }
                }
            }
            // The desktop container-specific visibility preference is not in this collection.
            // Show every Essential in this read-only view, keeping each container's layout order.
            val essentials = layout?.optJSONObject("essentials")
            essentials?.keys()?.forEach { key -> append(essentials.optJSONArray(key).strings()) }
            // Missing layout/child references during concurrent desktop updates must not hide pins.
            append(nodes.filterValues {
                it.kind == "tab" && it.data.optBoolean("essential")
            }.keys.toList())
            append(data.optJSONArray("children").strings())
            append(nodes.filterValues { it.kind == "tab" && !it.data.optBoolean("essential") }.keys.toList())
            ZenSpace(
                uuid, data.string("name").orEmpty(), data.string("icon"), 0, null,
                JSONObject(data.toString()), tabs, containerGuid,
            )
        }
        val order = layout?.optJSONArray("spaces").strings().withIndex().associate { it.value to it.index }
        return ZenWorkspacesSnapshot(spaces.sortedBy { order[it.uuid] ?: Int.MAX_VALUE }, containers)
    }

    private fun JSONObject.string(key: String): String? =
        (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
        (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf(String::isNotBlank) }

    private fun isWebUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
