package com.firebolt9907.tangentbrowser.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import com.firebolt9907.tangentbrowser.ui.BannerPriority
import com.firebolt9907.tangentbrowser.ui.BannerPromptCard
import com.firebolt9907.tangentbrowser.ui.BannerStackState
import com.firebolt9907.tangentbrowser.ui.MorphingBannerHost

/**
 * The host half of **element zap** (see [ElementZap]): while the in-page picker is live, this bottom
 * banner names what is currently selected and offers the three things the page script can't render
 * itself — Widen (step up to the parent), Hide (commit), and Cancel.
 *
 * Deliberately not swipe-to-dismiss like the passive banners: dismissing has to also tear down the
 * page-side picker (which is swallowing the page's clicks), so every exit goes through [onCancel].
 */
@Composable
fun ZapPickerBannerHost(
    visible: Boolean,
    selection: ElementZap.Selection?,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    gridProgress: () -> Float = { 0f },
    // True while the tab switcher is open: this banner belongs to the current tab's page, so it
    // must neither show nor take taps over the overview (see MorphingBannerHost.suppressed).
    suppressed: Boolean = false,
    stack: BannerStackState? = null,
    onWiden: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        gridProgress = gridProgress,
        suppressed = suppressed,
        stack = stack,
        stackId = "elementZap",
        stackPriority = BannerPriority.ElementZap,
    ) {
        // Hold the last selection so the action row keeps its labels through the collapse after the
        // picker steps back to "nothing selected".
        var lastSelection by remember { mutableStateOf(selection) }
        if (selection != null) lastSelection = selection
        BannerPromptCard(
            icon = Icons.Outlined.HideSource,
            title = if (selection == null) "Tap an element to hide it" else "Hide this element?",
            subtitle = selection?.label ?: "It stays hidden on this site",
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            dismissDescription = "Stop hiding elements",
            onDismiss = onCancel,
            actionsVisible = selection != null,
        ) {
            if (lastSelection?.canWiden == true) {
                TextButton(onClick = onWiden) { Text("Select larger") }
            }
            FilledTonalButton(onClick = onConfirm) { Text("Hide") }
        }
    }
}
