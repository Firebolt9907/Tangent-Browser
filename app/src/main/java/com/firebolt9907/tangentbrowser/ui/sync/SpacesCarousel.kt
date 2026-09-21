package com.firebolt9907.tangentbrowser.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.sync.zen.ZenContainer
import com.firebolt9907.tangentbrowser.sync.zen.ZenSpace
import com.firebolt9907.tangentbrowser.sync.zen.ZenSyncedTab
import com.firebolt9907.tangentbrowser.ui.components.Favicon
import com.firebolt9907.tangentbrowser.ui.theme.titleSmallEmphasized
import kotlinx.coroutines.launch
import java.net.URI

/** Full-height remote space browser. Named chips replace the oversized decorative card deck. */
@Composable
fun SpacesCarousel(
    spaces: List<ZenSpace>,
    containers: List<ZenContainer>,
    onOpenTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (spaces.isEmpty()) return
    val pager = rememberPagerState(pageCount = { spaces.size })
    val scope = rememberCoroutineScope()
    val chipScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(pager.currentPage) { chipScroll.animateScrollToItem(pager.currentPage) }

    Column(modifier.fillMaxWidth()) {
        LazyRow(
            state = chipScroll,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(spaces, key = { it.uuid }) { space ->
                val index = spaces.indexOf(space)
                val accent = spaceAccent(space, containers)
                FilterChip(
                    selected = pager.currentPage == index,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    label = { Text(space.name.ifBlank { "Unnamed space" }, maxLines = 1) },
                    leadingIcon = { Text(spaceGlyph(space)) },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.20f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
        HorizontalPager(
            state = pager,
            key = { spaces[it].uuid },
            pageSpacing = 16.dp,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            val space = spaces[page]
            val essentials = space.tabs.filter { it.essential }
            val pins = space.tabs.filter { it.pinned && !it.essential }
            val normal = space.tabs.filter { !it.pinned && !it.essential }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (space.tabs.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                            Text("Nothing in this space yet", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Enable normal tab syncing in Zen to include unpinned tabs.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (essentials.isNotEmpty()) {
                    item { SpaceSectionHeader("Essentials", essentials.size) }
                    items(essentials) { SpaceTabRow(it, compact = true, onOpen = { onOpenTab(it.url) }) }
                }
                if (pins.isNotEmpty()) {
                    item { SpaceSectionHeader("Pinned", pins.size) }
                    items(pins) { SpaceTabRow(it, compact = true, onOpen = { onOpenTab(it.url) }) }
                }
                if (normal.isNotEmpty()) {
                    item { SpaceSectionHeader("Tabs", normal.size) }
                    items(normal) { SpaceTabRow(it, compact = false, onOpen = { onOpenTab(it.url) }) }
                }
            }
        }
    }
}

@Composable
private fun SpaceSectionHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
    }
}

@Composable
private fun SpaceTabRow(tab: ZenSyncedTab, compact: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (compact) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f) else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = "Open tab in Tangent", onClick = onOpen)
            .heightIn(min = if (compact) 50.dp else 60.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Favicon(tab.url)
        Column(Modifier.weight(1f)) {
            Text(tab.title, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = if (compact) tab.folderName else listOfNotNull(tab.folderName,
                runCatching { URI(tab.url).host?.removePrefix("www.") }.getOrNull()).joinToString(" · ")
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun spaceGlyph(space: ZenSpace): String = space.icon
    ?.takeIf { it.length <= 6 && !it.contains('/') && !it.contains(':') }
    ?: space.name.trim().firstOrNull()?.uppercase() ?: "▢"

private fun spaceAccent(space: ZenSpace, containers: List<ZenContainer>): Color {
    val color = containers.firstOrNull {
        if (space.containerGuid != null) it.guid == space.containerGuid
        else space.containerTabId > 0 && it.userContextId == space.containerTabId
    }?.color
    val named = when (color) {
        "blue" -> Color(0xFF37ADFF)
        "turquoise" -> Color(0xFF00C79A)
        "green" -> Color(0xFF51CD00)
        "yellow" -> Color(0xFFFFCB00)
        "orange" -> Color(0xFFFF9F00)
        "red" -> Color(0xFFFF613D)
        "pink" -> Color(0xFFFF4BDA)
        "purple" -> Color(0xFFAF51F5)
        else -> null
    }
    return named ?: color?.takeIf { it.startsWith('#') }
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: Color.hsl(space.uuid.hashCode().mod(360).toFloat(), 0.52f, 0.52f)
}
