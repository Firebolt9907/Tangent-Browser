package com.firebolt9907.tangentbrowser.permissions

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.permission.Permission
import mozilla.components.concept.engine.permission.PermissionRequest

/**
 * The persistence key for a remembered site-permission decision: the request's **full origin**
 * (`scheme://host[:port]`), lower-cased, or null if [uri] has no usable scheme+host. Scoping by
 * origin — not by bare host — is a security boundary: a camera/microphone/geolocation grant the user
 * gave to `https://example.com` must NOT be silently reused for `http://example.com`, otherwise an
 * active-network attacker who downgrades or MITMs the http origin would inherit that grant with no
 * prompt. Parsed with java.net.URI so it is a real authority parse and JVM-unit-testable.
 */
internal fun sitePermissionOriginKey(uri: String?): String? {
    val parsed = uri?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return null
    val scheme = parsed.scheme?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val host = parsed.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val port = parsed.port
    return if (port >= 0) "$scheme://$host:$port" else "$scheme://$host"
}

/**
 * A user-facing site permission that can be reviewed / changed after the fact from the aA sheet.
 *
 * Each maps to one or more concrete `Permission.Content*` classes (by [Permission] `simpleName`) — the
 * same class names [SitePermissionController] keys remembered decisions under. Some user-facing
 * permissions cover more than one Gecko permission (e.g. "Camera" = both `ContentVideoCamera` and
 * `ContentVideoCapture`), so setting a decision writes every underlying key and reading treats them as
 * one. Autoplay is intentionally absent — it's resolved by a fixed global policy in
 * [SitePermissionController.onContentPermissionRequest] before remembered decisions are consulted, so a
 * stored autoplay decision would never be honoured.
 */
enum class ManagedPermission(
    val label: String,
    val description: String,
    val classNames: List<String>,
) {
    Location("Location", "See this site's requests for your device location.", listOf("ContentGeoLocation")),
    Camera("Camera", "Let this site access your camera.", listOf("ContentVideoCamera", "ContentVideoCapture")),
    Microphone("Microphone", "Let this site access your microphone.", listOf("ContentAudioCapture", "ContentAudioMicrophone")),
    Notifications("Notifications", "Let this site send you notifications.", listOf("ContentNotification")),
    PersistentStorage("Persistent storage", "Let this site store data that isn't auto-cleared.", listOf("ContentPersistentStorage")),
    DrmMedia("DRM media", "Let this site play DRM-protected media.", listOf("ContentMediaKeySystemAccess", "ContentProtectedMediaId")),
    LocalNetwork("Local network", "Let this site reach devices on your local network.", listOf("ContentLocalNetworkAccess")),
    LocalDevice("Connected device", "Let this site talk to a device connected to your phone.", listOf("ContentLocalDeviceAccess")),
}

/**
 * Bridges GeckoView permission requests to the Compose UI.
 *
 * There are two kinds of request:
 *  - **Content** requests (a site wants geolocation, camera, microphone, notifications…). These are
 *    surfaced as [contentRequest] so the UI can show an allow/block popup, then resolved via
 *    [grantCurrent] / [rejectCurrent].
 *  - **App** requests (Gecko needs the matching Android runtime permission before it can satisfy a
 *    content request). These are routed straight to the host Activity through [requestAppPermissions],
 *    which launches the OS permission dialog and reports back.
 *
 * All observer callbacks arrive on the main thread, so no extra synchronization is needed.
 */
class SitePermissionController(context: Context? = null) {

    // Persists the user's per-host content-permission decisions so a site that was allowed/denied
    // once is not re-prompted on every page load (e.g. nytimes.com spamming "allow autoplay").
    private val prefs = context?.getSharedPreferences("tangent_site_permissions", Context.MODE_PRIVATE)

    private val _contentRequest = MutableStateFlow<PermissionRequest?>(null)
    val contentRequest: StateFlow<PermissionRequest?> = _contentRequest.asStateFlow()

    /**
     * Set by the host Activity. Given a list of Android permission strings, it must launch the OS
     * runtime-permission flow and invoke the callback with whether they were all granted.
     */
    var requestAppPermissions: ((permissions: List<String>, onResult: (granted: Boolean) -> Unit) -> Unit)? = null

    fun onContentPermissionRequest(request: PermissionRequest) {
        // Autoplay default policy (applied to every site, no prompt and not remembered):
        //   • autoplay WITH audio  → always reject
        //   • autoplay WITHOUT audio → always allow
        // Sites spam these on every load even when there's no media, so prompting is pure noise.
        if (request.permissions.isNotEmpty() && request.permissions.all { it.isAutoPlay() }) {
            if (request.permissions.any { it is Permission.ContentAutoPlayAudible }) {
                request.reject()
            } else {
                request.grant(request.permissions)
            }
            return
        }

        // Apply a remembered decision for this host without prompting, if one covers the request.
        // All requested permissions remembered as "allow" → grant; any remembered "deny" → reject.
        val origin = sitePermissionOriginKey(request.uri)
        if (origin != null && prefs != null && request.permissions.isNotEmpty()) {
            val decisions = request.permissions.map { rememberedDecision(origin, it) }
            when {
                decisions.any { it == false } -> {
                    request.reject()
                    return
                }
                decisions.all { it == true } -> {
                    request.grant(request.permissions)
                    return
                }
                // otherwise at least one is unknown → fall through and prompt
            }
        }

        // Only one popup at a time. Reject (rather than silently drop) a second concurrent request
        // so the page isn't left waiting on a prompt that will never appear.
        if (_contentRequest.value != null) {
            request.reject()
            return
        }
        _contentRequest.value = request
    }

    fun onCancelContentPermissionRequest(request: PermissionRequest) {
        if (_contentRequest.value?.id == request.id) {
            _contentRequest.value = null
        }
    }

    fun grantCurrent() {
        val request = _contentRequest.value ?: return
        _contentRequest.value = null
        rememberDecision(request, granted = true)
        request.grant(request.permissions)
    }

    fun rejectCurrent() {
        val request = _contentRequest.value ?: return
        _contentRequest.value = null
        rememberDecision(request, granted = false)
        request.reject()
    }

    /** Forget every remembered allow/deny decision so sites will prompt again. */
    fun clearRememberedDecisions() {
        prefs?.edit { clear() }
    }

    /**
     * The remembered decision for [permission] on the site whose URL is [uri]: `true` = allow, `false`
     * = block, `null` = ask (no stored decision). All of the permission's underlying class keys are
     * written together by [setManagedDecision], so reading the first that has a value is sufficient.
     * Returns `null` when [uri] has no usable origin or nothing is stored.
     */
    fun managedDecisionFor(uri: String?, permission: ManagedPermission): Boolean? {
        val store = prefs ?: return null
        val origin = sitePermissionOriginKey(uri) ?: return null
        for (className in permission.classNames) {
            val key = keyFor(origin, className)
            if (store.contains(key)) return store.getBoolean(key, false)
        }
        return null
    }

    /**
     * Set (or with [decision] = `null`, clear back to "ask") the remembered decision for [permission]
     * on [uri]. Writes/removes every underlying Gecko permission key so the change is honoured no
     * matter which concrete `Content*` variant the site later requests. No-op if [uri] has no origin.
     */
    fun setManagedDecision(uri: String?, permission: ManagedPermission, decision: Boolean?) {
        val store = prefs ?: return
        val origin = sitePermissionOriginKey(uri) ?: return
        store.edit {
            permission.classNames.forEach { className ->
                val key = keyFor(origin, className)
                if (decision == null) remove(key) else putBoolean(key, decision)
            }
        }
    }

    private fun Permission.isAutoPlay(): Boolean =
        this is Permission.ContentAutoPlayAudible || this is Permission.ContentAutoPlayInaudible

    private fun keyFor(origin: String, permission: Permission): String =
        keyFor(origin, permission::class.java.simpleName)

    private fun keyFor(origin: String, permissionClassName: String): String =
        "$origin|$permissionClassName"

    /** Remembered decision for one permission on an origin: true=allow, false=deny, null=unknown. */
    private fun rememberedDecision(origin: String, permission: Permission): Boolean? {
        val store = prefs ?: return null
        val key = keyFor(origin, permission)
        if (!store.contains(key)) return null
        return store.getBoolean(key, false)
    }

    private fun rememberDecision(request: PermissionRequest, granted: Boolean) {
        val store = prefs ?: return
        val origin = sitePermissionOriginKey(request.uri) ?: return
        store.edit {
            request.permissions.forEach { putBoolean(keyFor(origin, it), granted) }
        }
    }

    fun onAppPermissionRequest(request: PermissionRequest) {
        // For app requests each Permission's id IS the Android permission string.
        val androidPermissions = request.permissions.mapNotNull { it.id }.filter { it.isNotBlank() }
        val bridge = requestAppPermissions
        if (androidPermissions.isEmpty() || bridge == null) {
            request.reject()
            return
        }
        bridge(androidPermissions) { granted ->
            if (granted) request.grant() else request.reject()
        }
    }
}
