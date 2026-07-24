package com.firebolt9907.tangentbrowser.state

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-shot channel from launcher **app shortcuts** (long-press the home-screen icon) into the running
 * `TangentRoot` UI. `MainActivity` records the requested [Action] from the shortcut intent; `TangentRoot`
 * collects it, performs the UI action, and clears it via [consume].
 *
 * A conflating [MutableStateFlow] (not a SharedFlow) is used deliberately so a cold start works: the
 * activity sets the action in `onCreate` *before* the UI composes, and `TangentRoot`'s collector
 * replays the current value on first subscribe. Warm starts (`onNewIntent`) just overwrite it.
 */
object AppShortcutSignals {
    enum class Action { NewTab, Bookmarks, History, VoiceSearch }

    val pending = MutableStateFlow<Action?>(null)

    fun request(action: Action) {
        pending.value = action
    }

    fun consume() {
        pending.value = null
    }

    // Distinct intent actions per shortcut. Static-shortcut <intent> elements can't carry arbitrary
    // extras reliably, so the action string itself selects the behavior. Declared in res/xml/shortcuts.xml.
    const val ACTION_NEW_TAB = "com.firebolt9907.tangentbrowser.action.NEW_TAB"
    const val ACTION_BOOKMARKS = "com.firebolt9907.tangentbrowser.action.BOOKMARKS"
    const val ACTION_HISTORY = "com.firebolt9907.tangentbrowser.action.HISTORY"
    // Fired by the home-screen search widget's mic button (see widget/SearchWidgetProvider).
    const val ACTION_VOICE_SEARCH = "com.firebolt9907.tangentbrowser.action.VOICE_SEARCH"

    /** Maps a shortcut intent's action to an [Action], or null if it isn't a shortcut intent. */
    fun fromAction(action: String?): Action? = when (action) {
        ACTION_NEW_TAB -> Action.NewTab
        ACTION_BOOKMARKS -> Action.Bookmarks
        ACTION_HISTORY -> Action.History
        ACTION_VOICE_SEARCH -> Action.VoiceSearch
        else -> null
    }
}
