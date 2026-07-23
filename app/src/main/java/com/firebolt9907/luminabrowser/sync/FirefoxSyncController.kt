package com.firebolt9907.luminabrowser.sync

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.core.net.toUri
import com.firebolt9907.luminabrowser.R
import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
import com.firebolt9907.luminabrowser.sync.zen.Sync15StorageClient
import com.firebolt9907.luminabrowser.sync.zen.ZEN_WORKSPACES_COLLECTION
import com.firebolt9907.luminabrowser.sync.zen.ZenWorkspaceRecords
import com.firebolt9907.luminabrowser.sync.zen.ZenWorkspacesSnapshot
import com.firebolt9907.luminabrowser.sync.zen.ZenWorkspacesStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mozilla.appservices.fxaclient.FxaServer
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.concept.base.crash.Breadcrumb
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.DeviceCapability
import mozilla.components.concept.sync.DeviceConfig
import mozilla.components.concept.sync.DeviceType
import mozilla.components.concept.sync.FxAEntryPoint
import mozilla.components.concept.push.PushProcessor
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.concept.sync.Profile
import mozilla.components.feature.accounts.push.FxaPushSupportFeature
import mozilla.components.feature.accounts.push.SendTabFeature
import mozilla.components.feature.push.AutoPushFeature
import mozilla.components.feature.push.PushConfig
import mozilla.components.feature.syncedtabs.storage.SyncedTabsStorage
import mozilla.components.service.fxa.FxaAuthData
import mozilla.components.service.fxa.PeriodicSyncConfig
import mozilla.components.service.fxa.ServerConfig
import mozilla.components.service.fxa.SyncConfig
import mozilla.components.service.fxa.SyncEngine
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.service.fxa.manager.SCOPE_SYNC
import mozilla.components.service.fxa.sync.GlobalSyncableStoreProvider
import mozilla.components.service.fxa.sync.SyncReason
import mozilla.components.service.fxa.sync.SyncStatusObserver
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient
import mozilla.components.support.base.observer.ObserverRegistry

private const val FxAClientId = "3c49430b43dfba77"
private const val FxARedirectUrl = "https://accounts.firefox.com/oauth/success/$FxAClientId"
private const val MaxActiveTabAgeMs = 21L * 24L * 60L * 60L * 1000L

/** A remote tab counts as a handoff candidate if it was used on the other device this recently. */
private const val HandoffWindowMs = 5L * 60L * 1000L

/**
 * Minimum spacing between the network syncs that back a *resume-triggered* handoff check. Kept
 * loose because ON_RESUME fires often and, absent a push, there's no evidence anything changed.
 */
private const val HandoffSyncDebounceMs = 60_000L

/**
 * Minimum spacing for a *push-triggered* handoff check. A push means a remote device actually
 * changed its tabs, so we sync almost immediately (iCloud-Handoff-grade latency) — the small floor
 * only collapses a burst of rapid tab switches on the other device into a single sync.
 */
private const val PushSyncDebounceMs = 5_000L

/**
 * Cadence of the in-app periodic sync while the browser is foregrounded. This is the *fallback*
 * path: when FxA push is configured (see [FirefoxSyncController.initPush]) remote tab changes
 * arrive near-instantly and trigger a sync, so this timer only has to catch anything a dropped or
 * undelivered push missed — hence a relaxed interval rather than Firefox's 600s active cadence.
 * When the app is backgrounded the loop stops and the 4-hour WorkManager [PeriodicSyncConfig] and
 * background push take over.
 */
private const val ForegroundSyncIntervalMs = 30L * 60L * 1000L

/**
 * Cadence of the foreground-only "Send tab" command poll ([FirefoxSyncController.pollIncomingCommands]).
 * Short, because it's the sole receive path for sent tabs (no working push) and users expect a sent
 * tab to land within seconds; the poll is a light request and the loop is cancelled on ON_PAUSE.
 */
private const val CommandPollIntervalMs = 25L * 1000L

class FirefoxSyncController(
    private val application: Application,
    private val store: BrowserStore,
    private val scope: CoroutineScope,
    private val loginsStorage: PlacesLoginsStorage? = null,
    private val creditCardsStorage: mozilla.components.service.sync.autofill.AutofillCreditCardsAddressesStorage? = null,
    private val historyStorage: mozilla.components.browser.storage.sync.PlacesHistoryStorage? = null,
    private val bookmarksStorage: mozilla.components.browser.storage.sync.PlacesBookmarksStorage? = null,
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
            type = if (application.isTabletFormFactor()) DeviceType.TABLET else DeviceType.MOBILE,
            capabilities = setOf(DeviceCapability.SEND_TAB, DeviceCapability.CLOSE_TABS),
            secureStateAtRest = true,
        ),
        syncConfig = SyncConfig(
            supportedEngines = setOf(
                SyncEngine.Tabs,
                SyncEngine.Passwords,
                SyncEngine.CreditCards,
                SyncEngine.History,
                SyncEngine.Bookmarks,
                SyncEngine.Addresses,
            ),
            periodicSyncConfig = PeriodicSyncConfig(periodMinutes = 240, initialDelayMinutes = 5),
        ),
        crashReporter = crashReporter,
    )

    private val globalPrefs = GlobalPreferencesStore(application)

    private val sync15StorageClient = Sync15StorageClient(HttpURLConnectionClient())

    private val remoteTabsStorage = RemoteTabsStorage(application, crashReporter)
    private val syncedTabsStorage = SyncedTabsStorage(
        accountManager = accountManager,
        store = store,
        tabsStorage = remoteTabsStorage,
        maxActiveTime = MaxActiveTabAgeMs,
    )

    private val _state = MutableStateFlow(
        // Seed with the last persisted Zen spaces so they render instantly on cold start, before
        // any network sync has run.
        FirefoxSyncUiState(zenWorkspaces = ZenWorkspacesStorage.fromJson(globalPrefs.zenWorkspacesJson)),
    )
    val state: StateFlow<FirefoxSyncUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<FirefoxSyncEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<FirefoxSyncEvent> = _events.asSharedFlow()

    /**
     * Engines this account syncs: tabs always; passwords and credit cards ride along only when their
     * respective stores were provided.
     */
    private val syncEngines: List<SyncEngine> = buildList {
        add(SyncEngine.Tabs)
        if (loginsStorage != null) add(SyncEngine.Passwords)
        if (creditCardsStorage != null) add(SyncEngine.CreditCards)
        if (historyStorage != null) add(SyncEngine.History)
        if (bookmarksStorage != null) add(SyncEngine.Bookmarks)
        if (creditCardsStorage != null) add(SyncEngine.Addresses)
    }

    @Volatile private var authInFlight = false
    /** True while a *local* [signOut] is running, so [accountObserver.onLoggedOut] can tell an
     * intentional local sign-out apart from a remote disconnect and skip the "signed out remotely"
     * event/message for the former. */
    @Volatile private var localSignOutInFlight = false
    /**
     * Latched true once we've established this device was signed out remotely (a device disconnect,
     * account deletion, or password change on another device). It's our own authoritative bit
     * because [FxaAccountManager.authenticatedAccount] keeps returning the account — and
     * [FxaAccountManager.accountNeedsReauth] can lag — after a remote sign-out, so relying on those
     * alone leaves the UI wrongly "signed in" (and spinning "Syncing tabs..." against dead
     * credentials). Cleared on a successful re-sign-in or a deliberate local sign-out.
     */
    @Volatile private var remoteSignOut = false
    @Volatile private var lastHandoffSyncAtMs = 0L
    @Volatile private var dismissedHandoffKey: String? = null
    @Volatile private var foregroundSyncJob: Job? = null
    @Volatile private var foregroundCommandJob: Job? = null
    @Volatile private var pushFeature: AutoPushFeature? = null
    /** Guards [installSendTabFeature] against double-registering the receipt observer. */
    @Volatile private var sendTabInstalled = false

    /**
     * Watches the FxA account for a sign-out that originated *elsewhere* — a remote device
     * disconnecting this one, a password change, or a revoked token. The manager reports these two
     * ways depending on how the news arrives, and both land us in a signed-out state here.
     */
    private val accountObserver = object : AccountObserver {
        override fun onLoggedOut() {
            // Fires for both a local [signOut] and a remote disconnect (push / account destroyed).
            // The localSignOutInFlight guard keeps the deliberate local case from being reported as
            // a remote sign-out (signOut already set the "Signed out" state).
            if (localSignOutInFlight) {
                stopForegroundPeriodicSync()
                return
            }
            onRemoteSignOut("Firefox Sync was signed out on another device")
        }

        override fun onAuthenticationProblems() {
            // A sync found the stored credentials no longer valid (remote disconnect / password
            // change discovered lazily). Treat it as a remote sign-out and prompt re-auth.
            onRemoteSignOut("Firefox Sync signed you out — sign in again to resume")
        }

        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
            // A fresh (or recovered) sign-in clears any prior remote-sign-out latch.
            remoteSignOut = false
        }

        override fun onProfileUpdated(profile: Profile) = Unit
        override fun onReady(authenticatedAccount: OAuthAccount?) = Unit
    }

    /**
     * Central handler for "this device was signed out somewhere else". Latches [remoteSignOut],
     * tears down the foreground sync loop, resets the UI to a signed-out shell, and emits the
     * one-shot event exactly once per detection so the host can surface it (e.g. a toast).
     */
    private fun onRemoteSignOut(message: String) {
        stopForegroundPeriodicSync()
        if (remoteSignOut) return
        remoteSignOut = true
        resetToSignedOut(message)
        _events.tryEmit(FirefoxSyncEvent.LoggedOutRemotely)
    }

    init {
        GlobalSyncableStoreProvider.configureStore(SyncEngine.Tabs to lazy { remoteTabsStorage })
        if (loginsStorage != null) {
            GlobalSyncableStoreProvider.configureStore(
                SyncEngine.Passwords to lazy { loginsStorage },
            )
        }
        if (creditCardsStorage != null) {
            // The CreditCards engine also needs a KeyProvider (the sync manager decrypts card numbers
            // for the outgoing payload); AutofillCrypto is one. Without it the sync worker throws.
            GlobalSyncableStoreProvider.configureStore(
                SyncEngine.CreditCards to lazy { creditCardsStorage },
                keyProvider = lazy { creditCardsStorage.crypto },
            )
            GlobalSyncableStoreProvider.configureStore(
                SyncEngine.Addresses to lazy { creditCardsStorage },
            )
        }
        if (historyStorage != null) {
            GlobalSyncableStoreProvider.configureStore(
                SyncEngine.History to lazy { historyStorage },
            )
        }
        if (bookmarksStorage != null) {
            GlobalSyncableStoreProvider.configureStore(
                SyncEngine.Bookmarks to lazy { bookmarksStorage },
            )
        }
        syncedTabsStorage.start()
        // Detect a remote sign-out: another device disconnecting this one, a password change, or a
        // server-side token revocation. The account manager surfaces these as onLoggedOut (when a
        // push/device event tells us outright) or onAuthenticationProblems (when a sync discovers
        // the credentials are no longer valid). Both must flip the UI back to signed-out.
        accountManager.register(accountObserver)
        // Register "Send tab" receipt before starting the account manager, so the observer is live
        // for the initial command poll that start() does for a restored account. This is the real
        // delivery path: instant FxA push is unavailable to us (Mozilla's push server bridges only
        // its own Firebase project and rejects our sender id — see initPush), so received tabs are
        // pulled by pollIncomingCommands() on our sync passes instead.
        installSendTabFeature()
        scope.launch {
            setBusy("Preparing Firefox Sync...")
            runCatching {
                accountManager.start()
                refreshAccountState()
                if (accountManager.authenticatedAccount() != null) {
                    // Catch a sign-out that happened remotely while the app was dead.
                    verifyAccount()
                    pollIncomingCommands()
                    refreshSyncedTabs()
                }
            }.onFailure { throwable ->
                setError("Firefox Sync is unavailable: ${throwable.readableMessage()}")
            }
            // Best-effort push fast-path. Inert against Mozilla's hosted server for a third-party
            // Firebase project (subscription is rejected), but harmless and isolated so it never
            // fails sync; kept so a future self-hosted autopush needs only a serverHost change.
            runCatching { initPush() }
        }
    }

    /**
     * Best-effort FxA push fast-path so a remote device's tab change re-syncs this device instantly
     * instead of waiting for a foreground poll — the mechanism vanilla Firefox uses.
     *
     * **This does not currently deliver anything with our own Firebase project.** Mozilla's hosted
     * push server (`updates.push.services.mozilla.com`) bridges only Mozilla's own FCM project; a
     * subscription for a third-party sender id is rejected (`400 "invalid app ID"`), so FxA never
     * gets a push endpoint for this device. Actual "Send tab" receipt therefore rides
     * [pollIncomingCommands] on our sync passes, not push. This scaffolding is kept — isolated and
     * failure-swallowing — so wiring a self-hosted autopush later needs only a `PushConfig`
     * `serverHost` change. No-op unless a real `google-services.json` was supplied at build time
     * (the plugin generates `gcm_defaultSenderId`, looked up by name so the no-Firebase build still
     * compiles). Idempotent.
     */
    private fun initPush() {
        if (pushFeature != null) return
        val senderId = firebaseSenderId() ?: return
        val push = AutoPushFeature(
            context = application,
            service = LuminaFirebasePushService(),
            config = PushConfig(senderId = senderId),
            crashReporter = crashReporter,
        )
        PushProcessor.install(push)
        push.initialize()

        // Binds push to the account: subscribes the FxA push scope and processes incoming account
        // events. The returned Job doesn't need awaiting.
        FxaPushSupportFeature(
            context = application,
            accountManager = accountManager,
            pushFeature = push,
            crashReporter = crashReporter,
        ).initialize()

        // Any push for this account (a remote device uploaded/changed its open tabs) → sync almost
        // immediately and re-evaluate the handoff candidate. The short PushSyncDebounceMs floor
        // (vs the 60s resume debounce) is what makes this feel like iCloud Handoff while still
        // collapsing a burst of rapid remote tab switches into one sync.
        push.register(object : AutoPushFeature.Observer {
            override fun onMessageReceived(scope: String, message: ByteArray?) {
                checkForHandoffTab(PushSyncDebounceMs)
            }
        })
        pushFeature = push
    }

    /** The FCM sender id the Google Services plugin generates, or null when Firebase isn't set up. */
    private fun firebaseSenderId(): String? {
        val resId = application.resources.getIdentifier(
            "gcm_defaultSenderId", "string", application.packageName,
        )
        if (resId == 0) return null
        return application.getString(resId).takeIf { it.isNotBlank() }
    }

    /**
     * Register receipt of "Send tab to this device" commands, unconditionally (independent of push /
     * Firebase). [SendTabFeature] observes the account for incoming
     * [mozilla.components.concept.sync.DeviceCommandIncoming] commands; those are dispatched when the
     * constellation is polled ([pollIncomingCommands]) or — if push ever works — when an FxA push
     * arrives. Each received tab raises a system notification ([ReceivedTabNotifications]); tapping
     * it opens the URL as a normal tab in [com.firebolt9907.luminabrowser.MainActivity]. Idempotent.
     */
    private fun installSendTabFeature() {
        if (sendTabInstalled) return
        sendTabInstalled = true
        SendTabFeature(accountManager) { device, tabs ->
            // Notification (works while backgrounded) for each tab...
            tabs.forEach { tab ->
                ReceivedTabNotifications.notify(application, tab.url, tab.title, device?.displayName)
            }
            // ...and an in-app banner for the newest one, so an open app shows receipt immediately
            // without the user leaving to the notification shade.
            tabs.lastOrNull()?.let { tab ->
                _state.update {
                    it.copy(
                        receivedTab = FirefoxReceivedTab(
                            deviceName = device?.displayName,
                            deviceType = device?.let { deviceTypeLabel(it.deviceType, it.displayName) }.orEmpty(),
                            title = tab.title,
                            url = tab.url,
                            receivedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    /** Clear the received-tab banner (tapped-through, dismissed, or auto-expired by the host). */
    fun dismissReceivedTab() {
        _state.update { it.copy(receivedTab = null) }
    }

    /**
     * Poll the device constellation for pending incoming commands (notably "Send tab"). This is the
     * actual receive path for sent tabs — instant FxA push is unavailable to us (see [initPush]) —
     * so it runs after every foreground sync pass. The registered [SendTabFeature] observer fires
     * for each command found. Wrapped so a poll failure never surfaces as a sync error; a no-op when
     * signed out.
     */
    private suspend fun pollIncomingCommands() {
        runCatching {
            accountManager.authenticatedAccount()?.deviceConstellation()?.pollForCommands()
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
                remoteSignOut = false
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
            if (usableAccount() == null) {
                _state.update { it.copy(devices = emptyList(), isBusy = false) }
                refreshAccountState()
                return@launch
            }
            setBusy("Syncing tabs...")
            runCatching {
                accountManager.authenticatedAccount()?.deviceConstellation()?.refreshDevices()
                refreshTargetDevices()

                awaitSync(syncEngines)
                pollIncomingCommands()

                val devices = readSyncedDevices()
                _state.update {
                    it.copy(
                        devices = devices,
                        isBusy = false,
                        message = if (devices.isEmpty()) "No tabs from other devices yet" else "Synced tabs updated",
                        isError = false,
                    )
                }
                updateHandoffFromDevices(devices)
            }.onFailure { throwable ->
                setError("Could not sync tabs: ${throwable.readableMessage()}")
            }
            refreshZenWorkspaces()
        }
    }

    /**
     * Best-effort read of Zen browser's `workspaces` Sync collection (spaces + containers).
     * Zen's engine is not in their stable channel yet, so absence of the collection (404 →
     * empty snapshot) is the common case; failures keep the previous snapshot and are never
     * surfaced as sync errors.
     */
    private suspend fun refreshZenWorkspaces() {
        val account = accountManager.authenticatedAccount() ?: return
        // Today Zen's Spaces engine writes only `space` + `container` records to the single `spaces`
        // collection (Twilight: "sync your Spaces … soon, folders, essentials, split views"). Tab
        // records aren't on the wire yet, so parsed spaces have no tabs — the parser is forward-compat
        // and the Spaces UI stays gated (ZEN_SPACES_TABS_UI_ENABLED) until they ship.
        runCatching {
            val spaceRecords = sync15StorageClient.fetchCollection(account, ZEN_WORKSPACES_COLLECTION)
            ZenWorkspaceRecords.parse(spaceRecords)
        }.onSuccess { snapshot ->
            _state.update { it.copy(zenWorkspaces = snapshot) }
            // Persist so the spaces render instantly on the next cold start, before any sync runs.
            runCatching { globalPrefs.zenWorkspacesJson = ZenWorkspacesStorage.toJson(snapshot) }
        }
    }

    /**
     * Populate the synced-tabs list from local storage without triggering a network sync.
     * Background periodic sync (every 4h) keeps that storage fresh, so this lets the UI show
     * the most recent results instantly when the sheet opens, without a 30s round trip.
     */
    fun loadCachedSyncedTabs() {
        scope.launch {
            if (usableAccount() == null) return@launch
            refreshTargetDevices()
            runCatching { readSyncedDevices() }
                .onSuccess { devices ->
                    if (devices.isNotEmpty()) {
                        _state.update { it.copy(devices = devices, isError = false) }
                    }
                    updateHandoffFromDevices(devices)
                }
            // Refresh Zen spaces directly off the `spaces` collection — a light read that doesn't
            // wait on the full tabs-engine sync — so opening the sheet shows current spaces without
            // the user forcing a manual sync first.
            refreshZenWorkspaces()
        }
    }

    /**
     * Handoff check: quietly sync the tabs engine (no busy/message churn — this runs on every app
     * resume, so it must never flash sync UI), then surface the most recently used tab from another
     * device as a handoff candidate if it was active within [HandoffWindowMs]. The network sync is
     * debounced to [minSyncSpacingMs]; inside the debounce window the candidate is still
     * re-evaluated from local storage so an expired banner clears promptly.
     *
     * [minSyncSpacingMs] is [HandoffSyncDebounceMs] for the routine resume check and the shorter
     * [PushSyncDebounceMs] when a push tells us a remote device just changed its tabs (handoff).
     */
    fun checkForHandoffTab(minSyncSpacingMs: Long = HandoffSyncDebounceMs) {
        scope.launch {
            if (usableAccount() == null) return@launch
            val now = System.currentTimeMillis()
            if (now - lastHandoffSyncAtMs >= minSyncSpacingMs) {
                lastHandoffSyncAtMs = now
                runCatching { awaitSync(listOf(SyncEngine.Tabs)) }
                pollIncomingCommands()
            }
            refreshDevicesFromStorage()
        }
    }

    /**
     * Begin (or reuse) the foreground periodic sync loop. Call from ON_RESUME. Every
     * [ForegroundSyncIntervalMs] it runs a quiet full sync (tabs + passwords + credit cards, no
     * busy/message churn) and refreshes the synced-tab list, handoff candidate, and Zen workspaces — mirroring
     * vanilla Firefox's behaviour of re-syncing on a timer while the browser is in active use.
     * Idempotent: an already-running loop is left alone. Cancelled by [stopForegroundPeriodicSync].
     */
    fun startForegroundPeriodicSync() {
        if (foregroundSyncJob?.isActive != true) {
            foregroundSyncJob = scope.launch {
                while (isActive) {
                    delay(ForegroundSyncIntervalMs)
                    if (usableAccount() == null) continue
                    lastHandoffSyncAtMs = System.currentTimeMillis()
                    runCatching { awaitSync(syncEngines) }
                    pollIncomingCommands()
                    refreshDevicesFromStorage()
                    refreshZenWorkspaces()
                }
            }
        }
        // A separate, much faster loop that ONLY polls incoming commands (notably "Send tab"). This
        // is the responsiveness path: since instant FxA push is unavailable to us (see initPush), a
        // sent tab would otherwise wait up to the 30-min full-sync tick. pollForCommands is a light
        // request, so a short cadence while foregrounded is cheap; the loop stops on ON_PAUSE.
        if (foregroundCommandJob?.isActive != true) {
            foregroundCommandJob = scope.launch {
                while (isActive) {
                    delay(CommandPollIntervalMs)
                    if (usableAccount() == null) continue
                    pollIncomingCommands()
                }
            }
        }
    }

    /** Stop the foreground sync + command-poll loops. Call from ON_PAUSE. */
    fun stopForegroundPeriodicSync() {
        foregroundSyncJob?.cancel()
        foregroundSyncJob = null
        foregroundCommandJob?.cancel()
        foregroundCommandJob = null
    }

    /** Hide the handoff banner and keep this device+URL pair from resurfacing it. */
    fun dismissHandoff() {
        _state.value.handoffTab?.let { dismissedHandoffKey = it.dismissKey }
        _state.update { it.copy(handoffTab = null) }
    }

    /** Drop [handoff] from state once its window lapses, without blocking future candidates. */
    fun expireHandoff(handoff: FirefoxHandoffTab) {
        _state.update { current ->
            if (current.handoffTab == handoff) current.copy(handoffTab = null) else current
        }
    }

    /**
     * Read local synced-device storage into UI state (quietly — no busy/message churn) and
     * re-evaluate the handoff candidate. Shared by the resume/push handoff check and the
     * foreground periodic loop.
     */
    private suspend fun refreshDevicesFromStorage() {
        runCatching { readSyncedDevices() }.onSuccess { devices ->
            if (devices.isNotEmpty()) _state.update { it.copy(devices = devices) }
            updateHandoffFromDevices(devices)
        }
    }

    private fun updateHandoffFromDevices(devices: List<FirefoxSyncedDevice>) {
        if (!globalPrefs.handoffEnabled) {
            if (_state.value.handoffTab != null) _state.update { it.copy(handoffTab = null) }
            return
        }
        val cutoff = System.currentTimeMillis() - HandoffWindowMs
        // Tabs already open locally aren't worth handing off (this also naturally retires the
        // banner right after the user accepts it). No upper bound on lastUsed: a remote clock
        // slightly ahead of ours must not disqualify an otherwise-fresh tab.
        val localUrls = store.state.tabs.mapTo(HashSet()) { it.content.url }
        val handoff = devices.asSequence()
            .flatMap { device -> device.tabs.asSequence().map { tab -> device to tab } }
            .filter { (_, tab) -> tab.lastUsed >= cutoff && tab.url !in localUrls }
            .maxByOrNull { (_, tab) -> tab.lastUsed }
            ?.let { (device, tab) ->
                FirefoxHandoffTab(
                    deviceId = device.id,
                    deviceName = device.name,
                    deviceType = device.type,
                    title = tab.title,
                    url = tab.url,
                    lastUsed = tab.lastUsed,
                )
            }
            ?.takeIf { it.dismissKey != dismissedHandoffKey }
        _state.update { it.copy(handoffTab = handoff) }
    }

    /** Read the constellation's other devices (Send Tab targets) into UI state. */
    private fun refreshTargetDevices() {
        val others = accountManager.authenticatedAccount()
            ?.deviceConstellation()?.state()?.otherDevices.orEmpty()
        val targets = others.map { device ->
            FirefoxTargetDevice(
                id = device.id,
                name = device.displayName,
                // Raw constellation type (MOBILE/DESKTOP/TABLET/VR/UNKNOWN) — the send banner shows the
                // real device icon and a question mark for UNKNOWN, rather than guessing from the name.
                type = device.deviceType.name,
                lastAccessTime = device.lastAccessTime,
            )
        }
        _state.update { it.copy(targetDevices = targets) }
    }

    /** Push the current tab to another of the account's devices via the Send Tab command. */
    fun sendTab(deviceId: String, title: String, url: String) {
        scope.launch {
            val constellation = accountManager.authenticatedAccount()?.deviceConstellation()
            if (constellation == null) {
                setError("Sign in to send tabs")
                return@launch
            }
            setBusy("Sending tab...")
            runCatching {
                constellation.sendCommandToDevice(
                    deviceId,
                    mozilla.components.concept.sync.DeviceCommandOutgoing.SendTab(
                        title.ifBlank { url },
                        url,
                    ),
                )
            }.onSuccess { sent ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        isError = sent != true,
                        message = if (sent == true) "Tab sent" else "Could not send tab",
                    )
                }
            }.onFailure { throwable ->
                setError("Could not send tab: ${throwable.readableMessage()}")
            }
        }
    }

    /**
     * Trigger a sync and suspend until it reaches idle, or 30s elapses. `syncNow` only enqueues a
     * WorkManager job and returns immediately, so we register a status observer first to avoid
     * missing the completion callback. The observer is unregistered in a `finally` so it can never
     * leak in the registry — even if `syncNow` throws or the wait times out. A timeout is benign:
     * the WorkManager job keeps running, we just stop blocking the UI on it. [engines] null means
     * "sync the account's default engine set".
     */
    private suspend fun awaitSync(engines: List<SyncEngine>?) {
        val syncDone = CompletableDeferred<Unit>()
        val syncRegistry = accountManager.syncStatusObserverRegistry
        val observer = object : SyncStatusObserver {
            override fun onStarted() {}
            override fun onIdle() {
                syncDone.complete(Unit)
            }
            override fun onError(error: Exception?) {
                syncDone.completeExceptionally(error ?: Exception("Sync error"))
            }
        }
        syncRegistry.register(observer)
        try {
            if (engines != null) {
                accountManager.syncNow(
                    reason = SyncReason.User,
                    customEngineSubset = engines,
                    debounce = false,
                )
            } else {
                accountManager.syncNow(reason = SyncReason.User, debounce = false)
            }
            withTimeout(30_000L) { syncDone.await() }
        } catch (_: TimeoutCancellationException) {
            // Sync is still running in WorkManager; we just stop waiting on it here.
        } finally {
            syncRegistry.unregister(observer)
        }
    }

    private suspend fun readSyncedDevices(): List<FirefoxSyncedDevice> =
        syncedTabsStorage.getSyncedDeviceTabs().map { deviceTabs ->
            FirefoxSyncedDevice(
                id = deviceTabs.device.id,
                name = deviceTabs.device.displayName,
                type = deviceTypeLabel(deviceTabs.device.deviceType, deviceTabs.device.displayName),
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

    val logins: PlacesLoginsStorage? get() = loginsStorage

    fun syncNow() {
        scope.launch {
            if (usableAccount() == null) return@launch
            setBusy("Syncing...")
            runCatching {
                awaitSync(null)
                _state.update { it.copy(isBusy = false, message = "Sync completed", isError = false) }
            }.onFailure { throwable ->
                setError("Sync failed: ${throwable.readableMessage()}")
            }
        }
    }

    fun signOut() {
        scope.launch {
            setBusy("Signing out...")
            localSignOutInFlight = true
            remoteSignOut = false
            runCatching {
                accountManager.logout()
                _state.value = FirefoxSyncUiState(message = "Signed out")
            }.onFailure { throwable ->
                setError("Could not sign out: ${throwable.readableMessage()}")
            }
            localSignOutInFlight = false
        }
    }

    /** Clear all account-derived state back to a signed-out shell carrying [message]. */
    private fun resetToSignedOut(message: String) {
        _state.value = FirefoxSyncUiState(message = message, isError = true)
    }

    /**
     * The account we can actually sync with, or null. An account that was signed out remotely
     * ([remoteSignOut]) or that the manager reports as needing re-auth
     * ([FxaAccountManager.accountNeedsReauth]) is deliberately treated as unusable here even though
     * [FxaAccountManager.authenticatedAccount] still returns it — otherwise every guarded operation
     * would proceed and spin "Syncing tabs..." against dead credentials.
     */
    private fun usableAccount(): OAuthAccount? {
        if (remoteSignOut) return null
        val account = accountManager.authenticatedAccount() ?: return null
        return account.takeUnless { accountManager.accountNeedsReauth() }
    }

    /**
     * Actively confirm the account is still authorized, so a *remote* sign-out is caught promptly
     * rather than whenever a background sync next happens to fail. [OAuthAccount.checkAuthorizationStatus]
     * asks the FxA server directly (bypassing the local sync-token cache that can stay valid for a
     * while after a remote disconnect); a definitive `false` means the device was signed out
     * elsewhere. `null`/errors are transient/inconclusive and ignored. No-op when already signed out
     * or already known to be in a remote-sign-out state. Cheap enough to run on resume / sheet open.
     */
    fun verifyAccount() {
        scope.launch {
            if (remoteSignOut) return@launch
            val account = accountManager.authenticatedAccount() ?: return@launch
            if (accountManager.accountNeedsReauth()) {
                onRemoteSignOut("Firefox Sync signed you out — sign in again to resume")
                return@launch
            }
            val authorized = runCatching { account.checkAuthorizationStatus(SCOPE_SYNC) }.getOrNull()
            if (authorized == false) {
                // Let the account layer rebuild its cached state, then reflect signed-out in the UI.
                runCatching { account.authErrorDetected() }
                onRemoteSignOut("Firefox Sync was signed out on another device")
            }
        }
    }

    private fun refreshAccountState() {
        val profile = accountManager.accountProfile()
        _state.update {
            it.copy(
                isSignedIn = usableAccount() != null,
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
    val targetDevices: List<FirefoxTargetDevice> = emptyList(),
    val handoffTab: FirefoxHandoffTab? = null,
    /** A tab another device just pushed here via "Send tab"; drives the in-app received-tab banner. */
    val receivedTab: FirefoxReceivedTab? = null,
    val zenWorkspaces: ZenWorkspacesSnapshot = ZenWorkspacesSnapshot.EMPTY,
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

/** A tab deliberately sent to this device via Firefox "Send tab", surfaced as a banner while open. */
data class FirefoxReceivedTab(
    val deviceName: String?,
    val deviceType: String,
    val title: String,
    val url: String,
    val receivedAt: Long,
)

/** A tab freshly active on another device, offered for continuation on this one. */
data class FirefoxHandoffTab(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val title: String,
    val url: String,
    val lastUsed: Long,
) {
    // lastUsed is deliberately excluded: dismissing a tab the other device is still actively
    // using must stick even as its timestamp keeps advancing on later syncs.
    val dismissKey: String get() = "$deviceId|$url"
}

data class FirefoxSyncedDevice(
    val id: String,
    val name: String,
    val type: String,
    val tabs: List<FirefoxSyncedTab>,
)

data class FirefoxTargetDevice(
    val id: String,
    val name: String,
    val type: String,
    /** Constellation last-access time; drives "most recently used device" selection. Null = unknown. */
    val lastAccessTime: Long? = null,
)

data class FirefoxSyncedTab(
    val title: String,
    val url: String,
    val lastUsed: Long,
)

sealed interface FirefoxSyncEvent {
    data object AuthenticationCompleted : FirefoxSyncEvent

    /** The account was signed out from another device (or its credentials were revoked). */
    data object LoggedOutRemotely : FirefoxSyncEvent
}

private enum class LuminaFxAEntryPoint(override val entryName: String) : FxAEntryPoint {
    SyncedTabs("synced-tabs"),
}

private fun String.oauthValues(): Map<String, String> {
    val uri = this.toUri()
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

/**
 * Whether this device is a tablet (vs a phone), so we self-report the right [DeviceType] to Firefox
 * Sync. Uses the smallest-width qualifier (>= 600dp = tablet), the same threshold Android's resource
 * system and Fenix use to classify tablets.
 */
private fun Context.isTabletFormFactor(): Boolean =
    resources.configuration.smallestScreenWidthDp >= 600

/** Human label for a constellation device type, e.g. MOBILE → "Mobile". */
private fun DeviceType.displayLabel(): String =
    name.lowercase().replaceFirstChar(Char::titlecase)

/**
 * Best-effort form-factor label for a synced device. Firefox self-reports [Device.deviceType]
 * correctly, but non-Firefox sync clients (Zen and other forks, third-party sync clients) commonly
 * register as [DeviceType.UNKNOWN]. Rather than show a generic phone icon / "Unknown" for those, we
 * infer the form factor from the device's display name (Firefox names devices "<Browser> on
 * <machine>", so the machine name is usually present) and fall back to Desktop — a sync client that
 * omits its type is far more likely to be a desktop browser than a phone.
 */
private fun deviceTypeLabel(deviceType: DeviceType, displayName: String): String =
    if (deviceType != DeviceType.UNKNOWN) deviceType.displayLabel()
    else inferDeviceTypeFromName(displayName)

private val TabletNameHints = listOf("ipad", "tablet", "kindle", "galaxy tab", "surface pro")
private val MobileNameHints = listOf(
    "iphone", "phone", "pixel", "galaxy", "android", "oneplus", "moto", "nexus",
    "xiaomi", "redmi", "huawei", "oppo", "vivo", "nokia",
)
private val DesktopNameHints = listOf(
    "macbook", "imac", "mac mini", "mac ", " mac", "laptop", "desktop", "windows", "linux",
    "ubuntu", "fedora", "debian", "thinkpad", "surface", "chromebook", "computer", "pc", "workstation",
)

private fun inferDeviceTypeFromName(displayName: String): String {
    val name = " ${displayName.lowercase()} "
    return when {
        TabletNameHints.any { it in name } -> "Tablet"
        MobileNameHints.any { it in name } -> "Mobile"
        DesktopNameHints.any { it in name } -> "Desktop"
        else -> "Desktop"
    }
}
