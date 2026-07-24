package com.firebolt9907.tangentbrowser.ui.sync

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.sync.zen.ZenWorkspacesSnapshot
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader

/**
 * Focused bottom sheet showing just the Zen [SpacesCarousel], summoned by swiping up on the
 * tab-overview toolbar (Arc mobile's space switcher gesture). Kept separate from [SyncedTabsSheet]
 * so the gesture lands directly on the spaces deck instead of the full Firefox Sync surface.
 */
@Composable
fun SpacesSheet(
    snapshot: ZenWorkspacesSnapshot,
    onDismiss: () -> Unit,
    onOpenTab: (String) -> Unit,
) {
    TangentModalSheet(onDismissRequest = onDismiss) {
        SheetHeader(
            title = "Spaces",
            leadingIcon = { Icon(Icons.Default.ViewCarousel, contentDescription = null) },
        )
        Spacer(Modifier.height(16.dp))
        if (snapshot.spaces.isEmpty()) {
            Text(
                text = "No synced spaces yet. Spaces from Zen on your other devices will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            SpacesCarousel(
                spaces = snapshot.spaces,
                containers = snapshot.containers,
                onOpenTab = { url ->
                    onOpenTab(url)
                    onDismiss()
                }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
