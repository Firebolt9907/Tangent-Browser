package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.sync.zen.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZenPhoneMergeTest {
    private val space = "b3b97e90-e22e-4c5b-9abc-c08af0893c9d"
    private val local = ZenPhoneTab("phone", "https://one.example/", "One")
    private fun desktop(id: String = "desktop", workspace: String = space, url: String = "https://two.example/") =
        Sync15Record(id, 10.0, JSONObject().put("id", id).put("kind", "tab").put("data", JSONObject()
            .put("workspaceUuid", workspace).put("url", url).put("title", "Two").put("tabId", id)
            .put("folderId", JSONObject.NULL).put("pinned", true).put("containerGuid", "work")))
    private fun deleted(id: String) = Sync15Record(id, 20.0, JSONObject().put("id", id).put("deleted", true))
    private fun link(tab: ZenPhoneTab = local) = ZenPhoneLink(tab.id, ZenPhoneSpace.tabRecordId(space, tab.id), tab.url)

    @Test fun `desktop deletion closes only unchanged matching phone tab`() {
        val baseline = link()
        val remote = listOf(deleted(baseline.recordId))
        assertEquals(listOf(local.id), ZenPhoneMerge.inbound(space, listOf(local), remote, listOf(baseline)).closeIds)
        assertTrue(ZenPhoneMerge.inbound(space, listOf(local.copy(url = "https://new.example/")), remote, listOf(baseline)).closeIds.isEmpty())
        assertTrue(ZenPhoneMerge.inbound(space, listOf(local), listOf(deleted("unrelated")), listOf(baseline)).closeIds.isEmpty())
    }

    @Test fun `migration recognizes deletion of previously published deterministic phone id`() {
        assertEquals(listOf(local.id), ZenPhoneMerge.inbound(space, listOf(local), listOf(deleted(link().recordId)), emptyList()).closeIds)
    }

    @Test fun `missing records and missing children never imply deletion`() {
        assertTrue(ZenPhoneMerge.inbound(space, listOf(local), emptyList(), listOf(link())).closeIds.isEmpty())
    }

    @Test fun `desktop additions are imported once by identity including duplicate URLs`() {
        val remote = listOf(desktop("a"), desktop("b"))
        val first = ZenPhoneMerge.inbound(space, listOf(local), remote, emptyList())
        assertEquals(listOf("a", "b"), first.additions.map { it.recordId })
        val links = listOf(ZenPhoneLink("import-a", "a", remote[0].cleartext.getJSONObject("data").getString("url")))
        assertEquals(listOf("b"), ZenPhoneMerge.inbound(space, listOf(local), remote, links).additions.map { it.recordId })
        // Even if import-a is absent locally, it was closed here; a retry must not resurrect it.
    }

    @Test fun `other spaces essentials private and executable URLs are excluded`() {
        val essential = desktop("essential").also { it.cleartext.getJSONObject("data").put("essential", true) }
        val private = desktop("private").also { it.cleartext.getJSONObject("data").put("private", true) }
        val remote = listOf(desktop("other", "other-space"), desktop("unsafe", url = "javascript:alert(1)"), essential, private)
        assertTrue(ZenPhoneMerge.inbound(space, listOf(local), remote, emptyList()).additions.isEmpty())
    }

    @Test fun `latest record wins including tombstone and later recreation`() {
        assertTrue(ZenPhoneMerge.inbound(space, emptyList(), listOf(deleted("desktop"), desktop()), emptyList()).additions.isEmpty())
        assertEquals(1, ZenPhoneMerge.inbound(space, emptyList(), listOf(deleted("desktop"), desktop().copy(modified = 30.0)), emptyList()).additions.size)
    }

    @Test fun `imported tab retains desktop identity and metadata on upload`() {
        val tab = local.copy(id = "imported")
        val links = listOf(ZenPhoneLink(tab.id, "desktop", tab.url))
        val writes = ZenPhoneSpace.plan(space, "Phone", listOf(tab), listOf(desktop()), links)
        val record = writes.first { it.id == "desktop" }.cleartext.getJSONObject("data")
        assertTrue(record.getBoolean("pinned"))
        assertEquals("work", record.getString("containerGuid"))
        assertEquals(tab.url, record.getString("url"))
        assertFalse(writes.any { it.id.startsWith("tg-") })
        assertEquals("desktop", writes.first { it.id == space }.cleartext.getJSONObject("data").getJSONArray("children").getString(0))
    }

    @Test fun `closing imported tab tombstones it without touching unrelated tabs`() {
        val links = listOf(ZenPhoneLink("imported", "desktop", "https://two.example/"))
        val writes = ZenPhoneSpace.plan(space, "Phone", emptyList(), listOf(desktop(), desktop("unrelated")), links)
        assertTrue(writes.any { it.id == "desktop" && it.cleartext.optBoolean("deleted") && it.expectedModified == 10.0 })
        assertFalse(writes.any { it.id == "unrelated" })
    }

    @Test fun `desktop move outside phone space is never written or deleted`() {
        val links = listOf(ZenPhoneLink(local.id, "desktop", local.url))
        val remote = listOf(desktop(workspace = "other-space"))
        assertFalse(ZenPhoneSpace.plan(space, "Phone", listOf(local), remote, links).any { it.id == "desktop" })
        assertFalse(ZenPhoneSpace.plan(space, "Phone", emptyList(), remote, links).any { it.id == "desktop" })
    }

    @Test fun `identity checkpoint survives process restart and rejects malformed data`() {
        val links = listOf(link(), ZenPhoneLink("import", "desktop", "https://two.example/"))
        assertEquals(links, ZenPhoneMerge.decode(ZenPhoneMerge.encode(links)))
        assertThrows(Exception::class.java) { ZenPhoneMerge.decode("corrupt") }
    }
}
