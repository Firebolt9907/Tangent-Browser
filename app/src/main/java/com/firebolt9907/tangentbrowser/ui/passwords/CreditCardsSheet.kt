package com.firebolt9907.tangentbrowser.ui.passwords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
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
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SwipeToRemoveRow
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.CreditCard
import mozilla.components.concept.storage.CreditCardNumber
import mozilla.components.concept.storage.NewCreditCardFields
import mozilla.components.service.sync.autofill.AutofillCreditCardsAddressesStorage

/**
 * Manage saved payment methods (credit/debit cards). Mirrors [PasswordsSheet]. Card numbers are only
 * ever shown masked (last 4) — the full PAN stays encrypted at rest and is never surfaced here — and
 * there is deliberately no CVC field (GeckoView can't autofill one and PCI-DSS forbids storing it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditCardsSheet(
    creditCardsStorage: AutofillCreditCardsAddressesStorage,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberTangentHaptics()
    var cards by remember { mutableStateOf<List<CreditCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cards = runCatching { creditCardsStorage.getAllCreditCards() }.getOrDefault(emptyList())
        loading = false
    }

    TangentModalSheet(onDismissRequest = onDismiss) {
        SheetHeader(
            title = "Payment methods",
            leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
        )

        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 24.dp),
            )
        } else {
            if (cards.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.CreditCard,
                    title = "No saved cards",
                    description = "Cards you save will appear here and can be autofilled into " +
                        "payment forms. Add one with the button below.",
                )
            } else {
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(cards, key = { it.guid }) { card ->
                        // Swipe end-to-start to delete → confirm dialog (row springs back until
                        // confirmed, since payment data is sensitive). See SwipeToRemoveRow.
                        SwipeToRemoveRow(
                            onRemove = { showDeleteConfirm = card.guid },
                            settleOnRemove = false,
                        ) {
                            CreditCardRow(card = card)
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
                    Text("Add Card")
                }
            }
        }
    }

    if (showAddDialog) {
        AddCardDialog(
            onDismiss = { showAddDialog = false },
            onSave = { fields ->
                scope.launch {
                    runCatching {
                        creditCardsStorage.addCreditCard(fields)
                        creditCardsStorage.getAllCreditCards()
                    }.onSuccess { cards = it }
                }
                showAddDialog = false
            },
        )
    }

    showDeleteConfirm?.let { guid ->
        val card = cards.find { it.guid == guid }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete card?") },
            text = { Text("Remove the card ending in ${card?.cardNumberLast4 ?: "••••"}?") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.remove()
                    scope.launch {
                        runCatching {
                            creditCardsStorage.deleteCreditCard(guid)
                            creditCardsStorage.getAllCreditCards()
                        }.onSuccess { cards = it }
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
private fun CreditCardRow(
    card: CreditCard,
) {
    ListItem(
        leadingContent = { Icon(Icons.Default.CreditCard, contentDescription = null) },
        headlineContent = {
            Text(
                text = "•••• ${card.cardNumberLast4}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (card.billingName.isNotBlank()) {
                    Text(
                        text = card.billingName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "Expires ${card.expiryMonth.toString().padStart(2, '0')}/${card.expiryYear}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun AddCardDialog(
    onDismiss: () -> Unit,
    onSave: (NewCreditCardFields) -> Unit,
) {
    var number by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }

    val digits = number.filter { it.isDigit() }
    val monthNum = month.toLongOrNull()
    val yearNum = year.toLongOrNull()
    val canSave = CardValidation.isLuhnValid(digits) &&
        monthNum != null && monthNum in 1..12 &&
        yearNum != null && yearNum in 2000..2100

    val save = {
        // Guard rather than !! — the confirm button is gated on canSave (which requires both
        // non-null), but reading the values defensively keeps a future rewiring from crashing.
        val expMonth = monthNum
        val expYear = yearNum
        if (expMonth != null && expYear != null) {
            onSave(
                NewCreditCardFields(
                    billingName = name.trim(),
                    plaintextCardNumber = CreditCardNumber.Plaintext(digits),
                    cardNumberLast4 = digits.takeLast(4),
                    expiryMonth = expMonth,
                    expiryYear = expYear,
                    cardType = detectCardType(digits),
                ),
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Card") },
        text = {
            Column {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Card number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name on card") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = month,
                        onValueChange = { month = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("MM") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("YYYY") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
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

/**
 * Best-effort issuer-network label from the card number prefix (used for the stored card's type /
 * icon hint). Empty string when unknown — the storage accepts that.
 */
private fun detectCardType(digits: String): String = when {
    digits.startsWith("4") -> "visa"
    digits.take(2).toIntOrNull() in 51..55 || digits.take(4).toIntOrNull() in 2221..2720 -> "mastercard"
    digits.take(2) in setOf("34", "37") -> "amex"
    digits.startsWith("6011") || digits.take(2) == "65" -> "discover"
    else -> ""
}
