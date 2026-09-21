package com.firebolt9907.tangentbrowser.sync.webauthn

/**
 * The vault-facing half of the passkey bridge: given a validated WebAuthn ceremony request (rpId
 * already checked against the page origin by [PasskeyBridge]), decide whether to service it and, if
 * so, run the crypto ([WebAuthnAuthenticator]) against stored key material.
 *
 * Kept as an interface so [PasskeyBridge] (pure transport + protocol) is testable without a live
 * vault, and so the concrete [com.firebolt9907.tangentbrowser.sync.bitwarden.BitwardenPasskeyProvider]
 * can inject its own UI (credential picker + biometric gate) without the bridge knowing about
 * Compose or Android.
 */
interface PasskeyProvider {
    /** Whether this provider represents a user-verifying platform authenticator. */
    val isUserVerifyingPlatformAuthenticatorAvailable: Boolean
        get() = true

    suspend fun getAssertion(request: PasskeyGetRequest): PasskeyOutcome
    suspend fun makeCredential(request: PasskeyCreateRequest): PasskeyOutcome
}

/** A `navigator.credentials.get()` request, post-validation. */
data class PasskeyGetRequest(
    val rpId: String,
    val origin: String,
    /** base64url credential ids from the RP's `allowCredentials` (empty = discoverable/any). */
    val allowCredentialIds: List<String>,
    /** SHA-256 of the page's `clientDataJSON` — what the assertion signature covers. */
    val clientDataHash: ByteArray,
    /** WebAuthn request-options JSON accepted by Android Credential Manager. */
    val credentialManagerRequestJson: String? = null,
)

/** A `navigator.credentials.create()` request, post-validation. */
data class PasskeyCreateRequest(
    val rpId: String,
    val rpName: String?,
    val origin: String,
    val userId: ByteArray?,
    val userName: String?,
    val userDisplayName: String?,
    /** SHA-256 of the page's `clientDataJSON`; browser providers must pass this to the authenticator. */
    val clientDataHash: ByteArray,
    /** WebAuthn creation-options JSON accepted by Android Credential Manager. */
    val credentialManagerRequestJson: String? = null,
)

/** What the user is being asked to confirm, so the gate can show a meaningful prompt. */
sealed interface PasskeyPrompt {
    val rpId: String
    val account: String?

    data class Verify(override val rpId: String, override val account: String?) : PasskeyPrompt
    data class Create(override val rpId: String, override val account: String?) : PasskeyPrompt
}

/** The result the bridge serializes back to the page. */
sealed interface PasskeyOutcome {
    /** Credential Manager's standard WebAuthn registration response JSON. */
    data class CredentialManagerCreated(val responseJson: String) : PasskeyOutcome

    /** Credential Manager's standard WebAuthn authentication response JSON. */
    data class CredentialManagerAsserted(val responseJson: String) : PasskeyOutcome

    /** Registration succeeded; return the new credential to the page. */
    data class Created(
        val credentialId: ByteArray,
        val attestationObject: ByteArray,
        val authenticatorData: ByteArray,
    ) : PasskeyOutcome

    /** Authentication succeeded; return the assertion to the page. */
    data class Asserted(
        val credentialId: ByteArray,
        val authenticatorData: ByteArray,
        val signature: ByteArray,
        val userHandle: ByteArray?,
    ) : PasskeyOutcome

    /** The vault can't/won't service this — let the native authenticator (or softtoken) handle it. */
    data object Fallback : PasskeyOutcome

    /** A terminal failure the page should see as a rejected promise (e.g. user cancelled). */
    data class Failed(val error: String, val message: String? = null) : PasskeyOutcome
}
