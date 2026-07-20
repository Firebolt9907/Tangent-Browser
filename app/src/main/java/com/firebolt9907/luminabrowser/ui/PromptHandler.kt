package com.firebolt9907.luminabrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.firebolt9907.luminabrowser.ui.content.loadJavascriptUrl
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.prompt.PromptRequest

@Composable
fun PromptHandler(
    engineSession: EngineSession?,
    // Mirrors the user's "Login Autofill" setting. Gecko's silent page-load autofill is forced off
    // (see Engine.kt), so this toggle now gates whether the biometric-gated login picker is offered
    // at all: when false we dismiss SelectLoginPrompt without showing any fill UI.
    loginAutofillEnabled: Boolean = true,
    // Mirrors the user's "Require biometrics" setting. When true, selecting a saved login must pass
    // BiometricGate before it fills; when false the credential fills immediately on selection.
    biometricAutofillRequired: Boolean = true,
    // Mirrors the user's "Payment method autofill" setting. When false we dismiss SelectCreditCard
    // without showing the picker (Gecko's page-load CC autofill is also gated on the same pref in the
    // storage delegate — see Engine.kt).
    creditCardAutofillEnabled: Boolean = true,
    // Mirrors the user's "Address autofill" setting. When false we dismiss SelectAddress without
    // showing the picker (also gated in the storage delegate — see Engine.kt).
    addressAutofillEnabled: Boolean = true,
) {
    var promptRequest by remember { mutableStateOf<PromptRequest?>(null) }
    val context = LocalContext.current

    // File-upload (<input type=file>) plumbing. The active File request is held here so the
    // activity-result callbacks can route the chosen document(s) back to Gecko (or dismiss on
    // cancel). Uses OpenDocument, which grants a persistable read grant on the returned Uri.
    var fileRequest by remember { mutableStateOf<PromptRequest.File?>(null) }
    fun clearFileRequest() { fileRequest = null; promptRequest = null }
    val pickSingleFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val req = fileRequest
        clearFileRequest()
        if (req != null) {
            if (uri != null) req.onSingleFileSelected(context, uri) else req.onDismiss()
        }
    }
    val pickMultipleFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val req = fileRequest
        clearFileRequest()
        if (req != null) {
            if (uris.isNotEmpty()) req.onMultipleFilesSelected(context, uris.toTypedArray()) else req.onDismiss()
        }
    }

    DisposableEffect(engineSession) {
        if (engineSession == null) return@DisposableEffect onDispose {}

        val observer = object : EngineSession.Observer {
            override fun onPromptRequest(request: PromptRequest) {
                promptRequest = request
            }

            override fun onPromptDismissed(request: PromptRequest) {
                if (promptRequest == request) {
                    promptRequest = null
                }
            }
        }
        engineSession.register(observer)

        onDispose {
            engineSession.unregister(observer)
            promptRequest = null
        }
    }

    when (val request = promptRequest) {
        is PromptRequest.IdentityCredential.SelectProvider -> {
            IdentityCredentialListDialog(
                title = "Select credential provider",
                items = request.providers.map { it.name },
                onConfirm = { index -> request.onConfirm(request.providers[index]); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.IdentityCredential.SelectAccount -> {
            IdentityCredentialListDialog(
                title = "Select passkey",
                items = request.accounts.map {
                    listOfNotNull(it.name, it.email).filter { it.isNotBlank() }.joinToString(" — ")
                },
                onConfirm = { index -> request.onConfirm(request.accounts[index]); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.IdentityCredential.PrivacyPolicy -> {
            IdentityCredentialPrivacyPolicyDialog(
                providerDomain = request.providerDomain,
                onConfirm = { accepted -> request.onConfirm(accepted); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.Alert -> {
            JsAlertDialog(
                title = request.title,
                message = request.message,
                onConfirm = { request.onConfirm(false); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.Confirm -> {
            JsConfirmDialog(
                title = request.title,
                message = request.message,
                positiveLabel = request.positiveButtonTitle.ifBlank { "OK" },
                negativeLabel = request.negativeButtonTitle.ifBlank { "Cancel" },
                onPositive = { request.onConfirmPositiveButton(false); promptRequest = null },
                onNegative = { request.onConfirmNegativeButton(false); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.BeforeUnload -> {
            BeforeUnloadDialog(
                onLeave = { request.onLeave(); promptRequest = null },
                onStay = { request.onStay(); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.TextPrompt -> {
            JsTextPromptDialog(
                title = request.title,
                label = request.inputLabel,
                initialValue = request.inputValue,
                onConfirm = { value -> request.onConfirm(false, value); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.SingleChoice -> {
            SingleChoiceDialog(
                choices = request.choices.toList(),
                onConfirm = { choice -> request.onConfirm(choice); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.MultipleChoice -> {
            MultipleChoiceDialog(
                choices = request.choices.toList(),
                onConfirm = { choices -> request.onConfirm(choices); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.MenuChoice -> {
            MenuChoiceDialog(
                choices = request.choices.toList(),
                onConfirm = { choice -> request.onConfirm(choice); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.Authentication -> {
            AuthenticationDialog(
                title = request.title,
                message = request.message,
                initialUsername = request.userName,
                initialPassword = request.password,
                onlyPassword = request.onlyShowPassword,
                previousFailed = request.previousFailed,
                onConfirm = { user, pass -> request.onConfirm(user, pass); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.File -> {
            // Launch the system document picker once per request; the result callbacks above route
            // the selection back to Gecko. Only MIME-type entries (containing "/") are valid picker
            // filters — HTML `accept` can also list extensions, which we drop in favor of "*/*".
            LaunchedEffect(request) {
                fileRequest = request
                val types = request.mimeTypes
                    .filter { it.contains("/") }
                    .toTypedArray()
                    .ifEmpty { arrayOf("*/*") }
                if (request.isMultipleFilesSelection) {
                    pickMultipleFiles.launch(types)
                } else {
                    pickSingleFile.launch(types)
                }
            }
        }
        is PromptRequest.Repost -> {
            RepostDialog(
                onConfirm = { request.onConfirm(); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.TimeSelection -> {
            // Native date/time picker for <input type=date|time|datetime-local|month>. Fired once
            // per request; the helper always calls back to clear the pending prompt.
            LaunchedEffect(request) {
                showTimeSelectionPrompt(context, request) { promptRequest = null }
            }
        }
        is PromptRequest.Color -> {
            // <input type="color"> chooser.
            ColorPickerDialog(
                initialColor = request.defaultColor,
                onConfirm = { hex -> request.onConfirm(hex); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.Share -> {
            // Web Share API (navigator.share). Launch the system share sheet, then resolve or reject
            // the page's promise based on whether a share target could be launched.
            LaunchedEffect(request) {
                val launched = shareWebData(context, request.data.title, request.data.text, request.data.url)
                if (launched) request.onSuccess() else request.onFailure()
                promptRequest = null
            }
        }
        is PromptRequest.SelectLoginPrompt -> {
            // Gecko raises this when a login field is focused and our LoginStorageDelegate
            // (onLoginFetch) returned stored credentials for the domain. Without rendering it there is
            // no fill UI at all — this is the doorhanger that actually triggers password autofill.
            // Selecting an entry is gated behind BiometricGate; on success we fill (onConfirm) and
            // then inject the auto-submit script to press the form's login button.
            if (!loginAutofillEnabled) {
                // User disabled login autofill — offer no fill UI and let the field stay empty.
                LaunchedEffect(request) { request.onDismiss(); promptRequest = null }
                return
            }
            SelectLoginSheet(
                logins = request.logins,
                onSelect = { login ->
                    // Shared fill action: confirm the credential to Gecko, then inject the
                    // auto-submit script (it polls briefly for the filled password field before
                    // pressing submit).
                    val fill: () -> Unit = {
                        request.onConfirm(login)
                        promptRequest = null
                        engineSession?.loadJavascriptUrl(buildLoginSubmitScript())
                        Unit
                    }
                    if (biometricAutofillRequired) {
                        val host = com.firebolt9907.luminabrowser.state.compactHost(login.origin)
                        BiometricGate.authenticate(
                            context = context,
                            title = "Fill password",
                            subtitle = if (host.isNotBlank()) "for $host" else "for this site",
                            onSuccess = fill,
                            onFailure = { request.onDismiss(); promptRequest = null },
                        )
                    } else {
                        // User turned off the biometric requirement — fill straight away.
                        fill()
                    }
                },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.SaveLoginPrompt -> {
            // Offered after the user submits credentials on a form so a new/changed password gets
            // persisted (and then synced). Confirming routes the entry through the storage delegate's
            // onLoginSave → PlacesLoginsStorage.addOrUpdate.
            val entry = request.logins.firstOrNull()
            val host = entry?.let { com.firebolt9907.luminabrowser.state.compactHost(it.origin) }.orEmpty()
            JsConfirmDialog(
                title = "Save password?",
                message = if (host.isNotBlank()) "Save this password for $host?" else "Save this password?",
                positiveLabel = "Save",
                negativeLabel = "Not now",
                onPositive = { entry?.let { request.onConfirm(it) }; promptRequest = null },
                onNegative = { request.onDismiss(); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.SelectCreditCard -> {
            // Gecko raises this when a payment-card field is focused and our storage delegate
            // (onCreditCardsFetch) returned stored cards for the form. Without rendering it there is
            // no fill UI — this is the doorhanger that actually triggers card autofill. Selecting a
            // card is gated behind BiometricGate (financial data), matching the login flow.
            if (!creditCardAutofillEnabled) {
                LaunchedEffect(request) { request.onDismiss(); promptRequest = null }
                return
            }
            SelectCreditCardSheet(
                cards = request.creditCards,
                onSelect = { card ->
                    val fill: () -> Unit = {
                        request.onConfirm(card)
                        promptRequest = null
                    }
                    if (biometricAutofillRequired) {
                        BiometricGate.authenticate(
                            context = context,
                            title = "Fill card",
                            subtitle = "•••• ${card.obfuscatedCardNumber.takeLast(4)}",
                            onSuccess = fill,
                            onFailure = { request.onDismiss(); promptRequest = null },
                        )
                    } else {
                        fill()
                    }
                },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.SaveCreditCard -> {
            // Offered after the user submits a payment form so a new/changed card gets persisted (and
            // then synced). Confirming routes the entry through the storage delegate's onCreditCardSave.
            val card = request.creditCard
            JsConfirmDialog(
                title = "Save card?",
                message = "Save the card ending in ${card.obfuscatedCardNumber.takeLast(4)} for faster checkout?",
                positiveLabel = "Save",
                negativeLabel = "Not now",
                onPositive = { request.onConfirm(card); promptRequest = null },
                onNegative = { request.onDismiss(); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.SelectAddress -> {
            // Gecko raises this when an address field is focused and our storage delegate
            // (onAddressesFetch) returned stored addresses for the form. Without rendering it there is
            // no fill UI. Addresses aren't secret, so there is no biometric gate here.
            if (!addressAutofillEnabled) {
                LaunchedEffect(request) { request.onDismiss(); promptRequest = null }
                return
            }
            SelectAddressSheet(
                addresses = request.addresses,
                onSelect = { address ->
                    request.onConfirm(address)
                    promptRequest = null
                },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.Popup -> {
            // A blocked pop-up asked to open. Let the user allow it (e.g. a bank's OTP window) or keep
            // it blocked; dismissing defaults to deny.
            val target = com.firebolt9907.luminabrowser.state.compactHost(request.targetUri)
            JsConfirmDialog(
                title = "Allow pop-up?",
                message = if (target.isNotBlank()) "This page wants to open a window for $target." else "This page wants to open a pop-up window.",
                positiveLabel = "Allow",
                negativeLabel = "Block",
                onPositive = { request.onAllow(); promptRequest = null },
                onNegative = { request.onDeny(); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        else -> {
            android.util.Log.w("PromptHandler", "Unhandled prompt: ${request?.javaClass?.simpleName}")
        }
    }
}
