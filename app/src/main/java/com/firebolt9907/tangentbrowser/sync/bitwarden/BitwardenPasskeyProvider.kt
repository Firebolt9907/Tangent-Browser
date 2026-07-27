package com.firebolt9907.tangentbrowser.sync.bitwarden

import com.firebolt9907.tangentbrowser.sync.webauthn.PasskeyCreateRequest
import com.firebolt9907.tangentbrowser.sync.webauthn.PasskeyGetRequest
import com.firebolt9907.tangentbrowser.sync.webauthn.PasskeyOutcome
import com.firebolt9907.tangentbrowser.sync.webauthn.PasskeyPrompt
import com.firebolt9907.tangentbrowser.sync.webauthn.PasskeyProvider
import com.firebolt9907.tangentbrowser.sync.webauthn.WebAuthnAuthenticator
import java.util.UUID

/**
 * Serves WebAuthn ceremonies from the Bitwarden vault: reads/writes `fido2Credentials`
 * ([BitwardenPasskeyStore]) and runs the crypto ([WebAuthnAuthenticator]). The UI concerns — which
 * credential to use and the biometric confirmation — are injected so this class stays free of Compose
 * and Android context; the controller glue (module 5) supplies them with the real picker sheet and
 * [com.firebolt9907.tangentbrowser.ui.BiometricGate].
 *
 * Routing (the user's choice): try the vault first; when it holds no matching credential, return
 * [PasskeyOutcome.Fallback] so the page's native authenticator (or the softtoken) can take over.
 */
class BitwardenPasskeyProvider(
    private val controller: BitwardenController,
    /** Pick one candidate to authenticate with (the picker sheet); null = the user dismissed it. */
    private val chooseCredential: suspend (List<BitwardenPasskeyStore.Candidate>) -> BitwardenPasskeyStore.Candidate?,
    /** Confirm the user's presence/verification (biometric gate); false = cancelled/failed. */
    private val confirmUser: suspend (PasskeyPrompt) -> Boolean,
) : PasskeyProvider {

    override suspend fun getAssertion(request: PasskeyGetRequest): PasskeyOutcome {
        if (!controller.isUsable) return PasskeyOutcome.Fallback
        val candidates = controller.passkeyStore.findForRp(
            request.rpId,
            request.allowCredentialIds.ifEmpty { null },
        )
        if (candidates.isEmpty()) return PasskeyOutcome.Fallback

        val chosen = chooseCredential(candidates)
            ?: return PasskeyOutcome.Failed("NotAllowedError", "No passkey selected")
        if (!confirmUser(PasskeyPrompt.Verify(request.rpId, chosen.userName))) {
            return PasskeyOutcome.Failed("NotAllowedError", "User verification failed")
        }

        val assertion = WebAuthnAuthenticator.getAssertion(
            rpId = request.rpId,
            privateKeyPkcs8 = chosen.privateKeyPkcs8,
            clientDataHash = request.clientDataHash,
            userVerified = true,
            signCount = chosen.counter,
        )
        return PasskeyOutcome.Asserted(
            credentialId = chosen.credentialIdBytes,
            authenticatorData = assertion.authenticatorData,
            signature = assertion.signature,
            userHandle = chosen.userHandle,
        )
    }

    override suspend fun makeCredential(request: PasskeyCreateRequest): PasskeyOutcome {
        if (!controller.isUsable) return PasskeyOutcome.Fallback
        val userKey = controller.currentUserKey ?: return PasskeyOutcome.Fallback
        if (!confirmUser(PasskeyPrompt.Create(request.rpId, request.userName))) {
            return PasskeyOutcome.Failed("NotAllowedError", "User verification failed")
        }

        // Mint a GUID; its .NET-endian bytes are the RP-facing credential id, and encodeNewCipher
        // stores that same GUID string — so a later get() from any Bitwarden client matches.
        val guid = UUID.randomUUID()
        val credIdBytes = BitwardenPasskeyStore.guidToCredentialIdBytes(guid)
        val reg = WebAuthnAuthenticator.makeCredential(request.rpId, credentialId = credIdBytes)

        val body = controller.passkeyStore.encodeNewCipher(
            BitwardenPasskeyStore.NewPasskey(
                rpId = request.rpId,
                rpName = request.rpName,
                credentialIdBytes = credIdBytes,
                privateKeyPkcs8 = reg.privateKeyPkcs8,
                userHandle = request.userId,
                userName = request.userName,
                userDisplayName = request.userDisplayName,
            ),
            userKey,
        )
        controller.createPasskeyCipher(body)
            ?: return PasskeyOutcome.Failed("UnknownError", "Could not save the passkey to your vault")

        return PasskeyOutcome.Created(
            credentialId = credIdBytes,
            attestationObject = reg.attestationObject,
            authenticatorData = reg.authenticatorData,
        )
    }
}
