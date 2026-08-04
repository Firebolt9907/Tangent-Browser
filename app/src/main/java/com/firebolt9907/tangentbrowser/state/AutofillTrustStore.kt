package com.firebolt9907.tangentbrowser.state

import android.content.Context
import androidx.core.content.edit

/**
 * Per-credential opt-in for **unattended** login autofill, plus the usage history that earns the
 * right to be offered it.
 *
 * Tangent's default is deliberately strict (invariant #15): a saved password is only ever typed into
 * a page after the user picks it from a sheet, behind a biometric gate. That default stays. This
 * store backs one narrow exception, and every condition below has to hold before a password is
 * filled without the user present:
 *
 *  1. the page is on an enterprise identity provider ([SsoFlow.isEnterpriseIdp] — not an ordinary
 *     site's login page),
 *  2. exactly **one** saved credential matches, so there is no choice to get wrong,
 *  3. the user has already filled that same credential [AlwaysAutofillThreshold] times by hand, and
 *  4. having earned the offer, the user explicitly tapped "Always autofill" for it.
 *
 * Conditions 3 and 4 are what make this an informed decision rather than a default: the button does
 * not exist until the user has demonstrably done the thing manually several times, and it applies to
 * **one credential**, not to a host, a category, or the app.
 *
 * Keyed by login GUID, so deleting and re-saving a credential resets its standing — and revoking is
 * immediate ([setAlwaysAutofill] with false). Nothing here is synced; the opt-in is a property of
 * this device, where the biometric enrolment that would otherwise gate the fill also lives.
 */
class AutofillTrustStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("tangent_autofill_trust", Context.MODE_PRIVATE)

    /** Hand-fills of one credential before "Always autofill" is even offered. */
    companion object {
        const val AlwaysAutofillThreshold = 3

        /**
         * Whether the "Always autofill" affordance should appear for a credential. Pure so the rule
         * is testable without a [Context].
         */
        fun offersAlwaysAutofill(
            fillCount: Int,
            alreadyAlways: Boolean,
            isEnterpriseIdp: Boolean,
            isOnlyMatch: Boolean,
        ): Boolean = isEnterpriseIdp &&
            isOnlyMatch &&
            !alreadyAlways &&
            fillCount >= AlwaysAutofillThreshold
    }

    fun fillCount(guid: String): Int = prefs.getInt("count:$guid", 0)

    /** Record a user-driven fill. Only hand-fills count — an unattended fill must not earn trust. */
    fun recordManualFill(guid: String) {
        prefs.edit { putInt("count:$guid", fillCount(guid) + 1) }
    }

    fun isAlwaysAutofill(guid: String): Boolean = prefs.getBoolean("always:$guid", false)

    fun setAlwaysAutofill(guid: String, enabled: Boolean) {
        prefs.edit { putBoolean("always:$guid", enabled) }
    }

    /** Drop everything known about a credential (used when it is deleted). */
    fun forget(guid: String) {
        prefs.edit {
            remove("count:$guid")
            remove("always:$guid")
        }
    }
}
