package com.firebolt9907.luminabrowser.ui.groups

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.firebolt9907.luminabrowser.state.LuminaTab
import com.firebolt9907.luminabrowser.state.LuminaTabGroup
import com.firebolt9907.luminabrowser.state.TabGroupColor
import com.firebolt9907.luminabrowser.state.TabGroupsRepository
import com.firebolt9907.luminabrowser.state.TabRepository
import com.firebolt9907.luminabrowser.ui.components.EmptyState
import com.firebolt9907.luminabrowser.ui.components.LuminaModalSheet
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import com.firebolt9907.luminabrowser.ui.components.SheetSectionHeader
import com.firebolt9907.luminabrowser.ui.containers.swatch
import mozilla.components.browser.state.state.ContainerState

/** Concrete swatch for each [TabGroupColor]. Kept in the UI layer so the state enum stays Compose-free. */
fun TabGroupColor.swatch(): Color = when (this) {
    TabGroupColor.GRAY -> Color(0xFF8E8E93)
    TabGroupColor.BLUE -> Color(0xFF3B82F6)
    TabGroupColor.CYAN -> Color(0xFF06B6D4)
    TabGroupColor.GREEN -> Color(0xFF22C55E)
    TabGroupColor.YELLOW -> Color(0xFFEAB308)
    TabGroupColor.ORANGE -> Color(0xFFF97316)
    TabGroupColor.RED -> Color(0xFFEF4444)
    TabGroupColor.PINK -> Color(0xFFEC4899)
    TabGroupColor.PURPLE -> Color(0xFF8B5CF6)
}

/**
 * Management surface for organizational [LuminaTabGroup]s. Lets the user create/rename/recolor/delete
 * groups, assign open tabs to a group, and jump to a tab. Deliberately a standalone sheet (like the
 * other library sheets) rather than being woven into the gesture-heavy tab overview.
 *
 * Grouping is a label only — it does not isolate cookies (that's a container). [onOpenTab] selects a
 * tab and is expected to close the overview.
 */
@Composable
fun TabGroupsSheet(
    repository: TabGroupsRepository,
    tabRepository: TabRepository,
    // Available containers, so a group can choose the container its new tabs default to.
    containers: List<ContainerState>,
    onOpenTab: (tabId: String) -> Unit,
    // Open a fresh tab into the group (in the group's default container, if any) and close the overview.
    onNewTabInGroup: (groupId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups by repository.groups.collectAsStateWithLifecycle()
    val tabs by tabRepository.tabs.collectAsStateWithLifecycle()
    val tabsById = remember(tabs) { tabs.associateBy { it.id } }
    val groupedIds = remember(groups) { groups.flatMap { it.tabIds }.toHashSet() }
    val ungrouped = remember(tabs, groupedIds) { tabs.filter { it.id !in groupedIds } }

    var showCreate by remember { mutableStateOf(false) }
    // Group id awaiting a rename dialog, or null.
    var renameTarget by remember { mutableStateOf<LuminaTabGroup?>(null) }

    LuminaModalSheet(onDismissRequest = onDismiss, scrollable = true) {
        SheetHeader(
            title = "Tab Groups",
            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            trailing = {
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New group")
                }
            },
        )
        Spacer(Modifier.size(12.dp))

        if (groups.isEmpty() && ungrouped.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Folder,
                title = "No tabs to group",
                description = "Open a few tabs, then group them here.",
            )
            return@LuminaModalSheet
        }

        groups.forEach { group ->
            GroupCard(
                group = group,
                tabsById = tabsById,
                containers = containers,
                onOpenTab = { onOpenTab(it); onDismiss() },
                onNewTab = { onNewTabInGroup(group.id) },
                onRemoveTab = { repository.removeTabFromGroup(it) },
                onRename = { renameTarget = group },
                onRecolor = { repository.setColor(group.id, it) },
                onSetDefaultContainer = { repository.setDefaultContainer(group.id, it) },
                onDelete = { repository.deleteGroup(group.id) },
            )
            Spacer(Modifier.size(12.dp))
        }

        if (ungrouped.isNotEmpty()) {
            SheetSectionHeader("Ungrouped")
            Spacer(Modifier.size(6.dp))
            ungrouped.forEach { tab ->
                TabRow(
                    tab = tab,
                    accent = null,
                    onOpen = { onOpenTab(tab.id); onDismiss() },
                    trailing = {
                        if (groups.isEmpty()) {
                            // Nothing to add to yet — offer a one-tap "create group with this tab".
                            IconButton(onClick = {
                                val id = repository.createGroup("New Group", TabGroupColor.BLUE)
                                repository.addTabToGroup(tab.id, id)
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add to a new group")
                            }
                        } else {
                            AddToGroupButton(
                                groups = groups,
                                onPick = { repository.addTabToGroup(tab.id, it) },
                            )
                        }
                    },
                )
            }
        }
    }

    if (showCreate) {
        GroupEditDialog(
            title = "New Group",
            initialName = "",
            initialColor = TabGroupColor.BLUE,
            onConfirm = { name, color ->
                repository.createGroup(name.ifBlank { "New Group" }, color)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    renameTarget?.let { target ->
        GroupEditDialog(
            title = "Rename Group",
            initialName = target.name,
            initialColor = target.color,
            onConfirm = { name, color ->
                repository.renameGroup(target.id, name.ifBlank { target.name })
                repository.setColor(target.id, color)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun GroupCard(
    group: LuminaTabGroup,
    tabsById: Map<String, LuminaTab>,
    containers: List<ContainerState>,
    onOpenTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onRemoveTab: (String) -> Unit,
    onRename: () -> Unit,
    onRecolor: (TabGroupColor) -> Unit,
    onSetDefaultContainer: (contextId: String?) -> Unit,
    onDelete: () -> Unit,
) {
    val accent = group.color.swatch()
    val defaultContainer = containers.firstOrNull { it.contextId == group.defaultContextId }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.name.ifBlank { "Group" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Surface the group's default container so its effect (new tabs open isolated) is visible.
                if (defaultContainer != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(defaultContainer.color.swatch()),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = defaultContainer.name.ifBlank { "Container" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                text = "${group.tabIds.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onNewTab) {
                Icon(Icons.Default.Add, contentDescription = "New tab in group")
            }
            GroupOverflow(
                current = group.color,
                containers = containers,
                currentDefaultContextId = group.defaultContextId,
                onRename = onRename,
                onRecolor = onRecolor,
                onSetDefaultContainer = onSetDefaultContainer,
                onDelete = onDelete,
            )
        }
        if (group.tabIds.isEmpty()) {
            Text(
                text = "No tabs — add some from Ungrouped below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 2.dp),
            )
        }
        group.tabIds.mapNotNull { tabsById[it] }.forEach { tab ->
            TabRow(
                tab = tab,
                accent = accent,
                onOpen = { onOpenTab(tab.id) },
                trailing = {
                    IconButton(onClick = { onRemoveTab(tab.id) }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove from group",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun TabRow(
    tab: LuminaTab,
    accent: Color?,
    onOpen: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent ?: MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = tab.title.ifBlank { tab.url },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tab.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun AddToGroupButton(
    groups: List<LuminaTabGroup>,
    onPick: (groupId: String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Add, contentDescription = "Add to group")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            groups.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.name.ifBlank { "Group" }) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(g.color.swatch()),
                        )
                    },
                    onClick = { open = false; onPick(g.id) },
                )
            }
        }
    }
}

@Composable
private fun GroupOverflow(
    current: TabGroupColor,
    containers: List<ContainerState>,
    currentDefaultContextId: String?,
    onRename: () -> Unit,
    onRecolor: (TabGroupColor) -> Unit,
    onSetDefaultContainer: (contextId: String?) -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var colorOpen by remember { mutableStateOf(false) }
    var containerOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Group options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { open = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Change color") },
                onClick = { open = false; colorOpen = true },
            )
            if (containers.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Default container") },
                    onClick = { open = false; containerOpen = true },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete group") },
                onClick = { open = false; onDelete() },
            )
        }
        // Default-container chooser: "None" plus each container, with a check on the current choice.
        DropdownMenu(expanded = containerOpen, onDismissRequest = { containerOpen = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                trailingIcon = if (currentDefaultContextId == null) {
                    { Icon(Icons.Default.Check, contentDescription = "Selected") }
                } else null,
                onClick = { containerOpen = false; onSetDefaultContainer(null) },
            )
            containers.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name.ifBlank { "Container" }) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(c.color.swatch()),
                        )
                    },
                    trailingIcon = if (currentDefaultContextId == c.contextId) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else null,
                    onClick = { containerOpen = false; onSetDefaultContainer(c.contextId) },
                )
            }
        }
        DropdownMenu(expanded = colorOpen, onDismissRequest = { colorOpen = false }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                TabGroupColor.entries.forEach { c ->
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(c.swatch())
                            .then(
                                if (c == current) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                            .semantics {
                                contentDescription = c.name.lowercase().replaceFirstChar { it.uppercase() }
                                selected = c == current
                                role = Role.RadioButton
                            }
                            .clickable { colorOpen = false; onRecolor(c) },
                    )
                }
            }
        }
    }
}

/** New-group / rename dialog: a name field plus a colour picker row. */
@Composable
private fun GroupEditDialog(
    title: String,
    initialName: String,
    initialColor: TabGroupColor,
    onConfirm: (name: String, color: TabGroupColor) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
                    TabGroupColor.entries.forEach { c ->
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
        confirmButton = { TextButton(onClick = { onConfirm(name, color) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
