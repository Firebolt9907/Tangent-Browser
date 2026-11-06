package com.firebolt9907.tangentbrowser.ui

import java.util.Locale
import mozilla.components.concept.storage.Address

internal data class AddressPickerChoice(val address: Address, val primary: String, val secondary: String)

internal fun addressPickerTitle(field: String): String = when {
    field.startsWith("tel") -> "Fill phone number"
    field == "email" -> "Fill email"
    field == "given-name" -> "Fill first name"
    field == "family-name" -> "Fill last name"
    field == "additional-name" -> "Fill middle name"
    field == "name" -> "Fill name"
    field == "address-level2" -> "Fill city"
    field == "address-level1" -> "Fill state or region"
    field == "organization" -> "Fill organization"
    field == "postal-code" -> "Fill postal code"
    field.startsWith("country") -> "Fill country"
    field.startsWith("address") || field == "street-address" -> "Fill address"
    else -> "Fill saved information"
}

/** Collapse identical values for contact fields; keep distinct postal addresses selectable.
 * Always return the original Gecko profile: Gecko owns field formatting and form filling.
 */
internal fun addressPickerChoices(addresses: List<Address>, field: String): List<AddressPickerChoice> {
    val contact = field.startsWith("tel") || field == "email" || field in
        listOf("name", "given-name", "additional-name", "family-name", "organization", "postal-code", "country", "country-name", "address-level1", "address-level2", "address-level3")
    fun value(a: Address): String = when {
        field.startsWith("tel") -> a.tel
        field == "email" -> a.email
        field in listOf("name", "given-name", "additional-name", "family-name") -> a.name
        field == "organization" -> a.organization
        field == "address-level1" -> a.addressLevel1
        field == "address-level2" -> a.addressLevel2
        field == "address-level3" -> a.addressLevel3
        field == "postal-code" -> a.postalCode
        field.startsWith("country") -> a.country
        else -> addressSummaryLine(a).ifBlank { a.addressLabel }.ifBlank { a.name }
    }.trim()
    fun key(value: String): String = if (field.startsWith("tel")) {
        value.filter { it.isDigit() || it == '+' }
    } else value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    return addresses.filter { value(it).isNotBlank() }
        .distinctBy { if (contact) key(value(it)) else listOf(key(value(it)), it.name.lowercase(Locale.ROOT), it.country) }
        .map { a -> AddressPickerChoice(a, value(a), a.name.takeUnless { it.equals(value(a), ignoreCase = true) }.orEmpty()) }
}
