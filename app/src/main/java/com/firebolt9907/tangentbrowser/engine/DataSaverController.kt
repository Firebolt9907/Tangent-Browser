package com.firebolt9907.tangentbrowser.engine

import android.content.Context
import com.firebolt9907.tangentbrowser.state.DataSaverPolicy
import com.firebolt9907.tangentbrowser.state.ReaderHostEvidence
import com.firebolt9907.tangentbrowser.state.siteKeyOf
import com.firebolt9907.tangentbrowser.state.isDataSaverActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension

/**
 * Owns the built-in **data saver** blocking extension (`assets/extensions/datasaver/`) and the policy
 * it enforces. Installed like [com.firebolt9907.tangentbrowser.sync.webauthn.PasskeyExtension] and
 * [com.firebolt9907.tangentbrowser.ui.reader.ReaderExtension], but talks over the extension's
 * **background** port rather than a per-session content-script port — blocking `webRequest` lives in
 * a persistent background page and applies process-wide, so there is exactly one port and one policy.
 *
 * Everything the extension does is driven from here: it ships inert and blocks nothing until it
 * receives a policy. [recompute] is the single write path, and is cheap enough to call on any signal
 * that could have changed the answer (resume, connectivity change, reader extraction outcome, the
 * user asking for a full page).
 *
 * See [DataSaverPolicy] for *why* the blocking is request-shaped rather than
 * `setAllowJavascript(false)`, and [ReaderHostEvidence] for why the aggressive tier needs no
 * speculative load-then-reload.
 */
class DataSaverController(
    private val context: Context,
    private val evidence: ReaderHostEvidence,
    /**
     * The compact per-site reader-rules JSON, read fresh on each recompute. A supplier rather than a
     * value because the rules asset loads asynchronously at startup (and can be replaced by a signed
     * remote update mid-session), so there is no single moment at construction when it is ready.
     */
    private val readerRulesJson: () -> String,
) {
    companion object {
        const val ID = "datasaver@tangent.firebolt9907"
        private const val URL = "resource://android/assets/extensions/datasaver/"
        /**
         * Must match `PORT_NAME` in `assets/extensions/datasaver/datasaver.js` — a mismatch means the
         * port never connects and the extension stays inert forever, with no error anywhere. Not
         * private so `DataSaverExtensionAssetTest` can pin the two together.
         */
        internal const val PORT_NAME = "TangentDataSaver"
    }

    private var extension: WebExtension? = null
    private var installing = false
    private var port: Port? = null

    /**
     * Hosts the user has asked to load in full. Session-scoped: a fresh launch starts clean.
     *
     * Copy-on-write behind `@Volatile` rather than a mutable set — it is written from Compose (the aA
     * sheet toggle, main thread) and read by [recompute], which also runs on the extension's port
     * callback, so a plain `MutableSet` could be iterated while being mutated.
     */
    @Volatile private var exemptHosts: Set<String> = emptySet()

    private val _active = MutableStateFlow(false)
    /** True while the extension is actually cancelling requests. Drives the UI affordance. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    /** Requests cancelled since launch, as reported by the extension. */
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    /** Install the extension once (idempotent) and push the current policy as soon as it connects. */
    fun install(engine: Engine) {
        if (extension != null || installing) return
        installing = true
        engine.installBuiltInWebExtension(
            id = ID,
            url = URL,
            onSuccess = { ext ->
                extension = ext
                installing = false
                ext.registerBackgroundMessageHandler(PORT_NAME, handler)
            },
            onError = { installing = false },
        )
    }

    private val handler = object : MessageHandler {
        override fun onPortConnected(port: Port) {
            this@DataSaverController.port = port
            // The background page connects after install and again after any extension-process
            // restart, and it comes up inert both times — so the first thing a new port needs is the
            // current policy, not the next state change.
            recompute()
        }

        override fun onPortDisconnected(port: Port) {
            if (this@DataSaverController.port === port) this@DataSaverController.port = null
        }

        override fun onPortMessage(message: Any, port: Port) {
            val json = message as? org.json.JSONObject ?: return
            if (json.optString("type") == "stats") {
                _blockedCount.value = json.optInt("blocked", _blockedCount.value)
            }
        }
    }

    /**
     * Re-derive the policy from the OS Data Saver signal plus current host state and push it. Safe to
     * call from anywhere and at any frequency — it is a couple of set reads and one port message, and
     * no-ops entirely when the extension hasn't connected yet.
     */
    fun recompute() {
        evidence.seed(readerRulesJson())
        val policy = DataSaverPolicy.compute(
            active = isDataSaverActive(context),
            readerProvenHosts = evidence.provenHosts(),
            exemptHosts = exemptHosts,
        )
        _active.value = policy.enabled
        runCatching { port?.postMessage(policy.toJson()) }
    }

    /** True when [url]'s host is currently exempt from blocking. */
    fun isExempt(url: String?): Boolean = siteKeyOf(url)?.let { exemptHosts.contains(it) } == true

    /**
     * Stop blocking on [url]'s host for the rest of the session — the "Load full page" escape hatch.
     * Returns false when the URL has no host to exempt, so the caller can skip the reload.
     */
    fun exempt(url: String?): Boolean {
        val host = siteKeyOf(url) ?: return false
        if (host in exemptHosts) return false
        exemptHosts = exemptHosts + host
        recompute()
        return true
    }

    /** Re-apply blocking to [url]'s host. */
    fun unexempt(url: String?) {
        val host = siteKeyOf(url) ?: return
        if (host !in exemptHosts) return
        exemptHosts = exemptHosts - host
        recompute()
    }

    /**
     * Reader extraction produced a real article on [url] — the host joins the aggressive tier, so its
     * *next* visit also skips first-party script and CSS.
     */
    fun onReaderSucceeded(url: String?) {
        evidence.recordSuccess(url)
        recompute()
    }

    /**
     * Reader extraction found nothing usable on [url]. Demotes the host out of the aggressive tier so
     * the next visit gets its scripts back. Only meaningful for a host that was *in* that tier — a
     * failure on an ordinary host changes nothing, and recomputing is cheap either way.
     */
    fun onReaderFailed(url: String?) {
        evidence.recordFailure(url)
        recompute()
    }
}
