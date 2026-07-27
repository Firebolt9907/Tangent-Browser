package com.firebolt9907.tangentbrowser.sync.webauthn

import kotlinx.coroutines.CoroutineScope
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.WebExtension

/**
 * Owns the built-in **passkey-bridge** content-script extension (`assets/extensions/passkey/`) and
 * its native-messaging [PasskeyBridge]. Structured like
 * [com.firebolt9907.tangentbrowser.ui.reader.ReaderExtension]: install once at startup, register the
 * port per session/tab.
 *
 * Unlike the reader extension the bridge needs app-level collaborators (the vault provider, a
 * coroutine scope), so [initialize] must be called before [registerFor] does anything — the
 * controller glue (module 5) supplies a [PasskeyProvider] backed by the Bitwarden vault plus the
 * picker + biometric UI. Until then the override still installs in-page but every ceremony resolves
 * to native fallback, so passwords/passkeys keep working.
 */
object PasskeyExtension {
    const val ID = "passkey@tangent.firebolt9907"
    private const val URL = "resource://android/assets/extensions/passkey/"

    private var extension: WebExtension? = null
    private var installing = false

    @Volatile private var bridge: PasskeyBridge? = null

    /** Supply the vault provider + scope. Idempotent; the first caller wins. */
    fun initialize(scope: CoroutineScope, provider: PasskeyProvider) {
        if (bridge == null) bridge = PasskeyBridge(scope, provider)
    }

    /** True once both the extension is installed and a provider has been wired. */
    fun isReady(): Boolean = extension != null && bridge != null

    /** Install the extension once (idempotent). Safe before/after the engine has sessions. */
    fun install(engine: Engine) {
        if (extension != null || installing) return
        installing = true
        engine.installBuiltInWebExtension(
            id = ID,
            url = URL,
            onSuccess = { ext ->
                extension = ext
                installing = false
            },
            onError = { installing = false },
        )
    }

    /** Route this session's passkey-bridge port to the native handler. No-op until installed+wired. */
    fun registerFor(session: EngineSession) {
        val ext = extension ?: return
        bridge?.registerHandler(session, ext)
    }
}
