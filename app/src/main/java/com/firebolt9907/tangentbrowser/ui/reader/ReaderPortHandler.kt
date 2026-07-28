package com.firebolt9907.tangentbrowser.ui.reader

import android.util.Log
import com.firebolt9907.tangentbrowser.util.TangentLog
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONArray
import org.json.JSONObject

/**
 * Receives article data from the reader-extractor content script over a native messaging Port.
 *
 * The content script (`extensions/reader/extractor.js`) runs in an isolated world the page cannot
 * observe, extracts the article, and calls `port.postMessage({type:"article", article:{...}})`.
 * This handler routes that JSON to [ReaderManager.setReaderEligible].
 *
 * Protocol (port name `"TangentReader"`):
 *   content → app : `{type:"article", article:{...}}` when an article is found
 *               `{type:"none"}` when the page isn't readable
 *   app → content : `{action:"extract"}` to re-run extraction on demand
 */
class ReaderPortHandler : MessageHandler {

    /** Active port per session. The key is the [EngineSession] used to register the handler. */
    private val ports = mutableMapOf<EngineSession, Port>()

    /** Maps each registered session to its tab ID so [onPortMessage] can route articles. */
    private val sessionTabIds = mutableMapOf<EngineSession, String>()

    /**
     * Register this handler for the given session/port-name pair. Safe to call repeatedly for the
     * same session — replaces the previous handler and disconnects any stale port.
     */
    fun registerHandler(
        session: EngineSession,
        tabId: String,
        extension: WebExtension,
    ) {
        val name = PORT_NAME
        // If we already have a handler for this session, disconnect the old port before replacing.
        val oldPort = ports.remove(session)
        if (oldPort != null) {
            try { oldPort.disconnect() } catch (_: Exception) {}
        }
        sessionTabIds[session] = tabId
        extension.registerContentMessageHandler(session, name, this)
        TangentLog.debug(TAG) { "registerHandler tab=$tabId session=$session" }
    }

    /**
     * Send an `{action:"extract"}` message to the content script for the given session, triggering
     * a re-extraction (used by the manual "Show Reader" button for SPAs / late-loading content).
     * Returns true iff a live port existed to receive it — callers use a false to fall back to the
     * javascript:-URL prompt-bridge extractor (e.g. the extension isn't installed / connected yet).
     */
    fun sendExtract(session: EngineSession): Boolean {
        val port = ports[session]
        if (port == null) {
            TangentLog.debug(TAG) { "sendExtract: no port for session=$session" }
            return false
        }
        return try {
            port.postMessage(JSONObject().put("action", "extract"))
            TangentLog.debug(TAG) { "sendExtract sent to session=$session" }
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendExtract failed", e)
            false
        }
    }

    /** True iff a live port exists for [session] (the content script has connected). */
    fun hasPort(session: EngineSession): Boolean = ports[session] != null

    /**
     * Push the compact per-site ruleset to the content script so its extraction applies the same
     * `include`/`strip` overrides as the javascript: path. The script re-extracts on receipt. Sent
     * on every (re)connect because a port is per-load; a null/blank ruleset is simply not sent.
     */
    private fun sendConfig(port: Port) {
        val rules = try { JSONArray(ReaderRules.cached()) } catch (e: Exception) { return }
        try {
            port.postMessage(JSONObject().put("action", "config").put("rules", rules))
        } catch (e: Exception) {
            Log.w(TAG, "sendConfig failed", e)
        }
    }

    override fun onPortConnected(port: Port) {
        val session = port.engineSession ?: return
        val oldPort = ports[session]
        if (oldPort != null && oldPort !== port) {
            try { oldPort.disconnect() } catch (_: Exception) {}
        }
        ports[session] = port
        val tabId = sessionTabIds[session] ?: "unknown"
        TangentLog.debug(TAG) { "onPortConnected tab=$tabId session=$session port=${port.name()}" }
        // Hand the content script the per-site rules as soon as it connects (before it can produce a
        // rule-aware extraction). Its initial document_end pass runs with an empty ruleset, then
        // re-runs on this config.
        sendConfig(port)
    }

    override fun onPortMessage(message: Any, port: Port) {
        val session = port.engineSession ?: return
        val tabId = sessionTabIds[session]
        if (tabId == null) {
            Log.w(TAG, "onPortMessage: no tabId for session=$session")
            return
        }
        if (message !is JSONObject) {
            Log.w(TAG, "onPortMessage: unexpected message type=${message.javaClass.simpleName}")
            return
        }
        val type = message.optString("type", "")
        when (type) {
            "article" -> {
                val articleJson = message.optJSONObject("article")
                if (articleJson != null) {
                    try {
                        val article = ReaderExtractor.parseArticleJson(articleJson.toString())
                        if (article != null) {
                            TangentLog.debug(TAG) { "onPortMessage: article received tab=$tabId title=${article.title.take(40)}" }
                            ReaderManager.instance.setReaderEligible(tabId, article)
                        } else {
                            TangentLog.debug(TAG) { "onPortMessage: parseArticleJson returned null tab=$tabId" }
                            ReaderManager.instance.reportReaderUnavailable(tabId)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "onPortMessage: failed to parse article tab=$tabId", e)
                        ReaderManager.instance.reportReaderUnavailable(tabId)
                    }
                }
            }
            "none" -> {
                TangentLog.debug(TAG) { "onPortMessage: page not readable tab=$tabId" }
                ReaderManager.instance.reportReaderUnavailable(tabId)
            }
            else -> {
                TangentLog.debug(TAG) { "onPortMessage: unknown type=$type tab=$tabId" }
            }
        }
    }

    override fun onPortDisconnected(port: Port) {
        val session = port.engineSession ?: return
        val removed = ports.remove(session)
        if (removed != null) {
            TangentLog.debug(TAG) { "onPortDisconnected session=$session" }
        }
    }

    companion object {
        private const val TAG = "ReaderPortHandler"
        const val PORT_NAME = "TangentReader"
    }
}
