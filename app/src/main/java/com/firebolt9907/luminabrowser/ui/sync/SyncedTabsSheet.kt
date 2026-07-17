package com.firebolt9907.luminabrowser.ui.sync

import androidx.compose.foundation.clickable
import com.firebolt9907.luminabrowser.sync.zen.ZEN_SPACES_TABS_UI_ENABLED
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.firebolt9907.luminabrowser.ui.components.LuminaModalSheet
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.luminabrowser.ui.components.EmptyState
import com.firebolt9907.luminabrowser.sync.FirefoxSyncController
import com.firebolt9907.luminabrowser.sync.FirefoxSyncedDevice
import com.firebolt9907.luminabrowser.sync.FirefoxSyncedTab
import com.firebolt9907.luminabrowser.ui.components.Favicon
import com.firebolt9907.luminabrowser.ui.rememberLuminaHaptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedTabsSheet(
    controller: FirefoxSyncController,
    onDismiss: () -> Unit,
    onOpenTab: (String) -> Unit,
    onSignInUrl: (String) -> Unit,
    currentPageUrl: String = "",
    currentPageTitle: String = "",
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val haptics = rememberLuminaHaptics()

    // On open, actively confirm we're still signed in (catches a remote sign-out) and reflect the
    // latest background-sync results immediately (no network round trip).
    LaunchedEffect(Unit) { controller.verifyAccount() }
    LaunchedEffect(state.isSignedIn) {
        if (state.isSignedIn) controller.loadCachedSyncedTabs()
    }

    LuminaModalSheet(onDismissRequest = onDismiss) {
        SheetHeader(
            title = "Firefox Sync",
            leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
            trailing = {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(22.dp)
                            .height(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
        )

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
                onClick = { haptics.softClick(); controller.beginSignIn(onSignInUrl) },
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
                    onClick = { haptics.softClick(); controller.refreshSyncedTabs() },
                ) {
                    Text("Sync Now")
                }
                TextButton(
                    enabled = !state.isBusy,
                    onClick = { haptics.remove(); controller.signOut() },
                ) {
                    Text("Sign Out")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Zen browser spaces synced on this account (read-only; empty unless a Zen client with
            // space sync has written the `spaces` collection). Arc-style swipeable deck. Gated off
            // until Zen ships tab-in-space sync (spaces carry no tabs today) — see ZenWorkspaces.kt.
            if (ZEN_SPACES_TABS_UI_ENABLED && state.zenWorkspaces.spaces.isNotEmpty()) {
                Text(
                    text = "Spaces",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SpacesCarousel(
                    spaces = state.zenWorkspaces.spaces,
                    containers = state.zenWorkspaces.containers,
                    onOpenTab = { url ->
                        onOpenTab(url)
                        onDismiss()
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

            val hasRealUrl = currentPageUrl.isNotBlank() && currentPageUrl != "about:blank"
            if (hasRealUrl && state.targetDevices.isNotEmpty()) {
                Text(
                    text = "Send this tab to",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                state.targetDevices.forEach { device ->
                    TextButton(
                        enabled = !state.isBusy,
                        onClick = {
                            haptics.create()
                            controller.sendTab(device.id, currentPageTitle, currentPageUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.width(18.dp),
                            )
                            Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (state.devices.isEmpty()) {
                // Friendly empty state for the normal "nothing synced yet" case. Error cases
                // are surfaced by the red message block below, so we don't render here for them
                // (which also avoids the message appearing twice).
                if (!state.isError) {
                    EmptyState(
                        icon = Icons.Default.CloudSync,
                        title = "No synced tabs yet",
                        description = state.message
                            ?: "Tabs open on your other Firefox devices will appear here.",
                    )
                }
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
                                haptics = haptics,
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
    haptics: com.firebolt9907.luminabrowser.ui.LuminaHaptics? = null,
) {
    ListItem(
        modifier = Modifier.clickable { haptics?.softClick(); onOpen() },
        leadingContent = { Favicon(tab.url) },
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
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open tab",
            )
        },
    )
}
