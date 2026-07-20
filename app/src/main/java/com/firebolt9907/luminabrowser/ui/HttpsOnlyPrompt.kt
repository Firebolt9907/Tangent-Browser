package com.firebolt9907.luminabrowser.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.firebolt9907.luminabrowser.engine.LuminaBrowserComponents
import com.firebolt9907.luminabrowser.state.hostOf

private data class HttpsOnlyRequest(val host: String, val httpUrl: String)

/**
 * "This site can't load securely — open it over HTTP?" prompt for HTTPS-Only mode.
 *
 * GeckoView's built-in HTTPS-Only error page (with its "Continue to HTTP Site" control) renders cut
 * off behind our chrome insets, leaving the user stranded. So [com.firebolt9907.luminabrowser.engine.LuminaRequestInterceptor]
 * signals the failure through [LuminaBrowserComponents.httpsOnlyFailures] and we surface this in-app
 * dialog instead. Confirming records a per-host exception (via
 * [com.firebolt9907.luminabrowser.engine.HttpsOnlyExceptionController.allow]) so future visits skip
 * both the failure and this prompt, then reloads the tab over HTTP.
 */
@Composable
internal fun HttpsOnlyExceptionDialog(components: LuminaBrowserComponents) {
    val prefs = components.globalPreferencesStore
    var pending by remember { mutableStateOf<HttpsOnlyRequest?>(null) }

    LaunchedEffectCollectFailures(components) { uri ->
        val host = hostOf(uri) ?: return@LaunchedEffectCollectFailures
        val httpUrl = toHttpUrl(uri)
        if (prefs.isHttpAllowedForHost(host)) {
            // Already approved (e.g. approval landed mid-load): retry over HTTP without re-prompting.
            components.httpsOnlyExceptionController.allow(host)
            components.tabRepository.loadUrl(httpUrl)
        } else {
            pending = HttpsOnlyRequest(host, httpUrl)
        }
    }

    val request = pending ?: return
    AlertDialog(
        onDismissRequest = { pending = null },
        icon = { Icon(imageVector = Icons.Filled.NoEncryption, contentDescription = null) },
        title = { Text("Open ${request.host} over HTTP?") },
        text = {
            Text(
                "HTTPS-Only Mode is on, but ${request.host} couldn't be reached securely. This is " +
                    "common for local or self-hosted sites. Loading it over HTTP is not encrypted.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val host = request.host
                val httpUrl = request.httpUrl
                pending = null
                components.httpsOnlyExceptionController.allow(host)
                components.tabRepository.loadUrl(httpUrl)
            }) {
                Text("Load over HTTP")
            }
        },
        dismissButton = {
            TextButton(onClick = { pending = null }) { Text("Cancel") }
        },
    )
}

/**
 * Collects [LuminaBrowserComponents.httpsOnlyFailures] for the life of the composition. Split out so
 * the dialog body stays declarative; keyed on `components` so a graph swap re-subscribes.
 */
@Composable
private fun LaunchedEffectCollectFailures(
    components: LuminaBrowserComponents,
    onFailure: suspend (String) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(components) {
        components.httpsOnlyFailures.collect { onFailure(it) }
    }
}

/** Force [uri] onto the `http` scheme, preserving host/path/query. */
private fun toHttpUrl(uri: String): String = when {
    uri.startsWith("https://", ignoreCase = true) -> "http://" + uri.substring("https://".length)
    uri.startsWith("http://", ignoreCase = true) -> uri
    else -> "http://$uri"
}
