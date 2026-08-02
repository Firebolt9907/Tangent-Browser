package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.firebolt9907.tangentbrowser.ui.theme.headlineSmallEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.titleMediumEmphasized
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.concept.storage.Address
import mozilla.components.concept.storage.CreditCardEntry

/**
 * The "pick a saved card to fill" sheet shown for
 * [mozilla.components.concept.engine.prompt.PromptRequest.SelectCreditCard]. Numbers are only ever
 * shown masked (last 4) — the full PAN never reaches this UI. Tapping a row runs the biometric gate
 * (in [PromptHandler]) before the card is filled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectCreditCardSheet(
    cards: List<CreditCardEntry>,
    onSelect: (CreditCardEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AutofillPickerScaffold(
        title = "Fill card",
        subtitle = "Choose a saved payment method",
        onDismiss = onDismiss,
    ) {
        items(cards, key = { it.guid ?: it.obfuscatedCardNumber }) { card ->
            val masked = "•••• ${card.obfuscatedCardNumber.takeLast(4)}"
            AutofillPickerRow(
                icon = Icons.Rounded.CreditCard,
                primary = masked,
                secondary = listOfNotNull(
                    card.name.takeIf { it.isNotBlank() },
                    card.expiryDate.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                onClick = { onSelect(card) },
            )
        }
    }
}

/**
 * The "pick a saved address to fill" sheet shown for
 * [mozilla.components.concept.engine.prompt.PromptRequest.SelectAddress]. Addresses aren't sensitive
 * the way passwords/cards are, so there is no biometric gate — selecting fills immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectAddressSheet(
    addresses: List<Address>,
    onSelect: (Address) -> Unit,
    onDismiss: () -> Unit,
) {
    AutofillPickerScaffold(
        title = "Fill address",
        subtitle = "Choose a saved address",
        onDismiss = onDismiss,
    ) {
        items(addresses, key = { it.guid }) { address ->
            AutofillPickerRow(
                icon = Icons.Rounded.LocationOn,
                primary = address.addressLabel.ifBlank { address.name }.ifBlank { "Saved address" },
                secondary = addressSummaryLine(address),
                onClick = { onSelect(address) },
            )
        }
    }
}

/** One-line "street, city, region postal" summary used under the address label. */
internal fun addressSummaryLine(address: Address): String =
    listOf(
        address.streetAddress.replace('\n', ' ').trim(),
        address.addressLevel2,
        listOf(address.addressLevel1, address.postalCode).filter { it.isNotBlank() }.joinToString(" "),
    ).filter { it.isNotBlank() }.joinToString(", ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutofillPickerScaffold(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun AutofillPickerRow(
    icon: ImageVector,
    primary: String,
    secondary: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
