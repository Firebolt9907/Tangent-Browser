package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.buildViewportSpacerScript
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
        // allOurs must accept tangentIgnoreSafeTop targets, else the spacer's per-pass style
        // write self-triggers a perpetual full-document walk.
        assertTrue(script().contains("tangentIgnoreSafeTop==='true'"))
    }

    @Test
    fun `handles flex-row bodies via host padding fallback (iter 26 and 28)`() {
        val s = script()
        assertTrue("detects nowrap row flex hosts", s.contains("flexRow"))
        // iter 28: adds topPx to the site's existing padding rather than replacing it.
        assertTrue("preserves the site's own body padding", s.contains("tangentHostBasePad"))
    }

    @Test
    fun `grid bodies use the host-padding fallback, not the in-flow spacer div (iter 28)`() {
        // A display:grid body places its header via grid-template-areas / explicit lines, so an
        // in-flow spacer div (even with grid-column:1/-1 + order) can't push an area-placed header
        // out of the status-bar band — but padding the grid container shifts every track down.
        // So grid (like nowrap row-flex) takes the host-padding branch. Repro: learn.microsoft.com.
        val s = script()
        assertTrue(
            "detects grid/inline-grid hosts",
            s.contains("const isGrid=(hdisp==='grid'||hdisp==='inline-grid')"),
        )
        assertTrue(
            "grid hosts share the flex-row host-padding branch",
            s.contains("if(flexRow||isGrid){"),
        )
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
        assertTrue(s.contains("__tangentStickyIO"))
        assertTrue(s.contains("new IntersectionObserver"))
        assertFalse("must not reintroduce a per-scroll listener", s.contains("addEventListener('scroll'"))
    }

    @Test
    fun `re-inset on late font and image reflow, bound once (iter 20)`() {
        val s = script()
        assertTrue("catch web-font swap reflow", s.contains("document.fonts.ready"))
        assertTrue("window load reflow", s.contains("addEventListener('load'"))
        assertTrue("listeners are deduped across repeated injections", s.contains("__tangentListenersBound"))
    }

    @Test
    fun `steady-state walk backs off once the header set is stable (iter 7)`() {
        assertTrue(script().contains("window.__tangentCooldownMs=window.__tangentStableCount>=3?2000:500"))
    }

    @Test
    fun `the cooldown is bypassed when something NEW paints in the band (iter 52)`() {
        // The relaxed 2000ms cooldown bounds the cost of the full-document walk, but it also let a
        // header mounted by an SPA route change sit UNDER the status bar for ~1.5s. The walk now
        // runs immediately when the top-painted element at three sample points across the strip
        // differs from the previous sample — a correctly inset header samples the same element every
        // pass (no bypass), a newly mounted one does not. Rate-limited so a page animating under the
        // bar can't turn this into a per-frame walk.
        val s = script()
        assertTrue(
            "the cooldown early-return is gated on the band being unchanged",
            s.contains("if(since<cd&&!bandChanged(now)){"),
        )
        assertTrue(
            "samples the top-painted element across the strip, ignoring our own spacer",
            s.contains("var e=document.elementFromPoint(xs[i],y);out.push((e&&e.id===topId)?null:(e||null))"),
        )
        assertTrue(
            "bypass is rate-limited and remembers the last sample",
            s.contains("if(now-(window.__tangentLastBypassMs||0)<300)return false") &&
                s.contains("window.__tangentBandSamples=s"),
        )
    }

    @Test
    fun `self-heal flag is reset in a finally so one bad header can't disable the observer (iter 18)`() {
        val s = script()
        assertTrue(s.contains("finally{"))
        assertTrue(s.contains("__tangentApplyingHeaderInsets=false"))
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
        assertTrue("original shadow is saved for restore", s.contains("tangentOrigShadow"))
    }

    @Test
    fun `in-flow spacer strip is filled with the color painted below it (iter 50)`() {
        // A static (in-flow) colored header the spacer merely pushes down would otherwise leave a
        // transparent gap above it in the status-bar band. The spacer samples the element painted at
        // its bottom edge (elementFromPoint) and paints itself that element's first opaque bg, so a
        // colored nav bar reads as continuous to the screen top. Falls back to body/html base color.
        val s = script()
        assertTrue("samples the element below the strip", s.contains("elementFromPoint(x,topPx+2)"))
        assertTrue("computes a fill for the strip", s.contains("function topBg(sp)"))
        assertTrue("reads a solid or gradient bg per element", s.contains("function topPick(n)"))
        assertTrue("walks up to the first opaque background", s.contains("function topOpaque(c)"))
        assertTrue("applies the sampled bg to the spacer", s.contains("var __sbg=topBg(el)"))
        assertTrue("carries a branded gradient header up into the strip", s.contains("/gradient/i.test(img)"))
        assertTrue("falls back to the page base color", s.contains("topPick(document.body)"))
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
    fun `click-through (pointer-events none) header with interactive children is still inset (youtube_com)`() {
        // m.youtube.com's fixed masthead (ytm-mobile-topbar-renderer) is `pointer-events:none` so
        // taps fall through its transparent regions; the real buttons inside re-enable
        // `pointer-events:auto`. The old guard lumped `pointerEvents==='none'` in with
        // display/visibility/opacity and skipped the whole header, so its buttons never got top
        // padding and sat under the status bar. A pointer-events:none element must only be excluded
        // when it is a true click-through scrim — i.e. it has NO interactive descendants.
        val s = script()
        assertFalse(
            "pointer-events:none must not be a blanket exclusion anymore",
            s.contains("opacity==='0'||cs.pointerEvents==='none')return false"),
        )
        assertTrue(
            "pointer-events:none only excludes when there's no interactive content",
            s.contains("cs.pointerEvents==='none'&&!el.querySelector("),
        )
        assertTrue(
            "interactive-content probe covers real header controls",
            s.contains("'a,button,input,select,textarea,[role=\"button\"],[role=\"link\"],[tabindex],[onclick]'"),
        )
    }

    @Test
    fun `out-of-flow top-band elements (absolute or fixed corner clusters) are shifted below the bar (google_com)`() {
        // Google's homepage sign-in / apps cluster is position:absolute pinned to the top corner, so
        // the flow spacer never moves it and isCandidate's 30%-width filter rejects it — it sat under
        // the status bar. Any small fixed/absolute element painting inside the [0,topPx) band is now
        // translated down by topPx (bandShift). Verified in a headless-Chrome harness against a
        // reproduction of the cluster: it moved from top=0 to top=48.
        val s = script()
        assertTrue(
            "absolute elements are now scanned, not just fixed/sticky",
            s.contains("pos!=='fixed'&&pos!=='sticky'&&pos!=='absolute'"),
        )
        assertTrue("only elements intruding into the status-bar band are shifted", s.contains("rect.top<=topPx-2"))
        assertTrue("the shift is a topPx translateY", s.contains("translateY('+topPx+'px)"))
        assertTrue("original transform is saved and restored on collapse", s.contains("tangentOrigTransform"))
        assertTrue("band shift is height-bounded so full overlays aren't translated", s.contains("rect.height<=160"))
    }

    @Test
    fun `full-screen fixed overlay with top content is padded so its search input clears the bar (google_com search)`() {
        // Tapping Google's search box opens a full-screen position:fixed overlay with the input at
        // its top. It's too tall to be a header (isCandidate rejects it) and too tall for bandShift,
        // so the input stayed under the status bar. A top-pinned fixed/absolute layer taller than the
        // header cap, covering the width, whose in-flow content paints in the band now gets
        // padding-top:topPx (applyOverlay). This also covers full-height fixed app-shell roots.
        val s = script()
        assertTrue(
            "collects top-pinned oversized layers as overlays",
            s.contains("rect.height>headerCap&&rect.width>=window.innerWidth*0.6"),
        )
        assertTrue("only pads overlays that actually have in-flow content in the band", s.contains("overlayHasBandContent"))
        assertTrue("the overlay branch pads rather than translates", s.contains("function applyOverlay("))
    }

    @Test
    fun `a full-cover absolute child of a chosen fixed header is padded, not band-shifted (apple_com)`() {
        // apple.com's `nav#globalnav` is position:fixed and its whole visible bar is a
        // position:absolute inset:0 child (.globalnav-content) that holds BOTH the translucent
        // background AND the Apple/search/bag icons. Padding the nav stretches that child so its
        // background fills the reserved strip, but the icons are laid out from the child's own top
        // and stay under the status bar. Band-shifting the child (the old fix) moved the icons but,
        // because padding had already stretched the child to nav-height + topPx, translating it
        // another topPx dragged the translucent bar topPx below the nav, washing out the page
        // content underneath — the "apple.com bleed". The correct fix mirrors the header's padding
        // onto the full-cover child (padCover): its content shifts into the safe area while its
        // background keeps covering the strip, and the child is marked so the intruder pass does
        // NOT also band-shift it.
        val s = script()
        assertTrue(
            "full-cover inset:0 absolute children of a padded header get the same padding-top",
            s.contains("function padCover(el)") && s.contains("padCover(el);el.dataset.tangentSafeTopApplied='true'"),
        )
        assertTrue(
            "only inset:0 (top:0 && bottom:0) fixed-height cover layers qualify",
            s.contains("kcs.top==='0px'&&kcs.bottom==='0px'&&kcs.height!=='auto'"),
        )
        assertTrue(
            "a padded cover child is marked so it is not double-offset by bandShift",
            s.contains("k.dataset.tangentCoverPadded='true'") &&
                s.contains("if(it.el.dataset&&it.el.dataset.tangentCoverPadded==='true')return false"),
        )
        assertTrue(
            "the cover mark is cleared on collapse so a later pass can re-pad",
            s.contains("el.dataset.tangentCoverPadded='false'"),
        )
        // The pattern isn't apple-specific: every code path that RESERVES the strip by padding the
        // element (applyFixed for pinned headers, applyOverlay for full-screen app-shell overlays)
        // runs padCover, so any site whose padded container hides its content in a full-cover
        // absolute child is handled. applySticky is intentionally excluded — it shifts `top` instead
        // of padding, so the header's content moves with it and there is nothing to mirror.
        assertTrue(
            "both padding paths (fixed header + full-screen overlay) run padCover",
            s.contains("padCover(el);el.dataset.tangentSafeTopApplied='true'"),
        )
        // A standalone intruder (NOT inside any chosen header) is only shielded by a NON-chosen
        // containing intruder — that clause is unchanged.
        assertTrue(
            "an intruder is only shielded by a NON-chosen containing intruder",
            s.contains("o.el.contains(it.el)&&!isChosenEl(o.el)"),
        )
    }

    @Test
    fun `an absolute descendant of a chosen header is never band-shifted on top of its padding (support_google_com)`() {
        // support.google.com's fixed #gb header contains the breadcrumb title in a position:absolute
        // box (.gb_Ae) whose containing block is an in-flow (position:relative) wrapper INSIDE the
        // header. applyFixed's padding-top already shifts that wrapper down by topPx, carrying the
        // absolute breadcrumb with it. The intruder pass then ALSO matched the breadcrumb (absolute,
        // near the top) and band-shifted it a second topPx, so it slid a full header-height below
        // where it belonged and collided with the "Help Center / Community" tab bar. A chosen header
        // owns its whole subtree (padding + padCover already place it), so the intruder pass must
        // skip anything a chosen header CONTAINS — not only its ancestors/wrappers.
        val s = script()
        assertTrue(
            "a chosen header shields its own descendants from a second (band-shift) offset",
            s.contains("chosenEls.some(function(h){return h===it.el||it.el.contains(h)||h.contains(it.el)})"),
        )
    }

    @Test
    fun `header-inset scheduling is a non-starvable pending-flag debounce (figma_com)`() {
        // On a mutation-heavy SPA (figma.com), the old schedule() did
        // cancelAnimationFrame(pending) + requestAnimationFrame(applyHeaderInsets) on EVERY
        // MutationObserver hit. Continuous mutations cancelled the pending frame before it could
        // run, so applyHeaderInsets NEVER executed and fixed headers were never inset. The fix is a
        // pending-flag debounce: once a frame is queued, further schedule() calls are no-ops until
        // it fires, so the pass can't be perpetually starved.
        val s = script()
        assertTrue(
            "schedule guards on a pending flag so it can't be re-cancelled forever",
            s.contains("function schedule(){if(window.__tangentHeaderInsetPending)return;window.__tangentHeaderInsetPending=true;"),
        )
        assertTrue(
            "the queued frame clears the pending flag before running the pass",
            s.contains("window.__tangentHeaderInsetPending=false;applyHeaderInsets()"),
        )
        assertFalse(
            "must not go back to the starvable cancel+reschedule pattern",
            s.contains("cancelAnimationFrame(window.__tangentHeaderInsetFrame"),
        )
    }

    @Test
    fun `first header-inset pass is synchronous so it shares one layout pass with the body spacer`() {
        // The body spacer div is inserted synchronously; if the fixed/sticky header inset is only
        // queued via schedule() (rAF), the header visibly jumps down a frame after content already
        // shifted. The first pass must call applyHeaderInsets() directly; only the follow-up passes
        // go through the debounced schedule().
        val s = script()
        assertTrue(
            "first pass runs synchronously",
            s.contains("applyHeaderInsets();setTimeout(schedule,1000);"),
        )
        assertFalse(
            "must not defer the first pass to the next frame",
            s.contains("if(!skipHeader){schedule();"),
        )
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

    @Test
    fun `scroll-padding-top is also set inline with importance to win over site CSS (iter 22)`() {
        // A site rule like `:root{scroll-padding-top:0!important}` (specificity 0,1,0) beats our
        // stylesheet `html,body{...!important}` (0,0,1), zeroing the inset so anchor jumps land
        // under the status bar. Author inline `!important` outranks any author stylesheet
        // `!important` regardless of selector specificity, so we ALSO set it inline on the scroll
        // roots. Keep both — the stylesheet is the fallback, the inline decl is the cascade winner.
        val s = script()
        assertTrue(
            "scroll-padding-top is set inline on documentElement with importance",
            s.contains("de.style.setProperty('scroll-padding-top',effS,'important')"),
        )
        assertTrue(
            "scroll-padding-top is set inline on body with importance",
            s.contains("body.style.setProperty('scroll-padding-top',effS,'important')"),
        )
    }

    @Test
    fun `scroll-padding-top ADDS topPx to the site's own value, not replaces it (iter 25)`() {
        // A site that sets its OWN root scroll-padding-top (e.g. `html{scroll-padding-top:64px}`)
        // to clear its own sticky header would, if we simply forced the value to topPx, have anchor
        // jumps land UNDER that header — because we ALSO inset the header down by topPx (to
        // [topPx, topPx+64]). The correct clearance is (site value)+topPx. ensureStyle captures the
        // site's own value by reading it with our overrides neutralized, then adds topPx.
        val s = script()
        assertTrue(
            "captures the site's own scroll-padding-top with our overrides removed",
            s.contains("de.style.removeProperty('scroll-padding-top')") &&
                s.contains("base=px(getComputedStyle(de).scrollPaddingTop)"),
        )
        assertTrue(
            "the floor is the site's base plus topPx (reduces to topPx when base is 0)",
            s.contains("var floor=(base>0?base:0)+topPx"),
        )
    }

    @Test
    fun `scroll-padding-top rises to the bottom of any wide top-pinned bar we leave or inset (iter 26)`() {
        // A site whose own fixed nav sits at the top (whether we inset it, or it already sits just
        // below the status bar like kubernetes.io) covers anchor targets when scroll-padding-top is
        // only topPx. After the header pass we measure every wide (>=60% vw) fixed/sticky bar pinned
        // in the top band and raise scroll-padding-top to the lowest such bottom (clamped to
        // topPx + half the viewport so a tall overlay can't run it away), then re-apply via setSPT.
        val s = script()
        assertTrue(
            "collects wide top-pinned fixed/sticky bars",
            s.contains("topBars.push(el)"),
        )
        assertTrue(
            "want is seeded from the site+topPx floor and clamped to half the viewport",
            s.contains("var sptWant=window.__tangentSptFloor||topPx") &&
                s.contains("var sptCap=topPx+window.innerHeight*0.5"),
        )
        assertTrue(
            "the applied value is a grow-only latch (max of prior, this pass's want, and the floor)",
            s.contains("var _applied=Math.max(window.__tangentSptApplied||0,Math.round(sptWant),window.__tangentSptFloor||topPx)") &&
                s.contains("window.__tangentSptApplied=_applied") &&
                s.contains("setSPT(_applied)"),
        )
        assertTrue(
            "ensureStyle never drops scroll-padding below the latched value (anti-flicker)",
            s.contains("var eff=Math.max(floor,window.__tangentSptApplied||0)"),
        )
    }

    @Test
    fun `a viewport-locked app shell is padded in place, not pushed off the bottom (iter 51)`() {
        // An app shell locked to the viewport height (height:100vh/100dvh, or 100% of a locked
        // html/body chain) does not shrink when the in-flow spacer pushes it down — its bottom
        // topPx pixels leave the screen with the bottom nav / player / composer, and in a
        // scroll-locked document (html or body overflow:hidden, the app-shell norm) they can never
        // be scrolled back. In that shape we pad the SHELL itself with border-box instead.
        val s = script()
        assertTrue(
            "only applies to scroll-locked documents",
            s.contains("if(hs.overflowY!=='hidden'&&bs.overflowY!=='hidden')return null"),
        )
        assertTrue(
            "matches a wide top child whose outer OR content height equals the viewport",
            s.contains("Math.abs(r.height-vh)<=4||Math.abs(inner-vh)<=4"),
        )
        assertTrue(
            "pads with border-box so the shell keeps its viewport height",
            s.contains("k.style.setProperty('box-sizing','border-box','important')"),
        )
        assertTrue(
            "the inset ADDS to the shell's own padding from a saved base (no ratcheting)",
            s.contains("k.dataset.tangentShellBasePad=px(kcs.paddingTop)") &&
                s.contains("var tgt=(px(k.dataset.tangentShellBasePad)+topPx)+'px'"),
        )
        assertTrue(
            "the in-flow spacer is suppressed on this path, and a previous shell is restored",
            s.contains("padShell(shell);capNested(shell);window.__tangentShellEl=shell;el.style.cssText='display:none!important;';return") &&
                s.contains("unpadShell(window.__tangentShellEl)"),
        )
        // `vh` always means the FULL viewport — nothing in the page can redefine it — so a shell
        // that nests another viewport-locked layer (web.telegram.org) needs the inner one CAPPED,
        // and a `display:contents` wrapper (telegram K's #auth-flow-root) has no box to pad, so the
        // scan has to look through it at the real shell.
        assertTrue(
            "the scan looks through display:contents wrappers",
            s.contains("if(kcs.display==='contents'){for(i=0;i<k.children.length&&i<20;i++)q.push(k.children[i]);continue}"),
        )
        assertTrue(
            "nested viewport-locked layers are capped to the remaining height, restorably",
            s.contains("k.style.setProperty('max-height','calc(100vh - '+topPx+'px)','important')") &&
                s.contains("window.__tangentShellCapped") &&
                s.contains("function uncapNested()"),
        )
        // The soft keyboard shrinks window.innerHeight but not the large-viewport `vh` unit, so a
        // shell that is still exactly 100vh tall stops matching h ~= innerHeight. Without a sticky
        // re-match the inset is torn off mid-typing and swaps to the in-flow spacer, which brings
        // the bottom clipping back.
        assertTrue(
            "an already-chosen shell is kept while it stays in flow, wide and at least viewport-tall",
            s.contains("var prev=window.__tangentShellEl;if(prev&&prev.isConnected&&host.contains(prev))") &&
                s.contains("if(pr.width>=window.innerWidth*0.8&&pr.top<=topPx+2&&pr.height>=vh-4)return prev"),
        )
        // ...but only as a FALLBACK, after the scan. Short-circuiting on the remembered shell made
        // x.com's SPA route change keep insetting the OLD shell while the new route's header sat in
        // the band. A fresh match must always win.
        assertTrue(
            "the remembered shell is consulted only after the scan finds nothing",
            s.indexOf("Math.abs(r.height-vh)<=4||Math.abs(inner-vh)<=4") <
                s.indexOf("var prev=window.__tangentShellEl"),
        )
        assertTrue(
            "a shell that is itself the scroll container gets its own scroll-padding-top",
            s.contains("if(scs.overflowY==='auto'||scs.overflowY==='scroll')") &&
                s.contains("k.style.setProperty('scroll-padding-top',(window.__tangentSptEffPx||topPx)+'px','important')"),
        )
    }
}
