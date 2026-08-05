package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.SearchEngine
import com.firebolt9907.tangentbrowser.state.parseSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `searchTermsFrom` isn't covered here: it goes through `android.net.Uri`, which is unmocked on the
 * JVM. Everything that decides where a search *goes* is plain string work precisely so it can be
 * pinned here.
 */
class SearchEngineTest {

    // ── Built-ins ─────────────────────────────────────────────────────────────

    @Test fun google_hasNoWebFilter() =
        assertFalse(SearchEngine.BuiltIn.Google.searchUrl("cats").contains("udm="))

    /** The whole point of the "no AI" entry — udm=14 is what suppresses AI Overviews. */
    @Test fun googleWeb_carriesUdm14() =
        assertTrue(SearchEngine.BuiltIn.GoogleWeb.searchUrl("cats").contains("&udm=14"))

    @Test fun startpage_usesQueryParam() =
        assertTrue(
            SearchEngine.BuiltIn.Startpage.searchUrl("cats")
                .startsWith("https://www.startpage.com/sp/search?query=cats"),
        )

    /** Three engines don't use `q`; getting the parameter wrong silently searches for nothing. */
    @Test fun engines_useTheirOwnQueryParam() {
        assertTrue(SearchEngine.BuiltIn.Yahoo.searchUrl("cats").contains("?p=cats"))
        assertTrue(SearchEngine.BuiltIn.Yandex.searchUrl("cats").contains("?text=cats"))
        assertTrue(SearchEngine.BuiltIn.Startpage.searchUrl("cats").contains("?query=cats"))
    }

    /** Mojeek publishes no suggestion endpoint — the null is the feature, not an oversight. */
    @Test fun mojeek_hasNoSuggestUrl() =
        assertNull(SearchEngine.BuiltIn.Mojeek.suggestUrl("cats"))

    @Test fun everyOtherBuiltIn_hasASuggestUrl() =
        SearchEngine.builtIns
            .filter { it != SearchEngine.BuiltIn.Mojeek }
            .forEach { assertTrue(it.label, it.suggestUrl("cats")?.contains("cats") == true) }

    @Test fun builtIns_areAllHttps() =
        SearchEngine.builtIns.forEach { engine ->
            assertTrue(engine.label, engine.searchUrl("cats").startsWith("https://"))
            engine.suggestUrl("cats")?.let { assertTrue(engine.label, it.startsWith("https://")) }
        }

    @Test fun onboardingChoices_areAllBuiltIns() =
        assertTrue(SearchEngine.builtIns.containsAll(SearchEngine.onboardingChoices))

    @Test fun builtIns_encodeTheQuery() =
        SearchEngine.builtIns.forEach { engine ->
            assertTrue(engine.label, engine.searchUrl("a b&c").contains("a+b%26c"))
        }

    @Test fun builtIns_haveDistinctIds() =
        assertEquals(SearchEngine.builtIns.size, SearchEngine.builtIns.map { it.id }.distinct().size)

    /** Ids previously persisted as enum names must still resolve after the sealed-class move. */
    @Test fun legacyIds_stillResolve() {
        assertEquals(SearchEngine.BuiltIn.Google, SearchEngine.fromName("Google"))
        assertEquals(SearchEngine.BuiltIn.DuckDuckGo, SearchEngine.fromName("DuckDuckGo"))
        assertEquals(SearchEngine.BuiltIn.Bing, SearchEngine.fromName("Bing"))
    }

    @Test fun unknownId_fallsBackToDefault() =
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromName("Altavista"))

    /** Custom lives in preferences, so resolving it by id alone must not half-succeed. */
    @Test fun customId_doesNotResolveHere() =
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromName(SearchEngine.Custom.ID))

    // ── Custom templates ──────────────────────────────────────────────────────

    @Test fun custom_substitutesEncodedQuery() =
        assertEquals(
            "https://kagi.com/search?q=a+b%26c",
            SearchEngine.Custom("Kagi", "https://kagi.com/search?q=%s").searchUrl("a b&c"),
        )

    @Test fun custom_acceptsFirefoxPlaceholder() =
        assertEquals(
            "https://kagi.com/search?q=cats",
            SearchEngine.Custom("Kagi", "https://kagi.com/search?q={searchTerms}").searchUrl("cats"),
        )

    @Test fun custom_withoutSuggestTemplate_hasNoSuggestUrl() =
        assertNull(SearchEngine.Custom("Kagi", "https://kagi.com/search?q=%s").suggestUrl("cats"))

    @Test fun custom_suggestTemplateIsFilled() =
        assertEquals(
            "https://kagi.com/api/autosuggest?q=cats",
            SearchEngine.Custom(
                label = "Kagi",
                searchTemplate = "https://kagi.com/search?q=%s",
                suggestTemplate = "https://kagi.com/api/autosuggest?q=%s",
            ).suggestUrl("cats"),
        )

    @Test fun validTemplate_requiresPlaceholder() =
        assertFalse(SearchEngine.isValidTemplate("https://kagi.com/search?q=cats"))

    @Test fun validTemplate_requiresHttpScheme() {
        assertFalse(SearchEngine.isValidTemplate("javascript:alert(%s)"))
        assertFalse(SearchEngine.isValidTemplate("kagi.com/search?q=%s"))
        assertFalse(SearchEngine.isValidTemplate("file:///tmp/%s"))
    }

    @Test fun validTemplate_requiresHost() =
        assertFalse(SearchEngine.isValidTemplate("https:///search?q=%s"))

    @Test fun validTemplate_acceptsHttpAndHttps() {
        assertTrue(SearchEngine.isValidTemplate("https://kagi.com/search?q=%s"))
        assertTrue(SearchEngine.isValidTemplate("http://localhost:8080/search?q=%s"))
        assertTrue(SearchEngine.isValidTemplate("  https://kagi.com/search?q={searchTerms}  "))
    }

    // ── Suggestion parsing ────────────────────────────────────────────────────

    @Test fun parses_openSearchPair() =
        assertEquals(
            listOf("cats", "cat videos"),
            parseSuggestions("""["cat", ["cats", "cat videos"]]"""),
        )

    @Test fun parses_bareArray() =
        assertEquals(listOf("cats", "cat videos"), parseSuggestions("""["cats", "cat videos"]"""))

    @Test fun parses_objectWithSuggestionObjects() =
        assertEquals(
            listOf("cats", "cat videos"),
            parseSuggestions("""{"suggestions": [{"text": "cats"}, {"text": "cat videos"}]}"""),
        )

    @Test fun parses_objectWithStringResults() =
        assertEquals(listOf("cats"), parseSuggestions("""{"results": ["cats"]}"""))

    @Test fun parses_htmlErrorPage_asNothing() =
        assertEquals(emptyList<String>(), parseSuggestions("<!doctype html><html></html>"))
}
