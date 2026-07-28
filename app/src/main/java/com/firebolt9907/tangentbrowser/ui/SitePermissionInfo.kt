package com.firebolt9907.tangentbrowser.ui

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
import androidx.compose.ui.graphics.vector.ImageVector
import com.firebolt9907.tangentbrowser.permissions.ManagedPermission
import mozilla.components.concept.engine.permission.Permission

/**
 * How a single site permission is presented: the noun [phrase] that slots into "Allow <host> to use
 * <phrase>?" (carries its own determiner, e.g. "your camera"), a one-line [explanation] of what
 * granting it means, and the [icon] shown alongside.
 *
 * Shared by the permission request banner ([SitePermissionBannerHost]) and the aA sheet's
 * site-permissions section so both render identical copy and iconography. Every GeckoView
 * content-permission type (see `Permission.Content*`) must map to a real label, explanation, and icon
 * here — anything falling through to [GenericPermission] reads as the uninformative "wants to use this
 * feature", leaving the user unable to make a sane allow/block decision.
 */
data class PermissionInfo(
    val phrase: String,
    val explanation: String,
    val icon: ImageVector,
)

val GenericPermission = PermissionInfo(
    phrase = "this feature",
    explanation = "The site is requesting a browser permission.",
    icon = Icons.Filled.Lock,
)

fun infoFor(permission: Permission): PermissionInfo = when (permission) {
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

fun describePermissions(permissions: List<Permission>): PermissionInfo {
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

/** The icon shown next to a [ManagedPermission] row in the aA sheet's site-permissions section. */
fun iconFor(permission: ManagedPermission): ImageVector = when (permission) {
    ManagedPermission.Location -> Icons.Filled.LocationOn
    ManagedPermission.Camera -> Icons.Filled.Videocam
    ManagedPermission.Microphone -> Icons.Filled.Mic
    ManagedPermission.Notifications -> Icons.Filled.Notifications
    ManagedPermission.PersistentStorage -> Icons.Filled.Storage
    ManagedPermission.DrmMedia -> Icons.Filled.PlayArrow
    ManagedPermission.LocalNetwork -> Icons.Filled.Lan
    ManagedPermission.LocalDevice -> Icons.Filled.Usb
}
