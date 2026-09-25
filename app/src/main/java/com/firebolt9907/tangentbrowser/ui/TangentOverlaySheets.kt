package com.firebolt9907.tangentbrowser.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.engine.TangentBrowserComponents
import com.firebolt9907.tangentbrowser.state.TangentViewModel
import com.firebolt9907.tangentbrowser.ui.addons.AddonsSheet
import com.firebolt9907.tangentbrowser.ui.bookmarks.BookmarksSheet
import com.firebolt9907.tangentbrowser.ui.chrome.AaSheet
import com.firebolt9907.tangentbrowser.ui.content.ImageContextSheet
import com.firebolt9907.tangentbrowser.ui.content.LinkPeekCard
import com.firebolt9907.tangentbrowser.ui.content.UtilityWebCardHost
import com.firebolt9907.tangentbrowser.ui.content.UtilityWebRequest
import com.firebolt9907.tangentbrowser.ui.downloads.DownloadsSheet
import com.firebolt9907.tangentbrowser.ui.history.HistorySheet
import com.firebolt9907.tangentbrowser.ui.passwords.PasswordsSheet
import com.firebolt9907.tangentbrowser.ui.settings.SettingsSheet
import com.firebolt9907.tangentbrowser.ui.sync.SyncedTabsSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView

/**
 * All of [TangentRoot]'s modal sheets and floating overlays — the tab-overview-independent surfaces
 * that render on top of the browser: Settings / History / Bookmarks / Downloads / Passwords /
 * Add-ons / Synced-tabs / the aA appearance sheet, plus the prompt dialogs, the standalone
 * UtilityWebCard, the link glance (LinkPeekCard) and the image context sheet. (The site-permission
 * request is a Now-Bar banner mounted in TangentRoot, not a dialog here.)
 *
 * Pulled out of TangentRoot.kt verbatim: the visibility flags and the TangentRoot-owned state each
 * surface mutates are hoisted in as values + callbacks, while anything reachable through
 * [components] (storages, use-cases, controllers) is derived here to keep the parameter list to the
 * genuinely external state. No gesture/render invariants live here — this is pure overlay wiring.
 */
@Composable
internal fun TangentOverlaySheets(
    components: TangentBrowserComponents,
    viewModel: TangentViewModel,
    scope: CoroutineScope,
    // Current-page context shared by several sheets.
    selectedContent: ContentState?,
    selectedTabId: String?,
    actualSelectedTabId: String?,
    selectedEngineSession: EngineSession?,
    currentHost: String,
    currentUrl: String?,
    currentPageSnapshot: Bitmap?,
    topChromeInsetPx: Int,
    // Height of the pill/toolbar chrome at the bottom — the page-feedback snackbar sits above it.
    bottomChromeInsetPx: Int,
    // Owned by TangentRoot (whose own confirmations use it too); the host is still mounted at the
    // end of this composable so a message draws over the sheets that raise it.
    feedback: PageFeedback,
    showBottomToolbar: Boolean,
    gridEngaged: Boolean,
    onCloseGrid: (animateBack: Boolean) -> Unit,
    // Opens a standalone UtilityWebCard (add-on options / Firefox sign-in) over the current page.
    onOpenUtilityUrl: (title: String, url: String) -> Unit,
    // Add-ons
    showAddons: Boolean,
    onDismissAddons: () -> Unit,
    // Settings (the prefs-mutating callbacks that touch TangentRoot-only state stay in the caller).
    showSettings: Boolean,
    onDismissSettings: () -> Unit,
    onSettingChanged: () -> Unit,
    onReplayOnboarding: () -> Unit,
    // Onboarding (replayed from Settings)
    showOnboarding: Boolean,
    onFinishOnboarding: () -> Unit,
    // Synced tabs
    showSyncedTabs: Boolean,
    onDismissSyncedTabs: () -> Unit,
    // Tab groups (organizational)
    showTabGroups: Boolean,
    onDismissTabGroups: () -> Unit,
    // Containers (contextual identities / cookie isolation)
    showContainers: Boolean,
    onDismissContainers: () -> Unit,
    // Passwords
    showPasswords: Boolean,
    onDismissPasswords: () -> Unit,
    // Credit cards / payment methods
    showCreditCards: Boolean,
    onDismissCreditCards: () -> Unit,
    showAddresses: Boolean,
    onDismissAddresses: () -> Unit,
    // History
    showHistory: Boolean,
    onDismissHistory: () -> Unit,
    // Bookmarks
    showBookmarks: Boolean,
    onDismissBookmarks: () -> Unit,
    // Downloads
    showDownloads: Boolean,
    onDismissDownloads: () -> Unit,
    // aA appearance / page-tools sheet
    showAa: Boolean,
    onDismissAa: () -> Unit,
    onAaEngineRefresh: () -> Unit,
    onFindInPage: () -> Unit,
    // Element zap: starts the in-page element picker for the current tab, and restores everything
    // hidden on this host. [zappedCount] is the current host's hidden-element count.
    onZapElement: (() -> Unit)?,
    zappedCount: Int,
    onRestoreZapped: () -> Unit,
    // Enters Internet Archive (Wayback) mode for the current tab.
    onOpenArchive: () -> Unit,
    siteTrackingProtectionDisabled: Boolean,
    onSiteTrackingProtectionDisabledChange: (Boolean) -> Unit,
    // Flips the global high-performance web dark mode from the aA sheet. The host persists the pref,
    // re-applies engine settings (Tier 1), and updates its live-injection state var (Tier 2).
    // Standalone add-on/sign-in web card
    utilityWeb: UtilityWebRequest?,
    onDismissUtilityWeb: () -> Unit,
    // Link glance
    peekUrl: String?,
    peekSession: EngineSession?,
    peekView: EngineView?,
    peekOrigin: Offset,
    peekSourceRect: Rect?,
    peekElementBitmap: Bitmap?,
    onPeekAdopt: (url: String, foreground: Boolean) -> Unit,
    onDismissPeek: () -> Unit,
    // Image context
    imageMenuUrl: String?,
    onDismissImageMenu: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val clipboardManager = LocalClipboardManager.current
    val density = LocalDensity.current
    // Containers offered by the link-peek "open in a separate profile" action.
    val peekContainers by components.containerRepository.containers.collectAsStateWithLifecycle()

    if (showAddons) {
        AddonsSheet(
            controller = components.addonController,
            onDismiss = onDismissAddons,
        )
    }

    if (showSettings) {
        SettingsSheet(
            globalPrefsStore = components.globalPreferencesStore,
            onDismiss = onDismissSettings,
            onSettingChanged = onSettingChanged,
            onClearSitePermissions = {
                components.sitePermissionController.clearRememberedDecisions()
            },
            onReplayOnboarding = onReplayOnboarding,
            onClearBrowsingData = { history, cookiesAndSiteData, cache ->
                // Cookies/site data and the on-disk cache live in the engine; history lives in
                // Places. Saved logins and bookmarks are deliberately left untouched.
                val engineTypes = buildList {
                    if (cookiesAndSiteData) {
                        add(mozilla.components.concept.engine.Engine.BrowsingData.COOKIES)
                        add(mozilla.components.concept.engine.Engine.BrowsingData.DOM_STORAGES)
                        add(mozilla.components.concept.engine.Engine.BrowsingData.AUTH_SESSIONS)
                    }
                    if (cache) {
                        add(mozilla.components.concept.engine.Engine.BrowsingData.NETWORK_CACHE)
                        add(mozilla.components.concept.engine.Engine.BrowsingData.IMAGE_CACHE)
                    }
                }
                if (engineTypes.isNotEmpty()) {
                    components.engine.clearData(
                        mozilla.components.concept.engine.Engine.BrowsingData.select(
                            *engineTypes.toIntArray()
                        )
                    )
                }
                if (history) {
                    // PlacesHistoryStorage.deleteEverything is suspend and manages its own IO.
                    scope.launch {
                        runCatching { components.historyStorage.deleteEverything() }
                    }
                }
            },
        )
    }

    // Onboarding replayed from Settings ("Show onboarding again"). A full-screen opaque overlay;
    // dismisses back to the browser when finished/skipped. (First-run onboarding is gated
    // separately in MainActivity.)
    if (showOnboarding) {
        com.firebolt9907.tangentbrowser.ui.onboarding.TangentOnboarding(
            onFinish = onFinishOnboarding,
        )
    }

    if (showSyncedTabs) {
        SyncedTabsSheet(
            controller = components.firefoxSyncController,
            onDismiss = onDismissSyncedTabs,
            onOpenTab = { url ->
                viewModel.onOpenNewTab(url = url, title = "", selectTab = true)
                if (gridEngaged) onCloseGrid(true)
            },
            onSignInUrl = { url ->
                // Run the OAuth flow in a standalone card over the current page rather than a real
                // tab. The card's onIntercept feeds every navigation to handleOAuthRedirect, which
                // completes the sign-in on the success redirect and dismisses the card.
                onOpenUtilityUrl("Firefox Sign In", url)
                onDismissSyncedTabs()
                if (gridEngaged) onCloseGrid(false)
            },
            currentPageUrl = selectedContent?.url.orEmpty(),
            currentPageTitle = selectedContent?.title.orEmpty(),
        )
    }

    if (showTabGroups) {
        val containers by components.containerRepository.containers.collectAsStateWithLifecycle()
        com.firebolt9907.tangentbrowser.ui.groups.TabGroupsSheet(
            repository = components.tabGroupsRepository,
            tabRepository = components.tabRepository,
            containers = containers,
            onOpenTab = { tabId ->
                components.tabRepository.selectTab(tabId)
                if (gridEngaged) onCloseGrid(true)
            },
            onNewTabInGroup = { groupId ->
                (components.tabRepository as? com.firebolt9907.tangentbrowser.state.GeckoTabRepository)
                    ?.addTabInGroup(url = "about:blank", groupId = groupId)
                onDismissTabGroups()
                if (gridEngaged) onCloseGrid(true)
            },
            onCloseTabs = { ids -> components.tabRepository.closeTabs(ids) },
            onDismiss = onDismissTabGroups,
        )
    }

    if (showContainers) {
        com.firebolt9907.tangentbrowser.ui.containers.ContainersSheet(
            repository = components.containerRepository,
            tabRepository = components.tabRepository,
            onNewTabInContainer = { contextId ->
                (components.tabRepository as? com.firebolt9907.tangentbrowser.state.GeckoTabRepository)
                    ?.addTabInContainer(url = "about:blank", contextId = contextId)
                onDismissContainers()
                if (gridEngaged) onCloseGrid(true)
            },
            onOpenTab = { tabId ->
                components.tabRepository.selectTab(tabId)
                if (gridEngaged) onCloseGrid(true)
            },
            onCloseTabs = { ids -> components.tabRepository.closeTabs(ids) },
            onDismiss = onDismissContainers,
        )
    }

    // The site content-permission prompt is rendered as a Now-Bar banner (SitePermissionBannerHost),
    // mounted with the other banners in TangentRoot — not as a dialog here.

    PromptHandler(
        engineSession = selectedEngineSession,
        loginAutofillEnabled = components.globalPreferencesStore.loginAutofillEnabled,
        biometricAutofillRequired = components.globalPreferencesStore.biometricAutofillEnabled,
        creditCardAutofillEnabled = components.globalPreferencesStore.creditCardAutofillEnabled,
        addressAutofillEnabled = components.globalPreferencesStore.addressAutofillEnabled,
    )

    // Proactive login autofill: offer the saved-password picker before the user taps a login field,
    // so the keyboard never appears when a credential is already saved (see ProactiveLoginAutofill).
    ProactiveLoginAutofill(
        engineSession = selectedEngineSession,
        loginsStorage = components.loginsStorage,
        enabled = components.globalPreferencesStore.loginAutofillEnabled,
        biometricRequired = components.globalPreferencesStore.biometricAutofillEnabled,
    )

    if (showPasswords) {
        PasswordsSheet(
            loginsStorage = components.loginsStorage,
            onDismiss = onDismissPasswords,
        )
    }

    if (showCreditCards) {
        com.firebolt9907.tangentbrowser.ui.passwords.CreditCardsSheet(
            creditCardsStorage = components.creditCardsStorage,
            onDismiss = onDismissCreditCards,
        )
    }

    if (showAddresses) {
        com.firebolt9907.tangentbrowser.ui.passwords.AddressesSheet(
            creditCardsStorage = components.creditCardsStorage,
            onDismiss = onDismissAddresses,
        )
    }

    if (showHistory) {
        val recentlyClosedTabs by components.tabRepository.recentlyClosedTabs.collectAsStateWithLifecycle()
        HistorySheet(
            historyStorage = components.historyStorage,
            onOpenUrl = { url ->
                viewModel.onOpenNewTab(url = url, title = "", selectTab = true)
                onDismissHistory()
                if (gridEngaged) onCloseGrid(true)
            },
            onDismiss = onDismissHistory,
            recentlyClosed = recentlyClosedTabs,
            onReopenClosed = { closed ->
                components.tabRepository.reopenClosedTab(closed.id)
                onDismissHistory()
                if (gridEngaged) onCloseGrid(true)
            },
        )
    }

    if (showBookmarks) {
        BookmarksSheet(
            bookmarksStorage = components.bookmarksStorage,
            currentPageUrl = selectedContent?.url.orEmpty(),
            currentPageTitle = selectedContent?.title.orEmpty(),
            onOpenUrl = { url ->
                viewModel.onOpenNewTab(url = url, title = "", selectTab = true)
                onDismissBookmarks()
                if (gridEngaged) onCloseGrid(true)
            },
            onDismiss = onDismissBookmarks,
        )
    }

    if (showDownloads) {
        DownloadsSheet(
            onDismiss = onDismissDownloads,
        )
    }

    if (showAa) {
        val aaContainers by components.containerRepository.containers.collectAsStateWithLifecycle()
        val aaCurrentContextId = selectedTabId
            ?.let { id -> components.store.state.tabs.firstOrNull { it.id == id } }?.contextId
        val aaSpaces by components.tabGroupsRepository.groups.collectAsStateWithLifecycle()
        val aaCurrentSpaceId = selectedTabId
            ?.let { id -> components.tabGroupsRepository.groupIdForTab(id) }
        val dataSaverActive by components.dataSaverController.active.collectAsStateWithLifecycle()
        val dataSaverBlocked by components.dataSaverController.blockedCount
            .collectAsStateWithLifecycle()
        AaSheet(
            host = currentHost,
            store = components.sitePreferencesStore,
            globalPrefsStore = components.globalPreferencesStore,
            addonController = components.addonController,
            onFeedback = { message, actionLabel, onAction ->
                feedback.show(message, actionLabel, onAction)
            },
            sitePermissionController = components.sitePermissionController,
            containers = aaContainers,
            currentContextId = aaCurrentContextId,
            onReopenInContainer = { contextId ->
                val id = selectedTabId ?: return@AaSheet
                (components.tabRepository as? com.firebolt9907.tangentbrowser.state.GeckoTabRepository)
                    ?.reopenTabInContainer(id, contextId)
            },
            spaces = aaSpaces,
            currentSpaceId = aaCurrentSpaceId,
            onMoveToSpace = { groupId ->
                val id = selectedTabId ?: return@AaSheet
                if (groupId != null) {
                    components.tabGroupsRepository.addTabToGroup(id, groupId)
                } else {
                    components.tabGroupsRepository.removeTabFromGroup(id)
                }
            },
            onDismiss = {
                onDismissAa()
                // The appearance sheet's opaque backdrop pauses the GeckoView compositor while
                // it covers the engine; on dismiss the surface keeps showing a stale frame that
                // covers the pill until the user scrolls. Bump this to wake the compositor now.
                onAaEngineRefresh()
            },
            onOpenAddonSettings = { addon ->
                // Render the options page in a standalone card over the current page instead of a
                // real tab: the user stays on their page and it remains the active tab for
                // extensions, so options that act on "the current page" target it — not the
                // options document itself.
                addon.optionsPageUrl?.let { url ->
                    onOpenUtilityUrl("${addon.name} Settings", url)
                    onDismissAa()
                    if (gridEngaged) onCloseGrid(false)
                }
            },
            onOpenAddonQuickSettings = { addon ->
                // Dismiss the sheet first; the popup arrives asynchronously via
                // onActionPopupTabRequested, which renders the popup session in a UtilityWebCard.
                // The originating page stays the active tab for extensions (setActiveSession is
                // never repointed at the card), so the popup binds to it.
                onDismissAa()
                if (gridEngaged) onCloseGrid(false)
                components.addonController.openPopup(addon.id)
            },
            onFindInPage = onFindInPage,
            onZapElement = onZapElement,
            zappedCount = zappedCount,
            onRestoreZapped = onRestoreZapped,
            onPrint = {
                // GeckoView opens the Android print dialog (physical printers + Save as PDF).
                selectedTabId?.let { components.sessionUseCases.printContent.invoke(it) }
            },
            onOpenArchive = currentUrl
                ?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?.let { { onOpenArchive() } },
            trackingProtectionEnabledGlobally = components.globalPreferencesStore.trackingProtection,
            siteTrackingProtectionDisabled = siteTrackingProtectionDisabled,
            onToggleSiteTrackingProtection = { disable ->
                selectedTabId?.let { id ->
                    if (disable) {
                        components.trackingProtectionUseCases.addException(id)
                    } else {
                        components.trackingProtectionUseCases.removeException(id)
                    }
                    onSiteTrackingProtectionDisabledChange(disable)
                    // Re-run the page so the new policy takes effect immediately.
                    components.sessionUseCases.reload.invoke(id)
                }
            },
            dataSaverActive = dataSaverActive,
            dataSaverExempt = components.dataSaverController.isExempt(currentUrl),
            dataSaverBlockedCount = dataSaverBlocked,
            onToggleDataSaverExempt = { exempt ->
                if (exempt) {
                    components.dataSaverController.exempt(currentUrl)
                } else {
                    components.dataSaverController.unexempt(currentUrl)
                }
                // The blocking decision is made per request, so the page has to be fetched again for
                // the change to mean anything — same as the tracking-protection toggle above.
                selectedTabId?.let { components.sessionUseCases.reload.invoke(it) }
            },
            pageUrl = currentUrl.orEmpty(),
            pageTitle = selectedContent?.title.orEmpty(),
            tabId = selectedTabId,
            engineSession = selectedEngineSession,
            backgroundSnapshot = currentPageSnapshot,
            statusBarTopPad = with(density) { topChromeInsetPx.toDp() },
            bookmarksStorage = components.bookmarksStorage,
            pageIcon = selectedContent?.icon,
            showBottomToolbar = showBottomToolbar,
        )
    }

    // Add-on quick settings / options page / Firefox sign-in, floated over the current page in a
    // standalone GeckoView card (never a real tab). onIntercept handles the two navigations that
    // should close the card: an add-on .xpi install request and the FxA OAuth success redirect.
    UtilityWebCardHost(
        request = utilityWeb,
        engine = components.engine,
        onIntercept = { url ->
            components.firefoxSyncController.handleOAuthRedirect(url) ||
                components.addonController.installFromPageRequest(url)
        },
        onDismiss = onDismissUtilityWeb,
    )

    // Link glance (Safari/Arc). Drawn last so it floats above the content, chrome and grid.
    peekUrl?.let { link ->
        // The cropped element pixels (small) are produced off-band by the crop effect above,
        // which then frees the full-screen capture — so nothing large is retained here.
        LinkPeekCard(
            url = link,
            peekSession = peekSession,
            peekView = peekView,
            origin = peekOrigin,
            sourceRect = peekSourceRect,
            elementBitmap = peekElementBitmap?.asImageBitmap(),
            onOpenHere = { u ->
                // Open the peeked page now, reusing its already-loaded session (no reload). It
                // becomes a new foreground tab; the current tab is preserved rather than replaced.
                onPeekAdopt(u, true)
            },
            onOpenNewTab = { u ->
                // Open in the background, also reusing the loaded session (no reload).
                onPeekAdopt(u, false)
            },
            onCopy = { u ->
                clipboardManager.setText(AnnotatedString(u))
                feedback.show("Link copied")
                onDismissPeek()
            },
            onShare = { u ->
                shareUrl(appContext, u) { feedback.show(it) }
                onDismissPeek()
            },
            onDownload = { u ->
                // Save the link target via DownloadsFeature → system DownloadManager (same path as
                // "Save image"): the OS handles the notification, filename and storage.
                actualSelectedTabId?.let { id ->
                    components.store.dispatch(
                        ContentAction.UpdateDownloadAction(
                            id,
                            DownloadState(url = u, private = selectedContent?.private == true),
                        )
                    )
                }
                feedback.show("Downloading…")
                onDismissPeek()
            },
            containers = peekContainers,
            onOpenInContainer = { u, contextId ->
                // Open the link in a separate profile (fresh session — a container can't reuse the
                // default-context peek session), as a new foreground tab.
                (components.tabRepository as? com.firebolt9907.tangentbrowser.state.GeckoTabRepository)
                    ?.addTabInContainer(url = u, contextId = contextId)
                onDismissPeek()
            },
            onDismiss = onDismissPeek,
        )
    }
    imageMenuUrl?.let { imageUrl ->
        // Each of these narrates itself in two beats — a progress line, then the outcome — and the
        // failure beat offers the retry a Toast never could. They're local funs rather than inline
        // lambdas so the retry action can re-invoke the very operation that failed.
        fun copyImage(u: String) {
            feedback.show("Copying image…")
            scope.launch {
                val ok = com.firebolt9907.tangentbrowser.ui.content.ImageClipboard.copy(appContext, u)
                if (ok) feedback.show("Image copied")
                else feedback.show("Couldn't copy image", "Try again") { copyImage(u) }
            }
        }

        fun saveToPhotos(u: String) {
            feedback.show("Saving to Photos…")
            scope.launch {
                val ok = com.firebolt9907.tangentbrowser.ui.content.ImageClipboard
                    .saveToPhotos(appContext, u)
                if (ok) feedback.show("Saved to Photos")
                else feedback.show("Couldn't save image", "Try again") { saveToPhotos(u) }
            }
        }

        fun copyTextFromImage(u: String) {
            feedback.show("Scanning image for text…")
            scope.launch {
                when (com.firebolt9907.tangentbrowser.ui.content.ImageClipboard
                    .copyTextFromImage(appContext, u)) {
                    is com.firebolt9907.tangentbrowser.ui.content.ImageClipboard.OcrResult.Text ->
                        feedback.show("Text copied")
                    com.firebolt9907.tangentbrowser.ui.content.ImageClipboard.OcrResult.Empty ->
                        feedback.show("No text found in image")
                    com.firebolt9907.tangentbrowser.ui.content.ImageClipboard.OcrResult.Failed ->
                        feedback.show("Couldn't read image", "Try again") { copyTextFromImage(u) }
                }
            }
        }

        ImageContextSheet(
            imageUrl = imageUrl,
            onOpenNewTab = { u -> viewModel.onOpenNewTab(url = u, title = "", selectTab = true) },
            onSearchImage = { u ->
                // Reverse image search via Google Lens (takes the image URL directly).
                val lens = "https://lens.google.com/uploadbyurl?url=" +
                    java.net.URLEncoder.encode(u, "UTF-8")
                viewModel.onOpenNewTab(url = lens, title = "", selectTab = true)
            },
            onSave = { u ->
                // Route the image through DownloadsFeature (started in TangentBrowserActivity):
                // it observes ContentState.download and hands the URL to the system
                // DownloadManager (notification + storage handled by the OS).
                actualSelectedTabId?.let { id ->
                    components.store.dispatch(
                        ContentAction.UpdateDownloadAction(
                            id,
                            DownloadState(url = u, private = selectedContent?.private == true),
                        )
                    )
                }
                // The link glance's identical "download this" dispatch says "Downloading…"; this one
                // said nothing at all, so saving an image looked like a dead button until the system
                // notification arrived.
                feedback.show("Downloading…")
            },
            onCopyImage = ::copyImage,
            onSaveToPhotos = if (com.firebolt9907.tangentbrowser.ui.content.ImageClipboard.canSaveToPhotos) {
                ::saveToPhotos
            } else null,
            onCopyText = ::copyTextFromImage,
            onCopy = { u ->
                clipboardManager.setText(AnnotatedString(u))
                feedback.show("Image address copied")
            },
            onShare = { u ->
                // Share the actual image bytes (what "Share image" implies); fall back to sharing the
                // URL text if the picture can't be fetched.
                scope.launch {
                    val ok = com.firebolt9907.tangentbrowser.ui.content.ImageClipboard
                        .shareImage(appContext, u)
                    if (!ok) shareUrl(appContext, u) { feedback.show(it) }
                }
            },
            onDismiss = onDismissImageMenu,
        )
    }

    // Last, so page-level confirmations draw over every surface that raises one.
    PageFeedbackHost(feedback, with(density) { bottomChromeInsetPx.toDp() })
}
