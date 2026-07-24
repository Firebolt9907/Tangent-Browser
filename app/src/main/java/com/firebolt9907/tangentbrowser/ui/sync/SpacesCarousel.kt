package com.firebolt9907.tangentbrowser.ui.sync

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.sp
import com.firebolt9907.tangentbrowser.sync.zen.ZenContainer
import com.firebolt9907.tangentbrowser.sync.zen.ZenSpace
import com.firebolt9907.tangentbrowser.sync.zen.ZenSyncedTab
import kotlin.math.absoluteValue
import androidx.compose.foundation.clickable
import com.firebolt9907.tangentbrowser.ui.components.Favicon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem

/**
 * Arc-style horizontal deck of Zen [ZenSpace]s: one accent-coloured card per space, swipeable, with
 * neighbours peeking at the edges and non-centred cards scaled + faded so the focused space reads as
 * the current one. A dot indicator tracks the pager position. Read-only: each space lists the tabs
 * Zen synced for it (`zenWorkspace`-tagged records), tapping one opens it here — not a live switcher.
 *
 * Shared by [SyncedTabsSheet] (the Firefox Sync modal) and [SpacesSheet] (the tab-switcher swipe-up
 * summon) so both surfaces show identical cards.
 */
@Composable
fun SpacesCarousel(
    spaces: List<ZenSpace>,
    containers: List<ZenContainer>,
    onOpenTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (spaces.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { spaces.size })

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            // Symmetric padding so the neighbouring cards peek at both edges, telegraphing that the
            // deck scrolls. pageSpacing keeps a gutter between adjacent cards mid-swipe.
            contentPadding = PaddingValues(horizontal = 36.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
        ) { page ->
            val space = spaces[page]
            // 0 for the centred page, growing toward 1 as a card moves off-centre; drives both the
            // scale-down and the fade so off-focus spaces recede.
            val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                .absoluteValue.coerceIn(0f, 1f)
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = lerp(0.55f, 1f, 1f - offset)
                    }
            ) {
                SpaceCard(
                    space = space,
                    accent = spaceAccent(space, containers),
                    container = containers.firstOrNull { it.userContextId == space.containerTabId },
                    offset = offset,
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Tabs in ${space.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (space.tabs.isEmpty()) {
                        Text(
                            text = "No open tabs in this space",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            space.tabs.forEach { tab ->
                                SpaceTabRow(
                                    tab = tab,
                                    onOpen = { onOpenTab(tab.url) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (index in spaces.indices) {
                val selected = index == pagerState.currentPage
                val width by animateFloatAsState(if (selected) 18f else 6f, label = "dotWidth")
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(width.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun SpaceTabRow(
    tab: ZenSyncedTab,
    onOpen: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onOpen() },
        leadingContent = { Favicon(tab.url) },
        headlineContent = {
            Text(
                text = tab.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = tab.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open tab",
            )
        },
    )
}

@Composable
private fun SpaceCard(
    space: ZenSpace,
    accent: Color,
    container: ZenContainer?,
    offset: Float,
) {
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val scale = lerp(0.88f, 1f, 1f - offset)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = lerp(0.55f, 1f, 1f - offset)
            }
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    listOf(accent, androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.30f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = spaceGlyph(space),
                fontSize = 44.sp,
                color = onAccent,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = space.name.ifBlank { "Unnamed space" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onAccent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            container?.name?.takeIf { it.isNotBlank() }?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = onAccent.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Zen space icons are emoji or internal `chrome://`/SVG URLs; only emoji render meaningfully. */
private fun spaceGlyph(space: ZenSpace): String {
    val icon = space.icon
    if (icon != null && icon.length <= 4 && !icon.startsWith("chrome") && !icon.contains("/")) {
        return icon
    }
    return space.name.trim().firstOrNull()?.uppercase() ?: "▢"
}

/**
 * Accent colour for a space: the associated container's colour when the space is bound to one,
 * otherwise a stable hue derived from the space id so every space stays visually distinct even
 * without container/theme metadata.
 */
private fun spaceAccent(space: ZenSpace, containers: List<ZenContainer>): Color {
    containers.firstOrNull { it.userContextId == space.containerTabId }
        ?.color
        ?.let(::parseContainerColor)
        ?.let { return it }
    val seed = space.uuid.ifEmpty { space.name }
    val hue = seed.hashCode().mod(360).toFloat()
    return Color.hsl(hue, 0.52f, 0.52f)
}

/** Parse a Zen/Firefox container colour: a `#rrggbb`/`#rgb` hex, or a Firefox identity colour name. */
private fun parseContainerColor(raw: String): Color? {
    val value = raw.trim()
    if (value.startsWith("#")) {
        return runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()
    }
    return when (value.lowercase()) {
        "blue" -> Color(0xFF37ADFF)
        "turquoise" -> Color(0xFF00C79A)
        "green" -> Color(0xFF51CD00)
        "yellow" -> Color(0xFFFFCB00)
        "orange" -> Color(0xFFFF9F00)
        "red" -> Color(0xFFFF613D)
        "pink" -> Color(0xFFFF4BDA)
        "purple" -> Color(0xFFAF51F5)
        "toolbar" -> Color(0xFF7C7C7D)
        else -> null
    }
}
