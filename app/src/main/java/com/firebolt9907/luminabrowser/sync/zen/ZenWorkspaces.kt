package com.firebolt9907.luminabrowser.sync.zen

import org.json.JSONObject

/**
 * Models + record parsing for Zen browser's workspace Sync engine
 * (zen-browser/desktop: src/zen/sync/ZenWorkspacesSync.sys.mjs, engine version 2).
 *
 * Server collection: `workspaces`. Record IDs are `s~<space-uuid>` and `c~<userContextId>`, but —
 * like Zen's applyIncomingBatch — dispatch is on the cleartext `type` field, not the ID prefix.
 * The protocol is not in stable Zen yet, so parsing mirrors their source exactly (same skip rules,
 * same ordering) and each space keeps its raw JSON: Zen merges spaces with object spread, so
 * unknown fields must survive for future write support.
 */

const val ZEN_WORKSPACES_COLLECTION = "workspaces"

data class ZenSpace(
    val uuid: String,
    val name: String,
    val icon: String?,
    val containerTabId: Int,
    /** Sidebar position from the record; null when the writer omitted it. */
    val position: Int?,
    /** Full cleartext minus the `id`/`type` sync envelope (Zen's stripSyncFields). */
    val raw: JSONObject,
)

data class ZenContainer(
    val userContextId: Int,
    val name: String,
    val icon: String?,
    val color: String?,
)

data class ZenWorkspacesSnapshot(
    val spaces: List<ZenSpace>,
    val containers: List<ZenContainer>,
) {
    companion object {
        val EMPTY = ZenWorkspacesSnapshot(emptyList(), emptyList())
    }
}

object ZenWorkspaceRecords {
    fun spaceRecordId(uuid: String): String = "s~$uuid"
    fun containerRecordId(userContextId: Int): String = "c~$userContextId"

    fun parse(records: List<Sync15Record>): ZenWorkspacesSnapshot {
        val spaces = mutableListOf<ZenSpace>()
        val containers = mutableListOf<ZenContainer>()
        for (record in records) {
            val data = record.cleartext
            when (data.optString("type")) {
                "space" -> parseSpace(data)?.let(spaces::add)
                "container" -> parseContainer(data)?.let(containers::add)
                // Unknown/absent type: ignored, exactly like Zen's applyIncomingBatch.
            }
        }
        return ZenWorkspacesSnapshot(
            spaces = orderSpacesByPosition(spaces),
            containers = containers,
        )
    }

    private fun parseSpace(data: JSONObject): ZenSpace? {
        // Zen skips spaces without a truthy uuid (`if (!space?.uuid) continue`).
        val uuid = data.optString("uuid")
        if (uuid.isEmpty()) return null
        // `position` is only honored when it is a number (`typeof position === "number"`).
        val position = (data.opt("position") as? Number)?.toInt()
        return ZenSpace(
            uuid = uuid,
            name = data.optString("name"),
            icon = data.optString("icon").ifEmpty { null },
            containerTabId = data.optInt("containerTabId", 0),
            position = position,
            raw = stripSyncFields(data),
        )
    }

    private fun parseContainer(data: JSONObject): ZenContainer? {
        // Zen skips containers without a truthy name, and normalizeUserContextId rejects
        // anything that isn't a positive safe integer.
        val name = data.optString("name")
        if (name.isEmpty()) return null
        val userContextId = (data.opt("userContextId") as? Number)?.toLong()
            ?: data.optString("userContextId").toLongOrNull()
            ?: return null
        if (userContextId <= 0 || userContextId > Int.MAX_VALUE) return null
        return ZenContainer(
            userContextId = userContextId.toInt(),
            name = name,
            icon = data.optString("icon").ifEmpty { null },
            color = data.optString("color").ifEmpty { null },
        )
    }

    /**
     * Zen's #getOrderedWorkspacesByPosition: sort key is the record's `position` when present,
     * otherwise the item's incoming index; ties keep incoming order.
     */
    private fun orderSpacesByPosition(spaces: List<ZenSpace>): List<ZenSpace> =
        spaces.withIndex()
            .sortedWith(
                compareBy({ (index, space) -> space.position ?: index }, { (index, _) -> index }),
            )
            .map { it.value }

    private fun stripSyncFields(data: JSONObject): JSONObject {
        val copy = JSONObject(data.toString())
        copy.remove("id")
        copy.remove("type")
        return copy
    }
}
