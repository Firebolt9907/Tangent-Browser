package com.firebolt9907.tangentbrowser.ui.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.firebolt9907.tangentbrowser.ui.theme.pressMorphShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.firebolt9907.tangentbrowser.ui.TangentHaptics
import com.firebolt9907.tangentbrowser.ui.TangentMotion
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion

/**
 * Reusable presentational building blocks for the "aA" sheet — extracted from `AaSheet.kt`
 * so the sheet file holds the sheet's behavior and these hold the shared, stateless UI.
 */

/** The page identity header: favicon chip, title, and host. */
@Composable
internal fun PageHeader(
    pageIcon: android.graphics.Bitmap?,
    title: String,
    host: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (pageIcon != null) {
                val img = remember(pageIcon) { pageIcon.asImageBitmap() }
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (host.isNotEmpty()) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A single quick-action tile: rounded icon chip over a short label. */
@Composable
internal fun QuickActionTile(
    action: QuickAction,
    modifier: Modifier = Modifier,
) {
    // The whole tile is the touch target, but the icon container is the only part of it with a
    // fill — so that is what performs the expressive press morph. Both share one interaction
    // source: the outer Column reports the press, the inner Box reacts to it.
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = action.label,
            ) { action.onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(pressMorphShape(interactionSource, resting = 14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Rounded surface card used to group related controls (reader options, zoom). */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        content = content,
    )
}

/** A small muted section label. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A tappable header that expands/collapses its content. */
@Composable
internal fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    haptics: TangentHaptics? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Rotate one chevron rather than hard-swapping up/down icons, and animate the body's
    // expand/collapse (and the sheet-height change) instead of popping it in and out.
    //
    // Expressive motion throughout: the chevron is a small control that moves, so it takes the
    // *fast spatial* tier (the tier is chosen by the size of the change, not by taste) — its light
    // damping gives the flick of overshoot that makes the rotation read as a physical flip rather
    // than a linear sweep. Nothing here animates alongside a material3 component, so the expressive
    // scheme is free to apply; see ExpressiveMotion's docs for the sheet-backdrop exception.
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = ExpressiveMotion.fastSpatialSpec(),
        label = "collapsibleChevron",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { haptics?.select(); onToggle() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            // The body's height change is spatial, the cross-fade over it is an effect (critically
            // damped — a bouncing opacity reads as a flicker). Matches the section rows in AaSheet.kt
            // exactly, so the two collapsibles in the same sheet no longer open on different curves.
            //
            // The height takes the flat-damped [TangentMotion.RevealExpand]/[RevealCollapse] pair, not
            // an expressive spatial: every expressive tier is 0.8-damped, and overshoot on an IntSize
            // grows the section taller than its content and flashes a strip of sheet background before
            // settling. This sheet scrolls, so that kick carries every row below the section with it.
            enter = expandVertically(TangentMotion.RevealExpand) +
                fadeIn(ExpressiveMotion.defaultEffectsSpec<Float>()),
            exit = shrinkVertically(TangentMotion.RevealCollapse) +
                fadeOut(ExpressiveMotion.defaultEffectsSpec<Float>()),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

/** A full-width sheet action row: leading icon, then a left-aligned label. */
@Composable
internal fun ActionRowContent(
    icon: ImageVector,
    text: String,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * A tap-anywhere toggle row for the aA sheet's Page Settings: title + explanation on the left, a
 * stateless [Switch] on the right. The whole row is the tap target — the Switch gets
 * `onCheckedChange = null` so it can't become a second, smaller one. [onLongClick] optionally backs
 * a hidden long-press action on a row. Was copy-pasted four times before extraction.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AaToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = enabled, onClick = onToggle, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}
