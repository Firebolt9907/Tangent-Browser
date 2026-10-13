package com.firebolt9907.tangentbrowser.ui.chrome

import androidx.activity.compose.BackHandler
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import androidx.core.net.toUri
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.iconFor
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.permissions.ManagedPermission
import com.firebolt9907.tangentbrowser.permissions.SitePermissionController
import mozilla.components.browser.state.state.ContainerState
import com.firebolt9907.tangentbrowser.ui.containers.swatch
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.filled.Lock
import com.firebolt9907.tangentbrowser.ui.reader.PageSpeaker
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarksStorage
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.firebolt9907.tangentbrowser.ui.reader.ReaderTheme
import com.firebolt9907.tangentbrowser.ui.reader.ReaderManager
import com.firebolt9907.tangentbrowser.ui.reader.toggleReader
import com.firebolt9907.tangentbrowser.ui.TangentMotion
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import com.firebolt9907.tangentbrowser.ui.components.SwatchChoice
import com.firebolt9907.tangentbrowser.ui.theme.titleMediumEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.titleSmallEmphasized
import com.firebolt9907.tangentbrowser.ui.shareUrl
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.addons.AddonController
import com.firebolt9907.tangentbrowser.addons.InstalledAddon
import com.firebolt9907.tangentbrowser.state.BarMode
import com.firebolt9907.tangentbrowser.state.BarUiState
import com.firebolt9907.tangentbrowser.state.TangentTabGroup
import com.firebolt9907.tangentbrowser.ui.groups.swatch
import com.firebolt9907.tangentbrowser.state.GlobalPreferencesStore
import com.firebolt9907.tangentbrowser.state.compactHost
import com.firebolt9907.tangentbrowser.state.hostFromUrl
import com.firebolt9907.tangentbrowser.state.SitePreferences
import com.firebolt9907.tangentbrowser.state.SitePreferencesStore
import com.firebolt9907.tangentbrowser.ui.archive.siteHostFromUrl
import com.firebolt9907.tangentbrowser.ui.addons.AddonsManagerSheetContent
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
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
    // Page-level feedback (see ui/PageFeedback.kt). The sheet's confirmations fire *together with*
    // [onDismiss], so they cannot live in an in-sheet snackbar — the sheet is gone before one would
    // draw. The host raises them instead, from outside the sheet.
    onFeedback: (message: String, actionLabel: String?, onAction: (() -> Unit)?) -> Unit =
        { _, _, _ -> },
    onOpenAddonSettings: (InstalledAddon) -> Unit = {},
    onOpenAddonQuickSettings: (InstalledAddon) -> Unit = {},
    onFindInPage: () -> Unit = {},
    onPrint: () -> Unit = {},
    // Element zap (tap a page element to hide it for this site). Null hides the row entirely — the
    // picker needs a live page session to inject into. [zappedCount] drives the restore row.
    onZapElement: (() -> Unit)? = null,
    zappedCount: Int = 0,
    onRestoreZapped: () -> Unit = {},
    // Enters Internet Archive (Wayback) time-travel mode for this tab. Null hides the row.
    onOpenArchive: (() -> Unit)? = null,
    trackingProtectionEnabledGlobally: Boolean = false,
    siteTrackingProtectionDisabled: Boolean = false,
    onToggleSiteTrackingProtection: (disable: Boolean) -> Unit = {},
    // Data saver (OS Data Saver on a metered connection). [dataSaverActive] gates the row entirely —
    // there is nothing to say when we aren't blocking anything. [dataSaverExempt] is this host's
    // "load in full" escape hatch; toggling it reloads the page.
    dataSaverActive: Boolean = false,
    dataSaverExempt: Boolean = false,
    dataSaverBlockedCount: Int = 0,
    onToggleDataSaverExempt: (exempt: Boolean) -> Unit = {},
    pageUrl: String = "",
    pageTitle: String = "",
    tabId: String? = null,
    engineSession: mozilla.components.concept.engine.EngineSession? = null,
    backgroundSnapshot: android.graphics.Bitmap? = null,
    statusBarTopPad: Dp = 0.dp,
    bookmarksStorage: BookmarksStorage? = null,
    pageIcon: android.graphics.Bitmap? = null,
    showBottomToolbar: Boolean = false,
    // Site content-permission review/edit (camera/mic/location/…) for the current origin.
    sitePermissionController: SitePermissionController? = null,
    // Container (cookie-jar) switching for the current tab. [containers] is the registry; [currentContextId]
    // is the tab's current jar (null = default); [onReopenInContainer] recreates the tab in the chosen jar.
    containers: List<ContainerState> = emptyList(),
    currentContextId: String? = null,
    onReopenInContainer: (contextId: String?) -> Unit = {},
    // Spaces (tab groups) the current tab can be moved between. [currentSpaceId] is the tab's group
    // (null = Personal); [onMoveToSpace] re-homes the tab (null → Personal).
    spaces: List<TangentTabGroup> = emptyList(),
    currentSpaceId: String? = null,
    onMoveToSpace: (groupId: String?) -> Unit = {},
) {
    // Always open fully expanded (no partially-expanded detent). Otherwise the sheet's own drag
    // gesture competes with the inner list's scroll: the first up-drag gets consumed to expand the
    // sheet (reading as an overscroll bounce) instead of scrolling the content, so scrolling from
    // the top feels inconsistent — sometimes it scrolls, sometimes the sheet just animates.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // iOS-style scaled-page backdrop behind the sheet. Driven by the sheet's own show/hide target so
    // the page scales down on open and back up on dismiss; read lazily so it doesn't recompose here.
    // The spec is picked by direction to match ModalBottomSheet's own asymmetric springs (see
    // [TangentMotion.SheetShow]/[TangentMotion.SheetHide]) — with a single tween the page card and the
    // sheet travel on different curves, so the card trails the rising sheet and then sits scaled-down
    // for a beat after the sheet has already dropped off screen.
    val showingTarget = sheetState.targetValue != SheetValue.Hidden
    val backdropFraction = animateFloatAsState(
        targetValue = if (showingTarget) 1f else 0f,
        animationSpec = if (showingTarget) TangentMotion.SheetShow else TangentMotion.SheetHide,
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
                // Expressive motion: the slide is spatial (springs, with the slight overshoot that
                // sells the pane as a physical object), the cross-fade is an effect (critically
                // damped, so opacity never wobbles). Nothing here rides an M3 component's own
                // animation, so it is free to use the expressive scheme.
                val toSub = targetState != AaPane.Page
                val slide = ExpressiveMotion.defaultSpatialSpec<IntOffset>()
                val enter = if (toSub) slideInHorizontally(slide) { it / 5 }
                    else slideInHorizontally(slide) { -it / 5 }
                val exit = if (toSub) slideOutHorizontally(slide) { -it / 5 }
                    else slideOutHorizontally(slide) { it / 5 }
                val fade = ExpressiveMotion.defaultEffectsSpec<Float>()
                (enter + fadeIn(fade)) togetherWith (exit + fadeOut(fade))
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
                    onUblockAutomaticUpdatesChange = addonController::setUblockAutomaticUpdates,
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
                    dataSaverActive = dataSaverActive,
                    dataSaverExempt = dataSaverExempt,
                    dataSaverBlockedCount = dataSaverBlockedCount,
                    onToggleDataSaverExempt = onToggleDataSaverExempt,
                    onBack = { pane = AaPane.Page },
                )
                AaPane.Permissions -> SitePermissionsPaneContent(
                    host = host,
                    pageUrl = pageUrl,
                    controller = sitePermissionController,
                    onBack = { pane = AaPane.Page },
                )
                AaPane.Page -> PageTabContent(
                    host = host,
                    store = store,
                    globalPrefsStore = globalPrefsStore,
                    onDismiss = onDismiss,
                    onFeedback = onFeedback,
                    onFindInPage = onFindInPage,
                    onPrint = onPrint,
                    onZapElement = onZapElement,
                    zappedCount = zappedCount,
                    onRestoreZapped = onRestoreZapped,
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
                    onOpenPermissions = { pane = AaPane.Permissions }
                        .takeIf { sitePermissionController != null },
                    containers = containers,
                    currentContextId = currentContextId,
                    onReopenInContainer = onReopenInContainer,
                    spaces = spaces,
                    currentSpaceId = currentSpaceId,
                    onMoveToSpace = onMoveToSpace,
                )
            }
        }
    }
}

/** UI name for the default Space (a tab in no group), mirroring the switcher's "Personal" chip. */
private const val DefaultSpaceName = "Personal"

/** The panes the aA sheet swaps between; sub-panes slide in over the Page view. */
private enum class AaPane { Page, Addons, Settings, Permissions }

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
    onDismiss: () -> Unit,
    onFeedback: (message: String, actionLabel: String?, onAction: (() -> Unit)?) -> Unit =
        { _, _, _ -> },
    onFindInPage: () -> Unit = {},
    onPrint: () -> Unit = {},
    // Element zap (tap a page element to hide it for this site). Null hides the row entirely — the
    // picker needs a live page session to inject into. [zappedCount] drives the restore row.
    onZapElement: (() -> Unit)? = null,
    zappedCount: Int = 0,
    onRestoreZapped: () -> Unit = {},
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
    onOpenPermissions: (() -> Unit)? = null,
    containers: List<ContainerState> = emptyList(),
    currentContextId: String? = null,
    onReopenInContainer: (contextId: String?) -> Unit = {},
    spaces: List<TangentTabGroup> = emptyList(),
    currentSpaceId: String? = null,
    onMoveToSpace: (groupId: String?) -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = rememberTangentHaptics()
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
    var showContainerPicker by remember { mutableStateOf(false) }
    var showSpacePicker by remember { mutableStateOf(false) }
    var showPageTools by rememberSaveable(pageUrl) { mutableStateOf(false) }
    var showSiteOptions by rememberSaveable(pageUrl) { mutableStateOf(false) }

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
    val shareableUrl = com.firebolt9907.tangentbrowser.ui.archive.WaybackManager.instance
        .originalFromArchiveUrl(pageUrl) ?: pageUrl

    // ---- Shared action handlers (reused by the quick-action tiles and secondary rows) ----
    fun doShare() {
        // onFeedback lands on the page-level host outside this sheet (iter 23/24), so the
        // "no share target" message survives the onDismiss() below.
        shareUrl(context, shareableUrl, pageTitle) { onFeedback(it, null, null) }
        onDismiss()
    }

    fun doCopy() {
        clipboard.setText(AnnotatedString(shareableUrl))
        onFeedback("Link copied", null, null)
        onDismiss()
    }

    fun doFind() {
        onFindInPage()
        onDismiss()
    }

    // Shared save-to-bookmarks shell (plain bookmark + reading list): run [saveOp] off the sheet,
    // buzz + report the outcome, and close immediately — the write finishes in the background.
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
            onFeedback(if (ok) successMessage else failureMessage, null, null)
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

    if (showContainerPicker) {
        fun choose(contextId: String?) {
            showContainerPicker = false
            if (contextId != currentContextId) {
                onReopenInContainer(contextId)
                onDismiss()
            }
        }
        AlertDialog(
            onDismissRequest = { showContainerPicker = false },
            icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
            title = { Text("Open tab in container") },
            text = {
                Column {
                    Text(
                        text = "Reopens this tab in the chosen container's cookie jar. In-page state is lost; the page reloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    ContainerPickerRow(
                        label = com.firebolt9907.tangentbrowser.ui.containers.DefaultContainerName,
                        color = com.firebolt9907.tangentbrowser.ui.containers.DefaultContainerSwatch,
                        selected = currentContextId == null,
                        onClick = { haptics.select(); choose(null) },
                    )
                    containers.forEach { container ->
                        ContainerPickerRow(
                            label = container.name.ifBlank { "Container" },
                            color = container.color.swatch(),
                            selected = currentContextId == container.contextId,
                            onClick = { haptics.select(); choose(container.contextId) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { haptics.softClick(); showContainerPicker = false }) { Text("Cancel") }
            },
        )
    }

    if (showSpacePicker) {
        fun choose(groupId: String?) {
            showSpacePicker = false
            if (groupId != currentSpaceId) {
                onMoveToSpace(groupId)
                onDismiss()
            }
        }
        AlertDialog(
            onDismissRequest = { showSpacePicker = false },
            icon = { Icon(Icons.Outlined.Workspaces, contentDescription = null) },
            title = { Text("Move tab to Space") },
            text = {
                Column {
                    ContainerPickerRow(
                        label = DefaultSpaceName,
                        color = null,
                        selected = currentSpaceId == null,
                        onClick = { haptics.select(); choose(null) },
                    )
                    spaces.forEach { space ->
                        ContainerPickerRow(
                            label = space.name.ifBlank { "Space" },
                            color = space.color.swatch(),
                            selected = currentSpaceId == space.id,
                            onClick = { haptics.select(); choose(space.id) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { haptics.softClick(); showSpacePicker = false }) { Text("Cancel") }
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
                // Flat-damped height (see [TangentMotion.RevealExpand]): the reader controls are a
                // tall block in the middle of a scrolling sheet, so an expressive spatial's overshoot
                // would kick every row below them past their resting place and back.
                enter = expandVertically(TangentMotion.RevealExpand) +
                    fadeIn(ExpressiveMotion.defaultEffectsSpec<Float>()),
                exit = shrinkVertically(TangentMotion.RevealCollapse) +
                    fadeOut(ExpressiveMotion.defaultEffectsSpec<Float>()),
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
                            // Every page colour here can collide with the sheet behind it (White on a
                            // light sheet, Dark on a dark one, System always), so the ring is not
                            // optional the way it is for the container/group pickers.
                            SwatchChoice(
                                swatch = optionBg,
                                label = "${themeOption.name} reader theme",
                                selected = readerTheme == themeOption,
                                onSelect = { haptics.select(); readerManager.setTheme(themeOption) },
                                outline = MaterialTheme.colorScheme.outlineVariant,
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
                            // titleSmallEmphasized is 14sp Bold, so the small "A" needs no override
                            // at all; the large one keeps its size cue and borrows the same weight.
                            Text("A", style = MaterialTheme.typography.titleSmallEmphasized)
                        }
                        Text(
                            text = "${readerFontSize}%",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        IconButton(
                            onClick = { haptics.step(); readerManager.setFontSize(readerFontSize + 10) },
                            enabled = readerFontSize < 200,
                            modifier = Modifier.semantics {
                                contentDescription = "Increase reader font size"
                            }
                        ) {
                            Text(
                                "A",
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                fontSize = 20.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    // Four mutually exclusive choices = a segmented button row, not four TextButtons
                    // that signalled the pick by going bold. The stock component carries the
                    // single-choice semantics, the selected container tone and the state layers; the
                    // check icon is dropped (`icon = {}`) because with it the four labels no longer
                    // fit a phone-width sheet, and the label keeps its own typeface as the preview.
                    val readerFonts = listOf("System", "Georgia", "Charter", "Athelas")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        readerFonts.forEachIndexed { index, name ->
                            val fontTypeface = when (name) {
                                "Georgia" -> FontFamily(Typeface.create("georgia", Typeface.NORMAL))
                                "Charter" -> FontFamily(Typeface.create("charter", Typeface.NORMAL))
                                "Athelas" -> FontFamily(Typeface.create("athelas", Typeface.NORMAL))
                                else -> FontFamily.Default
                            }
                            SegmentedButton(
                                selected = readerFontName == name,
                                onClick = { haptics.select(); readerManager.setFontName(name) },
                                shape = SegmentedButtonDefaults.itemShape(index, readerFonts.size),
                                icon = {},
                                modifier = Modifier.semantics {
                                    contentDescription = "$name reader font"
                                },
                            ) {
                                Text(
                                    text = name,
                                    fontFamily = fontTypeface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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

        // ---------- 4a. A file the page produced as a blob: URL — the durable way to keep it ----------
        // Blob documents have no link to long-press and often no download button of their own, and
        // the "save this file" banner can be waved away; this row is the affordance that stays put.
        val blobFile = remember(pageUrl) {
            com.firebolt9907.tangentbrowser.state.BlobFileStore.lookup(pageUrl)
        }
        if (blobFile != null) {
            Spacer(Modifier.height(12.dp))
            SectionCard {
                TextButton(
                    onClick = {
                        haptics.softClick()
                        bookmarkScope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                com.firebolt9907.tangentbrowser.state.BlobFileStore
                                    .saveToDownloads(context, blobFile)
                            }
                            onFeedback(
                                if (saved != null) "Saved $saved to Downloads"
                                else "Couldn't save this file",
                                null,
                                null,
                            )
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionRowContent(
                        icon = Icons.Default.Download,
                        text = "Save ${blobFile.typeLabel} to Downloads",
                    )
                }
            }
        }

        if (hasRealUrl) {
            Spacer(Modifier.height(12.dp))
            AaDisclosureRow("Page tools", showPageTools) { showPageTools = !showPageTools }
            AnimatedVisibility(visible = showPageTools) {
                Column {
                    // ---------- 4b. Element zap: tap a page element to hide it, for good, on this site ----------
                    if (hasRealUrl && isWebPage && onZapElement != null && canEdit) {
                        Spacer(Modifier.height(12.dp))
                        SectionCard {
                            TextButton(
                                onClick = { haptics.softClick(); onZapElement(); onDismiss() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ActionRowContent(
                                    icon = Icons.Outlined.HideSource,
                                    text = "Hide an element",
                                )
                            }
                            // Only offered once there's something to undo — the row is meaningless otherwise.
                            if (zappedCount > 0) {
                                TextButton(
                                    onClick = { haptics.softClick(); onRestoreZapped() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    ActionRowContent(
                                        icon = Icons.Default.Restore,
                                        text = if (zappedCount == 1) {
                                            "Restore 1 hidden element"
                                        } else {
                                            "Restore $zappedCount hidden elements"
                                        },
                                    )
                                }
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
                }
            }
        }
        AaDisclosureRow("Site options", showSiteOptions) { showSiteOptions = !showSiteOptions }
        AnimatedVisibility(visible = showSiteOptions) {
            Column {
                // ---------- 6. Sub-views: Container · Site permissions · Page Settings · Add-ons ----------
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))

                // Container (cookie jar) for this tab. Tapping opens a picker; choosing a different jar
                // reopens the tab in it (see reopenTabInContainer). The current container is shown as a
                // trailing value with its color swatch.
                AaNavRow(
                    icon = Icons.Outlined.Shield,
                    label = "Container",
                    value = com.firebolt9907.tangentbrowser.ui.containers.containerNameFor(currentContextId, containers),
                    valueColor = com.firebolt9907.tangentbrowser.ui.containers.containerSwatchFor(currentContextId, containers),
                    onClick = { showContainerPicker = true },
                )

                // Move the current tab to another Space (tab group), or back to Personal. Hidden when there
                // are no Spaces to move between.
                if (spaces.isNotEmpty()) {
                    AaNavRow(
                        icon = Icons.Outlined.Workspaces,
                        label = "Move to Space",
                        value = spaces.firstOrNull { it.id == currentSpaceId }?.name?.ifBlank { "Space" }
                            ?: DefaultSpaceName,
                        valueColor = spaces.firstOrNull { it.id == currentSpaceId }?.color?.swatch(),
                        onClick = { showSpacePicker = true },
                    )
                }

                // Site permissions (camera/mic/location/notifications/…) for the current origin. Hidden when
                // there's no controller or no real origin to scope decisions to.
                if (onOpenPermissions != null && host.isNotEmpty()) {
                    AaNavRow(
                        icon = Icons.Default.Lock,
                        label = "Site permissions",
                        onClick = onOpenPermissions,
                    )
                }

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
    }
}

/** Less-used actions stay available without filling the first screen with buttons. */
@Composable
private fun AaDisclosureRow(label: String, expanded: Boolean, onClick: () -> Unit) {
    val haptics = rememberTangentHaptics()
    TextButton(
        onClick = { haptics.select(); onClick() },
        modifier = Modifier.fillMaxWidth().semantics {
            stateDescription = if (expanded) "Expanded" else "Collapsed"
        },
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
        )
    }
}

/**
 * A tappable "opens a sub-view" row: leading icon, label, an optional count [badge] or a trailing
 * [value] (with an optional [valueColor] swatch dot, e.g. the current container), and a chevron.
 */
@Composable
private fun AaNavRow(
    icon: ImageVector,
    label: String,
    badge: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    onClick: () -> Unit,
) {
    val haptics = rememberTangentHaptics()
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
            if (value != null) {
                if (valueColor != null) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(valueColor),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
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

/** One choice in the container picker: a color swatch (or hollow ring for "none"), name, and check. */
@Composable
private fun ContainerPickerRow(
    label: String,
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (color == null) {
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Current",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The Site Permissions sub-view: a tri-state (Ask / Allow / Block) control per managed permission for
 * the current origin, backed by [SitePermissionController]'s remembered decisions. Slides in over the
 * Page view with a Back affordance. "Ask" clears the stored decision so the site prompts again.
 */
@Composable
private fun SitePermissionsPaneContent(
    host: String,
    pageUrl: String,
    controller: SitePermissionController?,
    onBack: () -> Unit,
) {
    val haptics = rememberTangentHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 28.dp),
    ) {
        SheetHeader(
            title = "Site permissions",
            leadingIcon = {
                IconButton(onClick = { haptics.softClick(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = host.ifEmpty { "This site" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (controller == null) {
            Text(
                text = "Permissions are unavailable here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        ManagedPermission.entries.forEach { permission ->
            var decision by remember(pageUrl, permission) {
                mutableStateOf(controller.managedDecisionFor(pageUrl, permission))
            }
            PermissionTriStateRow(
                icon = iconFor(permission),
                label = permission.label,
                description = permission.description,
                decision = decision,
                onSelect = { next ->
                    if (next != decision) {
                        decision = next
                        controller.setManagedDecision(pageUrl, permission, next)
                        haptics.select()
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** A managed-permission row: icon + label/description, with an Ask/Allow/Block segmented selector. */
@Composable
private fun PermissionTriStateRow(
    icon: ImageVector,
    label: String,
    description: String,
    decision: Boolean?,
    onSelect: (Boolean?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    // Three-segment selector: Ask (null) · Allow (true) · Block (false). The selection is ONE pill
    // that slides between the segments rather than a per-segment background that repaints — the
    // segments are equal-weight, so the indicator's geometry is derivable in the draw phase.
    val options = listOf<Pair<String, Boolean?>>("Ask" to null, "Allow" to true, "Block" to false)
    val selectedIndex = options.indexOfFirst { it.second == decision }.coerceAtLeast(0)
    val indicator = animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = ExpressiveMotion.fastSpatialSpec(),
        label = "permissionSegmentIndicator",
    )
    val indicatorColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val segmentGapPx = with(density) { SegmentGap.toPx() }
    val segmentRadiusPx = with(density) { 10.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(2.dp)
            .drawBehind {
                val count = options.size
                val segWidth = (size.width - segmentGapPx * (count - 1)) / count
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset(indicator.value * (segWidth + segmentGapPx), 0f),
                    size = Size(segWidth, size.height),
                    cornerRadius = CornerRadius(segmentRadiusPx),
                )
            },
        horizontalArrangement = Arrangement.spacedBy(SegmentGap),
    ) {
        options.forEachIndexed { index, (segLabel, segValue) ->
            // Label tint cross-fades on an effects spring: a 0.8-damped spatial would overshoot the
            // colour lerp past its endpoint (see the motion log's colour rule).
            val labelColor by animateColorAsState(
                targetValue = if (index == selectedIndex) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = ExpressiveMotion.defaultEffectsSpec(),
                label = "permissionSegmentLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(segValue) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = segLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = labelColor,
                )
            }
        }
    }
}

/** Gap between the permission selector's segments; the sliding indicator's geometry depends on it. */
private val SegmentGap = 2.dp

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
    dataSaverActive: Boolean,
    dataSaverExempt: Boolean,
    dataSaverBlockedCount: Int,
    onToggleDataSaverExempt: (exempt: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val haptics = rememberTangentHaptics()
    val canEdit = host.isNotEmpty()
    val initial = remember(host) { store.get(host) }
    var topPaddingFix by remember(host) { mutableStateOf(initial.topPaddingFix) }
    var desktopMode by remember(host) { mutableStateOf(initial.desktopMode) }

    fun persist(
        top: Boolean = topPaddingFix,
        desktop: Boolean = desktopMode,
    ) {
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

        // Data saver escape hatch. Only shown while we're actually blocking (OS Data Saver on a
        // metered connection) — off Data Saver the row would be a toggle for nothing. "Checked" =
        // saving data on this site; unchecking exempts the host for the session and reloads, which is
        // the answer to "this page looks broken / I want the images after all".
        if (dataSaverActive && canEdit) {
            var saving by remember(pageUrl) { mutableStateOf(!dataSaverExempt) }
            AaToggleRow(
                title = "Save data on this site",
                // Lead with the running tally once there is one: the whole feature is invisible by
                // construction (nothing arrives, so nothing shows), and a number is the only feedback
                // that it is doing anything at all.
                subtitle = if (dataSaverBlockedCount > 0) {
                    "$dataSaverBlockedCount downloads skipped so far. Turn off to load this site in full."
                } else {
                    "Skips images, media and third-party scripts. Turn off to load in full."
                },
                checked = saving,
                enabled = true,
                onToggle = {
                    saving = !saving
                    onToggleDataSaverExempt(!saving)
                    haptics.toggle(saving)
                },
            )
            Spacer(Modifier.height(16.dp))
        }

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

        Spacer(Modifier.height(16.dp))

    }
}
