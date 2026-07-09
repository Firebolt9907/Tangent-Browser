package com.firebolt9907.luminabrowser.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.luminabrowser.permissions.SitePermissionController
import mozilla.components.concept.engine.permission.Permission

/**
 * The site content-permission prompt (geolocation, camera, microphone, notifications…). Extracted
 * from `LuminaRoot.kt` — it depends only on the [SitePermissionController], not on any of the root's
 * gesture state, so it lives cleanly on its own. The controller applies remembered decisions and the
 * autoplay policy before this ever shows; this only surfaces genuinely-new requests.
 */
@Composable
internal fun SitePermissionDialog(
    controller: SitePermissionController,
) {
    val request by controller.contentRequest.collectAsStateWithLifecycle()
    val current = request ?: return
    val host = remember(current.uri) {
        runCatching { current.uri?.toUri()?.host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: current.uri?.takeIf { it.isNotBlank() }
            ?: "This site"
    }
    val what = remember(current) { describeContentPermissions(current.permissions) }
    AlertDialog(
        onDismissRequest = { controller.rejectCurrent() },
        icon = {
            Icon(
                imageVector = permissionIcon(current.permissions),
                contentDescription = null,
            )
        },
        title = { Text("Allow $what?") },
        text = { Text("$host wants to use your $what.") },
        confirmButton = {
            TextButton(onClick = { controller.grantCurrent() }) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = { controller.rejectCurrent() }) {
                Text("Block")
            }
        },
    )
}

private fun permissionIcon(permissions: List<Permission>): ImageVector {
    permissions.forEach { perm ->
        when (perm) {
            is Permission.ContentGeoLocation -> return Icons.Filled.LocationOn
            is Permission.ContentVideoCamera,
            is Permission.ContentVideoCapture -> return Icons.Filled.Videocam
            is Permission.ContentAudioCapture,
            is Permission.ContentAudioMicrophone -> return Icons.Filled.Mic
            is Permission.ContentNotification -> return Icons.Filled.Notifications
            is Permission.ContentPersistentStorage -> return Icons.Filled.Storage
            is Permission.ContentAutoPlayAudible,
            is Permission.ContentAutoPlayInaudible -> return Icons.Filled.PlayArrow
            else -> Unit
        }
    }
    return Icons.Filled.Lock
}

private fun describeContentPermissions(permissions: List<Permission>): String {
    val labels = permissions.map { perm ->
        when (perm) {
            is Permission.ContentGeoLocation -> "location"
            is Permission.ContentVideoCamera,
            is Permission.ContentVideoCapture -> "camera"
            is Permission.ContentAudioCapture,
            is Permission.ContentAudioMicrophone -> "microphone"
            is Permission.ContentNotification -> "notifications"
            is Permission.ContentPersistentStorage -> "persistent storage"
            is Permission.ContentAutoPlayAudible,
            is Permission.ContentAutoPlayInaudible -> "autoplay media"
            else -> "this device feature"
        }
    }.distinct()
    return if (labels.isEmpty()) "this device feature" else labels.joinToString(" and ")
}
