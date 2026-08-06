package com.firebolt9907.tangentbrowser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import com.firebolt9907.tangentbrowser.ui.theme.labelLargeEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.pressMorphCorner
import com.firebolt9907.tangentbrowser.ui.theme.pressMorphShape

/**
 * The one floating-banner card shape shared by every whole-card-tappable Now-Bar banner (handoff,
 * received tab, send-to-device, OTP paste, blob file save). They differ only in glyph and wording,
 * so the surface, the press morph and the a11y shape live here rather than in a copy per banner.
 *
 * Expressive detail: the card is an opaque tappable container, so it takes the shape-morph on press
 * ([pressMorphCorner]) — via the corner radius rather than [pressMorphShape], since these banners
 * are `SquircleShape` and must stay superelliptical while they compress. The `interactionSource` is
 * hoisted into both the shape and the row's `clickable`, or the morph never sees the press; passing
 * one explicitly also means passing `indication`, or the ripple silently goes away.
 *
 * Banners whose leading slot is not a plain tinted glyph (the open-in-app card renders the target
 * app's real launcher icon) or that ask a question with explicit buttons rather than a whole-card
 * tap (the site-permission prompt) deliberately stay separate composables.
 *
 * A purely informational banner passes `onClick = null`: it then takes no clickable, and no press
 * morph either — reshaping a container that can't be pressed would promise an interaction it
 * doesn't have.
 *
 * @param iconTint defaults to `primary`, which reads as "there is something to do here". An
 *   informational card should pass `onSurfaceVariant` so the glyph doesn't claim to be actionable.
 */
@Composable
internal fun BannerCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    dismissDescription: String,
    onDismiss: () -> Unit,
    onClick: (() -> Unit)? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = SquircleShape(
            if (onClick == null) 28.dp else pressMorphCorner(interactionSource, resting = 28.dp),
        ),
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
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            role = Role.Button,
                            onClick = onClick,
                        )
                    },
                )
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = dismissDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The *question* variant of [BannerCard]: a banner that asks something and answers it with explicit
 * buttons rather than a whole-card tap (the site-permission prompt, the element-zap picker). Shares
 * [BannerCard]'s surface — same squircle, tone, elevation and max width — so the Now-Bar reads as one
 * family, but takes no press morph and no whole-card `clickable`: the card itself isn't the target,
 * the buttons in [actions] are.
 *
 * @param onDismiss when non-null, a trailing close button in the header. Banners that can be swiped
 *   away pass null and put their reject on `Modifier.bannerSwipeToDismiss` via [modifier] instead.
 * @param actionsVisible drives the action row's expand/collapse — spatial spring on the height (it
 *   moves) crossed with an effects spring on the alpha, so a card that grows a button doesn't jump.
 */
@Composable
internal fun BannerPromptCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    dismissDescription: String? = null,
    onDismiss: (() -> Unit)? = null,
    actionsVisible: Boolean = true,
    actions: @Composable RowScope.() -> Unit,
) {
    // The close button carries its own 12dp of internal padding, so the content stops 12dp short of
    // the 16dp gutter when it's present and the action row makes that back up.
    val contentEnd = if (onDismiss == null) 16.dp else 4.dp
    Surface(
        shape = SquircleShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 480.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = contentEnd),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = dismissDescription,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = actionsVisible,
                enter = expandVertically(ExpressiveMotion.defaultSpatialSpec()) +
                    fadeIn(ExpressiveMotion.defaultEffectsSpec()),
                exit = shrinkVertically(ExpressiveMotion.defaultSpatialSpec()) +
                    fadeOut(ExpressiveMotion.defaultEffectsSpec()),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp - contentEnd),
                    content = actions,
                )
            }
        }
    }
}
