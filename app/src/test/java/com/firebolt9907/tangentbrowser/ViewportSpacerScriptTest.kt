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
    fun `applying the inset while scrolled does not jump the page (iter 53)`() {
        // Inserting the spacer above the current scroll position moves everything below it down by
        // topPx. Scroll anchoring normally hides that, but pages that opt out (overflow-anchor:none —
        // app feeds, virtualized lists) get no compensation and an already-scrolled reader sees the
        // page jump a status bar's worth. We measure a real FLOW element across the change (fixed /
        // sticky ancestors are skipped: those are supposed to move when we inset them) and scroll by
        // exactly the distance it moved — a no-op when the browser already compensated.
        val s = script()
        assertTrue(
            "the spacer application is wrapped in the compensator",
            s.contains("withScrollComp(function(){spacer(topId,top)})"),
        )
        assertTrue(
            "no-op at scroll-top",
            s.contains("var y0=window.scrollY;if(y0<=0){fn();return}"),
        )
        assertTrue(
            "the reference must be flow content, not something inside a fixed/sticky container",
            s.contains("if(c==='fixed'||c==='sticky'){bad=true;break}"),
        )
        // elementFromPoint returns a transparent full-screen scrim / drag layer even though it paints
        // nothing, which would cost us the compensation entirely. Walk the whole hit stack instead,
        // and sample three points so one overlay or gap can't defeat it.
        assertTrue(
            "walks the full hit stack, not just the topmost element",
            s.contains("document.elementsFromPoint?document.elementsFromPoint(x,y)"),
        )
        assertTrue(
            "falls back across three sample points",
            s.contains("flowRefAt(Math.floor(w/2),Math.floor(h/2))||flowRefAt(Math.floor(w/2),Math.floor(h/3))||flowRefAt(Math.floor(w/4),Math.floor(h*2/3))"),
        )
        assertTrue(
            "compensates by the measured delta, bounded so it can never run away",
            s.contains("if(Math.abs(dy)>1&&Math.abs(dy)<=topPx*2+8)window.scrollBy(0,dy)"),
        )
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
            s.contains("if(since<cd&&!bandChanged(now)&&"),
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
        assertTrue("samples just below the strip", s.contains("var ys=[topPx+2,topPx+6,topPx+12]"))
        assertTrue("computes a fill for the strip", s.contains("function topBg(sp)"))
        assertTrue("reads a solid or gradient bg per element", s.contains("function topPick(n)"))
        assertTrue("walks up to the first opaque background", s.contains("function topOpaque(c)"))
        assertTrue("applies the sampled bg to the spacer", s.contains("function paintStrip(el)"))
        assertTrue("carries a branded gradient header up into the strip", s.contains("/gradient/i.test(img)"))
        assertTrue("falls back to the page base color", s.contains("topPick(document.body)"))
    }

    @Test
    fun `the strip fill comes from the paint stack and clamps vertical gradients (iter 55)`() {
        // Two ways the strip ended up a different colour from the bar right under it:
        // (a) the fill was picked by walking the ANCESTOR chain of whatever hit-tests below the
        //     strip. On github that is a transparent `header` whose ancestors run straight to
        //     `body` (#0d1117) — the hero gradient that actually paints there hangs off a sibling
        //     subtree, so the strip came out page-dark above a blue bar. The paint stack contains
        //     the ancestors as well, so reading it strictly supersedes the old walk.
        // (b) a vertical gradient was copied into the strip, squashing the whole ramp into topPx
        //     and painting a colour from the MIDDLE of the bar. Continued upward past its first
        //     stop a gradient clamps, so the strip takes that stop's flat colour instead.
        val s = script()
        assertTrue(
            "the fill is read from the paint stack, not an ancestor walk",
            s.contains("document.elementsFromPoint?document.elementsFromPoint(x,y)"),
        )
        assertTrue(
            "a gradient with no opaque stop is an overlay, not the bar's fill",
            s.contains("function gradHasOpaque(img)") && s.contains("/gradient/i.test(img)&&gradHasOpaque(img)"),
        )
        assertTrue(
            "vertical gradients are flattened to their top-edge stop",
            s.contains("function gradFlat(img)") && s.contains("var flat=gradFlat(img);if(flat&&topOpaque(flat))return {c:flat}"),
        )
        assertTrue(
            "horizontal gradients stay uniform vertically, so they are still copied verbatim",
            s.contains("if(/to\\s+(left|right)/i.test(head)&&!/top|bottom/i.test(head))return ''"),
        )
        assertTrue(
            "body/html are the last resort, so a real bar always beats the page background",
            s.contains("n===document.documentElement||n===document.body)continue"),
        )
        assertTrue(
            "the fill is re-sampled after the headers are inset, against the final layout",
            s.contains("if(_sp&&_sp.style.display!=='none')paintStrip(_sp)"),
        )
    }

    @Test
    fun `a header that changes with scroll position keeps a stable inset (iter 56)`() {
        // Scroll-reactive headers (auto-hide on scroll-down, shrink once scrolled) broke the inset
        // two ways that only show up across a scroll cycle:
        //  (a) applyFixed grew a border-box header's height from the LIVE rect. On a header with
        //      `transition:height`, restore() clears our height, the box animates back over ~200ms,
        //      and the next pass measures a value still containing most of the previous inset and
        //      adds topPx again — 56px ratcheted to 200px across one scroll cycle. The base height
        //      is now measured with transitions suppressed, so the inset is idempotent.
        //  (b) a bar slid out of the band is restored; when the site slides it back in, the site
        //      writes the transform when the slide STARTS, so the mutation we observe still shows it
        //      off-screen and nothing fires when it lands. The re-inset waited for the next cooldown
        //      and the bar sat over the status bar for ~485ms on every scroll-up.
        val s = script()
        assertTrue(
            "the base height is measured with transitions suppressed",
            s.contains("function baseHeight(el)") && s.contains("el.style.setProperty('transition','none','important')"),
        )
        assertTrue(
            "the measurement excludes our own padding and height",
            s.contains("el.style.removeProperty('height');el.style.removeProperty('min-height');el.style.removeProperty('padding-top')"),
        )
        assertTrue(
            "the grown height comes from that base, never from the live rect",
            s.contains("const newH=(bh>0?bh:rect.height)+topPx"),
        )
        assertTrue(
            "bars we dropped are remembered so their return can trigger a walk",
            s.contains("function headerReturned()") && s.contains("!headerReturned()"),
        )
        assertTrue(
            "the dropped list accumulates until the bar is re-inset, instead of being wiped each pass",
            s.contains("var _d=(window.__tangentDropped||[]).filter("),
        )
        assertTrue(
            "a finished transition/animation re-runs the walk",
            s.contains("document.addEventListener('transitionend',function(){schedule()}") &&
                s.contains("document.addEventListener('animationend',function(){schedule()}"),
        )
    }

    @Test
    fun `the strip fill survives modal scrims and unreadable overlays (iter 57)`() {
        // A consent sheet or modal scrim is what the reader actually sees, so the strip has to show
        // the same composite. Three ways the naive "first background in the paint stack" answer was
        // wrong, all found by sweeping bandFill.js over the corpus:
        //  - a half-transparent scrim was copied onto the strip verbatim (topOpaque only rejected
        //    alpha exactly 0), where it composites over a different backdrop than over the page;
        //  - a full-screen cross-origin <iframe> consent sheet (spiegel's Sourcepoint modal) was
        //    walked straight THROUGH to the brand gradient behind it, painting a bright orange strip
        //    above a dark sheet;
        //  - a translucent scrim spanning the strip too was folded in, applying it twice and leaving
        //    the strip darker than the page under it (temu). An OPAQUE full-screen layer is NOT
        //    skipped: it covers the strip itself, so its colour is exactly what shows there.
        val s = script()
        assertTrue(
            "translucent layers are composited, not copied",
            s.contains("function topOver(t,b)") && s.contains("function topFold(layers,base)"),
        )
        assertTrue(
            "only a fully opaque layer ends the walk",
            s.contains("if(col.a>=0.999)return {c:layers.length?topFold(layers,col):r.c}"),
        )
        assertTrue(
            "a large unreadable replaced element hides what is behind it and stops the walk",
            s.contains("function topOccluder(n,r)") && s.contains("if(rect&&topOccluder(n,rect))return null"),
        )
        assertTrue(
            "small logos and icons must NOT count as occluders",
            s.contains("return r.width>=window.innerWidth*0.6&&r.height>=40"),
        )
        assertTrue(
            "a translucent overlay spanning the strip is skipped, not folded twice",
            s.contains("if(rect&&rect.top<=1&&rect.bottom>=topPx-1&&rect.height>topPx+4)continue"),
        )
        assertTrue(
            "collected layers fold onto the page base when no opaque layer is found",
            s.contains("if(layers.length&&base)return {c:topFold(layers,base)}"),
        )
        // A bar built entirely from transparent wrappers over a transparent body (udemy) yields no
        // opaque layer at any depth. Returning nothing leaves the strip transparent — the very
        // defect this fill exists to prevent — so the old ancestor walk stays as a fallback.
        assertTrue(
            "an empty paint stack falls back to the ancestor walk before giving up",
            s.contains("function topBgAnc(sp,y)") && s.contains("var a2=topBgAnc(sp,ys[j]);if(a2)return a2"),
        )
    }

    @Test
    fun `the strip fill ignores edge slivers and sees into shadow roots (iter 58)`() {
        // Two ways the sample landed on the wrong surface, both from the corpus sweep:
        //  - a thin sliver pinned at the strip's edge (a Material progress-bar track, a divider, a
        //    4px accent) is not the surface to match. Google's apps put one exactly at topPx, so the
        //    strip came out #1e1f20 above a #0e0e0e app background on mail/docs/accounts.
        //  - elementsFromPoint stops at a shadow HOST, so a header rendered inside a shadow root
        //    (paypal's #shadow-host-header) was invisible and the strip took the warm page
        //    background behind the host instead of the white nav. collect() already pierces shadow
        //    roots for the header walk; the colour sampling has to as well.
        val s = script()
        assertTrue(
            "a sliver at the strip edge cannot define the fill",
            s.contains("if(rect&&rect.height<16)continue;"),
        )
        assertTrue(
            "the sample stack pierces shadow roots",
            s.contains("function topStack(x,y,root,depth)") && s.contains("if(depth<3&&n.shadowRoot)"),
        )
        assertTrue(
            "shadow content paints above its host, so it is sampled first",
            s.contains("for(var j=0;j<inner.length;j++){if(inner[j]!==n)out.push(inner[j])}"),
        )
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

    @Test
    fun `a site rewriting its own header's inline styles re-triggers the walk (iter 54)`() {
        // Our insets are inline !important, which wins the CASCADE — but a site that drives its
        // header from JS (auto-hiding bars, `style.cssText = …` rewrites) REPLACES the declaration
        // outright. Two throttles used to swallow that: the MutationObserver dismissed any record
        // whose target is an element we already inset as "ours", and the walk's 2000ms cooldown
        // blocked the re-apply. Both must yield to a clobber check, or the header sits under the
        // status bar for most of every cooldown (measured 88% of frames on the autohide fixture).
        val s = script()
        assertTrue(
            "every inline inset we apply is recorded so a clobber is detectable",
            s.contains("function want(el,prop,val){el.style.setProperty(prop,val,'important');(el.__tangentWant=el.__tangentWant||{})[prop]=val}"),
        )
        assertTrue(
            "the header inset paths go through want(), not raw setProperty",
            s.contains("want(el,'padding-top',(px(cs.paddingTop)+topPx)+'px');want(el,'top',Math.max(0,px(cs.top))+'px')"),
        )
        assertTrue(
            "a clobber detector compares our recorded declarations against the live style",
            s.contains("if(el.style.getPropertyValue(k)!==w[k])return true"),
        )
        assertTrue(
            "the observer's all-ours early-return yields to the clobber check",
            s.contains("if(allOurs&&!insetsClobbered())return;schedule()"),
        )
        assertTrue(
            "the walk's cooldown also yields to the clobber check",
            s.contains("if(since<cd&&!bandChanged(now)&&!insetsClobbered()&&"),
        )
    }
}
