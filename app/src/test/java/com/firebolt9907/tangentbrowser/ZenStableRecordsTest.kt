package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.sync.zen.Sync15Record
import com.firebolt9907.tangentbrowser.sync.zen.ZenWorkspaceRecords
import com.firebolt9907.tangentbrowser.sync.zen.ZenWorkspacesStorage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Wire fixtures follow Zen 1.22b's ZenSpacesSyncModel projections (engine version 3). */
class ZenStableRecordsTest {
    private fun record(id: String, kind: String, data: String) = Sync15Record(
        id, 1.0, JSONObject().put("id", id).put("kind", kind).put("data", JSONObject(data)),
    )

    private fun fixture() = listOf(
        record("a", "space", """{"uuid":"a","name":"Work","icon":null,"containerGuid":"custom-guid","children":["folder","last"]}"""),
        record("b", "space", """{"uuid":"b","name":"Home","children":[]}"""),
        record("custom-guid", "container", """{"guid":"custom-guid","name":"Work container","color":"purple","icon":"briefcase"}"""),
        record("last", "tab", """{"tabId":"last","url":"https://last.example/","title":"Last","workspaceUuid":"a","essential":false}"""),
        record("two", "tab", """{"tabId":"two","url":"https://two.example/","title":"Two","workspaceUuid":"a","folderId":"folder"}"""),
        record("one", "tab", """{"tabId":"one","url":"https://one.example/","title":"One","staticLabel":"Custom label","workspaceUuid":"a","folderId":"folder"}"""),
        record("folder", "folder", """{"folderId":"folder","name":"Research","workspaceUuid":"a","children":["split"]}"""),
        record("split", "split", """{"splitId":"split","workspaceUuid":"a","folderId":"folder","tabs":["one","two"]}"""),
        record("e2", "tab", """{"tabId":"e2","url":"https://e2.example/","essential":true,"containerGuid":"custom-guid"}"""),
        record("e1", "tab", """{"tabId":"e1","url":"https://e1.example/","essential":true,"containerGuid":"custom-guid"}"""),
        record("layout", "layout", """{"spaces":["b","a"],"essentials":{"custom-guid":["e1","e2"]}}"""),
    )

    @Test fun `stable envelope reads spaces containers pins folders splits and layout order`() {
        val snapshot = ZenWorkspaceRecords.parse(fixture())
        assertEquals(listOf("b", "a"), snapshot.spaces.map { it.uuid })
        val work = snapshot.spaces.last()
        assertEquals("custom-guid", work.containerGuid)
        assertNull(work.icon)
        assertEquals("Work container", snapshot.containers.single().name)
        assertEquals(work.containerGuid, snapshot.containers.single().guid)
        assertEquals(listOf("e1", "e2", "one", "two", "last").map { "https://$it.example/" }, work.tabs.map { it.url })
        assertEquals("Custom label", work.tabs[2].title)
        assertEquals("Research", work.tabs[2].folderName)
        assertTrue(work.tabs.all { it.pinned })
        assertEquals(2, snapshot.spaces.first().tabs.size)
        assertTrue(snapshot.spaces.first().tabs.all { it.essential })
    }

    @Test fun `stable metadata and ordering survive persistence`() {
        val snapshot = ZenWorkspaceRecords.parse(fixture())
        val restored = ZenWorkspacesStorage.fromJson(ZenWorkspacesStorage.toJson(snapshot))
        assertEquals(snapshot.spaces.map { it.tabs }, restored.spaces.map { it.tabs })
        assertEquals(snapshot.containers, restored.containers)
        assertEquals("custom-guid", restored.spaces.last().containerGuid)
    }

    @Test fun `cycles missing references duplicates and cross space children do not lose pins`() {
        val records = fixture().filter { it.id != "folder" } + listOf(
            record("folder", "folder", """{"workspaceUuid":"a","children":["folder","missing","one","one","wrong"]}"""),
            record("wrong", "tab", """{"url":"https://wrong.example/","workspaceUuid":"b"}"""),
        )
        val snapshot = ZenWorkspaceRecords.parse(records)
        val work = snapshot.spaces.last().tabs
        assertEquals(5, work.size)
        assertEquals(1, work.count { it.url == "https://one.example/" })
        assertFalse(work.any { it.url == "https://wrong.example/" })
        assertTrue(snapshot.spaces.first().tabs.any { it.url == "https://wrong.example/" })
    }

    @Test fun `tombstones malformed data unknown kinds and unsafe URLs are ignored`() {
        val extra = listOf(
            Sync15Record("one", 2.0, JSONObject("""{"id":"one","deleted":true}""")),
            Sync15Record("broken", 1.0, JSONObject("""{"kind":"tab","data":null}""")),
            record("future", "future", """{"url":"https://future.example/","workspaceUuid":"a"}"""),
            record("unsafe", "tab", """{"url":"javascript:alert(1)","workspaceUuid":"a"}"""),
            record("internal", "tab", """{"url":"about:config","workspaceUuid":"a"}"""),
            record("badurl", "tab", """{"url":"https://","workspaceUuid":"a"}"""),
            record("noname", "space", """{"uuid":null}"""),
        )
        val snapshot = ZenWorkspaceRecords.parse(extra + fixture())
        assertEquals(2, snapshot.spaces.size)
        assertEquals(4, snapshot.spaces.last().tabs.size)
        assertFalse(snapshot.spaces.last().tabs.any { it.url.contains("one.example") })
    }

    @Test fun `1_22_1b unpinned tabs sync from every space including split members`() {
        val records = fixture().filter { it.id != "a" && it.id != "b" } + listOf(
            record("a", "space", """{"uuid":"a","name":"Work","children":["folder","normal-a","normal-split","last"]}"""),
            record("b", "space", """{"uuid":"b","name":"Home","children":["normal-b"]}"""),
            record("normal-a", "tab", """{"tabId":"normal-a","url":"https://normal-a.example/","workspaceUuid":"a","essential":false,"pinned":false}"""),
            record("normal-b", "tab", """{"tabId":"normal-b","url":"https://normal-b.example/","workspaceUuid":"b","essential":false,"pinned":false}"""),
            record("normal-split", "split", """{"splitId":"normal-split","workspaceUuid":"a","pinned":false,"tabs":["n2","n1"]}"""),
            record("n1", "tab", """{"tabId":"n1","url":"https://n1.example/","workspaceUuid":"a","pinned":false}"""),
            record("n2", "tab", """{"tabId":"n2","url":"https://n2.example/","workspaceUuid":"a","pinned":false}"""),
        )
        val snapshot = ZenWorkspaceRecords.parse(records)
        val work = snapshot.spaces.last().tabs
        val home = snapshot.spaces.first().tabs
        assertEquals(listOf("normal-a", "n2", "n1").map { "https://$it.example/" }, work.filterNot { it.pinned }.map { it.url })
        assertEquals(listOf("https://normal-b.example/"), home.filterNot { it.pinned }.map { it.url })
        assertTrue(work.indexOfFirst { it.url.contains("normal-a") } < work.indexOfFirst { it.url.contains("last.example") })
        val restored = ZenWorkspacesStorage.fromJson(ZenWorkspacesStorage.toJson(snapshot))
        assertEquals(snapshot.spaces.map { it.tabs }, restored.spaces.map { it.tabs })
    }

    @Test fun `missing layout still shows scoped pins and essentials`() {
        val snapshot = ZenWorkspaceRecords.parse(fixture().filter { it.id != "layout" })
        assertEquals(5, snapshot.spaces.first().tabs.size)
        assertEquals(2, snapshot.spaces.last().tabs.size)
    }
}
