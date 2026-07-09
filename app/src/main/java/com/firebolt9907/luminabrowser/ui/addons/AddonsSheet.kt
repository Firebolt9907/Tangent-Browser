package com.firebolt9907.luminabrowser.ui.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.luminabrowser.addons.AddonController
import com.firebolt9907.luminabrowser.addons.AddonPermissionPrompt
import com.firebolt9907.luminabrowser.addons.InstalledAddon
import com.firebolt9907.luminabrowser.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsSheet(
    controller: AddonController,
    onDismiss: () -> Unit,
    bottomInsetDp: Dp = 0.dp,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val pendingPrompt = state.pendingPrompt

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = {
            pendingPrompt?.let { controller.respondToPrompt(it.id, false) }
            onDismiss()
        },
        contentWindowInsets = { WindowInsets(bottom = bottomInsetDp) },
    ) {
        if (pendingPrompt != null) {
            AddonPermissionSheetContent(
                prompt = pendingPrompt,
                onAccept = {
                    controller.respondToPrompt(pendingPrompt.id, true)
                    onDismiss()
                },
                onDeny = {
                    controller.respondToPrompt(pendingPrompt.id, false)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
internal fun AddonsManagerSheetContent(
    state: com.firebolt9907.luminabrowser.addons.AddonsUiState,
    installText: String,
    onInstallTextChange: (String) -> Unit,
    onInstall: () -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onOpenSettings: (InstalledAddon) -> Unit,
    onOpenQuickSettings: (InstalledAddon) -> Unit,
    onUninstall: (String) -> Unit,
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
            Icon(Icons.Default.Extension, contentDescription = null)
            Text("Add-ons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val canInstall = installText.isNotBlank() && !state.isBusy
            OutlinedTextField(
                value = installText,
                onValueChange = onInstallTextChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("AMO slug, page, or .xpi URL") },
                trailingIcon = if (installText.isNotEmpty()) {
                    {
                        IconButton(onClick = { onInstallTextChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (canInstall) onInstall() }),
            )
            Button(
                enabled = canInstall,
                onClick = onInstall,
            ) {
                Text("Install")
            }
        }

        state.message?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                color = if (state.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (state.addons.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Extension,
                title = "No add-ons installed",
                description = "Install an extension by entering its addons.mozilla.org name or .xpi link above.",
            )
        } else {
            LazyColumn {
                items(state.addons, key = InstalledAddon::id) { addon ->
                    AddonRow(
                        addon = addon,
                        onEnabledChange = { onEnabledChange(addon.id, it) },
                        onOpenSettings = { onOpenSettings(addon) },
                        onOpenQuickSettings = { onOpenQuickSettings(addon) },
                        onUninstall = { onUninstall(addon.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonRow(
    addon: InstalledAddon,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onUninstall: () -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                text = addon.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val details = buildList {
                if (addon.version.isNotBlank()) add("v${addon.version}")
                if (addon.description.isNotBlank()) add(addon.description)
            }.joinToString(" - ")
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = addon.isEnabled,
                    onCheckedChange = onEnabledChange,
                )
                if (addon.isEnabled && addon.hasQuickSettings) {
                    IconButton(onClick = onOpenQuickSettings) {
                        Icon(Icons.Default.Tune, contentDescription = "Open ${addon.name} quick settings")
                    }
                }
                if (addon.optionsPageUrl != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Open ${addon.name} settings")
                    }
                }
                IconButton(onClick = { pendingRemoval = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove ${addon.name}")
                }
            }
        },
    )

    if (pendingRemoval) {
        AlertDialog(
            onDismissRequest = { pendingRemoval = false },
            title = { Text("Remove ${addon.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = false
                        onUninstall()
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun AddonPermissionSheetContent(
    prompt: AddonPermissionPrompt,
    onAccept: () -> Unit,
    onDeny: () -> Unit,
) {
    val permissionGroups = listOf(
        PermissionGroup(
            title = "Extension access",
            items = prompt.permissions.map(::readablePermission),
        ),
        PermissionGroup(
            title = "Website access",
            items = prompt.origins.map(::readableOrigin),
        ),
        PermissionGroup(
            title = "Data collection",
            items = prompt.dataCollectionPermissions.map(::readableDataCollectionPermission),
        ),
    ).filter { it.items.isNotEmpty() }
    val totalPermissions = permissionGroups.sumOf { it.items.size }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(prompt.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (permissionGroups.isEmpty()) {
            Text(
                text = "This add-on does not request additional permissions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = "Review what this add-on can access before installing.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            permissionGroups.forEach { group ->
                PermissionGroupCard(group)
            }
            if (totalPermissions > 12) {
                Text(
                    text = "$totalPermissions total permission requests",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDeny) {
                Text("Cancel")
            }
            Button(onClick = onAccept) {
                Text("Allow")
            }
        }
    }
}

private data class PermissionGroup(
    val title: String,
    val items: List<String>,
)

@Composable
private fun PermissionGroupCard(group: PermissionGroup) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = group.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            group.items.take(6).forEach { item ->
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (group.items.size > 6) {
                Text(
                    text = "+ ${group.items.size - 6} more",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun readablePermission(permission: String): String =
    when (permission) {
        "activeTab" -> "Access the current tab when you interact with the add-on"
        "alarms" -> "Run scheduled background tasks"
        "bookmarks" -> "Read and modify bookmarks"
        "browsingData" -> "Clear browsing data"
        "clipboardRead" -> "Read from the clipboard"
        "clipboardWrite" -> "Write to the clipboard"
        "cookies" -> "Read and modify cookies"
        "downloads" -> "Download files and read download history"
        "geolocation" -> "Access your location"
        "history" -> "Read and modify browsing history"
        "management" -> "View and manage installed add-ons"
        "nativeMessaging" -> "Exchange messages with native applications"
        "notifications" -> "Show notifications"
        "privacy" -> "Read and modify privacy settings"
        "proxy" -> "Control proxy settings"
        "sessions" -> "Access recently closed tabs"
        "storage" -> "Store add-on data locally"
        "tabs" -> "Access browser tabs"
        "topSites" -> "Access top sites"
        "unlimitedStorage" -> "Store unlimited local data"
        "webNavigation" -> "Observe browser navigation"
        "webRequest" -> "Observe network requests"
        "webRequestBlocking" -> "Block or modify network requests"
        else -> permission
    }

private fun readableOrigin(origin: String): String =
    when (origin) {
        "<all_urls>", "*://*/*" -> "All websites"
        else -> origin
            .removePrefix("*://")
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/*")
            .ifBlank { origin }
    }

private fun readableDataCollectionPermission(permission: String): String =
    permission
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceFirstChar { it.titlecase() }
