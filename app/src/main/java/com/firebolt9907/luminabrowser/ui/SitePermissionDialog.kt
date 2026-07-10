package com.firebolt9907.luminabrowser.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
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
 *
 * Every GeckoView content-permission type (see `GeckoPermissionRequest`) must map to a real label,
 * explanation, and icon here. Anything that falls through to the generic fallback reads as the
 * infamous "wants to use this feature" — uninformative, so the user can't make a sane allow/block
 * decision. If mozilla-components adds a new `Permission.Content*`, add a branch below.
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
    val info = remember(current) { describePermissions(current.permissions) }
    AlertDialog(
        onDismissRequest = { controller.rejectCurrent() },
        icon = {
            Icon(
                imageVector = info.icon,
                contentDescription = null,
            )
        },
        title = { Text("Allow $host to use ${info.phrase}?") },
        text = { Text(info.explanation) },
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

/**
 * How a single permission is presented: the noun [phrase] that slots into "Allow <host> to use
 * <phrase>?" (carries its own determiner, e.g. "your camera"), a one-line [explanation] of what
 * granting it means, and the [icon] shown above the dialog.
 */
private data class PermissionInfo(
    val phrase: String,
    val explanation: String,
    val icon: ImageVector,
)

private val GenericPermission = PermissionInfo(
    phrase = "this feature",
    explanation = "The site is requesting a browser permission.",
    icon = Icons.Filled.Lock,
)

private fun infoFor(permission: Permission): PermissionInfo = when (permission) {
    is Permission.ContentGeoLocation -> PermissionInfo(
        "your location",
        "The site will be able to see your device's location.",
        Icons.Filled.LocationOn,
    )
    is Permission.ContentVideoCamera, is Permission.ContentVideoCapture -> PermissionInfo(
        "your camera",
        "The site will be able to access your camera.",
        Icons.Filled.Videocam,
    )
    is Permission.ContentAudioCapture, is Permission.ContentAudioMicrophone -> PermissionInfo(
        "your microphone",
        "The site will be able to access your microphone.",
        Icons.Filled.Mic,
    )
    is Permission.ContentAudioOther -> PermissionInfo(
        "audio capture",
        "The site will be able to capture audio.",
        Icons.Filled.Mic,
    )
    is Permission.ContentVideoScreen -> PermissionInfo(
        "screen sharing",
        "The site will be able to capture and view your screen.",
        Icons.Filled.ScreenShare,
    )
    is Permission.ContentVideoOther -> PermissionInfo(
        "video capture",
        "The site will be able to capture video.",
        Icons.Filled.Videocam,
    )
    is Permission.ContentNotification -> PermissionInfo(
        "notifications",
        "The site will be able to send you notifications.",
        Icons.Filled.Notifications,
    )
    is Permission.ContentPersistentStorage -> PermissionInfo(
        "persistent storage",
        "The site can store data on your device that isn't auto-cleared.",
        Icons.Filled.Storage,
    )
    is Permission.ContentAutoPlayAudible, is Permission.ContentAutoPlayInaudible -> PermissionInfo(
        "autoplay media",
        "The site can start playing media automatically.",
        Icons.Filled.PlayArrow,
    )
    is Permission.ContentMediaKeySystemAccess -> PermissionInfo(
        "DRM-protected media",
        "The site wants to play DRM-protected media (e.g. paid or streaming video).",
        Icons.Filled.PlayArrow,
    )
    is Permission.ContentProtectedMediaId -> PermissionInfo(
        "a protected-media ID",
        "The site wants a device identifier used for DRM-protected media.",
        Icons.Filled.PlayArrow,
    )
    is Permission.ContentCrossOriginStorageAccess -> PermissionInfo(
        "cross-site cookies",
        "This site, embedded on another site, wants to use its own cookies — this can enable cross-site tracking.",
        Icons.Filled.Cookie,
    )
    is Permission.ContentLocalDeviceAccess -> PermissionInfo(
        "a local device",
        "The site wants to communicate with a device connected to your phone.",
        Icons.Filled.Usb,
    )
    is Permission.ContentLocalNetworkAccess -> PermissionInfo(
        "your local network",
        "The site wants to reach devices or servers on your local network. Legitimate for some apps, but ad and tracking scripts also abuse this to probe your network.",
        Icons.Filled.Lan,
    )
    else -> GenericPermission
}

private fun describePermissions(permissions: List<Permission>): PermissionInfo {
    val infos = permissions.map(::infoFor).distinctBy { it.phrase }
    return when {
        infos.isEmpty() -> GenericPermission
        infos.size == 1 -> infos.first()
        else -> {
            // Multiple distinct permissions in one request (e.g. camera + microphone). Join the
            // phrases, keep the first icon, and spell out both implications.
            val phrase = infos.joinToString(" and ") { it.phrase }
            val explanation = infos.joinToString(" ") { it.explanation }
            PermissionInfo(phrase, explanation, infos.first().icon)
        }
    }
}
