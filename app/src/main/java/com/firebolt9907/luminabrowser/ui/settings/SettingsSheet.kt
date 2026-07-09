package com.firebolt9907.luminabrowser.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.firebolt9907.luminabrowser.ui.components.SheetRowDivider
import com.firebolt9907.luminabrowser.ui.components.SheetSectionHeader
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
    var useGoogleCache by remember { mutableStateOf(globalPrefsStore.useGoogleCache) }
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
    var webFontsEnabled by remember { mutableStateOf(globalPrefsStore.webFontsEnabled) }
    var domStorageEnabled by remember { mutableStateOf(globalPrefsStore.domStorageEnabled) }
    var suspendMediaWhenInactive by remember { mutableStateOf(globalPrefsStore.suspendMediaWhenInactive) }
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

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(bottom = bottomInsetDp) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                SheetRowDivider()
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
                SettingsToggle(
                    title = "Total Cookie Protection",
                    subtitle = "Isolate cookies to block cross-site tracking",
                    checked = cookieBehaviorOptInPartitioning,
                    onCheckedChange = {
                        cookieBehaviorOptInPartitioning = it
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
                        fingerprintingProtection = it
                        globalPrefsStore.fingerprintingProtection = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Platform-independent math (fdlibm)",
                    subtitle = "Use standardized floating point calculations to prevent fingerprinting",
                    checked = fdlibmMathEnabled,
                    onCheckedChange = {
                        fdlibmMathEnabled = it
                        globalPrefsStore.fdlibmMathEnabled = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Query Parameter Stripping",
                    subtitle = "Remove tracking parameters (like fbclid, utm_*) from URLs",
                    checked = queryParameterStripping,
                    onCheckedChange = {
                        queryParameterStripping = it
                        globalPrefsStore.queryParameterStripping = it
                        onSettingChanged()
                    },
                )
                if (queryParameterStripping) {
                    SheetRowDivider()
                    SettingsTextField(
                        title = "Custom parameters to strip",
                        subtitle = "Comma-separated list of tracking parameters to strip from URLs",
                        value = queryParameterStrippingStripList,
                        onValueChange = {
                            queryParameterStrippingStripList = it
                            globalPrefsStore.queryParameterStrippingStripList = it
                            onSettingChanged()
                        }
                    )
                    SheetRowDivider()
                    SettingsTextField(
                        title = "Custom parameters to keep",
                        subtitle = "Comma-separated list of parameters to exclude from stripping",
                        value = queryParameterStrippingAllowList,
                        onValueChange = {
                            queryParameterStrippingAllowList = it
                            globalPrefsStore.queryParameterStrippingAllowList = it
                            onSettingChanged()
                        }
                    )
                }
                SheetRowDivider()
                SettingsPicker(
                    title = "Cookie banner auto-dismiss",
                    subtitle = "Automatically decline or ignore cookie consent banners",
                    selectedLabel = when (cookieBannerHandlingMode) {
                        1 -> "Reject all"
                        2 -> "Reject or accept all"
                        else -> "Disabled"
                    },
                    options = listOf(
                        "Disabled" to {
                            cookieBannerHandlingMode = 0
                            globalPrefsStore.cookieBannerHandlingMode = 0
                            onSettingChanged()
                        },
                        "Reject all" to {
                            cookieBannerHandlingMode = 1
                            globalPrefsStore.cookieBannerHandlingMode = 1
                            onSettingChanged()
                        },
                        "Reject or accept all" to {
                            cookieBannerHandlingMode = 2
                            globalPrefsStore.cookieBannerHandlingMode = 2
                            onSettingChanged()
                        }
                    )
                )
                SheetRowDivider()
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
                            dohSettingsMode = 0
                            globalPrefsStore.dohSettingsMode = 0
                            onSettingChanged()
                        },
                        "Default" to {
                            dohSettingsMode = 1
                            globalPrefsStore.dohSettingsMode = 1
                            onSettingChanged()
                        },
                        "Increased" to {
                            dohSettingsMode = 2
                            globalPrefsStore.dohSettingsMode = 2
                            onSettingChanged()
                        },
                        "Max" to {
                            dohSettingsMode = 3
                            globalPrefsStore.dohSettingsMode = 3
                            onSettingChanged()
                        }
                    )
                )
                if (dohSettingsMode > 0) {
                    SheetRowDivider()
                    SettingsTextField(
                        title = "Custom DoH URL",
                        subtitle = "Optional custom DNS over HTTPS provider URL",
                        value = dohProviderUrl,
                        onValueChange = {
                            dohProviderUrl = it
                            globalPrefsStore.dohProviderUrl = it
                            onSettingChanged()
                        }
                    )
                }
                SheetRowDivider()
                SettingsToggle(
                    title = "Trust enterprise roots",
                    subtitle = "Trust system-installed CA certificates (useful for VPNs/proxies)",
                    checked = enterpriseRootsEnabled,
                    onCheckedChange = {
                        enterpriseRootsEnabled = it
                        globalPrefsStore.enterpriseRootsEnabled = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "DOM Storage",
                    subtitle = "Enable LocalStorage and SessionStorage on web pages",
                    checked = domStorageEnabled,
                    onCheckedChange = {
                        domStorageEnabled = it
                        globalPrefsStore.domStorageEnabled = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Local file access",
                    subtitle = "Allow websites to request access to local files (file:///)",
                    checked = allowFileAccess,
                    onCheckedChange = {
                        allowFileAccess = it
                        globalPrefsStore.allowFileAccess = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Allow file URL cross-access",
                    subtitle = "Allow local file:/// pages to access other local files",
                    checked = allowFileAccessFromFileURLs,
                    onCheckedChange = {
                        allowFileAccessFromFileURLs = it
                        globalPrefsStore.allowFileAccessFromFileURLs = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Post-quantum cryptography",
                    subtitle = "Secure HTTPS connections against future quantum attacks",
                    checked = postQuantumKeyExchangeEnabled,
                    onCheckedChange = {
                        postQuantumKeyExchangeEnabled = it
                        globalPrefsStore.postQuantumKeyExchangeEnabled = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Safe Browsing V5",
                    subtitle = "Real-time enhanced protection against malware and phishing",
                    checked = safeBrowsingV5Enabled,
                    onCheckedChange = {
                        safeBrowsingV5Enabled = it
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
                        emailTrackerBlockingPrivateBrowsing = it
                        globalPrefsStore.emailTrackerBlockingPrivateBrowsing = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Dismiss subframe cookie banners",
                    subtitle = "Automatically dismiss cookie banners inside sub-frames/iframes",
                    checked = cookieBannerHandlingGlobalRulesSubFrames,
                    onCheckedChange = {
                        cookieBannerHandlingGlobalRulesSubFrames = it
                        globalPrefsStore.cookieBannerHandlingGlobalRulesSubFrames = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Local Network Access protection",
                    subtitle = "Block public websites from probing/scanning your local network",
                    checked = lnaBlockingEnabled,
                    onCheckedChange = {
                        lnaBlockingEnabled = it
                        globalPrefsStore.lnaBlockingEnabled = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsTextField(
                    title = "Banned ports override",
                    subtitle = "Comma-separated list of ports to override/block",
                    value = bannedPorts,
                    onValueChange = {
                        bannedPorts = it
                        globalPrefsStore.bannedPorts = it
                        onSettingChanged()
                    }
                )
                SheetRowDivider()
                SettingsTextField(
                    title = "CRLite Channel",
                    subtitle = "Certificate revocation list update channel",
                    value = crliteChannel,
                    onValueChange = {
                        crliteChannel = it
                        globalPrefsStore.crliteChannel = it
                        onSettingChanged()
                    }
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
                    title = "JavaScript",
                    subtitle = "Enable JavaScript on web pages",
                    checked = javaScriptEnabled,
                    onCheckedChange = {
                        javaScriptEnabled = it
                        globalPrefsStore.javaScriptEnabled = it
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
                    subtitle = "Automatically fill saved usernames and passwords",
                    checked = loginAutofillEnabled,
                    onCheckedChange = {
                        loginAutofillEnabled = it
                        globalPrefsStore.loginAutofillEnabled = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Web Fonts",
                    subtitle = "Allow web pages to load custom fonts",
                    checked = webFontsEnabled,
                    onCheckedChange = {
                        webFontsEnabled = it
                        globalPrefsStore.webFontsEnabled = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Suspend media when inactive",
                    subtitle = "Pause background audio/video playback when a tab is inactive",
                    checked = suspendMediaWhenInactive,
                    onCheckedChange = {
                        suspendMediaWhenInactive = it
                        globalPrefsStore.suspendMediaWhenInactive = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Show scrollbars",
                    subtitle = "Render scrollbars when scrolling web content",
                    checked = scrollBarsEnabled,
                    onCheckedChange = {
                        scrollBarsEnabled = it
                        globalPrefsStore.scrollBarsEnabled = it
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
                        forceUserScalableContent = it
                        globalPrefsStore.forceUserScalableContent = it
                        onSettingChanged()
                    },
                )
                SheetRowDivider()
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
                    )
                )
                SheetRowDivider()
                SettingsToggle(
                    title = "Sync with system font size",
                    subtitle = "Automatically adjust text scale based on Android settings",
                    checked = automaticFontSizeAdjustment,
                    onCheckedChange = {
                        automaticFontSizeAdjustment = it
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
                        automaticLanguageAdjustment = it
                        globalPrefsStore.automaticLanguageAdjustment = it
                        onSettingChanged()
                    },
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
                SheetRowDivider()
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
                SheetRowDivider()
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
                )
            }

            Spacer(Modifier.height(20.dp))

            SheetSectionHeader("Reader Mode")
            Spacer(Modifier.height(8.dp))
            SheetCard {
                SettingsToggle(
                    title = "Bypass via Google Cache",
                    subtitle = "Load articles from Google's search scraper cache for faster speeds and tracker removal",
                    checked = useGoogleCache,
                    onCheckedChange = {
                        useGoogleCache = it
                        globalPrefsStore.useGoogleCache = it
                        com.firebolt9907.luminabrowser.ui.reader.ReaderManager.instance.setUseGoogleCache(it)
                        onSettingChanged()
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
                modifier = Modifier.size(14.dp),
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

@Composable
private fun SettingsTextField(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
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
                    onValueChange(text)
                    showDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
