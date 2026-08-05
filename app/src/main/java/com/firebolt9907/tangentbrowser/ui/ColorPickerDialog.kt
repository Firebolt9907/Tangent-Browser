package com.firebolt9907.tangentbrowser.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.components.SwatchChoice
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import com.firebolt9907.tangentbrowser.ui.theme.bodyLargeEmphasized
import kotlin.math.roundToInt

/**
 * Color chooser for an HTML `<input type="color">` ([mozilla.components.concept.engine.prompt.
 * PromptRequest.Color]) — previously unhandled, so tapping such an input did nothing. There's no
 * platform color-picker dialog on Android, so this is a compact RGB dialog (a swatch row of common
 * colors plus R/G/B sliders and a live preview). Emits a `#rrggbb` string, the format the input
 * expects. Fenix ships a richer picker; this covers the common case with minimal surface.
 *
 * The presets are [SwatchChoice]es — the same 48dp-target, animated-selection swatch the container
 * and tab-group editors use — so a preset is hittable, shows *which* one is active (this dialog
 * previously gave no selection feedback at all), and reads to TalkBack as a radio choice. Six 48dp
 * targets don't fit an `AlertDialog`'s ~264dp of usable width, hence the [FlowRow].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    initialColor: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember(initialColor) { parseHexColor(initialColor) }
    var red by remember { mutableFloatStateOf(start.first.toFloat()) }
    var green by remember { mutableFloatStateOf(start.second.toFloat()) }
    var blue by remember { mutableFloatStateOf(start.third.toFloat()) }
    val haptics = rememberTangentHaptics()

    val rgb = Triple(red.roundToInt(), green.roundToInt(), blue.roundToInt())
    val current = Color(rgb.first, rgb.second, rgb.third)
    val hex = "#%02x%02x%02x".format(rgb.first, rgb.second, rgb.third)
    // Colour is an effect, not a movement: critically damped, so dragging a slider doesn't leave the
    // preview overshooting past the value under the thumb.
    val preview by animateColorAsState(
        targetValue = current,
        animationSpec = ExpressiveMotion.defaultEffectsSpec(),
        label = "colorPreview",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a color") },
        text = {
            // Scrolls so the sliders stay reachable at large font scales.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Live preview of the chosen color with the emitted hex code.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(preview)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .semantics { contentDescription = "Selected color $hex" },
                    )
                    Text(hex, style = MaterialTheme.typography.bodyLargeEmphasized)
                }
                Spacer(Modifier.height(4.dp))
                // Quick swatches of common colors.
                FlowRow(Modifier.fillMaxWidth()) {
                    Swatches.forEach { swatch ->
                        SwatchChoice(
                            swatch = Color(swatch.r, swatch.g, swatch.b),
                            label = swatch.name,
                            selected = rgb == Triple(swatch.r, swatch.g, swatch.b),
                            onSelect = {
                                haptics.select()
                                red = swatch.r.toFloat()
                                green = swatch.g.toFloat()
                                blue = swatch.b.toFloat()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ColorSlider("Red", red, Color(0xFFE53935)) { red = it }
                ColorSlider("Green", green, Color(0xFF43A047)) { green = it }
                ColorSlider("Blue", blue, Color(0xFF1E88E5)) { blue = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(hex) }) { Text("Select") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One channel of the RGB triple. [name] is the full channel name — it labels the slider for
 * TalkBack, while the visible glyph is just its initial (a fixed-*size* label clipped its own text
 * once the user's font scale grew, so the label is width-constrained only).
 */
@Composable
private fun ColorSlider(name: String, value: Float, tint: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            name.take(1),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 16.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = name },
        )
    }
}

/** A named preset colour for the quick-swatch row (the name is the swatch's accessibility label). */
private data class Swatch(val name: String, val r: Int, val g: Int, val b: Int)

private val Swatches = listOf(
    Swatch("Black", 0, 0, 0),
    Swatch("White", 255, 255, 255),
    Swatch("Red", 229, 57, 53),
    Swatch("Green", 67, 160, 71),
    Swatch("Blue", 30, 136, 229),
    Swatch("Yellow", 253, 216, 53),
)

/** Parse a `#rrggbb` (or `#rgb`) string into an (r, g, b) triple, defaulting to black on garbage. */
private fun parseHexColor(hex: String): Triple<Int, Int, Int> {
    val cleaned = hex.trim().removePrefix("#")
    return try {
        when (cleaned.length) {
            6 -> Triple(
                cleaned.substring(0, 2).toInt(16),
                cleaned.substring(2, 4).toInt(16),
                cleaned.substring(4, 6).toInt(16),
            )
            3 -> Triple(
                cleaned.substring(0, 1).repeat(2).toInt(16),
                cleaned.substring(1, 2).repeat(2).toInt(16),
                cleaned.substring(2, 3).repeat(2).toInt(16),
            )
            else -> Triple(0, 0, 0)
        }
    } catch (_: NumberFormatException) {
        Triple(0, 0, 0)
    }
}
