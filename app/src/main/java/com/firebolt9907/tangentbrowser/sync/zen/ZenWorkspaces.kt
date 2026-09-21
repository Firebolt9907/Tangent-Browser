package com.firebolt9907.tangentbrowser.sync.zen

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only Zen Spaces Sync projection. Stable Zen 1.22b uses engine version 3:
 * `spaces` records contain {id, kind, data}. See ZenSpacesSyncModel.sys.mjs upstream.
 * Legacy flat `type` records remain readable for accounts last synced by Twilight.
 */
const val ZEN_WORKSPACES_COLLECTION = "spaces"
const val ZEN_SPACES_TABS_UI_ENABLED = true

data class ZenSyncedTab(
    val title: String,
    val url: String,
    /** Space uuid this tab belongs to (`zen-workspace-id`); null for essentials (all spaces). */
    val workspaceUuid: String?,
    /** Folder (tab group) id this tab is nested under, or null. */
    val groupId: String? = null,
    val essential: Boolean = false,
    val pinned: Boolean = false,
    val userContextId: Int = 0,
    /** Sidebar position within its window; drives ordering. Null when the writer omitted it. */
    val position: Int? = null,
    val icon: String? = null,
    val folderName: String? = null,
)

data class ZenSpace(
    val uuid: String,
    val name: String,
    val icon: String?,
    val containerTabId: Int,
    /** Sidebar position from the record; null when the writer omitted it. */
    val position: Int?,
    /** Full cleartext minus the `id`/`type` sync envelope (Zen's stripSyncFields). */
    val raw: JSONObject,
    val tabs: List<ZenSyncedTab> = emptyList(),
    val containerGuid: String? = null,
)

data class ZenContainer(
    val userContextId: Int,
    val name: String,
    val icon: String?,
    val color: String?,
    val guid: String? = null,
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
        if (records.any { it.cleartext.has("kind") }) return ZenStableRecords.parse(records)
        val spaces = mutableListOf<ZenSpace>()
        val containers = mutableListOf<ZenContainer>()
        val tabs = mutableListOf<ZenSyncedTab>()
        for (record in records) {
            val data = record.cleartext
            if (data.optBoolean("deleted")) continue
            when (data.optString("type")) {
                "space" -> parseSpace(data)?.let(spaces::add)
                "container" -> parseContainer(data)?.let(containers::add)
                // Legacy experimental tab shapes; stable records are handled above.
                "tab", "pin", "essential" -> parseTab(data)?.let(tabs::add)
                // folder/split and unknown types: ignored for display, exactly like Zen's dispatch.
            }
        }
        return ZenWorkspacesSnapshot(
            spaces = attachTabs(orderSpacesByPosition(spaces), tabs),
            containers = containers,
        )
    }

    /**
     * Map each parsed tab onto its space via `zenWorkspace == space.uuid`. Essentials (no workspace)
     * appear in every space, as Zen pins them across all workspaces. Within a space, pinned/essential
     * tabs sort ahead of the rest, then by sidebar position.
     */
    private fun attachTabs(spaces: List<ZenSpace>, tabs: List<ZenSyncedTab>): List<ZenSpace> {
        if (tabs.isEmpty()) return spaces
        val essentials = tabs.filter { it.essential }
        val order = compareBy<ZenSyncedTab>({ !it.pinned }, { it.position ?: Int.MAX_VALUE })
        return spaces.map { space ->
            val own = tabs.filter { !it.essential && it.workspaceUuid == space.uuid }
            space.copy(tabs = (essentials + own).sortedWith(order))
        }
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

    /** Parse legacy experimental flat/session-store tab shapes. */
    private fun parseTab(data: JSONObject): ZenSyncedTab? {
        val entry = data.optJSONArray("entries")?.takeIf { it.length() > 0 }?.let { entries ->
            val index = data.optInt("index", 1)
            entries.optJSONObject((index - 1).coerceIn(0, entries.length() - 1))
                ?: entries.optJSONObject(0)
        }
        val url = (entry?.optString("url") ?: data.optString("url")).orEmpty()
        if (url.isBlank()) return null
        val title = (entry?.optString("title")?.ifBlank { null } ?: data.optString("title"))
            .ifBlank { url }
        val workspace = firstNonNullString(data, "zenWorkspace", "workspaceUuid")
        return ZenSyncedTab(
            title = title,
            url = url,
            workspaceUuid = workspace,
            groupId = firstNonNullString(data, "groupId", "parentUuid"),
            essential = data.optBoolean("zenEssential") || data.optBoolean("isEssential"),
            pinned = data.optBoolean("pinned"),
            userContextId = data.optInt("userContextId", 0),
            position = (data.opt("position") as? Number)?.toInt(),
            icon = firstNonNullString(data, "image", "icon"),
        )
    }

    /** First key present, non-null and non-empty; tolerates the field-name variance of an as-yet-unshipped record. */
    private fun firstNonNullString(data: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (data.isNull(key)) continue
            data.optString(key).ifEmpty { null }?.let { return it }
        }
        return null
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

/**
 * Compact JSON persistence for a parsed snapshot, so synced spaces render instantly on cold start
 * (before any network sync completes) instead of only after a full manual sync. Only the fields the
 * UI reads are stored; the space `raw` blob is preserved for forward compatibility.
 */
object ZenWorkspacesStorage {
    fun toJson(snapshot: ZenWorkspacesSnapshot): String {
        val spaces = JSONArray()
        for (space in snapshot.spaces) {
            val tabs = JSONArray()
            for (tab in space.tabs) {
                tabs.put(
                    JSONObject()
                        .put("title", tab.title)
                        .put("url", tab.url)
                        .put("workspaceUuid", tab.workspaceUuid)
                        .put("groupId", tab.groupId)
                        .put("essential", tab.essential)
                        .put("pinned", tab.pinned)
                        .put("userContextId", tab.userContextId)
                        .put("position", tab.position)
                        .put("icon", tab.icon)
                        .put("folderName", tab.folderName),
                )
            }
            spaces.put(
                JSONObject()
                    .put("uuid", space.uuid)
                    .put("name", space.name)
                    .put("icon", space.icon)
                    .put("containerTabId", space.containerTabId)
                    .put("containerGuid", space.containerGuid)
                    .put("position", space.position)
                    .put("raw", space.raw)
                    .put("tabs", tabs),
            )
        }
        val containers = JSONArray()
        for (c in snapshot.containers) {
            containers.put(
                JSONObject()
                    .put("userContextId", c.userContextId)
                    .put("name", c.name)
                    .put("icon", c.icon)
                    .put("color", c.color)
                    .put("guid", c.guid),
            )
        }
        return JSONObject().put("spaces", spaces).put("containers", containers).toString()
    }

    fun fromJson(json: String?): ZenWorkspacesSnapshot {
        if (json.isNullOrBlank()) return ZenWorkspacesSnapshot.EMPTY
        return runCatching {
            val root = JSONObject(json)
            val spaces = root.optJSONArray("spaces")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val tabsArr = s.optJSONArray("tabs")
                    val tabs = if (tabsArr == null) emptyList() else {
                        (0 until tabsArr.length()).mapNotNull { j ->
                            val t = tabsArr.optJSONObject(j) ?: return@mapNotNull null
                            ZenSyncedTab(
                                title = t.optString("title"),
                                url = t.optString("url"),
                                workspaceUuid = t.optStringOrNull("workspaceUuid"),
                                groupId = t.optStringOrNull("groupId"),
                                essential = t.optBoolean("essential"),
                                pinned = t.optBoolean("pinned"),
                                userContextId = t.optInt("userContextId", 0),
                                position = (t.opt("position") as? Number)?.toInt(),
                                icon = t.optStringOrNull("icon"),
                                folderName = t.optStringOrNull("folderName"),
                            )
                        }
                    }
                    ZenSpace(
                        uuid = s.optString("uuid"),
                        name = s.optString("name"),
                        icon = s.optStringOrNull("icon"),
                        containerTabId = s.optInt("containerTabId", 0),
                        position = (s.opt("position") as? Number)?.toInt(),
                        raw = s.optJSONObject("raw") ?: JSONObject(),
                        tabs = tabs,
                        containerGuid = s.optStringOrNull("containerGuid"),
                    )
                }
            } ?: emptyList()
            val containers = root.optJSONArray("containers")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    ZenContainer(
                        userContextId = c.optInt("userContextId", 0),
                        name = c.optString("name"),
                        icon = c.optStringOrNull("icon"),
                        color = c.optStringOrNull("color"),
                        guid = c.optStringOrNull("guid"),
                    )
                }
            } ?: emptyList()
            ZenWorkspacesSnapshot(spaces, containers)
        }.getOrDefault(ZenWorkspacesSnapshot.EMPTY)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
