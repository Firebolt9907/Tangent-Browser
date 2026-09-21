package com.firebolt9907.tangentbrowser.sync.webauthn

import android.app.Activity
import android.util.Log
import com.yubico.yubikit.fido.android.ui.FidoClient
import com.yubico.yubikit.fido.android.ui.Origin
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The activity-bound FIDO client used by the currently foreground browser surface.
 *
 * YubiKit's client uses the Activity Result API, so it has to be constructed by the activity before
 * `onStart`; a provider created with the engine cannot construct it lazily during a ceremony.
 */
object HardwareSecurityKeyClientRegistry {
    private var activity = WeakReference<Activity>(null)
    private var client = WeakReference<FidoClient>(null)

    @Synchronized
    fun attach(owner: Activity, value: FidoClient) {
        activity = WeakReference(owner)
        client = WeakReference(value)
    }

    @Synchronized
    fun detach(owner: Activity) {
        if (activity.get() === owner) {
            activity.clear()
            client.clear()
        }
    }

    @Synchronized
    fun current(): FidoClient? {
        activity.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return null
        return client.get()
    }
}

/**
 * WebAuthn provider for genuine roaming FIDO authenticators connected over USB or NFC.
 *
 * Despite the SDK name this is not YubiKey-specific: the high-level client discovers generic
 * FIDO2/CTAP authenticators. It talks CTAP directly instead of relying on Android Credential
 * Manager's provider sheet, which does not reliably expose roaming keys for a third-party browser
 * using delegated web origins.
 */
class HardwareSecurityKeyPasskeyProvider : PasskeyProvider {
    // A USB/NFC token is a roaming (cross-platform) authenticator, not a platform authenticator.
    override val isUserVerifyingPlatformAuthenticatorAvailable: Boolean = false

    override suspend fun getAssertion(request: PasskeyGetRequest): PasskeyOutcome = runCeremony {
        val requestJson = request.credentialManagerRequestJson
            ?: return@runCeremony Result.failure(MissingRequestException())
        getAssertion(
            origin = Origin(request.origin),
            request = requestJson,
            clientDataHash = request.clientDataHash.toHex(),
        )
    }.fold(
        onSuccess = { PasskeyOutcome.CredentialManagerAsserted(it) },
        onFailure = ::failure,
    )

    override suspend fun makeCredential(request: PasskeyCreateRequest): PasskeyOutcome = runCeremony {
        val requestJson = request.credentialManagerRequestJson
            ?: return@runCeremony Result.failure(MissingRequestException())
        makeCredential(
            origin = Origin(request.origin),
            request = requestJson,
            clientDataHash = request.clientDataHash.toHex(),
        )
    }.fold(
        onSuccess = { PasskeyOutcome.CredentialManagerCreated(it) },
        onFailure = ::failure,
    )

    /** FidoClient requires both lookup and invocation on the main thread. */
    private suspend fun runCeremony(
        block: suspend FidoClient.() -> Result<String>,
    ): Result<String> = withContext(Dispatchers.Main.immediate) {
        val client = HardwareSecurityKeyClientRegistry.current()
            ?: return@withContext Result.failure(NoForegroundClientException())
        runCatching { client.block().getOrThrow() }
    }

    private fun failure(error: Throwable): PasskeyOutcome.Failed {
        Log.w(TAG, "Hardware security-key ceremony failed", error)
        val webError = when {
            error is CancellationException -> "NotAllowedError"
            error is MissingRequestException -> "NotSupportedError"
            error is NoForegroundClientException -> "NotAllowedError"
            error.javaClass.simpleName.contains("Cancel", ignoreCase = true) -> "NotAllowedError"
            error.javaClass.simpleName.contains("NotSupported", ignoreCase = true) -> "NotSupportedError"
            error.javaClass.simpleName.contains("Security", ignoreCase = true) -> "SecurityError"
            else -> "UnknownError"
        }
        return PasskeyOutcome.Failed(webError, error.message)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private class MissingRequestException : Exception("WebAuthn request JSON is missing")
    private class NoForegroundClientException : Exception("No foreground security-key client")

    private companion object {
        const val TAG = "TangentSecurityKey"
    }
}
