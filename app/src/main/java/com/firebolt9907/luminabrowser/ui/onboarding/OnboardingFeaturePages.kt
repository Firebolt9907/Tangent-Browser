package com.firebolt9907.luminabrowser.ui.onboarding

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.luminabrowser.LuminaApplication
import com.firebolt9907.luminabrowser.state.SearchEngine
import com.firebolt9907.luminabrowser.ui.grid.TabViewMode

/**
 * Three self-contained onboarding pages. Each is a vertically-centred [Column] with a large icon,
 * headline, supporting text and (for the interactive pages) controls. The host pager owns the
 * page-navigation buttons — these composables must not add their own.
 */

private const val PAGE_H_PADDING = 32

@Composable
private fun OnboardingScaffold(
    icon: ImageVector,
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_H_PADDING.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        content()
    }
}

/**
 * Interactive privacy page. Each toggle mirrors the Settings sheet: it writes the preference and
 * re-applies engine settings so the change takes effect on the live engine, exactly as the Settings
 * screen does via `onSettingChanged` → [applyEngineSettings].
 */
@Composable
fun PrivacyPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val components = remember(context) {
        (context.applicationContext as LuminaApplication).components
    }
    val store = components.globalPreferencesStore

    var tracking by remember { mutableStateOf(store.trackingProtection) }
    var httpsOnly by remember { mutableStateOf(store.httpsOnly) }
    var gpc by remember { mutableStateOf(store.globalPrivacyControl) }
    var blockPopUps by remember { mutableStateOf(store.blockPopUps) }

    OnboardingScaffold(
        icon = Icons.Rounded.Shield,
        headline = "Private by default",
        body = "Lumina blocks trackers out of the box. Fine-tune your protection — you can change " +
            "any of this later in Settings.",
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleRow(
                icon = Icons.Rounded.Shield,
                title = "Enhanced Tracking Protection",
                subtitle = "Block trackers, fingerprinters and cryptominers",
                checked = tracking,
                onCheckedChange = {
                    tracking = it
                    store.trackingProtection = it
                    components.applyEngineSettings()
                },
            )
            ToggleRow(
                icon = Icons.Rounded.Lock,
                title = "HTTPS-Only Mode",
                subtitle = "Require secure connections for every site",
                checked = httpsOnly,
                onCheckedChange = {
                    httpsOnly = it
                    store.httpsOnly = it
                    components.applyEngineSettings()
                },
            )
            ToggleRow(
                icon = Icons.Rounded.PrivacyTip,
                title = "Global Privacy Control",
                subtitle = "Tell sites not to sell or share your data",
                checked = gpc,
                onCheckedChange = {
                    gpc = it
                    store.globalPrivacyControl = it
                    components.applyEngineSettings()
                },
            )
            ToggleRow(
                icon = Icons.Rounded.Block,
                title = "Block pop-ups",
                subtitle = "Stop sites from opening unwanted windows",
                checked = blockPopUps,
                onCheckedChange = {
                    blockPopUps = it
                    store.blockPopUps = it
                    components.applyEngineSettings()
                },
            )
        }
    }
}

/**
 * Interactive appearance page. These are pure UI preferences read live by LuminaRoot, so they only
 * need to be persisted — no engine re-apply.
 */
@Composable
fun AppearancePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as LuminaApplication).components.globalPreferencesStore
    }

    var bottomToolbar by remember { mutableStateOf(store.showBottomToolbar) }
    var searchSuggestions by remember { mutableStateOf(store.searchSuggestionsEnabled) }
    var tabViewMode by remember { mutableStateOf(TabViewMode.fromName(store.tabViewModeName)) }

    OnboardingScaffold(
        icon = Icons.Rounded.Palette,
        headline = "Make it yours",
        body = "A couple of quick choices to set up how Lumina looks and feels.",
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleRow(
                icon = Icons.Rounded.SwapVert,
                title = "Bottom toolbar",
                subtitle = "Keep the address bar within thumb's reach",
                checked = bottomToolbar,
                onCheckedChange = {
                    bottomToolbar = it
                    store.showBottomToolbar = it
                },
            )
            ToggleRow(
                icon = Icons.Rounded.ManageSearch,
                title = "Search suggestions",
                subtitle = "Show suggestions as you type in the address bar",
                checked = searchSuggestions,
                onCheckedChange = {
                    searchSuggestions = it
                    store.searchSuggestionsEnabled = it
                },
            )

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tab overview",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "How your open tabs are laid out",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TabViewModeOption(
                    icon = Icons.Rounded.ViewCarousel,
                    label = "Deck",
                    selected = tabViewMode == TabViewMode.DECK,
                    modifier = Modifier.weight(1f),
                ) {
                    tabViewMode = TabViewMode.DECK
                    store.tabViewModeName = TabViewMode.DECK.name
                }
                TabViewModeOption(
                    icon = Icons.Rounded.GridView,
                    label = "Grid",
                    selected = tabViewMode == TabViewMode.GRID,
                    modifier = Modifier.weight(1f),
                ) {
                    tabViewMode = TabViewMode.GRID
                    store.tabViewModeName = TabViewMode.GRID.name
                }
                TabViewModeOption(
                    icon = Icons.Rounded.ViewColumn,
                    label = "Filmstrip",
                    selected = tabViewMode == TabViewMode.PIXEL,
                    modifier = Modifier.weight(1f),
                ) {
                    tabViewMode = TabViewMode.PIXEL
                    store.tabViewModeName = TabViewMode.PIXEL.name
                }
            }
        }
    }
}

@Composable
private fun TabViewModeOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/**
 * Optional Firefox Sync sign-in. Tapping "Sign in with Firefox" launches the Mozilla-account OAuth
 * flow (via [FirefoxSyncController.beginSignIn]) and hands the URL to [onSignInUrl], which renders it
 * in a standalone sign-in card *over* onboarding (the same UtilityWebCard the synced-tabs sheet uses)
 * rather than opening a real tab or dismissing onboarding — so the user returns here, now signed in,
 * to finish the remaining steps. Beyond Firefox's own tabs, our sync layer also reads Zen browser's
 * `workspaces` collection, so a Zen user's spaces show up here too.
 */
@Composable
fun FirefoxSyncPage(
    onSignInUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val components = remember(context) {
        (context.applicationContext as LuminaApplication).components
    }
    val controller = components.firefoxSyncController
    val state by controller.state.collectAsStateWithLifecycle()

    OnboardingScaffold(
        icon = if (state.isSignedIn) Icons.Rounded.CloudDone else Icons.Rounded.CloudSync,
        headline = if (state.isSignedIn) "Sync is on" else "Sync your browsing",
        body = if (state.isSignedIn) {
            "You're signed in" + (state.accountEmail?.let { " as $it" } ?: "") +
                ". Your tabs and history stay in step across your devices."
        } else {
            "Sign in with a Mozilla account to bring your tabs, history and passwords across every " +
                "device. Already use Zen browser? Lumina also reads your Zen spaces and workspaces, " +
                "so those come along too."
        },
        modifier = modifier,
    ) {
        if (state.isSignedIn) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Signed in to Firefox Sync",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Button(
                enabled = !state.isBusy,
                onClick = {
                    controller.beginSignIn { url -> onSignInUrl(url) }
                },
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text("Sign in with Firefox")
            }
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        // Whole-row toggle (the M3/iOS settings convention) instead of only the small Switch being a
        // tap target. Role.Switch + presentational Switch (onCheckedChange = null) merges semantics so
        // a screen reader announces one switch, not a row plus a separate control.
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Interactive search-engine picker. Persists the choice to [SearchEngine] on the preferences store. */
@Composable
fun SearchEnginePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as LuminaApplication).components.globalPreferencesStore
    }
    var selected by remember { mutableStateOf(store.searchEngine) }

    OnboardingScaffold(
        icon = Icons.Rounded.Search,
        headline = "Choose your search engine",
        body = "This is where your address-bar searches go. You can change it later in Settings.",
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchEngine.entries.forEach { engine ->
                val isSelected = engine == selected
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                onClick = {
                                    selected = engine
                                    store.searchEngine = engine
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                selected = engine
                                store.searchEngine = engine
                            },
                        )
                        Text(
                            text = engine.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** Interactive "make Lumina the default browser" page. Uses RoleManager on API 29+, else settings. */
@Composable
fun DefaultBrowserPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isDefault by remember { mutableStateOf(isDefaultBrowser(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isDefault = isDefaultBrowser(context) }

    // The user may set the default from the system UI and come back — re-check on resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = isDefaultBrowser(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OnboardingScaffold(
        icon = Icons.Rounded.OpenInBrowser,
        headline = "Make Lumina your default",
        body = if (isDefault) {
            "Lumina is already your default browser. Links from other apps will open right here."
        } else {
            "Open links from your other apps in Lumina so every page arrives fast and private."
        },
        modifier = modifier,
    ) {
        if (isDefault) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Lumina is your default",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Button(onClick = { launchDefaultBrowserRequest(context, launcher) }) {
                Text("Set as default")
            }
        }
    }
}

/** Whether Lumina currently holds the browser role / is the default handler for web links. */
private fun isDefaultBrowser(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
            return rm.isRoleHeld(RoleManager.ROLE_BROWSER)
        }
    }
    return false
}

private fun launchDefaultBrowserRequest(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm != null &&
            rm.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            !rm.isRoleHeld(RoleManager.ROLE_BROWSER)
        ) {
            runCatching {
                launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
            }.onSuccess { return }
        }
    }
    // Older devices (or if the role intent failed): open the system default-apps settings.
    runCatching {
        launcher.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
}
