package com.firebolt9907.tangentbrowser.sync.webauthn

import android.util.Log
import com.google.common.net.InternetDomainName
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native half of the passkey bridge (`assets/extensions/passkey/passkey.js`). Receives WebAuthn
 * ceremony requests the content script relays over a native-messaging Port ("TangentPasskey"),
 * validates them, delegates the decision + crypto to a [PasskeyProvider], and posts the result back
 * over the same Port keyed by the request `id`.
 *
 * This class is pure transport + protocol + the ONE security check that matters: [effectiveRpId]
 * confirms the requested rpId is the page origin's host or a registrable parent of it, so a page can
 * never mint or read a credential scoped to another site. The origin itself is authoritative — the
 * content script stamps it from the frame's real `location.origin` (never page-supplied JSON).
 */
class PasskeyBridge(
    private val scope: CoroutineScope,
    private val provider: PasskeyProvider,
) : MessageHandler {

    private val ports = mutableMapOf<EngineSession, Port>()

    /** Register this handler for [session]. Idempotent; replaces any prior port for the session. */
    fun registerHandler(session: EngineSession, extension: WebExtension) {
        ports.remove(session)?.let { runCatching { it.disconnect() } }
        extension.registerContentMessageHandler(session, PORT_NAME, this)
    }

    override fun onPortConnected(port: Port) {
        val session = port.engineSession ?: return
        ports[session]?.takeIf { it !== port }?.let { runCatching { it.disconnect() } }
        ports[session] = port
        Log.i(TAG, "Passkey content bridge connected")
    }

    override fun onPortDisconnected(port: Port) {
        port.engineSession?.let { ports.remove(it) }
    }

    override fun onPortMessage(message: Any, port: Port) {
        val obj = message as? JSONObject ?: return
        val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return
        Log.i(TAG, "Received ${obj.optString("op")} ceremony")
        scope.launch {
            val reply = runCatching { handle(obj) }
                .getOrElse { failure("UnknownError", it.message) }
            runCatching {
                Log.i(TAG, "Returning ceremony result ok=${reply.optBoolean("ok")}")
                port.postMessage(reply.put("id", id))
            }
                .onFailure { Log.w(TAG, "postMessage failed", it) }
        }
    }

    internal suspend fun handle(obj: JSONObject): JSONObject {
        val origin = obj.optString("origin").takeIf { it.isNotEmpty() }
            ?: return failure("SecurityError", "missing origin")
        val rpId = effectiveRpId(origin, obj.optString("rpId").takeIf { it.isNotEmpty() })
            ?: return failure("SecurityError", "rpId is not valid for origin")

        return when (obj.optString("op")) {
            "uvpaa" -> JSONObject()
                .put("ok", true)
                .put("available", provider.isUserVerifyingPlatformAuthenticatorAvailable)
            "get" -> {
                val clientDataJSON = obj.optString("clientDataJSON")
                    .takeIf { it.isNotEmpty() } ?: return failure("UnknownError", "missing clientDataJSON")
                val request = PasskeyGetRequest(
                    rpId = rpId,
                    origin = origin,
                    allowCredentialIds = obj.optJSONArray("allowCredentials").toStringList(),
                    clientDataHash = sha256(clientDataJSON.toByteArray(Charsets.UTF_8)),
                    credentialManagerRequestJson = boundRequestJson(obj, rpId, create = false),
                )
                reply(provider.getAssertion(request))
            }
            "create" -> {
                val request = PasskeyCreateRequest(
                    rpId = rpId,
                    rpName = obj.optString("rpName").takeIf { it.isNotEmpty() },
                    origin = origin,
                    userId = obj.optString("userId").takeIf { it.isNotEmpty() }?.let { decodeB64Url(it) },
                    userName = obj.optString("userName").takeIf { it.isNotEmpty() },
                    userDisplayName = obj.optString("userDisplayName").takeIf { it.isNotEmpty() },
                    clientDataHash = obj.optString("clientDataJSON")
                        .takeIf { it.isNotEmpty() }
                        ?.let { sha256(it.toByteArray(Charsets.UTF_8)) }
                        ?: return failure("UnknownError", "missing clientDataJSON"),
                    credentialManagerRequestJson = boundRequestJson(obj, rpId, create = true),
                )
                reply(provider.makeCredential(request))
            }
            else -> failure("UnknownError", "unknown op")
        }
    }

    companion object {
        const val PORT_NAME = "TangentPasskey"
        private const val TAG = "PasskeyBridge"

        private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        // Providers consume requestJson rather than request.rpId. Keep that second representation
        // bound to the checked RP, even if page-owned option getters returned different values
        // when the isolated script read them twice. Never forward an unchecked RP to a provider.
        private fun boundRequestJson(obj: JSONObject, rpId: String, create: Boolean): String? {
            val raw = obj.optString("requestJson").takeIf { it.isNotEmpty() } ?: return null
            val request = JSONObject(raw)
            if (create) {
                val rp = request.optJSONObject("rp") ?: JSONObject()
                request.put("rp", rp.put("id", rpId))
            } else {
                request.put("rpId", rpId)
            }
            return request.toString()
        }

        /**
         * The rpId the ceremony may use, or null if the request must be rejected. Per WebAuthn §5.1.3,
         * the rpId must equal the origin's effective domain or be a registrable parent of it
         * (`accounts.example.com` may claim `example.com`). If the page supplied no rpId, it defaults
         * to the origin's host. Only https (and localhost for dev) origins are honoured.
         */
        internal fun effectiveRpId(origin: String, requestedRpId: String?): String? {
            val uri = runCatching { URI(origin) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val isLocalhost = host == "localhost" || host == "127.0.0.1"
            if (uri.scheme != "https" && !(uri.scheme == "http" && isLocalhost)) return null
            if (uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() ||
                uri.rawQuery != null || uri.rawFragment != null || uri.port > 65535
            ) return null
            val rpId = requestedRpId?.lowercase() ?: host
            if (isLocalhost) return rpId.takeIf { it == host }
            val domain = runCatching { InternetDomainName.from(rpId) }.getOrNull() ?: return null
            // Exact-host RPs (including internal names) are allowed. Broadening to a parent must
            // stop below the full PSL, including private suffixes such as github.io.
            if (host == rpId) return rpId
            return rpId.takeIf { host.endsWith(".$it") && domain.isUnderPublicSuffix }
        }

        /** Serialize a provider outcome to the wire JSON (the bridge adds the `id`). */
        internal fun reply(outcome: PasskeyOutcome): JSONObject = when (outcome) {
            is PasskeyOutcome.CredentialManagerCreated -> JSONObject()
                .put("ok", true)
                .put("credentialManagerResponse", outcome.responseJson)
            is PasskeyOutcome.CredentialManagerAsserted -> JSONObject()
                .put("ok", true)
                .put("credentialManagerResponse", outcome.responseJson)
            is PasskeyOutcome.Created -> JSONObject()
                .put("ok", true)
                .put("credentialId", b64Url(outcome.credentialId))
                .put("attestationObject", b64Url(outcome.attestationObject))
                .put("authenticatorData", b64Url(outcome.authenticatorData))
            is PasskeyOutcome.Asserted -> JSONObject()
                .put("ok", true)
                .put("credentialId", b64Url(outcome.credentialId))
                .put("authenticatorData", b64Url(outcome.authenticatorData))
                .put("signature", b64Url(outcome.signature))
                .put("userHandle", outcome.userHandle?.let { b64Url(it) } ?: JSONObject.NULL)
            PasskeyOutcome.Fallback -> JSONObject().put("ok", false).put("fallback", true)
            is PasskeyOutcome.Failed -> failure(outcome.error, outcome.message)
        }

        private fun failure(error: String, message: String?): JSONObject =
            JSONObject().put("ok", false).put("error", error).put("message", message ?: JSONObject.NULL)

        private fun b64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun decodeB64Url(s: String): ByteArray =
            Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_').trimEnd('='))

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
            return out
        }
    }
}
