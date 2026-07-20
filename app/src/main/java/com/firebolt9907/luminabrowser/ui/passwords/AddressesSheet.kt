package com.firebolt9907.luminabrowser.ui.passwords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.ui.addressSummaryLine
import com.firebolt9907.luminabrowser.ui.components.EmptyState
import com.firebolt9907.luminabrowser.ui.components.LuminaModalSheet
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import com.firebolt9907.luminabrowser.ui.components.SwipeToRemoveRow
import com.firebolt9907.luminabrowser.ui.rememberLuminaHaptics
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
    val haptics = rememberLuminaHaptics()
    var addresses by remember { mutableStateOf<List<Address>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        addresses = runCatching { creditCardsStorage.getAllAddresses() }.getOrDefault(emptyList())
        loading = false
    }

    LuminaModalSheet(onDismissRequest = onDismiss) {
        SheetHeader(
            title = "Addresses",
            leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
        )

        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 24.dp),
            )
        } else {
            if (addresses.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.LocationOn,
                    title = "No saved addresses",
                    description = "Addresses you save will appear here and can be autofilled into " +
                        "checkout and profile forms. Add one with the button below.",
                )
            } else {
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(addresses, key = { it.guid }) { address ->
                        // Swipe end-to-start to delete → confirm dialog (row springs back until
                        // confirmed). See SwipeToRemoveRow.
                        SwipeToRemoveRow(
                            onRemove = { showDeleteConfirm = address.guid },
                            settleOnRemove = false,
                        ) {
                            AddressRow(address = address)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Add Address")
                }
            }
        }
    }

    if (showAddDialog) {
        AddAddressDialog(
            onDismiss = { showAddDialog = false },
            onSave = { fields ->
                scope.launch {
                    runCatching {
                        creditCardsStorage.addAddress(fields)
                        creditCardsStorage.getAllAddresses()
                    }.onSuccess { addresses = it }
                }
                showAddDialog = false
            },
        )
    }

    showDeleteConfirm?.let { guid ->
        val address = addresses.find { it.guid == guid }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete address?") },
            text = { Text("Remove ${address?.name?.ifBlank { "this address" } ?: "this address"}?") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.remove()
                    scope.launch {
                        runCatching {
                            creditCardsStorage.deleteAddress(guid)
                            creditCardsStorage.getAllAddresses()
                        }.onSuccess { addresses = it }
                    }
                    showDeleteConfirm = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

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
                AddressField(name, { name = it }, "Full name", ImeAction.Next)
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
