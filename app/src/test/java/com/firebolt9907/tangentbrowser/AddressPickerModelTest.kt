package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.addressPickerChoices
import com.firebolt9907.tangentbrowser.ui.addressPickerTitle
import com.firebolt9907.tangentbrowser.ui.content.AddressFieldProbe
import com.firebolt9907.tangentbrowser.ui.content.BridgeGuard
import mozilla.components.concept.storage.Address
import org.junit.Assert.*
import org.junit.Test

class AddressPickerModelTest {
    private fun profile(id: String, street: String, phone: String = "(555) 123-4567", email: String = "person@example.test") = Address(
        guid = id, name = "Example Person", organization = "", streetAddress = street,
        addressLevel3 = "", addressLevel2 = "Example City", addressLevel1 = "IN",
        postalCode = "12345", country = "US", tel = phone, email = email,
        timeCreated = 0, timeLastUsed = null, timeLastModified = 0, timesUsed = 0,
    )

    @Test fun phoneFieldCollapsesAddressVariantsAndOmitsMissingPhones() {
        val profiles = listOf(profile("a", "1 Main St"), profile("b", "2 Main St", "5551234567"),
            profile("c", "3 Main St", ""), profile("d", "4 Main St", "5559876543"))
        val choices = addressPickerChoices(profiles, "tel")
        assertEquals("Fill phone number", addressPickerTitle("tel"))
        assertEquals(2, choices.size)
        assertEquals("(555) 123-4567", choices.first().primary)
        assertSame(profiles.first(), choices.first().address)
    }

    @Test fun emailsCollapseButDifferentStreetsRemainSelectable() {
        val profiles = listOf(profile("a", "1 Main St"), profile("b", "2 Main St"))
        assertEquals(1, addressPickerChoices(profiles, "email").size)
        val streets = addressPickerChoices(profiles, "street-address")
        assertEquals(2, streets.size)
        assertTrue(streets.first().primary.startsWith("1 Main St"))
        assertEquals("Example Person", streets.first().secondary)
        assertEquals("Fill saved information", addressPickerTitle(""))
    }

    @Test fun hintsAreBoundToTabAndOriginAndClearedOnNavigation() {
        BridgeGuard.setTrustedOrigin("picker-test", "https://example.test")
        try {
            AddressFieldProbe.onPayload("picker-test", "tel")
            assertEquals("tel", AddressFieldProbe.field("picker-test"))
            assertEquals("", AddressFieldProbe.field("another-tab"))
            BridgeGuard.setTrustedOrigin("picker-test", "https://other.test")
            assertEquals("", AddressFieldProbe.field("picker-test"))
            AddressFieldProbe.forget("picker-test")
            assertEquals("", AddressFieldProbe.field("picker-test"))
        } finally {
            AddressFieldProbe.forget("picker-test")
            BridgeGuard.forget("picker-test")
        }
    }
}
