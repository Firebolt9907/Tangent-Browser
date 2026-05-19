package com.firebolt9907.luminabrowser

import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.firebolt9907.luminabrowser.ui.LuminaRoot
import mozilla.components.concept.engine.activity.ActivityDelegate

class MainActivity : ComponentActivity(), ActivityDelegate {

    private val app get() = application as LuminaApplication
    private var pendingIntentSenderResult: ((Intent?) -> Unit)? = null
    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        pendingIntentSenderResult?.invoke(result.data)
        pendingIntentSenderResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent { LuminaRoot() }
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.components.engine.registerActivityDelegate(this)
    }

    override fun onStop() {
        super.onStop()
        app.components.engine.unregisterActivityDelegate()
    }

    override fun startIntentSenderForResult(intent: IntentSender, onResult: (Intent?) -> Unit) {
        pendingIntentSenderResult = onResult
        runCatching {
            intentSenderLauncher.launch(IntentSenderRequest.Builder(intent).build())
        }.onFailure {
            pendingIntentSenderResult = null
            onResult(null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val url = extractUrl(intent) ?: return
        if (app.components.firefoxSyncController.handleOAuthRedirect(url)) return
        app.components.tabRepository.addTab(url = url, title = "")
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.isNotBlank() }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isEmpty()) null else firstUrlIn(text) ?: text
            }
            else -> null
        }
    }

    private fun firstUrlIn(text: String): String? {
        val match = Regex("https?://\\S+").find(text) ?: return null
        return match.value
    }
}
