package com.firebolt9907.luminabrowser.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.luminabrowser.sync.FirefoxSyncController
import com.firebolt9907.luminabrowser.sync.FirefoxSyncedDevice
import com.firebolt9907.luminabrowser.sync.FirefoxSyncedTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedTabsSheet(
    controller: FirefoxSyncController,
    onDismiss: () -> Unit,
    onOpenTab: (String) -> Unit,
    onSignInUrl: (String) -> Unit,
    bottomInsetDp: Dp = 0.dp,
) {
    val state by controller.state.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(bottom = bottomInsetDp) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.CloudSync, contentDescription = null)
                Text("Firefox Sync", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(22.dp)
                            .height(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!state.isSignedIn) {
                Text(
                    text = "Sign in with a Mozilla account to see tabs from your other Firefox devices.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !state.isBusy,
                    onClick = { controller.beginSignIn(onSignInUrl) },
                ) {
                    Text("Sign in with Firefox")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.accountEmail ?: "Mozilla account",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Tabs engine enabled",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        enabled = !state.isBusy,
                        onClick = controller::refreshSyncedTabs,
                    ) {
                        Text("Sync Now")
                    }
                    TextButton(
                        enabled = !state.isBusy,
                        onClick = controller::signOut,
                    ) {
                        Text("Sign Out")
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (state.devices.isEmpty()) {
                    Text(
                        text = state.message ?: "No tabs from other devices yet",
                        color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                } else {
                    LazyColumn {
                        state.devices.forEach { device ->
                            item(key = "device:${device.id}") {
                                DeviceHeader(device)
                            }
                            items(device.tabs, key = { tab -> "${device.id}:${tab.url}:${tab.lastUsed}" }) { tab ->
                                SyncedTabRow(
                                    tab = tab,
                                    onOpen = {
                                        onOpenTab(tab.url)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            state.message?.takeIf { state.devices.isNotEmpty() || state.isError }?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DeviceHeader(device: FirefoxSyncedDevice) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp),
    ) {
        Text(
            text = device.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = device.type,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SyncedTabRow(
    tab: FirefoxSyncedTab,
    onOpen: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = tab.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = tab.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            TextButton(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Open")
            }
        },
    )
}
