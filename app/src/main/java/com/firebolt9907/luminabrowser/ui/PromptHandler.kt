package com.firebolt9907.luminabrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.prompt.Choice
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
        else -> {
            android.util.Log.w("PromptHandler", "Unhandled prompt: ${request?.javaClass?.simpleName}")
        }
    }
}

/** JS `alert()` — a message the page wants to show. Single OK, matching Safari. */
@Composable
private fun JsAlertDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
    )
}

/** JS `confirm()` — a yes/no question. Previously auto-answered "yes"; now actually asks. */
@Composable
private fun JsConfirmDialog(
    title: String,
    message: String,
    positiveLabel: String,
    negativeLabel: String,
    onPositive: () -> Unit,
    onNegative: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onPositive) { Text(positiveLabel) } },
        dismissButton = { TextButton(onClick = onNegative) { Text(negativeLabel) } },
    )
}

/** The `beforeunload` "leave this page?" guard — previously auto-left (risking unsaved data). */
@Composable
private fun BeforeUnloadDialog(
    onLeave: () -> Unit,
    onStay: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave this page?") },
        text = { Text("Changes you made may not be saved.") },
        confirmButton = { TextButton(onClick = onLeave) { Text("Leave") } },
        dismissButton = { TextButton(onClick = onStay) { Text("Stay") } },
    )
}

/** JS `prompt()` — a message plus a text field. Previously unhandled (the page got nothing back). */
@Composable
private fun JsTextPromptDialog(
    title: String,
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = {
            Column {
                if (label.isNotBlank()) {
                    Text(label)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** HTTP basic/digest auth — previously unhandled, so password-protected sites couldn't be opened. */
@Composable
private fun AuthenticationDialog(
    title: String,
    message: String,
    initialUsername: String,
    initialPassword: String,
    onlyPassword: Boolean,
    previousFailed: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.ifBlank { "Sign In" }) },
        text = {
            Column {
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                if (previousFailed) {
                    Text(
                        "The username or password was incorrect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (!onlyPassword) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(username, password) }) { Text("Sign In") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IdentityCredentialProviderDialog(
    providers: List<String>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select credential provider") },
        text = {
            Column {
                providers.forEachIndexed { index, name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(index) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun IdentityCredentialAccountDialog(
    accounts: List<String>,
    providerName: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select passkey") },
        text = {
            Column {
                accounts.forEachIndexed { index, account ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(index) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(account, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun IdentityCredentialPrivacyPolicyDialog(
    providerDomain: String,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy policy") },
        text = {
            Text(
                "The provider at $providerDomain requires you to review their privacy policy " +
                    "and terms of service before proceeding.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(true) }) { Text("Accept") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("Decline") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleChoiceDialog(
    choices: List<Choice>,
    onConfirm: (Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Choose an option",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ChoiceItems(choices, indicator = { choice, onClick ->
                RadioButton(
                    selected = choice.selected,
                    onClick = onClick,
                    enabled = choice.enable,
                )
            }, onSelect = onConfirm)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultipleChoiceDialog(
    choices: List<Choice>,
    onConfirm: (Array<Choice>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateOf(choices.filter { it.selected }.toMutableSet()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Select options",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ChoiceItems(choices, indicator = { choice, onClick ->
                Checkbox(
                    checked = choice in selected.value,
                    onCheckedChange = { checked ->
                        if (checked) selected.value.add(choice)
                        else selected.value.remove(choice)
                    },
                    enabled = choice.enable,
                )
            }, onSelect = { choice ->
                if (choice in selected.value) selected.value.remove(choice)
                else selected.value.add(choice)
            })
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onConfirm(selected.value.toTypedArray()) }) { Text("OK") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuChoiceDialog(
    choices: List<Choice>,
    onConfirm: (Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            ChoiceItems(choices, indicator = { _, _ -> }, onSelect = onConfirm)
        }
    }
}

@Composable
private fun ChoiceItems(
    choices: List<Choice>,
    indicator: @Composable (Choice, () -> Unit) -> Unit,
    onSelect: (Choice) -> Unit,
    indent: Int = 0,
) {
    choices.forEach { choice ->
        if (choice.isASeparator) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        } else if (!choice.children.isNullOrEmpty()) {
            Text(
                text = choice.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = (16 + indent * 24).dp,
                    top = 12.dp,
                    bottom = 4.dp,
                ),
            )
            ChoiceItems(choice.children!!.toList(), indicator, onSelect, indent + 1)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (choice.enable) Modifier.clickable { onSelect(choice) }
                        else Modifier
                    )
                    .padding(
                        start = (8 + indent * 24).dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                indicator(choice, { onSelect(choice) })
                Spacer(Modifier.width(12.dp))
                Text(
                    text = choice.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (choice.enable) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        }
    }
}
