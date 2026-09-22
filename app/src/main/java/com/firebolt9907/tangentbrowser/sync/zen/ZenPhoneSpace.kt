package com.firebolt9907.tangentbrowser.sync.zen

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** A normal phone tab, before projecting onto Zen's portable record schema. */
internal data class ZenPhoneTab(
    val id: String,
    val url: String,
    val title: String,
    val private: Boolean = false,
    val icon: String? = null,
    /** Local change detection only; never sent to Zen. */
    val iconRevision: Int? = null,
)

internal data class ZenPhoneWrite(val id: String, val cleartext: JSONObject, val expectedModified: Double)

/** Only this installation's space/tab records are writable. Never writes shared layout or metadata. */
internal object ZenPhoneSpace {
    fun visibleTabs(tabs: List<ZenPhoneTab>): List<ZenPhoneTab> = tabs.filter { tab ->
        !tab.private && runCatching {
            val uri = URI(tab.url)
            uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun prefix(spaceId: String) = "tg-${UUID.fromString(spaceId)}-"
    fun tabRecordId(spaceId: String, tabId: String): String = prefix(spaceId) +
        UUID.nameUUIDFromBytes(tabId.toByteArray(Charsets.UTF_8)).toString().replace("-", "").take(20)

    internal fun resolvedRecordId(spaceId: String, localId: String, linked: String?, existing: Map<String, Sync15Record>): String {
        val data = linked?.let { existing[it]?.cleartext?.optJSONObject("data") }
        // A desktop move out of the dedicated space ends ownership of the original record.
        return if (linked != null && (data == null || data.optString("workspaceUuid") == spaceId)) linked
        else tabRecordId(spaceId, localId)
    }

    // Android's org.json lacks JSONObject.similar; compare objects with stable key order.
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }

    fun plan(spaceId: String, name: String, tabs: List<ZenPhoneTab>, remote: List<Sync15Record>, links: List<ZenPhoneLink> = emptyList()): List<ZenPhoneWrite> {
        val prefix = prefix(spaceId)
        val existing = remote.sortedBy { it.modified }.associateBy { it.id }
        val linkedIds = links.associate { it.localId to it.recordId }
        val managedIds = links.mapTo(mutableSetOf()) { it.recordId }
        // Tangent stores oldest-first; Zen displays the first child at the top.
        val current = visibleTabs(tabs).asReversed().associateBy { tab ->
            resolvedRecordId(spaceId, tab.id, linkedIds[tab.id], existing)
        }
        val writes = mutableListOf<ZenPhoneWrite>()
        fun write(id: String, kind: String, data: JSONObject) {
            val cleartext = JSONObject().put("id", id).put("kind", kind).put("data", data)
            val previous = existing[id]
            if (previous == null || canonical(previous.cleartext) != canonical(cleartext)) {
                writes += ZenPhoneWrite(id, cleartext, previous?.modified ?: 0.0)
            }
        }
        current.forEach { (id, tab) ->
            val previous = existing[id]?.cleartext?.optJSONObject("data")
            // A restored/unloaded phone tab may not have an icon yet. Keep the desktop copy only
            // for the exact same page; navigation must not carry another site's favicon forward.
            val icon = tab.icon ?: previous?.takeIf { it.optString("url") == tab.url }
                ?.optString("icon").orEmpty()
            // Imported desktop tabs retain pin/folder/container metadata and their record identity.
            val data = if (!id.startsWith(prefix) && previous != null) JSONObject(previous.toString()) else JSONObject()
                .put("tabId", id).put("containerGuid", JSONObject.NULL)
                .put("essential", false).put("pinned", false).put("workspaceUuid", spaceId)
                .put("folderId", JSONObject.NULL).put("staticLabel", JSONObject.NULL)
                .put("hasStaticIcon", false).put("defaultContainer", false)
            write(id, "tab", data
                .put("tabId", id).put("url", tab.url).put("title", tab.title)
                .put("icon", icon))
        }
        val previousSpace = existing[spaceId]?.cleartext?.optJSONObject("data")
        val space = previousSpace?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("uuid", spaceId).put("name", name).put("icon", "📱")
            .put("theme", JSONObject.NULL).put("containerGuid", JSONObject.NULL)
        // Linked desktop tabs at the space root participate in phone recency order too. Tabs in
        // folders keep their desktop placement; unrelated desktop children retain their order.
        val rootTabs = current.keys.filter { id ->
            val folder = existing[id]?.cleartext?.optJSONObject("data")?.opt("folderId") as? String
            id.startsWith(prefix) || folder.isNullOrBlank()
        }
        val extraChildren = previousSpace?.optJSONArray("children")?.let { children ->
            (0 until children.length()).mapNotNull { children.opt(it) as? String }
                .filterNot { it.startsWith(prefix) || it in rootTabs || (it in managedIds && it !in current) }
        }.orEmpty()
        space.put("children", JSONArray((rootTabs + extraChildren).distinct()))
        write(spaceId, "space", space)
        existing.values.filter { record ->
            (record.id.startsWith(prefix) || record.id in managedIds) && record.id !in current &&
                record.cleartext.optString("kind") == "tab" &&
                record.cleartext.optJSONObject("data")?.optString("workspaceUuid") == spaceId
        }.forEach { record ->
            writes += ZenPhoneWrite(record.id, JSONObject().put("id", record.id).put("deleted", true), record.modified)
        }
        return writes
    }
}
