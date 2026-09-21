package com.firebolt9907.tangentbrowser.sync.webauthn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.WebExtension
import java.util.Collections
import java.util.WeakHashMap

/**
 * Owns the built-in **passkey-bridge** content-script extension (`assets/extensions/passkey/`) and
 * its native-messaging [PasskeyBridge]. Structured like
 * [com.firebolt9907.tangentbrowser.ui.reader.ReaderExtension]: install once at startup, register the
 * port per session/tab.
 *
 * Unlike the reader extension the bridge needs app-level collaborators (a provider and coroutine
 * scope), so [initialize] must be called before a session can be wired. Sessions encountered while
 * the asynchronous extension install is still running are retained weakly and registered when both
 * halves are ready.
 */
object PasskeyExtension {
    const val ID = "passkey@tangent.firebolt9907"
    private const val URL = "resource://android/assets/extensions/passkey/"

    private var extension: WebExtension? = null
    private var installing = false
    private val pendingSessions = Collections.newSetFromMap(
        WeakHashMap<EngineSession, Boolean>(),
    )

    @Volatile private var bridge: PasskeyBridge? = null

    /** Supply the vault provider + scope. Idempotent; the first caller wins. */
    fun initialize(scope: CoroutineScope, provider: PasskeyProvider) {
        synchronized(this) {
            if (bridge == null) bridge = PasskeyBridge(scope, provider)
        }
        registerPendingSessions()
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
                synchronized(this) {
                    extension = ext
                    installing = false
                }
                Log.i("TangentPasskeys", "Passkey content bridge installed")
                registerPendingSessions()
            },
            onError = {
                installing = false
                Log.w("TangentPasskeys", "Passkey content bridge installation failed", it)
            },
        )
    }

    /** Route this session's passkey-bridge port, or defer it until install/provider setup finishes. */
    fun registerFor(session: EngineSession) {
        val ready = synchronized(this) {
            val currentBridge = bridge
            val currentExtension = extension
            if (currentBridge == null || currentExtension == null) {
                pendingSessions += session
                null
            } else {
                currentBridge to currentExtension
            }
        }
        ready?.first?.registerHandler(session, ready.second)
    }

    private fun registerPendingSessions() {
        val ready = synchronized(this) {
            val currentBridge = bridge ?: return
            val currentExtension = extension ?: return
            val sessions = pendingSessions.toList()
            pendingSessions.clear()
            Triple(currentBridge, currentExtension, sessions)
        }
        ready.third.forEach { session ->
            ready.first.registerHandler(session, ready.second)
        }
    }
}
