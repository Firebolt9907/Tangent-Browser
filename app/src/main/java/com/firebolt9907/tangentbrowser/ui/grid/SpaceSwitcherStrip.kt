package com.firebolt9907.tangentbrowser.ui.grid

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.state.TangentTabGroup
import com.firebolt9907.tangentbrowser.ui.groups.swatch
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion

/**
 * The Space switcher — a horizontal strip of chips at the bottom of the tab overview (above the
 * overview toolbar) that changes which local Space the switcher shows. The first chip is the default
 * **Personal** space (tabs in no group); each remaining chip is a [TangentTabGroup] elevated to a
 * Space, showing its colour, name and live tab count. A trailing "＋" chip creates a new Space.
 *
 * Tapping a chip selects that space (via [onSelectSpace]; null = Personal); long-pressing a named
 * space opens Space management ([onManageSpaces]) to rename / recolour / set a default container /
 * delete. The active chip is filled with the primary colour, cross-fading between states so that
 * switching spaces reads as one chip changing rather than two chips repainting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpaceSwitcherStrip(
    spaces: List<TangentTabGroup>,
    activeSpaceId: String?,
    personalCount: Int,
    countFor: (spaceId: String) -> Int,
    onSelectSpace: (spaceId: String?) -> Unit,
    onNewSpace: () -> Unit,
    onManageSpaces: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpaceChip(
                label = "Personal",
                count = personalCount,
                dotColor = null,
                selected = activeSpaceId == null,
                onClick = { onSelectSpace(null) },
                onLongClick = null,
            )
            spaces.forEach { space ->
                SpaceChip(
                    label = space.name.ifBlank { "Space" },
                    count = countFor(space.id),
                    dotColor = space.color.swatch(),
                    selected = activeSpaceId == space.id,
                    onClick = { onSelectSpace(space.id) },
                    onLongClick = onManageSpaces,
                )
            }
            // New-space affordance.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .combinedClickable(onClick = onNewSpace, onLongClick = onManageSpaces),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New space",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpaceChip(
    label: String,
    count: Int,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    // Selection is four colour channels on one control (fill, label, dot, count). They all move on
    // the SAME effects spec so the chip changes state as one object — a chip whose fill lands before
    // its label reads as two overlapping animations. Effects, not spatial: an 0.8-damped spatial
    // overshoots a colour lerp past its endpoint.
    // Each target is derived from `selected`, never from another animated value — feeding one
    // animateColorAsState into the next stacks a second spring on an already-smooth signal.
    val fgTarget = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = ExpressiveMotion.defaultEffectsSpec(),
        label = "spaceChipBg",
    )
    val fg by animateColorAsState(
        targetValue = fgTarget,
        animationSpec = ExpressiveMotion.defaultEffectsSpec(),
        label = "spaceChipFg",
    )
    val dotTint by animateColorAsState(
        targetValue = if (selected) fgTarget else (dotColor ?: Color.Transparent),
        animationSpec = ExpressiveMotion.defaultEffectsSpec(),
        label = "spaceChipDot",
    )
    val countTint by animateColorAsState(
        targetValue = if (selected) fgTarget else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = ExpressiveMotion.defaultEffectsSpec(),
        label = "spaceChipCount",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dotTint),
            )
            Spacer(Modifier.width(7.dp))
        } else {
            Icon(
                Icons.Default.Home,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (count > 99) "99+" else "$count",
                style = MaterialTheme.typography.labelMedium,
                color = countTint,
            )
        }
    }
}
