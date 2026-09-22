package com.firebolt9907.tangentbrowser.sync.zen

import org.json.JSONArray
import org.json.JSONObject

/** Last agreed identity and URL, persisted per account and installation space. URLs are not keys. */
internal data class ZenPhoneLink(val localId: String, val recordId: String, val url: String)
internal data class ZenPhoneImport(val recordId: String, val url: String, val title: String)
internal data class ZenPhoneInbound(val closeIds: List<String>, val additions: List<ZenPhoneImport>)

internal object ZenPhoneMerge {
    fun inbound(spaceId: String, local: List<ZenPhoneTab>, remote: List<Sync15Record>, links: List<ZenPhoneLink>): ZenPhoneInbound {
        val records = remote.sortedBy { it.modified }.associateBy { it.id }
        val known = links.associateBy { it.recordId }
        val close = local.filter { tab ->
            val link = links.firstOrNull { it.localId == tab.id }
            val id = link?.recordId ?: ZenPhoneSpace.tabRecordId(spaceId, tab.id)
            val record = records[id]
            // Only an explicit tombstone is deletion evidence. Missing records/space children can
            // be a partial desktop publication or a reset collection, never an instruction to close.
            record?.cleartext?.optBoolean("deleted") == true && (link == null || link.url == tab.url)
        }.map { it.id }
        val additions = records.values.mapNotNull { record ->
            val data = record.cleartext.optJSONObject("data") ?: return@mapNotNull null
            if (record.cleartext.optBoolean("deleted") || record.cleartext.optString("kind") != "tab" ||
                data.optString("workspaceUuid") != spaceId || data.optBoolean("essential") ||
                data.optBoolean("private") || data.optBoolean("isPrivate")) return@mapNotNull null
            // A known missing local tab was closed on this phone; don't resurrect it before upload.
            if (record.id in known || local.any { ZenPhoneSpace.tabRecordId(spaceId, it.id) == record.id }) return@mapNotNull null
            // Unmatched phone-owned records are local closes from the pre-bidirectional version.
            if (record.id.startsWith("tg-$spaceId-")) return@mapNotNull null
            val candidate = ZenPhoneTab(record.id, data.optString("url"), data.optString("title"))
            if (ZenPhoneSpace.visibleTabs(listOf(candidate)).isEmpty()) return@mapNotNull null
            ZenPhoneImport(record.id, candidate.url, candidate.title)
        }
        return ZenPhoneInbound(close, additions)
    }

    fun encode(links: List<ZenPhoneLink>): String = JSONArray(links.map {
        JSONObject().put("localId", it.localId).put("recordId", it.recordId).put("url", it.url)
    }).toString()

    fun decode(json: String): List<ZenPhoneLink> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            ZenPhoneLink(value.getString("localId"), value.getString("recordId"), value.getString("url"))
        }
    }
}
