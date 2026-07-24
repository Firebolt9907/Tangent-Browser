package com.firebolt9907.tangentbrowser.ui.passwords

/**
 * Card-number validation shared by the add-card UI.
 *
 * Every real issued card number satisfies the Luhn (mod-10) checksum, so checking it before we store
 * a card is a free typo guard: it rejects a fat-fingered digit that would otherwise be saved and then
 * silently fail at checkout. It is a data-integrity check, not a security control.
 */
internal object CardValidation {
    /**
     * True if [digits] (already stripped to 0-9) passes the Luhn checksum. Requires the normal card
     * length range; an empty or too-short string is not a valid card number.
     */
    fun isLuhnValid(digits: String): Boolean {
        if (digits.length !in 12..19) return false
        if (!digits.all { it in '0'..'9' }) return false
        var sum = 0
        var double = false
        // Walk right-to-left, doubling every second digit and summing the digits of the result.
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    /**
     * True if a card's `[expiryMonth]/[expiryYear]` is a plausible, not-yet-expired date relative to
     * the current [nowYear]/[nowMonth] (both 1-based months, four-digit years). Rejects an out-of-range
     * month, an expiry strictly before the current month (the card is already dead), and a year absurdly
     * far out (> [nowYear] + 25) that is almost certainly a typo. The current month itself is still
     * valid — a card is usable through the last day of its expiry month.
     */
    fun isExpiryValid(expiryMonth: Int, expiryYear: Int, nowYear: Int, nowMonth: Int): Boolean {
        if (expiryMonth !in 1..12) return false
        if (expiryYear < nowYear || expiryYear > nowYear + 25) return false
        if (expiryYear == nowYear && expiryMonth < nowMonth) return false
        return true
    }
}
