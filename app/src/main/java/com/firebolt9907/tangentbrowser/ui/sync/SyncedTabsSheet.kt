package com.firebolt9907.tangentbrowser.ui.sync

import androidx.compose.foundation.clickable
import com.firebolt9907.tangentbrowser.sync.zen.ZEN_SPACES_TABS_UI_ENABLED
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.firebolt9907.tangentbrowser.ui.components.BusyIndicator
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.sync.FirefoxSyncController
import com.firebolt9907.tangentbrowser.sync.FirefoxSyncedDevice
import com.firebolt9907.tangentbrowser.sync.FirefoxSyncedTab
import com.firebolt9907.tangentbrowser.ui.components.Favicon
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.theme.titleSmallEmphasized

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
    val haptics = rememberTangentHaptics()

    // On open, actively confirm we're still signed in (catches a remote sign-out) and reflect the
    // latest background-sync results immediately (no network round trip).
    LaunchedEffect(Unit) { controller.verifyAccount() }
    LaunchedEffect(state.isSignedIn) {
        if (state.isSignedIn) controller.loadCachedSyncedTabs()
    }

    if (state.isSignedIn && ZEN_SPACES_TABS_UI_ENABLED && state.zenWorkspaces.spaces.isNotEmpty()) {
        ZenSpacesSyncSheet(state, controller, onDismiss, onOpenTab, currentPageUrl, currentPageTitle)
        return
    }

    TangentModalSheet(onDismissRequest = onDismiss) {
        SheetHeader(
            title = "Firefox Sync",
            leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
            trailing = { BusyIndicator(visible = state.isBusy) },
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
                        style = MaterialTheme.typography.titleSmallEmphasized,
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
                // Sign Out is rare and irreversible-ish next to Sync Now, which is the reason people
                // open this sheet — so it keeps its space but loses visual weight (demotion, not
                // removal).
                TextButton(
                    enabled = !state.isBusy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    onClick = { haptics.remove(); controller.signOut() },
                ) {
                    Text("Sign Out")
                }
            }

            Spacer(Modifier.height(12.dp))

            val hasRealUrl = currentPageUrl.isNotBlank() && currentPageUrl != "about:blank"
            if (hasRealUrl && state.targetDevices.isNotEmpty()) {
                Text(
                    text = "Send this tab to",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // One send target is one action on a named object — an AssistChip, not a full-width
                // button. Chips wrap, so a three-device account costs one line instead of three
                // stacked buttons pushing the synced-tab list off the first screenful. AssistChip
                // applies the 48dp minimum touch target itself despite its 32dp visual height.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.targetDevices.forEach { device ->
                        AssistChip(
                            enabled = !state.isBusy,
                            onClick = {
                                haptics.create()
                                controller.sendTab(device.id, currentPageTitle, currentPageUrl)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message,
                    color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                // A sync failure that only states the failure leaves the only way out as closing and
                // reopening the sheet. Signed-in errors are almost always transient (network, token
                // refresh), so the retry belongs next to the message.
                if (state.isError && state.isSignedIn) {
                    TextButton(
                        enabled = !state.isBusy,
                        onClick = { haptics.softClick(); controller.refreshSyncedTabs() },
                    ) {
                        Text("Try again")
                    }
                }
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
            style = MaterialTheme.typography.titleSmallEmphasized,
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
    haptics: com.firebolt9907.tangentbrowser.ui.TangentHaptics? = null,
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
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            // Decorative: the whole row is the click target, so a description here just makes
            // TalkBack read a second, unactionable "Open tab" element after the row itself.
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
