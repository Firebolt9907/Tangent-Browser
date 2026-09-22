package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.sync.zen.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZenPhoneSpaceTest {
    private val space = "b3b97e90-e22e-4c5b-9abc-c08af0893c9d"
    private val tabs = listOf(
        ZenPhoneTab("first", "https://one.example/", "One"),
        ZenPhoneTab("second", "https://two.example/", "Two"),
    )
    private fun remote(writes: List<ZenPhoneWrite>) = writes.map { Sync15Record(it.id, 12.5, it.cleartext) }

    @Test fun `phone records use stable v3 unpinned tab format and roundtrip through reader`() {
        val writes = ZenPhoneSpace.plan(space, "Tangent · Pixel", tabs, emptyList())
        assertEquals(3, writes.size)
        assertTrue(writes.all { it.expectedModified == 0.0 && it.id.length <= 64 })
        val parsed = ZenWorkspaceRecords.parse(remote(writes)).spaces.single()
        assertEquals("Tangent · Pixel", parsed.name)
        assertEquals(tabs.asReversed().map { it.url }, parsed.tabs.map { it.url })
        assertTrue(parsed.tabs.none { it.pinned || it.essential })
        assertTrue(ZenPhoneSpace.plan(space, "Tangent · Pixel", tabs, remote(writes)).isEmpty())
    }

    @Test fun `reopening an older tab only rewrites newest first space order`() {
        val before = remote(ZenPhoneSpace.plan(space, "Phone", tabs, emptyList()))
        val writes = ZenPhoneSpace.plan(space, "Phone", tabs.reversed(), before)
        assertEquals(listOf(space), writes.map { it.id })
        val children = writes.single().cleartext.getJSONObject("data").getJSONArray("children")
        assertEquals(tabs.map { ZenPhoneSpace.tabRecordId(space, it.id) },
            (0 until children.length()).map(children::getString))
        assertEquals(12.5, writes.single().expectedModified, 0.0)
    }

    @Test fun `privacy filter excludes private tabs and non web URLs`() {
        val unsafe = listOf(
            ZenPhoneTab("private", "https://secret.example/", "Secret", private = true),
            ZenPhoneTab("blank", "about:blank", "New tab"),
            ZenPhoneTab("local", "file:///secret", "File"),
            ZenPhoneTab("js", "javascript:alert(1)", "Script"),
            ZenPhoneTab("bad", "https://", "Malformed"),
        )
        val writes = ZenPhoneSpace.plan(space, "Phone", tabs + unsafe, emptyList())
        assertEquals(3, writes.size)
        assertFalse(writes.joinToString { it.cleartext.toString() }.contains("secret"))
    }

    @Test fun `navigation keeps identity and only writes changed tab`() {
        val before = remote(ZenPhoneSpace.plan(space, "Phone", tabs, emptyList()))
        val changed = tabs.toMutableList().apply { this[0] = this[0].copy(url = "https://next.example/", title = "Next") }
        val writes = ZenPhoneSpace.plan(space, "Phone", changed, before)
        assertEquals(1, writes.size)
        assertEquals(ZenPhoneSpace.tabRecordId(space, "first"), writes.single().id)
        assertEquals(12.5, writes.single().expectedModified, 0.0)
    }

    @Test fun `closing tabs tombstones only phone owned tabs in its own space`() {
        val before = remote(ZenPhoneSpace.plan(space, "Phone", tabs, emptyList()))
        val other = Sync15Record("desktop", 9.0, JSONObject("""{"kind":"tab","data":{"workspaceUuid":"$space","url":"https://desktop.example/"}}"""))
        val writes = ZenPhoneSpace.plan(space, "Phone", emptyList(), before + other)
        assertEquals(setOf(space) + tabs.map { ZenPhoneSpace.tabRecordId(space, it.id) }, writes.map { it.id }.toSet())
        assertEquals(2, writes.count { it.cleartext.optBoolean("deleted") })
        assertTrue(writes.none { it.id == "desktop" || it.id == "layout" })
    }

    @Test fun `desktop appearance and extra children survive phone updates`() {
        val before = remote(ZenPhoneSpace.plan(space, "Phone", tabs, emptyList()))
        val data = before.last().cleartext.getJSONObject("data")
        data.put("name", "My phone").put("icon", "💚")
        data.getJSONArray("children").put("desktop-folder")
        val writes = ZenPhoneSpace.plan(space, "Phone", tabs.reversed(), before)
        assertEquals(1, writes.size)
        val updated = writes.single().cleartext.getJSONObject("data")
        assertEquals("My phone", updated.getString("name"))
        assertEquals("💚", updated.getString("icon"))
        assertEquals("desktop-folder", updated.getJSONArray("children").getString(2))
    }

    @Test fun `duplicate URLs remain distinct and multiple phones have separate record IDs`() {
        val duplicate = listOf(tabs[0], tabs[0].copy(id = "duplicate"))
        val writes = ZenPhoneSpace.plan(space, "Phone", duplicate, emptyList())
        assertEquals(3, writes.map { it.id }.distinct().size)
        assertNotEquals(ZenPhoneSpace.tabRecordId(space, "first"),
            ZenPhoneSpace.tabRecordId("c3b97e90-e22e-4c5b-9abc-c08af0893c9d", "first"))
    }

    @Test fun `remote tombstone can be recreated with correct precondition`() {
        val tombstone = Sync15Record(space, 42.0, JSONObject().put("id", space).put("deleted", true))
        val writes = ZenPhoneSpace.plan(space, "Phone", tabs, listOf(tombstone))
        assertEquals(42.0, writes.last().expectedModified, 0.0)
    }
    @Test fun `favicon is embedded in v3 record and roundtrips through reader`() {
        val icon = "data:image/png;base64,aWNvbg=="
        val writes = ZenPhoneSpace.plan(space, "Phone", listOf(tabs[0].copy(icon = icon)), emptyList())
        val data = writes.first().cleartext.getJSONObject("data")
        assertEquals(icon, data.getString("icon"))
        assertFalse(data.getBoolean("hasStaticIcon"))
        assertFalse(data.has("iconRevision"))
        assertEquals(icon, ZenWorkspaceRecords.parse(remote(writes)).spaces.single().tabs.single().icon)
    }

    @Test fun `favicon arriving after URL and title only updates its tab`() {
        val before = remote(ZenPhoneSpace.plan(space, "Phone", tabs, emptyList()))
        val updated = tabs.map { if (it.id == "first") it.copy(icon = "data:image/png;base64,bmV3", iconRevision = 42) else it }
        val writes = ZenPhoneSpace.plan(space, "Phone", updated, before)
        assertEquals(1, writes.size)
        assertEquals(ZenPhoneSpace.tabRecordId(space, "first"), writes.single().id)
        assertEquals("data:image/png;base64,bmV3", writes.single().cleartext.getJSONObject("data").getString("icon"))
    }

    @Test fun `missing local favicon preserves same page icon but never another page icon`() {
        val before = remote(ZenPhoneSpace.plan(space, "Phone", listOf(tabs[0].copy(icon = "data:image/png;base64,b2xk")), emptyList()))
        assertTrue(ZenPhoneSpace.plan(space, "Phone", listOf(tabs[0]), before).isEmpty())
        val navigated = ZenPhoneSpace.plan(space, "Phone", listOf(tabs[0].copy(url = "https://other.example/")), before)
        assertEquals("", navigated.single().cleartext.getJSONObject("data").getString("icon"))
    }

}
