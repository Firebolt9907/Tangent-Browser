package com.firebolt9907.tangentbrowser.ui.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.firebolt9907.tangentbrowser.ui.BannerCard
import com.firebolt9907.tangentbrowser.ui.BannerPriority
import com.firebolt9907.tangentbrowser.ui.BannerStackState
import com.firebolt9907.tangentbrowser.ui.MorphingBannerHost

/**
 * Transient "Reader mode isn't available for this page" banner, floating above the bottom bar (same
 * placement/morph as the OTP-paste and handoff banners). Shown **only** after the user *manually*
 * asks for reader mode — via the aA pill long-press or the aA sheet — on a page the extractor can't
 * turn into an article. It is driven by [ReaderManager.readerUnavailableTab], which is raised solely
 * while a user-initiated activation is pending, so the automatic reader pass never triggers it. The
 * call site auto-retires it on a timer; the X dismisses immediately.
 *
 * Rendered by the shared [BannerCard] in its informational form — no whole-card tap, so no press
 * morph, and an `onSurfaceVariant` glyph rather than the actionable banners' `primary` one.
 */
@Composable
fun ReaderUnavailableBannerHost(
    visible: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    stack: BannerStackState? = null,
    onDismiss: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        stackId = "readerUnavailable",
        stackPriority = BannerPriority.ReaderUnavailable,
    ) {
        BannerCard(
            icon = Icons.Outlined.MenuBook,
            title = "Reader mode unavailable",
            subtitle = "This page couldn’t be opened in Reader",
            dismissDescription = "Dismiss reader notice",
            onDismiss = onDismiss,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
