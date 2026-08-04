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
  // --- shared dark detector: mirrors `darkDetectJs` in WebDarkMode.kt (pinned by
  // WebDarkModeScriptTest). Kept as a copy because a document_start content script cannot import
  // from the native injection. See that Kotlin doc for why each signal exists.
  var TG_CLS = CLS;
  function tgLum(c) {
    if (!c) return -1;
    var m = c.match(/rgba?\(([^)]+)\)/); if (!m) return -1;
    var p = m[1].split(',');
    var a = p.length > 3 ? parseFloat(p[3]) : 1;
    if (a < 0.5) return -1;
    var r = parseFloat(p[0]), g = parseFloat(p[1]), b = parseFloat(p[2]);
    if (isNaN(r) || isNaN(g) || isNaN(b)) return -1;
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }
  function bgLum(el) { if (!el) return -1; try { return tgLum(getComputedStyle(el).backgroundColor); } catch (e) { return -1; } }
  function fgLum(el) { if (!el) return -1; try { return tgLum(getComputedStyle(el).color); } catch (e) { return -1; } }
  // The LIGHTEST opaque surface under a handful of viewport probes, or -1 if none resolved.
  function paintedLum() {
    try {
      if (!document.body || !document.elementFromPoint) return -1;
      var w = window.innerWidth || 0, h = window.innerHeight || 0;
      if (w <= 0 || h <= 0) return -1;
      var pts = [[w * 0.5, h * 0.35], [w * 0.5, h * 0.65], [w * 0.12, h * 0.5], [w * 0.88, h * 0.5], [w * 0.5, h * 0.9]];
      var worst = -1;
      for (var i = 0; i < pts.length; i++) {
        var el = document.elementFromPoint(pts[i][0], pts[i][1]), d = 0;
        while (el && d < 12) { var l = bgLum(el); if (l >= 0) { if (l > worst) worst = l; break; } el = el.parentElement; d++; }
      }
      return worst;
    } catch (e) { return -1; }
  }
  function schemeIsDark() {
    try {
      var m = document.querySelector('meta[name="color-scheme"]');
      if (m && /dark/i.test(m.content || '')) return true;
    } catch (e) {}
    try {
      var s = getComputedStyle(document.documentElement);
      return /\bdark\b/.test(s.colorScheme || s.getPropertyValue('color-scheme') || '');
    } catch (e) {}
    return false;
  }
  function measureDark(deep) {
    var declared = bgLum(document.body);
    if (declared < 0) declared = bgLum(document.documentElement);
    if (declared >= 0 && declared < 128) return true;
    var painted = deep ? paintedLum() : -1;
    if (declared < 0) return painted >= 0 ? painted < 128 : schemeIsDark();
    var fg = fgLum(document.body);
    if (painted >= 0 && painted < 100 && fg >= 150) return true;
    return fg >= 200;
  }
  // Measure with OUR class off: it puts an opaque #fafafa background and color-scheme:dark on <html>,
  // so otherwise the detector reads its own output back and can never un-apply.
  function pageIsDark(deep) {
    var r = document.documentElement;
    var had = false;
    try { had = !!(r && r.classList.contains(TG_CLS)); if (had) r.classList.remove(TG_CLS); } catch (e) {}
    try { return measureDark(deep); }
    finally { if (had) { try { r.classList.add(TG_CLS); } catch (e) {} } }
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
  //
  // `deep` (the viewport hit-test probe) is off on the pre-paint pass: at document_start there is no
  // layout to hit-test, and forcing one is exactly the cost this script exists to avoid. The later
  // macrotask passes do run it — by then the wrapper that carries a site's dark surface exists, and
  // that wrapper is the thing body/html alone can never see.
  function recheck(deep) { try { if (pageIsDark(deep) || isMediaDoc()) root.classList.remove(CLS); } catch (e) {} }
  try { requestAnimationFrame(function () { recheck(false); }); } catch (e) { recheck(false); }
  setTimeout(function () { recheck(true); }, 0);
  setTimeout(function () { recheck(true); }, 120);
})();
