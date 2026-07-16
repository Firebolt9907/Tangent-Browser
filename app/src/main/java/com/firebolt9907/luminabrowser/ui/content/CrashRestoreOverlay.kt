package com.firebolt9907.luminabrowser.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-bleed recovery surface shown when a tab's Gecko content process has been killed
 * (`ContentState.crashed`). Without this the tab is a dead, blank [android.view.SurfaceView] with
 * no way back — Fenix surfaces the same "tab crashed / restore" affordance. Tapping Restore
 * dispatches `CrashAction.RestoreCrashedSessionAction` via `SessionUseCases.crashRecovery`, which
 * re-links a fresh engine session and reloads the last URL from saved state.
 *
 * Rendered as an opaque overlay on top of [ContentHost] so it hides the blank engine surface. It
 * is padded down by the top chrome inset so the message clears the status bar / pill.
 */
@Composable
fun CrashRestoreOverlay(
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(PaddingValues(top = topInset)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SentimentDissatisfied,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                text = "This tab has stopped working",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "The page ran out of memory or crashed. Restore it to reload where you left off.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
            Button(onClick = onRestore) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Restore tab",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
