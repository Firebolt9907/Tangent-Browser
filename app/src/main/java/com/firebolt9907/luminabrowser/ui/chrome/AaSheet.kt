package com.firebolt9907.luminabrowser.ui.chrome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import com.firebolt9907.luminabrowser.ui.reader.PageSpeaker
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarksStorage
import mozilla.components.concept.storage.BookmarkNodeType
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.firebolt9907.luminabrowser.MainActivity
import com.firebolt9907.luminabrowser.R
import androidx.compose.material.icons.filled.Home
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.firebolt9907.luminabrowser.ui.reader.ReaderTheme
import com.firebolt9907.luminabrowser.ui.reader.ReaderManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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

/** Folder (under the Mobile bookmarks root) that backs the Safari-style Reading List. */
private const val READING_LIST_FOLDER = "Reading List"

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
    val uri = runCatching { url.toUri() }.getOrNull() ?: return null
    if (uri.scheme != "http" && uri.scheme != "https") return null

    // Packages that handle *any* https URL are general-purpose browsers.
    val genericProbe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
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
    val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
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
    onOpenAddonQuickSettings: (InstalledAddon) -> Unit = {},
    onFindInPage: () -> Unit = {},
    pageUrl: String = "",
    pageTitle: String = "",
    bottomInsetDp: Dp = 0.dp,
    initialTab: Int = 0,
    tabId: String? = null,
    engineSession: mozilla.components.concept.engine.EngineSession? = null,
    backgroundSnapshot: android.graphics.Bitmap? = null,
    statusBarTopPad: Dp = 0.dp,
    bookmarksStorage: BookmarksStorage? = null,
    pageIcon: android.graphics.Bitmap? = null,
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
                bookmarksStorage = bookmarksStorage,
                pageIcon = pageIcon,
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
                    onOpenQuickSettings = onOpenAddonQuickSettings,
                    onUninstall = addonController::uninstall,
                )
            }
        }
    }
}

/** One tile in the quick-action row: an icon chip over a short label. */
private data class QuickAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
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
    bookmarksStorage: BookmarksStorage? = null,
    pageIcon: android.graphics.Bitmap? = null,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val bookmarkScope = rememberCoroutineScope()
    val initial = remember(host) { store.get(host) }
    var zoomFactor by remember(host) { mutableFloatStateOf(initial.zoomFactor) }
    var topPaddingFix by remember(host) { mutableStateOf(initial.topPaddingFix) }
    var desktopMode by remember(host) { mutableStateOf(initial.desktopMode) }
    var useGoogleCache by remember { mutableStateOf(globalPrefsStore.useGoogleCache) }
    var showDebugCacheToggle by remember { mutableStateOf(false) }
    val canEdit = host.isNotEmpty()

    // null = not set, "" = Default browser, "com.foo" = specific app
    var preferredBrowserPkg by remember {
        mutableStateOf(globalPrefsStore.preferredBrowserPackage)
    }
    var showBrowserPicker by remember { mutableStateOf(false) }
    // Advanced per-site toggles are collapsed by default — most users never touch them.
    var pageSettingsExpanded by rememberSaveable { mutableStateOf(false) }

    fun persist(
        zoom: Float = zoomFactor,
        top: Boolean = topPaddingFix,
        desktop: Boolean = desktopMode,
    ) {
        if (!canEdit) return
        store.set(host, SitePreferences(topPaddingFix = top, zoomFactor = zoom, desktopMode = desktop))
    }

    val hasRealUrl = pageUrl.isNotBlank() && pageUrl != "about:blank"
    // Persistent actions (home-screen shortcut, bookmark, reading list) only make sense for real
    // web pages — not javascript:/data:/file:/about: URLs.
    val isWebPage = pageUrl.startsWith("http://", ignoreCase = true) ||
        pageUrl.startsWith("https://", ignoreCase = true)

    // ---- Shared action handlers (reused by the quick-action tiles and secondary rows) ----
    fun doShare() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, pageUrl)
            // Title becomes the subject line in apps that use one (email, etc.).
            if (pageTitle.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, pageTitle)
        }
        try {
            context.startActivity(Intent.createChooser(shareIntent, null))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
        }
        onDismiss()
    }

    fun doCopy() {
        clipboard.setText(AnnotatedString(pageUrl))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    fun doFind() {
        onFindInPage()
        onDismiss()
    }

    fun doBookmark() {
        val storage = bookmarksStorage ?: return
        val url = pageUrl
        val title = pageTitle.ifBlank { url }
        bookmarkScope.launch {
            val ok = runCatching {
                storage.addItem(
                    parentGuid = BookmarkRoot.Mobile.id,
                    url = url,
                    title = title,
                    position = null,
                ).getOrThrow()
            }.isSuccess
            Toast.makeText(
                context,
                if (ok) "Bookmarked" else "Couldn't add bookmark",
                Toast.LENGTH_SHORT,
            ).show()
        }
        onDismiss()
    }

    fun doReadingList() {
        val storage = bookmarksStorage ?: return
        val url = pageUrl
        val title = pageTitle.ifBlank { url }
        bookmarkScope.launch {
            val ok = runCatching {
                val root = storage.getTree(BookmarkRoot.Mobile.id, recursive = false).getOrNull()
                val folderGuid = root?.children
                    ?.firstOrNull { it.type == BookmarkNodeType.FOLDER && it.title == READING_LIST_FOLDER }
                    ?.guid
                    ?: storage.addFolder(BookmarkRoot.Mobile.id, READING_LIST_FOLDER, null).getOrThrow()
                storage.addItem(
                    parentGuid = folderGuid,
                    url = url,
                    title = title,
                    position = null,
                ).getOrThrow()
            }.isSuccess
            Toast.makeText(
                context,
                if (ok) "Added to Reading List" else "Couldn't add to Reading List",
                Toast.LENGTH_SHORT,
            ).show()
        }
        onDismiss()
    }

    fun doAddToHomeScreen() {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            val label = pageTitle.ifBlank { host.ifBlank { pageUrl } }
            val shortcutIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = pageUrl.toUri()
                putExtra(MainActivity.EXTRA_HOME_SHORTCUT, true)
            }
            // Use the page favicon when available (Safari does), else the app icon.
            val shortcutIcon = pageIcon?.let { IconCompat.createWithBitmap(it) }
                ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            val info = ShortcutInfoCompat.Builder(context, "lumina-${pageUrl.hashCode()}")
                .setShortLabel(label.take(24))
                .setLongLabel(label.take(48))
                .setIcon(shortcutIcon)
                .setIntent(shortcutIntent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        } else {
            Toast.makeText(context, "Not supported on this launcher", Toast.LENGTH_SHORT).show()
        }
        onDismiss()
    }

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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        val readerManager = ReaderManager.instance
        val activeReaderTabs by readerManager.activeReaderTabs.collectAsStateWithLifecycle()
        val eligibleReaderTabs by readerManager.eligibleReaderTabs.collectAsStateWithLifecycle()
        val pendingActivationTabs by readerManager.pendingActivationTabs.collectAsStateWithLifecycle()
        val readerTheme by readerManager.theme.collectAsStateWithLifecycle()
        val readerFontSize by readerManager.fontSize.collectAsStateWithLifecycle()
        val readerFontName by readerManager.fontName.collectAsStateWithLifecycle()
        val tabArticles by readerManager.tabArticles.collectAsStateWithLifecycle()
        val listenArticle = tabId?.let { tabArticles[it] }
        val isSpeaking by PageSpeaker.speaking.collectAsStateWithLifecycle()

        val isEligible = tabId != null && eligibleReaderTabs.contains(tabId)
        val isActive = tabId != null && activeReaderTabs.contains(tabId)
        val isPending = tabId != null && pendingActivationTabs.contains(tabId)

        // Whether reader is remembered for this domain (drives the "stays on" hint below). Held in
        // state so the hint updates immediately when the user toggles reader from this sheet.
        var readerPreferred by remember(pageUrl) {
            mutableStateOf(store.isReaderPreferred(hostFromUrl(pageUrl)))
        }

        // ---------- 1. Page header: favicon + title + host ----------
        PageHeader(
            pageIcon = pageIcon,
            title = pageTitle.ifBlank { if (canEdit) host else "Page" },
            host = if (canEdit) host else "",
        )

        // ---------- 2. Quick actions: the everyday, high-priority actions ----------
        if (hasRealUrl) {
            val quickActions = buildList {
                add(QuickAction(Icons.Default.Share, "Share", ::doShare))
                if (bookmarksStorage != null && isWebPage) {
                    add(QuickAction(Icons.Default.Star, "Bookmark", ::doBookmark))
                }
                add(QuickAction(Icons.Default.ContentCopy, "Copy", ::doCopy))
                add(QuickAction(Icons.Default.Search, "Find", ::doFind))
                if (isWebPage) {
                    add(QuickAction(Icons.Default.Home, "Home", ::doAddToHomeScreen))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickActions.forEach { action ->
                    QuickActionTile(action = action, modifier = Modifier.weight(1f))
                }
            }
        }

        // ---------- 3. Reader ----------
        if (hasRealUrl) {
            Spacer(Modifier.height(18.dp))
            TextButton(
                onClick = {
                    val readerHost = hostFromUrl(pageUrl)
                    if (isActive) {
                        tabId?.let {
                            val originalUrl = readerManager.getOriginalUrl(it)
                            if (originalUrl != null) {
                                readerManager.setRestoring(it, true)
                                engineSession?.loadUrl(originalUrl)
                                readerManager.setOriginalUrl(it, null)
                            }
                            readerManager.setReaderActive(it, false)
                        }
                        store.setReaderPreferred(readerHost, false)
                        readerPreferred = false
                    } else if (isEligible) {
                        tabId?.let {
                            if (readerManager.useGoogleCache.value && com.firebolt9907.luminabrowser.ui.reader.shouldRedirectToGoogleCache(pageUrl)) {
                                readerManager.requestReaderActivation(it)
                                readerManager.setOriginalUrl(it, pageUrl)
                                val cacheUrl = com.firebolt9907.luminabrowser.ui.reader.getGoogleCacheUrl(pageUrl)
                                engineSession?.loadUrl(cacheUrl)
                            } else {
                                readerManager.setReaderActive(it, true)
                            }
                        }
                        store.setReaderPreferred(readerHost, true)
                        readerPreferred = true
                    } else if (tabId != null && engineSession != null && !isPending) {
                        readerManager.requestReaderActivation(tabId)
                        store.setReaderPreferred(readerHost, true)
                        readerPreferred = true
                        if (readerManager.useGoogleCache.value && com.firebolt9907.luminabrowser.ui.reader.shouldRedirectToGoogleCache(pageUrl)) {
                            readerManager.setOriginalUrl(tabId, pageUrl)
                            val cacheUrl = com.firebolt9907.luminabrowser.ui.reader.getGoogleCacheUrl(pageUrl)
                            engineSession.loadUrl(cacheUrl)
                        } else {
                            com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor.extractReaderContent(engineSession, tabId)
                        }
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

            // Listen to Page (TTS over the reader-extracted article). Shown once the article has
            // been extracted; kept in the sheet (no onDismiss) so the toggle stays reachable.
            if (listenArticle != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { PageSpeaker.toggle(context, listenArticle, tabId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionRowContent(
                        icon = if (isSpeaking) Icons.Default.Close else Icons.Default.PlayArrow,
                        text = if (isSpeaking) "Stop Listening" else "Listen to Page",
                    )
                }
            }

            if (isActive) {
                Spacer(Modifier.height(12.dp))
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReaderTheme.entries.forEach { themeOption ->
                            val optionBg = when (themeOption) {
                                ReaderTheme.System -> MaterialTheme.colorScheme.surface
                                ReaderTheme.White -> Color(0xFFFFFFFF)
                                ReaderTheme.Sepia -> Color(0xFFF4ECD8)
                                ReaderTheme.Dark -> Color(0xFF1C1C1E)
                            }
                            val optionBorderColor = when {
                                readerTheme == themeOption -> MaterialTheme.colorScheme.primary
                                themeOption == ReaderTheme.System -> MaterialTheme.colorScheme.outlineVariant
                                else -> Color.LightGray
                            }
                            val borderWidth = if (readerTheme == themeOption) 3.dp else 1.5.dp
                            val themeLabel = "${themeOption.name} reader theme"
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(optionBg)
                                    .border(borderWidth, optionBorderColor, CircleShape)
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
            }
        }

        // ---------- 4. Page zoom ----------
        Spacer(Modifier.height(18.dp))
        SectionCard {
            SectionLabel("Page Zoom")
            Spacer(Modifier.height(4.dp))
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
        }

        // ---------- 5. Page Settings (advanced, collapsed by default) ----------
        Spacer(Modifier.height(8.dp))
        CollapsibleSection(
            title = "Page Settings",
            expanded = pageSettingsExpanded || isActive,
            onToggle = { pageSettingsExpanded = !pageSettingsExpanded },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = canEdit,
                        onClick = {
                            topPaddingFix = !topPaddingFix
                            persist(top = topPaddingFix)
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
                    .combinedClickable(
                        enabled = canEdit,
                        onClick = {
                            desktopMode = !desktopMode
                            persist(desktop = desktopMode)
                            engineSession?.toggleDesktopMode(desktopMode, reload = true)
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

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = canEdit,
                        onClick = {
                            useGoogleCache = !useGoogleCache
                            globalPrefsStore.useGoogleCache = useGoogleCache
                            readerManager.setUseGoogleCache(useGoogleCache)
                        },
                        onLongClick = { showDebugCacheToggle = !showDebugCacheToggle },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bypass via Google Cache",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Load reader content from Google's scraper cache",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useGoogleCache,
                    onCheckedChange = null,
                    enabled = canEdit,
                )
            }

            if (showDebugCacheToggle) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            enabled = canEdit,
                            onClick = {
                                globalPrefsStore.debugCacheHtml = !globalPrefsStore.debugCacheHtml
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Debug: show parsed HTML",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Type debug:cache in address bar to inspect raw HTML",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = globalPrefsStore.debugCacheHtml,
                        onCheckedChange = null,
                        enabled = canEdit,
                    )
                }
            }
        }

        // ---------- 6. Secondary actions: reading list + open-in ----------
        if (hasRealUrl) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // Add to Reading List (Safari keeps this separate from bookmarks). Backed by a
            // dedicated "Reading List" folder under the Mobile root, created on first use.
            if (bookmarksStorage != null && isWebPage) {
                TextButton(
                    onClick = ::doReadingList,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionRowContent(icon = Icons.AutoMirrored.Filled.MenuBook, text = "Add to Reading List")
                }
            }

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
                        val intent = Intent(Intent.ACTION_VIEW, pageUrl.toUri()).apply {
                            setPackage(resolvedNativeApp.packageName)
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            // Preferred native app gone; fall back to a plain VIEW, still guarded
                            // in case nothing at all handles the URL.
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, pageUrl.toUri())) }
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
                        val uri = pageUrl.toUri()
                        val pkg = preferredBrowserPkg
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            if (!pkg.isNullOrEmpty()) setPackage(pkg)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            // Preferred browser gone; fall back to a plain VIEW, still guarded in
                            // case nothing handles the URL.
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
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

/** Page header: a favicon chip beside the page title and host. */
@Composable
private fun PageHeader(
    pageIcon: android.graphics.Bitmap?,
    title: String,
    host: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (pageIcon != null) {
                val img = remember(pageIcon) { pageIcon.asImageBitmap() }
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (host.isNotEmpty()) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A single quick-action tile: rounded icon chip over a short label. */
@Composable
private fun QuickActionTile(
    action: QuickAction,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = action.label) { action.onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Rounded surface card used to group related controls (reader options, zoom). */
@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        content = content,
    )
}

/** A small muted section label. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A tappable header that expands/collapses its content. */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggle() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
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
