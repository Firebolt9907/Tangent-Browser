package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.theme.bodySmallEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.labelMediumEmphasized
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

/**
 * JS `prompt()` — a message plus a text field. Previously unhandled (the page got nothing back).
 *
 * The field is the only reason this dialog exists, so it takes focus (and raises the keyboard) on
 * open, and its IME action commits the answer — otherwise every one-word reply cost a keyboard
 * dismissal plus a reach up to "OK".
 */
@Composable
internal fun JsTextPromptDialog(
    title: String,
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(value) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The "confirm form resubmission" prompt shown when reloading/navigating to a page that would
 * re-POST data. Previously unhandled, which could leave a reload stuck waiting on an answer.
 */
@Composable
internal fun RepostDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm form resubmission") },
        text = { Text("To reload this page, the information you previously submitted will be sent again.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Resend") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * HTTP basic/digest auth — previously unhandled, so password-protected sites couldn't be opened.
 *
 * Whichever field the user actually has to fill takes focus on open (the password when the realm
 * only wants one), username advances to password with "Next", and password signs in with "Done".
 */
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
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    LaunchedEffect(onlyPassword) {
        if (onlyPassword) passwordFocus.requestFocus() else usernameFocus.requestFocus()
    }
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
                        style = MaterialTheme.typography.bodySmallEmphasized,
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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(usernameFocus),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(username, password) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(username, password) }) { Text("Sign In") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A tap-to-pick list dialog used by both identity-credential steps (provider selection and
 * account/passkey selection) — they were identical apart from the title.
 *
 * Each row is a full-width 48dp target announcing itself as a button: the rows were previously
 * 12dp-padded around a single line of `bodyLarge`, i.e. ~44dp, and carried no role at all, so
 * TalkBack read the provider name as static text with nothing to activate.
 */
@Composable
internal fun IdentityCredentialListDialog(
    title: String,
    items: List<String>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberTangentHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                items.forEachIndexed { index, name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                haptics.select()
                                onConfirm(index)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
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

/**
 * The shared bottom-sheet shell for the three `<select>`/choice pickers below.
 *
 * [TangentModalSheet] with `scrollable = true`: [ChoiceItems] emits its rows straight into this
 * column rather than into a lazy list, so a long `<select>` — a country, state or year dropdown,
 * which is where most of them come from — otherwise ran off the bottom of the sheet with no way to
 * reach the last options. The shared shell also brings `skipPartiallyExpanded`, so the picker opens
 * at full height and the first upward drag scrolls the options instead of expanding a half-detent.
 *
 * [actions] is the multi-select's commit row, and it rides the shell's `overlay` slot rather than
 * being the last child of the scrolling column: a `<select multiple>` of a hundred options would
 * otherwise park "OK" a hundred rows below the fold. Pinned to the bottom edge it is also inside
 * the one-handed reach zone. The content column pays for it in extra bottom padding so the last
 * option can still be scrolled clear of the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    title: String?,
    onDismiss: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    TangentModalSheet(
        onDismissRequest = onDismiss,
        scrollable = true,
        bottomPadding = if (actions != null) 92.dp else 28.dp,
        overlay = {
            if (actions != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(BottomSheetDefaults.ContainerColor),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        if (title != null) {
            SheetHeader(title = title, modifier = Modifier.padding(bottom = 8.dp))
        }
        content()
    }
}

@Composable
internal fun SingleChoiceDialog(
    choices: List<Choice>,
    onConfirm: (Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceSheet(title = "Choose an option", onDismiss = onDismiss) {
        ChoiceItems(
            choices = choices,
            onSelect = onConfirm,
            role = Role.RadioButton,
            isSelected = { it.selected },
            indicator = { choice, selected ->
                // Presentational: the row owns the click and the selected state, so the control
                // must not publish a second, duplicate target of its own.
                RadioButton(selected = selected, onClick = null, enabled = choice.enable)
            },
        )
    }
}

@Composable
internal fun MultipleChoiceDialog(
    choices: List<Choice>,
    onConfirm: (Array<Choice>) -> Unit,
    onDismiss: () -> Unit,
) {
    // An immutable set in state, replaced on every toggle. This was a `MutableSet` behind
    // `mutableStateOf` and mutated in place, so Compose never saw a change and the boxes did not
    // visibly tick — a multi-select the user could not read back.
    var selected by remember { mutableStateOf(choices.flatten().filter { it.selected }.toSet()) }

    ChoiceSheet(
        title = "Select options",
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onConfirm(selected.toTypedArray()) }) { Text("OK") }
        },
    ) {
        ChoiceItems(
            choices = choices,
            onSelect = { choice ->
                selected = if (choice in selected) selected - choice else selected + choice
            },
            role = Role.Checkbox,
            isSelected = { it in selected },
            indicator = { choice, checked ->
                Checkbox(checked = checked, onCheckedChange = null, enabled = choice.enable)
            },
        )
    }
}

@Composable
internal fun MenuChoiceDialog(
    choices: List<Choice>,
    onConfirm: (Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceSheet(title = null, onDismiss = onDismiss) {
        ChoiceItems(choices = choices, onSelect = onConfirm, role = Role.Button)
    }
}

/** Every choice in the tree, group children included — what the toggle set has to be seeded from. */
private fun List<Choice>.flatten(): List<Choice> =
    flatMap { choice ->
        val children = choice.children?.toList().orEmpty()
        if (children.isEmpty()) listOf(choice) else children.flatten()
    }

/**
 * The rows behind all three pickers. [role] decides how a row announces itself and which selection
 * modifier it takes — `selectable` for the radio picker, `toggleable` for the checkbox one, plain
 * `clickable` for the menu — and [indicator] is *presentational only*: the row is the whole touch
 * target, so a control that also handled its own click would give TalkBack two overlapping targets
 * per option and leave the rest of the row dead to touch.
 */
@Composable
internal fun ChoiceItems(
    choices: List<Choice>,
    onSelect: (Choice) -> Unit,
    role: Role = Role.Button,
    isSelected: (Choice) -> Boolean = { false },
    indent: Int = 0,
    indicator: (@Composable (Choice, Boolean) -> Unit)? = null,
) {
    choices.forEach { choice ->
        if (choice.isASeparator) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        } else if (!choice.children.isNullOrEmpty()) {
            Text(
                text = choice.label,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = (16 + indent * 24).dp,
                    top = 12.dp,
                    bottom = 4.dp,
                ),
            )
            ChoiceItems(
                choices = choice.children?.toList().orEmpty(),
                onSelect = onSelect,
                role = role,
                isSelected = isSelected,
                indent = indent + 1,
                indicator = indicator,
            )
        } else {
            val selected = isSelected(choice)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // A menu row carries no control of its own, so nothing else guarantees it the
                    // 48dp minimum a RadioButton/Checkbox brings with it.
                    .heightIn(min = 48.dp)
                    .then(
                        when (role) {
                            Role.RadioButton -> Modifier.selectable(
                                selected = selected,
                                enabled = choice.enable,
                                role = Role.RadioButton,
                                onClick = { onSelect(choice) },
                            )
                            Role.Checkbox -> Modifier.toggleable(
                                value = selected,
                                enabled = choice.enable,
                                role = Role.Checkbox,
                                onValueChange = { onSelect(choice) },
                            )
                            else -> Modifier.clickable(
                                enabled = choice.enable,
                                role = Role.Button,
                                onClick = { onSelect(choice) },
                            )
                        },
                    )
                    .padding(
                        start = (8 + indent * 24).dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (indicator != null) {
                    indicator(choice, selected)
                    Spacer(Modifier.width(12.dp))
                } else {
                    Spacer(Modifier.width(8.dp))
                }
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
