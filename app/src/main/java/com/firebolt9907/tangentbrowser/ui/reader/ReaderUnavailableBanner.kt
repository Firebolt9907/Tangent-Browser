package com.firebolt9907.tangentbrowser.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.BannerPriority
import com.firebolt9907.tangentbrowser.ui.BannerStackState
import com.firebolt9907.tangentbrowser.ui.MorphingBannerHost
import com.firebolt9907.tangentbrowser.ui.bannerSwipeToDismiss
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape

/**
 * Transient "Reader mode isn't available for this page" banner, floating above the bottom bar (same
 * placement/morph as the OTP-paste and handoff banners). Shown **only** after the user *manually*
 * asks for reader mode — via the aA pill long-press or the aA sheet — on a page the extractor can't
 * turn into an article. It is driven by [ReaderManager.readerUnavailableTab], which is raised solely
 * while a user-initiated activation is pending, so the automatic reader pass never triggers it. The
 * call site auto-retires it on a timer; the X dismisses immediately.
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
                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reader mode unavailable",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "This page couldn’t be opened in Reader",
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
