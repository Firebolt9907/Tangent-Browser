package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Open in <app>" banner floating above the address pill. Surfaced in two cases:
 *  - **requested** — the page actively tried to open a native app (an `intent://` / custom-scheme
 *    deep link caught by `TangentRequestInterceptor`); [appName] is the app it wants.
 *  - **passive** — the current site simply *has* an associated app (e.g. instagram.com → Instagram,
 *    resolved by `nativeAppFor`); offered as a suggestion.
 *
 * Shows the target app's real launcher icon ([packageName]) with a graceful fallback. Tapping the
 * card launches the app; the X (or a swipe) dismisses it. Replaces the old "Open in <app>" row that
 * was buried in the aA sheet. Joins the shared Now-Bar stack like the other banners.
 */
@Composable
fun OpenInAppBannerHost(
    appName: String?,
    packageName: String?,
    requested: Boolean,
    visible: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    // Grid-open progress (0..1): fade out with the pill as the tab switcher opens.
    gridProgress: () -> Float = { 0f },
    // Horizontal pill offset (px) during a tab-switch swipe — the banner tracks it so it slides with
    // the current tab's pill instead of sitting still. (Same wiring as the archive scrubber.)
    horizontalOffsetX: () -> Float = { 0f },
    // 0..1 fade applied only as the tab-switch settle completes, so the banner disappears exactly when
    // the switch finishes without a visible fade mid-swipe.
    switchFade: () -> Float = { 0f },
    stack: BannerStackState? = null,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible && appName != null,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        gridProgress = gridProgress,
        horizontalOffsetX = horizontalOffsetX,
        switchFade = switchFade,
        stack = stack,
        stackId = "openInApp",
        // Base of the stack (like the archive scrubber): this is a persistent banner, so transient
        // banners (OTP paste, send-to-device, received tab, handoff — priorities 1-4) pile in FRONT
        // of it rather than behind it.
        stackPriority = 0,
    ) {
        // Hold the last values so the card doesn't blank out mid-exit-morph after state clears.
        var lastName by remember { mutableStateOf(appName) }
        var lastPackage by remember { mutableStateOf(packageName) }
        var lastRequested by remember { mutableStateOf(requested) }
        if (appName != null) {
            lastName = appName
            lastPackage = packageName
            lastRequested = requested
        }
        lastName?.let { name ->
            OpenInAppCard(
                appName = name,
                packageName = lastPackage,
                requested = lastRequested,
                onOpen = onOpen,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun OpenInAppCard(
    appName: String,
    packageName: String?,
    requested: Boolean,
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
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            AppIcon(packageName = packageName)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Open in $appName",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (requested) {
                        "This page wants to open the app"
                    } else {
                        "Open this page in the app"
                    },
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

/**
 * The target app's launcher icon, loaded off-thread from [packageName]. Falls back to a generic
 * "open in new" glyph while loading or if the icon can't be resolved (app uninstalled between
 * resolution and render, restricted access, …). Clipped to a small squircle so a square legacy icon
 * still reads as a rounded app tile.
 */
@Composable
private fun AppIcon(packageName: String?) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = if (packageName.isNullOrEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(width = 96, height = 96)
                        .asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val resolved = icon
    if (resolved != null) {
        Image(
            bitmap = resolved,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(30.dp)
                .clip(SquircleShape(9.dp)),
        )
    } else {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 2.dp, end = 2.dp)
                .size(24.dp),
        )
    }
}
