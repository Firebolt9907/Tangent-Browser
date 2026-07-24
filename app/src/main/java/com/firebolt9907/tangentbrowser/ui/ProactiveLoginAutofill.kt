package com.firebolt9907.tangentbrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.firebolt9907.tangentbrowser.sync.PlacesLoginsStorage
import com.firebolt9907.tangentbrowser.ui.content.LoginFieldProbe
import com.firebolt9907.tangentbrowser.ui.content.loadJavascriptUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.storage.Login

/**
 * Proactive login autofill: shows the saved-password picker **before** the user taps a login field,
 * so when a credential is already saved the soft keyboard never has to come up.
 *
 * Gecko's own login autofill ([PromptRequest.SelectLoginPrompt], handled in [PromptHandler]) only
 * surfaces once a login field is *focused*, and focusing a web input raises the keyboard. This closes
 * that gap by pairing [LoginFieldProbe] (a content script that reports a visible, empty password field
 * over the shared `tangent-*` prompt bridge) with a saved-login lookup: on detection we query
 * [PlacesLoginsStorage.getByBaseDomain] for the page's host and, if any credentials exist, show the
 * same [SelectLoginSheet] the focus-driven path uses. Selection is biometric-gated (per the same
 * setting) and fills via JS ([LoginFieldProbe.buildFillScript]) since we bypass Gecko's focus prompt.
 *
 * De-nag: each host is offered at most once (until the app process restarts). If the user dismisses
 * the proactive sheet, tapping the field still works — Gecko's focus-driven [SelectLoginPrompt] then
 * fires as usual.
 */
@Composable
internal fun ProactiveLoginAutofill(
    engineSession: EngineSession?,
    loginsStorage: PlacesLoginsStorage,
    // Mirrors the "Login Autofill" setting — when off we never probe-offer (same as PromptHandler).
    enabled: Boolean,
    // Mirrors "Require biometrics" — gate selection behind BiometricGate when true.
    biometricRequired: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // rememberUpdatedState so the JS fill always targets the live session, not a stale capture from
    // when the probe listener was first attached.
    val fillSession by rememberUpdatedState(engineSession)

    // URL last reported by the probe, awaiting a saved-login lookup. Consumed by the LaunchedEffect.
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    // Logins to offer in the sheet (null = no sheet shown).
    var offer by remember { mutableStateOf<List<Login>?>(null) }
    // Hosts already offered this process — don't re-nag on every focusin / re-detect.
    val handledHosts = remember { mutableSetOf<String>() }

    // Bridge listener: the probe fires on Gecko's UI thread; marshal onto the composition scope
    // (main) before touching Compose state.
    DisposableEffect(Unit) {
        LoginFieldProbe.onFieldDetected = { url ->
            scope.launch { pendingUrl = url }
        }
        onDispose { LoginFieldProbe.onFieldDetected = null }
    }

    LaunchedEffect(pendingUrl, enabled) {
        val url = pendingUrl ?: return@LaunchedEffect
        pendingUrl = null
        if (!enabled) return@LaunchedEffect
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase()
        if (host.isNullOrBlank() || host in handledHosts) return@LaunchedEffect
        val logins = withContext(Dispatchers.IO) {
            runCatching { loginsStorage.getByBaseDomain(host) }.getOrDefault(emptyList())
        }.filter { it.password.isNotBlank() }
        if (logins.isEmpty()) return@LaunchedEffect
        // Mark handled up front: whether the user picks or dismisses, we won't re-offer this host.
        handledHosts.add(host)
        offer = logins
    }

    val logins = offer ?: return
    SelectLoginSheet(
        logins = logins,
        onSelect = { login ->
            val fill: () -> Unit = {
                fillSession?.loadJavascriptUrl(
                    LoginFieldProbe.buildFillScript(login.username, login.password),
                )
                // Bump usage metadata — the JS fill bypasses Gecko's onLoginUsed callback.
                scope.launch(Dispatchers.IO) { runCatching { loginsStorage.touch(login.guid) } }
                offer = null
            }
            if (biometricRequired) {
                val displayHost = com.firebolt9907.tangentbrowser.state.compactHost(login.origin)
                BiometricGate.authenticate(
                    context = context,
                    title = "Fill password",
                    subtitle = if (displayHost.isNotBlank()) "for $displayHost" else "for this site",
                    onSuccess = fill,
                    onFailure = { offer = null },
                )
            } else {
                fill()
            }
        },
        onDismiss = { offer = null },
    )
}
