package com.firebolt9907.tangentbrowser.state

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers how many hops a given sign-in chain took last time, so the "Completing sign-in" cover can
 * show a *determinate* progress bar instead of an indefinite spinner.
 *
 * Keyed by the host of the chain's **first** auth hop (in practice `login.microsoftonline.com`), not
 * by every host it passes through: the intermediate hosts are what varies between an already-signed-in
 * pass and a full re-auth, while the entry point identifies "which SSO is this".
 *
 * The estimate is an exponential moving average rather than a max or a mean-of-all. A chain's length
 * genuinely varies — a cached pass is short, one that stops for a password and an Authenticator
 * approval is long — and an EMA tracks the recent normal without a single 12-hop re-auth permanently
 * skewing every later bar. [Alpha] is deliberately low-ish so one unusual login nudges rather than
 * redefines the estimate.
 */
class SsoChainStats(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("tangent_sso_chain_stats", Context.MODE_PRIVATE)

    companion object {
        /** Weight given to the newest observation. */
        const val Alpha = 0.35f

        /** Below this, a chain is too short to have been worth measuring; don't pollute the estimate. */
        const val MinRecordableHops = 2

        /** Never let the bar sit at 100% while the chain is still running. */
        private const val MaxProgress = 0.96f

        /** Folds a completed chain's [observed] hop count into the [previous] estimate. */
        fun updatedEstimate(previous: Float?, observed: Int): Float =
            if (previous == null) observed.toFloat() else previous + Alpha * (observed - previous)

        /**
         * Progress to show when [hops] hops have been entered (the last of them [pageProgressPercent]
         * loaded) and the chain historically takes [expected]. Returns null when there is no history
         * yet, which the overlay renders as an indeterminate bar.
         *
         * Hop count alone moves the bar in coarse steps — one jump per redirect, with a dead pause
         * between. Folding in the engine's own per-document load percentage subdivides each step, so
         * the bar advances continuously: completed hops supply the whole units, the in-flight hop
         * contributes its own fraction of one.
         *
         * The seam between hops is exactly continuous. A hop finishing at 100% gives
         * `(n-1 + 1)/d = n/d`; the next hop starting at 0% gives `(n + 0)/d = n/d`. So the bar never
         * jumps forward on a redirect nor snaps back when the new document reports 0.
         *
         * Overshoot is handled by stretching rather than clamping flat: if this login is running
         * longer than usual the bar keeps creeping toward [MaxProgress] instead of parking at full
         * and looking stuck. It never reaches 1.0 — only leaving the chain completes it.
         */
        fun progressFor(hops: Int, pageProgressPercent: Int, expected: Float?): Float? {
            if (expected == null || expected <= 0f) return null
            if (hops <= 0) return 0f
            // Denominator grows once we pass the estimate, so the ratio always stays under 1.
            val denominator = maxOf(expected, hops + 1f)
            val completed = (hops - 1).toFloat()
            val inFlight = (pageProgressPercent / 100f).coerceIn(0f, 1f)
            return ((completed + inFlight) / denominator).coerceIn(0f, MaxProgress)
        }
    }

    fun expectedHops(key: String): Float? =
        prefs.getFloat("hops:$key", 0f).takeIf { it > 0f }

    fun record(key: String, hops: Int) {
        if (hops < MinRecordableHops) return
        prefs.edit { putFloat("hops:$key", updatedEstimate(expectedHops(key), hops)) }
    }
}
