package com.firebolt9907.luminabrowser.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Long-press action sheet for a *bare* image (an `<img>` that isn't a link), giving the standard
 * Fenix image context actions the app was otherwise missing: open the image in a new tab, save it
 * to Downloads, copy its address, or share it. Linked images keep the existing link "glance"
 * (LinkPeekCard); this only fires for [mozilla.components.concept.engine.HitResult.IMAGE].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageContextSheet(
    imageUrl: String,
    onOpenNewTab: (String) -> Unit,
    onSearchImage: (String) -> Unit,
    onSave: (String) -> Unit,
    onCopyImage: (String) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                text = imageUrl,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ImageAction(Icons.AutoMirrored.Filled.OpenInNew, "Open image in new tab") {
                onOpenNewTab(imageUrl); onDismiss()
            }
            ImageAction(Icons.Rounded.ImageSearch, "Search image with Google") {
                onSearchImage(imageUrl); onDismiss()
            }
            ImageAction(Icons.Rounded.Download, "Save image") {
                onSave(imageUrl); onDismiss()
            }
            ImageAction(Icons.Rounded.Image, "Copy image") {
                onCopyImage(imageUrl); onDismiss()
            }
            ImageAction(Icons.Rounded.ContentCopy, "Copy image address") {
                onCopy(imageUrl); onDismiss()
            }
            ImageAction(Icons.Rounded.Share, "Share image") {
                onShare(imageUrl); onDismiss()
            }
        }
    }
}

@Composable
private fun ImageAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
