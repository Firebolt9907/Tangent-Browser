package com.firebolt9907.tangentbrowser.addons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.Action
import mozilla.components.concept.engine.webextension.ActionHandler
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionDelegate
import mozilla.components.concept.engine.webextension.WebExtensionInstallException
import mozilla.components.concept.engine.webextension.WebExtensionRuntime

/**
 * Thin wrapper over Gecko's [Engine] (which is also a [WebExtensionRuntime]). Mirrors installed
 * extensions into [state], normalizes user-typed install inputs (slug / AMO page / `.xpi` URL),
 * bridges Gecko's blocking permission prompts to Compose via [respondToPrompt], and surfaces each
 * add-on's browser/page action ("quick settings" popup) via [openPopup].
 */
class AddonController(
    private val engine: Engine,
    private val scope: CoroutineScope,
) {
    private val extensions = mutableMapOf<String, WebExtension>()
    private val promptCallbacks = mutableMapOf<Long, (Boolean) -> Unit>()
    private var nextPromptId = 1L

    /** Latest browser/page action per extension id, the source for the quick-settings popup. */
    private val actions = mutableMapOf<String, Action>()
    /**
     * Extension *instances* we've attached a global [ActionHandler] to. Keyed by identity, not id:
     * [refresh] replaces extensions with fresh instances, and the default action delegate (which
     * Gecko consults to toggle the popup) must be re-applied to each new instance or the popup
     * toggle silently goes nowhere.
     */
    private val actionHandlersRegistered: MutableSet<WebExtension> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    /** The tab session currently treated as active for web extensions (never a popup tab). */
    private var activeSession: EngineSession? = null

    var onNewTabRequested: ((url: String, engineSession: EngineSession, active: Boolean) -> Unit)? = null

    /**
     * Invoked when an add-on's action popup ("quick settings") should be shown. Firefox for Android
     * renders these popups in a regular tab, and so do we: the host opens a new tab backed by the
     * provided popup [EngineSession], which Gecko then loads the popup document into.
     */
    var onActionPopupTabRequested: ((title: String, engineSession: EngineSession) -> Unit)? = null

    private val _state = MutableStateFlow(AddonsUiState())
    val state: StateFlow<AddonsUiState> = _state.asStateFlow()

    private val delegate = object : WebExtensionDelegate {
        override fun onInstalled(extension: WebExtension) {
            remember(extension)
            setStatus("${extension.displayName()} installed")
        }

        override fun onUninstalled(extension: WebExtension) {
            extensions.remove(extension.id)
            publishInstalled()
            setStatus("${extension.displayName()} removed")
        }

        override fun onEnabled(extension: WebExtension) {
            remember(extension)
        }

        override fun onDisabled(extension: WebExtension) {
            remember(extension)
        }

        override fun onReady(extension: WebExtension) {
            remember(extension)
        }

        override fun onOptionalPermissionsChanged(extension: WebExtension) {
            remember(extension)
        }

        override fun onInstallPermissionRequest(
            extension: WebExtension,
            permissions: List<String>,
            origins: List<String>,
            dataCollectionPermissions: List<String>,
            onConfirm: (PermissionPromptResponse) -> Unit,
        ) {
            showPrompt(
                title = "Install ${extension.displayName()}?",
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions = dataCollectionPermissions,
            ) { accepted ->
                onConfirm(
                    PermissionPromptResponse(
                        isPermissionsGranted = accepted,
                        isPrivateModeGranted = false,
                        isTechnicalAndInteractionDataGranted = accepted,
                    )
                )
            }
        }

        override fun onUpdatePermissionRequest(
            extension: WebExtension,
            newPermissions: List<String>,
            newOrigins: List<String>,
            newDataCollectionPermissions: List<String>,
            onPermissionsGranted: (Boolean) -> Unit,
        ) {
            showPrompt(
                title = "Update ${extension.displayName()} permissions?",
                permissions = newPermissions,
                origins = newOrigins,
                dataCollectionPermissions = newDataCollectionPermissions,
                responder = onPermissionsGranted,
            )
        }

        override fun onOptionalPermissionsRequest(
            extension: WebExtension,
            permissions: List<String>,
            origins: List<String>,
            dataCollectionPermissions: List<String>,
            onPermissionsGranted: (Boolean) -> Unit,
        ) {
            showPrompt(
                title = "${extension.displayName()} wants more access",
                permissions = permissions,
                origins = origins,
                dataCollectionPermissions = dataCollectionPermissions,
                responder = onPermissionsGranted,
            )
        }

        override fun onInstallationFailedRequest(
            extension: WebExtension?,
            exception: WebExtensionInstallException,
        ) {
            val name = extension?.displayName()
                ?: exception.extensionName
                ?: "add-on"
            setError("Could not install $name: ${exception.message ?: exception.javaClass.simpleName}")
        }

        override fun onNewTab(
            extension: WebExtension,
            engineSession: EngineSession,
            active: Boolean,
            url: String,
        ) {
            setStatus("${extension.displayName()} requested a new tab")
            onNewTabRequested?.invoke(url, engineSession, active)
        }

        override fun onExtensionListUpdated() {
            refresh()
        }
    }

    init {
        engine.registerWebExtensionDelegate(delegate)
        refresh()
    }

    fun refresh() {
        engine.listInstalledWebExtensions(
            onSuccess = { list ->
                scope.launch {
                    extensions.clear()
                    // Drop handler bookkeeping for the now-replaced instances so the fresh ones get
                    // their default action delegate (re)attached — required for popup toggling.
                    actionHandlersRegistered.clear()
                    list.forEach {
                        extensions[it.id] = it
                        registerActionHandlerIfNeeded(it)
                    }
                    publishInstalled()
                }
            },
            onError = { throwable -> setError("Could not load add-ons: ${throwable.readableMessage()}") },
        )
    }

    fun install(input: String) {
        val url = input.toAddonInstallUrl()
        if (url == null) {
            setError("Enter an add-on slug, AMO page, or direct .xpi URL")
            return
        }
        installResolvedUrl(url)
    }

    /** Intercept AMO download/`.xpi` URLs from the in-page click bridge. */
    fun installFromPageRequest(url: String): Boolean {
        val installUrl = url.takeIf { it.isAddonDownloadUrl() }?.toAddonInstallUrl() ?: return false
        installResolvedUrl(installUrl)
        return true
    }

    private fun installResolvedUrl(url: String) {
        setBusy("Installing add-on...")
        engine.installWebExtension(
            url = url,
            installationMethod = InstallationMethod.MANAGER,
            onSuccess = { extension ->
                remember(extension)
                setStatus("${extension.displayName()} installed")
            },
            onError = { throwable ->
                setError("Install failed: ${throwable.readableMessage()}")
                refresh()
            },
        )
    }

    fun setEnabled(addonId: String, enabled: Boolean) {
        val extension = extensions[addonId] ?: return
        setBusy(if (enabled) "Enabling add-on..." else "Disabling add-on...")
        if (enabled) {
            engine.enableWebExtension(
                extension = extension,
                source = EnableSource.USER,
                onSuccess = { remember(it); setStatus("${it.displayName()} enabled") },
                onError = { setError("Enable failed: ${it.readableMessage()}") },
            )
        } else {
            engine.disableWebExtension(
                extension = extension,
                source = EnableSource.USER,
                onSuccess = { remember(it); setStatus("${it.displayName()} disabled") },
                onError = { setError("Disable failed: ${it.readableMessage()}") },
            )
        }
    }

    fun uninstall(addonId: String) {
        val extension = extensions[addonId] ?: return
        setBusy("Removing add-on...")
        engine.uninstallWebExtension(
            ext = extension,
            onSuccess = {
                extensions.remove(addonId)
                publishInstalled()
                setStatus("${extension.displayName()} removed")
            },
            onError = { _, throwable -> setError("Remove failed: ${throwable.readableMessage()}") },
        )
    }

    fun respondToPrompt(promptId: Long, accepted: Boolean) {
        val callback = promptCallbacks.remove(promptId) ?: return
        _state.update { it.copy(pendingPrompt = null) }
        callback(accepted)
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, isError = false) }
    }

    /**
     * Open the add-on's action popup ("quick settings"). Triggering the action's click is what
     * makes Gecko toggle the popup, which arrives back as [ActionHandler.onToggleActionPopup].
     */
    fun openPopup(addonId: String) {
        val action = actions[addonId]
        com.firebolt9907.tangentbrowser.util.TangentLog.debug("TangentAddon") { "openPopup $addonId action=$action enabled=${action?.enabled} activeSession=$activeSession" }
        if (action == null) {
            setError("This add-on has no quick settings")
            return
        }
        if (action.enabled == false) {
            setError("Quick settings aren't available on this page")
            return
        }
        action.onClick()
        com.firebolt9907.tangentbrowser.util.TangentLog.debug("TangentAddon") { "openPopup $addonId onClick() returned" }
    }

    private fun remember(webExtension: WebExtension) {
        scope.launch {
            extensions[webExtension.id] = webExtension
            registerActionHandlerIfNeeded(webExtension)
            publishInstalled()
        }
    }

    /**
     * Set the tab session that should be treated as the active tab for web extensions. Extensions
     * report their browser/page action *per tab* (e.g. uBlock's badge count, Chrome Mask's per-site
     * state) via a *session-scoped* action delegate — without one we never learn an add-on has a
     * popup. So we register a session handler on the active tab and mark it active. The popup, when
     * toggled from a session action, also binds to this tab, giving it the right page context.
     *
     * Pass `null` when the active tab is itself a popup tab, so the originating page stays active.
     */
    fun setActiveSession(session: EngineSession?) {
        com.firebolt9907.tangentbrowser.util.TangentLog.debug("TangentAddon") { "setActiveSession $session (was $activeSession)" }
        if (session == null) return
        if (session === activeSession) return
        // Clear the active flag on the tab we're leaving so extensions don't see two active tabs.
        (activeSession as? mozilla.components.browser.engine.gecko.GeckoEngineSession)
            ?.markActiveForWebExtensions(false)
        activeSession = session
        extensions.values.forEach { registerSessionActionHandlerIfNeeded(it, session) }
        (session as? mozilla.components.browser.engine.gecko.GeckoEngineSession)
            ?.markActiveForWebExtensions(true)
    }

    /** Attach a global [ActionHandler] once per extension (covers default, tab-independent actions). */
    private fun registerActionHandlerIfNeeded(extension: WebExtension) {
        if (!extension.supportActions) return
        if (!actionHandlersRegistered.add(extension)) return
        extension.registerActionHandler(makeActionHandler())
        // A new extension can install after a tab is already active; give it a session handler too.
        activeSession?.let { registerSessionActionHandlerIfNeeded(extension, it) }
    }

    /** Attach a per-session [ActionHandler] so we receive this extension's per-tab action updates. */
    private fun registerSessionActionHandlerIfNeeded(extension: WebExtension, session: EngineSession) {
        if (!extension.supportActions) return
        if (extension.hasActionHandler(session)) return
        extension.registerActionHandler(session, makeActionHandler())
    }

    private fun makeActionHandler(): ActionHandler = object : ActionHandler {
        override fun onBrowserAction(extension: WebExtension, session: EngineSession?, action: Action) {
            updateAction(extension.id, action)
        }

        override fun onPageAction(extension: WebExtension, session: EngineSession?, action: Action) {
            updateAction(extension.id, action)
        }

        override fun onToggleActionPopup(extension: WebExtension, action: Action): EngineSession? {
            val popupSession = engine.createSession()
            val name = extensions[extension.id]?.displayName() ?: "Add-on"
            com.firebolt9907.tangentbrowser.util.TangentLog.debug("TangentAddon") { "onToggleActionPopup ${extension.id} popupSession=$popupSession activeSession=$activeSession" }
            onActionPopupTabRequested?.invoke(name, popupSession)
            return popupSession
        }
    }

    private fun updateAction(addonId: String, action: Action) {
        scope.launch {
            // Gecko sends partial action updates; merge so a badge-only update doesn't drop the
            // title/icon/onClick captured by the initial default action.
            actions[addonId] = actions[addonId]?.copyWithOverride(action) ?: action
            publishInstalled()
        }
    }

    private fun publishInstalled() {
        _state.update { current ->
            current.copy(
                addons = extensions.values
                    .map { it.toInstalledAddon(hasQuickSettings = actions.containsKey(it.id)) }
                    .sortedBy { it.name.lowercase() },
                isBusy = false,
            )
        }
    }

    private fun showPrompt(
        title: String,
        permissions: List<String>,
        origins: List<String>,
        dataCollectionPermissions: List<String>,
        responder: (Boolean) -> Unit,
    ) {
        scope.launch {
            // Only one permission prompt at a time. If one is already showing, deny this new one
            // immediately rather than overwriting pendingPrompt — that would orphan the first
            // prompt's Gecko callback, hanging its extension operation and leaking the callback.
            if (_state.value.pendingPrompt != null) {
                responder(false)
                return@launch
            }
            val promptId = nextPromptId++
            promptCallbacks[promptId] = responder
            _state.update {
                it.copy(
                    isBusy = false,
                    pendingPrompt = AddonPermissionPrompt(
                        id = promptId,
                        title = title,
                        permissions = permissions,
                        origins = origins,
                        dataCollectionPermissions = dataCollectionPermissions,
                    ),
                )
            }
        }
    }

    private fun setBusy(message: String) {
        _state.update { it.copy(isBusy = true, message = message, isError = false) }
    }

    private fun setStatus(message: String) {
        _state.update { it.copy(isBusy = false, message = message, isError = false) }
    }

    private fun setError(message: String) {
        _state.update { it.copy(isBusy = false, message = message, isError = true) }
    }
}

data class AddonsUiState(
    val addons: List<InstalledAddon> = emptyList(),
    val pendingPrompt: AddonPermissionPrompt? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class InstalledAddon(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val isEnabled: Boolean,
    val optionsPageUrl: String?,
    val hasQuickSettings: Boolean = false,
)

data class AddonPermissionPrompt(
    val id: Long,
    val title: String,
    val permissions: List<String>,
    val origins: List<String>,
    val dataCollectionPermissions: List<String>,
)

private fun WebExtension.toInstalledAddon(hasQuickSettings: Boolean): InstalledAddon =
    InstalledAddon(
        id = id,
        name = displayName(),
        description = getMetadata()?.description.orEmpty(),
        version = getMetadata()?.version.orEmpty(),
        isEnabled = isEnabled(),
        optionsPageUrl = getMetadata()?.optionsPageUrl?.takeIf { it.isNotBlank() },
        hasQuickSettings = hasQuickSettings,
    )

private fun WebExtension.displayName(): String =
    getMetadata()?.name?.ifBlank { id } ?: id

private fun Throwable.readableMessage(): String =
    message ?: javaClass.simpleName

// AMO/.xpi install-URL helpers (toAddonInstallUrl, toAmoLatestXpiUrl, isAddonDownloadUrl) live in
// AddonInstallUrls.kt.
