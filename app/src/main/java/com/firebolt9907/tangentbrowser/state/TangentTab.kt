package com.firebolt9907.tangentbrowser.state

data class TangentTab(
    val id: String,
    val url: String,
    val title: String,
    /**
     * Cookie-store id ([mozilla.components.browser.state.state.TabSessionState.contextId]) of the
     * container this tab is isolated in, or null for the default context. Lets the containers UI
     * count/list a container's tabs without re-reading the store. Stable across the tab's life.
     */
    val contextId: String? = null,
)
