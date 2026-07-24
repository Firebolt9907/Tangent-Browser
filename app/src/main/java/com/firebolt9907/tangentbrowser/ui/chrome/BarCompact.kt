package com.firebolt9907.tangentbrowser.ui.chrome

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
import com.firebolt9907.tangentbrowser.state.BarMode
import com.firebolt9907.tangentbrowser.state.BarUiState
import com.firebolt9907.tangentbrowser.ui.TangentHaptics
import com.firebolt9907.tangentbrowser.ui.theme.TangentBrowserTheme

@Composable
internal fun BarCompact(
    state: BarUiState,
    onUrlTap: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: (totalDelta: Offset, velocity: Offset) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    haptics: TangentHaptics? = null,
) {
    BarPill(
        widthFraction = 0.4f,
        height = 36.dp,
        cornerRadius = 36.dp,
        onDragDelta = onDragDelta,
        onDragEnd = onDragEnd,
        modifier = modifier,
        interactive = interactive,
        exponent = 2.0f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClickLabel = "Edit address") { haptics?.softClick(); onUrlTap() }
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

        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BarCompactPreview() {
    TangentBrowserTheme(darkTheme = true) {
        BarCompact(
            state = BarUiState("apple.com", 7, BarMode.Compact, ""),
            onUrlTap = {},
            onDragDelta = { _ -> },
            onDragEnd = { _, _ -> },
        )
    }
}
