package com.firebolt9907.tangentbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.firebolt9907.tangentbrowser.ui.theme.pressMorphShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.firebolt9907.tangentbrowser.ui.theme.headlineSmallEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.titleMediumEmphasized
import com.firebolt9907.tangentbrowser.ui.theme.titleSmallEmphasized
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.state.compactHost
import com.firebolt9907.tangentbrowser.ui.content.LoginFieldProbe
import mozilla.components.concept.storage.Login
import kotlin.math.abs

/**
 * The "pick a saved login to fill" sheet shown for [mozilla.components.concept.engine.prompt.PromptRequest.SelectLoginPrompt].
 * A Material 3 modal bottom sheet: each stored credential as a card with a color-keyed letter tile,
 * the username, the site host, and a masked password. Tapping a row runs the biometric gate (in
 * [PromptHandler]) before the credential is filled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectLoginSheet(
    logins: List<Login>,
    onSelect: (Login) -> Unit,
    onDismiss: () -> Unit,
    // When non-null, this credential has earned the unattended-autofill offer: it is the only match
    // on an enterprise identity provider and the user has already filled it by hand several times.
    // See [com.firebolt9907.tangentbrowser.state.AutofillTrustStore] for every condition. Null in the
    // ordinary case, which is what keeps this off individual websites.
    alwaysAutofillCandidate: Login? = null,
    onAlwaysAutofill: (Login) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Fill password",
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Unlock to fill your saved credentials",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(logins, key = { it.guid }) { login ->
                    LoginRow(login = login, onClick = { onSelect(login) })
                }
            }

            if (alwaysAutofillCandidate != null) {
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "You sign in here often",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tangent can fill this one automatically on " +
                        compactHost(alwaysAutofillCandidate.origin).ifBlank { "this sign-in service" } +
                        ", without asking. It applies to this saved login only, and stays off " +
                        "everywhere else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onAlwaysAutofill(alwaysAutofillCandidate) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Always autofill") }
            }
        }
    }
}

@Composable
private fun LoginRow(login: Login, onClick: () -> Unit) {
    val host = compactHost(login.origin).ifBlank { login.origin }
    val primary = login.username.ifBlank { host }.ifBlank { "Saved login" }

    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = pressMorphShape(interactionSource, resting = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LetterTile(label = primary)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (login.username.isNotBlank() && host.isNotBlank()) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "•".repeat(login.password.length.coerceIn(6, 12)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun LetterTile(label: String) {
    val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    val hue = (abs(label.hashCode()) % 360).toFloat()
    val tile = Color.hsl(hue, 0.45f, 0.55f)
    val onTile = if (tile.luminance() > 0.5f) Color.Black else Color.White
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tile),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = onTile,
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
    }
}

/**
 * `javascript:` payload injected into the page shortly after a login is filled, to press the form's
 * submit control — the "click login after autofill" behavior.
 *
 * Delegates to [LoginFieldProbe.buildSubmitScript], which shares the probe's *visibility-aware* field
 * locator. That sharing is the point: a hidden decoy password input (which every identifier-first IdP
 * keeps in the DOM) used to satisfy a plain `querySelector` here and stall the submit.
 */
internal fun buildLoginSubmitScript(
    stage: String = LoginFieldProbe.Stage.Password,
): String = LoginFieldProbe.buildSubmitScript(stage)
