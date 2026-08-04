package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.engine.DataSaverController
import com.firebolt9907.tangentbrowser.state.DataSaverPolicy
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract between [DataSaverPolicy] (which builds the policy JSON) and the blocking
 * extension asset (which reads it). The two are joined only by string keys over a native port, so
 * renaming a field on the Kotlin side compiles perfectly and silently leaves the extension blocking
 * nothing — a failure with no error and no symptom other than the data bill.
 *
 * Also pins the two properties that make the blocking safe to ship: the never-block list (cancelling
 * `main_frame` would break navigation outright, `xmlhttprequest` would break every SPA), and the
 * fail-open behavior on port loss.
 */
class DataSaverExtensionAssetTest {

    private fun assetFile(name: String): File {
        // `:app:test` runs from the module dir; tolerate the repo root too.
        for (p in listOf(
            "src/main/assets/extensions/datasaver/$name",
            "app/src/main/assets/extensions/datasaver/$name",
        )) {
            val f = File(p)
            if (f.isFile) return f
        }
        throw AssertionError("datasaver/$name not found from working dir ${File(".").absolutePath}")
    }

    private val script by lazy { assetFile("datasaver.js").readText() }
    private val manifest by lazy { assetFile("manifest.json").readText() }

    @Test
    fun `script reads every key the policy writes`() {
        val json = DataSaverPolicy.compute(true, setOf("nytimes.com"), setOf("example.com")).toJson()
        for (key in json.keys()) {
            assertTrue(
                "policy key '$key' is written by DataSaverPolicy but never read by datasaver.js",
                script.contains("'$key'") || script.contains("\"$key\"") || script.contains(".$key"),
            )
        }
    }

    @Test
    fun `script connects on the port name the controller registers`() {
        assertTrue(
            "port name drift leaves the extension permanently inert, silently",
            script.contains("'${DataSaverController.PORT_NAME}'"),
        )
    }

    @Test
    fun `extension id matches the controller`() {
        assertTrue(manifest.contains("\"${DataSaverController.ID}\""))
    }

    @Test
    fun `script never cancels documents or XHR`() {
        // These are the request types whose cancellation breaks the page rather than slimming it.
        for (type in listOf("main_frame", "sub_frame", "xmlhttprequest", "websocket")) {
            assertTrue("$type must be in NEVER_BLOCK", script.contains("$type: true"))
        }
    }

    @Test
    fun `script fails open when the host port drops`() {
        assertTrue(
            "onDisconnect must disable blocking — a dead controller must not leave pages stripped",
            script.contains("onDisconnect") && script.contains("policy.enabled = false"),
        )
    }

    @Test
    fun `manifest requests blocking webRequest on all urls`() {
        for (permission in listOf("webRequest", "webRequestBlocking", "<all_urls>", "nativeMessaging")) {
            assertTrue("manifest must request $permission", manifest.contains("\"$permission\""))
        }
    }

    @Test
    fun `manifest background page is persistent`() {
        // A blocking webRequest listener cannot live in a non-persistent (event) page: the listener
        // has to be registered before the first request it is meant to cancel.
        assertTrue(manifest.contains("\"persistent\": true"))
    }
}
