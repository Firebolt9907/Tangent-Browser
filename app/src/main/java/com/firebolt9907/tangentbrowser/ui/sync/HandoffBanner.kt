package com.firebolt9907.tangentbrowser.ui.sync

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.sync.FirefoxHandoffTab
import com.firebolt9907.tangentbrowser.sync.FirefoxReceivedTab
import com.firebolt9907.tangentbrowser.ui.BannerStackState
import com.firebolt9907.tangentbrowser.ui.MorphingBannerHost
import com.firebolt9907.tangentbrowser.ui.bannerSwipeToDismiss
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape

/**
 * Handoff-style banner floating above the bottom bar: offers to continue a tab that was just
 * active on another Firefox Sync device. Tapping the card opens the tab; the X dismisses it
 * (sticky per device+URL, see FirefoxSyncController.dismissHandoff).
 *
 * Rendered as a fillMaxSize overlay (like BottomBarLayer) so it can bottom-align itself from any
 * call site inside the root content Box.
 */
@Composable
fun HandoffBannerHost(
    handoff: FirefoxHandoffTab?,
    visible: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    stack: BannerStackState? = null,
    onOpen: (FirefoxHandoffTab) -> Unit,
    onDismiss: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible && handoff != null,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        stackId = "handoff",
        stackPriority = 4,
    ) {
        // The banner stays mounted through the exit morph, after the candidate has been cleared from
        // state — hold the last one so the card doesn't blank out mid-morph.
        var lastHandoff by remember { mutableStateOf(handoff) }
        if (handoff != null) lastHandoff = handoff
        lastHandoff?.let { tab ->
            HandoffCard(tab = tab, onOpen = { onOpen(tab) }, onDismiss = onDismiss)
        }
    }
}

/**
 * Transient "Send to device" prompt floating above the bottom bar, shown right after the user
 * copies the current link. Offers to push that link to their most-recently-used other Firefox
 * Sync device (via Send Tab). Auto-dismisses on a timer from the call site; tapping sends and the
 * X dismisses. Only shown when signed in with at least one reachable target device.
 */
@Composable
fun SendToDeviceBannerHost(
    deviceName: String?,
    deviceType: String?,
    visible: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    stack: BannerStackState? = null,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible && deviceName != null,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        stackId = "sendToDevice",
        stackPriority = 2,
    ) {
        // Hold the last name/type so the card doesn't blank out mid-exit-morph after state clears.
        var lastName by remember { mutableStateOf(deviceName) }
        var lastType by remember { mutableStateOf(deviceType) }
        if (deviceName != null) {
            lastName = deviceName
            lastType = deviceType
        }
        lastName?.let { name ->
            SendToDeviceCard(
                deviceName = name,
                deviceType = lastType.orEmpty(),
                onSend = onSend,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * Manual "paste code" fallback for SMS one-time-code autofill, floating above the bottom bar (same
 * placement as [SendToDeviceBannerHost]). Shown when the page reports an OTP field; tapping it reads
 * a code from the clipboard (Google Messages puts detected codes there) and fills it — covering the
 * cases SMS User Consent can't (sender in contacts, code already received, its own content filter).
 */
@Composable
fun OtpPasteBannerHost(
    visible: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    stack: BannerStackState? = null,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        stackId = "otpPaste",
        stackPriority = 1,
    ) {
        Surface(
            shape = SquircleShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .bannerSwipeToDismiss(onDismiss),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPaste)
                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Sms,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enter code from SMS",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Tap to paste the code from your clipboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SendToDeviceCard(
    deviceName: String,
    deviceType: String,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = SquircleShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .widthIn(max = 480.dp)
            .bannerSwipeToDismiss(onDismiss),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSend)
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = deviceIcon(deviceType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Link copied",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Send to $deviceName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun HandoffCard(
    tab: FirefoxHandoffTab,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = SquircleShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .widthIn(max = 480.dp)
            .bannerSwipeToDismiss(onDismiss),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = deviceIcon(tab.deviceType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continue from ${tab.deviceName}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tab.title.ifBlank { tab.url },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss handoff",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Banner floating above the bottom bar announcing a tab another device just pushed here via Firefox
 * "Send tab". Tapping the card opens the tab; the X dismisses it. Shown while the app is foreground;
 * the call site auto-retires it on a timer. Complements the system notification (which covers the
 * backgrounded case).
 */
@Composable
fun ReceivedTabBannerHost(
    received: FirefoxReceivedTab?,
    visible: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    stack: BannerStackState? = null,
    onOpen: (FirefoxReceivedTab) -> Unit,
    onDismiss: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible && received != null,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        stackId = "receivedTab",
        stackPriority = 3,
    ) {
        // Hold the last value so the card doesn't blank out mid-exit-morph after state clears.
        var last by remember { mutableStateOf(received) }
        if (received != null) last = received
        last?.let { tab ->
            ReceivedTabCard(tab = tab, onOpen = { onOpen(tab) }, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun ReceivedTabCard(
    tab: FirefoxReceivedTab,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = SquircleShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .widthIn(max = 480.dp)
            .bannerSwipeToDismiss(onDismiss),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = deviceIcon(tab.deviceType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tab from ${tab.deviceName ?: "another device"}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tab.title.ifBlank { tab.url },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Icon for a synced device. The handoff/received banners pass an inferred label
// (Mobile/Tablet/Desktop); the send-to-device banner passes the RAW constellation type, so a device
// that reports DeviceType.UNKNOWN (common for non-Firefox sync clients) shows a question mark rather
// than a guessed computer.
private fun deviceIcon(deviceType: String) = when (deviceType.lowercase()) {
    "tablet" -> Icons.Default.TabletAndroid
    "mobile" -> Icons.Default.Smartphone
    "desktop" -> Icons.Default.Computer
    else -> Icons.Default.QuestionMark
}
