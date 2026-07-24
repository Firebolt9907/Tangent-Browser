package com.firebolt9907.tangentbrowser.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Device-credential / biometric gate used before a stored password is filled into a page.
 *
 * The prompt allows either a strong biometric (fingerprint/face) *or* the device PIN/pattern/password
 * (`DEVICE_CREDENTIAL`), so it works on devices with no enrolled biometric as long as a screen lock
 * is set. If the device has no secure lock at all (`canAuthenticate` reports none enrolled / no
 * hardware), the gate is a no-op and [onSuccess] runs immediately — autofill must still function on
 * an unsecured device rather than becoming impossible to trigger.
 *
 * Requires the hosting activity to be a [FragmentActivity] ([androidx.biometric.BiometricPrompt]'s
 * only supported host); both browser activities extend `TangentBrowserActivity : FragmentActivity`.
 */
object BiometricGate {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private fun Context.findFragmentActivity(): FragmentActivity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Run [onSuccess] after the user confirms their identity. On any cancel/error, [onFailure] runs
     * (default: nothing). If no secure lock is available the gate is skipped and [onSuccess] runs.
     */
    fun authenticate(
        context: Context,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {},
    ) {
        val activity = context.findFragmentActivity()
        val canAuth = activity != null &&
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

        if (activity == null || !canAuth) {
            // No lock screen / no supported authenticator — don't strand the user; fill anyway.
            // (The explicit null check also lets the compiler smart-cast `activity` below.)
            onSuccess()
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }
                // onAuthenticationFailed (a single bad fingerprint) is transient — the system UI keeps
                // the prompt open for a retry, so we don't treat it as a terminal failure here.
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}
