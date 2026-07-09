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
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.prompt.PromptRequest

@Composable
fun PromptHandler(engineSession: EngineSession?) {
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
            IdentityCredentialProviderDialog(
                providers = request.providers.map { it.name },
                onConfirm = { index -> request.onConfirm(request.providers[index]); promptRequest = null },
                onDismiss = { request.onDismiss(); promptRequest = null },
            )
        }
        is PromptRequest.IdentityCredential.SelectAccount -> {
            IdentityCredentialAccountDialog(
                accounts = request.accounts.map {
                    listOfNotNull(it.name, it.email).filter { it.isNotBlank() }.joinToString(" — ")
                },
                providerName = request.provider.name,
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
        else -> {
            android.util.Log.w("PromptHandler", "Unhandled prompt: ${request?.javaClass?.simpleName}")
        }
    }
}
