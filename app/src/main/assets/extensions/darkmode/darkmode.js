/*
 * Tangent web-dark-mode pre-paint content script (run_at: document_start).
 *
 * WHY: the native dark-mode injection (WebDarkMode.injectWebDarkMode) runs at first-contentful-paint /
 * load-complete — i.e. AFTER a light-only page has already painted its white background — so those
 * pages flashed white before darkening. A javascript: URL has no earlier hook; only a document_start
 * content script can put a dark filter in place before the first paint.
 *
 * WHAT: at document_start this adds the SAME filter class/style the native filter tier uses
 * (`tangent-dark-fallback` + `#tangent-dark-style`), so the very first paint is already inverted-dark on
 * a light page — no white flash. It then re-decides BEFORE the first paint (a requestAnimationFrame
 * callback runs ahead of that frame's paint, by which point render-blocking CSS — and therefore a
 * site's own dark theme honored via prefers-color-scheme — has applied): if the page is genuinely dark
 * or is a directly-viewed media document, the filter is removed so those paint native-dark from frame
 * one (no light flash either). `getComputedStyle` reports the page's REAL colors regardless of the CSS
 * filter, so this check is accurate even with the filter on.
 *
 * HAND-OFF: the native FCP injection reuses the exact same class/id, so it seamlessly takes over with
 * the full image classifier + MutationObserver (filter tier), or strips this and swaps to the dynamic
 * recolor (adaptive tier), or removes it entirely (page turned out dark / per-site off). This script
 * is only ever installed+enabled while web dark mode is globally on. Fully self-contained + fail-safe:
 * any error leaves the page rendering normally.
 *
 * Keep the class/id/contrast constants in sync with WebDarkMode.kt (FALLBACK_CLASS, STYLE_ID,
 * ROOT_CONTRAST, MEDIA_CONTRAST) — WebDarkModeScriptTest asserts they match.
 */
(function () {
  var CLS = 'tangent-dark-fallback';
  var STYLE_ID = 'tangent-dark-style';
  var ROOT_CONTRAST = '0.9';
  var MEDIA_CONTRAST = '1.1111';

  function css() {
    var media = 'html.' + CLS + ' img,html.' + CLS + ' video,html.' + CLS + ' picture,html.' + CLS +
      ' canvas,html.' + CLS + ' iframe,html.' + CLS + ' embed,html.' + CLS + ' object';
    return 'html.' + CLS + '{filter:invert(1) hue-rotate(180deg) contrast(' + ROOT_CONTRAST +
      ')!important;background-color:#fafafa!important;color-scheme:dark;}' +
      media + '{filter:invert(1) hue-rotate(180deg) contrast(' + MEDIA_CONTRAST + ')!important;}';
  }
  function ensureStyle() {
    try {
      var s = document.getElementById(STYLE_ID);
      if (!s) { s = document.createElement('style'); s.id = STYLE_ID; (document.head || document.documentElement).appendChild(s); }
      if (s.textContent !== css()) s.textContent = css();
    } catch (e) {}
  }
  function bgLum(el) {
    if (!el) return -1;
    var c; try { c = getComputedStyle(el).backgroundColor; } catch (e) { return -1; }
    var m = c && c.match(/rgba?\(([^)]+)\)/); if (!m) return -1;
    var p = m[1].split(','); var a = p.length > 3 ? parseFloat(p[3]) : 1; if (a < 0.5) return -1;
    return 0.2126 * parseFloat(p[0]) + 0.7152 * parseFloat(p[1]) + 0.0722 * parseFloat(p[2]);
  }
  function pageIsDark() {
    var l = bgLum(document.body); if (l < 0) l = bgLum(document.documentElement);
    if (l < 0) return false; // no opaque bg declared -> UA canvas is white -> treat as light
    return l < 128;
  }
  function isMediaDoc() {
    try {
      var ct = document.contentType || '';
      if (/^(image|video|audio)\//.test(ct) || ct === 'application/pdf') return true;
      var b = document.body;
      if (b && b.children && b.children.length === 1) {
        var t = b.children[0].tagName;
        if (t === 'IMG' || t === 'VIDEO' || t === 'AUDIO') return true;
      }
    } catch (e) {}
    return false;
  }

  var root = document.documentElement;
  if (!root) return;
  ensureStyle();
  try { root.classList.add(CLS); } catch (e) {}

  // Re-decide before the first paint (rAF) and again on the earliest macrotasks, so a genuinely dark
  // page / media document loses the invert before it can flash light. Native re-confirms at FCP.
  function recheck() { try { if (pageIsDark() || isMediaDoc()) root.classList.remove(CLS); } catch (e) {} }
  try { requestAnimationFrame(recheck); } catch (e) { recheck(); }
  setTimeout(recheck, 0);
  setTimeout(recheck, 120);
})();
