package com.firebolt9907.tangentbrowser.ui.containers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Deck
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nature
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.state.ContainerRepository
import com.firebolt9907.tangentbrowser.state.TangentTab
import com.firebolt9907.tangentbrowser.state.TabRepository
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import mozilla.components.browser.state.state.ContainerState

/**
 * UI name for the implicit default cookie jar (`contextId == null`). The default jar is not a real
 * [ContainerState], but everywhere the user picks or sees a container we present it as a first-class
 * entry named "Personal" (its swatch is [DefaultContainerSwatch]) instead of "No container"/"None".
 */
const val DefaultContainerName = "Personal"

/** Neutral swatch for the "Personal" default container (`contextId == null`). */
val DefaultContainerSwatch = Color(0xFF64748B)

/** Display name for a tab/group's container: a real container's name, else "Personal" for null. */
fun containerNameFor(contextId: String?, containers: List<ContainerState>): String =
    contextId?.let { id -> containers.firstOrNull { it.contextId == id }?.name?.ifBlank { "Container" } }
        ?: DefaultContainerName

/** Display swatch for a tab/group's container: a real container's colour, else the Personal swatch. */
fun containerSwatchFor(contextId: String?, containers: List<ContainerState>): Color =
    contextId?.let { id -> containers.firstOrNull { it.contextId == id }?.color?.swatch() }
        ?: DefaultContainerSwatch

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

/** Firefox container icon identities mapped to Material glyphs (drawn white on the colour swatch). */
fun ContainerState.Icon.material(): ImageVector = when (this) {
    ContainerState.Icon.FINGERPRINT -> Icons.Filled.Fingerprint
    ContainerState.Icon.BRIEFCASE -> Icons.Filled.BusinessCenter
    ContainerState.Icon.DOLLAR -> Icons.Filled.AttachMoney
    ContainerState.Icon.CART -> Icons.Filled.ShoppingCart
    ContainerState.Icon.CIRCLE -> Icons.Filled.Circle
    ContainerState.Icon.GIFT -> Icons.Filled.CardGiftcard
    ContainerState.Icon.VACATION -> Icons.Filled.BeachAccess
    ContainerState.Icon.FOOD -> Icons.Filled.Restaurant
    ContainerState.Icon.FRUIT -> Icons.Filled.Nature
    ContainerState.Icon.PET -> Icons.Filled.Pets
    ContainerState.Icon.TREE -> Icons.Filled.Nature
    ContainerState.Icon.CHILL -> Icons.Filled.Deck
    ContainerState.Icon.FENCE -> Icons.Filled.Fence
}

/** User-selectable icons (in picker order). */
private val ContainerIconChoices = listOf(
    ContainerState.Icon.CIRCLE,
    ContainerState.Icon.BRIEFCASE,
    ContainerState.Icon.DOLLAR,
    ContainerState.Icon.CART,
    ContainerState.Icon.FINGERPRINT,
    ContainerState.Icon.GIFT,
    ContainerState.Icon.VACATION,
    ContainerState.Icon.FOOD,
    ContainerState.Icon.PET,
    ContainerState.Icon.TREE,
    ContainerState.Icon.CHILL,
    ContainerState.Icon.FENCE,
)

/** User-selectable colours (TOOLBAR is a system value, omitted from the picker). */
private val ContainerColorChoices =
    ContainerState.Color.entries.filter { it != ContainerState.Color.TOOLBAR }

/**
 * Management surface for **containers** (contextual identities): create / edit / delete isolated
 * cookie jars, see how many tabs each holds, jump into those tabs, open a new tab inside one, or
 * close all of a container's tabs at once. Unlike a tab group, a container isolates
 * cookies/storage/logins at the GeckoView level — signing into a site in "Work" keeps it separate
 * from "Personal".
 *
 * [onNewTabInContainer] opens a fresh tab in the given container (contextId) and closes the overview;
 * [onOpenTab] selects an existing tab; [onCloseTabs] bulk-closes a container's tabs.
 */
@Composable
fun ContainersSheet(
    repository: ContainerRepository,
    tabRepository: TabRepository,
    onNewTabInContainer: (contextId: String) -> Unit,
    onOpenTab: (tabId: String) -> Unit,
    onCloseTabs: (ids: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val containers by repository.containers.collectAsStateWithLifecycle()
    val tabs by tabRepository.tabs.collectAsStateWithLifecycle()
    val haptics = rememberTangentHaptics()
    // Tabs grouped by the container they're isolated in (null contextId = default context, not shown).
    val tabsByContext = remember(tabs) { tabs.filter { it.contextId != null }.groupBy { it.contextId } }

    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ContainerState?>(null) }
    // Deleting a container drops an isolated cookie/login jar — confirm first (like the app's other
    // destructive actions), rather than wiping it on a single tap of the trash icon.
    var showDeleteConfirm by remember { mutableStateOf<ContainerState?>(null) }
    var showCloseConfirm by remember { mutableStateOf<ContainerState?>(null) }

    TangentModalSheet(onDismissRequest = onDismiss, scrollable = true) {
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
            val containerTabs = tabsByContext[container.contextId].orEmpty()
            ContainerRow(
                container = container,
                tabs = containerTabs,
                onNewTab = { onNewTabInContainer(container.contextId); onDismiss() },
                onOpenTab = { onOpenTab(it); onDismiss() },
                onEdit = { editTarget = container },
                onCloseTabs = { showCloseConfirm = container },
                onDelete = { showDeleteConfirm = container },
            )
            Spacer(Modifier.size(10.dp))
        }
    }

    if (showCreate) {
        ContainerEditDialog(
            title = "New Container",
            confirmLabel = "Create",
            initialName = "",
            initialColor = ContainerState.Color.BLUE,
            initialIcon = ContainerState.Icon.CIRCLE,
            onConfirm = { name, color, icon ->
                repository.createContainer(name.ifBlank { "Container" }, color, icon)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    editTarget?.let { target ->
        ContainerEditDialog(
            title = "Edit Container",
            confirmLabel = "Save",
            initialName = target.name,
            initialColor = target.color,
            initialIcon = target.icon,
            onConfirm = { name, color, icon ->
                repository.updateContainer(target.contextId, name.ifBlank { target.name }, color, icon)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }

    showCloseConfirm?.let { container ->
        val name = container.name.ifBlank { "Container" }
        val ids = tabsByContext[container.contextId].orEmpty().map { it.id }
        AlertDialog(
            onDismissRequest = { showCloseConfirm = null },
            title = { Text("Close tabs?") },
            text = { Text("Close all ${ids.size} tab(s) open in the “$name” container? The container itself is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.remove()
                    onCloseTabs(ids)
                    showCloseConfirm = null
                }) {
                    Text("Close tabs", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = null }) { Text("Cancel") }
            },
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
    tabs: List<TangentTab>,
    onNewTab: () -> Unit,
    onOpenTab: (tabId: String) -> Unit,
    onEdit: () -> Unit,
    onCloseTabs: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = container.color.swatch()
    // Expandable to reveal the container's open tabs; collapsed by default to keep the list scannable.
    var expanded by remember { mutableStateOf(false) }
    // Rotating chevron rather than swapped up/down glyphs — the same disclosure idiom as the aA
    // sheet's collapsible sections (see CollapsibleSection in AaSheetComponents).
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "containerChevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable(enabled = tabs.isNotEmpty()) { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    container.icon.material(),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = tabs.isNotEmpty()) { expanded = !expanded },
            ) {
                Text(
                    text = container.name.ifBlank { "Container" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (tabs.isEmpty()) "No open tabs" else "${tabs.size} tab${if (tabs.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Disclosure indicator: without it nothing told the user the row expands at all, or
            // whether it currently is. Only shown when there are tabs to reveal — the same
            // condition that enables the tap targets above.
            if (tabs.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation),
                )
            }
            IconButton(onClick = onNewTab) {
                Icon(Icons.Default.Add, contentDescription = "New tab in this container")
            }
            ContainerOverflow(
                hasTabs = tabs.isNotEmpty(),
                onEdit = onEdit,
                onCloseTabs = onCloseTabs,
                onDelete = onDelete,
            )
        }
        AnimatedVisibility(visible = expanded && tabs.isNotEmpty()) {
            Column(Modifier.padding(top = 6.dp)) {
                tabs.forEach { tab ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenTab(tab.id) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = tab.title.ifBlank { tab.url },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerOverflow(
    hasTabs: Boolean,
    onEdit: () -> Unit,
    onCloseTabs: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Container options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { open = false; onEdit() },
            )
            if (hasTabs) {
                DropdownMenuItem(
                    text = { Text("Close all tabs") },
                    onClick = { open = false; onCloseTabs() },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete container") },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

@Composable
private fun ContainerEditDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialColor: ContainerState.Color,
    initialIcon: ContainerState.Icon,
    onConfirm: (name: String, color: ContainerState.Color, icon: ContainerState.Icon) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var icon by remember { mutableStateOf(initialIcon) }
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
                    ContainerColorChoices.forEach { c ->
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
                Spacer(Modifier.size(12.dp))
                // Icon picker: a wrapping set of the container glyphs, tinted with the chosen colour.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ContainerIconChoices.forEach { i ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .then(
                                    if (i == icon) {
                                        Modifier.border(2.dp, color.swatch(), CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .semantics {
                                    contentDescription = i.name.lowercase().replaceFirstChar { it.uppercase() }
                                    selected = i == icon
                                    role = Role.RadioButton
                                }
                                .clickable { icon = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                i.material(),
                                contentDescription = null,
                                tint = if (i == icon) color.swatch() else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, color, icon) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
