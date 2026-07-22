package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.content.DarkCutoutAssets
import com.firebolt9907.luminabrowser.ui.content.WebDarkMode
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
        assertTrue("keep-tagged graphics excluded from counter-invert", s.contains(":not([data-lumina-dark-keep])"))
    }

    @Test
    fun `default apply runs the structural image classifier but not pixel analysis`() {
        val s = WebDarkMode.buildApplyScript()
        // Tier 2 classifier runs on the default path (tags logos/icons so they don't vanish).
        assertTrue("classifier runs", s.contains("classifyImages"))
        assertTrue("tags graphics to keep", s.contains("data-lumina-dark-keep"))
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
        assertTrue("tags url() backgrounds", s.contains("data-lumina-dark-bg"))
        assertTrue("only tags real image backgrounds, not gradients", s.contains("indexOf('url(')"))
        assertTrue("incremental observer for new nodes", s.contains("__luminaDarkObserver"))
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

    @Test
    fun `remove drops both the class and the injected style`() {
        val s = WebDarkMode.buildRemoveScript()
        assertTrue(s.startsWith("javascript:"))
        assertFalse(s.contains("\n"))
        assertTrue(s.contains("lumina-dark-fallback"))
        assertTrue(s.contains("lumina-dark-style"))
        assertTrue("disconnects the aggressive observer", s.contains("__luminaDarkObserver"))
        // Also tears down the dynamic tier: class, style, observer, and the inline recolors it set.
        assertTrue(s.contains("lumina-dyn"))
        assertTrue(s.contains("__luminaDynObserver"))
        assertTrue("strips per-element inline recolors", s.contains("removeProperty"))
        assertTrue("terminates the cutout worker", s.contains("__lumCut") && s.contains("terminate"))
    }
}
