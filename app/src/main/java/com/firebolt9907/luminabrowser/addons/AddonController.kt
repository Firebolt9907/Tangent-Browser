package com.firebolt9907.luminabrowser.addons

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.EnableSource
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionDelegate
import mozilla.components.concept.engine.webextension.WebExtensionInstallException
import mozilla.components.concept.engine.webextension.WebExtensionRuntime

/**
 * Thin wrapper over Gecko's [WebExtensionRuntime]. Mirrors installed extensions into
 * [state], normalizes user-typed install inputs (slug / AMO page / `.xpi` URL), and
 * bridges Gecko's blocking permission prompts to Compose via [respondToPrompt].
 */
class AddonController(
    private val runtime: WebExtensionRuntime,
    private val scope: CoroutineScope,
) {
    private val extensions = mutableMapOf<String, WebExtension>()
    private val promptCallbacks = mutableMapOf<Long, (Boolean) -> Unit>()
    private var nextPromptId = 1L
    var onNewTabRequested: ((url: String, engineSession: EngineSession, active: Boolean) -> Unit)? = null

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
        runtime.registerWebExtensionDelegate(delegate)
        refresh()
    }

    fun refresh() {
        runtime.listInstalledWebExtensions(
            onSuccess = { list ->
                scope.launch {
                    extensions.clear()
                    list.forEach { extensions[it.id] = it }
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
        runtime.installWebExtension(
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
            runtime.enableWebExtension(
                extension = extension,
                source = EnableSource.USER,
                onSuccess = { remember(it); setStatus("${it.displayName()} enabled") },
                onError = { setError("Enable failed: ${it.readableMessage()}") },
            )
        } else {
            runtime.disableWebExtension(
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
        runtime.uninstallWebExtension(
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

    private fun remember(webExtension: WebExtension) {
        scope.launch {
            extensions[webExtension.id] = webExtension
            publishInstalled()
        }
    }

    private fun publishInstalled() {
        _state.update { current ->
            current.copy(
                addons = extensions.values
                    .map { it.toInstalledAddon() }
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
)

data class AddonPermissionPrompt(
    val id: Long,
    val title: String,
    val permissions: List<String>,
    val origins: List<String>,
    val dataCollectionPermissions: List<String>,
)

private fun WebExtension.toInstalledAddon(): InstalledAddon =
    InstalledAddon(
        id = id,
        name = displayName(),
        description = getMetadata()?.description.orEmpty(),
        version = getMetadata()?.version.orEmpty(),
        isEnabled = isEnabled(),
        optionsPageUrl = getMetadata()?.optionsPageUrl?.takeIf { it.isNotBlank() },
    )

private fun WebExtension.displayName(): String =
    getMetadata()?.name?.ifBlank { id } ?: id

private fun Throwable.readableMessage(): String =
    message ?: javaClass.simpleName

private fun String.toAddonInstallUrl(): String? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        val uri = Uri.parse(trimmed)
        val path = uri.path.orEmpty()
        if (path.endsWith(".xpi")) return trimmed
        val segments = uri.pathSegments
        val addonIndex = segments.indexOf("addon")
        val slug = segments.getOrNull(addonIndex + 1)
        if (uri.host?.endsWith("addons.mozilla.org") == true && !slug.isNullOrBlank()) {
            return slug.toAmoLatestXpiUrl()
        }
        return trimmed
    }
    return trimmed.trim('/').takeIf { it.isNotBlank() }?.toAmoLatestXpiUrl()
}

private fun String.toAmoLatestXpiUrl(): String =
    "https://addons.mozilla.org/firefox/downloads/latest/$this/latest.xpi"

private fun String.isAddonDownloadUrl(): Boolean {
    val uri = Uri.parse(this)
    val path = uri.path.orEmpty()
    return path.endsWith(".xpi", ignoreCase = true) ||
        (uri.host?.endsWith("addons.mozilla.org") == true &&
            path.contains("/firefox/downloads/latest/"))
}
