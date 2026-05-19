package com.firebolt9907.luminabrowser.sync

import android.app.Application
import android.net.Uri
import com.firebolt9907.luminabrowser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mozilla.appservices.fxaclient.FxaServer
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.concept.base.crash.Breadcrumb
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.DeviceCapability
import mozilla.components.concept.sync.DeviceConfig
import mozilla.components.concept.sync.DeviceType
import mozilla.components.concept.sync.FxAEntryPoint
import mozilla.components.feature.syncedtabs.storage.SyncedTabsStorage
import mozilla.components.service.fxa.FxaAuthData
import mozilla.components.service.fxa.PeriodicSyncConfig
import mozilla.components.service.fxa.ServerConfig
import mozilla.components.service.fxa.SyncConfig
import mozilla.components.service.fxa.SyncEngine
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.service.fxa.sync.SyncReason

private const val FxAClientId = "3c49430b43dfba77"
private const val FxARedirectUrl = "https://accounts.firefox.com/oauth/success/$FxAClientId"
private const val MaxActiveTabAgeMs = 21L * 24L * 60L * 60L * 1000L

class FirefoxSyncController(
    private val application: Application,
    private val store: BrowserStore,
    private val scope: CoroutineScope,
) {
    private val crashReporter = object : CrashReporting {
        override fun submitCaughtException(throwable: Throwable): Job = Job()
        override fun recordCrashBreadcrumb(breadcrumb: Breadcrumb) = Unit
    }

    private val accountManager = FxaAccountManager(
        context = application,
        serverConfig = ServerConfig(FxaServer.Release, FxAClientId, FxARedirectUrl),
        deviceConfig = DeviceConfig(
            name = "${application.getString(R.string.app_name)} on Android",
            type = DeviceType.MOBILE,
            capabilities = setOf(DeviceCapability.SEND_TAB, DeviceCapability.CLOSE_TABS),
            secureStateAtRest = true,
        ),
        syncConfig = SyncConfig(
            supportedEngines = setOf(SyncEngine.Tabs),
            periodicSyncConfig = PeriodicSyncConfig(periodMinutes = 240, initialDelayMinutes = 5),
        ),
        crashReporter = crashReporter,
    )

    private val remoteTabsStorage = RemoteTabsStorage(application, crashReporter)
    private val syncedTabsStorage = SyncedTabsStorage(
        accountManager = accountManager,
        store = store,
        tabsStorage = remoteTabsStorage,
        maxActiveTime = MaxActiveTabAgeMs,
    )

    private val _state = MutableStateFlow(FirefoxSyncUiState())
    val state: StateFlow<FirefoxSyncUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<FirefoxSyncEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<FirefoxSyncEvent> = _events.asSharedFlow()

    @Volatile private var authInFlight = false

    init {
        syncedTabsStorage.start()
        scope.launch {
            setBusy("Preparing Firefox Sync...")
            runCatching {
                accountManager.start()
                refreshAccountState()
                if (accountManager.authenticatedAccount() != null) refreshSyncedTabs()
            }.onFailure { throwable ->
                setError("Firefox Sync is unavailable: ${throwable.readableMessage()}")
            }
        }
    }

    fun beginSignIn(openUrl: (String) -> Unit) {
        scope.launch {
            setBusy("Opening Firefox sign in...")
            val url = runCatching {
                accountManager.beginAuthentication(entrypoint = LuminaFxAEntryPoint.SyncedTabs)
            }.getOrElse { throwable ->
                setError("Could not start sign in: ${throwable.readableMessage()}")
                null
            }
            if (url == null) {
                if (_state.value.isBusy) setError("Could not start sign in")
            } else {
                _state.update { it.copy(isBusy = false, message = "Complete sign in to continue", isError = false) }
                openUrl(url)
            }
        }
    }

    fun handleOAuthRedirect(url: String): Boolean {
        if (!url.startsWith(FxARedirectUrl, ignoreCase = true)) return false
        if (authInFlight) return true
        val values = url.oauthValues()
        val code = values["code"]
        val state = values["state"]
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            setError("Firefox sign in returned without an OAuth code")
            return true
        }
        authInFlight = true
        scope.launch {
            setBusy("Completing Firefox sign in...")
            runCatching {
                accountManager.finishAuthentication(
                    FxaAuthData(
                        authType = AuthType.OtherExternal(values["action"]),
                        code = code,
                        state = state,
                    )
                )
                refreshAccountState()
                refreshSyncedTabs()
                _events.emit(FirefoxSyncEvent.AuthenticationCompleted)
            }.onFailure { throwable ->
                setError("Firefox sign in failed: ${throwable.readableMessage()}")
            }
            authInFlight = false
        }
        return true
    }

    fun refreshSyncedTabs() {
        scope.launch {
            if (accountManager.authenticatedAccount() == null) {
                _state.update { it.copy(devices = emptyList(), isBusy = false) }
                return@launch
            }
            setBusy("Syncing tabs...")
            runCatching {
                accountManager.authenticatedAccount()?.deviceConstellation()?.refreshDevices()
                accountManager.syncNow(
                    reason = SyncReason.User,
                    customEngineSubset = listOf(SyncEngine.Tabs),
                    debounce = false,
                )
                val devices = syncedTabsStorage.getSyncedDeviceTabs().map { deviceTabs ->
                    FirefoxSyncedDevice(
                        id = deviceTabs.device.id,
                        name = deviceTabs.device.displayName,
                        type = deviceTabs.device.deviceType.name.lowercase().replaceFirstChar(Char::titlecase),
                        tabs = deviceTabs.tabs.mapNotNull { tab ->
                            val active = runCatching { tab.active() }.getOrNull() ?: return@mapNotNull null
                            FirefoxSyncedTab(
                                title = active.title.ifBlank { active.url },
                                url = active.url,
                                lastUsed = tab.lastUsed,
                            )
                        },
                    )
                }.filter { it.tabs.isNotEmpty() }
                _state.update {
                    it.copy(
                        devices = devices,
                        isBusy = false,
                        message = if (devices.isEmpty()) "No tabs from other devices yet" else "Synced tabs updated",
                        isError = false,
                    )
                }
            }.onFailure { throwable ->
                setError("Could not sync tabs: ${throwable.readableMessage()}")
            }
        }
    }

    fun signOut() {
        scope.launch {
            setBusy("Signing out...")
            runCatching {
                accountManager.logout()
                _state.value = FirefoxSyncUiState(message = "Signed out")
            }.onFailure { throwable ->
                setError("Could not sign out: ${throwable.readableMessage()}")
            }
        }
    }

    private fun refreshAccountState() {
        val profile = accountManager.accountProfile()
        _state.update {
            it.copy(
                isSignedIn = accountManager.authenticatedAccount() != null,
                accountEmail = profile?.email,
                isBusy = false,
                isError = false,
            )
        }
    }

    private fun setBusy(message: String) {
        _state.update { it.copy(isBusy = true, message = message, isError = false) }
    }

    private fun setError(message: String) {
        _state.update { it.copy(isBusy = false, message = message, isError = true) }
    }
}

data class FirefoxSyncUiState(
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val devices: List<FirefoxSyncedDevice> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class FirefoxSyncedDevice(
    val id: String,
    val name: String,
    val type: String,
    val tabs: List<FirefoxSyncedTab>,
)

data class FirefoxSyncedTab(
    val title: String,
    val url: String,
    val lastUsed: Long,
)

sealed interface FirefoxSyncEvent {
    data object AuthenticationCompleted : FirefoxSyncEvent
}

private enum class LuminaFxAEntryPoint(override val entryName: String) : FxAEntryPoint {
    SyncedTabs("synced-tabs"),
}

private fun String.oauthValues(): Map<String, String> {
    val uri = Uri.parse(this)
    val queryPairs = uri.queryParameterNames.associateWith { name ->
        uri.getQueryParameter(name).orEmpty()
    }
    val fragment = uri.fragment.orEmpty()
    val fragmentPairs = fragment.split('&')
        .mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            Uri.decode(part.substring(0, index)) to Uri.decode(part.substring(index + 1))
        }
        .toMap()
    return queryPairs + fragmentPairs
}

private fun Throwable.readableMessage(): String =
    message ?: javaClass.simpleName
