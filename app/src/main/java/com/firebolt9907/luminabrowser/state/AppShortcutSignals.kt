package com.firebolt9907.luminabrowser.state

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-shot channel from launcher **app shortcuts** (long-press the home-screen icon) into the running
 * `LuminaRoot` UI. `MainActivity` records the requested [Action] from the shortcut intent; `LuminaRoot`
 * collects it, performs the UI action, and clears it via [consume].
 *
 * A conflating [MutableStateFlow] (not a SharedFlow) is used deliberately so a cold start works: the
 * activity sets the action in `onCreate` *before* the UI composes, and `LuminaRoot`'s collector
 * replays the current value on first subscribe. Warm starts (`onNewIntent`) just overwrite it.
 */
object AppShortcutSignals {
    enum class Action { NewTab, Bookmarks, History }

    val pending = MutableStateFlow<Action?>(null)

    fun request(action: Action) {
        pending.value = action
    }

    fun consume() {
        pending.value = null
    }

    // Distinct intent actions per shortcut. Static-shortcut <intent> elements can't carry arbitrary
    // extras reliably, so the action string itself selects the behavior. Declared in res/xml/shortcuts.xml.
    const val ACTION_NEW_TAB = "com.firebolt9907.luminabrowser.action.NEW_TAB"
    const val ACTION_BOOKMARKS = "com.firebolt9907.luminabrowser.action.BOOKMARKS"
    const val ACTION_HISTORY = "com.firebolt9907.luminabrowser.action.HISTORY"

    /** Maps a shortcut intent's action to an [Action], or null if it isn't a shortcut intent. */
    fun fromAction(action: String?): Action? = when (action) {
        ACTION_NEW_TAB -> Action.NewTab
        ACTION_BOOKMARKS -> Action.Bookmarks
        ACTION_HISTORY -> Action.History
        else -> null
    }
}
