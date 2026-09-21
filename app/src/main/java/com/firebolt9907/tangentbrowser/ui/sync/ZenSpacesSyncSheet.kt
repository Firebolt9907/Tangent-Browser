package com.firebolt9907.tangentbrowser.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.sync.FirefoxSyncController
import com.firebolt9907.tangentbrowser.sync.FirefoxSyncUiState
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet

/** Zen replaces the legacy device-tab presentation, while the underlying Firefox engines keep running. */
@Composable
internal fun ZenSpacesSyncSheet(
    state: FirefoxSyncUiState,
    controller: FirefoxSyncController,
    onDismiss: () -> Unit,
    onOpenTab: (String) -> Unit,
    currentPageUrl: String,
    currentPageTitle: String,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    TangentModalSheet(onDismissRequest = onDismiss, bottomPadding = 12.dp) {
        Column(Modifier.fillMaxHeight(0.94f)) {
            SheetHeader(
                title = "Spaces",
                trailing = {
                    if (state.isBusy) {
                        CircularProgressIndicator(Modifier.padding(14.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = controller::refreshSyncedTabs) {
                            Icon(Icons.Default.Refresh, "Sync spaces")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Sync options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Sync settings") }, onClick = {
                                menuOpen = false; settingsOpen = true
                            })
                            if (currentPageUrl.startsWith("https://") || currentPageUrl.startsWith("http://")) {
                                state.targetDevices.forEach { device ->
                                    DropdownMenuItem(text = { Text("Send current tab to ${device.name}") }, onClick = {
                                        menuOpen = false
                                        controller.sendTab(device.id, currentPageTitle, currentPageUrl)
                                    }, enabled = !state.isBusy)
                                }
                            }
                        }
                    }
                },
            )
            // Only actionable errors compete with the space content; routine status stays out of the list.
            (state.zenWorkspacesError ?: state.message?.takeIf { state.isError })?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            SpacesCarousel(
                spaces = state.zenWorkspaces.spaces,
                containers = state.zenWorkspaces.containers,
                onOpenTab = { onOpenTab(it); onDismiss() },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Sync settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.accountEmail ?: "Mozilla account", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Share phone tabs", modifier = Modifier.weight(1f))
                        Switch(checked = state.zenPhoneSpaceEnabled, onCheckedChange = controller::setZenPhoneSpaceEnabled)
                    }
                    Text(if (state.zenPhoneSpaceEnabled)
                        "Your phone tabs appear in a dedicated Tangent space on your computer. Enable normal tab syncing in Zen to receive them. Private tabs aren't shared."
                    else "Updates are paused. The existing desktop copy stays available.",
                        style = MaterialTheme.typography.bodySmall)
                    state.zenPhoneSpaceError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } },
            dismissButton = {
                TextButton(enabled = !state.isBusy, onClick = { settingsOpen = false; controller.signOut() }) {
                    Text("Sign out")
                }
            },
        )
    }
}
