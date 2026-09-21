package com.firebolt9907.tangentbrowser.sync.webauthn

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import java.lang.ref.WeakReference
import org.json.JSONObject

/** The currently foreground browser activity, used only while showing Credential Manager UI. */
object SystemPasskeyActivityRegistry {
    @Volatile private var activity = WeakReference<Activity>(null)

    fun attach(value: Activity) {
        activity = WeakReference(value)
    }

    fun detach(value: Activity) {
        if (activity.get() === value) activity.clear()
    }

    fun current(): Activity? = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
}

/**
 * Routes a browser WebAuthn ceremony through Android Credential Manager. Unlike GeckoView 153's
 * legacy FIDO2PrivilegedApiClient route, the selected credential provider performs the privileged
 * caller check, so a provider such as Bitwarden can honour its user-managed trusted-browser list.
 * Browser-origin delegation is available from Android 14 (API 34).
 */
class AndroidCredentialManagerPasskeyProvider : PasskeyProvider {
    override suspend fun getAssertion(request: PasskeyGetRequest): PasskeyOutcome {
        if (Build.VERSION.SDK_INT < 34) return PasskeyOutcome.Fallback
        val activity = SystemPasskeyActivityRegistry.current()
            ?: return PasskeyOutcome.Failed("NotAllowedError", "No foreground browser activity")
        val requestJson = request.credentialManagerRequestJson
            ?: return PasskeyOutcome.Failed("NotSupportedError", "Credential Manager request is missing")
        logRequestShape("get", request.origin, requestJson)
        return runCatching {
            val option = GetPublicKeyCredentialOption(
                requestJson = requestJson,
                clientDataHash = request.clientDataHash,
            )
            val response = CredentialManager.create(activity).getCredential(
                context = activity,
                request = GetCredentialRequest(
                    credentialOptions = listOf(option),
                    origin = request.origin,
                ),
            )
            val credential = response.credential as? PublicKeyCredential
                ?: error("Credential provider returned ${response.credential.type}")
            PasskeyOutcome.CredentialManagerAsserted(credential.authenticationResponseJson)
        }.getOrElse(::credentialManagerFailure)
    }

    override suspend fun makeCredential(request: PasskeyCreateRequest): PasskeyOutcome {
        if (Build.VERSION.SDK_INT < 34) return PasskeyOutcome.Fallback
        val activity = SystemPasskeyActivityRegistry.current()
            ?: return PasskeyOutcome.Failed("NotAllowedError", "No foreground browser activity")
        val requestJson = request.credentialManagerRequestJson
            ?: return PasskeyOutcome.Failed("NotSupportedError", "Credential Manager request is missing")
        logRequestShape("create", request.origin, requestJson)
        return runCatching {
            val rawResponse = CredentialManager.create(activity).createCredential(
                context = activity,
                request = CreatePublicKeyCredentialRequest(
                    requestJson = requestJson,
                    clientDataHash = request.clientDataHash,
                    origin = request.origin,
                ),
            )
            val response = rawResponse as? CreatePublicKeyCredentialResponse
                ?: error("Credential provider returned ${rawResponse.type}")
            PasskeyOutcome.CredentialManagerCreated(response.registrationResponseJson)
        }.getOrElse(::credentialManagerFailure)
    }

    private fun credentialManagerFailure(error: Throwable): PasskeyOutcome.Failed {
        Log.w(TAG, "Credential Manager ceremony failed", error)
        val name = if (error.javaClass.simpleName.contains("Cancel", ignoreCase = true)) {
            "NotAllowedError"
        } else {
            "UnknownError"
        }
        return PasskeyOutcome.Failed(name, error.message)
    }

    /** Log only matching metadata; challenges, user handles, and credential IDs stay out of logs. */
    private fun logRequestShape(operation: String, origin: String, requestJson: String) {
        runCatching {
            val request = JSONObject(requestJson)
            val rpId = if (operation == "create") {
                request.optJSONObject("rp")?.optString("id")
            } else {
                request.optString("rpId")
            }
            Log.i(
                TAG,
                "Credential Manager $operation origin=$origin rpId=$rpId " +
                    "allowCredentials=${request.optJSONArray("allowCredentials")?.length() ?: 0} " +
                    "extensions=${request.optJSONObject("extensions")?.keys()?.asSequence()?.toList().orEmpty()}",
            )
        }.onFailure { Log.w(TAG, "Could not inspect Credential Manager request shape", it) }
    }

    private companion object {
        const val TAG = "TangentPasskeys"
    }
}
