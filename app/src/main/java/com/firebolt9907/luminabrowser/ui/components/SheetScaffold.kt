package com.firebolt9907.luminabrowser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks for the bottom sheets so every surface (Settings, Bookmarks, History,
 * Downloads, Passwords, Sync, Add-ons) reads with the same header, section labels, and grouped
 * cards. Presentation only — behavior stays in each sheet.
 */

/**
 * Consistent sheet title row: an optional leading icon, a large bold title, and an optional trailing
 * action (e.g. a "Clear all" button or a busy spinner). Every sheet's header goes through this so the
 * icon spacing, title weight, and trailing alignment stay identical across surfaces.
 */
@Composable
fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(10.dp))
        }
        // iOS-18 large-title weight — bold rather than the M3 default Normal. Weighted so the
        // trailing content sits flush right (and a long title ellipsizes rather than shoving it off).
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** A small muted section label used above a group of related controls. */
@Composable
fun SheetSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A rounded surface card that groups related rows (Material 3 grouped-list style). */
@Composable
fun SheetCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        content = content,
    )
}

/**
 * A hairline separator between rows inside a [SheetCard]. Inset to the card's content padding so it
 * reads as a grouped-list divider (the iOS / Material-3 settings convention) rather than a full-bleed
 * rule. Place between rows, not before the first or after the last.
 */
@Composable
fun SheetRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}
