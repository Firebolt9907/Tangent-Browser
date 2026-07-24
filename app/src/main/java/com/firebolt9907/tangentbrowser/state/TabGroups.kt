package com.firebolt9907.tangentbrowser.state

/**
 * Colour identity of a tab group. State-layer pure (no Compose): the UI maps each entry to a
 * concrete swatch. Persisted by name; unknown/legacy names fall back to [BLUE].
 */
enum class TabGroupColor {
    GRAY,
    BLUE,
    CYAN,
    GREEN,
    YELLOW,
    ORANGE,
    RED,
    PINK,
    PURPLE,
    ;

    companion object {
        fun fromName(name: String?): TabGroupColor =
            entries.firstOrNull { it.name == name } ?: BLUE
    }
}

/**
 * An organizational tab group (Chrome/Arc-style): a named, coloured set of open tabs. Purely a
 * label over existing tabs — grouping has no effect on cookie isolation (that's a *container*,
 * [com.firebolt9907.tangentbrowser.state.ContainerRepository]). A tab belongs to at most one group.
 *
 * [tabIds] are live tab ids and are transient across process restarts (restored tabs get fresh
 * ids); membership is re-linked on restore via each persisted tab's `groupId`
 * ([TabPersistence.PersistedTab.groupId]), while the group's own identity/name/colour survives in
 * the group registry ([TabGroupsRepository]). See AGENTS.md invariant #9-adjacent persistence notes.
 */
data class TangentTabGroup(
    val id: String,
    val name: String,
    val color: TabGroupColor,
    val tabIds: List<String> = emptyList(),
    /**
     * Container (cookie-store `contextId`) that new tabs opened *into this group* default to, or null
     * for the default context. Resolved against [com.firebolt9907.tangentbrowser.state.ContainerRepository];
     * a stale id (container deleted) simply falls back to the default context.
     */
    val defaultContextId: String? = null,
)
