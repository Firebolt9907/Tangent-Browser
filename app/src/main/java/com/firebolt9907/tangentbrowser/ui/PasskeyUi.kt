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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.firebolt9907.tangentbrowser.state.compactHost
import com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenPasskeyStore
import com.firebolt9907.tangentbrowser.sync.webauthn.PasskeyPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the [com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenPasskeyProvider]'s two
 * injected suspend lambdas — which run off the UI on a bridge coroutine — to the Compose UI mounted
 * in [PasskeyUiHost]. Each request parks on a [CompletableDeferred] the host resolves when the user
 * responds. Only one ceremony runs at a time (a passkey ceremony is user-initiated), so a single-slot
 * StateFlow per stage is enough.
 */
object PasskeyUiController {

    data class PickerRequest(
        val rpId: String,
        val candidates: List<BitwardenPasskeyStore.Candidate>,
        val deferred: CompletableDeferred<BitwardenPasskeyStore.Candidate?>,
    )

    data class VerifyRequest(
        val prompt: PasskeyPrompt,
        val deferred: CompletableDeferred<Boolean>,
    )

    private val _picker = MutableStateFlow<PickerRequest?>(null)
    val picker: StateFlow<PickerRequest?> = _picker.asStateFlow()

    private val _verify = MutableStateFlow<VerifyRequest?>(null)
    val verify: StateFlow<VerifyRequest?> = _verify.asStateFlow()

    /** Choose a credential. A single candidate resolves immediately (no sheet) — the biometric gate
     *  that follows is confirmation enough; multiple candidates raise the picker. */
    suspend fun pick(
        candidates: List<BitwardenPasskeyStore.Candidate>,
    ): BitwardenPasskeyStore.Candidate? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val deferred = CompletableDeferred<BitwardenPasskeyStore.Candidate?>()
        _picker.value = PickerRequest(candidates.first().rpId, candidates, deferred)
        return try { deferred.await() } finally { _picker.value = null }
    }

    fun resolvePick(candidate: BitwardenPasskeyStore.Candidate?) {
        _picker.value?.deferred?.complete(candidate)
    }

    /** Confirm user presence/verification via the biometric gate. */
    suspend fun requestVerify(prompt: PasskeyPrompt): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        _verify.value = VerifyRequest(prompt, deferred)
        return try { deferred.await() } finally { _verify.value = null }
    }

    fun resolveVerify(confirmed: Boolean) {
        _verify.value?.deferred?.complete(confirmed)
    }
}

/**
 * Mount once per foreground surface (next to `PromptHandler` in `TangentOverlaySheets` and
 * `CustomTabRoot`). Renders the credential picker and drives [BiometricGate] for the confirmation
 * step, resolving [PasskeyUiController]'s parked ceremonies. Inert unless a Bitwarden-backed passkey
 * ceremony is in flight.
 */
@Composable
fun PasskeyUiHost() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickerRequest by PasskeyUiController.picker.collectAsStateWithLifecycle()
    val verifyRequest by PasskeyUiController.verify.collectAsStateWithLifecycle()

    pickerRequest?.let { request ->
        PasskeyPickerSheet(
            request = request,
            onSelect = { PasskeyUiController.resolvePick(it) },
            onDismiss = { PasskeyUiController.resolvePick(null) },
        )
    }

    androidx.compose.runtime.LaunchedEffect(verifyRequest) {
        val request = verifyRequest ?: return@LaunchedEffect
        val creating = request.prompt is PasskeyPrompt.Create
        BiometricGate.authenticate(
            context = context,
            title = if (creating) "Create passkey" else "Use passkey",
            subtitle = buildString {
                append(request.prompt.rpId)
                request.prompt.account?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            },
            onSuccess = { PasskeyUiController.resolveVerify(true) },
            onFailure = { PasskeyUiController.resolveVerify(false) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasskeyPickerSheet(
    request: PasskeyUiController.PickerRequest,
    onSelect: (BitwardenPasskeyStore.Candidate) -> Unit,
    onDismiss: () -> Unit,
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
                text = "Choose a passkey",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sign in to ${request.rpId} with your Bitwarden vault",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(request.candidates, key = { it.cipherId + it.credentialIdB64Url }) { candidate ->
                    PasskeyRow(candidate = candidate, onClick = { onSelect(candidate) })
                }
            }
        }
    }
}

@Composable
private fun PasskeyRow(candidate: BitwardenPasskeyStore.Candidate, onClick: () -> Unit) {
    val host = compactHost("https://${candidate.rpId}").ifBlank { candidate.rpId }
    val primary = candidate.userDisplayName?.takeIf { it.isNotBlank() }
        ?: candidate.userName?.takeIf { it.isNotBlank() }
        ?: host

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (primary != host) {
                    Text(
                        text = host,
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
