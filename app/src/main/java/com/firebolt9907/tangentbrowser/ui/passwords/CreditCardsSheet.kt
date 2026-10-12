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
import androidx.compose.material.icons.filled.CreditCard
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
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.SheetContentPhase
import com.firebolt9907.tangentbrowser.ui.components.SheetContentSwitch
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SwipeToRemoveRow
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.theme.bodyLargeEmphasized
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
    val snackbarHostState = remember { SnackbarHostState() }
    var cards by remember { mutableStateOf<List<CreditCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // A failed read used to fall through to an empty list, so a storage error rendered as the
    // confident (and wrong) claim "No saved cards" with no way to retry. Remembered instead.
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        loading = cards.isEmpty()
        runCatching { creditCardsStorage.getAllCreditCards() }
            .onSuccess {
                cards = it
                loadFailed = false
            }
            .onFailure {
                loadFailed = true
                if (cards.isNotEmpty()) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Couldn't refresh cards",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            }
        loading = false
    }

    TangentModalSheet(onDismissRequest = onDismiss) {
        // The snackbar overlays the sheet's content rather than sitting after it in the column, so
        // a message doesn't shove the list around as it comes and goes.
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetHeader(
                    title = "Payment methods",
                    leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                )

                Spacer(Modifier.height(12.dp))

                // Spinner → empty/list is a cross-fade, not a swap: see SheetContentSwitch.
                val phase = when {
                    loading -> SheetContentPhase.Loading
                    cards.isEmpty() -> SheetContentPhase.Empty
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
                                    icon = Icons.Default.CreditCard,
                                    title = "Couldn't load cards",
                                    description = "Your saved cards are still there — the store " +
                                        "just didn't answer.",
                                    action = {
                                        Button(onClick = { reloadKey++ }) { Text("Try again") }
                                    },
                                )
                            } else {
                                EmptyState(
                                    icon = Icons.Default.CreditCard,
                                    title = "No saved cards",
                                    description = "Cards you save will appear here and can be " +
                                        "autofilled into payment forms. Add one with the button " +
                                        "below.",
                                )
                            }

                            // Capped, not fixed: the Add button below must stay reachable when the
                            // list is long, but a short list should shrink to fit rather than reserve
                            // 320dp of empty space above it.
                            SheetContentPhase.Content -> LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                            ) {
                                items(cards, key = { it.guid }) { card ->
                                    // Swipe end-to-start to delete → confirm dialog (row springs
                                    // back until confirmed). Unlike an address (iter 18) a card is
                                    // NOT restorable: the PAN is encrypted at rest and is never
                                    // decrypted for this UI, so an undo could only re-add a card
                                    // without its number. The dialog stays.
                                    SwipeToRemoveRow(
                                        onRemove = { showDeleteConfirm = card.guid },
                                        settleOnRemove = false,
                                        removeLabel = "Remove card",
                                    ) {
                                        CreditCardRow(card = card)
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
                                Text("Add Card")
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
        AddCardDialog(
            onDismiss = { showAddDialog = false },
            onSave = { fields ->
                scope.launch {
                    runCatching {
                        creditCardsStorage.addCreditCard(fields)
                        creditCardsStorage.getAllCreditCards()
                    }.onSuccess {
                        cards = it
                        haptics.create()
                    }.onFailure {
                        // A failed save was silent, so a card that never landed looked saved.
                        snackbarHostState.showSnackbar("Couldn't save that card")
                    }
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
                            .onFailure {
                                snackbarHostState.showSnackbar("Couldn't remove that card")
                            }
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
            // The masked number IS the row's identity — carry the emphasized weight, not the
            // supporting text's.
            Text(
                text = "•••• ${card.cardNumberLast4}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLargeEmphasized,
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

    val now = remember { java.util.Calendar.getInstance() }
    val nowYear = now.get(java.util.Calendar.YEAR)
    val nowMonth = now.get(java.util.Calendar.MONTH) + 1 // Calendar.MONTH is 0-based

    val digits = number.filter { it.isDigit() }
    val monthNum = month.toLongOrNull()
    val yearNum = year.toLongOrNull()
    // Bound the Longs to sane ranges before toInt() so an over-long typed number can't overflow.
    val canSave = CardValidation.isLuhnValid(digits) &&
        monthNum != null && monthNum in 1..12 &&
        yearNum != null && yearNum in 2000..(nowYear + 25).toLong() &&
        CardValidation.isExpiryValid(monthNum.toInt(), yearNum.toInt(), nowYear, nowMonth)

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

    // Four fields plus labels overflow a dialog at large font scales, and the card number is the
    // only field anyone starts with — so scroll the body and open the keyboard on it.
    val numberFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { numberFocus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Card") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Card number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(numberFocus),
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
