package com.firebolt9907.luminabrowser.ui.chrome

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Voice search via the system speech recognizer — the same permission-less delegation pattern as the
 * SMS one-time-code fetcher: we fire [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] and Google's speech
 * activity owns the microphone in its own process, so this app declares **no** `RECORD_AUDIO`
 * permission. The transcribed query comes back through the activity result.
 *
 * Returns a launcher lambda to invoke on mic tap, or `null` when no speech-recognition service is
 * installed (so callers can hide the mic button entirely).
 */
@Composable
fun rememberVoiceSearchLauncher(onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    if (!available) return null

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) onResult(spoken)
        }
    }

    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Search or say a website")
        }
        runCatching { launcher.launch(intent) }
    }
}
