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
import com.firebolt9907.tangentbrowser.state.AutofillTrustStore
import com.firebolt9907.tangentbrowser.state.SsoFlow
import com.firebolt9907.tangentbrowser.ui.content.LoginAutofillTarget
import com.firebolt9907.tangentbrowser.ui.content.LoginFieldProbe
import com.firebolt9907.tangentbrowser.ui.content.SsoAutofillHold
import com.firebolt9907.tangentbrowser.ui.content.loadJavascriptUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginsStorage

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
 * ## Identifier-first sign-ins are two offers on one page
 *
 * An enterprise IdP (Entra ID above all) asks for the email, then swaps the password field into the
 * *same document* with no navigation. [LoginFieldProbe] reports which half is showing
 * ([LoginFieldProbe.Stage]) and this composable treats the two as separate offers: the de-nag key is
 * host **and** stage, so the email step can no longer consume the host's one offer and leave the
 * password step suppressed. Picking a credential at the identifier step also submits it (that press is
 * what advances the IdP) and carries the choice to the password step, so one sign-in costs one gesture
 * rather than two. The identifier step is only acted on for [SsoFlow.isEnterpriseIdp] hosts — on an
 * ordinary site an unrequested sheet over the email box would be a nag with no SSO to justify it.
 *
 * De-nag: each host+stage is offered at most once per [HandledTtlMs]. If the user dismisses the
 * proactive sheet, tapping the field still works — Gecko's focus-driven [SelectLoginPrompt] then
 * fires as usual.
 */
/** Gap between filling the fields and pressing the form's submit button. */
private const val AutofillSubmitDelayMs = 180L

/** How long the cover is held after submit, to bridge into the resulting navigation's load. */
private const val AutofillHoldTailMs = 1_200L

/**
 * How long a hand-picked credential carries over from the identifier step of an identifier-first
 * sign-in to the password step that follows it.
 *
 * An enterprise IdP splits one sign-in across two views of the same document, and asking the user to
 * pick the same credential — and re-authenticate to a biometric prompt — a second time, seconds
 * later, in the middle of a flow they already authorised, is what makes autofill feel unreliable
 * rather than secure. This is scoped to the same tab and origin, the same credential, and one continuous
 * sign-in; it is not a relaxation of the biometric gate, it is one gesture per sign-in instead of one
 * per field.
 */
private const val StageContinuationMs = 120_000L

/**
 * How long a host+stage stays "already offered". Long enough that a re-detect or a stray focusin
 * can't re-raise the sheet, short enough that a second sign-in later in the same session is offered
 * again rather than silently falling back to tapping the field.
 */
private const val HandledTtlMs = 10 * 60_000L

/**
 * The credential the user picked for the identifier step, carried to the password step of the same
 * sign-in. See [StageContinuationMs].
 */
private data class StageContinuation(val target: LoginAutofillTarget, val guid: String, val atMs: Long)

@Composable
internal fun ProactiveLoginAutofill(
    engineSession: EngineSession?,
    selectedTabId: String?,
    loginsStorage: LoginsStorage,
    // Mirrors the "Login Autofill" setting — when off we never probe-offer (same as PromptHandler).
    enabled: Boolean,
    // Mirrors "Require biometrics" — gate selection behind BiometricGate when true.
    biometricRequired: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Compare the live selection with the captured target; never redirect a pending fill to it.
    val fillSession by rememberUpdatedState(engineSession)
    val currentTabId by rememberUpdatedState(selectedTabId)
    val autofillEnabled by rememberUpdatedState(enabled)

    // Stage + trusted target last reported by the probe, awaiting a saved-login lookup. Consumed by the
    // LaunchedEffect.
    var pending by remember { mutableStateOf<Pair<String, LoginAutofillTarget>?>(null) }
    // Logins to offer in the sheet (null = no sheet shown).
    var offer by remember { mutableStateOf<List<Login>?>(null) }
    // The stage the visible sheet was raised for, so a selection fills (and submits) the right half,
    // and the target it was raised on — the carry-over below binds to the page in front of the user,
    // never to the credential's stored origin.
    var offerStage by remember { mutableStateOf(LoginFieldProbe.Stage.Password) }
    var offerTarget by remember { mutableStateOf<LoginAutofillTarget?>(null) }
    var offerSession by remember { mutableStateOf<EngineSession?>(null) }
    // The credential (if any) that has earned the unattended-autofill offer for the sheet currently
    // being shown. Computed alongside `offer` so the sheet stays a dumb renderer.
    var alwaysAutofillCandidate by remember { mutableStateOf<Login?>(null) }
    // Host+stage pairs already offered this process — don't re-nag on every focusin / re-detect. Keyed
    // by stage as well as host because the two halves of an identifier-first sign-in live on one host:
    // a host-only key lets the email step consume the single offer and leaves the password step — the
    // one the user actually needs filled — silently suppressed.
    val handledKeys = remember { mutableMapOf<String, Long>() }
    val trustStore = remember(context) { AutofillTrustStore(context) }
    var continuation by remember { mutableStateOf<StageContinuation?>(null) }

    // Bridge listener: the probe fires on Gecko's UI thread; marshal onto the composition scope
    // (main) before touching Compose state.
    DisposableEffect(Unit) {
        LoginFieldProbe.onFieldDetected = { stage, target ->
            scope.launch { if (target.isCurrent(currentTabId)) pending = stage to target }
        }
        onDispose {
            LoginFieldProbe.onFieldDetected = null
            // Leaving with a sheet up (tab switch, surface teardown) must not latch the claim, or the
            // focus-driven picker would be suppressed for the rest of the process.
            LoginPickerCoordinator.release(LoginPickerCoordinator.OwnerProactive)
        }
    }

    LaunchedEffect(pending, enabled, engineSession, selectedTabId) {
        val (stage, target) = pending ?: return@LaunchedEffect
        // Consume only after async work finishes; clearing an effect key here cancels its lookup.
        if (!enabled || !target.isCurrent(currentTabId)) return@LaunchedEffect
        val targetSession = fillSession ?: return@LaunchedEffect
        val url = target.origin
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase()
        if (host.isNullOrBlank()) return@LaunchedEffect
        val isIdp = SsoFlow.isEnterpriseIdp(url)
        // The identifier step is only ever acted on for an enterprise IdP. On an ordinary two-step
        // site an unrequested sheet over the email box would be a new nag with no SSO to justify it —
        // there, today's behaviour (offer at the password step, nothing before it) is still right.
        if (stage == LoginFieldProbe.Stage.User && !isIdp) return@LaunchedEffect
        val key = "${target.tabId}|${target.origin}|$stage"
        val now = android.os.SystemClock.elapsedRealtime()
        // De-nag, but not forever: signing out and back in inside one app session used to fall back to
        // the tap-the-field path for the rest of the process, which reads as autofill giving up.
        if (handledKeys[key]?.let { now - it < HandledTtlMs } == true) return@LaunchedEffect
        val logins = withContext(Dispatchers.IO) {
            runCatching { loginsStorage.getByBaseDomain(host) }.getOrDefault(emptyList())
        }.filter { it.password.isNotBlank() && target.acceptsCredential(it.origin) }
        if (!target.isCurrent(currentTabId) || fillSession !== targetSession) return@LaunchedEffect
        if (logins.isEmpty()) return@LaunchedEffect

        val soleLogin = logins.singleOrNull()
        // Carry-over from the identifier step of this same sign-in: the user picked this credential
        // (and passed the biometric gate) moments ago, for this host, in a flow that is still running.
        // See StageContinuationMs.
        val carried = continuation?.takeIf {
            stage == LoginFieldProbe.Stage.Password &&
                isIdp &&
                it.target == target &&
                android.os.SystemClock.elapsedRealtime() - it.atMs <= StageContinuationMs &&
                soleLogin?.guid == it.guid
        }

        // Unattended fill. Every condition has to hold: an enterprise identity provider (NOT an
        // ordinary site's login page — see SsoFlow.isEnterpriseIdp), exactly one saved credential so
        // there is no choice to get wrong, and either an explicit prior "Always autofill" opt-in for
        // that specific credential or the carry-over above. Otherwise fall through to the normal
        // biometric-gated sheet.
        if (soleLogin != null && isIdp &&
            (carried != null || trustStore.isAlwaysAutofill(soleLogin.guid))
        ) {
            handledKeys[key] = now
            if (stage == LoginFieldProbe.Stage.Password) continuation = null
            // Own the picker for the duration of the unattended fill so Gecko's focus-driven
            // SelectLoginPrompt (which the fill itself re-triggers) doesn't pop a sheet asking for the
            // credential we just filled.
            LoginPickerCoordinator.claim(LoginPickerCoordinator.OwnerProactive)
            // Hold the "Completing sign-in" cover across the fill so the user never watches a form
            // populate itself. Released in the finally below no matter what — a latched cover would
            // be a far worse bug than a visible fill.
            SsoAutofillHold.active = true
            try {
                targetSession.loadJavascriptUrl(
                    LoginFieldProbe.buildFillScript(soleLogin.username, soleLogin.password, stage, target.origin),
                )
                withContext(Dispatchers.IO) { runCatching { loginsStorage.touch(soleLogin.guid) } }
                // Let the fill land before submitting; buildSubmitScript only acts on a form whose
                // visible field for this stage already holds a value, so it cannot submit a stray form.
                delay(AutofillSubmitDelayMs)
                if (target.isCurrent(currentTabId) && fillSession === targetSession) {
                    targetSession.loadJavascriptUrl(LoginFieldProbe.buildSubmitScript(stage, target.origin))
                }
                delay(AutofillHoldTailMs)
            } finally {
                SsoAutofillHold.active = false
                LoginPickerCoordinator.release(
                    LoginPickerCoordinator.OwnerProactive,
                    quietForMs = LoginPickerCoordinator.QuietAfterFillMs,
                )
            }
            pending = null
            return@LaunchedEffect
        }

        // Gecko's focus-driven picker may already be up (this page's password field autofocused) — in
        // that case the user has the same sheet in front of them and a second one would just stack.
        if (!LoginPickerCoordinator.claim(LoginPickerCoordinator.OwnerProactive)) return@LaunchedEffect

        // Mark handled up front: whether the user picks or dismisses, we won't re-offer this stage.
        handledKeys[key] = now
        offerStage = stage
        offerTarget = target
        offerSession = targetSession
        alwaysAutofillCandidate = soleLogin?.takeIf {
            AutofillTrustStore.offersAlwaysAutofill(
                fillCount = trustStore.fillCount(it.guid),
                alreadyAlways = trustStore.isAlwaysAutofill(it.guid),
                isEnterpriseIdp = isIdp,
                isOnlyMatch = true,
            )
        }
        offer = logins
        pending = null
    }

    val logins = offer ?: return
    val stage = offerStage
    val target = offerTarget ?: return
    val targetSession = offerSession ?: return
    // Every exit from the sheet hands the picker back. `quietForMs` differs by intent: after a fill we
    // suppress the focus-driven prompt the fill re-triggers, but a dismissal must leave it free so
    // tapping the field still summons the picker.
    fun closeSheet(filled: Boolean) {
        offer = null
        alwaysAutofillCandidate = null
        LoginPickerCoordinator.release(
            LoginPickerCoordinator.OwnerProactive,
            quietForMs = if (filled) LoginPickerCoordinator.QuietAfterFillMs else 0L,
        )
    }
    // Fill + press the stage's button, and — on the identifier step — remember the choice so the
    // password step that follows is not a second interrogation. Submitting the identifier step is what
    // advances the IdP to the password view; without it the user is left staring at a filled email box.
    fun fillAndSubmit(login: Login): Boolean {
        if (!autofillEnabled || !target.isCurrent(currentTabId) || fillSession !== targetSession ||
            !target.acceptsCredential(login.origin)
        ) {
            closeSheet(filled = false)
            return false
        }
        targetSession.loadJavascriptUrl(
            LoginFieldProbe.buildFillScript(login.username, login.password, stage, target.origin),
        )
        scope.launch {
            delay(AutofillSubmitDelayMs)
            if (autofillEnabled && target.isCurrent(currentTabId) && fillSession === targetSession) {
                targetSession.loadJavascriptUrl(LoginFieldProbe.buildSubmitScript(stage, target.origin))
            }
        }
        continuation = if (stage == LoginFieldProbe.Stage.User) {
            StageContinuation(
                target = target,
                guid = login.guid,
                atMs = android.os.SystemClock.elapsedRealtime(),
            )
        } else {
            null
        }
        return true
    }
    SelectLoginSheet(
        logins = logins,
        alwaysAutofillCandidate = alwaysAutofillCandidate,
        onAlwaysAutofill = { login ->
            // Only persist consent if this is still the target the user was shown.
            if (!fillAndSubmit(login)) return@SelectLoginSheet
            trustStore.setAlwaysAutofill(login.guid, true)
            scope.launch(Dispatchers.IO) { runCatching { loginsStorage.touch(login.guid) } }
            closeSheet(filled = true)
        },
        onSelect = { login ->
            val fill: () -> Unit = fill@{
                if (!fillAndSubmit(login)) return@fill
                // Bump usage metadata — the JS fill bypasses Gecko's onLoginUsed callback.
                scope.launch(Dispatchers.IO) { runCatching { loginsStorage.touch(login.guid) } }
                // A hand-fill: this is what earns the "Always autofill" offer later. Unattended
                // fills deliberately do NOT count, so trust can only ever be built by the user.
                trustStore.recordManualFill(login.guid)
                closeSheet(filled = true)
            }
            if (biometricRequired) {
                val displayHost = com.firebolt9907.tangentbrowser.state.compactHost(login.origin)
                BiometricGate.authenticate(
                    context = context,
                    title = "Fill password",
                    subtitle = if (displayHost.isNotBlank()) "for $displayHost" else "for this site",
                    onSuccess = fill,
                    onFailure = { closeSheet(filled = false) },
                )
            } else {
                fill()
            }
        },
        onDismiss = { closeSheet(filled = false) },
    )
}
