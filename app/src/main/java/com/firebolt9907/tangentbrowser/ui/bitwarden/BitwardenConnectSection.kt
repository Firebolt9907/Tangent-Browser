package com.firebolt9907.tangentbrowser.ui.bitwarden

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import com.firebolt9907.tangentbrowser.ui.components.BusyIndicator
import com.firebolt9907.tangentbrowser.ui.components.BusyIndicatorAxis
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion
import com.firebolt9907.tangentbrowser.ui.theme.bodySmallEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.labelLargeEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.titleSmallEmphasized
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenAuthMethod
import com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenConnectionState
import com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenController
import com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenCredentials
import com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenRegion

/**
 * Bitwarden connection UI, embedded at the top of the Passwords sheet. A compact status card plus a
 * connect/2FA dialog driven entirely by [BitwardenController.uiState]. When the vault changes
 * (connect, sync) it calls [onVaultChanged] so the surrounding password list reloads with the merged
 * Bitwarden entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenConnectSection(
    controller: BitwardenController,
    onVaultChanged: () -> Unit,
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    // Close the dialog once connected, and refresh the list after any connect/sync.
    LaunchedEffect(state.state) {
        if (state.state == BitwardenConnectionState.CONNECTED) showDialog = false
    }
    LaunchedEffect(state.lastSyncAtMs) {
        if (state.lastSyncAtMs != null) onVaultChanged()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (state.state == BitwardenConnectionState.CONNECTED) {
                Text("Bitwarden", style = MaterialTheme.typography.titleSmallEmphasized)
                Spacer(Modifier.height(2.dp))
                Text(
                    state.email ?: "Connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.syncNow() },
                        enabled = !state.isBusy,
                    ) { Text("Sync now") }
                    TextButton(onClick = { controller.disconnect() }) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                Text("Bitwarden", style = MaterialTheme.typography.titleSmallEmphasized)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Add your Bitwarden vault as a login source.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showDialog = true }) { Text("Connect Bitwarden") }
            }
            StatusMessage(state.message, state.isError)
        }
    }

    if (showDialog) {
        BitwardenConnectDialog(
            controller = controller,
            onDismiss = {
                showDialog = false
                controller.clearMessage()
            },
        )
    }
}

/**
 * The controller narrates busy → outcome, so this line appears, changes and vanishes repeatedly
 * during one connect. Animating it keeps the card from snapping its own height each time: expand /
 * shrink on the spatial spring, alpha on the critically-damped effects spring. The text is held in a
 * [remember] because [AnimatedVisibility] keeps composing its content through the exit — reading the
 * live value would blank the line a frame before the card finishes collapsing.
 */
@Composable
private fun StatusMessage(message: String?, isError: Boolean) {
    val text = message?.takeIf { it.isNotBlank() }
    var lastShown by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf(false) }
    if (text != null) {
        lastShown = text
        lastError = isError
    }
    AnimatedVisibility(
        visible = text != null,
        enter = expandVertically(animationSpec = ExpressiveMotion.defaultSpatialSpec()) +
            fadeIn(animationSpec = ExpressiveMotion.defaultEffectsSpec()),
        exit = shrinkVertically(animationSpec = ExpressiveMotion.defaultSpatialSpec()) +
            fadeOut(animationSpec = ExpressiveMotion.defaultEffectsSpec()),
    ) {
        Text(
            lastShown,
            style = if (lastError) MaterialTheme.typography.bodySmallEmphasized
            else MaterialTheme.typography.bodySmall,
            color = if (lastError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitwardenConnectDialog(
    controller: BitwardenController,
    onDismiss: () -> Unit,
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val haptics = rememberTangentHaptics()
    val firstField = remember { FocusRequester() }

    var region by remember { mutableStateOf(BitwardenRegion.US) }
    var selfHostUrl by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var masterPassword by remember { mutableStateOf("") }
    var useApiKey by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var twoFactorCode by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf<Int?>(null) }

    val needsTwoFactor = state.state == BitwardenConnectionState.NEEDS_TWO_FACTOR
    if (needsTwoFactor && selectedProvider == null) {
        selectedProvider = state.twoFactorProviders.firstOrNull() ?: 0
    }

    val canConnect = email.isNotBlank() && masterPassword.isNotBlank() &&
        (!useApiKey || (clientId.isNotBlank() && clientSecret.isNotBlank())) &&
        (region != BitwardenRegion.SELF_HOSTED || selfHostUrl.isNotBlank())

    // A local fun, not an inline lambda, so the confirm button and the last field's IME action can't
    // drift apart on how they build the credentials.
    fun connect() {
        controller.connect(
            BitwardenCredentials(
                method = if (useApiKey) BitwardenAuthMethod.API_KEY else BitwardenAuthMethod.PASSWORD,
                region = region,
                selfHostedBaseUrl = selfHostUrl.takeIf { region == BitwardenRegion.SELF_HOSTED },
                email = email.trim(),
                masterPassword = masterPassword,
                clientId = clientId.trim().takeIf { useApiKey },
                clientSecret = clientSecret.trim().takeIf { useApiKey },
            ),
        )
    }

    // The dialog opens straight onto a field the user must fill; leaving it unfocused costs a tap
    // and a keyboard wait on every connect and every 2FA challenge.
    LaunchedEffect(needsTwoFactor) {
        runCatching { firstField.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = { if (!state.isBusy) onDismiss() },
        title = { Text(if (needsTwoFactor) "Two-step login" else "Connect Bitwarden") },
        text = {
            // The dialog can grow to six fields plus the switch once the API-key half is open, and an
            // AlertDialog does not scroll its own text slot — without this it clips at large font scales.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (needsTwoFactor) {
                    Text(
                        "Enter your two-step verification code.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.twoFactorProviders.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        ProviderPicker(state.twoFactorProviders, selectedProvider) { selectedProvider = it }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = twoFactorCode,
                        onValueChange = { twoFactorCode = it },
                        label = { Text("Verification code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (twoFactorCode.isNotBlank() && !state.isBusy) {
                                    controller.submitTwoFactor(twoFactorCode, selectedProvider ?: 0)
                                }
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField),
                    )
                } else {
                    RegionPicker(
                        region = region,
                        selfHostUrl = selfHostUrl,
                        onRegion = { haptics.select(); region = it },
                        onSelfHost = { selfHostUrl = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = masterPassword,
                        onValueChange = { masterPassword = it },
                        label = { Text("Master password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            // Nothing follows this field unless the API-key half is open.
                            imeAction = if (useApiKey) ImeAction.Next else ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (canConnect && !state.isBusy) connect() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // The whole row is the target — a Switch alone leaves the label and its two-line
                    // explanation as dead space, which is most of the row's width.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = useApiKey,
                                role = Role.Switch,
                                onValueChange = { haptics.toggle(it); useApiKey = it },
                            ),
                    ) {
                        Switch(checked = useApiKey, onCheckedChange = null)
                        Spacer(Modifier.size(8.dp))
                        Column {
                            Text("Use API key (more reliable)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Avoids captcha & 2FA prompts. From the web vault: Settings → Security → Keys.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (useApiKey) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            label = { Text("client_id") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = clientSecret,
                            onValueChange = { clientSecret = it },
                            label = { Text("client_secret") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { if (canConnect && !state.isBusy) connect() },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                StatusMessage(state.message, state.isError)
                BusyIndicator(
                    visible = state.isBusy,
                    axis = BusyIndicatorAxis.Vertical,
                    size = 24.dp,
                    leadingSpace = 12.dp,
                )
            }
        },
        confirmButton = {
            if (needsTwoFactor) {
                TextButton(
                    onClick = { controller.submitTwoFactor(twoFactorCode, selectedProvider ?: 0) },
                    enabled = twoFactorCode.isNotBlank() && !state.isBusy,
                ) { Text("Verify") }
            } else {
                TextButton(
                    enabled = canConnect && !state.isBusy,
                    onClick = { connect() },
                ) { Text("Connect") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isBusy) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionPicker(
    region: BitwardenRegion,
    selfHostUrl: String,
    onRegion: (BitwardenRegion) -> Unit,
    onSelfHost: (String) -> Unit,
) {
    Column {
        Text("Server", style = MaterialTheme.typography.labelLargeEmphasized)
        Spacer(Modifier.height(4.dp))
        // Three mutually exclusive values with short labels: a segmented row, not a button that opens
        // a menu. Picking a region was two taps and hid the alternatives behind the second one.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            BitwardenRegion.entries.forEachIndexed { index, r ->
                SegmentedButton(
                    selected = region == r,
                    onClick = { onRegion(r) },
                    shape = SegmentedButtonDefaults.itemShape(index, BitwardenRegion.entries.size),
                    // Without this the check slot eats the width and "Self-hosted" stops fitting.
                    icon = {},
                ) { Text(r.shortLabel()) }
            }
        }
        if (region == BitwardenRegion.SELF_HOSTED) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = selfHostUrl,
                onValueChange = onSelfHost,
                label = { Text("Server URL") },
                placeholder = { Text("https://vault.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPicker(providers: List<Int>, selected: Int?, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Method: ${twoFactorProviderLabel(selected ?: providers.firstOrNull() ?: 0)}")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        providers.forEach { p ->
            DropdownMenuItem(text = { Text(twoFactorProviderLabel(p)) }, onClick = { onSelect(p); expanded = false })
        }
    }
}

/** Segment labels: short enough that three fit one phone-width row. */
private fun BitwardenRegion.shortLabel(): String = when (this) {
    BitwardenRegion.US -> "US"
    BitwardenRegion.EU -> "EU"
    BitwardenRegion.SELF_HOSTED -> "Self-hosted"
}

/** Bitwarden TwoFactorProviderType ids → human labels (the common ones). */
private fun twoFactorProviderLabel(id: Int): String = when (id) {
    0 -> "Email"
    1 -> "Authenticator app"
    2 -> "Duo"
    3 -> "YubiKey"
    4 -> "U2F"
    6 -> "Duo (organization)"
    7 -> "WebAuthn"
    else -> "Provider $id"
}
