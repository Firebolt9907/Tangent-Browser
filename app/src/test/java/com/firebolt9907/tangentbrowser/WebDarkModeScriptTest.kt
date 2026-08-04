package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.DarkCutoutAssets
import com.firebolt9907.tangentbrowser.ui.content.WebDarkMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pins the shape of the injected high-performance web-dark-mode scripts (Tier 2 fallback). The script
 * runs in Gecko and has no other coverage; these guards keep the load-bearing pieces from regressing.
 * See `ui/content/WebDarkMode.kt` for the two-tier rationale.
 */
class WebDarkModeScriptTest {

    @Test
    fun `apply is a single-line javascript url`() {
        val s = WebDarkMode.buildApplyScript()
        assertTrue("must be a javascript: URL", s.startsWith("javascript:"))
        assertFalse("flattened to one line so it stays a valid javascript: URL", s.contains("\n"))
    }

    @Test
    fun `apply uses a hue-preserving composited invert, not per-element recolor`() {
        val s = WebDarkMode.buildApplyScript()
        // The whole performance premise: one GPU-composited filter, not DarkReader-style color math.
        assertTrue(s.contains("invert(1) hue-rotate(180deg)"))
    }

    @Test
    fun `apply softens contrast for comfort but keeps media faithful`() {
        val s = WebDarkMode.buildApplyScript()
        // Page gets a mild contrast<1 so pure black/white become comfortable grays (DarkReader-like).
        assertTrue("page softened", s.contains("contrast(0.9)"))
        // Media carries the inverse contrast so photos net ~contrast(1.0) and stay crisp.
        assertTrue("media kept faithful", s.contains("contrast(1.1111)"))
    }

    @Test
    fun `apply re-inverts media so images and video render normally`() {
        val s = WebDarkMode.buildApplyScript()
        // Media must be double-inverted (→ identity) or photos come out as negatives.
        assertTrue(s.contains("'img'"))
        assertTrue(s.contains("'video'"))
        assertTrue(s.contains("[style*=\"background-image\"]"))
        // ...except images the classifier tags as graphics, which must invert WITH the page.
        assertTrue("keep-tagged graphics excluded from counter-invert", s.contains(":not([data-tangent-dark-keep])"))
    }

    @Test
    fun `default apply runs the structural image classifier but not pixel analysis`() {
        val s = WebDarkMode.buildApplyScript()
        // Tier 2 classifier runs on the default path (tags logos/icons so they don't vanish).
        assertTrue("classifier runs", s.contains("classifyImages"))
        assertTrue("tags graphics to keep", s.contains("data-tangent-dark-keep"))
        assertTrue("structural signals", s.contains("isGraphicImg"))
        // Tier 3 (pixel decode) stays off unless explicitly enabled.
        assertTrue("analyze flag off", s.contains("var ANALYZE=false;"))
        assertFalse("no pixel decode on the default path is guarded by the flag", s.contains("var ANALYZE=true;"))
    }

    @Test
    fun `analyze apply enables pixel-content image classification`() {
        val s = WebDarkMode.buildApplyScript(analyze = true)
        assertTrue("analyze flag on", s.contains("var ANALYZE=true;"))
        assertTrue("decodes pixels for classification", s.contains("getImageData"))
        assertTrue("caches verdicts by url", s.contains("__lumImgVerdict"))
    }

    @Test
    fun `apply never inverts a directly-viewed image, video, or pdf document`() {
        val s = WebDarkMode.buildApplyScript()
        assertTrue("media-document guard present", s.contains("isMediaDoc"))
        assertTrue("checks the document content type", s.contains("document.contentType"))
        assertTrue("excludes pdf", s.contains("application/pdf"))
    }

    @Test
    fun `apply only darkens pages that stay light under the native override`() {
        val s = WebDarkMode.buildApplyScript()
        // The luminance gate is what lets Tier 1 (native dark themes) win with zero filter cost.
        assertTrue(s.contains("pageIsDark"))
        assertTrue(s.contains("getComputedStyle"))
    }

    /*
     * The already-dark detector. A false negative inverts a page that is already dark — the most
     * visible way this feature breaks — and each signal below exists because body/html's declared
     * background alone misses a whole class of real dark themes. Verified end-to-end in
     * tools/dark-mode-harness (`node detect.js`); four of its seven fixtures fail against the previous
     * implementation. These pins are the cheap guard that a future edit doesn't drop one.
     */

    @Test
    fun `detector samples the painted surface, not just body and html`() {
        val s = WebDarkMode.buildApplyScript()
        // Sites overwhelmingly paint their dark surface on a full-bleed wrapper and leave the document
        // background at the light default, so body/html alone read the page as light.
        assertTrue("hit-tests the viewport", s.contains("elementFromPoint"))
        assertTrue("walks up to the first opaque background", s.contains("paintedLum"))
    }

    @Test
    fun `detector reports the lightest probed surface so a dark hero cannot fake a dark page`() {
        val s = WebDarkMode.buildApplyScript()
        // Unanimity, encoded as a max: a LIGHT page whose dark hero fills the viewport must still be
        // inverted. Averaging the probes would call that page dark and leave a light site light.
        assertTrue("keeps the lightest, not the mean", s.contains("if(l>worst)worst=l"))
        // ...and overriding a light document background needs the text colour to agree too.
        assertTrue("corroborated by text colour", s.contains("fgLum"))
    }

    @Test
    fun `detector treats a declared dark color-scheme as dark, not as no background`() {
        val s = WebDarkMode.buildApplyScript()
        // "No opaque background" used to mean "the UA canvas is white". Under `color-scheme: dark`
        // that canvas is dark — which is exactly how many sites ship their dark theme.
        assertTrue(s.contains("schemeIsDark"))
        assertTrue("reads the CSS property", s.contains("color-scheme"))
        assertTrue("and the meta tag", s.contains("meta[name=\"color-scheme\"]"))
    }

    @Test
    fun `detector measures with our own filter class off`() {
        val s = WebDarkMode.buildApplyScript()
        // Our class puts an opaque #fafafa background and color-scheme:dark on <html>. Measuring with
        // it on reads our own output back, so a page relying on the UA canvas could never be
        // recognised as dark on a re-check — the filter would latch on forever.
        assertTrue("drops the class to measure", s.contains("classList.remove(TG_CLS)"))
        assertTrue("and always puts it back", s.contains("finally{if(had)"))
    }

    @Test
    fun `apply re-checks late enough for a theme that lands after hydration`() {
        val s = WebDarkMode.buildApplyScript()
        assertTrue(s.contains("setTimeout(apply,300)"))
        assertTrue(s.contains("setTimeout(apply,1200)"))
        // Heavy app shells build the surface carrying the dark theme well after 1.2s.
        assertTrue("late pass for slow hydration", s.contains("setTimeout(apply,3000)"))
    }

    @Test
    fun `the filter and dynamic tiers share one detector`() {
        // Two copies drifting apart means the same site is judged differently depending on which tier
        // the user has enabled.
        for (token in listOf("paintedLum", "schemeIsDark", "measureDark", "function pageIsDark(deep)")) {
            assertTrue("apply missing '$token'", WebDarkMode.buildApplyScript().contains(token))
            assertTrue("dynamic missing '$token'", WebDarkMode.buildDynamicScript().contains(token))
        }
    }

    @Test
    fun `default apply does not run the aggressive background scan`() {
        val s = WebDarkMode.buildApplyScript(aggressive = false)
        assertTrue("aggressive flag is off", s.contains("var AGG=false;"))
        // The scan is gated on AGG at runtime, so the default path pays no getComputedStyle walk.
    }

    @Test
    fun `aggressive apply tags class-based background images and observes new nodes`() {
        val s = WebDarkMode.buildApplyScript(aggressive = true)
        assertTrue("aggressive flag is on", s.contains("var AGG=true;"))
        assertTrue("tags url() backgrounds", s.contains("data-tangent-dark-bg"))
        assertTrue("only tags real image backgrounds, not gradients", s.contains("indexOf('url(')"))
        assertTrue("incremental observer for new nodes", s.contains("__tangentDarkObserver"))
        assertTrue("bounded scan", s.contains("SCAN_CAP"))
    }

    @Test
    fun `dynamic script is a single-line javascript url`() {
        val s = WebDarkMode.buildDynamicScript()
        assertTrue("must be a javascript: URL", s.startsWith("javascript:"))
        assertFalse("flattened to one line so it stays a valid javascript: URL", s.contains("\n"))
    }

    @Test
    fun `dynamic recolors per-section instead of applying a global invert filter`() {
        val s = WebDarkMode.buildDynamicScript()
        // The whole point: NO page-wide invert filter — it remaps colors element by element.
        assertFalse("no global invert", s.contains("invert(1) hue-rotate(180deg)"))
        assertTrue("reads computed styles per element", s.contains("getComputedStyle"))
        assertTrue("remaps background", s.contains("background-color"))
        assertTrue("remaps text", s.contains("'color'"))
        assertTrue("works in HSL to preserve hue", s.contains("rgbToHsl") && s.contains("hslToRgb"))
    }

    @Test
    fun `dynamic preserves already-dark sections and darkens light ones`() {
        val s = WebDarkMode.buildDynamicScript()
        // Dark backgrounds below the hinge pass through unchanged (Apple's dark iPhone tile survives).
        assertTrue("background lightness map with pass-through hinge", s.contains("mapBgL"))
        assertTrue("text lightness map", s.contains("mapFgL"))
    }

    @Test
    fun `dynamic leaves media and svg untouched so photos and logos stay correct`() {
        val s = WebDarkMode.buildDynamicScript()
        // No inversion means no counter-invert/classification needed — media is simply skipped.
        assertTrue("skips media tags", s.contains("isMedia"))
        assertTrue("skips svg namespace", s.contains("svg"))
    }

    @Test
    fun `dynamic decides light-vs-dark once before it mutates any color`() {
        val s = WebDarkMode.buildDynamicScript()
        // Latch prevents a re-decide from seeing our own darkening and wrongly bailing.
        assertTrue(s.contains("__lumDynActive"))
        assertTrue(s.contains("pageIsDark"))
        assertTrue("still guards media documents", s.contains("isMediaDoc"))
    }

    @Test
    fun `cutout script is a single-line javascript url that boots the driver with wasm and worker`() {
        val s = WebDarkMode.buildImageCutoutScript()
        assertTrue("must be a javascript: URL", s.startsWith("javascript:"))
        assertFalse("flattened to one line so it stays a valid javascript: URL", s.contains("\n"))
        // Boots the page-context driver with the embedded WASM + worker blobs and the tuning params.
        assertTrue("defines the driver", s.contains("function lumCutSetup"))
        assertTrue("boots it", s.contains("lumCutSetup("))
        assertTrue("passes the wasm blob", s.contains(DarkCutoutAssets.WASM_B64))
        assertTrue("passes the worker blob", s.contains(DarkCutoutAssets.WORKER_B64))
        // No // line comments survived flattening (they'd eat the rest of the one-line URL).
        assertFalse("driver is block-comment-only", DarkCutoutAssets.DRIVER_JS.contains("//"))
    }

    @Test
    fun `cutout embeds a real wasm module exporting the kernel entry points`() {
        val wasm = Base64.getDecoder().decode(DarkCutoutAssets.WASM_B64)
        // WASM magic number "\0asm".
        assertTrue("valid wasm header", wasm.size > 8 &&
            wasm[0] == 0x00.toByte() && wasm[1] == 0x61.toByte() &&
            wasm[2] == 0x73.toByte() && wasm[3] == 0x6d.toByte())
        // Export names live verbatim in the export section of the binary.
        val text = String(wasm, Charsets.ISO_8859_1)
        assertTrue("exports cutout", text.contains("cutout"))
        assertTrue("exports pixels_ptr", text.contains("pixels_ptr"))
        assertTrue("exports capacity", text.contains("capacity"))
        assertTrue("exports blur_alpha", text.contains("blur_alpha"))
        assertTrue("exports smooth_rgb", text.contains("smooth_rgb"))
        assertTrue("exports to_alpha_mask", text.contains("to_alpha_mask"))
    }

    @Test
    fun `cutout is non-destructive - applies a CSS mask, never swaps the image src`() {
        // Blur fix: the image pixels are never replaced; we mask the original so it stays full-res.
        assertTrue("worker collapses to an alpha mask in the kernel",
            String(Base64.getDecoder().decode(DarkCutoutAssets.WORKER_B64), Charsets.UTF_8).contains("to_alpha_mask"))
        assertTrue("driver applies a CSS mask", DarkCutoutAssets.DRIVER_JS.contains("mask-image"))
        assertTrue("mask reads the alpha channel", DarkCutoutAssets.DRIVER_JS.contains("mask-mode"))
        assertFalse("never swaps the src (that caused the blur)", DarkCutoutAssets.DRIVER_JS.contains("img.src = bu"))
    }

    @Test
    fun `cutout keying is adaptive to the measured background, not an absolute contrast`() {
        // The kernel takes a colorBand (distance from the seed/border mean) so keying is relative to
        // the image's own background rather than a hardcoded threshold; the host threads it through.
        assertTrue("host passes colorBand", WebDarkMode.buildImageCutoutScript().contains("colorBand:"))
        assertTrue("worker passes colorBand to the kernel",
            String(Base64.getDecoder().decode(DarkCutoutAssets.WORKER_B64), Charsets.UTF_8).contains("P.colorBand"))
    }

    @Test
    fun `ml-ready cutout takes the native segmentation bridge, not the wasm worker`() {
        val ml = WebDarkMode.buildImageCutoutScript(mlReady = true)
        assertTrue("flags the ML path", ml.contains("mlReady:true"))
        assertTrue("carries the nonce-tagged darkcut bridge", ml.contains("tangent-darkcut"))
        assertTrue("driver has the ML setup", DarkCutoutAssets.DRIVER_JS.contains("lumCutSetupML"))
        // Default is the offline WASM fallback.
        assertTrue("fallback path", WebDarkMode.buildImageCutoutScript().contains("mlReady:false"))
    }

    @Test
    fun `apply-mask script is a single-line javascript url that masks the tagged image`() {
        val s = WebDarkMode.buildApplyMaskScript("lc7", "data:image/png;base64,AAAA", 800, 600)
        assertTrue(s.startsWith("javascript:"))
        assertFalse(s.contains("\n"))
        assertTrue("targets the tagged element", s.contains("data-tangent-cut-id=\"lc7\""))
        assertTrue("applies the mask", s.contains("mask-image") && s.contains("mask-mode"))
        assertTrue("carries the mask data URI", s.contains("data:image/png;base64,AAAA"))
        assertTrue("aligns under object-fit", s.contains("objectFit"))
    }

    @Test
    fun `dynamic mode injects the cutout while the filter tiers do not`() {
        // The cutout is a dynamic-only concern; keep it out of the filter path's assertions by shape:
        // its driver function name must not appear in the recolor/apply scripts.
        assertFalse(WebDarkMode.buildDynamicScript().contains("lumCutSetup"))
        assertFalse(WebDarkMode.buildApplyScript().contains("lumCutSetup"))
        assertTrue(WebDarkMode.buildImageCutoutScript().contains("lumCutSetup"))
    }

    @Test
    fun `cutout worker runs the kernel off the main thread and is fail-safe`() {
        val worker = String(Base64.getDecoder().decode(DarkCutoutAssets.WORKER_B64), Charsets.UTF_8)
        assertTrue("instantiates wasm in the worker", worker.contains("WebAssembly.instantiate"))
        assertTrue("keys via the kernel", worker.contains("ex.cutout("))
        assertTrue("pre-smooths RGB", worker.contains("ex.smooth_rgb("))
        assertTrue("blurs alpha for smooth edges", worker.contains("ex.blur_alpha("))
        assertTrue("encodes off-thread", worker.contains("convertToBlob"))
        // Driver leaves images alone on any failure (CSP/worker/wasm/CORS) — element recolor stands.
        assertTrue("driver has a dead/skip path", DarkCutoutAssets.DRIVER_JS.contains("markSkipAll"))
    }

    // --- document_start pre-paint extension (white-flash fix) ---

    private fun readAsset(rel: String): String {
        // JVM unit tests run with the module dir (app/) as cwd.
        val candidates = listOf(java.io.File(rel), java.io.File("app/$rel"))
        val f = candidates.firstOrNull { it.exists() }
            ?: error("asset not found from ${java.io.File(".").absolutePath}: $rel")
        return f.readText()
    }

    @Test
    fun `pre-paint content script runs at document_start on all pages`() {
        val manifest = readAsset("src/main/assets/extensions/darkmode/manifest.json")
        assertTrue("must inject before first paint", manifest.contains("\"run_at\": \"document_start\""))
        assertTrue("covers all pages", manifest.contains("<all_urls>"))
        assertTrue("loads the content script", manifest.contains("darkmode.js"))
    }

    @Test
    fun `pre-paint script uses the SAME filter class-id-contrast as the native filter tier`() {
        // Reusing the class/id lets the native FCP injection seamlessly take over (no double-invert);
        // matching contrast keeps the pre-paint frame identical to the settled one. Cross-check the
        // literals against what buildApplyScript emits so the two can't silently drift.
        val js = readAsset("src/main/assets/extensions/darkmode/darkmode.js")
        val apply = WebDarkMode.buildApplyScript()
        // Literals present verbatim in both.
        for (token in listOf("tangent-dark-fallback", "tangent-dark-style", "invert(1) hue-rotate(180deg)")) {
            assertTrue("darkmode.js missing '$token'", js.contains(token))
            assertTrue("buildApplyScript missing '$token' (drift!)", apply.contains(token))
        }
        // darkmode.js builds the CSS by concatenation, so the contrast constants appear as JS values;
        // the native script emits them inlined. Assert both so they can't drift.
        assertTrue("darkmode.js root contrast", js.contains("ROOT_CONTRAST = '0.9'"))
        assertTrue("darkmode.js media contrast", js.contains("MEDIA_CONTRAST = '1.1111'"))
        assertTrue("native root contrast", apply.contains("contrast(0.9)"))
        assertTrue("native media contrast", apply.contains("contrast(1.1111)"))
    }

    @Test
    fun `pre-paint script carries the SAME already-dark detector as the native tiers`() {
        // darkmode.js can't import from the native injection (it's a document_start content script),
        // so the detector is a hand-kept copy. Drift means the pre-paint frame and the settled frame
        // disagree about whether the site is dark — a visible flash on exactly the sites the detector
        // was improved for.
        val js = readAsset("src/main/assets/extensions/darkmode/darkmode.js")
        val apply = WebDarkMode.buildApplyScript()
        for (token in listOf("paintedLum", "schemeIsDark", "measureDark", "fgLum", "elementFromPoint")) {
            assertTrue("darkmode.js missing '$token'", js.contains(token))
            assertTrue("buildApplyScript missing '$token' (drift!)", apply.contains(token))
        }
        // Same thresholds on both sides, written the same way.
        for (token in listOf("painted < 100", "fg >= 150", "fg >= 200")) {
            assertTrue("darkmode.js missing threshold '$token'", js.contains(token))
        }
        for (token in listOf("painted<100", "fg>=150", "fg>=200")) {
            assertTrue("buildApplyScript missing threshold '$token' (drift!)", apply.contains(token))
        }
    }

    @Test
    fun `pre-paint script skips the hit-test probe before first paint but runs it after`() {
        val js = readAsset("src/main/assets/extensions/darkmode/darkmode.js")
        // At document_start there is no layout to hit-test, and forcing one is the cost this script
        // exists to avoid. The later macrotask passes do probe — that's when the wrapper carrying a
        // site's dark surface exists.
        assertTrue("pre-paint pass is shallow", js.contains("recheck(false)"))
        assertTrue("later passes are deep", js.contains("recheck(true)"))
    }

    @Test
    fun `pre-paint script removes the invert before first paint for genuinely dark pages`() {
        val js = readAsset("src/main/assets/extensions/darkmode/darkmode.js")
        // rAF fires before the frame's paint; getComputedStyle is filter-independent, so a dark page
        // loses the provisional invert before it can flash light.
        assertTrue("re-decides on a pre-paint frame", js.contains("requestAnimationFrame"))
        assertTrue("checks the real background", js.contains("getComputedStyle") && js.contains("pageIsDark"))
        assertTrue("never inverts a media document", js.contains("isMediaDoc"))
        assertTrue("drops the class when dark", js.contains("classList.remove"))
    }

    @Test
    fun `dynamic tier strips the pre-paint filter so the page is not both inverted and recolored`() {
        val s = WebDarkMode.buildDynamicScript()
        assertTrue("removes the pre-paint filter class", s.contains("classList.remove('tangent-dark-fallback')"))
        assertTrue("removes the pre-paint filter style", s.contains("getElementById('tangent-dark-style')"))
    }

    @Test
    fun `pre-paint strip is a lightweight single-line url that drops only the filter`() {
        val s = WebDarkMode.buildRemovePrePaintScript()
        assertTrue(s.startsWith("javascript:"))
        assertFalse(s.contains("\n"))
        assertTrue(s.contains("tangent-dark-fallback"))
        assertTrue(s.contains("tangent-dark-style"))
        // Must NOT do the heavy per-element inline-style walk (that's buildRemoveScript's job).
        assertFalse("no destructive element walk", s.contains("getElementsByTagName('*')"))
        assertFalse("no inline-style stripping", s.contains("removeProperty"))
    }

    @Test
    fun `remove drops both the class and the injected style`() {
        val s = WebDarkMode.buildRemoveScript()
        assertTrue(s.startsWith("javascript:"))
        assertFalse(s.contains("\n"))
        assertTrue(s.contains("tangent-dark-fallback"))
        assertTrue(s.contains("tangent-dark-style"))
        assertTrue("disconnects the aggressive observer", s.contains("__tangentDarkObserver"))
        // Also tears down the dynamic tier: class, style, observer, and the inline recolors it set.
        assertTrue(s.contains("tangent-dyn"))
        assertTrue(s.contains("__tangentDynObserver"))
        assertTrue("strips per-element inline recolors", s.contains("removeProperty"))
        assertTrue("terminates the cutout worker", s.contains("__lumCut") && s.contains("terminate"))
        assertTrue("strips the cutout CSS mask", s.contains("mask-image"))
    }

    /*
     * The properties below are what make the dynamic tier reach the elements it used to miss, and
     * what makes turning dark mode off restore the page without a reload. Each is verified
     * end-to-end in tools/dark-mode-harness (every fixture there fails against the previous
     * implementation); these pins are the cheap guard that a future edit doesn't quietly drop one.
     */

    @Test
    fun `dynamic walk is resumable and frame-budgeted, not a truncating cap`() {
        val s = WebDarkMode.buildDynamicScript()
        // The old walk took Math.min(all.length, CAP) from index 0 on EVERY pass, so a long page's
        // tail was never darkened however many passes ran.
        assertFalse("no truncating re-scan from index 0", s.contains("Math.min(all.length"))
        assertTrue("per-frame budget", s.contains("BUDGET"))
        assertTrue("resumes across frames", s.contains("requestAnimationFrame(pump)"))
        assertTrue("static resumable lists", s.contains("querySelectorAll('*')"))
    }

    @Test
    fun `dynamic separates the read phase from the write phase`() {
        val s = WebDarkMode.buildDynamicScript()
        // Interleaved read/write forces a style recalc per element; visit() may only collect ops.
        assertTrue("visit collects ops", s.contains("function visit(el,ops)"))
        assertTrue("ops are applied in a batch", s.contains("function flush(ops)"))
        assertTrue("writes happen in flush", s.contains("el.style.setProperty(ops[i+1]"))
    }

    @Test
    fun `dynamic memoizes color math by color string`() {
        val s = WebDarkMode.buildDynamicScript()
        assertTrue("cache keyed by kind and color", s.contains("var k=kind+'|'+c,v=st.cache[k]"))
        assertTrue("raw conversion is the cache miss path", s.contains("function modifyRaw"))
    }

    @Test
    fun `dynamic reaches shadow roots and same-origin iframes`() {
        val s = WebDarkMode.buildDynamicScript()
        assertTrue("adopts open shadow roots", s.contains("adoptRoot") && s.contains("shadowRoot"))
        assertTrue("adopts same-origin iframes", s.contains("adoptFrame") && s.contains("contentDocument"))
    }

    @Test
    fun `dynamic re-reads an element whose class or style changed`() {
        val s = WebDarkMode.buildDynamicScript()
        assertTrue("observes attributes", s.contains("attributeFilter:['class','style']"))
        assertTrue("clears the done latch on mutation", s.contains("t[DONE]=0"))
        // Our own writes must not be mistaken for the page's, or the observer re-queues forever.
        assertTrue("counts self-writes", s.contains("t[PEND]>0"))
    }

    @Test
    fun `dynamic darkens pseudo-elements and light background images`() {
        val s = WebDarkMode.buildDynamicScript()
        assertTrue("pseudo rules are keyed by a tagging attribute", s.contains("data-tangent-dyn-p"))
        assertTrue("probes both pseudos", s.contains("'::before'") && s.contains("'::after'"))
        assertTrue("scrims url() backgrounds", s.contains("linear-gradient(rgba(0,0,0,'+SCRIM+')"))
        assertTrue("size floor keeps icons unscrimmed", s.contains("el.offsetWidth>=BGMIN"))
    }

    @Test
    fun `remove restores the page's own inline colors instead of stripping them`() {
        val s = WebDarkMode.buildRemoveScript()
        // The blanket sweep wiped colors the SITE had set inline, so the page stayed visibly wrong
        // until a reload — which is what made dark mode look like it needed a refresh to turn off.
        assertTrue("reads the recorded originals", s.contains("__lumDynOrig"))
        assertTrue("puts the original value back", s.contains("el.style.setProperty(p,v[0],v[1])"))
        assertTrue("works from the touched list", s.contains("dyn.touched"))
        assertTrue("disconnects every observer it registered", s.contains("dyn.obs"))
        assertTrue("clears the pseudo tagging attribute", s.contains("data-tangent-dyn-p"))
        // The old brute-force sweep survives only for pages injected by an older build.
        assertTrue("legacy fallback is gated on missing state", s.contains("}else{"))
    }
}
