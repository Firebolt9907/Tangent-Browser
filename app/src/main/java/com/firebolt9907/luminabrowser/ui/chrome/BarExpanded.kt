package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState
import com.firebolt9907.luminabrowser.ui.reader.ReaderTheme
import com.firebolt9907.luminabrowser.ui.LuminaHaptics
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape
import com.firebolt9907.luminabrowser.ui.theme.LuminaBrowserTheme
import kotlin.math.roundToInt

/**
 * Width that collapses from [full] (progress 0) to zero (progress 1), read in the LAYOUT phase so
 * the scroll-collapse spring never recomposes the pill's controls. Pair with `Modifier.clipToBounds`
 * on the same node so the fixed-size child clips away as the reported width shrinks — matching the
 * old `Modifier.width(lerp(full, 0.dp, progress))`.
 */
private fun Modifier.collapsingWidth(full: Dp, progress: () -> Float): Modifier = this.layout { measurable, constraints ->
    val p = progress().coerceIn(0f, 1f)
    val w = (full.toPx() * (1f - p)).roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(constraints)
    layout(w, placeable.height) { placeable.place(0, 0) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BarExpanded(
    state: BarUiState,
    onUrlTap: () -> Unit,
    onUrlLongPress: () -> Unit = {},
    onAaTap: () -> Unit,
    onAaLongPress: () -> Unit = {},
    onDragStart: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: (totalDelta: Offset, velocity: Offset) -> Unit,
    onReload: () -> Unit,
    onReloadLongPress: () -> Unit = {},
    canGoForward: Boolean = false,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    controlsEnabled: Boolean = true,
    collapseProgress: () -> Float = { 0f },
    isReaderEligible: Boolean = false,
    isReaderActive: Boolean = false,
    readerTheme: ReaderTheme = ReaderTheme.System,
    onClick: (() -> Unit)? = null,
    // Applied to the address label so it can act as a shared element that morphs into the editing
    // field (supplied by SafariBar's SharedTransitionLayout). Default no-op for previews/ghosts.
    addressSharedModifier: Modifier = Modifier,
    // Site theme color ("Match website theme color" option). When non-null and the page isn't in
    // reader mode, the pill takes this as its background and its content (aA / URL text/icons) is
    // recolored via a nested theme for contrast. Reader mode owns its own colors and ignores this.
    tintColor: Color? = null,
    haptics: LuminaHaptics? = null,
) {
    // controlsProgress = 1f - collapseProgress(), read in draw phase via graphicsLayer.
    val controlsAlpha: () -> Float = { (1f - collapseProgress().coerceIn(0f, 1f)) }
    // Splash (ripple) shape for the aA / reload chips — a squircle echoing the pill's own corner
    // style so the touch feedback reads as part of the pill rather than a hard square.
    val splashShape = remember { SquircleShape(radius = 16.dp, exponent = 3f) }
    // Flag plain-HTTP pages as not secure (modern-browser convention). https / internal pages
    // (about:, blank new tab) show nothing — only http:// is called out.
    val isInsecure = !state.blankNewTabPreview &&
        state.pageUrl.startsWith("http://", ignoreCase = true)
    val blankPreview = state.blankNewTabPreview

    // Reader mode owns the pill color; otherwise the site tint (if any) fills it, else the default
    // surface. `siteTintActive` also drives the nested content-contrast theme below.
    val siteTintActive = tintColor != null && !isReaderActive
    val containerColor = if (isReaderActive) {
        when (readerTheme) {
            ReaderTheme.System -> MaterialTheme.colorScheme.surfaceContainerHigh
            ReaderTheme.White -> Color(0xFFF2F2F7)
            ReaderTheme.Sepia -> Color(0xFFEADFCA)
            ReaderTheme.Dark -> Color(0xFF2C2C2E)
        }
    } else {
        tintColor
    }

    // Contrast color for content sitting on the site tint (dark on light tint, light on dark).
    val onTint = tintColor?.let { if (it.luminance() > 0.5f) Color.Black else Color.White }
    val aAColor = when {
        isReaderActive -> MaterialTheme.colorScheme.primary
        siteTintActive -> if (isReaderEligible) onTint!! else onTint!!.copy(alpha = 0.6f)
        isReaderEligible -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }

    BoxWithConstraints(modifier = modifier) {
        val expandedWidth = (maxWidth - 40.dp).coerceAtLeast(0.dp)
        val compactWidth = (((if (blankPreview) "+" else state.displayUrl).length * 8).dp + 28.dp)
            .coerceIn(80.dp, (maxWidth - 40.dp).coerceAtLeast(80.dp))

        BarPill(
            // Static height/cornerRadius are placeholders — `morph` overrides all per-frame geometry
            // and interpolates it in the layout/draw phases (no recomposition on the collapse spring).
            height = 56.dp,
            cornerRadius = 28.dp,
            onDragStart = onDragStart,
            onDragDelta = onDragDelta,
            onDragEnd = onDragEnd,
            interactive = interactive,
            containerColor = containerColor,
            onClick = onClick,
            morph = PillMorph(
                progress = collapseProgress,
                expandedWidth = expandedWidth,
                compactWidth = compactWidth,
                expandedHeight = 56.dp,
                compactHeight = 36.dp,
                expandedCorner = 28.dp,
                compactCorner = 18.dp,
                expandedExponent = 3.0f,
                compactExponent = 2.0f,
                expandedElevation = 6.dp,
                compactElevation = 0.dp,
            ),
        ) {
            PillTintScope(if (siteTintActive) tintColor else null, onTint) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!blankPreview) {
                    Box(
                        modifier = Modifier
                            .collapsingWidth(48.dp, collapseProgress)
                            .clipToBounds()
                            .graphicsLayer { alpha = controlsAlpha() },
                    ) {
                        @OptIn(ExperimentalFoundationApi::class)
                        val aADescription = if (isReaderActive) {
                            "Reader and page settings"
                        } else {
                            "Page settings and reader view"
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .padding(4.dp)
                                .clip(splashShape)
                                .combinedClickable(
                                    onClick = { haptics?.softClick(); onAaTap() },
                                    onLongClick = { haptics?.longPress(); onAaLongPress() },
                                    enabled = controlsEnabled,
                                )
                                .semantics(mergeDescendants = true) {
                                    contentDescription = aADescription
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isReaderActive) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    tint = aAColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                Text(
                                    text = "aA",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isReaderEligible) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = aAColor
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        // Shared element: this address region's bounds are matched against the
                        // editing field's (same "addressText" key) so the URL glides + morphs
                        // between the pill and the editing modal instead of cross-fading in place.
                        .then(addressSharedModifier)
                        // No ripple on the address text itself — tapping it opens the editing
                        // sheet (a full surface change), where a splash reads as noise. The aA /
                        // reload chips keep their (now squircle) splash for button-like feedback.
                        // Long-press copies the URL (Arc/Safari-style fast copy) — only on real
                        // pages, never on the blank new-tab preview.
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = controlsEnabled,
                            onClickLabel = if (blankPreview) "Open a new tab" else "Edit address",
                            onLongClickLabel = if (blankPreview) null else "Copy link",
                            onLongClick = if (blankPreview) null else {
                                { haptics?.longPress(); onUrlLongPress() }
                            },
                            onClick = { haptics?.softClick(); onUrlTap() },
                        )
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isInsecure) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = "Not secure",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { alpha = controlsAlpha() },
                        )
                        Spacer(Modifier.collapsingWidth(4.dp, collapseProgress))
                    }
                    Text(
                        text = if (blankPreview) "+" else state.displayUrl,
                        style = if (blankPreview) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )

                }
                if (!blankPreview) {
                    Box(
                        modifier = Modifier
                            .collapsingWidth(48.dp, collapseProgress)
                            .clipToBounds()
                            .graphicsLayer { alpha = controlsAlpha() },
                    ) {
                        @OptIn(ExperimentalFoundationApi::class)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .padding(4.dp)
                                .clip(splashShape)
                                .combinedClickable(
                                    onClick = { haptics?.softClick(); onReload() },
                                    onLongClick = if (canGoForward) { { haptics?.longPress(); onReloadLongPress() } } else null,
                                    enabled = controlsEnabled,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                            // Subtle chevron hints that long-press navigates forward.
                            if (canGoForward) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = "Go forward (long press reload)",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 6.dp, bottom = 6.dp)
                                        .size(9.dp),
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * Recolors the pill body's content for legibility on the site theme tint. When [tintColor] is null
 * (option off, or reader mode owns the color) it is a passthrough. Otherwise it overrides the "on"
 * roles the pill content reads — including [LocalContentColor] used by the URL label and reload icon
 * — and the pill's surface role, so nothing hard-codes a color that would vanish on the tint.
 */
@Composable
private fun PillTintScope(
    tintColor: Color?,
    onTint: Color?,
    content: @Composable () -> Unit,
) {
    if (tintColor == null || onTint == null) {
        content()
        return
    }
    val scheme = MaterialTheme.colorScheme.copy(
        onSurface = onTint,
        onSurfaceVariant = onTint.copy(alpha = 0.7f),
        primary = onTint,
        error = onTint,
        surfaceContainerHighest = tintColor,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides onTint, content = content)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BarExpandedPreview() {
    LuminaBrowserTheme(darkTheme = true) {
        BarExpanded(
            state = BarUiState("apple.com", 4, BarMode.Expanded, ""),
            onUrlTap = {},
            onAaTap = {},
            onDragStart = {},
            onDragDelta = { _ -> },
            onDragEnd = { _, _ -> },
            onReload = {},
        )
    }
}
