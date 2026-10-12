package com.firebolt9907.tangentbrowser.ui.passwords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.addressSummaryLine
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.SheetContentPhase
import com.firebolt9907.tangentbrowser.ui.components.SheetContentSwitch
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SwipeToRemoveRow
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.Address
import mozilla.components.concept.storage.UpdatableAddressFields
import mozilla.components.service.sync.autofill.AutofillCreditCardsAddressesStorage

/**
 * Manage saved addresses (name / contact / postal info) used to autofill checkout & profile forms.
 * Mirrors [CreditCardsSheet]. This MC version surfaces no address "save" doorhanger, so addresses are
 * added here manually; fill happens via [PromptRequest.SelectAddress] once one is saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressesSheet(
    creditCardsStorage: AutofillCreditCardsAddressesStorage,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberTangentHaptics()
    val snackbarHostState = remember { SnackbarHostState() }
    var addresses by remember { mutableStateOf<List<Address>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // A failed read used to fall through to an empty list, so a storage error rendered as the
    // confident (and wrong) claim "No saved addresses" with no way to retry. Remembered instead.
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        loading = addresses.isEmpty()
        runCatching { creditCardsStorage.getAllAddresses() }
            .onSuccess {
                addresses = it
                loadFailed = false
            }
            .onFailure {
                loadFailed = true
                if (addresses.isNotEmpty()) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Couldn't refresh addresses",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            }
        loading = false
    }

    // Deleting an address is fully reversible: an Address carries every field addAddress() takes,
    // so the record can be rebuilt verbatim. That makes a snackbar undo the right surface — the
    // confirm dialog this replaces taxed every delete to protect against a mistake we can just fix.
    // (No haptics.remove() here — SwipeToRemoveRow already plays it on the gesture that gets here.)
    suspend fun deleteWithUndo(address: Address) {
        val deleted = runCatching { creditCardsStorage.deleteAddress(address.guid) }
        if (deleted.isFailure) {
            snackbarHostState.showSnackbar(
                message = "Couldn't remove that address",
                duration = SnackbarDuration.Short,
            )
            return
        }
        addresses = runCatching { creditCardsStorage.getAllAddresses() }
            .getOrElse { addresses.filterNot { it.guid == address.guid } }
        val result = snackbarHostState.showSnackbar(
            message = "Address removed",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            runCatching {
                creditCardsStorage.addAddress(address.toUpdatableFields())
            }.onSuccess {
                haptics.create()
                runCatching { creditCardsStorage.getAllAddresses() }
                    .onSuccess { addresses = it }
                    .onFailure {
                        snackbarHostState.showSnackbar(
                            message = "Address restored; couldn't refresh the list",
                            duration = SnackbarDuration.Short,
                        )
                    }
            }.onFailure {
                snackbarHostState.showSnackbar(
                    message = "Couldn't restore that address",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    TangentModalSheet(onDismissRequest = onDismiss) {
        // The snackbar overlays the sheet's content rather than sitting after it in the column, so
        // a message doesn't shove the list around as it comes and goes.
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetHeader(
                    title = "Addresses",
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                )

                Spacer(Modifier.height(12.dp))

                // Spinner → empty/list is a cross-fade, not a swap: see SheetContentSwitch.
                val phase = when {
                    loading -> SheetContentPhase.Loading
                    addresses.isEmpty() -> SheetContentPhase.Empty
                    else -> SheetContentPhase.Content
                }
                SheetContentSwitch(phase, modifier = Modifier.fillMaxWidth()) { current ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        when (current) {
                            SheetContentPhase.Loading -> CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(vertical = 24.dp),
                            )

                            SheetContentPhase.Empty -> if (loadFailed) {
                                EmptyState(
                                    icon = Icons.Default.LocationOn,
                                    title = "Couldn't load addresses",
                                    description = "Your saved addresses are still there — the " +
                                        "store just didn't answer.",
                                    action = {
                                        Button(onClick = { reloadKey++ }) { Text("Try again") }
                                    },
                                )
                            } else {
                                EmptyState(
                                    icon = Icons.Default.LocationOn,
                                    title = "No saved addresses",
                                    description = "Addresses you save will appear here and can be " +
                                        "autofilled into checkout and profile forms. Add one with " +
                                        "the button below.",
                                )
                            }

                            // Capped, not fixed: the Add button below must stay reachable when the
                            // list is long, but a short list should shrink to fit rather than reserve
                            // 320dp of empty space above it.
                            SheetContentPhase.Content -> LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                            ) {
                                items(addresses, key = { it.guid }) { address ->
                                    // The row springs back while the storage call runs. A failed
                                    // delete must leave it visible; a successful one removes it
                                    // from the list and offers Undo.
                                    SwipeToRemoveRow(
                                        onRemove = { scope.launch { deleteWithUndo(address) } },
                                        settleOnRemove = false,
                                        removeLabel = "Remove address",
                                    ) {
                                        AddressRow(address = address)
                                    }
                                }
                            }
                        }

                        if (current != SheetContentPhase.Loading) {
                            Spacer(Modifier.height(8.dp))

                            // The one action on this surface, and the reason the sheet exists when
                            // it is empty — a full-width filled button, not a tonal outline sharing
                            // a Row with nothing.
                            Button(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text("Add Address")
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showAddDialog) {
        AddAddressDialog(
            onDismiss = { showAddDialog = false },
            onSave = { fields ->
                scope.launch {
                    runCatching { creditCardsStorage.addAddress(fields) }.onSuccess {
                        haptics.create()
                        runCatching { creditCardsStorage.getAllAddresses() }
                            .onSuccess { addresses = it }
                            .onFailure {
                                snackbarHostState.showSnackbar(
                                    message = "Address saved; couldn't refresh the list",
                                    duration = SnackbarDuration.Short,
                                )
                            }
                    }.onFailure {
                        // Silently dropping the save made a failed write look identical to a
                        // successful one that simply hadn't refreshed yet.
                        snackbarHostState.showSnackbar("Couldn't save that address")
                    }
                }
                showAddDialog = false
            },
        )
    }
}

/** Rebuilds the writable field set from a stored [Address] — the inverse of [addAddress]. */
private fun Address.toUpdatableFields() = UpdatableAddressFields(
    name = name,
    organization = organization,
    streetAddress = streetAddress,
    addressLevel3 = addressLevel3,
    addressLevel2 = addressLevel2,
    addressLevel1 = addressLevel1,
    postalCode = postalCode,
    country = country,
    tel = tel,
    email = email,
)

@Composable
private fun AddressRow(
    address: Address,
) {
    ListItem(
        leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null) },
        headlineContent = {
            Text(
                text = address.name.ifBlank { address.addressLabel.ifBlank { "Address" } },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val summary = addressSummaryLine(address)
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

@Composable
private fun AddAddressDialog(
    onDismiss: () -> Unit,
    onSave: (UpdatableAddressFields) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var postal by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var tel by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    // Nine fields deep; opening the dialog and then having to tap the first one is a wasted step.
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocus.requestFocus() }

    // At least a name or a street line makes for a useful, fillable record.
    val canSave = name.isNotBlank() || street.isNotBlank()

    val save = {
        onSave(
            UpdatableAddressFields(
                name = name.trim(),
                organization = organization.trim(),
                streetAddress = street.trim(),
                addressLevel3 = "",
                addressLevel2 = city.trim(),
                addressLevel1 = region.trim(),
                postalCode = postal.trim(),
                country = country.trim(),
                tel = tel.trim(),
                email = email.trim(),
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Address") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AddressField(
                    name, { name = it }, "Full name", ImeAction.Next,
                    modifier = Modifier.focusRequester(nameFocus),
                )
                AddressField(organization, { organization = it }, "Organization (optional)", ImeAction.Next)
                AddressField(street, { street = it }, "Street address", ImeAction.Next, singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddressField(city, { city = it }, "City", ImeAction.Next, modifier = Modifier.weight(1f))
                    AddressField(region, { region = it }, "State/Region", ImeAction.Next, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddressField(postal, { postal = it }, "ZIP/Postal", ImeAction.Next, modifier = Modifier.weight(1f))
                    AddressField(country, { country = it }, "Country", ImeAction.Next, modifier = Modifier.weight(1f))
                }
                AddressField(tel, { tel = it }, "Phone", ImeAction.Next, keyboardType = KeyboardType.Phone)
                AddressField(email, { email = it }, "Email", ImeAction.Done, keyboardType = KeyboardType.Email)
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = canSave) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        modifier = modifier.fillMaxWidth(),
    )
}
