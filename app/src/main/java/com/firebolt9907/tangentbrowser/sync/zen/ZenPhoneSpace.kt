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

    fun plan(spaceId: String, name: String, tabs: List<ZenPhoneTab>, remote: List<Sync15Record>): List<ZenPhoneWrite> {
        val prefix = prefix(spaceId)
        val existing = remote.associateBy { it.id }
        val current = visibleTabs(tabs).associateBy { tabRecordId(spaceId, it.id) }
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
            write(id, "tab", JSONObject()
                .put("tabId", id).put("url", tab.url).put("title", tab.title)
                .put("icon", icon).put("containerGuid", JSONObject.NULL)
                .put("essential", false).put("pinned", false).put("workspaceUuid", spaceId)
                .put("folderId", JSONObject.NULL).put("staticLabel", JSONObject.NULL)
                .put("hasStaticIcon", false).put("defaultContainer", false))
        }
        val previousSpace = existing[spaceId]?.cleartext?.optJSONObject("data")
        val space = previousSpace?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("uuid", spaceId).put("name", name).put("icon", "📱")
            .put("theme", JSONObject.NULL).put("containerGuid", JSONObject.NULL)
        // Keep desktop-added children and user-chosen space appearance; the phone owns its own tabs.
        val extraChildren = previousSpace?.optJSONArray("children")?.let { children ->
            (0 until children.length()).mapNotNull { children.opt(it) as? String }
                .filterNot { it.startsWith(prefix) }
        }.orEmpty()
        space.put("children", JSONArray(current.keys.toList() + extraChildren))
        write(spaceId, "space", space)
        remote.filter { record ->
            record.id.startsWith(prefix) && record.id !in current &&
                record.cleartext.optString("kind") == "tab" &&
                record.cleartext.optJSONObject("data")?.optString("workspaceUuid") == spaceId
        }.forEach { record ->
            writes += ZenPhoneWrite(record.id, JSONObject().put("id", record.id).put("deleted", true), record.modified)
        }
        return writes
    }
}
