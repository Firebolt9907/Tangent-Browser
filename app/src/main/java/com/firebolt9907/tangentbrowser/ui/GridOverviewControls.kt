package com.firebolt9907.tangentbrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.engine.TangentBrowserComponents
import com.firebolt9907.tangentbrowser.state.TangentViewModel
import com.firebolt9907.tangentbrowser.sync.FirefoxSyncUiState
import com.firebolt9907.tangentbrowser.ui.grid.TabViewMode
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import com.firebolt9907.tangentbrowser.ui.sync.HandoffCard

/**
 * The floating bottom controls of the tab overview (deck/pixel/grid): the Firefox-Sync handoff
 * card and the toolbar row (New Tab, Synced, overflow → layout mode / reopen-closed / bookmarks /
 * history / downloads / passwords / settings). Extracted verbatim from TangentRoot's `if
 * (gridEngaged)` block. Pure chrome — it touches none of the gesture/snapshot invariants; the only
 * draw-phase read is the fade [alpha], which reads [GridGestureState.gridProgressState] inside a
 * `graphicsLayer` (invariant #4: read-only, no `snapTo`).
 *
 * Rendered by the caller only while `!gridGestureActive` (controls stay hidden during an active
 * drag), so its height is measured back into [GridGestureState.gridBottomControlsHeightPx] only
 * when the finger is up.
 */
@Composable
internal fun ColumnScope.GridOverviewControls(
    grid: GridGestureState,
    syncState: FirefoxSyncUiState,
    components: TangentBrowserComponents,
    viewModel: TangentViewModel,
    haptics: TangentHaptics,
    tabViewMode: TabViewMode,
    onNewTab: () -> Unit,
    onCloseGrid: () -> Unit,
    onSelectTabViewMode: (TabViewMode) -> Unit,
    onShowSyncedTabs: () -> Unit,
    onShowTabGroups: () -> Unit,
    onShowContainers: () -> Unit,
    /** Arc-style swipe-up on the toolbar to reveal the synced Zen spaces deck; null disables it. */
    onSwipeUpSpaces: (() -> Unit)?,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onShowDownloads: () -> Unit,
    onShowPasswords: () -> Unit,
    onShowCreditCards: () -> Unit,
    onShowAddresses: () -> Unit,
    onShowSettings: () -> Unit,
) {
    val handoff = syncState.handoffTab
    AnimatedVisibility(
        visible = handoff != null,
        // Expand/shrink rather than slide: the card grows out from behind the toolbar below it, and
        // — the point — the column's height is part of the animation, so the space switcher above
        // and the deck's bottomInset (measured off this column) travel with the card instead of
        // jumping a full card-height in the frame it mounts. See [TangentMotion.RevealExpand].
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = TangentMotion.RevealExpand,
        ) + fadeIn(ExpressiveMotion.defaultEffectsSpec()),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = TangentMotion.RevealCollapse,
        ) + fadeOut(ExpressiveMotion.fastEffectsSpec()),
    ) {
        var lastHandoff by remember { mutableStateOf(handoff) }
        if (handoff != null) lastHandoff = handoff
        lastHandoff?.let { tab ->
            HandoffCard(
                tab = tab,
                onOpen = {
                    components.firefoxSyncController.dismissHandoff()
                    viewModel.onOpenNewTab(url = tab.url, title = tab.title, selectTab = true)
                    onCloseGrid()
                },
                onDismiss = {
                    components.firefoxSyncController.dismissHandoff()
                }
            )
        }
    }

    // Swipe up anywhere on the toolbar surface to reveal the Zen spaces deck (Arc-style). A gentle
    // upward flick past the threshold fires; taps still reach the buttons (vertical drag doesn't
    // consume the down event until slop is crossed).
    val swipeModifier = if (onSwipeUpSpaces != null) {
        Modifier.pointerInput(Unit) {
            val thresholdPx = 44.dp.toPx()
            var accumulated = 0f
            detectVerticalDragGestures(
                onDragStart = { accumulated = 0f },
                onDragEnd = { if (accumulated <= -thresholdPx) onSwipeUpSpaces() },
                onVerticalDrag = { _, dragAmount -> accumulated += dragAmount },
            )
        }
    } else {
        Modifier
    }
    Surface(
        modifier = swipeModifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Drag-handle affordance hinting the swipe-up-for-spaces gesture; only shown when enabled.
        if (onSwipeUpSpaces != null) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // This is a composition input, but it has only one transition
            // per open/close animation instead of invalidating the toolbar on
            // every animation frame.
            val toolbarEnabled by remember {
                derivedStateOf { grid.gridProgressState.value > 0.65f }
            }
            FilledTonalIconButton(
                onClick = { onNewTab() },
                enabled = toolbarEnabled,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Tab")
            }
            // Synced Tabs is a labeled button, not an anonymous cloud icon —
            // it's the primary reason to open this hub on a second device, so it
            // reads clearly instead of hiding behind mystery-meat iconography.
            // A count badge signals there are actually tabs waiting from other
            // devices, so it's discoverable rather than a shot in the dark.
            val syncedTabCount = syncState.devices.sumOf { it.tabs.size }
            BadgedBox(
                badge = {
                    if (syncedTabCount > 0) {
                        Badge { Text(if (syncedTabCount > 99) "99+" else "$syncedTabCount") }
                    }
                },
            ) {
                FilledTonalButton(
                    onClick = { onShowSyncedTabs() },
                    enabled = toolbarEnabled,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Synced")
                }
            }
            // Secondary library/settings destinations live in an overflow menu
            // so the bar stays focused on tab actions (New Tab, layout, sync).
            Box {
                val context = LocalContext.current
                var overflowOpen by remember { mutableStateOf(false) }
                var confirmCloseAll by remember { mutableStateOf(false) }
                val reopenableCount by components.tabRepository.reopenableCount
                    .collectAsStateWithLifecycle()
                val openTabCount = components.store.state.tabs.size
                // The current tab backs the two page-level actions below (Duplicate / Add to Home
                // Screen), relocated here from the aA sheet. Read at compose time — the menu
                // recomposes each time it opens, so this reflects the tab that's currently selected.
                val selectedTab = components.store.state.selectedTabId
                    ?.let { id -> components.store.state.tabs.firstOrNull { it.id == id } }
                val selectedTabIsWeb = selectedTab?.content?.url
                    ?.startsWith("http", ignoreCase = true) == true
                FilledTonalIconButton(
                    onClick = { overflowOpen = true },
                    enabled = toolbarEnabled,
                ) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    // Tab layout is a display preference, not a primary action — so it's a single
                    // compact segmented control at the top of the menu (one row) rather than three
                    // stacked full-width items, keeping the action list below it short.
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TabViewMode.values().forEach { mode ->
                            val (modeLabel, modeIcon) = when (mode) {
                                TabViewMode.DECK -> "Deck" to Icons.Default.ViewCarousel
                                TabViewMode.PIXEL -> "Filmstrip" to Icons.Default.ViewColumn
                                TabViewMode.GRID -> "Grid" to Icons.Default.GridView
                            }
                            val selected = mode == tabViewMode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else Color.Transparent,
                                    )
                                    .clickable {
                                        overflowOpen = false
                                        if (!selected) onSelectTabViewMode(mode)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    modeIcon,
                                    contentDescription = modeLabel,
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    // Reopen the last closed tab (Safari/Arc). Disabled — not
                    // hidden — when the stack is empty, so its slot is stable.
                    DropdownMenuItem(
                        text = { Text("Reopen Closed Tab") },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = null,
                            )
                        },
                        enabled = reopenableCount > 0,
                        onClick = {
                            overflowOpen = false
                            components.tabRepository.reopenLastClosedTab()
                            haptics.create()
                            onCloseGrid()
                        },
                    )
                    // Bulk-close every tab. Recoverable (each real page lands on the Reopen stack), but
                    // still confirmed since it's a sweeping action. Disabled when there's nothing to clear.
                    DropdownMenuItem(
                        text = { Text("Close All Tabs") },
                        leadingIcon = {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        },
                        enabled = openTabCount > 0,
                        onClick = {
                            overflowOpen = false
                            confirmCloseAll = true
                        },
                    )
                    // Duplicate the current page into a new foreground tab (branch browsing).
                    // Relocated from the aA sheet so the action isn't lost.
                    DropdownMenuItem(
                        text = { Text("Duplicate Tab") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        enabled = selectedTabIsWeb,
                        onClick = {
                            overflowOpen = false
                            selectedTab?.content?.let { c ->
                                viewModel.onOpenNewTab(url = c.url, title = c.title, selectTab = true)
                            }
                            haptics.create()
                            onCloseGrid()
                        },
                    )
                    // Pin the current page to the launcher home screen (Safari's "Add to Home
                    // Screen"). Relocated from the aA sheet; uses the page favicon when available.
                    DropdownMenuItem(
                        text = { Text("Add to Home Screen") },
                        leadingIcon = { Icon(Icons.Default.AddToHomeScreen, contentDescription = null) },
                        enabled = selectedTabIsWeb,
                        onClick = {
                            overflowOpen = false
                            selectedTab?.content?.let { c ->
                                pinPageToHomeScreen(context, c.url, c.title, c.icon)
                            }
                            haptics.softClick()
                        },
                    )
                    HorizontalDivider()
                    // Tab organization: manage Spaces (named tab sets, switchable from the strip
                    // above) + containers (cookie isolation).
                    DropdownMenuItem(
                        text = { Text("Manage Spaces") },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowTabGroups() },
                    )
                    DropdownMenuItem(
                        text = { Text("Containers") },
                        leadingIcon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowContainers() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Bookmarks") },
                        leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowBookmarks() },
                    )
                    DropdownMenuItem(
                        text = { Text("History") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowHistory() },
                    )
                    DropdownMenuItem(
                        text = { Text("Downloads") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowDownloads() },
                    )
                    // Divide browsing data (above) from app/account items (below).
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Passwords") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowPasswords() },
                    )
                    DropdownMenuItem(
                        text = { Text("Payment methods") },
                        leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowCreditCards() },
                    )
                    DropdownMenuItem(
                        text = { Text("Addresses") },
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowAddresses() },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = { overflowOpen = false; onShowSettings() },
                    )
                }
                if (confirmCloseAll) {
                    AlertDialog(
                        onDismissRequest = { confirmCloseAll = false },
                        icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                        title = { Text("Close all tabs?") },
                        text = {
                            Text(
                                if (openTabCount == 1) "This closes the current tab." +
                                    " You can reopen it from Reopen Closed Tab."
                                else "This closes all $openTabCount tabs. You can reopen them from" +
                                    " Reopen Closed Tab.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmCloseAll = false
                                components.tabRepository.closeAllTabs()
                                haptics.remove()
                                onCloseGrid()
                            }) { Text("Close all") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
      }
    }
}
