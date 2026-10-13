package com.firebolt9907.tangentbrowser.ui.customtab

import android.content.ClipboardManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.engine.TangentBrowserComponents
import com.firebolt9907.tangentbrowser.state.AppLinkBannerStats
import com.firebolt9907.tangentbrowser.state.BlobFile
import com.firebolt9907.tangentbrowser.state.BlobFileStore
import com.firebolt9907.tangentbrowser.state.hostFromUrl
import com.firebolt9907.tangentbrowser.ui.OpenInAppBannerHost
import com.firebolt9907.tangentbrowser.ui.PageFeedback
import com.firebolt9907.tangentbrowser.ui.SitePermissionBannerHost
import com.firebolt9907.tangentbrowser.ui.chrome.NativeApp
import com.firebolt9907.tangentbrowser.ui.chrome.nativeAppFor
import com.firebolt9907.tangentbrowser.ui.content.AppLaunchRequests
import com.firebolt9907.tangentbrowser.ui.content.BlobFileBannerHost
import com.firebolt9907.tangentbrowser.ui.content.ClipboardChangeWatcher
import com.firebolt9907.tangentbrowser.ui.content.OtpAutofill
import com.firebolt9907.tangentbrowser.ui.content.loadJavascriptUrl
import com.firebolt9907.tangentbrowser.ui.launchAssociatedApp
import com.firebolt9907.tangentbrowser.ui.launchExternalApp
import com.firebolt9907.tangentbrowser.ui.reader.ReaderManager
import com.firebolt9907.tangentbrowser.ui.reader.ReaderUnavailableBannerHost
import com.firebolt9907.tangentbrowser.ui.rememberBannerStackState
import com.firebolt9907.tangentbrowser.ui.sync.OtpPasteBannerHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Page offers belong to this custom session, independently of the main window's selected tab. */
@Composable
internal fun CustomTabBanners(
    components: TangentBrowserComponents,
    tabId: String?,
    url: String,
    readerActive: Boolean,
    manualReaderRequested: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float,
    feedback: PageFeedback,
    onPersistentBannerVisibleChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = rememberBannerStackState()
    com.firebolt9907.tangentbrowser.ui.downloads.DownloadBannerHost(
        components = components,
        private = components.store.state.customTabs.firstOrNull { it.id == tabId }?.content?.private == true,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
    )
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentTabId by rememberUpdatedState(tabId)
    val clipboard = LocalClipboardManager.current
    val clipWatcher = remember { ClipboardChangeWatcher(context) }
    var otpFieldPresent by remember(tabId, url) { mutableStateOf(false) }
    var otpPasteVisible by remember(tabId, url) { mutableStateOf(false) }

    // The foreground activity owns the global OTP hooks. Captured SMS deliveries still check the
    // original tab, trusted origin and engine session, including after returning from consent.
    LifecycleResumeEffect(tabId, url) {
        fun currentTarget(): String? = currentTabId.takeIf {
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        OtpAutofill.onFieldDetected = { reportedId ->
            if (reportedId == currentTabId && reportedId == tabId) {
                val session = components.store.state.customTabs.firstOrNull { it.id == reportedId }
                    ?.engineState?.engineSession
                if (session != null) {
                    val delivery = OtpAutofill.createDelivery(reportedId, ::currentTarget) { code, origin ->
                        if (components.store.state.customTabs.firstOrNull { it.id == reportedId }
                                ?.engineState?.engineSession === session
                        ) {
                            session.loadJavascriptUrl(OtpAutofill.buildFillScript(code, origin))
                        }
                    }
                    if (delivery != null) {
                        OtpAutofill.codeFiller = delivery
                        OtpAutofill.smsListenStarter?.invoke(delivery)
                        otpFieldPresent = true
                        if (clipWatcher.consumeChanged()) otpPasteVisible = true
                    }
                }
            }
        }
        if (otpFieldPresent) tabId?.let { OtpAutofill.onFieldDetected?.invoke(it) }
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (clipWatcher.consumeChanged() && otpFieldPresent) otpPasteVisible = true
        }
        clipWatcher.addListener(listener)
        if (clipWatcher.consumeChanged() && otpFieldPresent) otpPasteVisible = true
        onPauseOrDispose {
            clipWatcher.removeListener(listener)
            OtpAutofill.onFieldDetected = null
            OtpAutofill.codeFiller = null
        }
    }
    LaunchedEffect(otpPasteVisible) {
        if (otpPasteVisible) {
            delay(30_000)
            otpPasteVisible = false
        }
    }

    val readerManager = remember { ReaderManager.instance }
    val unavailableTab by readerManager.readerUnavailableTab.collectAsStateWithLifecycle()
    var readerUnavailableVisible by remember(tabId, url) { mutableStateOf(false) }
    LaunchedEffect(unavailableTab, tabId, manualReaderRequested) {
        if (tabId != null && unavailableTab == tabId) {
            readerUnavailableVisible = manualReaderRequested
            readerManager.clearReaderUnavailable(tabId)
        }
    }
    LaunchedEffect(readerUnavailableVisible, readerActive) {
        if (readerActive) readerUnavailableVisible = false
        else if (readerUnavailableVisible) {
            delay(6_000)
            readerUnavailableVisible = false
        }
    }

    val blobFiles by BlobFileStore.files.collectAsStateWithLifecycle()
    val blob = remember(blobFiles, url) { BlobFileStore.lookup(url) }
    var dismissedBlobId by remember(tabId) { mutableStateOf<String?>(null) }
    fun saveBlob(file: BlobFile) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { BlobFileStore.saveToDownloads(context, file) }
            if (saved != null) feedback.show("Saved $saved to Downloads")
            else feedback.show("Couldn't save this file", "Try again") { saveBlob(file) }
        }
    }

    val pendingLaunch by AppLaunchRequests.pending.collectAsStateWithLifecycle()
    val requestedLaunch = pendingLaunch?.takeIf { tabId != null && it.tabId == tabId }
    val associatedApp by produceState<NativeApp?>(null, url) {
        value = null
        value = withContext(Dispatchers.IO) { nativeAppFor(context.packageManager, url) }
    }
    val appStats = remember { AppLinkBannerStats(context) }
    val dismissedHosts = remember { mutableStateSetOf<String>() }
    var actedPage by remember { mutableStateOf<String?>(null) }
    var blacklistPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    val host = appStats.normalizeHost(hostFromUrl(url))
    val pageKey = "$tabId|$url"
    val showPassive = tabId != null && requestedLaunch == null && associatedApp != null &&
        host.isNotBlank() && host !in dismissedHosts && !appStats.isBlacklisted(host) &&
        pageKey != actedPage
    val appBannerVisible = requestedLaunch != null || showPassive
    SideEffect { onPersistentBannerVisibleChange(appBannerVisible) }
    fun openRequestedApp() {
        val request = requestedLaunch ?: return
        launchExternalApp(context, request.intent, request.fallbackUrl,
            onFallback = { fallback ->
                request.tabId?.let { components.sessionUseCases.loadUrl(fallback, sessionId = it) }
            },
            onMessage = { feedback.show(it) },
        )
        AppLaunchRequests.clearForTab(request.tabId)
    }
    LaunchedEffect(requestedLaunch) {
        if (requestedLaunch != null && components.globalPreferencesStore.autoOpenAppLinks) {
            openRequestedApp()
        }
    }

    SitePermissionBannerHost(
        controller = components.sitePermissionController,
        tabId = tabId,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
    )
    OtpPasteBannerHost(
        visible = otpPasteVisible,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        onPaste = {
            val code = clipboard.getText()?.text?.let(OtpAutofill::extractCode)
            if (code != null) OtpAutofill.deliverCode(code)
            else feedback.show("No code found in clipboard")
            otpPasteVisible = false
        },
        onDismiss = { otpPasteVisible = false },
    )
    BlobFileBannerHost(
        file = blob,
        visible = blob != null && blob.id != dismissedBlobId,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        onSave = { file -> dismissedBlobId = file.id; saveBlob(file) },
        onDismiss = { dismissedBlobId = blob?.id },
    )
    ReaderUnavailableBannerHost(
        visible = readerUnavailableVisible,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        onDismiss = { readerUnavailableVisible = false },
    )
    OpenInAppBannerHost(
        appName = requestedLaunch?.appName?.takeIf { it.isNotBlank() }
            ?: if (requestedLaunch != null) "app" else associatedApp?.label,
        packageName = requestedLaunch?.packageName ?: associatedApp?.packageName,
        requested = requestedLaunch != null,
        visible = appBannerVisible,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        onOpen = {
            if (requestedLaunch != null) openRequestedApp()
            else associatedApp?.let { app ->
                launchAssociatedApp(context, url, app.packageName)
                appStats.recordOpen(host)
                actedPage = pageKey
            }
        },
        onDismiss = {
            if (requestedLaunch != null) AppLaunchRequests.clearForTab(tabId)
            else {
                dismissedHosts.add(host)
                if (appStats.recordDismiss(host)) {
                    blacklistPrompt = host to (associatedApp?.label ?: "the app")
                }
            }
        },
    )
    blacklistPrompt?.let { (site, appName) ->
        fun dismissPrompt() { appStats.markPrompted(site); blacklistPrompt = null }
        AlertDialog(
            onDismissRequest = ::dismissPrompt,
            title = { Text("Stop suggesting apps for $site?") },
            text = { Text("You've dismissed “Open in $appName” several times on $site.") },
            confirmButton = {
                TextButton(onClick = { appStats.addToBlacklist(site); blacklistPrompt = null }) {
                    Text("Stop showing")
                }
            },
            dismissButton = { TextButton(onClick = ::dismissPrompt) { Text("Keep showing") } },
        )
    }
}
