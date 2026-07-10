package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.content.buildViewportSpacerScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the hard-won robustness invariants of the injected top-viewport-spacer script so they
 * can't silently regress (the script itself runs in Gecko and has no other test coverage).
 * See `AGENTS.md` §11 and `LOOP-PROGRESS.md` for the rationale behind each guard.
 */
class ViewportSpacerScriptTest {

    private fun script(topPx: Int = 48, skipHeaderInsets: Boolean = false) =
        buildViewportSpacerScript(topPx, skipHeaderInsets)

    @Test
    fun `emits a single-line javascript url with the injected topPx`() {
        val s = script(topPx = 48)
        assertTrue("must be a javascript: URL", s.startsWith("javascript:"))
        assertFalse("newlines are stripped so it stays a valid javascript: URL", s.contains("\n"))
        assertTrue("topPx must be baked in", s.contains("const topPx=48;"))
    }

    @Test
    fun `skipHeaderInsets flag is threaded into the script`() {
        assertTrue(script(skipHeaderInsets = true).contains("const skipHeader=true;"))
        assertTrue(script(skipHeaderInsets = false).contains("const skipHeader=false;"))
    }

    @Test
    fun `is top-only — never re-adds a bottom spacer (AGENTS §11)`() {
        val s = script()
        assertFalse("bottom scroll-padding must never come back", s.contains("scroll-padding-bottom"))
        assertFalse("bottom inset is native-only", s.contains("padding-bottom"))
        assertTrue("top scroll-padding is the mechanism", s.contains("scroll-padding-top"))
    }

    @Test
    fun `MutationObserver treats the spacer div's own writes as ours (iter 29 anti-churn)`() {
        // allOurs must accept luminaIgnoreSafeTop targets, else the spacer's per-pass style
        // write self-triggers a perpetual full-document walk.
        assertTrue(script().contains("luminaIgnoreSafeTop==='true'"))
    }

    @Test
    fun `handles flex-row bodies via host padding fallback (iter 26 and 28)`() {
        val s = script()
        assertTrue("detects nowrap row flex hosts", s.contains("flexRow"))
        // iter 28: adds topPx to the site's existing padding rather than replacing it.
        assertTrue("preserves the site's own body padding", s.contains("luminaHostBasePad"))
    }

    @Test
    fun `cancels flex and grid column gap under the spacer (iter 24)`() {
        assertTrue(script().contains("margin-bottom"))
    }

    @Test
    fun `re-inset on SPA client-side navigation (iter 25)`() {
        val s = script()
        assertTrue("wraps history for pushState/replaceState navs", s.contains("wrapHist"))
        assertTrue(s.contains("popstate"))
    }

    @Test
    fun `topPx of zero produces no spacer height`() {
        val s = script(topPx = 0)
        assertTrue(s.contains("const topPx=0;"))
    }

    @Test
    fun `two calls with the same args are deterministic`() {
        assertEquals(script(topPx = 60), script(topPx = 60))
    }

    @Test
    fun `header height cap is viewport-adaptive, not a hard 240 (iter 19)`() {
        val s = script()
        assertTrue("tall two-row headers must qualify", s.contains("window.innerHeight*0.45"))
        assertTrue("but full-screen overlays are still excluded", s.contains("Math.min(400,Math.max(240,"))
    }

    @Test
    fun `already-safe fixed headers are not double-padded (iter 22)`() {
        assertTrue(script().contains("pos==='fixed'&&rect.top>=topPx-1"))
    }

    @Test
    fun `skips the walk while hidden and restores when the inset collapses (iter 23, 29)`() {
        val s = script()
        assertTrue("no walk on background/hidden tabs", s.contains("if(document.hidden)return"))
        // topPx<=0 (immersive/fullscreen) must restore prior insets, not leave a phantom offset.
        assertTrue("collapse restores insets", s.contains("if(topPx<=0){"))
    }

    @Test
    fun `sticky headers below the fold are inset proactively (iter 27)`() {
        val s = script()
        assertTrue(s.contains("stickyReachesTop"))
        assertTrue("bounded to ~1.5 viewports", s.contains("window.innerHeight*1.5"))
    }

    @Test
    fun `deep sticky headers are caught via IntersectionObserver, not a scroll listener (iter 28)`() {
        val s = script()
        assertTrue(s.contains("__luminaStickyIO"))
        assertTrue(s.contains("new IntersectionObserver"))
        assertFalse("must not reintroduce a per-scroll listener", s.contains("addEventListener('scroll'"))
    }

    @Test
    fun `re-inset on late font and image reflow, bound once (iter 20)`() {
        val s = script()
        assertTrue("catch web-font swap reflow", s.contains("document.fonts.ready"))
        assertTrue("window load reflow", s.contains("addEventListener('load'"))
        assertTrue("listeners are deduped across repeated injections", s.contains("__luminaListenersBound"))
    }

    @Test
    fun `steady-state walk backs off once the header set is stable (iter 7)`() {
        assertTrue(script().contains("window.__luminaCooldownMs=window.__luminaStableCount>=3?2000:500"))
    }

    @Test
    fun `self-heal flag is reset in a finally so one bad header can't disable the observer (iter 18)`() {
        val s = script()
        assertTrue(s.contains("finally{"))
        assertTrue(s.contains("__luminaApplyingHeaderInsets=false"))
    }

    @Test
    fun `stuck sticky header paints the status-bar strip its own bg color (iter 36)`() {
        val s = script()
        // A stuck sticky sits at top=topPx, leaving the strip above it transparent; a solid
        // upward box-shadow in the header's own backgroundColor fills that strip so page content
        // isn't visible above the header.
        assertTrue("reads the header's own bg", s.contains("cs.backgroundColor"))
        assertTrue("paints an upward strip via box-shadow", s.contains("box-shadow"))
        assertTrue("strip height matches the top inset", s.contains("'0 -'+topPx+'px 0 0 '"))
        assertTrue("skips transparent headers", s.contains("rgba(0, 0, 0, 0)"))
        assertTrue("original shadow is saved for restore", s.contains("luminaOrigShadow"))
    }

    @Test
    fun `observer also reacts to hidden and aria-hidden toggles (iter 49)`() {
        // Headers/cookie banners revealed by removing a `hidden`/`aria-hidden` attribute must
        // trigger a re-inset; the attributeFilter has to include those attrs or the mutation is
        // never observed.
        val s = script()
        assertTrue(s.contains("attributeFilter:['class','style','slot','hidden','aria-hidden']"))
    }

    @Test
    fun `block-flow spacer sorts first and spans full width so centered app-shell content is pushed (google_com)`() {
        // google.com's homepage + search results are a flex/grid body with centered content; the
        // spacer div is only guaranteed to push that content down if it always sorts to the very
        // first slot (order) and spans every grid column (grid-column), regardless of source order.
        val s = script()
        assertTrue("spacer must sort before all real children", s.contains("order:-2147483647!important"))
        assertTrue("spacer must span all grid columns", s.contains("grid-column:1 / -1!important"))
        assertTrue("and take full width so it can't collapse", s.contains("width:100%!important"))
    }

    @Test
    fun `scroll-padding-top targets both html and body scroll roots (iter 41)`() {
        // Anchor jumps must land below the inset header whether html OR body is the scroll
        // container; scroll-padding only affects where anchored scrolls land, never layout, so
        // applying it to both is safe and covers more sites.
        assertTrue(
            "both scroll roots get scroll-padding-top",
            script().contains("html,body{scroll-padding-top:"),
        )
    }
}
