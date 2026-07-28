package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.permissions.SitePermissionController
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import mozilla.components.concept.engine.permission.PermissionRequest

/**
 * The site content-permission prompt (geolocation, camera, microphone, notifications…) as a Now-Bar
 * banner floating above the address pill — the same shared [MorphingBannerHost] surface every other
 * transient card uses, replacing the old center-screen `AlertDialog`. The [controller] applies
 * remembered decisions and the autoplay policy before this ever shows, so this only surfaces
 * genuinely-new requests; Allow/Block resolve the request and remember the decision, and a swipe (or
 * the controller cancelling) dismisses as a reject.
 *
 * Joins the shared stack at the highest priority so it always sits front-most: unlike the informational
 * banners (handoff, received tab, …) it's an interactive request the user must answer.
 */
@Composable
fun SitePermissionBannerHost(
    controller: SitePermissionController,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    gridProgress: () -> Float = { 0f },
    horizontalOffsetX: () -> Float = { 0f },
    switchFade: () -> Float = { 0f },
    stack: BannerStackState? = null,
) {
    val request by controller.contentRequest.collectAsStateWithLifecycle()
    MorphingBannerHost(
        visible = request != null,
        bottomPadding = bottomPadding,
        // Replay the spring-out when a different request arrives while one is still on screen.
        enterKey = request?.id,
        collapseProgress = collapseProgress,
        gridProgress = gridProgress,
        horizontalOffsetX = horizontalOffsetX,
        switchFade = switchFade,
        stack = stack,
        stackId = "sitePermission",
        stackPriority = 10,
    ) {
        // Hold the last request so the card keeps its content through the exit morph after the
        // controller clears the flow on grant/reject.
        var lastRequest by remember { mutableStateOf(request) }
        if (request != null) lastRequest = request
        lastRequest?.let { req ->
            SitePermissionCard(
                request = req,
                onAllow = { controller.grantCurrent() },
                onBlock = { controller.rejectCurrent() },
            )
        }
    }
}

@Composable
private fun SitePermissionCard(
    request: PermissionRequest,
    onAllow: () -> Unit,
    onBlock: () -> Unit,
) {
    val host = remember(request.uri) {
        runCatching { request.uri?.toUri()?.host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: request.uri?.takeIf { it.isNotBlank() }
            ?: "This site"
    }
    val info = remember(request) { describePermissions(request.permissions) }
    Surface(
        shape = SquircleShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .widthIn(max = 480.dp)
            // A swipe away = block (the safe default), matching the dialog's dismiss = reject.
            .bannerSwipeToDismiss(onBlock),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = info.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Allow $host to use ${info.phrase}?",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = info.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onBlock) { Text("Block") }
                Button(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}
