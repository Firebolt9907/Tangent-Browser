package com.firebolt9907.tangentbrowser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.DisposableEffect
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.firebolt9907.tangentbrowser.state.GlobalPreferencesStore
import com.firebolt9907.tangentbrowser.state.PreloadMode
import com.firebolt9907.tangentbrowser.state.WebDarkModeTrigger
import com.firebolt9907.tangentbrowser.state.SearchEngine
import com.firebolt9907.tangentbrowser.ui.BiometricGate
import com.firebolt9907.tangentbrowser.ui.chrome.WideBarLayout
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.SheetCard
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SheetRowDivider
import com.firebolt9907.tangentbrowser.ui.components.SheetSectionHeader
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    globalPrefsStore: GlobalPreferencesStore,
    onDismiss: () -> Unit,
    onSettingChanged: () -> Unit,
    onClearSitePermissions: () -> Unit = {},
    onClearBrowsingData: (history: Boolean, cookiesAndSiteData: Boolean, cache: Boolean) -> Unit =
        { _, _, _ -> },
    onReplayOnboarding: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = rememberTangentHaptics()
    var trackingProtection by remember { mutableStateOf(globalPrefsStore.trackingProtection) }
    var blockPopUps by remember { mutableStateOf(globalPrefsStore.blockPopUps) }
    var javaScriptEnabled by remember { mutableStateOf(globalPrefsStore.javaScriptEnabled) }
    var httpsOnly by remember { mutableStateOf(globalPrefsStore.httpsOnly) }
    var globalPrivacyControl by remember { mutableStateOf(globalPrefsStore.globalPrivacyControl) }
    var keepAliveInBackground by remember { mutableStateOf(globalPrefsStore.keepAliveInBackground) }
    var searchEngine by remember { mutableStateOf(globalPrefsStore.searchEngine) }
    var showCustomSearchDialog by remember { mutableStateOf(false) }
    var searchSuggestionsEnabled by remember { mutableStateOf(globalPrefsStore.searchSuggestionsEnabled) }
    var showSearchTermsInBar by remember { mutableStateOf(globalPrefsStore.showSearchTermsInBar) }
    var httpCacheSizeMb by remember { mutableStateOf(globalPrefsStore.httpCacheSizeMb) }
    var preloadMode by remember { mutableStateOf(globalPrefsStore.preloadMode) }
    var readerSiteRules by remember { mutableStateOf(globalPrefsStore.readerSiteRules) }
    var cookieBannerHandlingMode by remember { mutableStateOf(globalPrefsStore.cookieBannerHandlingMode) }
    var queryParameterStripping by remember { mutableStateOf(globalPrefsStore.queryParameterStripping) }
    var mediaPlaybackRequiresUserGesture by remember { mutableStateOf(globalPrefsStore.mediaPlaybackRequiresUserGesture) }
    var cookieBehaviorOptInPartitioning by remember { mutableStateOf(globalPrefsStore.cookieBehaviorOptInPartitioning) }
    var fingerprintingProtection by remember { mutableStateOf(globalPrefsStore.fingerprintingProtection) }
    var dohSettingsMode by remember { mutableStateOf(globalPrefsStore.dohSettingsMode) }
    var enterpriseRootsEnabled by remember { mutableStateOf(globalPrefsStore.enterpriseRootsEnabled) }
    var forceUserScalableContent by remember { mutableStateOf(globalPrefsStore.forceUserScalableContent) }
    var fontSizeFactor by remember { mutableStateOf(globalPrefsStore.fontSizeFactor) }
    var loginAutofillEnabled by remember { mutableStateOf(globalPrefsStore.loginAutofillEnabled) }
    var biometricAutofillEnabled by remember { mutableStateOf(globalPrefsStore.biometricAutofillEnabled) }
    var bitwardenSaveTargetIsBitwarden by remember { mutableStateOf(globalPrefsStore.bitwardenSaveTargetIsBitwarden) }
    var systemAutofillEnabled by remember { mutableStateOf(globalPrefsStore.systemAutofillEnabled) }
    var passkeyMode by remember { mutableStateOf(globalPrefsStore.passkeyMode) }
    var creditCardAutofillEnabled by remember { mutableStateOf(globalPrefsStore.creditCardAutofillEnabled) }
    var addressAutofillEnabled by remember { mutableStateOf(globalPrefsStore.addressAutofillEnabled) }
    var formFieldMemoryEnabled by remember { mutableStateOf(globalPrefsStore.formFieldMemoryEnabled) }
    var autoOpenAppLinks by remember { mutableStateOf(globalPrefsStore.autoOpenAppLinks) }
    var webFontsEnabled by remember { mutableStateOf(globalPrefsStore.webFontsEnabled) }
    var domStorageEnabled by remember { mutableStateOf(globalPrefsStore.domStorageEnabled) }
    var suspendMediaWhenInactive by remember { mutableStateOf(globalPrefsStore.suspendMediaWhenInactive) }
    var tabAutoArchiveHours by remember { mutableStateOf(globalPrefsStore.tabAutoArchiveHours) }
    var scrollBarsEnabled by remember { mutableStateOf(globalPrefsStore.scrollBarsEnabled) }
    var allowFileAccess by remember { mutableStateOf(globalPrefsStore.allowFileAccess) }
    var postQuantumKeyExchangeEnabled by remember { mutableStateOf(globalPrefsStore.postQuantumKeyExchangeEnabled) }
    var safeBrowsingV5Enabled by remember { mutableStateOf(globalPrefsStore.safeBrowsingV5Enabled) }
    var automaticFontSizeAdjustment by remember { mutableStateOf(globalPrefsStore.automaticFontSizeAdjustment) }
    var automaticLanguageAdjustment by remember { mutableStateOf(globalPrefsStore.automaticLanguageAdjustment) }
    var cookieBannerHandlingGlobalRulesSubFrames by remember { mutableStateOf(globalPrefsStore.cookieBannerHandlingGlobalRulesSubFrames) }
    var emailTrackerBlockingPrivateBrowsing by remember { mutableStateOf(globalPrefsStore.emailTrackerBlockingPrivateBrowsing) }
    var dohProviderUrl by remember { mutableStateOf(globalPrefsStore.dohProviderUrl) }
    var lnaBlockingEnabled by remember { mutableStateOf(globalPrefsStore.lnaBlockingEnabled) }
    var allowFileAccessFromFileURLs by remember { mutableStateOf(globalPrefsStore.allowFileAccessFromFileURLs) }
    var bannedPorts by remember { mutableStateOf(globalPrefsStore.bannedPorts) }
    var fdlibmMathEnabled by remember { mutableStateOf(globalPrefsStore.fdlibmMathEnabled) }
    var queryParameterStrippingStripList by remember { mutableStateOf(globalPrefsStore.queryParameterStrippingStripList) }
    var queryParameterStrippingAllowList by remember { mutableStateOf(globalPrefsStore.queryParameterStrippingAllowList) }
    var crliteChannel by remember { mutableStateOf(globalPrefsStore.crliteChannel) }
    var showBottomToolbar by remember { mutableStateOf(globalPrefsStore.showBottomToolbar) }
    var pullMenuEnabled by remember { mutableStateOf(globalPrefsStore.pullMenuEnabled) }
    var matchSiteThemeColor by remember { mutableStateOf(globalPrefsStore.matchSiteThemeColor) }
    var tintPagesToSystemColor by remember { mutableStateOf(globalPrefsStore.tintPagesToSystemColor) }
    var webDarkModeTrigger by remember { mutableStateOf(globalPrefsStore.webDarkModeTrigger) }
    var webDarkModeAggressive by remember { mutableStateOf(globalPrefsStore.webDarkModeAggressive) }
    var webDarkModeImageAnalysis by remember { mutableStateOf(globalPrefsStore.webDarkModeImageAnalysis) }
    var handoffEnabled by remember { mutableStateOf(globalPrefsStore.handoffEnabled) }
    var blockAllAutoplay by remember { mutableStateOf(globalPrefsStore.blockAllAutoplay) }
    var webNotificationsEnabled by remember { mutableStateOf(globalPrefsStore.webNotificationsEnabled) }
    var serviceWorkersEnabled by remember { mutableStateOf(globalPrefsStore.serviceWorkersEnabled) }
    var webRtcIpsHidden by remember { mutableStateOf(globalPrefsStore.webRtcIpsHidden) }
    var sendBeaconEnabled by remember { mutableStateOf(globalPrefsStore.sendBeaconEnabled) }
    var wideBarLayout by remember {
        mutableStateOf(WideBarLayout.fromName(globalPrefsStore.wideBarLayoutName))
    }

    // The sheet has two pages: a curated main page and an "Advanced" page holding the long tail of
    // power-user toggles. They share all the hoisted state above, so switching is just a branch.
    var showAdvanced by remember { mutableStateOf(false) }

    // Settings' feedback used to be android.widget.Toast: unthemed, drawn outside the sheet, and on
    // modern Android often swallowed while a sheet is up. A SnackbarHost keeps it on the surface the
    // action happened on. Lives in the sheet's overlay slot so showing it can't shove the content.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    TangentModalSheet(
        onDismissRequest = onDismiss,
        scrollable = true,
        overlay = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp),
            )
        },
    ) {
        if (showAdvanced) {
            AdvancedSettingsPage(
                globalPrefsStore = globalPrefsStore,
                haptics = haptics,
                onSettingChanged = onSettingChanged,
                onBack = { haptics.softClick(); showAdvanced = false },
                globalPrivacyControl = globalPrivacyControl,
                onGlobalPrivacyControlChange = { globalPrivacyControl = it },
                cookieBehaviorOptInPartitioning = cookieBehaviorOptInPartitioning,
                onCookieBehaviorOptInPartitioningChange = { cookieBehaviorOptInPartitioning = it },
                fingerprintingProtection = fingerprintingProtection,
                onFingerprintingProtectionChange = { fingerprintingProtection = it },
                fdlibmMathEnabled = fdlibmMathEnabled,
                onFdlibmMathEnabledChange = { fdlibmMathEnabled = it },
                queryParameterStripping = queryParameterStripping,
                onQueryParameterStrippingChange = { queryParameterStripping = it },
                queryParameterStrippingStripList = queryParameterStrippingStripList,
                onQueryParameterStrippingStripListChange = { queryParameterStrippingStripList = it },
                queryParameterStrippingAllowList = queryParameterStrippingAllowList,
                onQueryParameterStrippingAllowListChange = { queryParameterStrippingAllowList = it },
                dohSettingsMode = dohSettingsMode,
                onDohSettingsModeChange = { dohSettingsMode = it },
                dohProviderUrl = dohProviderUrl,
                onDohProviderUrlChange = { dohProviderUrl = it },
                enterpriseRootsEnabled = enterpriseRootsEnabled,
                onEnterpriseRootsEnabledChange = { enterpriseRootsEnabled = it },
                domStorageEnabled = domStorageEnabled,
                onDomStorageEnabledChange = { domStorageEnabled = it },
                allowFileAccess = allowFileAccess,
                onAllowFileAccessChange = { allowFileAccess = it },
                allowFileAccessFromFileURLs = allowFileAccessFromFileURLs,
                onAllowFileAccessFromFileURLsChange = { allowFileAccessFromFileURLs = it },
                postQuantumKeyExchangeEnabled = postQuantumKeyExchangeEnabled,
                onPostQuantumKeyExchangeEnabledChange = { postQuantumKeyExchangeEnabled = it },
                safeBrowsingV5Enabled = safeBrowsingV5Enabled,
                onSafeBrowsingV5EnabledChange = { safeBrowsingV5Enabled = it },
                emailTrackerBlockingPrivateBrowsing = emailTrackerBlockingPrivateBrowsing,
                onEmailTrackerBlockingPrivateBrowsingChange = { emailTrackerBlockingPrivateBrowsing = it },
                cookieBannerHandlingGlobalRulesSubFrames = cookieBannerHandlingGlobalRulesSubFrames,
                onCookieBannerHandlingGlobalRulesSubFramesChange = { cookieBannerHandlingGlobalRulesSubFrames = it },
                lnaBlockingEnabled = lnaBlockingEnabled,
                onLnaBlockingEnabledChange = { lnaBlockingEnabled = it },
                bannedPorts = bannedPorts,
                onBannedPortsChange = { bannedPorts = it },
                crliteChannel = crliteChannel,
                onCrliteChannelChange = { crliteChannel = it },
                javaScriptEnabled = javaScriptEnabled,
                onJavaScriptEnabledChange = { javaScriptEnabled = it },
                webFontsEnabled = webFontsEnabled,
                onWebFontsEnabledChange = { webFontsEnabled = it },
                suspendMediaWhenInactive = suspendMediaWhenInactive,
                onSuspendMediaWhenInactiveChange = { suspendMediaWhenInactive = it },
                tabAutoArchiveHours = tabAutoArchiveHours,
                onTabAutoArchiveHoursChange = { tabAutoArchiveHours = it },
                blockAllAutoplay = blockAllAutoplay,
                onBlockAllAutoplayChange = { blockAllAutoplay = it },
                webNotificationsEnabled = webNotificationsEnabled,
                onWebNotificationsEnabledChange = { webNotificationsEnabled = it },
                serviceWorkersEnabled = serviceWorkersEnabled,
                onServiceWorkersEnabledChange = { serviceWorkersEnabled = it },
                webRtcIpsHidden = webRtcIpsHidden,
                onWebRtcIpsHiddenChange = { webRtcIpsHidden = it },
                sendBeaconEnabled = sendBeaconEnabled,
                onSendBeaconEnabledChange = { sendBeaconEnabled = it },
                scrollBarsEnabled = scrollBarsEnabled,
                onScrollBarsEnabledChange = { scrollBarsEnabled = it },
                forceUserScalableContent = forceUserScalableContent,
                onForceUserScalableContentChange = { forceUserScalableContent = it },
                automaticFontSizeAdjustment = automaticFontSizeAdjustment,
                onAutomaticFontSizeAdjustmentChange = { automaticFontSizeAdjustment = it },
                automaticLanguageAdjustment = automaticLanguageAdjustment,
                onAutomaticLanguageAdjustmentChange = { automaticLanguageAdjustment = it },
                wideBarLayout = wideBarLayout,
                onWideBarLayoutChange = { wideBarLayout = it },
                httpCacheSizeMb = httpCacheSizeMb,
                onHttpCacheSizeMbChange = { httpCacheSizeMb = it },
            )
            return@TangentModalSheet
        }

        SheetHeader("Settings")

        Spacer(Modifier.height(20.dp))

        DefaultBrowserCard(onMessage = ::notify)

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
            SheetRowDivider()
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
            SheetRowDivider()
            SettingsPicker(
                title = "Cookie banner auto-dismiss",
                // "Reject only" is the private choice but silently does nothing on the many banners
                // that offer no reject control — those sites keep showing the dialog. Say so in the
                // subtitle, so a banner that stays up doesn't read as the feature being broken.
                // (Option labels must stay identical to selectedLabel — SettingsPicker marks the
                // active row by string equality.)
                subtitle = when (cookieBannerHandlingMode) {
                    1 -> "Declines banners that offer a reject option; others stay up"
                    2 -> "Declines banners, accepting only when there's no reject option"
                    else -> "Automatically dismiss cookie consent banners"
                },
                selectedLabel = when (cookieBannerHandlingMode) {
                    1 -> "Reject only"
                    2 -> "Reject, else accept"
                    else -> "Disabled"
                },
                options = listOf(
                    "Disabled" to {
                        cookieBannerHandlingMode = 0
                        globalPrefsStore.cookieBannerHandlingMode = 0
                        onSettingChanged()
                    },
                    "Reject only" to {
                        cookieBannerHandlingMode = 1
                        globalPrefsStore.cookieBannerHandlingMode = 1
                        onSettingChanged()
                    },
                    "Reject, else accept" to {
                        cookieBannerHandlingMode = 2
                        globalPrefsStore.cookieBannerHandlingMode = 2
                        onSettingChanged()
                    }
                ),
                haptics = haptics,
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
            SheetRowDivider()
            SettingsToggle(
                title = "Block autoplaying media",
                subtitle = "Require user interaction before playing audio or video",
                checked = mediaPlaybackRequiresUserGesture,
                onCheckedChange = {
                    mediaPlaybackRequiresUserGesture = it
                    globalPrefsStore.mediaPlaybackRequiresUserGesture = it
                    onSettingChanged()
                },
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Login Autofill",
                subtitle = "Fill saved usernames and passwords",
                checked = loginAutofillEnabled,
                onCheckedChange = {
                    loginAutofillEnabled = it
                    globalPrefsStore.loginAutofillEnabled = it
                    onSettingChanged()
                },
            )
            if (loginAutofillEnabled) {
                SheetRowDivider()
                SettingsToggle(
                    title = "Require Biometrics",
                    subtitle = "Confirm with fingerprint, face, or device PIN before filling a password",
                    checked = biometricAutofillEnabled,
                    onCheckedChange = { requested ->
                        // Changing this protection is itself gated behind biometrics so it can't be
                        // silently flipped on a briefly-unlocked device. The prompt is a no-op (and
                        // the change applies immediately) on a device with no secure lock.
                        BiometricGate.authenticate(
                            context = context,
                            title = if (requested) "Require biometrics" else "Disable biometrics",
                            subtitle = "Confirm to change password autofill security",
                            onSuccess = {
                                biometricAutofillEnabled = requested
                                globalPrefsStore.biometricAutofillEnabled = requested
                                onSettingChanged()
                            },
                        )
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Save New Logins to Bitwarden",
                    subtitle = "When a Bitwarden account is connected, save newly captured passwords " +
                        "to your vault instead of this device",
                    checked = bitwardenSaveTargetIsBitwarden,
                    onCheckedChange = {
                        bitwardenSaveTargetIsBitwarden = it
                        globalPrefsStore.bitwardenSaveTargetIsBitwarden = it
                        onSettingChanged()
                    },
                )
            }
            SheetRowDivider()
            SettingsToggle(
                title = "Fill With Other Apps",
                subtitle = "Let a device password manager (Bitwarden, 1Password…) fill web forms " +
                    "through Android's autofill service. Works alongside the built-in login vault.",
                checked = systemAutofillEnabled,
                onCheckedChange = {
                    systemAutofillEnabled = it
                    globalPrefsStore.systemAutofillEnabled = it
                    onSettingChanged()
                },
            )
            if (systemAutofillEnabled) {
                SheetRowDivider()
                SystemAutofillServiceRow(onMessage = ::notify)
            }
            SheetRowDivider()
            SettingsPicker(
                title = "Passkeys",
                subtitle = "System opens Android's passkey provider (including Bitwarden when Tangent " +
                    "is enabled as a trusted browser). USB / NFC security key talks directly to a " +
                    "FIDO2 key. Built-in stores keys only in this browser; the " +
                    "experimental Bitwarden Vault option uses Tangent's custom vault client. Restart to apply.",
                selectedLabel = passkeyMode.label,
                options = com.firebolt9907.tangentbrowser.state.PasskeyMode.entries.map { mode ->
                    mode.label to {
                        passkeyMode = mode
                        globalPrefsStore.passkeyMode = mode
                        onSettingChanged()
                    }
                },
                haptics = haptics,
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Payment Method Autofill",
                subtitle = "Fill saved cards into checkout forms",
                checked = creditCardAutofillEnabled,
                onCheckedChange = {
                    creditCardAutofillEnabled = it
                    globalPrefsStore.creditCardAutofillEnabled = it
                    onSettingChanged()
                },
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Address Autofill",
                subtitle = "Fill saved addresses and contact info into forms",
                checked = addressAutofillEnabled,
                onCheckedChange = {
                    addressAutofillEnabled = it
                    globalPrefsStore.addressAutofillEnabled = it
                    onSettingChanged()
                },
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Remember Form Fields",
                subtitle = "Offer previously entered values for other form fields",
                checked = formFieldMemoryEnabled,
                onCheckedChange = {
                    formFieldMemoryEnabled = it
                    globalPrefsStore.formFieldMemoryEnabled = it
                    onSettingChanged()
                },
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Open links in apps automatically",
                subtitle = "When a site opens an installed app, launch it instead of showing a banner",
                checked = autoOpenAppLinks,
                onCheckedChange = {
                    autoOpenAppLinks = it
                    globalPrefsStore.autoOpenAppLinks = it
                    onSettingChanged()
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        SheetSectionHeader("Appearance")
        Spacer(Modifier.height(8.dp))
        SheetCard {
            SettingsPicker(
                title = "Text zoom",
                subtitle = "Adjust text scale factor on web pages",
                selectedLabel = when (fontSizeFactor) {
                    0.5f -> "50%"
                    0.8f -> "80%"
                    1.0f -> "100% (Default)"
                    1.2f -> "120%"
                    1.5f -> "150%"
                    2.0f -> "200%"
                    else -> "${(fontSizeFactor * 100).toInt()}%"
                },
                options = listOf(
                    "50%" to {
                        fontSizeFactor = 0.5f
                        globalPrefsStore.fontSizeFactor = 0.5f
                        onSettingChanged()
                    },
                    "80%" to {
                        fontSizeFactor = 0.8f
                        globalPrefsStore.fontSizeFactor = 0.8f
                        onSettingChanged()
                    },
                    "100% (Default)" to {
                        fontSizeFactor = 1.0f
                        globalPrefsStore.fontSizeFactor = 1.0f
                        onSettingChanged()
                    },
                    "120%" to {
                        fontSizeFactor = 1.2f
                        globalPrefsStore.fontSizeFactor = 1.2f
                        onSettingChanged()
                    },
                    "150%" to {
                        fontSizeFactor = 1.5f
                        globalPrefsStore.fontSizeFactor = 1.5f
                        onSettingChanged()
                    },
                    "200%" to {
                        fontSizeFactor = 2.0f
                        globalPrefsStore.fontSizeFactor = 2.0f
                        onSettingChanged()
                    }
                ),
                haptics = haptics,
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Show bottom toolbar",
                subtitle = "Show Back, Forward, Share, Bookmarks, and Tabs below the address bar",
                checked = showBottomToolbar,
                onCheckedChange = {
                    showBottomToolbar = it
                    globalPrefsStore.showBottomToolbar = it
                    onSettingChanged()
                },
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Multi-function pull to refresh",
                subtitle = "Pull down past the top of a page, then slide the same finger sideways to " +
                    "pick New Tab, Refresh, or Reader. Pulling straight down still just refreshes",
                checked = pullMenuEnabled,
                onCheckedChange = {
                    pullMenuEnabled = it
                    globalPrefsStore.pullMenuEnabled = it
                    onSettingChanged()
                },
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Match website theme color",
                subtitle = "Tint the address bar and accents to the site's declared theme color",
                checked = matchSiteThemeColor,
                onCheckedChange = {
                    matchSiteThemeColor = it
                    globalPrefsStore.matchSiteThemeColor = it
                    onSettingChanged()
                },
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Tint pages to my system color",
                subtitle = "Recolor website content toward your device's accent color, like Arc boosts",
                checked = tintPagesToSystemColor,
                onCheckedChange = {
                    tintPagesToSystemColor = it
                    globalPrefsStore.tintPagesToSystemColor = it
                    onSettingChanged()
                },
            )
            SheetRowDivider()
            SettingsPicker(
                title = "Dark mode for every website",
                subtitle = "Use each site's dark theme when it has one, and darken the rest — fast, unlike heavy page recolorers",
                selectedLabel = webDarkModeTrigger.label,
                options = WebDarkModeTrigger.entries.map { trigger ->
                    trigger.label to {
                        webDarkModeTrigger = trigger
                        globalPrefsStore.webDarkModeTrigger = trigger
                        onSettingChanged()
                    }
                },
                haptics = haptics,
            )
            if (webDarkModeTrigger != WebDarkModeTrigger.Off) {
                SheetRowDivider()
                SettingsToggle(
                    title = "Adaptive recoloring",
                    subtitle = "Recolor each section instead of inverting the whole page. Keeps photos true and preserves sections a site already made dark. Heavier on complex pages",
                    checked = webDarkModeAggressive,
                    onCheckedChange = {
                        webDarkModeAggressive = it
                        globalPrefsStore.webDarkModeAggressive = it
                        onSettingChanged()
                    },
                )
                if (!webDarkModeAggressive) {
                    SheetRowDivider()
                    SettingsToggle(
                        title = "Analyze image content (experimental)",
                        subtitle = "Sample image pixels to tell photos from icons and logos, so each is darkened correctly. Heavier; for testing",
                        checked = webDarkModeImageAnalysis,
                        onCheckedChange = {
                            webDarkModeImageAnalysis = it
                            globalPrefsStore.webDarkModeImageAnalysis = it
                            onSettingChanged()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SheetSectionHeader("Search")
        Spacer(Modifier.height(8.dp))
        SheetCard {
            SettingsPicker(
                title = "Search engine",
                subtitle = "Used for address-bar searches and suggestions",
                selectedLabel = searchEngine.label,
                // Built-ins, then the user's saved custom engine (if any), then the editor. Picking
                // "Custom…" always opens the dialog — that's the only way to create the first one.
                options = buildList {
                    SearchEngine.builtIns.forEach { engine ->
                        add(engine.label to {
                            searchEngine = engine
                            globalPrefsStore.searchEngine = engine
                        })
                    }
                    globalPrefsStore.customSearchEngine?.let { custom ->
                        if (custom.label != searchEngine.label) {
                            add(custom.label to {
                                searchEngine = custom
                                globalPrefsStore.searchEngine = custom
                            })
                        }
                    }
                    add("Custom…" to { showCustomSearchDialog = true })
                },
                haptics = haptics,
            )
            if (searchEngine is SearchEngine.Custom) {
                SheetRowDivider()
                SettingsRow(
                    title = "Edit custom engine",
                    subtitle = (searchEngine as SearchEngine.Custom).searchTemplate,
                    onClick = { haptics.select(); showCustomSearchDialog = true },
                )
            }
            SheetRowDivider()
            SettingsToggle(
                title = "Search suggestions",
                // A custom engine with no suggestions URL can't answer this toggle at all — say so
                // rather than offering a switch that quietly does nothing.
                subtitle = if (searchEngine.suggestUrl("test") == null) {
                    "${searchEngine.label} has no suggestions URL set, so only your history and bookmarks are used."
                } else {
                    "Show live suggestions from ${searchEngine.label} as you type. When off, only your history and bookmarks are used and no keystrokes leave the device."
                },
                checked = searchSuggestionsEnabled,
                onCheckedChange = {
                    searchSuggestionsEnabled = it
                    globalPrefsStore.searchSuggestionsEnabled = it
                },
            )
            SheetRowDivider()
            SettingsToggle(
                title = "Show search terms in address bar",
                subtitle = "On a ${searchEngine.label} results page, show what you searched instead of the site address.",
                checked = showSearchTermsInBar,
                onCheckedChange = {
                    showSearchTermsInBar = it
                    globalPrefsStore.showSearchTermsInBar = it
                    onSettingChanged()
                },
            )
        }

        if (showCustomSearchDialog) {
            CustomSearchEngineDialog(
                existing = globalPrefsStore.customSearchEngine,
                onDismiss = { showCustomSearchDialog = false },
                onSave = { custom ->
                    globalPrefsStore.searchEngine = custom
                    searchEngine = custom
                    showCustomSearchDialog = false
                    haptics.create()
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        SheetSectionHeader("Sync")
        Spacer(Modifier.height(8.dp))
        SheetCard {
            SettingsToggle(
                title = "Handoff",
                subtitle = "Show a banner to continue a tab that was just open on another Firefox Sync device.",
                checked = handoffEnabled,
                onCheckedChange = {
                    handoffEnabled = it
                    globalPrefsStore.handoffEnabled = it
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
            SheetRowDivider()
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
                haptics = haptics,
            )
        }

        Spacer(Modifier.height(20.dp))

        SheetSectionHeader("Reader Mode")
        Spacer(Modifier.height(8.dp))
        SheetCard {
            SettingsToggle(
                title = "Per-site reader tweaks",
                subtitle = "Fixes articles that don't parse cleanly, using per-site rules. Updates " +
                    "arrive from GitHub — signature-verified and downloaded in full — so new sites " +
                    "work without an app update, and the update never reveals which sites you visit. " +
                    "Turn off to use only the rules built into this app version (no update check).",
                checked = readerSiteRules,
                onCheckedChange = {
                    readerSiteRules = it
                    globalPrefsStore.readerSiteRules = it
                    com.firebolt9907.tangentbrowser.ui.reader.ReaderRules.setEnabled(it)
                    // Turning it on: pull the latest rules right away rather than waiting for the
                    // next throttled startup check.
                    if (it) {
                        com.firebolt9907.tangentbrowser.ui.reader.ReaderRules.refreshIfDue(
                            context, globalPrefsStore, force = true,
                        )
                    }
                    onSettingChanged()
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        SheetSectionHeader("Browsing data")
        Spacer(Modifier.height(8.dp))
        SheetCard {
            var showClearDialog by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.select()
                        showClearDialog = true
                    }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Clear browsing data",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (showClearDialog) {
                ClearBrowsingDataDialog(
                    onDismiss = { showClearDialog = false },
                    onConfirm = { history, cookies, cache ->
                        showClearDialog = false
                        haptics.remove()
                        onClearBrowsingData(history, cookies, cache)
                        notify("Browsing data cleared")
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SheetSectionHeader("Site permissions")
        Spacer(Modifier.height(8.dp))
        SheetCard {
            var showClearPermsConfirm by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.select()
                        showClearPermsConfirm = true
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
                    modifier = Modifier.size(14.dp),
                )
            }
            // Destructive + not undoable — confirm first, like the Clear-browsing-data row above.
            if (showClearPermsConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearPermsConfirm = false },
                    title = { Text("Clear site permissions?") },
                    text = {
                        Text(
                            "Forget every site's remembered choices for camera, microphone, location, " +
                                "notifications and the like? Sites will ask again next time.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearPermsConfirm = false
                            haptics.remove()
                            onClearSitePermissions()
                            notify("Cleared remembered site permissions")
                        }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearPermsConfirm = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SheetCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { haptics.select(); showAdvanced = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Advanced settings",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Fingerprinting, DNS-over-HTTPS, JavaScript, file access, and more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SheetSectionHeader("About")
        Spacer(Modifier.height(8.dp))
        SheetCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.select()
                        onReplayOnboarding()
                    }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Show onboarding again",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
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
                text = "Tangent Browser $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )
        }
    }
}

private fun formatCacheSize(mb: Int): String =
    if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

private fun formatAutoArchive(hours: Int): String = when {
    hours <= 0 -> "Never"
    hours < 24 -> "$hours hours"
    hours == 24 -> "1 day"
    hours % 168 == 0 -> "${hours / 168} week${if (hours / 168 == 1) "" else "s"}"
    hours % 24 == 0 -> "${hours / 24} days"
    else -> "$hours hours"
}

/**
 * The "Advanced settings" sub-page. Holds the long tail of power-user toggles that were previously
 * flattened into the main sheet — the ones a typical user should never need to touch. State lives in
 * the parent [SettingsSheet] (so it survives page switches); each control is passed its current value
 * plus a setter that also writes the pref and notifies. The parent renders this instead of the main
 * page when its `showAdvanced` flag is set.
 */
@Composable
private fun AdvancedSettingsPage(
    globalPrefsStore: GlobalPreferencesStore,
    haptics: com.firebolt9907.tangentbrowser.ui.TangentHaptics,
    onSettingChanged: () -> Unit,
    onBack: () -> Unit,
    globalPrivacyControl: Boolean,
    onGlobalPrivacyControlChange: (Boolean) -> Unit,
    cookieBehaviorOptInPartitioning: Boolean,
    onCookieBehaviorOptInPartitioningChange: (Boolean) -> Unit,
    fingerprintingProtection: Boolean,
    onFingerprintingProtectionChange: (Boolean) -> Unit,
    fdlibmMathEnabled: Boolean,
    onFdlibmMathEnabledChange: (Boolean) -> Unit,
    queryParameterStripping: Boolean,
    onQueryParameterStrippingChange: (Boolean) -> Unit,
    queryParameterStrippingStripList: String,
    onQueryParameterStrippingStripListChange: (String) -> Unit,
    queryParameterStrippingAllowList: String,
    onQueryParameterStrippingAllowListChange: (String) -> Unit,
    dohSettingsMode: Int,
    onDohSettingsModeChange: (Int) -> Unit,
    dohProviderUrl: String,
    onDohProviderUrlChange: (String) -> Unit,
    enterpriseRootsEnabled: Boolean,
    onEnterpriseRootsEnabledChange: (Boolean) -> Unit,
    domStorageEnabled: Boolean,
    onDomStorageEnabledChange: (Boolean) -> Unit,
    allowFileAccess: Boolean,
    onAllowFileAccessChange: (Boolean) -> Unit,
    allowFileAccessFromFileURLs: Boolean,
    onAllowFileAccessFromFileURLsChange: (Boolean) -> Unit,
    postQuantumKeyExchangeEnabled: Boolean,
    onPostQuantumKeyExchangeEnabledChange: (Boolean) -> Unit,
    safeBrowsingV5Enabled: Boolean,
    onSafeBrowsingV5EnabledChange: (Boolean) -> Unit,
    emailTrackerBlockingPrivateBrowsing: Boolean,
    onEmailTrackerBlockingPrivateBrowsingChange: (Boolean) -> Unit,
    cookieBannerHandlingGlobalRulesSubFrames: Boolean,
    onCookieBannerHandlingGlobalRulesSubFramesChange: (Boolean) -> Unit,
    lnaBlockingEnabled: Boolean,
    onLnaBlockingEnabledChange: (Boolean) -> Unit,
    bannedPorts: String,
    onBannedPortsChange: (String) -> Unit,
    crliteChannel: String,
    onCrliteChannelChange: (String) -> Unit,
    javaScriptEnabled: Boolean,
    onJavaScriptEnabledChange: (Boolean) -> Unit,
    webFontsEnabled: Boolean,
    onWebFontsEnabledChange: (Boolean) -> Unit,
    suspendMediaWhenInactive: Boolean,
    onSuspendMediaWhenInactiveChange: (Boolean) -> Unit,
    tabAutoArchiveHours: Int,
    onTabAutoArchiveHoursChange: (Int) -> Unit,
    blockAllAutoplay: Boolean,
    onBlockAllAutoplayChange: (Boolean) -> Unit,
    webNotificationsEnabled: Boolean,
    onWebNotificationsEnabledChange: (Boolean) -> Unit,
    serviceWorkersEnabled: Boolean,
    onServiceWorkersEnabledChange: (Boolean) -> Unit,
    webRtcIpsHidden: Boolean,
    onWebRtcIpsHiddenChange: (Boolean) -> Unit,
    sendBeaconEnabled: Boolean,
    onSendBeaconEnabledChange: (Boolean) -> Unit,
    scrollBarsEnabled: Boolean,
    onScrollBarsEnabledChange: (Boolean) -> Unit,
    forceUserScalableContent: Boolean,
    onForceUserScalableContentChange: (Boolean) -> Unit,
    automaticFontSizeAdjustment: Boolean,
    onAutomaticFontSizeAdjustmentChange: (Boolean) -> Unit,
    automaticLanguageAdjustment: Boolean,
    onAutomaticLanguageAdjustmentChange: (Boolean) -> Unit,
    wideBarLayout: WideBarLayout,
    onWideBarLayoutChange: (WideBarLayout) -> Unit,
    httpCacheSizeMb: Int,
    onHttpCacheSizeMbChange: (Int) -> Unit,
) {
    // Read/written directly against the store (not hoisted through SettingsSheet) — this toggle
    // has no engine-side effect, it only gates whether "Listen to Page" appears in the aA sheet.
    var listenToPageEnabled by remember { mutableStateOf(globalPrefsStore.listenToPageEnabled) }
    SheetHeader(
        title = "Advanced",
        // IconButton, matching every other in-sheet Back (Add-ons, aA): a bare clickable Icon here
        // was only a 20dp target — well under the 48dp minimum — and used a different glyph.
        leadingIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )

    Spacer(Modifier.height(20.dp))

    SheetSectionHeader("Anti-tracking & fingerprinting")
    Spacer(Modifier.height(8.dp))
    SheetCard {
        SettingsToggle(
            title = "Global Privacy Control",
            subtitle = "Tell sites not to sell or share your data",
            checked = globalPrivacyControl,
            onCheckedChange = {
                onGlobalPrivacyControlChange(it)
                globalPrefsStore.globalPrivacyControl = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Total Cookie Protection",
            subtitle = "Isolate cookies to block cross-site tracking",
            checked = cookieBehaviorOptInPartitioning,
            onCheckedChange = {
                onCookieBehaviorOptInPartitioningChange(it)
                globalPrefsStore.cookieBehaviorOptInPartitioning = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Fingerprinting Protection",
            subtitle = "Block scripts trying to identify your device profile",
            checked = fingerprintingProtection,
            onCheckedChange = {
                onFingerprintingProtectionChange(it)
                globalPrefsStore.fingerprintingProtection = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Query Parameter Stripping",
            subtitle = "Remove tracking parameters (like fbclid, utm_*) from URLs",
            checked = queryParameterStripping,
            onCheckedChange = {
                onQueryParameterStrippingChange(it)
                globalPrefsStore.queryParameterStripping = it
                onSettingChanged()
            },
        )
    }

    Spacer(Modifier.height(20.dp))

    SheetSectionHeader("DNS & certificates")
    Spacer(Modifier.height(8.dp))
    SheetCard {
        SettingsPicker(
            title = "DNS over HTTPS (DoH)",
            subtitle = "Encrypt DNS queries to prevent tracking by your ISP",
            selectedLabel = when (dohSettingsMode) {
                1 -> "Default"
                2 -> "Increased"
                3 -> "Max"
                else -> "Off"
            },
            options = listOf(
                "Off" to {
                    onDohSettingsModeChange(0)
                    globalPrefsStore.dohSettingsMode = 0
                    onSettingChanged()
                },
                "Default" to {
                    onDohSettingsModeChange(1)
                    globalPrefsStore.dohSettingsMode = 1
                    onSettingChanged()
                },
                "Increased" to {
                    onDohSettingsModeChange(2)
                    globalPrefsStore.dohSettingsMode = 2
                    onSettingChanged()
                },
                "Max" to {
                    onDohSettingsModeChange(3)
                    globalPrefsStore.dohSettingsMode = 3
                    onSettingChanged()
                }
            ),
            haptics = haptics,
        )
    }

    Spacer(Modifier.height(20.dp))

    SheetSectionHeader("Additional protections")
    Spacer(Modifier.height(8.dp))
    SheetCard {
        SettingsToggle(
            title = "Safe Browsing V5",
            subtitle = "Real-time enhanced protection against malware and phishing",
            checked = safeBrowsingV5Enabled,
            onCheckedChange = {
                onSafeBrowsingV5EnabledChange(it)
                globalPrefsStore.safeBrowsingV5Enabled = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Email Tracker Blocking",
            subtitle = "Block common email tracking pixels/scripts on web pages",
            checked = emailTrackerBlockingPrivateBrowsing,
            onCheckedChange = {
                onEmailTrackerBlockingPrivateBrowsingChange(it)
                globalPrefsStore.emailTrackerBlockingPrivateBrowsing = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Hide local IP in WebRTC",
            subtitle = "Prevent websites from seeing your local network IP during video calls. Restart to apply.",
            checked = webRtcIpsHidden,
            onCheckedChange = {
                onWebRtcIpsHiddenChange(it)
                globalPrefsStore.webRtcIpsHidden = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Allow analytics beacons",
            subtitle = "Let sites send fire-on-unload analytics (navigator.sendBeacon). Turn off for more privacy. Restart to apply.",
            checked = sendBeaconEnabled,
            onCheckedChange = {
                onSendBeaconEnabledChange(it)
                globalPrefsStore.sendBeaconEnabled = it
                onSettingChanged()
            },
        )
    }

    Spacer(Modifier.height(20.dp))

    SheetSectionHeader("Content")
    Spacer(Modifier.height(8.dp))
    SheetCard {
        SettingsToggle(
            title = "JavaScript",
            subtitle = "Enable JavaScript on web pages",
            checked = javaScriptEnabled,
            onCheckedChange = {
                onJavaScriptEnabledChange(it)
                globalPrefsStore.javaScriptEnabled = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Suspend media when inactive",
            subtitle = "Pause background audio/video playback when a tab is inactive",
            checked = suspendMediaWhenInactive,
            onCheckedChange = {
                onSuspendMediaWhenInactiveChange(it)
                globalPrefsStore.suspendMediaWhenInactive = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsPicker(
            title = "Auto-archive tabs",
            subtitle = "Discard tabs that haven't been viewed for this long. Applies on the next app launch.",
            selectedLabel = formatAutoArchive(tabAutoArchiveHours),
            options = listOf(0, 12, 24, 72, 168, 336, 720).map { hours ->
                formatAutoArchive(hours) to {
                    onTabAutoArchiveHoursChange(hours)
                    globalPrefsStore.tabAutoArchiveHours = hours
                    onSettingChanged()
                }
            },
            haptics = haptics,
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Block all autoplay",
            subtitle = "Prevent all media from auto-playing, including muted video. Saves battery. Restart to apply.",
            checked = blockAllAutoplay,
            onCheckedChange = {
                onBlockAllAutoplayChange(it)
                globalPrefsStore.blockAllAutoplay = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Web notifications",
            subtitle = "Allow sites to request notification permission. Off by default to save battery. Restart to apply.",
            checked = webNotificationsEnabled,
            onCheckedChange = {
                onWebNotificationsEnabledChange(it)
                globalPrefsStore.webNotificationsEnabled = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Service workers",
            subtitle = "Enable service workers for PWAs and modern web apps. Disable to save battery. Restart to apply.",
            checked = serviceWorkersEnabled,
            onCheckedChange = {
                onServiceWorkersEnabledChange(it)
                globalPrefsStore.serviceWorkersEnabled = it
                onSettingChanged()
            },
        )
    }

    Spacer(Modifier.height(20.dp))

    SheetSectionHeader("Accessibility & Appearance")
    Spacer(Modifier.height(8.dp))
    SheetCard {
        SettingsToggle(
            title = "Force user zoom",
            subtitle = "Override website blocks to allow pinch-to-zoom",
            checked = forceUserScalableContent,
            onCheckedChange = {
                onForceUserScalableContentChange(it)
                globalPrefsStore.forceUserScalableContent = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Sync with system font size",
            subtitle = "Automatically adjust text scale based on Android settings",
            checked = automaticFontSizeAdjustment,
            onCheckedChange = {
                onAutomaticFontSizeAdjustmentChange(it)
                globalPrefsStore.automaticFontSizeAdjustment = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Sync with system language",
            subtitle = "Request web content in Android's system language",
            checked = automaticLanguageAdjustment,
            onCheckedChange = {
                onAutomaticLanguageAdjustmentChange(it)
                globalPrefsStore.automaticLanguageAdjustment = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Read aloud",
            subtitle = "Always show \"Listen to Page\" in the page menu. Otherwise it appears only while Reader is on.",
            checked = listenToPageEnabled,
            onCheckedChange = {
                listenToPageEnabled = it
                globalPrefsStore.listenToPageEnabled = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsPicker(
            title = "Wide screen layout",
            subtitle = "How the bottom bar arranges itself in landscape and on tablets",
            selectedLabel = when (wideBarLayout) {
                WideBarLayout.SPLIT -> "Side by side"
                WideBarLayout.CORNER -> "Bottom-right corner"
            },
            options = listOf(
                "Side by side" to {
                    onWideBarLayoutChange(WideBarLayout.SPLIT)
                    globalPrefsStore.wideBarLayoutName = WideBarLayout.SPLIT.name
                    onSettingChanged()
                },
                "Bottom-right corner" to {
                    onWideBarLayoutChange(WideBarLayout.CORNER)
                    globalPrefsStore.wideBarLayoutName = WideBarLayout.CORNER.name
                    onSettingChanged()
                },
            ),
            haptics = haptics,
        )
    }

    Spacer(Modifier.height(20.dp))

    SheetSectionHeader("Performance")
    Spacer(Modifier.height(8.dp))
    SheetCard {
        SettingsPicker(
            title = "Web cache size",
            subtitle = "A larger cache reloads frequently-visited sites faster. Kept in Android's cache storage (not app data). Takes effect after restart.",
            selectedLabel = formatCacheSize(httpCacheSizeMb),
            options = listOf(128, 256, 512, 1024, 2048).map { mb ->
                formatCacheSize(mb) to {
                    onHttpCacheSizeMbChange(mb)
                    globalPrefsStore.httpCacheSizeMb = mb
                }
            },
            haptics = haptics,
        )
    }

    Spacer(Modifier.height(20.dp))
}

/**
 * "Set as default browser" prompt (Fenix parity). On Android 10+ this launches the system role
 * request dialog ([android.app.role.RoleManager.ROLE_BROWSER]); on older versions it opens the
 * system default-apps settings. Reflects the current default state and hides itself once Tangent is
 * already the default. State is re-checked whenever the sheet resumes (the user may have changed it
 * in system settings).
 */
@Composable
private fun DefaultBrowserCard(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isDefault by remember { mutableStateOf(isDefaultBrowser(context)) }

    val launcher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { isDefault = isDefaultBrowser(context) }

    // Re-check on resume: the user may set/unset the default from system settings and return.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isDefault = isDefaultBrowser(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (isDefault) return

    SheetCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { launchDefaultBrowserRequest(context, launcher, onMessage) }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Set Tangent as your default browser",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Open links from other apps in Tangent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Whether Tangent currently holds the browser role / is the default handler for web links. */
private fun isDefaultBrowser(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
            return rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
        }
    }
    // Fallback: resolve a generic web intent and compare the winning package to ours.
    val probe = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        "http://example.com".toUri(),
    ).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
    val resolved = context.packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName
    return resolved == context.packageName
}

private fun launchDefaultBrowserRequest(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    onMessage: (String) -> Unit,
) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
            runCatching {
                launcher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER))
            }.onSuccess { return }
        }
    }
    // Older devices (or if the role intent failed): open the system default-apps settings.
    runCatching {
        launcher.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }.onFailure {
        onMessage("Open Settings to change your default browser")
    }
}

/**
 * Status + shortcut for the OS-level autofill service (the "Fill With Other Apps" path). Shows
 * whether a device autofill service is currently active and offers a jump to the system picker so
 * the user can select their password-manager app (e.g. the official Bitwarden app). GeckoView feeds
 * the Android Autofill Framework automatically once a service is selected — no in-app credentials.
 */
@Composable
private fun SystemAutofillServiceRow(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // isEnabled() = a device autofill service is selected and usable here (any provider). Distinct
    // from hasEnabledAutofillServices(), which is true only when *we* are the service.
    fun serviceActive(): Boolean = runCatching {
        context.getSystemService(android.view.autofill.AutofillManager::class.java)
            ?.isEnabled == true
    }.getOrDefault(false)

    var active by remember { mutableStateOf(serviceActive()) }
    val launcher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { active = serviceActive() }

    // Re-check on resume: the user picks the service in system settings and returns.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) active = serviceActive()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { launchAutofillServicePicker(context, launcher, onMessage) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Autofill Service",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (active) "A device autofill service is active. Tap to change."
                else "No autofill service selected. Tap to choose your password manager.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Open the system autofill-service picker, falling back to general Settings on odd OEM builds. */
private fun launchAutofillServicePicker(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    onMessage: (String) -> Unit,
) {
    runCatching {
        launcher.launch(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE))
    }.onSuccess { return }
    runCatching {
        launcher.launch(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
    }.onFailure {
        onMessage("Open Settings → Passwords & accounts → Autofill service to choose your password manager")
    }
}

/**
 * "Delete browsing data" chooser (Fenix parity): pick which categories to wipe, then confirm.
 * History and open bookmarks/passwords are separate — this only clears browsing history, cookies/
 * site data, and the on-disk cache, leaving saved logins and bookmarks intact. Clearing is a
 * destructive one-shot, so it's gated behind this dialog rather than a bare row tap.
 */
@Composable
private fun ClearBrowsingDataDialog(
    onDismiss: () -> Unit,
    onConfirm: (history: Boolean, cookiesAndSiteData: Boolean, cache: Boolean) -> Unit,
) {
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear browsing data") },
        text = {
            Column {
                ClearDataCheckbox("Browsing history", history) { history = it }
                ClearDataCheckbox("Cookies and site data", cookies) { cookies = it }
                ClearDataCheckbox("Cached images and files", cache) { cache = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = history || cookies || cache,
                onClick = { onConfirm(history, cookies, cache) },
            ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ClearDataCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

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
    haptics: com.firebolt9907.tangentbrowser.ui.TangentHaptics? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { haptics?.select(); expanded = true }
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
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, onSelect) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    // Mark the active choice so opening the menu shows what's currently selected.
                    trailingIcon = if (label == selectedLabel) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                    onClick = {
                        haptics?.select()
                        onSelect()
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Editor for the user's own search engine. The search URL is required and must be an absolute
 * http(s) URL containing `%s`; the suggestions URL is optional (left blank, the address sheet just
 * shows history and bookmarks — the honest default, since an arbitrary endpoint's response shape
 * isn't knowable). Save stays disabled until the search template validates, so an unusable engine
 * can never become the one the address bar routes searches through.
 */
@Composable
private fun CustomSearchEngineDialog(
    existing: SearchEngine.Custom?,
    onDismiss: () -> Unit,
    onSave: (SearchEngine.Custom) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.label ?: "") }
    var searchTemplate by remember { mutableStateOf(existing?.searchTemplate ?: "") }
    var suggestTemplate by remember { mutableStateOf(existing?.suggestTemplate ?: "") }

    val searchValid = SearchEngine.isValidTemplate(searchTemplate)
    val suggestBlank = suggestTemplate.isBlank()
    val suggestValid = suggestBlank || SearchEngine.isValidTemplate(suggestTemplate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom search engine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Use %s where the search terms go — for example " +
                        "https://kagi.com/search?q=%s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = searchTemplate,
                    onValueChange = { searchTemplate = it },
                    label = { Text("Search URL") },
                    isError = searchTemplate.isNotBlank() && !searchValid,
                    supportingText = if (searchTemplate.isNotBlank() && !searchValid) {
                        { Text("Needs to be an http(s) URL containing %s") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = suggestTemplate,
                    onValueChange = { suggestTemplate = it },
                    label = { Text("Suggestions URL (optional)") },
                    isError = !suggestValid,
                    supportingText = if (!suggestValid) {
                        { Text("Needs to be an http(s) URL containing %s") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = searchValid && suggestValid,
                onClick = {
                    onSave(
                        SearchEngine.Custom(
                            label = name.trim().ifBlank { "Custom" },
                            searchTemplate = searchTemplate.trim(),
                            suggestTemplate = suggestTemplate.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** A settings row that just navigates/opens something — same shape as the other rows, no value. */
@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberTangentHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    haptics.toggle(it)
                    onCheckedChange(it)
                },
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

@Composable
private fun SettingsTextField(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    haptics: com.firebolt9907.tangentbrowser.ui.TangentHaptics? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { haptics?.select(); showDialog = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value.ifEmpty { "Not set" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics?.create()
                    onValueChange(text)
                    showDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { haptics?.softClick(); showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
