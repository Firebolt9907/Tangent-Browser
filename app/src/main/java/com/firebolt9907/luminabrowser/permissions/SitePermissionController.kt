package com.firebolt9907.luminabrowser.permissions

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.permission.Permission
import mozilla.components.concept.engine.permission.PermissionRequest

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
    private val prefs = context?.getSharedPreferences("lumina_site_permissions", Context.MODE_PRIVATE)

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
        val host = hostOf(request.uri)
        if (host != null && prefs != null && request.permissions.isNotEmpty()) {
            val decisions = request.permissions.map { rememberedDecision(host, it) }
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
        prefs?.edit()?.clear()?.apply()
    }

    private fun Permission.isAutoPlay(): Boolean =
        this is Permission.ContentAutoPlayAudible || this is Permission.ContentAutoPlayInaudible

    private fun hostOf(uri: String?): String? =
        uri?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun keyFor(host: String, permission: Permission): String =
        "$host|${permission::class.java.simpleName}"

    /** Remembered decision for one permission on a host: true=allow, false=deny, null=unknown. */
    private fun rememberedDecision(host: String, permission: Permission): Boolean? {
        val store = prefs ?: return null
        val key = keyFor(host, permission)
        if (!store.contains(key)) return null
        return store.getBoolean(key, false)
    }

    private fun rememberDecision(request: PermissionRequest, granted: Boolean) {
        val store = prefs ?: return
        val host = hostOf(request.uri) ?: return
        val editor = store.edit()
        request.permissions.forEach { editor.putBoolean(keyFor(host, it), granted) }
        editor.apply()
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
