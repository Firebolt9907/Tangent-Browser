package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Color chooser for an HTML `<input type="color">` ([mozilla.components.concept.engine.prompt.
 * PromptRequest.Color]) — previously unhandled, so tapping such an input did nothing. There's no
 * platform color-picker dialog on Android, so this is a compact RGB dialog (a swatch row of common
 * colors plus R/G/B sliders and a live preview). Emits a `#rrggbb` string, the format the input
 * expects. Fenix ships a richer picker; this covers the common case with minimal surface.
 */
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

    val current = Color(red.roundToInt(), green.roundToInt(), blue.roundToInt())
    val hex = "#%02x%02x%02x".format(red.roundToInt(), green.roundToInt(), blue.roundToInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a color") },
        text = {
            Column {
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
                            .background(current)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    )
                    Text(hex, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                // Quick swatches of common colors.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Swatches.forEach { swatch ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(swatch.r, swatch.g, swatch.b))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .semantics {
                                    contentDescription = swatch.name
                                    role = Role.Button
                                }
                                .clickable {
                                    red = swatch.r.toFloat()
                                    green = swatch.g.toFloat()
                                    blue = swatch.b.toFloat()
                                },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ColorSlider("R", red, Color(0xFFE53935)) { red = it }
                ColorSlider("G", green, Color(0xFF43A047)) { green = it }
                ColorSlider("B", blue, Color(0xFF1E88E5)) { blue = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(hex) }) { Text("Select") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ColorSlider(label: String, value: Float, tint: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.size(width = 16.dp, height = 20.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..255f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
            ),
            modifier = Modifier.fillMaxWidth(),
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
