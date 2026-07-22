package com.firebolt9907.luminabrowser.ui.chrome

import androidx.activity.compose.BackHandler
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import com.firebolt9907.luminabrowser.ui.rememberLuminaHaptics
import com.firebolt9907.luminabrowser.ui.reader.PageSpeaker
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarksStorage
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.firebolt9907.luminabrowser.ui.reader.ReaderTheme
import com.firebolt9907.luminabrowser.ui.reader.ReaderManager
import com.firebolt9907.luminabrowser.ui.reader.toggleReader
import com.firebolt9907.luminabrowser.ui.shareUrl
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.firebolt9907.luminabrowser.state.BarMode
import com.firebolt9907.luminabrowser.state.BarUiState
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import com.firebolt9907.luminabrowser.state.compactHost
import com.firebolt9907.luminabrowser.state.hostFromUrl
import com.firebolt9907.luminabrowser.state.SitePreferences
import com.firebolt9907.luminabrowser.state.SitePreferencesStore
import com.firebolt9907.luminabrowser.ui.archive.siteHostFromUrl
import com.firebolt9907.luminabrowser.ui.addons.AddonsManagerSheetContent
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import kotlin.math.roundToInt

// Browser/app resolution helpers (BrowserInfo, NativeApp, nativeAppFor, installedBrowsers)
// live in AaSheetAppResolver.kt.

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
    onPrint: () -> Unit = {},
    // Enters Internet Archive (Wayback) time-travel mode for this tab. Null hides the row.
    onOpenArchive: (() -> Unit)? = null,
    trackingProtectionEnabledGlobally: Boolean = false,
    siteTrackingProtectionDisabled: Boolean = false,
    onToggleSiteTrackingProtection: (disable: Boolean) -> Unit = {},
    // Flips the global high-performance web dark mode (see WebDarkMode.kt). Routed through the host so
    // Tier 1 (engine.preferredColorScheme via applyEngineSettings) and the live-page Tier-2 injection
    // both update. Reflected locally for instant switch feedback.
    onToggleWebDarkMode: (Boolean) -> Unit = {},
    pageUrl: String = "",
    pageTitle: String = "",
    tabId: String? = null,
    engineSession: mozilla.components.concept.engine.EngineSession? = null,
    backgroundSnapshot: android.graphics.Bitmap? = null,
    statusBarTopPad: Dp = 0.dp,
    bookmarksStorage: BookmarksStorage? = null,
    pageIcon: android.graphics.Bitmap? = null,
    showBottomToolbar: Boolean = false,
) {
    // Always open fully expanded (no partially-expanded detent). Otherwise the sheet's own drag
    // gesture competes with the inner list's scroll: the first up-drag gets consumed to expand the
    // sheet (reading as an overscroll bounce) instead of scrolling the content, so scrolling from
    // the top feels inconsistent — sometimes it scrolls, sometimes the sheet just animates.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    // A static, non-interactive copy of the address pill drawn *above* the opaque backdrop but
    // *below* the sheet. The real bottom-bar pill is hidden behind the backdrop while the sheet is
    // up; this stand-in occupies the same spot so that dragging the sheet down far enough reveals a
    // pill underneath (and the hand-off to the live pill on dismiss is seamless). It also means the
    // pill never has to be "restored" after the sheet closes — it was always represented on screen.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SafariBar(
            state = BarUiState(
                displayUrl = if (pageUrl.isNotBlank()) compactHost(pageUrl) else host,
                tabCount = 0,
                mode = BarMode.Expanded,
                editingText = "",
                pageUrl = pageUrl,
            ),
            onUrlTap = {},
            onAaTap = {},
            onEditingTextChange = {},
            onEditingSubmit = {},
            onEditingCancel = {},
            onDragStart = {},
            onDragDelta = {},
            onDragEnd = { _, _ -> },
            onReload = {},
            interactive = false,
            controlsEnabled = false,
            showBottomToolbar = showBottomToolbar,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // The backdrop provides the dimming; the default scrim would paint over it.
        scrimColor = Color.Transparent,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        // Add-ons and Page Settings are reached through buttons in the Page view (not top-level
        // tabs); each slides in over the Page with a Back affordance. The system Back button returns
        // to the Page view too rather than dismissing the sheet — this BackHandler outranks the
        // sheet's own dismiss handler because it's registered later, and only while a sub-pane is up.
        var pane by rememberSaveable { mutableStateOf(AaPane.Page) }
        BackHandler(enabled = pane != AaPane.Page) { pane = AaPane.Page }

        // Hoisted above AnimatedContent so add-on state and a half-typed install URL survive the
        // pane transition (AnimatedContent disposes the outgoing content).
        val addonState by addonController.state.collectAsStateWithLifecycle()
        var installText by rememberSaveable { mutableStateOf("") }
        AnimatedContent(
            targetState = pane,
            transitionSpec = {
                // Sub-panes slide in from the right (deeper); returning to Page slides back left.
                val toSub = targetState != AaPane.Page
                val enter = if (toSub) slideInHorizontally(tween(220)) { it / 5 }
                    else slideInHorizontally(tween(220)) { -it / 5 }
                val exit = if (toSub) slideOutHorizontally(tween(180)) { -it / 5 }
                    else slideOutHorizontally(tween(180)) { it / 5 }
                (enter + fadeIn(tween(220))) togetherWith (exit + fadeOut(tween(180)))
            },
            label = "aaContent",
        ) { current ->
            when (current) {
                AaPane.Addons -> AddonsManagerSheetContent(
                    state = addonState,
                    installText = installText,
                    onInstallTextChange = { installText = it },
                    onInstall = { addonController.install(installText) },
                    onEnabledChange = addonController::setEnabled,
                    onOpenSettings = onOpenAddonSettings,
                    onOpenQuickSettings = onOpenAddonQuickSettings,
                    onUninstall = addonController::uninstall,
                    onBack = { pane = AaPane.Page },
                )
                AaPane.Settings -> PageSettingsPaneContent(
                    host = host,
                    store = store,
                    pageUrl = pageUrl,
                    engineSession = engineSession,
                    trackingProtectionEnabledGlobally = trackingProtectionEnabledGlobally,
                    siteTrackingProtectionDisabled = siteTrackingProtectionDisabled,
                    onToggleSiteTrackingProtection = onToggleSiteTrackingProtection,
                    onBack = { pane = AaPane.Page },
                )
                AaPane.Page -> PageTabContent(
                    host = host,
                    store = store,
                    globalPrefsStore = globalPrefsStore,
                    onToggleWebDarkMode = onToggleWebDarkMode,
                    onDismiss = onDismiss,
                    onFindInPage = onFindInPage,
                    onPrint = onPrint,
                    onOpenArchive = onOpenArchive,
                    pageUrl = pageUrl,
                    pageTitle = pageTitle,
                    tabId = tabId,
                    engineSession = engineSession,
                    bookmarksStorage = bookmarksStorage,
                    pageIcon = pageIcon,
                    addonCount = addonState.addons.size,
                    onOpenAddons = { pane = AaPane.Addons },
                    onOpenPageSettings = { pane = AaPane.Settings },
                )
            }
        }
    }
}

/** The three panes the aA sheet swaps between; sub-panes slide in over the Page view. */
private enum class AaPane { Page, Addons, Settings }

/** One tile in the quick-action row: an icon chip over a short label. */
internal data class QuickAction(
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
    onToggleWebDarkMode: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
    onFindInPage: () -> Unit = {},
    onPrint: () -> Unit = {},
    onOpenArchive: (() -> Unit)? = null,
    pageUrl: String,
    pageTitle: String = "",
    tabId: String? = null,
    engineSession: mozilla.components.concept.engine.EngineSession? = null,
    bookmarksStorage: BookmarksStorage? = null,
    pageIcon: android.graphics.Bitmap? = null,
    addonCount: Int = 0,
    onOpenAddons: () -> Unit = {},
    onOpenPageSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = rememberLuminaHaptics()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val bookmarkScope = rememberCoroutineScope()
    val initial = remember(host) { store.get(host) }
    var zoomFactor by remember(host) { mutableFloatStateOf(initial.zoomFactor) }
    val canEdit = host.isNotEmpty()

    // null = not set, "" = Default browser, "com.foo" = specific app
    var preferredBrowserPkg by remember {
        mutableStateOf(globalPrefsStore.preferredBrowserPackage)
    }
    var showBrowserPicker by remember { mutableStateOf(false) }

    // Zoom is the only site pref the Page view writes; read-modify-write so it never clobbers the
    // top-padding / desktop-mode fields the Page Settings pane owns.
    fun persistZoom(zoom: Float) {
        if (!canEdit) return
        store.set(host, store.get(host).copy(zoomFactor = zoom))
    }

    val hasRealUrl = pageUrl.isNotBlank() && pageUrl != "about:blank"
    // Persistent actions (home-screen shortcut, bookmark, reading list) only make sense for real
    // web pages — not javascript:/data:/file:/about: URLs.
    val isWebPage = pageUrl.startsWith("http://", ignoreCase = true) ||
        pageUrl.startsWith("https://", ignoreCase = true)

    // In archive (Wayback) mode pageUrl is a web.archive.org snapshot wrapper. User-facing actions
    // that leave the app or persist a link — share, copy, bookmark, reading list, home-screen
    // shortcut, "open in other app" — should carry the ORIGINAL page URL, not the archive wrapper,
    // so a shared/bookmarked link points at the real site. Engine-facing ops (reader, desktop mode,
    // tracking protection, "View on Internet Archive") deliberately keep pageUrl.
    val shareableUrl = com.firebolt9907.luminabrowser.ui.archive.WaybackManager.instance
        .originalFromArchiveUrl(pageUrl) ?: pageUrl

    // ---- Shared action handlers (reused by the quick-action tiles and secondary rows) ----
    fun doShare() {
        shareUrl(context, shareableUrl, pageTitle)
        onDismiss()
    }

    fun doCopy() {
        clipboard.setText(AnnotatedString(shareableUrl))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    fun doFind() {
        onFindInPage()
        onDismiss()
    }

    // Shared save-to-bookmarks shell (plain bookmark + reading list): run [saveOp] off the sheet,
    // buzz + toast the outcome, and close immediately — the write finishes in the background.
    fun saveToBookmarks(
        successMessage: String,
        failureMessage: String,
        saveOp: suspend (storage: BookmarksStorage, url: String, title: String) -> Unit,
    ) {
        val storage = bookmarksStorage ?: return
        val url = shareableUrl
        val title = pageTitle.ifBlank { url }
        bookmarkScope.launch {
            val ok = runCatching { saveOp(storage, url, title) }.isSuccess
            if (ok) haptics.create()
            Toast.makeText(
                context,
                if (ok) successMessage else failureMessage,
                Toast.LENGTH_SHORT,
            ).show()
        }
        onDismiss()
    }

    fun doBookmark() = saveToBookmarks("Bookmarked", "Couldn't add bookmark") { storage, url, title ->
        storage.addItem(
            parentGuid = BookmarkRoot.Mobile.id,
            url = url,
            title = title,
            position = null,
        ).getOrThrow()
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
                                onClick = { haptics.select(); pick(browser) },
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
                            onClick = { haptics.select(); pick(browser) },
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
                TextButton(onClick = { haptics.softClick(); showBrowserPicker = false }) { Text("Cancel") }
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
            mutableStateOf(store.isReaderPreferred(siteHostFromUrl(pageUrl)))
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
                add(QuickAction(Icons.Default.Share, "Share") { haptics.softClick(); doShare() })
                if (bookmarksStorage != null && isWebPage) {
                    add(QuickAction(Icons.Default.Star, "Bookmark") { haptics.softClick(); doBookmark() })
                }
                add(QuickAction(Icons.Default.ContentCopy, "Copy") { haptics.softClick(); doCopy() })
                add(QuickAction(Icons.Default.Search, "Find") { haptics.softClick(); doFind() })
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

        // ---------- 2b. Dark mode for every website (global, high-performance) ----------
        if (hasRealUrl) {
            var webDark by remember { mutableStateOf(globalPrefsStore.webDarkModeEnabled) }
            Spacer(Modifier.height(14.dp))
            AaToggleRow(
                title = "Dark mode",
                subtitle = "Darken every website — uses each site's own dark theme when it has one",
                checked = webDark,
                enabled = true,
                onToggle = {
                    webDark = !webDark
                    onToggleWebDarkMode(webDark)
                    haptics.toggle(webDark)
                },
            )
        }

        // ---------- 3. Reader ----------
        if (hasRealUrl) {
            Spacer(Modifier.height(18.dp))
            TextButton(
                onClick = {
                    haptics.toggle(isActive)
                    val readerHost = siteHostFromUrl(pageUrl)
                    if (isActive || isEligible) {
                        tabId?.let { readerManager.toggleReader(it, pageUrl, engineSession) }
                        // Written even when tabId is null so the per-site preference still follows
                        // the user's intent.
                        store.setReaderPreferred(readerHost, !isActive)
                        readerPreferred = !isActive
                    } else if (tabId != null && engineSession != null && !isPending) {
                        readerManager.toggleReader(tabId, pageUrl, engineSession)
                        store.setReaderPreferred(readerHost, true)
                        readerPreferred = true
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

            // Listen to Page (TTS over the reader-extracted article). Only offered while Reader is
            // active, or when the user has opted into read-aloud globally ("Read aloud" in Settings →
            // Accessibility) — otherwise it's noise for people who never use TTS.
            if (listenArticle != null && (isActive || globalPrefsStore.listenToPageEnabled)) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { haptics.toggle(isSpeaking); PageSpeaker.toggle(context, listenArticle, tabId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionRowContent(
                        icon = if (isSpeaking) Icons.Default.Close else Icons.Default.PlayArrow,
                        text = if (isSpeaking) "Stop Listening" else "Listen to Page",
                    )
                }
            }

            AnimatedVisibility(
                visible = isActive,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(180)),
            ) {
                Column {
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
                                else -> MaterialTheme.colorScheme.outline
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
                                    .clickable { haptics.select(); readerManager.setTheme(themeOption) }
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
                            onClick = { haptics.step(); readerManager.setFontSize(readerFontSize - 10) },
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
                            onClick = { haptics.step(); readerManager.setFontSize(readerFontSize + 10) },
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
                                onClick = { haptics.select(); readerManager.setFontName(name) },
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
        }

        // ---------- 4. Page zoom (compact single row: label · − · % · +) ----------
        Spacer(Modifier.height(18.dp))
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Page Zoom")
                Spacer(Modifier.weight(1f))
                // Reset only surfaces when there's something to reset, so the row stays quiet at 100%.
                if (canEdit && zoomFactor != 1.0f) {
                    TextButton(onClick = { zoomFactor = 1.0f; persistZoom(1.0f); haptics.step() }) {
                        Text("Reset", style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(
                    onClick = {
                        val next = ((zoomFactor - 0.1f) * 100f).roundToInt() / 100f
                        zoomFactor = next.coerceIn(0.5f, 2.0f)
                        persistZoom(zoomFactor)
                        haptics.step()
                    },
                    enabled = canEdit && zoomFactor > 0.5f,
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom out")
                }
                Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${(zoomFactor * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(
                    onClick = {
                        val next = ((zoomFactor + 0.1f) * 100f).roundToInt() / 100f
                        zoomFactor = next.coerceIn(0.5f, 2.0f)
                        persistZoom(zoomFactor)
                        haptics.step()
                    },
                    enabled = canEdit && zoomFactor < 2.0f,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom in")
                }
            }
        }

        // ---------- 5. Secondary actions: open-in ----------
        if (hasRealUrl) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // View this page on the Internet Archive (Wayback Machine) — enters an in-app time-travel
            // mode with a bottom date scrubber. Web pages only.
            if (isWebPage && onOpenArchive != null) {
                TextButton(
                    onClick = { haptics.softClick(); onOpenArchive(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionRowContent(
                        icon = Icons.Default.History,
                        text = "View on Internet Archive",
                    )
                }
            }

            // Print / Save as PDF — routes through GeckoView's print flow, which opens the Android
            // print dialog (physical printers plus the system "Save as PDF" printer).
            if (isWebPage) {
                TextButton(
                    onClick = { haptics.softClick(); onPrint(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionRowContent(icon = Icons.Default.Print, text = "Print / Save as PDF")
                }
            }

            // "Open in <app>" for a site's associated native app now surfaces automatically as a
            // banner above the pill (see OpenInAppBannerHost / nativeAppFor), so it's no longer a
            // buried row here.

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
                        haptics.softClick()
                        val uri = shareableUrl.toUri()
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
                TextButton(onClick = { haptics.select(); showBrowserPicker = true }) {
                    Text(
                        text = "Change",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------- 6. Sub-views: Page Settings + Add-ons (each slides in over the Page) ----------
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        AaNavRow(
            icon = Icons.Default.Tune,
            label = "Page Settings",
            onClick = onOpenPageSettings,
        )
        AaNavRow(
            icon = Icons.Default.Extension,
            label = "Add-ons",
            badge = addonCount.takeIf { it > 0 }?.toString(),
            onClick = onOpenAddons,
        )
    }
}

/** A tappable "opens a sub-view" row: leading icon, label, optional count badge, trailing chevron. */
@Composable
private fun AaNavRow(
    icon: ImageVector,
    label: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val haptics = rememberLuminaHaptics()
    TextButton(
        onClick = { haptics.select(); onClick() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Text(text = label, modifier = Modifier.weight(1f))
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The Page Settings sub-view — advanced per-site toggles (tracking exception, top padding, desktop
 * mode). Slides in over the Page view with a Back affordance. Reads/writes [store] directly with
 * `copy()` so it never clobbers the zoom factor the Page view owns.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageSettingsPaneContent(
    host: String,
    store: SitePreferencesStore,
    pageUrl: String,
    engineSession: mozilla.components.concept.engine.EngineSession?,
    trackingProtectionEnabledGlobally: Boolean,
    siteTrackingProtectionDisabled: Boolean,
    onToggleSiteTrackingProtection: (disable: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val haptics = rememberLuminaHaptics()
    val canEdit = host.isNotEmpty()
    val initial = remember(host) { store.get(host) }
    var topPaddingFix by remember(host) { mutableStateOf(initial.topPaddingFix) }
    var desktopMode by remember(host) { mutableStateOf(initial.desktopMode) }

    fun persist(top: Boolean = topPaddingFix, desktop: Boolean = desktopMode) {
        if (!canEdit) return
        store.set(host, store.get(host).copy(topPaddingFix = top, desktopMode = desktop))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 28.dp),
    ) {
        SheetHeader(
            title = "Page Settings",
            leadingIcon = {
                IconButton(onClick = { haptics.softClick(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Spacer(Modifier.height(16.dp))

        // Per-site tracking protection (only meaningful when ETP is on globally). "Checked" =
        // protected; unchecking adds this site to the exception list so its trackers load — the
        // standard "shield off for this site" escape hatch when ETP breaks a page.
        if (trackingProtectionEnabledGlobally && canEdit) {
            var siteProtected by remember(pageUrl) {
                mutableStateOf(!siteTrackingProtectionDisabled)
            }
            AaToggleRow(
                title = "Block trackers on this site",
                subtitle = "Turn off if tracking protection breaks this site",
                checked = siteProtected,
                enabled = true,
                onToggle = {
                    siteProtected = !siteProtected
                    onToggleSiteTrackingProtection(!siteProtected)
                    haptics.toggle(siteProtected)
                },
            )
            Spacer(Modifier.height(16.dp))
        }

        AaToggleRow(
            title = "Add top padding for this site",
            subtitle = "Use when site headers don't move into the safe area",
            checked = topPaddingFix,
            enabled = canEdit,
            onToggle = {
                topPaddingFix = !topPaddingFix
                persist(top = topPaddingFix)
                haptics.toggle(topPaddingFix)
            },
        )

        Spacer(Modifier.height(16.dp))

        AaToggleRow(
            title = "Request desktop site",
            subtitle = "Load the desktop layout for this site",
            checked = desktopMode,
            enabled = canEdit,
            onToggle = {
                desktopMode = !desktopMode
                persist(desktop = desktopMode)
                engineSession?.toggleDesktopMode(desktopMode, reload = true)
                haptics.toggle(desktopMode)
            },
        )
    }
}

