package com.firebolt9907.luminabrowser.ui.chrome

/**
 * How the bottom bar arranges itself on wide screens (tablets, and any phone in landscape).
 * Persisted by name in [com.firebolt9907.luminabrowser.state.GlobalPreferencesStore.wideBarLayoutName];
 * on narrow/portrait phones neither applies — the classic full-width centred bar is used.
 *
 * - [SPLIT]  — one bottom row: action buttons clustered on the left, a width-capped address pill
 *              anchored to the bottom-right. The primary wide layout, paired with the depth
 *              card-stack tab-switch animation.
 * - [CORNER] — the classic stacked pill + toolbar, just width-capped and pinned to the bottom-right
 *              corner. The lighter alternative; keeps the flat tab-switch slide.
 */
enum class WideBarLayout {
    SPLIT,
    CORNER;

    companion object {
        /** Parse a persisted name; unknown / null falls back to [SPLIT] (the primary layout). */
        fun fromName(name: String?): WideBarLayout =
            entries.firstOrNull { it.name == name } ?: SPLIT
    }
}
