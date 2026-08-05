package com.firebolt9907.tangentbrowser.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion

/** Ink that stays legible over an arbitrary swatch colour. */
internal fun Color.onSwatch(): Color = if (luminance() > 0.4f) Color.Black else Color.White

/**
 * One colour in a swatch picker: a 48dp touch target wrapping a smaller *visible* dot.
 *
 * The visible dot is deliberately smaller than the target — an under-48dp swatch is the usual way
 * these pickers end up hard to hit, and growing the paint to fill 48dp would make a colour row look
 * like a row of buttons. Selection is animated rather than repainted: the dot grows on a spatial
 * spring (it moves/resizes) while the check fades in on an effects spring (alpha, so critically
 * damped — a bouncing opacity reads as a flicker).
 *
 * Shared by the container editor, the tab-group editor and the reader theme picker, which pick from
 * different colour enums but present them identically. [outline] draws a hairline ring around the
 * visible dot — needed wherever a swatch can be the same colour as the surface behind it (a white
 * reader page on a light sheet is otherwise an invisible choice).
 */
@Composable
internal fun SwatchChoice(
    swatch: Color,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    outline: Color? = null,
) {
    val diameter by animateDpAsState(
        targetValue = if (selected) 36.dp else 28.dp,
        animationSpec = ExpressiveMotion.fastSpatialSpec(),
        label = "swatchSize",
    )
    val check by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = ExpressiveMotion.defaultEffectsSpec(),
        label = "swatchCheck",
    )
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(swatch)
                .then(if (outline != null) Modifier.border(1.dp, outline, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (check > 0.01f) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = swatch.onSwatch().copy(alpha = check),
                    modifier = Modifier
                        .size(20.dp)
                        .scale(0.7f + 0.3f * check),
                )
            }
        }
    }
}
