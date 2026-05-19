package com.firebolt9907.luminabrowser.ui.chrome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import com.firebolt9907.luminabrowser.state.SitePreferences
import com.firebolt9907.luminabrowser.state.SitePreferencesStore
import kotlin.math.roundToInt

private const val OWN_PKG = "com.firebolt9907.luminabrowser"

// packageName "" = "Default browser" (no package constraint on the intent)
private data class BrowserInfo(val packageName: String, val label: String)

private val HARDCODED_BROWSERS = listOf(
    BrowserInfo("", "Default browser"),
    BrowserInfo("com.android.chrome", "Chrome"),
    BrowserInfo("org.mozilla.firefox", "Firefox"),
    BrowserInfo("org.mozilla.fenix", "Firefox Nightly"),
)

private fun installedBrowsers(pm: PackageManager): List<BrowserInfo> {
    // Use flags=0 to get every app that handles https, not only the current default.
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    val dynamic = pm.queryIntentActivities(probe, 0)
        .map { ri ->
            BrowserInfo(
                packageName = ri.activityInfo.packageName,
                label = ri.loadLabel(pm).toString(),
            )
        }
        .distinctBy { it.packageName }
        .filter { it.packageName != OWN_PKG }

    val dynamicPkgs = dynamic.map { it.packageName }.toSet()

    // For hardcoded entries also verify installation directly, since some packages
    // may not surface via queryIntentActivities on all Android versions.
    val hardcodedAvailable = HARDCODED_BROWSERS.filter { b ->
        b.packageName.isEmpty() ||
            b.packageName in dynamicPkgs ||
            runCatching { pm.getApplicationInfo(b.packageName, 0) }.isSuccess
    }
    val hardcodedPkgs = hardcodedAvailable.map { it.packageName }.toSet()
    val rest = dynamic.filter { it.packageName !in hardcodedPkgs }.sortedBy { it.label }
    return hardcodedAvailable + rest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AaSheet(
    host: String,
    store: SitePreferencesStore,
    globalPrefsStore: GlobalPreferencesStore,
    onDismiss: () -> Unit,
    pageUrl: String = "",
    bottomInsetDp: Dp = 0.dp,
) {
    val context = LocalContext.current
    val initial = remember(host) { store.get(host) }
    var zoomFactor by remember(host) { mutableStateOf(initial.zoomFactor) }
    var topPaddingFix by remember(host) { mutableStateOf(initial.topPaddingFix) }
    val canEdit = host.isNotEmpty()

    // null = not set, "" = Default browser, "com.foo" = specific app
    var preferredBrowserPkg by remember {
        mutableStateOf(globalPrefsStore.preferredBrowserPackage)
    }
    var showBrowserPicker by remember { mutableStateOf(false) }

    fun persist(zoom: Float = zoomFactor, top: Boolean = topPaddingFix) {
        if (!canEdit) return
        store.set(host, SitePreferences(topPaddingFix = top, zoomFactor = zoom))
    }

    val hasRealUrl = pageUrl.isNotBlank() && pageUrl != "about:blank"

    if (showBrowserPicker) {
        val allBrowsers = remember { installedBrowsers(context.packageManager) }
        val recentPkgs = remember { globalPrefsStore.recentBrowserPackages }
        val recentBrowsers = remember(recentPkgs, allBrowsers) {
            val allByPkg = allBrowsers.associateBy { it.packageName }
            recentPkgs.mapNotNull { pkg -> allByPkg[pkg] }
        }
        val otherBrowsers = remember(recentPkgs, allBrowsers) {
            allBrowsers.filter { it.packageName !in recentPkgs }
        }

        fun pick(browser: BrowserInfo) {
            preferredBrowserPkg = browser.packageName
            globalPrefsStore.preferredBrowserPackage = browser.packageName
            globalPrefsStore.recordRecentBrowser(browser.packageName)
            showBrowserPicker = false
        }

        AlertDialog(
            onDismissRequest = { showBrowserPicker = false },
            title = { Text("Choose fallback browser") },
            text = {
                Column {
                    if (recentBrowsers.isNotEmpty()) {
                        Text(
                            text = "Recent",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                        recentBrowsers.forEach { browser ->
                            TextButton(
                                onClick = { pick(browser) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Box(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = browser.label,
                                        modifier = Modifier.align(Alignment.CenterStart),
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    otherBrowsers.forEach { browser ->
                        TextButton(
                            onClick = { pick(browser) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(Modifier.fillMaxWidth()) {
                                Text(
                                    text = browser.label,
                                    modifier = Modifier.align(Alignment.CenterStart),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBrowserPicker = false }) { Text("Cancel") }
            },
        )
    }

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
            Text(
                text = if (canEdit) "Settings — $host" else "Settings",
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = {
                        val next = ((zoomFactor - 0.1f) * 100f).roundToInt() / 100f
                        zoomFactor = next.coerceIn(0.5f, 2.0f)
                        persist(zoom = zoomFactor)
                    },
                    enabled = canEdit && zoomFactor > 0.5f,
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom out")
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${(zoomFactor * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(
                    onClick = {
                        val next = ((zoomFactor + 0.1f) * 100f).roundToInt() / 100f
                        zoomFactor = next.coerceIn(0.5f, 2.0f)
                        persist(zoom = zoomFactor)
                    },
                    enabled = canEdit && zoomFactor < 2.0f,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom in")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(
                    onClick = {
                        zoomFactor = 1.0f
                        persist(zoom = 1.0f)
                    },
                    enabled = canEdit,
                ) {
                    Text("Reset")
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Per-site overrides",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add top padding for this site",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Use when site headers don't move into the safe area",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = topPaddingFix,
                    onCheckedChange = {
                        topPaddingFix = it
                        persist(top = it)
                    },
                    enabled = canEdit,
                )
            }

            if (hasRealUrl) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))

                // Share button
                TextButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, pageUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, null))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Share")
                }

                // Open in browser row — label + Change button
                val pm = context.packageManager
                val preferredLabel = remember(preferredBrowserPkg) {
                    val pkg = preferredBrowserPkg
                    when {
                        pkg == null -> null
                        pkg.isEmpty() -> "default browser"
                        else -> runCatching {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        }.getOrNull()
                    }
                }
                val openLabel = if (preferredLabel != null) "Open in $preferredLabel" else "Open in browser"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val uri = Uri.parse(pageUrl)
                            val pkg = preferredBrowserPkg
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                if (!pkg.isNullOrEmpty()) setPackage(pkg)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            Text(
                                text = openLabel,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        }
                    }
                    TextButton(onClick = { showBrowserPicker = true }) {
                        Text(
                            text = "Change",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
