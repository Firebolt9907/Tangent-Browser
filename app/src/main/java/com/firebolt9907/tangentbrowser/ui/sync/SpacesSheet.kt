package com.firebolt9907.tangentbrowser.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.sync.zen.ZenWorkspacesSnapshot
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
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
    TangentModalSheet(onDismissRequest = onDismiss, bottomPadding = 12.dp) {
      Column(Modifier.fillMaxHeight(0.94f)) {
        SheetHeader(
            title = "Spaces",
            leadingIcon = { Icon(Icons.Default.ViewCarousel, contentDescription = null) },
        )
        Spacer(Modifier.height(16.dp))
        if (snapshot.spaces.isEmpty()) {
            EmptyState(
                icon = Icons.Default.ViewCarousel,
                title = "No synced spaces yet",
                description = "Spaces from Zen on your other devices will appear here.",
            )
        } else {
            SpacesCarousel(
                modifier = Modifier.weight(1f),
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
}
