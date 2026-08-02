package com.firebolt9907.tangentbrowser.ui.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.firebolt9907.tangentbrowser.ui.theme.titleLargeEmphasized
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.addons.AddonPermissionPrompt

/**
 * The add-on install/permission prompt — the "review what this add-on can access" sheet content and
 * its human-readable permission/origin strings. Split out of `AddonsSheet.kt` so that file holds the
 * add-on manager UI and this holds the permission-review flow.
 */
@Composable
internal fun AddonPermissionSheetContent(
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
        Text(prompt.title, style = MaterialTheme.typography.titleLargeEmphasized)
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
