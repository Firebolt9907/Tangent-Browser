package com.firebolt9907.tangentbrowser.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.permissions.SitePermissionController
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
    tabId: String? = null,
) {
    val pendingRequest by controller.contentRequest.collectAsStateWithLifecycle()
    val request = pendingRequest?.let { controller.contentRequestFor(tabId) }
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
        stackPriority = BannerPriority.SitePermission,
    ) {
        // Hold the last request so the card keeps its content through the exit morph after the
        // controller clears the flow on grant/reject.
        var lastRequest by remember { mutableStateOf(request) }
        if (request != null) lastRequest = request
        lastRequest?.let { req ->
            SitePermissionCard(
                request = req,
                onAllow = {
                    if (controller.contentRequestFor(tabId)?.id == req.id) controller.grantCurrent(req.id)
                },
                onBlock = {
                    if (controller.contentRequestFor(tabId)?.id == req.id) controller.rejectCurrent(req.id)
                },
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
    BannerPromptCard(
        icon = info.icon,
        title = "Allow $host to use ${info.phrase}?",
        subtitle = info.explanation,
        titleMaxLines = 2,
        subtitleMaxLines = 3,
        // A swipe away = block (the safe default), matching the dialog's dismiss = reject.
        modifier = Modifier.bannerSwipeToDismiss(onBlock),
    ) {
        OutlinedButton(onClick = onBlock) { Text("Block") }
        Button(onClick = onAllow) { Text("Allow") }
    }
}
