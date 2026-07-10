package com.firebolt9907.luminabrowser.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the system bars while [isFullscreen] is true (swipe reveals them transiently) and restores
 * them when it flips back — the standard fullscreen-video treatment. Shared by `LuminaRoot` and
 * `CustomTabRoot`, which each track their own fullscreen flag. No-op when the composition isn't
 * hosted in an Activity window.
 */
@Composable
fun SyncSystemBarsWithFullscreen(isFullscreen: Boolean) {
    val window = (LocalContext.current as? Activity)?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
