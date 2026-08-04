package com.firebolt9907.tangentbrowser.ui

import android.os.SystemClock

/**
 * Arbitrates the ONE on-screen "Fill password" picker between the two independent paths that can raise
 * it, so a page never stacks two of them.
 *
 * - [OwnerProactive] — `ProactiveLoginAutofill`, driven by `LoginFieldProbe` (a visible empty password
 *   field is detected, before the user taps it).
 * - [OwnerFocus] — `PromptHandler`, driven by Gecko's `PromptRequest.SelectLoginPrompt` (raised on
 *   *every* focus of a login field).
 *
 * On a page that autofocuses its password field — Microsoft's `login.microsoftonline.com` password
 * step is the canonical case — both fire within a frame or two of each other and each composes its own
 * [SelectLoginSheet], producing two stacked modals. Neither side can see the other's Compose state
 * (different subtrees, one of them re-created per prompt), hence this process-wide claim.
 *
 * Deliberately NOT Compose state: both sides claim from an effect/coroutine, never during composition,
 * and a losing claim resolves immediately (dismiss the prompt) rather than waiting for the winner — so
 * nothing needs to recompose when the owner changes.
 */
internal object LoginPickerCoordinator {
    const val OwnerProactive = "proactive"
    const val OwnerFocus = "focus"

    /**
     * Quiet window after a proactive fill. Filling a password field re-fires focus/change events, so
     * Gecko re-raises `SelectLoginPrompt` right after we fill by JS — long enough after the fill that
     * the claim has already been released. Without this the second sheet still appears, now on a form
     * that is already filled.
     */
    const val QuietAfterFillMs = 6_000L

    @Volatile
    private var owner: String? = null

    @Volatile
    private var quietUntilMs = 0L

    /**
     * Try to become the single picker owner. Re-claiming as the current owner succeeds (idempotent for
     * a recomposing caller). The quiet window blocks [OwnerFocus] only — a proactive offer is a
     * deliberate, host-deduplicated event, while the focus prompt is the one that machine-guns.
     */
    @Synchronized
    fun claim(who: String): Boolean {
        val current = owner
        if (current != null) return current == who
        if (who == OwnerFocus && SystemClock.elapsedRealtime() < quietUntilMs) return false
        owner = who
        return true
    }

    /**
     * Give the picker back. [quietForMs] suppresses the focus-driven prompt for that long — pass
     * [QuietAfterFillMs] when a credential was actually filled, and 0 when the user dismissed (tapping
     * the field must still be able to summon the picker).
     */
    @Synchronized
    fun release(who: String, quietForMs: Long = 0L) {
        if (owner == who) owner = null
        if (quietForMs > 0L) {
            quietUntilMs = maxOf(quietUntilMs, SystemClock.elapsedRealtime() + quietForMs)
        }
    }
}
