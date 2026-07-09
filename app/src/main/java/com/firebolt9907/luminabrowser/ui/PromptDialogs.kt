package com.firebolt9907.luminabrowser.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import mozilla.components.concept.engine.prompt.Choice

/**
 * The dialog composables the [PromptHandler] router shows for each engine prompt — JS
 * alert/confirm/prompt, beforeunload, HTTP auth, identity-credential flows, and the
 * single/multiple/menu choice pickers. Split out of `PromptHandler.kt` so that file holds only
 * the prompt-routing logic and these hold the presentation.
 */

@Composable
internal fun JsAlertDialog(
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
internal fun JsConfirmDialog(
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
internal fun BeforeUnloadDialog(
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
internal fun JsTextPromptDialog(
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
internal fun AuthenticationDialog(
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
internal fun IdentityCredentialProviderDialog(
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
internal fun IdentityCredentialAccountDialog(
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
internal fun IdentityCredentialPrivacyPolicyDialog(
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
internal fun SingleChoiceDialog(
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
internal fun MultipleChoiceDialog(
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
internal fun MenuChoiceDialog(
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
internal fun ChoiceItems(
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
