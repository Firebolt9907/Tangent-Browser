package com.firebolt9907.luminabrowser.ui.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.luminabrowser.state.ContainerRepository
import com.firebolt9907.luminabrowser.state.TabRepository
import com.firebolt9907.luminabrowser.ui.components.EmptyState
import com.firebolt9907.luminabrowser.ui.components.LuminaModalSheet
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import com.firebolt9907.luminabrowser.ui.rememberLuminaHaptics
import mozilla.components.browser.state.state.ContainerState

/** Firefox container colours mapped to concrete swatches. */
fun ContainerState.Color.swatch(): Color = when (this) {
    ContainerState.Color.BLUE -> Color(0xFF3B82F6)
    ContainerState.Color.TURQUOISE -> Color(0xFF06B6D4)
    ContainerState.Color.GREEN -> Color(0xFF22C55E)
    ContainerState.Color.YELLOW -> Color(0xFFEAB308)
    ContainerState.Color.ORANGE -> Color(0xFFF97316)
    ContainerState.Color.RED -> Color(0xFFEF4444)
    ContainerState.Color.PINK -> Color(0xFFEC4899)
    ContainerState.Color.PURPLE -> Color(0xFF8B5CF6)
    ContainerState.Color.TOOLBAR -> Color(0xFF8E8E93)
}

/**
 * Management surface for **containers** (contextual identities): create/delete isolated cookie jars
 * and open a new tab inside one. Unlike a tab group, a container isolates cookies/storage/logins at
 * the GeckoView level — signing into a site in "Work" keeps it separate from "Personal".
 *
 * [onNewTabInContainer] opens a fresh tab in the given container (contextId) and is expected to
 * close the overview.
 */
@Composable
fun ContainersSheet(
    repository: ContainerRepository,
    tabRepository: TabRepository,
    onNewTabInContainer: (contextId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val containers by repository.containers.collectAsStateWithLifecycle()
    val tabs by tabRepository.tabs.collectAsStateWithLifecycle()
    val haptics = rememberLuminaHaptics()

    var showCreate by remember { mutableStateOf(false) }
    // Deleting a container drops an isolated cookie/login jar — confirm first (like the app's other
    // destructive actions), rather than wiping it on a single tap of the trash icon.
    var showDeleteConfirm by remember { mutableStateOf<ContainerState?>(null) }

    LuminaModalSheet(onDismissRequest = onDismiss, scrollable = true) {
        SheetHeader(
            title = "Containers",
            leadingIcon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
            trailing = {
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New container")
                }
            },
        )
        Text(
            text = "Each container keeps its own cookies, logins and site data separate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (containers.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Shield,
                title = "No containers yet",
                description = "Create one for Work, Personal, Shopping, and so on.",
            )
        }

        containers.forEach { container ->
            // We only know a tab's contextId via the store; TabRepository's LuminaTab doesn't carry
            // it, so the count is best-effort based on what the repository can resolve. Kept simple:
            // show the container and a "new tab" action; membership detail lives in the tab overview.
            ContainerRow(
                container = container,
                onNewTab = { onNewTabInContainer(container.contextId); onDismiss() },
                onDelete = { showDeleteConfirm = container },
            )
            Spacer(Modifier.size(10.dp))
        }
    }

    if (showCreate) {
        ContainerCreateDialog(
            onConfirm = { name, color ->
                repository.createContainer(name.ifBlank { "Container" }, color)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    showDeleteConfirm?.let { container ->
        val name = container.name.ifBlank { "Container" }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete container?") },
            text = {
                Text("Delete the “$name” container? Sites you opened in it will no longer be kept separate.")
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.remove()
                    repository.removeContainer(container.contextId)
                    showDeleteConfirm = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ContainerRow(
    container: ContainerState,
    onNewTab: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(container.color.swatch()),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = container.name.ifBlank { "Container" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNewTab) {
            Icon(Icons.Default.Add, contentDescription = "New tab in this container")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete container")
        }
    }
}

@Composable
private fun ContainerCreateDialog(
    onConfirm: (name: String, color: ContainerState.Color) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(ContainerState.Color.BLUE) }
    // The user-selectable colours (TOOLBAR is a system value, omitted from the picker).
    val choices = remember { ContainerState.Color.entries.filter { it != ContainerState.Color.TOOLBAR } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Container") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    choices.forEach { c ->
                        Box(
                            Modifier
                                .padding(2.dp)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c.swatch())
                                .then(
                                    if (c == color) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .semantics {
                                    contentDescription = c.name.lowercase().replaceFirstChar { it.uppercase() }
                                    selected = c == color
                                    role = Role.RadioButton
                                }
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, color) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
