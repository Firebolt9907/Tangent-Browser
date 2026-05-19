package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme

@Composable
internal fun BarCompact(
    state: BarUiState,
    onUrlTap: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: (totalDelta: Offset, velocity: Offset) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    BarPill(
        widthFraction = 0.4f,
        height = 44.dp,
        cornerRadius = 22.dp,
        onDragDelta = onDragDelta,
        onDragEnd = onDragEnd,
        modifier = modifier,
        interactive = interactive,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onUrlTap)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = state.compactDisplayUrl,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            TabCountChip(count = state.tabCount)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BarCompactPreview() {
    LuminaBrowserTheme(darkTheme = true) {
        BarCompact(
            state = BarUiState("apple.com", 7, BarMode.Compact, ""),
            onUrlTap = {},
            onDragDelta = { _ -> },
            onDragEnd = { _, _ -> },
        )
    }
}
