package com.firebolt9907.tangentbrowser

/** One SMS listening window and one consent result, each retaining its original destination. */
internal class OtpConsentDelivery(private val now: () -> Long) {
    private data class Pending(val started: Long, val deliver: (String) -> Unit)
    private var listening: Pending? = null
    private var consenting: Pending? = null

    fun arm(deliver: (String) -> Unit): Boolean {
        // Repeated field reports must not redirect an already armed SMS or consent dialog.
        if (listening?.fresh() == true || consenting != null) return false
        listening = Pending(now(), deliver)
        return true
    }

    fun beginConsent(): Boolean {
        if (consenting != null) return false
        val pending = listening ?: return false
        listening = null
        if (!pending.fresh()) return false
        consenting = pending
        return true
    }

    fun finish(code: String?) {
        val pending = consenting
        consenting = null
        if (code != null && pending?.fresh() == true) pending.deliver(code)
    }

    fun clear() {
        listening = null
        consenting = null
    }

    private fun Pending.fresh(): Boolean = now() - started in 0..300_000L
}
