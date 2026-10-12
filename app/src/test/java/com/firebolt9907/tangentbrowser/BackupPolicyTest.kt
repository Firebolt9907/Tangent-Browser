package com.firebolt9907.tangentbrowser

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupPolicyTest {
    private fun resource(name: String): File = listOf(
        File("src/main/res/xml/$name.xml"),
        File("app/src/main/res/xml/$name.xml"),
    ).first { it.isFile }

    private fun rules(name: String, section: String): Set<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource(name))
        val sections = document.getElementsByTagName(section)
        assertEquals("Missing or duplicate backup section", 1, sections.length)
        val includes = (sections.item(0) as Element).getElementsByTagName("include")
        return (0 until includes.length).map { index ->
            val element = includes.item(index) as Element
            element.getAttribute("domain") to element.getAttribute("path")
        }.toSet()
    }

    @Test fun allBackupTransportsAllowOnlyReviewedPreferences() {
        val approved = setOf(
            "sharedpref" to "tangent_global_prefs.xml",
            "sharedpref" to "tangent_site_prefs.xml",
            "sharedpref" to "tangent_zapped_elements.xml",
        )
        for ((name, section) in listOf(
            "backup_rules" to "full-backup-content",
            "data_extraction_rules" to "cloud-backup",
            "data_extraction_rules" to "device-transfer",
        )) {
            val included = rules(name, section)
            // An empty include set means Android defaults back to backing up everything.
            assertTrue("$section must opt into allowlist semantics", included.isNotEmpty())
            assertEquals(section, approved, included)
            for (sensitive in listOf(
                "file" to "mozilla/default/cookies.sqlite",
                "file" to "tangent_tabs.json",
                "file" to "tab_snapshots/page.jpg",
                "database" to "tangent_logins.db",
                "database" to "autofill.sqlite",
                "sharedpref" to "fxaStateAC.xml",
                "sharedpref" to "tangent_form_memory.xml",
                "sharedpref" to "tangent_autofill_trust.xml",
                "sharedpref" to "tangent_site_permissions.xml",
                "sharedpref" to "future_credential_store.xml",
            )) {
                assertFalse("$section must not copy $sensitive", sensitive in included)
            }
        }
    }
}
