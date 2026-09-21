"use strict";
/*
 * Tangent Reader article extractor — runs in the browser's isolated extension world.
 *
 * Page JavaScript cannot see or override this code. It reads the live DOM and layout, applies a
 * compact per-site rule set when available, then returns a plain article object containing headings,
 * paragraphs with links and inline math, lists, blockquotes, code, tables, images, figures, and
 * equation graphics. The caller decides how to render that object.
 *
 * The extractor deliberately avoids page-context calls such as window.prompt, so it continues to
 * work on pages whose CSP sandbox omits `allow-modals`. Full DOM and layout APIs are available here
 * (including getComputedStyle and getBoundingClientRect).
 */

/* A single sentinel codepoint (Unicode Private Use Area) marks where an inline math glyph sits in a
   paragraph's text; the reader replaces each one, in order, with the rendered equation. */
var TANGENT_MATH_SENTINEL = '';

/**
 * Pure extractor: given the compact per-site rules array, return the article object
 * ({ title, byline, url, blocks }) or null. No side effects survive the call (uid attributes are
 * always removed). Exposed for the JVM/puppeteer harness via module.exports below.
 */
function tangentExtractArticle(rules) {
  rules = rules || [];

  /* ---- per-site rule matching (host -> rule) ---- */
  function tangentHost() {
    try { return location.hostname.replace(/^www\./, '').toLowerCase(); } catch (e) { return ''; }
  }
  function matchedRule() {
    var host = tangentHost();
    if (!host) return null;
    for (var r of rules) {
      var pats = r && r.match;
      if (!pats) continue;
      if (!Array.isArray(pats)) pats = [pats];
      for (var pat of pats) {
        pat = String(pat).toLowerCase().replace(/^\*\./, '').replace(/^www\./, '');
        if (!pat) continue;
        if (host === pat || host.endsWith('.' + pat)) return r;
      }
    }
    return null;
  }
  var __rule = matchedRule();
  var __stripRoots = [];
  if (__rule && __rule.strip) {
    var ssel = Array.isArray(__rule.strip) ? __rule.strip.join(',') : __rule.strip;
    try { __stripRoots = Array.prototype.slice.call(document.querySelectorAll(ssel)); } catch (e) {}
  }
  function isStripped(el) {
    if (!__stripRoots.length || !el || el.nodeType !== 1) return false;
    for (var sr of __stripRoots) { if (sr === el || sr.contains(el)) return true; }
    return false;
  }

  /* ---- visibility / boilerplate ---- */
  function isVisible(el) {
    if (!el || el.nodeType !== 1) return true;
    if (el.getAttribute('aria-hidden') === 'true') return false;
    var s = window.getComputedStyle(el);
    if (!s) return true;
    if (s.display === 'none' || s.visibility === 'hidden' || s.visibility === 'collapse') return false;
    if (parseFloat(s.opacity || '1') === 0) return false;
    /* A `display:contents` element generates no box of its own (getBoundingClientRect is 0x0) yet
       its children render normally — common in React / styled-components wrappers. Treat it as
       visible so the walk descends into it; the real leaves inside still get box-checked. */
    if (s.display === 'contents') return true;
    var r = el.getBoundingClientRect();
    if (r.width <= 1 || r.height <= 1) return false;
    return true;
  }
  var BOILERPLATE_SEL = 'nav, aside, footer, form, [role="navigation"], [role="complementary"], [role="banner"], [role="contentinfo"], [class*="share" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="social" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="related" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="newsletter" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="subscribe" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="comment" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="breadcrumb" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="advert" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="sponsor" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="promo" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="credit" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="disclaimer" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="affiliate" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="hawk" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="category" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="toolbar" i]:not([class*="has-" i]):not([class*="with-" i]), [class~="ad" i], [class~="ads" i], [class^="ad-" i], [class*=" ad-" i], [class^="ads-" i], [class*=" ads-" i], [class^="ad_" i], [class*=" ad_" i], [class$="-ad" i], [class*="-ad " i], [class$="-ads" i], [class*="-ads " i], [class*="-ad-" i], [class*="_ad-" i], [class$="_ad" i], [class*="_ad " i], [class*="taboola" i], [id*="taboola" i], [class*="outbrain" i], [id*="outbrain" i], [class*="recirc" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="subscription" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="adthrive" i], [class*="adsbygoogle" i], [class*="mv-ad-box" i], [class*="dfp-ad" i], [id*="google_ads" i]';
  function isBoilerplate(el) {
    if (!el || el.nodeType !== 1) return false;
    var n = el;
    while (n && n !== articleEl && n !== document.body && n.nodeType === 1) {
      if (n.nodeName === 'ARTICLE' || n.nodeName === 'MAIN') { n = n.parentElement; continue; }
      if (n.matches(BOILERPLATE_SEL)) return true;
      n = n.parentElement;
    }
    return false;
  }
  function linkDensity(el) {
    var total = el.textContent.trim().length;
    if (total === 0) return 1;
    var linkLen = 0;
    var anchors = el.querySelectorAll('a');
    for (var a of anchors) { linkLen += a.textContent.length; }
    return linkLen / total;
  }
  function keep(el) {
    return isVisible(el) && !isBoilerplate(el) && !isStripped(el);
  }

  /* ---- text helpers ---- */
  function textOf(el) {
    if (!el) return '';
    var src = el;
    if (el.querySelector && el.querySelector('script, style, noscript, template')) {
      src = el.cloneNode(true);
      for (var n of src.querySelectorAll('script, style, noscript, template')) n.remove();
    }
    return (src.textContent || '').replace(/\s+/g, ' ').trim();
  }
  function richTextOf(el) {
    if (!el) return [];
    var src = el;
    if (el.querySelector && el.querySelector('script, style, noscript, template')) {
      src = el.cloneNode(true);
      for (var n of src.querySelectorAll('script, style, noscript, template')) n.remove();
    }
    var spans = [];
    function walkNode(node) {
      if (!node) return;
      if (node.nodeType === 3) {
        var t = node.textContent;
        if (t) spans.push({ text: t, href: null });
      } else if (node.nodeType === 1) {
        if (node.nodeName === 'A') {
          var rawHref = node.getAttribute('href');
          var resolvedHref = null;
          if (rawHref) {
            var lowHref = rawHref.trim().toLowerCase();
            if (!lowHref.startsWith('javascript:') && !lowHref.startsWith('mailto:') && !lowHref.startsWith('tel:')) {
              try { resolvedHref = new URL(rawHref, location.href).href; } catch (e) { resolvedHref = rawHref; }
            }
          }
          if (resolvedHref) {
            spans.push({ text: (node.textContent || '').replace(/\s+/g, ' ').trim(), href: resolvedHref });
          } else {
            var txt = node.textContent || '';
            if (txt) spans.push({ text: txt, href: null });
          }
        } else {
          for (var c of node.childNodes) walkNode(c);
        }
      }
    }
    for (var c of src.childNodes) walkNode(c);
    var merged = [];
    for (var s of spans) {
      if (s.href === null) {
        if (merged.length > 0 && merged[merged.length - 1].href === null) merged[merged.length - 1].text += s.text;
        else merged.push({ text: s.text, href: null });
      } else {
        s.text = s.text.replace(/\s+/g, ' ').trim();
        if (s.text) merged.push(s);
      }
    }
    for (var i = 0; i < merged.length; i++) {
      if (merged[i].href === null) merged[i].text = merged[i].text.replace(/\s+/g, ' ');
    }
    return merged;
  }
  function codeText(el) {
    if (!el) return '';
    var src = el;
    if (el.querySelector && el.querySelector('script, style')) {
      src = el.cloneNode(true);
      for (var n of src.querySelectorAll('script, style')) n.remove();
    }
    var t = (src.textContent || '').replace(/\r\n?/g, '\n');
    return t.replace(/^\n+/, '').replace(/\s+$/, '');
  }

  /* ---- image / media helpers ---- */
  function bestFromSrcset(ss) {
    if (!ss) return '';
    var best = '', bestW = -1;
    for (var part of ss.split(',')) {
      var t = part.trim().split(/\s+/);
      var u = t[0];
      if (!u) continue;
      var w = 0;
      if (t[1]) {
        if (t[1].endsWith('w')) w = parseInt(t[1]) || 0;
        else if (t[1].endsWith('x')) w = (parseFloat(t[1]) || 0) * 1000;
      }
      if (w >= bestW) { bestW = w; best = u; }
    }
    return best;
  }
  function imgSrc(img) {
    if (!img) return null;
    var s = img.currentSrc || img.getAttribute('src') || img.getAttribute('data-src') ||
      img.getAttribute('data-src-medium') || img.getAttribute('data-original') ||
      img.getAttribute('data-lazy-src') || img.getAttribute('data-hi-res-src') ||
      img.getAttribute('data-lazy') || img.getAttribute('data-echo') ||
      img.getAttribute('data-image') || img.getAttribute('data-url') ||
      img.getAttribute('data-native-src') || img.getAttribute('data-full-src') || '';
    if (s.indexOf('data:') === 0) s = '';
    if (!s) s = bestFromSrcset(img.getAttribute('srcset') || img.getAttribute('data-srcset') || img.getAttribute('data-lazy-srcset') || '');
    if (!s) {
      var pic = img.closest('picture');
      if (pic) {
        for (var source of pic.querySelectorAll('source')) {
          s = bestFromSrcset(source.getAttribute('srcset') || source.getAttribute('data-srcset') || '');
          if (s) break;
        }
      }
    }
    if (!s) {
      var par = img.parentElement;
      var nos = par ? par.querySelector('noscript') : null;
      if (nos) {
        var html = nos.textContent || '';
        var m = html.match(/srcset\s*=\s*["']([^"']+)["']/i);
        if (m) s = bestFromSrcset(m[1]);
        if (!s) { m = html.match(/src\s*=\s*["']([^"']+)["']/i); if (m) s = m[1]; }
      }
    }
    if (!s) return null;
    try { s = new URL(s, location.href).href; } catch (e) {}
    if (s.indexOf('data:') === 0) return null;
    return s;
  }
  function imgBlock(img) {
    var src = imgSrc(img);
    if (!src) return null;
    var w = img.naturalWidth || img.width || 0;
    var h = img.naturalHeight || img.height || 0;
    if (w && h && w < 150 && h < 150) return null;
    var block = { type: 'img', src: src, caption: null };
    if (w > 0 && h > 0) { block.w = w; block.h = h; }
    return block;
  }
  function figureBlock(fig) {
    var im = fig.querySelector('img');
    var b = im ? imgBlock(im) : null;
    if (!b) return null;
    var fc = fig.querySelector('figcaption');
    if (fc) { var t = textOf(fc); if (t) b.caption = t; }
    return b;
  }

  /* ---- rich graphics (inline SVG chart/diagram, rendered display math) ---- */
  function svgToDataUri(svg) {
    try {
      if (!svg.getAttribute('xmlns')) svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
      var s = new XMLSerializer().serializeToString(svg);
      if (!s || s.length > 262144) return null;
      return 'data:image/svg+xml;utf8,' + encodeURIComponent(s);
    } catch (e) { return null; }
  }
  function svgDims(svg) {
    var w = parseFloat(svg.getAttribute('width')) || 0;
    var h = parseFloat(svg.getAttribute('height')) || 0;
    if (!w || !h) {
      var vb = (svg.getAttribute('viewBox') || '').split(/[ ,]+/).filter(function (x) { return x.length; });
      if (vb.length === 4) { w = w || parseFloat(vb[2]); h = h || parseFloat(vb[3]); }
    }
    if (!w || !h) { var r = svg.getBoundingClientRect(); w = w || r.width; h = h || r.height; }
    return { w: Math.round(w) || 0, h: Math.round(h) || 0 };
  }
  function richGraphicBlock(node) {
    if (!node || node.nodeType !== 1) return null;
    var mathImg = (node.matches && node.matches('img.mwe-math-fallback-image-display')) ? node
      : (node.querySelector ? node.querySelector('img.mwe-math-fallback-image-display') : null);
    if (mathImg) {
      var src = imgSrc(mathImg);
      if (src) {
        var b = { type: 'figure_rich', src: src, caption: null, equation: true };
        var w = mathImg.naturalWidth || 0, h = mathImg.naturalHeight || 0;
        if (w > 0 && h > 0) { b.w = w; b.h = h; }
        return b;
      }
    }
    var svg = (node.nodeName === 'svg') ? node : (node.querySelector ? node.querySelector('svg') : null);
    if (svg && !svg.querySelector('image')) {
      var d = svgDims(svg);
      if (d.w >= 80 && d.h >= 40) {
        var uri = svgToDataUri(svg);
        if (uri) {
          var rb = { type: 'figure_rich', src: uri, caption: null, equation: false };
          if (d.w > 0 && d.h > 0) { rb.w = d.w; rb.h = d.h; }
          return rb;
        }
      }
    }
    return null;
  }
  function inlineMathSpan(node) {
    if (!node || node.nodeType !== 1) return null;
    var nm = node.nodeName;
    var cls = node.classList;
    if (nm === 'IMG' && cls && cls.contains('mwe-math-fallback-image-inline')) {
      var s = imgSrc(node);
      if (s) { var o = { src: s }; var w = node.naturalWidth || 0, h = node.naturalHeight || 0; if (w && h) { o.w = w; o.h = h; } return o; }
    }
    if (nm === 'SPAN' && cls && cls.contains('mwe-math-element')) {
      var mi = node.querySelector('img.mwe-math-fallback-image-inline');
      if (mi) { var s2 = imgSrc(mi); if (s2) { var o2 = { src: s2 }; var w2 = mi.naturalWidth || 0, h2 = mi.naturalHeight || 0; if (w2 && h2) { o2.w = w2; o2.h = h2; } return o2; } }
    }
    if (nm === 'MJX-CONTAINER' && node.getAttribute('display') !== 'true') {
      var svg2 = node.querySelector('svg');
      if (svg2) { var uri2 = svgToDataUri(svg2); if (uri2) { var d2 = svgDims(svg2); var o3 = { src: uri2 }; if (d2.w && d2.h) { o3.w = d2.w; o3.h = d2.h; } return o3; } }
    }
    return null;
  }
  function collectInlineMath(node, out) {
    var res = '';
    for (var c of node.childNodes) {
      if (c.nodeType === 3) { res += (c.textContent || ''); continue; }
      if (c.nodeType !== 1) continue;
      if (c.nodeName === 'SCRIPT' || c.nodeName === 'STYLE' || c.nodeName === 'NOSCRIPT' || c.nodeName === 'TEMPLATE') continue;
      var m = inlineMathSpan(c);
      if (m) { out.push(m); res += TANGENT_MATH_SENTINEL; continue; }
      res += collectInlineMath(c, out);
    }
    return res;
  }

  /* ---- tables / lists ---- */
  function tableBlock(table) {
    if (table.querySelector('table')) return null;
    if (linkDensity(table) > 0.6) return null;
    var rows = [];
    var hasHeader = false;
    for (var tr of table.querySelectorAll('tr')) {
      if (!isVisible(tr)) continue;
      var cells = [];
      var rowIsHeader = false;
      for (var c of tr.children) {
        var tag = c.nodeName;
        if (tag !== 'TD' && tag !== 'TH') continue;
        if (tag === 'TH') rowIsHeader = true;
        cells.push(textOf(c));
        if (cells.length >= 8) break;
      }
      if (cells.length === 0) continue;
      if (rows.length === 0 && rowIsHeader) hasHeader = true;
      rows.push(cells);
      if (rows.length >= 40) break;
    }
    if (rows.length < 2) return null;
    var maxCols = 0;
    rows.forEach(function (r) { if (r.length > maxCols) maxCols = r.length; });
    if (maxCols < 2 && !hasHeader) return null;
    return { type: 'table', rows: rows, hasHeader: hasHeader };
  }
  function pushList(node, depth) {
    if (linkDensity(node) > 0.5) return;
    var ordered = (node.nodeName === 'OL');
    var index = 0;
    for (var child of node.children) {
      if (child.nodeName !== 'LI' || !isVisible(child)) continue;
      var liText;
      if (child.querySelector('ul, ol')) {
        var clone = child.cloneNode(true);
        for (var n of clone.querySelectorAll('ul, ol')) n.remove();
        liText = textOf(clone);
      } else {
        liText = textOf(child);
      }
      if (liText.length > 0) blocks.push({ type: 'li', text: liText, ordered: ordered, index: index++, depth: depth });
      for (var sub of child.children) {
        if (sub.nodeName === 'UL' || sub.nodeName === 'OL') pushList(sub, depth + 1);
      }
    }
  }

  /* ---- heuristic container scorer (fallback when Readability unavailable) ---- */
  function findArticleElement() {
    if (__rule && __rule.include) {
      var incs = Array.isArray(__rule.include) ? __rule.include : [__rule.include];
      for (var isel of incs) {
        var inc = null;
        try { inc = document.querySelector(isel); } catch (e) {}
        if (inc && isVisible(inc) && inc.textContent.trim().length > 0) return inc;
      }
    }
    var artBody = document.querySelector('[itemprop="articleBody"]');
    if (artBody && isVisible(artBody) && artBody.textContent.trim().length > 400) return artBody;
    var bestElement = null, bestScore = 0;
    for (var art of document.querySelectorAll('article')) {
      if (isStripped(art)) continue;
      var score = art.querySelectorAll('p').length * 100 + art.textContent.length;
      if (score > bestScore) { bestScore = score; bestElement = art; }
    }
    if (bestElement && bestScore > 200) return bestElement;
    var scores = new Map();
    function addScore(el, amount) {
      if (!el || el.nodeType !== 1) return;
      if (el.nodeName === 'BODY' || el.nodeName === 'HTML') return;
      scores.set(el, (scores.get(el) || 0) + amount);
    }
    for (var p of document.querySelectorAll('p')) {
      if (!isVisible(p) || isStripped(p)) continue;
      var text = p.textContent.trim();
      if (text.length < 25) continue;
      var base = 1 + Math.min(Math.floor(text.length / 100), 3);
      base += (text.match(/,/g) || []).length;
      var parent = p.parentElement;
      addScore(parent, base);
      addScore(parent ? parent.parentElement : null, base / 2);
    }
    var topEl = null, topScore = 0;
    for (var entry of scores) {
      var scaled = entry[1] * (1 - linkDensity(entry[0]));
      if (scaled > topScore) { topScore = scaled; topEl = entry[0]; }
    }
    return topEl || bestElement || document.body;
  }

  /* ---- MediaWiki collapsibles (Wikipedia/Fandom) ---- */
  /* On a mobile UA Wikipedia serves MobileFrontend, which collapses EVERY section below the lead:
     the section body is a `.mf-collapsible-content` div carrying the `hidden` attribute + CSS that
     forces height:0. Its child <p>s overflow visibly, but the walk drops the 0-height *container*
     before it can recurse into them — so only the lead extracted. jquery.makeCollapsible (desktop
     data tables / infobox lists) hides its content the same way via display:none / aria-hidden.
     Force-expand every collapsible before extraction (removing `hidden` alone restores height).
     Gated on the .mw-parser-output marker so it never touches non-wiki pages; stripped containers
     (navbox/infobox/reflist) stay stripped after expansion, so this only recovers genuine in-body
     collapsed content. Idempotent-quiet: once expanded it mutates nothing, so the content-script's
     MutationObserver doesn't re-fire on it. */
  function tangentExpandCollapsibles() {
    try {
      if (!document.querySelector('.mw-parser-output')) return;
      var nodes = document.querySelectorAll('.mf-collapsible-content, .collapsible-block, .mw-collapsible, .mw-collapsible-content, .NavFrame, .NavContent, .collapsible-list, .mw-collapsible tbody, .mw-collapsible tr, .mw-collapsible td, .mw-collapsible th');
      for (var el of nodes) {
        if (el.classList) el.classList.remove('mw-collapsed');
        if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') el.removeAttribute('aria-hidden');
        if (el.hasAttribute && el.hasAttribute('hidden')) el.removeAttribute('hidden');
        var st = el.style;
        if (st) {
          if (st.display === 'none') st.display = '';
          if (st.visibility === 'hidden') st.visibility = '';
          if (st.height === '0px' || st.height === '0') st.height = '';
          if (st.maxHeight && st.maxHeight !== 'none') st.maxHeight = '';
          if (st.overflow === 'hidden') st.overflow = '';
        }
      }
    } catch (e) {}
  }
  tangentExpandCollapsibles();

  /* ---- resolve the article container (include rule, else heuristic scorer) ---- */
  var articleEl = findArticleElement();
  if (!articleEl) return null;

  var paragraphs = articleEl.querySelectorAll('p');
  if (paragraphs.length < 2 && articleEl.textContent.length < 1000) return null;

  /* ---- title ---- */
  var title = '';
  var h1 = articleEl.querySelector('h1') || document.querySelector('h1');
  if (h1) {
    title = textOf(h1);
  } else {
    title = document.title;
    var cleaned = title.replace(/\s+[|–—»·-]\s+[^|–—»·]+$/, '').trim();
    if (cleaned.length >= 8) title = cleaned;
  }
  function isTitleDup(t) { return title && t === title; }

  /* ---- byline (Readability's is clean; fall back to the DOM heuristic) ---- */
  function bylineText(el) {
    if (!el) return '';
    var parts = [];
    var seen = new Set();
    function push(t) {
      t = (t || '').replace(/\s+/g, ' ').trim();
      if (t.length > 0 && !seen.has(t)) { seen.add(t); parts.push(t); }
    }
    var BLOCK = 'div, p, li, address, time, bsp-timestamp';
    for (var b of el.querySelectorAll(BLOCK)) {
      if (b.querySelector(BLOCK)) continue;
      if (!isVisible(b)) continue;
      push(textOf(b));
    }
    if (parts.length === 0) push(textOf(el));
    return parts.join('\n');
  }
  var byline = null;
  var bylineEl = document.querySelector('[class*="author" i], [class*="byline" i], [itemprop="author"], [rel="author"]');
  if (bylineEl) {
    byline = bylineText(bylineEl);
    if (byline && byline.replace(/\n/g, ' ').length > 160) byline = null;
  }

  /* ---- block walk (over the LIVE container) ---- */
  var blocks = [];
  function walk(node) {
    if (!node) return;
    var name = node.nodeName;
    if (node.nodeType === 1 && (name === 'P' || /^H[1-6]$/.test(name) || name === 'BLOCKQUOTE' || name === 'PRE' || name === 'TABLE' || name === 'UL' || name === 'OL' || name === 'FIGURE' || name === 'IMG')) {
      var isMathDisplayImg = name === 'IMG' && node.classList && node.classList.contains('mwe-math-fallback-image-display');
      if (!isMathDisplayImg && !keep(node)) return;
    }
    if (name === 'TABLE') {
      if (node.querySelector('table')) { for (var ch of node.childNodes) walk(ch); return; }
      var t = tableBlock(node);
      if (t) blocks.push(t);
      return;
    }
    if (name === 'HR') {
      if (!isBoilerplate(node) && blocks.length > 0 && blocks[blocks.length - 1].type !== 'hr') blocks.push({ type: 'hr' });
      return;
    }
    if (name === 'PRE') {
      var code = codeText(node);
      if (code.length > 0) blocks.push({ type: 'pre', text: code });
      return;
    }
    if (name === 'P') {
      if (linkDensity(node) > 0.7) return;
      var imath = [];
      var text = collectInlineMath(node, imath).replace(/\s+/g, ' ').trim();
      if (imath.length === 0) text = textOf(node);
      if (text.length > 0 && !isTitleDup(text)) {
        var blk = { type: 'p', text: text, spans: richTextOf(node) };
        if (imath.length > 0) blk.imath = imath;
        blocks.push(blk);
      }
    } else if (/^H[1-6]$/.test(name)) {
      var htext = textOf(node);
      if (htext.length > 0 && !isTitleDup(htext)) blocks.push({ type: 'h', level: parseInt(name.substring(1)), text: htext });
    } else if (name === 'BLOCKQUOTE') {
      var bqtext = textOf(node);
      if (bqtext.length > 0) blocks.push({ type: 'bq', text: bqtext });
    } else if (name === 'FIGURE') {
      var rg = richGraphicBlock(node);
      if (rg) {
        var fc = node.querySelector('figcaption');
        if (fc) { var ct = textOf(fc); if (ct) rg.caption = ct; }
        blocks.push(rg);
      } else {
        var fb = figureBlock(node);
        if (fb) blocks.push(fb);
      }
    } else if (name === 'svg') {
      var sb = richGraphicBlock(node);
      if (sb) blocks.push(sb);
    } else if (name === 'MJX-CONTAINER') {
      var mb = richGraphicBlock(node);
      if (mb) { mb.equation = true; blocks.push(mb); }
    } else if (name === 'MATH') {
      var mmb = richGraphicBlock(node);
      if (mmb) { mmb.equation = true; blocks.push(mmb); }
    } else if (name === 'IMG') {
      if (!node.closest('figure')) {
        var rgi = richGraphicBlock(node);
        if (rgi) blocks.push(rgi);
        else { var ib = imgBlock(node); if (ib) blocks.push(ib); }
      }
    } else if (name === 'UL' || name === 'OL') {
      pushList(node, 0);
    } else {
      if (node.nodeType === 1 && !keep(node)) return;
      for (var c3 of node.childNodes) walk(c3);
    }
  }

  if (articleEl === document.body) {
    var contentEls = articleEl.querySelectorAll('p, h1, h2, h3, h4, h5, h6, blockquote, pre, table, hr, ul, ol, figure, img');
    contentEls.forEach(function (el) {
      if (el.parentElement && (el.parentElement.nodeName === 'UL' || el.parentElement.nodeName === 'OL' || el.parentElement.nodeName === 'BLOCKQUOTE' || el.parentElement.nodeName === 'PRE')) return;
      if (el.closest && el.closest('td, th')) return;
      if (el.nodeName === 'TABLE') {
        if (el.parentElement && el.parentElement.closest && el.parentElement.closest('table')) return;
        var t = tableBlock(el);
        if (t) blocks.push(t);
        return;
      }
      if (el.nodeName === 'PRE') {
        var code = codeText(el);
        if (code.length > 0) blocks.push({ type: 'pre', text: code });
        return;
      }
      if (el.nodeName === 'HR') {
        if (blocks.length > 0 && blocks[blocks.length - 1].type !== 'hr') blocks.push({ type: 'hr' });
        return;
      }
      if (!keep(el)) return;
      if (el.nodeName === 'FIGURE') {
        var fb = figureBlock(el);
        if (fb) blocks.push(fb);
      } else if (el.nodeName === 'IMG') {
        if (!el.closest('figure')) { var ib = imgBlock(el); if (ib) blocks.push(ib); }
      } else if (el.nodeName === 'P') {
        if (linkDensity(el) > 0.7) return;
        var ptext = textOf(el);
        if (ptext.length > 0 && !isTitleDup(ptext)) blocks.push({ type: 'p', text: ptext, spans: richTextOf(el) });
      } else if (/^H[1-6]$/.test(el.nodeName)) {
        var ht = textOf(el);
        if (ht.length > 0 && !isTitleDup(ht)) blocks.push({ type: 'h', level: parseInt(el.nodeName.substring(1)), text: ht });
      } else if (el.nodeName === 'BLOCKQUOTE') {
        var bt = textOf(el);
        if (bt.length > 0) blocks.push({ type: 'bq', text: bt });
      } else if (el.nodeName === 'UL' || el.nodeName === 'OL') {
        if (el.parentElement && el.parentElement.closest('ul, ol')) return;
        pushList(el, 0);
      }
    });
  } else {
    for (var child of articleEl.childNodes) walk(child);
  }

  /* Lead image fallback (og:image / twitter:image) when the body carried none. */
  var hasImage = blocks.some(function (b) { return b.type === 'img'; });
  if (!hasImage) {
    var metaImg = document.querySelector('meta[property="og:image"], meta[property="og:image:url"], meta[name="twitter:image"], meta[name="twitter:image:src"]');
    var mu = metaImg ? (metaImg.getAttribute('content') || '') : '';
    if (mu) {
      try { mu = new URL(mu, location.href).href; } catch (e) {}
      if (mu.indexOf('data:') !== 0 && mu.indexOf('http') === 0) blocks.unshift({ type: 'img', src: mu, caption: null });
    }
  }

  if (blocks.length > 0) return { title: title, byline: byline, url: location.href, blocks: blocks };
  return null;
}

/**
 * Pure SOCIAL extractor: for Reddit/X/Mastodon/Bluesky, return a thread object
 * ({ title, byline, url, mode:"social", blocks:[social_post, social_comment…] }) or null. Shares the
 * content-script Port transport with the article extractor (CSP-proof — X serves a strict CSP under
 * which a window.prompt bridge is silently dropped, so the Port is the only reliable delivery there).
 * Kept in step with SocialExtractor.buildSocialExtractorScript (the javascript: prompt fallback).
 */
function tangentExtractSocial() {
  function stxt(el) { return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  function sabs(u) { try { return new URL(u, location.href).href; } catch (e) { return u; } }
  function fmtCount(v) {
    var n = parseInt(v, 10);
    if (isNaN(n)) return String(v || '');
    if (n >= 1000000) return (Math.round(n / 100000) / 10) + 'm';
    if (n >= 1000) return (Math.round(n / 100) / 10) + 'k';
    return String(n);
  }
  function bestSrcset(ss) {
    if (!ss) return '';
    var best = '', bestW = -1, parts = ss.split(',');
    for (var i = 0; i < parts.length; i++) {
      var t = parts[i].trim().split(/\s+/), u = t[0];
      if (!u) continue;
      var wv = 0;
      if (t[1]) { if (t[1].charAt(t[1].length - 1) === 'w') wv = parseInt(t[1]) || 0; else wv = (parseFloat(t[1]) || 0) * 1000; }
      if (wv >= bestW) { bestW = wv; best = u; }
    }
    return best;
  }
  var GIF_RE = /\.gif(\?|$)/i;
  /* Dedupe key = the URL's last path segment sans query. Reddit serves the SAME image twice (a blurred
     preview.redd.it backdrop + the i.redd.it original, plus srcset variants) and X serves size variants
     of one media id (name=small/large) — all share a filename, so this collapses the duplicates. */
  function mediaKey(u) {
    try { var p = new URL(u, location.href); return (p.pathname.split('/').filter(Boolean).pop() || p.pathname).toLowerCase(); }
    catch (e) { return u; }
  }
  /* Collect inline media from <img> (and <video poster> for gif-as-video posts, shown as a static
     frame). Skips avatars/emoji/spacers and aria-hidden backdrops; dedupes by [mediaKey]; flags real
     animated GIFs so the reader can badge + animate them. Bounded. */
  function collectMedia(list) {
    var out = [], seen = {};
    for (var i = 0; i < list.length && out.length < 12; i++) {
      var im = list[i];
      if (im.getAttribute && im.getAttribute('aria-hidden') === 'true') continue;
      var s = '';
      if (im.nodeName === 'VIDEO') { s = im.getAttribute('poster') || ''; }
      else {
        s = im.currentSrc || im.getAttribute('src') || '';
        if (!s) s = bestSrcset(im.getAttribute('srcset') || '');
      }
      if (!s || s.indexOf('data:') === 0) continue;
      s = sabs(s);
      var wv = im.naturalWidth || im.width || 0, hv = im.naturalHeight || im.height || 0;
      if (wv && hv && wv < 64 && hv < 64) continue;
      var k = mediaKey(s);
      if (seen[k]) continue;
      seen[k] = 1;
      var o = { src: s };
      if (wv > 0 && hv > 0) { o.w = wv; o.h = hv; }
      if (GIF_RE.test(s)) o.gif = true;
      out.push(o);
    }
    return out;
  }
  function sspans(el) {
    if (!el) return [];
    var out = [], as = el.querySelectorAll('a[href]');
    for (var i = 0; i < as.length; i++) {
      var a = as[i], h = a.getAttribute('href');
      if (!h) continue;
      var lh = h.trim().toLowerCase();
      if (lh.indexOf('javascript:') === 0 || lh.indexOf('mailto:') === 0 || lh.indexOf('tel:') === 0) continue;
      var hv; try { hv = new URL(h, location.href).href; } catch (e) { hv = h; }
      var tt = (a.textContent || '').replace(/\s+/g, ' ').trim();
      if (tt) out.push({ text: tt, href: hv });
    }
    return out;
  }
  function shost() { try { return location.hostname.replace(/^www\./, '').toLowerCase(); } catch (e) { return ''; } }

  /* Reddit post media, scoped to the actual media container so we don't pull in the blurred backdrop,
     award icons, or a lightbox duplicate. Falls back to the whole post when no container matches. */
  function redditMediaEls(scope) {
    var container = scope.querySelector('[slot="post-media-container"]');
    if (container) return container.querySelectorAll('img, video[poster]');
    var pi = scope.querySelector('#post-image');
    if (pi) return [pi];
    return scope.querySelectorAll('img, video[poster]');
  }
  function extractReddit() {
    if (document.querySelector('shreddit-post')) return extractRedditShreddit();
    if (document.querySelector('.thing.link, #siteTable .thing')) return extractRedditOld();
    return null;
  }
  function extractRedditShreddit() {
    var post = document.querySelector('shreddit-post');
    if (!post) return null;
    var title = post.getAttribute('post-title') || stxt(post.querySelector('h1'));
    var author = post.getAttribute('author') || '';
    var sub = post.getAttribute('subreddit-prefixed-name') || '';
    var score = post.getAttribute('score');
    var ts = post.getAttribute('created-timestamp') || '';
    var comments = post.getAttribute('comment-count');
    var meta = [];
    if (score) meta.push(fmtCount(score) + ' points');
    if (comments) meta.push(fmtCount(comments) + ' comments');
    if (sub) meta.push(sub);
    var bodyEl = post.querySelector('[slot="text-body"]');
    var blocks = [{ type: 'social_post', author: author || sub || 'Reddit', handle: author ? ('u/' + author) : sub, avatar: null, timestamp: ts, meta: meta.join(' · '), title: title, text: bodyEl ? stxt(bodyEl) : '', spans: bodyEl ? sspans(bodyEl) : [], media: collectMedia(redditMediaEls(post)) }];
    var opAuthor = author;
    var cEls = document.querySelectorAll('shreddit-comment');
    var count = 0;
    for (var i = 0; i < cEls.length && count < 80; i++) {
      var c = cEls[i];
      var a = c.getAttribute('author');
      if (!a || a === '[deleted]') continue;
      var body = c.querySelector('[slot="comment"]') || c.querySelector('.md');
      var t = body ? stxt(body) : '';
      if (!t) continue;
      var depth = parseInt(c.getAttribute('depth') || '0') || 0;
      var cs = c.getAttribute('score');
      blocks.push({ type: 'social_comment', author: a, handle: 'u/' + a, avatar: null, timestamp: '', meta: cs ? (fmtCount(cs) + ' points') : null, text: t, spans: body ? sspans(body) : [], media: body ? collectMedia(body.querySelectorAll('img, video[poster]')) : [], depth: Math.min(depth, 8), op: a === opAuthor });
      count++;
    }
    return { title: title, byline: sub || null, blocks: blocks };
  }
  function extractRedditOld() {
    var thing = document.querySelector('#siteTable .thing.link') || document.querySelector('.thing.link');
    if (!thing) return null;
    var title = stxt(thing.querySelector('a.title'));
    var author = stxt(thing.querySelector('.author'));
    var sub = stxt(thing.querySelector('.subreddit'));
    var scoreEl = thing.querySelector('.score.unvoted');
    var score = scoreEl ? (scoreEl.getAttribute('title') || stxt(scoreEl)) : null;
    var bodyEl = thing.querySelector('.usertext-body .md');
    var meta = [];
    if (score) meta.push(fmtCount(score) + ' points');
    if (sub) meta.push(sub.charAt(0) === 'r' ? sub : ('r/' + sub));
    var blocks = [{ type: 'social_post', author: author || 'Reddit', handle: author ? ('u/' + author) : null, avatar: null, timestamp: '', meta: meta.join(' · '), title: title, text: bodyEl ? stxt(bodyEl) : '', spans: bodyEl ? sspans(bodyEl) : [], media: collectMedia(thing.querySelectorAll('.usertext-body img, .usertext-body video[poster]')) }];
    var cEls = document.querySelectorAll('.commentarea .comment');
    var count = 0;
    for (var i = 0; i < cEls.length && count < 80; i++) {
      var c = cEls[i];
      var a = stxt(c.querySelector('.entry .author'));
      if (!a) continue;
      var body = c.querySelector('.entry .usertext-body .md');
      var t = body ? stxt(body) : '';
      if (!t) continue;
      var depth = 0, p = c.parentElement;
      while (p) { if (p.classList && p.classList.contains('comment')) depth++; p = p.parentElement; }
      var cScoreEl = c.querySelector('.entry .score.unvoted');
      var cs = cScoreEl ? (cScoreEl.getAttribute('title') || stxt(cScoreEl)) : null;
      blocks.push({ type: 'social_comment', author: a, handle: 'u/' + a, avatar: null, timestamp: '', meta: cs ? (fmtCount(cs) + ' points') : null, text: t, spans: body ? sspans(body) : [], media: body ? collectMedia(body.querySelectorAll('img')) : [], depth: Math.min(depth, 8), op: a === author });
      count++;
    }
    return { title: title, byline: sub || null, blocks: blocks };
  }

  function twName(un) {
    var whole = stxt(un);
    var m = whole.match(/@[A-Za-z0-9_]+/);
    var handle = m ? m[0] : '';
    var name = handle ? whole.split(handle)[0].replace(/·.*/, '').trim() : whole;
    return { name: name, handle: handle };
  }
  function extractTwitter() {
    var arts = document.querySelectorAll('article[data-testid="tweet"]');
    if (!arts.length) arts = document.querySelectorAll('article[role="article"]');
    if (!arts.length) return null;
    var blocks = [], opHandle = null;
    for (var i = 0; i < arts.length && blocks.length < 80; i++) {
      var art = arts[i];
      var textEl = art.querySelector('[data-testid="tweetText"]');
      var t = textEl ? stxt(textEl) : '';
      var media = collectMedia(art.querySelectorAll('[data-testid="tweetPhoto"] img, [data-testid="tweetPhoto"] video[poster], [data-testid="videoPlayer"] video[poster]'));
      if (!t && !media.length) continue;
      var un = art.querySelector('[data-testid="User-Name"]');
      var nm = un ? twName(un) : { name: '', handle: '' };
      var timeEl = art.querySelector('time');
      var ts = timeEl ? (timeEl.getAttribute('datetime') || stxt(timeEl)) : '';
      var g = art.querySelector('[role="group"][aria-label]');
      var stats = g ? g.getAttribute('aria-label') : null;
      var spans = sspans(textEl);
      if (blocks.length === 0) {
        opHandle = nm.handle;
        blocks.push({ type: 'social_post', author: nm.name || nm.handle || 'Post', handle: nm.handle, avatar: null, timestamp: ts, meta: stats, title: null, text: t, spans: spans, media: media });
      } else {
        blocks.push({ type: 'social_comment', author: nm.name || nm.handle || 'Reply', handle: nm.handle, avatar: null, timestamp: ts, meta: stats, text: t, spans: spans, media: media, depth: 0, op: nm.handle && nm.handle === opHandle });
      }
    }
    if (!blocks.length) return null;
    return { title: '', byline: blocks[0].handle || 'X', blocks: blocks };
  }

  function mastoAuthor(scope) {
    var name = stxt(scope.querySelector('.display-name__html')) || stxt(scope.querySelector('.display-name bdi'));
    var acct = stxt(scope.querySelector('.display-name__account'));
    if (!name) name = stxt(scope.querySelector('.display-name'));
    return { name: name, acct: acct };
  }
  function extractMastodon() {
    var det = document.querySelector('.detailed-status');
    if (!det) return null;
    var a = mastoAuthor(det);
    var contentEl = det.querySelector('.status__content');
    var timeEl = det.querySelector('time');
    var ts = timeEl ? (timeEl.getAttribute('datetime') || stxt(timeEl)) : '';
    var blocks = [{ type: 'social_post', author: a.name || a.acct || 'Post', handle: a.acct || null, avatar: null, timestamp: ts, meta: null, title: null, text: contentEl ? stxt(contentEl) : '', spans: sspans(contentEl), media: collectMedia(det.querySelectorAll('.media-gallery img, .media-gallery__item img, video[poster]')) }];
    var opAcct = a.acct;
    var sts = document.querySelectorAll('.status:not(.detailed-status)');
    var count = 0;
    for (var i = 0; i < sts.length && count < 80; i++) {
      var s = sts[i];
      var ca = mastoAuthor(s);
      var cc = s.querySelector('.status__content');
      var t = cc ? stxt(cc) : '';
      if (!t) continue;
      var cTime = s.querySelector('time');
      blocks.push({ type: 'social_comment', author: ca.name || ca.acct || 'Reply', handle: ca.acct || null, avatar: null, timestamp: cTime ? (cTime.getAttribute('datetime') || '') : '', meta: null, text: t, spans: sspans(cc), media: collectMedia(s.querySelectorAll('.media-gallery img, .media-gallery__item img')), depth: 0, op: ca.acct && ca.acct === opAcct });
      count++;
    }
    return { title: '', byline: a.acct || 'Mastodon', blocks: blocks };
  }

  function bskyText(item) {
    var el = item.querySelector('[data-testid="postText"]');
    if (el) return { text: stxt(el), el: el };
    var cands = item.querySelectorAll('div[dir="auto"]');
    var best = null, bestLen = 0;
    for (var i = 0; i < cands.length; i++) { var l = (cands[i].textContent || '').length; if (l > bestLen) { bestLen = l; best = cands[i]; } }
    return { text: best ? stxt(best) : '', el: best };
  }
  function extractBluesky() {
    var items = document.querySelectorAll('[data-testid^="postThreadItem-by-"]');
    if (!items.length) return null;
    var blocks = [], opHandle = null;
    for (var i = 0; i < items.length && blocks.length < 80; i++) {
      var item = items[i];
      var tid = item.getAttribute('data-testid') || '';
      var handle = tid.replace('postThreadItem-by-', '');
      var bt = bskyText(item);
      var media = collectMedia(item.querySelectorAll('img[src*="feed_thumbnail"], img[src*="feed_fullsize"], img[src*="cdn.bsky"]'));
      if (!bt.text && !media.length) continue;
      var nameEl = item.querySelector('a[href^="/profile/"]');
      var name = nameEl ? stxt(nameEl) : handle;
      var spans = sspans(bt.el);
      if (blocks.length === 0) {
        opHandle = handle;
        blocks.push({ type: 'social_post', author: name || handle, handle: handle ? ('@' + handle) : null, avatar: null, timestamp: '', meta: null, title: null, text: bt.text, spans: spans, media: media });
      } else {
        blocks.push({ type: 'social_comment', author: name || handle, handle: handle ? ('@' + handle) : null, avatar: null, timestamp: '', meta: null, text: bt.text, spans: spans, media: media, depth: 0, op: handle && handle === opHandle });
      }
    }
    if (!blocks.length) return null;
    return { title: '', byline: blocks[0].handle || 'Bluesky', blocks: blocks };
  }

  var h = shost();
  var r = null;
  try {
    if (h === 'reddit.com' || h.indexOf('.reddit.com') >= 0) r = extractReddit();
    else if (h === 'twitter.com' || h.indexOf('.twitter.com') >= 0 || h === 'x.com' || h.indexOf('.x.com') >= 0) r = extractTwitter();
    else if (h === 'bsky.app' || h.indexOf('.bsky.app') >= 0) r = extractBluesky();
    else r = extractMastodon();
  } catch (e) { r = null; }
  if (!r || !r.blocks || !r.blocks.length) return null;
  r.url = location.href;
  r.mode = 'social';
  return r;
}

/* Harness / unit-test hook (node require). `browser` is absent there, so the content-script glue
   below is skipped and only the pure function is exported. */
if (typeof module === 'object' && module.exports) {
  module.exports = { tangentExtractArticle: tangentExtractArticle, tangentExtractSocial: tangentExtractSocial };
}

/* ---- content-script glue (GeckoView isolated world only) ---- */
if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.connectNative) {
  (function () {
    var port;
    try { port = browser.runtime.connectNative("TangentReader"); } catch (e) { return; }

    /* Compact per-site ruleset, pushed by the app over the port. Empty until config arrives — the
       extractor still works (Readability + heuristic), just without include/strip overrides. */
    var RULES = [];
    /* Keep only the richest extraction per URL, so a transient smaller re-render (late-hydrating
       SPAs) never clobbers a fuller article already sent. */
    var bestLen = -1;
    var bestUrl = '';

    function measure(article) {
      var n = 0;
      for (var b of article.blocks) n += (b && b.text) ? b.text.length : 24;
      return n;
    }

    /* Reddit/X/Mastodon/Bluesky run the social extractor (post + threaded replies) rather than the
       prose heuristic (which produces junk on their SPA shells — e.g. X's <title> is the whole tweet).
       Host-only so feeds also route here and simply post 'none'. */
    function isSocialHost() {
      var h;
      try { h = (location.hostname || '').replace(/^www\./, '').toLowerCase(); } catch (e) { return false; }
      return h === 'reddit.com' || h.indexOf('.reddit.com') >= 0 ||
        h === 'twitter.com' || h.indexOf('.twitter.com') >= 0 || h === 'x.com' || h.indexOf('.x.com') >= 0 ||
        h === 'bsky.app' || h.indexOf('.bsky.app') >= 0 ||
        h.indexOf('mastodon') >= 0 || h.indexOf('mstdn') >= 0;
    }

    function run() {
      var data = null;
      if (isSocialHost()) {
        try { data = tangentExtractSocial(); } catch (e) { data = null; }
      } else {
        try { data = tangentExtractArticle(RULES); } catch (e) { data = null; }
      }
      try {
        if (data) {
          var m = measure(data);
          if (location.href === bestUrl && m <= bestLen) return;
          bestUrl = location.href; bestLen = m;
          port.postMessage({ type: 'article', article: data });
        } else {
          port.postMessage({ type: 'none' });
        }
      } catch (e) {}
    }

    port.onMessage.addListener(function (msg) {
      if (!msg) return;
      if (msg.action === 'config' && Array.isArray(msg.rules)) { RULES = msg.rules; run(); return; }
      if (msg.action === 'extract') run();
    });

    /* Auto-extract once (document_end), then re-extract as late content lands, then stop (~12s).
       A MutationObserver only marks the DOM dirty; a bounded interval does the throttled work. */
    run();
    var dirty = false;
    try {
      new MutationObserver(function () { dirty = true; }).observe(document.body || document.documentElement, { childList: true, subtree: true, characterData: true });
    } catch (e) {}
    var ticks = 0;
    var timer = setInterval(function () {
      ticks++;
      if (dirty) { dirty = false; try { run(); } catch (e) {} }
      if (ticks >= 15) clearInterval(timer);
    }, 800);
  })();
}
