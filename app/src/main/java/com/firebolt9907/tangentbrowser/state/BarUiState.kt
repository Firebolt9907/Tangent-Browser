package com.firebolt9907.tangentbrowser.state

data class BarUiState(
    val displayUrl: String,
    val tabCount: Int,
    val mode: BarMode,
    val editingText: String,
    val compactDisplayUrl: String = displayUrl,
    val pageUrl: String = displayUrl,
    val blankNewTabPreview: Boolean = false,
)

enum class BarMode { Expanded, Compact, Editing }
