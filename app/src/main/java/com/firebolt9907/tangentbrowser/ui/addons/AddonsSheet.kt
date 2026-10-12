package com.firebolt9907.tangentbrowser.ui.addons

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.components.BusyIndicator
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SwipeToRemoveRow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.addons.AddonController
import com.firebolt9907.tangentbrowser.addons.AddonPermissionPrompt
import com.firebolt9907.tangentbrowser.addons.InstalledAddon
import com.firebolt9907.tangentbrowser.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsSheet(
    controller: AddonController,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val pendingPrompt = state.pendingPrompt

    ModalBottomSheet(
         sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
         onDismissRequest = {
             pendingPrompt?.let { controller.respondToPrompt(it.id, false) }
             onDismiss()
         },
         contentWindowInsets = { WindowInsets(0.dp) },
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
    state: com.firebolt9907.tangentbrowser.addons.AddonsUiState,
    installText: String,
    onInstallTextChange: (String) -> Unit,
    onInstall: () -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onOpenSettings: (InstalledAddon) -> Unit,
    onOpenQuickSettings: (InstalledAddon) -> Unit,
    onUninstall: (String) -> Unit,
    onUblockAutomaticUpdatesChange: (Boolean) -> Unit,
    // When non-null, the header's leading icon becomes a Back control (returns to the Page view).
    // Used by the aA sheet, which reaches Add-ons via a button rather than a top-level tab.
    onBack: (() -> Unit)? = null,
) {
    val haptics = rememberTangentHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        SheetHeader(
            title = "Add-ons",
            leadingIcon = {
                if (onBack != null) {
                    IconButton(onClick = { haptics.softClick(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Icon(Icons.Default.Extension, contentDescription = null)
                }
            },
            trailing = { BusyIndicator(visible = state.isBusy) },
        )

        ListItem(
            headlineContent = { Text("Automatically update uBlock Origin") },
            supportingContent = { Text("Use Mozilla-signed releases between Tangent updates. New permissions require approval.") },
            trailingContent = {
                Switch(
                    checked = state.ublockAutomaticUpdates,
                    onCheckedChange = onUblockAutomaticUpdatesChange,
                )
            },
        )

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
                        IconButton(onClick = { haptics.remove(); onInstallTextChange("") }) {
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
                onClick = { haptics.create(); onInstall() },
            ) {
                Text("Install")
            }
        }

        InstallStatus(
            message = state.message,
            isError = state.isError,
            onRetry = if (installText.isNotBlank() && !state.isBusy) onInstall else null,
        )

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

/**
 * The install/enable/remove outcome line. It was a bare coloured `bodySmall` that appeared and
 * vanished instantly, shoving the add-on list by its own height on every state change, and an
 * *error* stated the failure with no way out but retyping the input.
 *
 * Now a filled status card on the matching container role (`errorContainer` for a failure, a plain
 * `surfaceContainerHigh` for progress/success), animating in on the Expressive springs — the height
 * change is spatial, the fade and colour are effects — and carrying **Try again** when the failure
 * is retryable, i.e. when the field still holds the input that failed and no install is in flight.
 *
 * [message] is retained through the exit animation so the card fades out with its own text rather
 * than blanking a frame early.
 */
@Composable
private fun InstallStatus(
    message: String?,
    isError: Boolean,
    onRetry: (() -> Unit)?,
) {
    val haptics = rememberTangentHaptics()
    var lastShown by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    if (message != null) lastShown = message to isError
    val shown = lastShown

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(ExpressiveMotion.defaultEffectsSpec()) +
            expandVertically(ExpressiveMotion.defaultSpatialSpec()),
        exit = fadeOut(ExpressiveMotion.defaultEffectsSpec()) +
            shrinkVertically(ExpressiveMotion.defaultSpatialSpec()),
    ) {
        if (shown == null) return@AnimatedVisibility
        val (text, error) = shown
        val container by animateColorAsState(
            targetValue = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            animationSpec = ExpressiveMotion.defaultEffectsSpec(),
            label = "installStatusContainer",
        )
        val content = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(container)
                .padding(start = 14.dp, end = if (error && onRetry != null) 6.dp else 14.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (error) Icons.Default.ErrorOutline else Icons.Default.Info,
                contentDescription = null,
                tint = content,
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = content,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (error && onRetry != null) {
                TextButton(onClick = { haptics.softClick(); onRetry() }) {
                    Text("Try again", color = content)
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
    val haptics = rememberTangentHaptics()

    val item: @Composable () -> Unit = {
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
                    if (addon.isBuiltIn) add("Built in")
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
                        onCheckedChange = {
                            haptics.toggle(it)
                            onEnabledChange(it)
                        },
                    )
                    AddonOverflow(
                        addon = addon,
                        onOpenSettings = onOpenSettings,
                        onOpenQuickSettings = onOpenQuickSettings,
                        onRemove = { pendingRemoval = true },
                    )
                }
            },
        )
    }

    // Two ways to remove, both ending in the same confirm dialog (the row springs back until
    // confirmed, since uninstalling loses the add-on's data): the swipe every other list sheet uses,
    // and the row overflow menu, which is where an undiscoverable gesture gets a visible twin
    // without a destructive button sitting permanently on every row.
    // Built-in extensions (reader, dark mode, data saver, passkey bridge) back app features and are
    // reinstalled on every launch, so they get neither affordance — see AddonController.uninstall.
    if (addon.isBuiltIn) {
        item()
    } else {
        SwipeToRemoveRow(
            onRemove = { pendingRemoval = true },
            settleOnRemove = false,
            content = item,
        )
    }

    if (pendingRemoval) {
        AlertDialog(
            onDismissRequest = { pendingRemoval = false },
            title = { Text("Remove ${addon.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.remove()
                        pendingRemoval = false
                        onUninstall()
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
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

/**
 * The row's single overflow control: settings entries plus Remove, which is where a destructive
 * action belongs — one always-visible trash icon per row invites the mis-tap it can't undo.
 * Renders nothing when there is nothing to offer (a built-in add-on with no options page).
 */
@Composable
private fun AddonOverflow(
    addon: InstalledAddon,
    onOpenSettings: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptics = rememberTangentHaptics()
    val showQuickSettings = addon.isEnabled && addon.hasQuickSettings
    val showSettings = addon.optionsPageUrl != null
    if (!showQuickSettings && !showSettings && addon.isBuiltIn) return

    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { haptics.softClick(); open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options for ${addon.name}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showQuickSettings) {
                DropdownMenuItem(
                    text = { Text("Quick settings") },
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    onClick = { open = false; haptics.softClick(); onOpenQuickSettings() },
                )
            }
            if (showSettings) {
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = { open = false; haptics.softClick(); onOpenSettings() },
                )
            }
            if (!addon.isBuiltIn) {
                DropdownMenuItem(
                    text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { open = false; onRemove() },
                )
            }
        }
    }
}
