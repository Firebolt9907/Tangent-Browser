package com.firebolt9907.luminabrowser.ui.chrome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.widget.Toast
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.firebolt9907.luminabrowser.ui.reader.ReaderTheme
import com.firebolt9907.luminabrowser.ui.reader.ReaderManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.luminabrowser.addons.AddonController
import com.firebolt9907.luminabrowser.addons.InstalledAddon
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import com.firebolt9907.luminabrowser.state.hostFromUrl
import com.firebolt9907.luminabrowser.state.SitePreferences
import com.firebolt9907.luminabrowser.state.SitePreferencesStore
import com.firebolt9907.luminabrowser.ui.addons.AddonsManagerSheetContent
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

// A native app (e.g. Instagram, YouTube) that is associated with a specific URL.
private data class NativeApp(val packageName: String, val label: String)

/**
 * Resolve the non-browser app associated with [url], the way Chrome offers to open an
 * instagram.com link in the Instagram app. The heuristic: a "native app" handler is one
 * that handles this *specific* URL but does NOT handle arbitrary https URLs (which is what
 * a browser does). Returns null when only browsers (or nothing) can open the link.
 */
private fun nativeAppFor(pm: PackageManager, url: String): NativeApp? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    if (uri.scheme != "http" && uri.scheme != "https") return null

    // Packages that handle *any* https URL are general-purpose browsers.
    val genericProbe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val browserPkgs = pm.queryIntentActivities(genericProbe, 0)
        .map { it.activityInfo.packageName }
        .toSet()

    val specific = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    val handler = pm.queryIntentActivities(specific, 0).firstOrNull { ri ->
        val pkg = ri.activityInfo.packageName
        pkg != OWN_PKG && pkg !in browserPkgs
    } ?: return null

    return NativeApp(
        packageName = handler.activityInfo.packageName,
        label = handler.loadLabel(pm).toString(),
    )
}

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
    addonController: AddonController,
    onDismiss: () -> Unit,
    onOpenAddonSettings: (InstalledAddon) -> Unit = {},
    onFindInPage: () -> Unit = {},
    pageUrl: String = "",
    pageTitle: String = "",
    bottomInsetDp: Dp = 0.dp,
    initialTab: Int = 0,
    tabId: String? = null,
    engineSession: mozilla.components.concept.engine.EngineSession? = null,
    backgroundSnapshot: android.graphics.Bitmap? = null,
    statusBarTopPad: Dp = 0.dp,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    val sheetState = rememberModalBottomSheetState()

    // iOS-style scaled-page backdrop behind the sheet. Driven by the sheet's own show/hide target so
    // the page scales down on open and back up on dismiss; read lazily so it doesn't recompose here.
    val showingTarget = sheetState.targetValue != SheetValue.Hidden
    val backdropFraction = animateFloatAsState(
        targetValue = if (showingTarget) 1f else 0f,
        animationSpec = tween(300),
        label = "aaBackdrop",
    )
    ScaledPageBackdrop(
        snapshot = backgroundSnapshot,
        fraction = { backdropFraction.value },
        statusBarTopPad = statusBarTopPad,
        onTap = onDismiss,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // The backdrop provides the dimming; the default scrim would paint over it.
        scrimColor = Color.Transparent,
        contentWindowInsets = { WindowInsets(bottom = bottomInsetDp) },
    ) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Page") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Add-ons") },
            )
        }

        when (selectedTab) {
            0 -> PageTabContent(
                host = host,
                store = store,
                globalPrefsStore = globalPrefsStore,
                onDismiss = onDismiss,
                onFindInPage = onFindInPage,
                pageUrl = pageUrl,
                pageTitle = pageTitle,
                tabId = tabId,
                engineSession = engineSession,
            )
            1 -> {
                val addonState by addonController.state.collectAsStateWithLifecycle()
                var installText by rememberSaveable { mutableStateOf("") }
                AddonsManagerSheetContent(
                    state = addonState,
                    installText = installText,
                    onInstallTextChange = { installText = it },
                    onInstall = { addonController.install(installText) },
                    onEnabledChange = addonController::setEnabled,
                    onOpenSettings = onOpenAddonSettings,
                    onUninstall = addonController::uninstall,
                )
            }
        }
    }
}

@Composable
private fun PageTabContent(
    host: String,
    store: SitePreferencesStore,
    globalPrefsStore: GlobalPreferencesStore,
    onDismiss: () -> Unit,
    onFindInPage: () -> Unit = {},
    pageUrl: String,
    pageTitle: String = "",
    tabId: String? = null,
    engineSession: mozilla.components.concept.engine.EngineSession? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val initial = remember(host) { store.get(host) }
    var zoomFactor by remember(host) { mutableFloatStateOf(initial.zoomFactor) }
    var topPaddingFix by remember(host) { mutableStateOf(initial.topPaddingFix) }
    var desktopMode by remember(host) { mutableStateOf(initial.desktopMode) }
    val canEdit = host.isNotEmpty()

    // null = not set, "" = Default browser, "com.foo" = specific app
    var preferredBrowserPkg by remember {
        mutableStateOf(globalPrefsStore.preferredBrowserPackage)
    }
    var showBrowserPicker by remember { mutableStateOf(false) }

    fun persist(
        zoom: Float = zoomFactor,
        top: Boolean = topPaddingFix,
        desktop: Boolean = desktopMode,
    ) {
        if (!canEdit) return
        store.set(host, SitePreferences(topPaddingFix = top, zoomFactor = zoom, desktopMode = desktop))
    }

    val hasRealUrl = pageUrl.isNotBlank() && pageUrl != "about:blank"

    if (showBrowserPicker) {
        // installedBrowsers runs several PackageManager queries; produce it off the main thread so
        // opening the picker never blocks composition. Empty until the IO pass returns (one frame).
        val allBrowsers by produceState(initialValue = emptyList<BrowserInfo>(), context) {
            value = withContext(Dispatchers.IO) { installedBrowsers(context.packageManager) }
        }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 28.dp),
    ) {
        val readerManager = ReaderManager.instance
        val activeReaderTabs by readerManager.activeReaderTabs.collectAsStateWithLifecycle()
        val eligibleReaderTabs by readerManager.eligibleReaderTabs.collectAsStateWithLifecycle()
        val pendingActivationTabs by readerManager.pendingActivationTabs.collectAsStateWithLifecycle()
        val readerTheme by readerManager.theme.collectAsStateWithLifecycle()
        val readerFontSize by readerManager.fontSize.collectAsStateWithLifecycle()
        val readerFontName by readerManager.fontName.collectAsStateWithLifecycle()

        val isEligible = tabId != null && eligibleReaderTabs.contains(tabId)
        val isActive = tabId != null && activeReaderTabs.contains(tabId)
        val isPending = tabId != null && pendingActivationTabs.contains(tabId)

        // Whether reader is remembered for this domain (drives the "stays on" hint below). Held in
        // state so the hint updates immediately when the user toggles reader from this sheet.
        var readerPreferred by remember(pageUrl) {
            mutableStateOf(store.isReaderPreferred(hostFromUrl(pageUrl)))
        }

        if (hasRealUrl) {
            TextButton(
                onClick = {
                    // Remember the choice per-domain so future pages on this site honor it.
                    val readerHost = hostFromUrl(pageUrl)
                    if (isActive) {
                        tabId?.let { readerManager.setReaderActive(it, false) }
                        store.setReaderPreferred(readerHost, false)
                        readerPreferred = false
                    } else if (isEligible) {
                        tabId?.let { readerManager.setReaderActive(it, true) }
                        store.setReaderPreferred(readerHost, true)
                        readerPreferred = true
                    } else if (tabId != null && engineSession != null && !isPending) {
                        readerManager.requestReaderActivation(tabId)
                        store.setReaderPreferred(readerHost, true)
                        readerPreferred = true
                        com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor.extractReaderContent(engineSession, tabId)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isActive) "Hide Reader" else if (isPending) "Loading Reader…" else "Show Reader",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (readerPreferred) {
                Text(
                    text = "Reader stays on for this site",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                )
            }

            if (isActive) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReaderTheme.entries.forEach { themeOption ->
                        val optionBg = when (themeOption) {
                            ReaderTheme.White -> Color(0xFFFFFFFF)
                            ReaderTheme.Sepia -> Color(0xFFF4ECD8)
                            ReaderTheme.Dark -> Color(0xFF1C1C1E)
                        }
                        val optionBorderColor = if (readerTheme == themeOption) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.LightGray
                        }
                        val themeLabel = "${themeOption.name} reader theme"
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(optionBg)
                                .border(
                                    width = if (readerTheme == themeOption) 3.dp else 1.dp,
                                    color = optionBorderColor,
                                    shape = CircleShape
                                )
                                .semantics {
                                    contentDescription = themeLabel
                                    if (readerTheme == themeOption) selected = true
                                }
                                .clickable { readerManager.setTheme(themeOption) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { readerManager.setFontSize(readerFontSize - 10) },
                        enabled = readerFontSize > 50,
                        modifier = Modifier.semantics {
                            contentDescription = "Decrease reader font size"
                        }
                    ) {
                        Text("A", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "${readerFontSize}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { readerManager.setFontSize(readerFontSize + 10) },
                        enabled = readerFontSize < 200,
                        modifier = Modifier.semantics {
                            contentDescription = "Increase reader font size"
                        }
                    ) {
                        Text("A", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("System", "Georgia", "Charter", "Athelas").forEach { name ->
                        val fontTypeface = when (name) {
                            "Georgia" -> FontFamily(Typeface.create("georgia", Typeface.NORMAL))
                            "Charter" -> FontFamily(Typeface.create("charter", Typeface.NORMAL))
                            "Athelas" -> FontFamily(Typeface.create("athelas", Typeface.NORMAL))
                            else -> FontFamily.Default
                        }
                        val fontSelected = readerFontName == name
                        TextButton(
                            onClick = { readerManager.setFontName(name) },
                            modifier = Modifier.semantics {
                                contentDescription = "$name reader font"
                                if (fontSelected) selected = true
                            },
                        ) {
                            Text(
                                text = name,
                                fontFamily = fontTypeface,
                                fontWeight = if (fontSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (fontSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
        }

        Text(
            text = if (canEdit) host else "Page",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                // Nothing to reset when already at 100%.
                enabled = canEdit && zoomFactor != 1.0f,
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
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = topPaddingFix,
                    enabled = canEdit,
                    role = Role.Switch,
                    onValueChange = {
                        topPaddingFix = it
                        persist(top = it)
                    },
                ),
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
            // Row owns the toggle (and a11y); switch is visual-only to avoid a double-toggle.
            Switch(
                checked = topPaddingFix,
                onCheckedChange = null,
                enabled = canEdit,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = desktopMode,
                    enabled = canEdit,
                    role = Role.Switch,
                    onValueChange = {
                        desktopMode = it
                        persist(desktop = it)
                        // Apply immediately to the live session and reload so the new UA takes hold.
                        engineSession?.toggleDesktopMode(it, reload = true)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Request desktop site",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Load the desktop layout for this site",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = desktopMode,
                onCheckedChange = null,
                enabled = canEdit,
            )
        }

        if (hasRealUrl) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // Open in associated native app (e.g. Instagram for an instagram.com link),
            // shown only when a non-browser app claims this specific URL. Resolved off the main
            // thread (two PackageManager queries) so it never blocks the sheet; null until ready.
            val nativeApp by produceState<NativeApp?>(initialValue = null, pageUrl, context) {
                value = withContext(Dispatchers.IO) { nativeAppFor(context.packageManager, pageUrl) }
            }
            val resolvedNativeApp = nativeApp
            if (resolvedNativeApp != null) {
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).apply {
                            setPackage(resolvedNativeApp.packageName)
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)))
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionRowContent(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        text = "Open in ${resolvedNativeApp.label}",
                        bold = true,
                    )
                }
            }

            // Find in page button
            TextButton(
                onClick = {
                    onFindInPage()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ActionRowContent(icon = Icons.Default.Search, text = "Find in Page")
            }

            // Copy link button
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(pageUrl))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ActionRowContent(icon = Icons.Default.ContentCopy, text = "Copy link")
            }

            // Share button
            TextButton(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, pageUrl)
                        // Title becomes the subject line in apps that use one (email, etc.).
                        if (pageTitle.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, pageTitle)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, null))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ActionRowContent(icon = Icons.Default.Share, text = "Share")
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
                    ActionRowContent(icon = Icons.Default.Public, text = openLabel)
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

/** A full-width sheet action row: leading icon, then a left-aligned label. */
@Composable
private fun ActionRowContent(
    icon: ImageVector,
    text: String,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
