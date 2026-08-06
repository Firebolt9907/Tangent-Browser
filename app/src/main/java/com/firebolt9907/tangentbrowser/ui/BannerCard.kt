package com.firebolt9907.tangentbrowser.ui

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
