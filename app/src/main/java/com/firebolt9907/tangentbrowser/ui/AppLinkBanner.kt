package com.firebolt9907.tangentbrowser.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import com.firebolt9907.tangentbrowser.ui.theme.labelLargeEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.pressMorphCorner
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
    // True while the tab switcher is open: this banner belongs to the current tab's page, so it
    // must neither show nor take taps over the overview (see MorphingBannerHost.suppressed).
    suppressed: Boolean = false,
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
        suppressed = suppressed,
        gridProgress = gridProgress,
        horizontalOffsetX = horizontalOffsetX,
        switchFade = switchFade,
        stack = stack,
        stackId = "openInApp",
        // Base of the stack: a passive suggestion, so everything else — including the archive
        // scrubber the user may be actively dragging — piles in FRONT of it. See BannerPriority.
        stackPriority = BannerPriority.OpenInApp,
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

/**
 * Matches the shared floating-banner card in `sync/HandoffBanner.kt` (same surface, elevation, press
 * morph and a11y shape); it stays a separate composable only because its leading slot is the target
 * app's real launcher icon rather than a themed glyph.
 */
@Composable
private fun OpenInAppCard(
    appName: String,
    packageName: String?,
    requested: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = SquircleShape(pressMorphCorner(interactionSource, resting = 28.dp)),
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
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onOpen,
                )
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            AppIcon(packageName = packageName)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Open in $appName",
                    style = MaterialTheme.typography.labelLargeEmphasized,
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
                    contentDescription = "Don't open in $appName",
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
 *
 * The load finishes after the banner is already on screen, so the swap is animated rather than a pop:
 * scale on [ExpressiveMotion.fastSpatialSpec] (the glyph resizes — spatial) crossfading on
 * [ExpressiveMotion.fastEffectsSpec] (alpha — critically damped, or it flickers). Both states occupy
 * the same fixed 30dp slot, so the resolved icon arriving no longer nudges the title sideways.
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
    AnimatedContent(
        targetState = icon,
        transitionSpec = {
            (scaleIn(ExpressiveMotion.fastSpatialSpec(), initialScale = 0.7f) +
                fadeIn(ExpressiveMotion.fastEffectsSpec())) togetherWith
                (scaleOut(ExpressiveMotion.fastSpatialSpec(), targetScale = 0.7f) +
                    fadeOut(ExpressiveMotion.fastEffectsSpec()))
        },
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(30.dp),
        label = "appIcon",
    ) { resolved ->
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
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
