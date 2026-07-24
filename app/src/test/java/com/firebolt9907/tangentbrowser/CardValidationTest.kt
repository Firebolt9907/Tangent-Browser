package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.passwords.CardValidation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CardValidation.isLuhnValid] is the typo guard on the add-card form: real card numbers pass Luhn,
 * so a failing checksum means a mistyped digit that shouldn't be stored.
 */
class CardValidationTest {

    // Standard network test PANs — all satisfy Luhn.
    @Test fun visa16_valid() = assertTrue(CardValidation.isLuhnValid("4111111111111111"))
    @Test fun visa16_alt_valid() = assertTrue(CardValidation.isLuhnValid("4012888888881881"))
    @Test fun mastercard16_valid() = assertTrue(CardValidation.isLuhnValid("5555555555554444"))
    @Test fun amex15_valid() = assertTrue(CardValidation.isLuhnValid("378282246310005"))
    @Test fun discover16_valid() = assertTrue(CardValidation.isLuhnValid("6011111111111117"))

    // A single fat-fingered digit breaks the checksum.
    @Test fun typoLastDigit_invalid() = assertFalse(CardValidation.isLuhnValid("4111111111111112"))
    @Test fun typoMiddleDigit_invalid() = assertFalse(CardValidation.isLuhnValid("4111111121111111"))

    // Length gate: shorter than 12 or longer than 19 is never a card number.
    @Test fun tooShort_invalid() = assertFalse(CardValidation.isLuhnValid("411111"))
    @Test fun empty_invalid() = assertFalse(CardValidation.isLuhnValid(""))
    @Test fun tooLong_invalid() = assertFalse(CardValidation.isLuhnValid("41111111111111111111"))

    // All-zeros passes Luhn arithmetically but is guarded by nothing here — documents that Luhn alone
    // is a checksum, not an issuer check (length still applies).
    @Test fun twelveZeros_passesChecksum() = assertTrue(CardValidation.isLuhnValid("000000000000"))
}
