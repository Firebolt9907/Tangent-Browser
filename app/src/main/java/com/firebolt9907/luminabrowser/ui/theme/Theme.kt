package com.firebolt9907.luminabrowser.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Neutral80,
    secondary = NeutralGrey80,
    tertiary = NeutralVariant80
)

private val LightColorScheme = lightColorScheme(
    primary = Neutral40,
    secondary = NeutralGrey40,
    tertiary = NeutralVariant40
)

@Composable
fun LuminaBrowserTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    // When the "Match website theme color" option is on and the current site declares a theme color,
    // this is that color. The Material `primary` accent (buttons, highlights, selection) is derived
    // from it so app-wide accents match the site. The bottom-bar surface tint is applied separately
    // in BottomBarLayer so ordinary surfaces (sheets, cards) keep their neutral tone.
    siteColor: Color? = null,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = if (siteColor != null) {
        // Keep content on the accent legible: dark text/icons on a light site color, light on a dark
        // one. Only the accent roles are overridden — surfaces stay neutral so the recolor reads as
        // an accent shift, not a full repaint of every panel.
        val onSite = if (siteColor.luminance() > 0.5f) Color.Black else Color.White
        baseScheme.copy(
            primary = siteColor,
            onPrimary = onSite,
            primaryContainer = siteColor,
            onPrimaryContainer = onSite,
        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * The device's system accent color (Material You dynamic color) for the current light/dark mode, or
 * the app's brand primary on pre-Android-12 devices that have no dynamic palette. Read directly from
 * the platform — NOT from `MaterialTheme.colorScheme.primary` — so it stays the true system color
 * even when [LuminaBrowserTheme]'s `siteColor` override has repainted the in-app primary. Used by the
 * "Tint pages to my system color" boost to recolor web content toward the user's accent.
 */
@Composable
fun rememberSystemAccentColor(): Color {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
    } else {
        if (dark) DarkColorScheme.primary else LightColorScheme.primary
    }
}