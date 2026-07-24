package com.firebolt9907.tangentbrowser

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * Drives Play Services' **SMS User Consent** flow for one-time-code (2FA) autofill. No SMS
 * permission is involved: we ask Play Services to watch for one incoming message; when it arrives it
 * shows the user a one-tap "allow reading this message?" system dialog, then hands us the full text,
 * from which we pull the code.
 *
 * Wired to the page side via [com.firebolt9907.tangentbrowser.ui.content.OtpAutofill]: the browser
 * calls [startListening] when a page reports an OTP field, and forwards the extracted code back
 * through [onCode] (→ `OtpAutofill.deliverCode`, which injects the fill script).
 *
 * The consent launcher is registered at construction time (an Activity field), which is a valid
 * point to call `registerForActivityResult`. The broadcast receiver is registered lazily on first
 * use and torn down in [dispose].
 */
class OtpSmsController(
    private val activity: ComponentActivity,
    private val onCode: (String) -> Unit,
) {

    private var receiver: BroadcastReceiver? = null

    // Debounce: OTP-field focus can fire repeatedly; each startSmsUserConsent resets the 5-min
    // window, so collapse a burst into one arm.
    private var lastStartMs = 0L

    private val consentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
            val code = message?.let {
                com.firebolt9907.tangentbrowser.ui.content.OtpAutofill.extractCode(it)
            }
            if (code != null) {
                runCatching { onCode(code) }
            }
        }
    }

    /** Arm Play Services to watch for the next OTP SMS. Safe to call repeatedly (debounced). */
    fun startListening() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStartMs < DEBOUNCE_MS) return
        lastStartMs = now
        ensureReceiver()
        // null sender → accept a message from any number. startSmsUserConsent silently no-ops if
        // Play Services is unavailable; swallow so it never crashes a normal page load.
        runCatching { SmsRetriever.getClient(activity).startSmsUserConsent(null) }
            .onFailure { Log.d(TAG, "startSmsUserConsent failed", it) }
    }

    private fun ensureReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                @Suppress("DEPRECATION")
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode != CommonStatusCodes.SUCCESS) return
                val consent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT)
                }
                if (consent != null) {
                    runCatching { consentLauncher.launch(consent) }
                        .onFailure { Log.d(TAG, "launch consent failed", it) }
                }
            }
        }
        // The SMS_RETRIEVED broadcast originates from Google Play Services (a different package), so
        // the receiver must be exported; it is protected by SEND_PERMISSION so only GMS can deliver
        // to it. ContextCompat handles the API-33+ exported-flag requirement.
        ContextCompat.registerReceiver(
            activity,
            r,
            IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiver = r
    }

    /** Unregister the receiver. Call from the Activity's onDestroy. */
    fun dispose() {
        receiver?.let { runCatching { activity.unregisterReceiver(it) } }
        receiver = null
    }

    companion object {
        private const val TAG = "OtpSmsController"
        private const val DEBOUNCE_MS = 3000L
    }
}
