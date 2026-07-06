package com.firebolt9907.luminabrowser.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import com.firebolt9907.luminabrowser.state.PreloadMode
import com.firebolt9907.luminabrowser.state.SearchEngine
import com.firebolt9907.luminabrowser.ui.components.SheetCard
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import com.firebolt9907.luminabrowser.ui.components.SheetSectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    globalPrefsStore: GlobalPreferencesStore,
    onDismiss: () -> Unit,
    onSettingChanged: () -> Unit,
    onClearSitePermissions: () -> Unit = {},
    bottomInsetDp: Dp = 0.dp,
) {
    var trackingProtection by remember { mutableStateOf(globalPrefsStore.trackingProtection) }
    var blockPopUps by remember { mutableStateOf(globalPrefsStore.blockPopUps) }
    var javaScriptEnabled by remember { mutableStateOf(globalPrefsStore.javaScriptEnabled) }
    var httpsOnly by remember { mutableStateOf(globalPrefsStore.httpsOnly) }
    var globalPrivacyControl by remember { mutableStateOf(globalPrefsStore.globalPrivacyControl) }
    var keepAliveInBackground by remember { mutableStateOf(globalPrefsStore.keepAliveInBackground) }
    var searchEngine by remember { mutableStateOf(globalPrefsStore.searchEngine) }
    var searchSuggestionsEnabled by remember { mutableStateOf(globalPrefsStore.searchSuggestionsEnabled) }
    var httpCacheSizeMb by remember { mutableStateOf(globalPrefsStore.httpCacheSizeMb) }
    var preloadMode by remember { mutableStateOf(globalPrefsStore.preloadMode) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(bottom = bottomInsetDp) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            SheetHeader("Settings")

            Spacer(Modifier.height(20.dp))

            SheetSectionHeader("Privacy & Security")
            Spacer(Modifier.height(8.dp))
            SheetCard {
                SettingsToggle(
                    title = "Enhanced Tracking Protection",
                    subtitle = "Block trackers, fingerprinters, and cryptominers",
                    checked = trackingProtection,
                    onCheckedChange = {
                        trackingProtection = it
                        globalPrefsStore.trackingProtection = it
                        onSettingChanged()
                    },
                )
                SettingsToggle(
                    title = "Global Privacy Control",
                    subtitle = "Tell sites not to sell or share your data",
                    checked = globalPrivacyControl,
                    onCheckedChange = {
                        globalPrivacyControl = it
                        globalPrefsStore.globalPrivacyControl = it
                        onSettingChanged()
                    },
                )
                SettingsToggle(
                    title = "HTTPS-Only Mode",
                    subtitle = "Require secure connections for all sites",
                    checked = httpsOnly,
                    onCheckedChange = {
                        httpsOnly = it
                        globalPrefsStore.httpsOnly = it
                        onSettingChanged()
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            SheetSectionHeader("Content")
            Spacer(Modifier.height(8.dp))
            SheetCard {
                SettingsToggle(
                    title = "Block Pop-ups",
                    subtitle = "Prevent sites from opening new windows",
                    checked = blockPopUps,
                    onCheckedChange = {
                        blockPopUps = it
                        globalPrefsStore.blockPopUps = it
                        onSettingChanged()
                    },
                )
                SettingsToggle(
                    title = "JavaScript",
                    subtitle = "Enable JavaScript on web pages",
                    checked = javaScriptEnabled,
                    onCheckedChange = {
                        javaScriptEnabled = it
                        globalPrefsStore.javaScriptEnabled = it
                        onSettingChanged()
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            SheetSectionHeader("Search")
            Spacer(Modifier.height(8.dp))
            SheetCard {
                SettingsPicker(
                    title = "Search engine",
                    subtitle = "Used for address-bar searches and suggestions",
                    selectedLabel = searchEngine.label,
                    options = SearchEngine.entries.map { engine ->
                        engine.label to {
                            searchEngine = engine
                            globalPrefsStore.searchEngine = engine
                        }
                    },
                )
                SettingsToggle(
                    title = "Search suggestions",
                    subtitle = "Show live suggestions from ${searchEngine.label} as you type. When off, only your history and bookmarks are used and no keystrokes leave the device.",
                    checked = searchSuggestionsEnabled,
                    onCheckedChange = {
                        searchSuggestionsEnabled = it
                        globalPrefsStore.searchSuggestionsEnabled = it
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            SheetSectionHeader("Performance")
            Spacer(Modifier.height(8.dp))
            SheetCard {
                SettingsToggle(
                    title = "Keep active in background",
                    subtitle = "Reduce the chance Android closes tabs to reclaim memory. Shows a quiet notification and uses a little more battery.",
                    checked = keepAliveInBackground,
                    onCheckedChange = {
                        keepAliveInBackground = it
                        globalPrefsStore.keepAliveInBackground = it
                    },
                )
                SettingsPicker(
                    title = "Web cache size",
                    subtitle = "A larger cache reloads frequently-visited sites faster. Kept in Android's cache storage (not app data). Takes effect after restart.",
                    selectedLabel = formatCacheSize(httpCacheSizeMb),
                    options = listOf(128, 256, 512, 1024, 2048).map { mb ->
                        formatCacheSize(mb) to {
                            httpCacheSizeMb = mb
                            globalPrefsStore.httpCacheSizeMb = mb
                        }
                    },
                )
                SettingsPicker(
                    title = "Preload likely pages",
                    subtitle = "Start loading links you're likely to open (Discover/Search articles and the top address-bar match) before you tap, so they open faster. Uses extra data on pages you don't open.",
                    selectedLabel = preloadMode.label,
                    options = PreloadMode.entries.map { mode ->
                        mode.label to {
                            preloadMode = mode
                            globalPrefsStore.preloadMode = mode
                        }
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            SheetSectionHeader("Site permissions")
            Spacer(Modifier.height(8.dp))
            val context = LocalContext.current
            SheetCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onClearSitePermissions()
                            Toast.makeText(
                                context,
                                "Cleared remembered site permissions",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Clear remembered site permissions",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()
            }
            if (versionName != null) {
                Text(
                    text = "Lumina Browser $versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

private fun formatCacheSize(mb: Int): String =
    if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

/**
 * A settings row whose value is chosen from a dropdown. [options] pairs each visible label with the
 * action that applies it. The currently-selected value is shown inline to the right of the title.
 */
@Composable
private fun SettingsPicker(
    title: String,
    subtitle: String,
    selectedLabel: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(14.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, onSelect) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect()
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Row handles the toggle (and a11y); switch is visual-only to avoid double-toggle.
        Switch(checked = checked, onCheckedChange = null)
    }
}
