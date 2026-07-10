"use strict";
/*
 * Lumina reader extractor — runs as an isolated-world content script.
 *
 * This is the Safari/Firefox-Reader model: the page's own JavaScript cannot see or override
 * anything here (separate realm), and the extracted article is handed to the app over a native
 * messaging Port — never via window.prompt or any page-context call. Hostile pages (e.g.
 * TechCrunch's anti-adblock, which weaponizes window.prompt) therefore have nothing to intercept
 * or trip on. Full DOM + layout is available (getComputedStyle / getBoundingClientRect are real).
 *
 * Protocol (native app <-> content script), port name "LuminaReader":
 *   content -> app : { type: "article", article: {...} }  when an article is found
 *                    { type: "none" }                      when the page isn't readable
 *   app -> content : { action: "extract" }                 re-run extraction on demand
 *
 * NOTE: this isolated-world path is currently dormant (the live extractor is the javascript:-URL
 * one built in ReaderExtractor.buildReaderExtractorScript). The per-site rules engine (include /
 * strip overrides) lives there and in ReaderRules.kt. If this content-script path is ever
 * activated, mirror that engine here — fetch the compact ruleset (browser.runtime.getURL +
 * fetch of reader_rules.json) and apply the same include (content-root override) and strip
 * (subtree exclusion) logic, so both paths stay behaviourally identical.
 */
(function () {
  var port;
  try {
    port = browser.runtime.connectNative("LuminaReader");
  } catch (e) {
    return;
  }

  function extractArticle() {
    function findArticleElement() {
      var artBody = document.querySelector('[itemprop="articleBody"]');
      if (artBody && isVisible(artBody) && artBody.textContent.trim().length > 400) {
        return artBody;
      }
      var bestElement = null;
      var bestScore = 0;
      var articles = document.querySelectorAll('article');
      for (var art of articles) {
        var pCount = art.querySelectorAll('p').length;
        var textLen = art.textContent.length;
        var score = pCount * 100 + textLen;
        if (score > bestScore) {
          bestScore = score;
          bestElement = art;
        }
      }
      if (bestElement && bestScore > 200) {
        return bestElement;
      }
      var scores = new Map();
      function addScore(el, amount) {
        if (!el || el.nodeType !== 1) return;
        var tag = el.nodeName;
        if (tag === 'BODY' || tag === 'HTML') return;
        scores.set(el, (scores.get(el) || 0) + amount);
      }
      for (var p of document.querySelectorAll('p')) {
        if (!isVisible(p)) continue;
        var text = p.textContent.trim();
        if (text.length < 25) continue;
        var base = 1 + Math.min(Math.floor(text.length / 100), 3);
        base += (text.match(/,/g) || []).length;
        var parent = p.parentElement;
        addScore(parent, base);
        addScore(parent ? parent.parentElement : null, base / 2);
      }
      var topEl = null;
      var topScore = 0;
      for (var entry of scores) {
        var el = entry[0];
        var scaled = entry[1] * (1 - linkDensity(el));
        if (scaled > topScore) {
          topScore = scaled;
          topEl = el;
        }
      }
      if (topEl) {
        return topEl;
      }
      return bestElement || document.body;
    }
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
      if (!s) s = bestFromSrcset(img.getAttribute('srcset') || img.getAttribute('data-srcset') ||
        img.getAttribute('data-lazy-srcset') || '');
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
      var cap = (img.getAttribute('alt') || img.getAttribute('title') || '').trim();
      var block = { type: 'img', src: src, caption: cap || null };
      if (w > 0 && h > 0) { block.w = w; block.h = h; }
      return block;
    }
    function tableBlock(table) {
      if (table.querySelector('table')) return null;
      if (linkDensity(table) > 0.6) return null;
      var rows = [];
      var hasHeader = false;
      var trs = table.querySelectorAll('tr');
      for (var tr of trs) {
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
        if (liText.length > 0) {
          blocks.push({ type: 'li', text: liText, ordered: ordered, index: index++, depth: depth });
        }
        for (var sub of child.children) {
          if (sub.nodeName === 'UL' || sub.nodeName === 'OL') pushList(sub, depth + 1);
        }
      }
    }
    function figureBlock(fig) {
      var im = fig.querySelector('img');
      var b = im ? imgBlock(im) : null;
      if (!b) return null;
      var fc = fig.querySelector('figcaption');
      if (fc) { var t = textOf(fc); if (t) b.caption = t; }
      return b;
    }
    function isVisible(el) {
      if (!el || el.nodeType !== 1) return true;
      if (el.getAttribute('aria-hidden') === 'true') return false;
      var s = window.getComputedStyle(el);
      if (!s) return true;
      if (s.display === 'none' || s.visibility === 'hidden' || s.visibility === 'collapse') return false;
      if (parseFloat(s.opacity || '1') === 0) return false;
      var r = el.getBoundingClientRect();
      if (r.width <= 1 || r.height <= 1) return false;
      return true;
    }
    var BOILERPLATE_SEL = 'nav, aside, footer, form, [role="navigation"], [role="complementary"], [role="banner"], [role="contentinfo"], [class*="share" i], [class*="social" i], [class*="related" i], [class*="newsletter" i], [class*="subscribe" i], [class*="comment" i], [class*="breadcrumb" i], [class*="advert" i], [class*="sponsor" i], [class*="promo" i], [class*="credit" i], [class*="disclaimer" i], [class*="affiliate" i], [class*="hawk" i], [class*="category" i], [class*="toolbar" i]';
    function isBoilerplate(el) {
      if (!el || el.nodeType !== 1) return false;
      var n = el;
      while (n && n !== articleEl && n !== document.body && n.nodeType === 1) {
        if (n.matches(BOILERPLATE_SEL)) return true;
        n = n.parentElement;
      }
      return false;
    }
    function isPullQuote(el) {
      if (!el || el.nodeType !== 1) return false;
      var PULLQUOTE_RE = /pull[-_]?quote|large[-_]?quote|featured[-_]?quote|accent[-_]?quote|highlight[-_]?quote|big[-_]?quote|inline[-_]?callout|article[-_]?callout/i;
      var cls = el.className || '';
      var dataAttr = el.getAttribute('data-pullquote') || el.getAttribute('data-quote') || '';
      if (PULLQUOTE_RE.test(cls) || dataAttr) return true;
      if (el.closest && el.closest('[data-pullquote]')) return true;
      var text = textOf(el);
      if (text.length < 10 || text.length > 300) return false;
      try {
        var bodyStyle = window.getComputedStyle(document.body);
        var bodySize = parseFloat(bodyStyle.fontSize) || 16;
        var bqStyle = window.getComputedStyle(el);
        var bqSize = parseFloat(bqStyle.fontSize) || 0;
        if (bqSize >= bodySize * 1.4) return true;
        var innerP = el.querySelector('p');
        if (innerP) {
          var pSize = parseFloat(window.getComputedStyle(innerP).fontSize) || 0;
          if (pSize >= bodySize * 1.4) return true;
        }
      } catch (e) {}
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
      return isVisible(el) && !isBoilerplate(el);
    }
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
          if (t) {
            spans.push({ text: t, href: null });
          }
        } else if (node.nodeType === 1) {
          if (node.nodeName === 'A') {
            var rawHref = node.getAttribute('href');
            var resolvedHref = null;
            if (rawHref) {
              var lowHref = rawHref.trim().toLowerCase();
              if (!lowHref.startsWith('javascript:') && !lowHref.startsWith('mailto:') && !lowHref.startsWith('tel:')) {
                try {
                  resolvedHref = new URL(rawHref, location.href).href;
                } catch (e) {
                  resolvedHref = rawHref;
                }
              }
            }
            if (resolvedHref) {
              var linkText = (node.textContent || '').replace(/\s+/g, ' ').trim();
              spans.push({ text: linkText, href: resolvedHref });
            } else {
              var txt = node.textContent || '';
              if (txt) {
                spans.push({ text: txt, href: null });
              }
            }
          } else {
            for (var c of node.childNodes) {
              walkNode(c);
            }
          }
        }
      }
      for (var c of src.childNodes) {
        walkNode(c);
      }
      var mergedSpans = [];
      for (var s of spans) {
        if (s.href === null) {
          if (mergedSpans.length > 0 && mergedSpans[mergedSpans.length - 1].href === null) {
            mergedSpans[mergedSpans.length - 1].text += s.text;
          } else {
            mergedSpans.push({ text: s.text, href: null });
          }
        } else {
          s.text = s.text.replace(/\s+/g, ' ').trim();
          if (s.text) {
            mergedSpans.push(s);
          }
        }
      }
      for (var i = 0; i < mergedSpans.length; i++) {
        if (mergedSpans[i].href === null) {
          mergedSpans[i].text = mergedSpans[i].text.replace(/\s+/g, ' ');
        }
      }
      return mergedSpans;
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
    var articleEl = findArticleElement();
    if (!articleEl) return null;
    var paragraphs = articleEl.querySelectorAll('p');
    if (paragraphs.length < 2 && articleEl.textContent.length < 1000) {
      return null;
    }
    var title = '';
    var h1 = articleEl.querySelector('h1') || document.querySelector('h1');
    if (h1) {
      title = textOf(h1);
    } else {
      title = document.title;
      var cleaned = title.replace(/\s+[|–—»·-]\s+[^|–—»·]+$/, '').trim();
      if (cleaned.length >= 8) title = cleaned;
    }
    function isTitleDup(t) {
      return title && t === title;
    }
    function bylineText(el) {
      if (!el) return '';
      var parts = [];
      var seen = new Set();
      function push(t) {
        t = (t || '').replace(/\s+/g, ' ').trim();
        if (t.length > 0 && !seen.has(t)) { seen.add(t); parts.push(t); }
      }
      var BLOCK = 'div, p, li, address, time, bsp-timestamp';
      var bs = el.querySelectorAll(BLOCK);
      for (var b of bs) {
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
      if (byline && byline.replace(/\n/g, ' ').length > 160) {
        byline = null;
      }
    }
    var blocks = [];
    function walk(node) {
      if (!node) return;
      var name = node.nodeName;
      if (node.nodeType === 1 && (name === 'P' || /^H[1-6]$/.test(name) || name === 'BLOCKQUOTE' || name === 'PRE' || name === 'TABLE' || name === 'UL' || name === 'OL' || name === 'FIGURE' || name === 'IMG')) {
        if (!keep(node)) return;
      }
      if (name === 'TABLE') {
        if (node.querySelector('table')) { for (var child of node.childNodes) walk(child); return; }
        var t = tableBlock(node);
        if (t) blocks.push(t);
        return;
      }
      if (name === 'HR') {
        if (!isBoilerplate(node) && blocks.length > 0 && blocks[blocks.length - 1].type !== 'hr') {
          blocks.push({ type: 'hr' });
        }
        return;
      }
      if (name === 'PRE') {
        var code = codeText(node);
        if (code.length > 0) {
          blocks.push({ type: 'pre', text: code });
        }
        return;
      }
      if (name === 'P') {
        if (linkDensity(node) > 0.7) return;
        var text = textOf(node);
        if (text.length > 0 && !isTitleDup(text)) {
          var spans = richTextOf(node);
          blocks.push({ type: 'p', text: text, spans: spans });
        }
      } else if (/^H[1-6]$/.test(name)) {
        var level = parseInt(name.substring(1));
        var htext = textOf(node);
        if (htext.length > 0 && !isTitleDup(htext)) {
          blocks.push({ type: 'h', level: level, text: htext });
        }
      } else if (name === 'BLOCKQUOTE') {
        var bqtext = textOf(node);
        if (bqtext.length > 0) {
          blocks.push({ type: 'bq', text: bqtext, pullQuote: isPullQuote(node) });
        }
      } else if (name === 'FIGURE') {
        var fb = figureBlock(node);
        if (fb) blocks.push(fb);
      } else if (name === 'IMG') {
        if (!node.closest('figure')) {
          var ib = imgBlock(node);
          if (ib) blocks.push(ib);
        }
      } else if (name === 'UL' || name === 'OL') {
        pushList(node, 0);
      } else {
        if (node.nodeType === 1 && !keep(node)) return;
        for (var c2 of node.childNodes) {
          walk(c2);
        }
      }
    }
    if (articleEl === document.body) {
      var contentEls = articleEl.querySelectorAll('p, h1, h2, h3, h4, h5, h6, blockquote, pre, table, hr, ul, ol, figure, img');
      contentEls.forEach(function (el) {
        if (el.parentElement && (el.parentElement.nodeName === 'UL' || el.parentElement.nodeName === 'OL' || el.parentElement.nodeName === 'BLOCKQUOTE' || el.parentElement.nodeName === 'PRE')) {
          return;
        }
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
          if (!el.closest('figure')) {
            var ib = imgBlock(el);
            if (ib) blocks.push(ib);
          }
        } else if (el.nodeName === 'P') {
          if (linkDensity(el) > 0.7) return;
          var ptext = textOf(el);
          if (ptext.length > 0 && !isTitleDup(ptext)) {
            var pspans = richTextOf(el);
            blocks.push({ type: 'p', text: ptext, spans: pspans });
          }
        } else if (/^H[1-6]$/.test(el.nodeName)) {
          var htext = textOf(el);
          if (htext.length > 0 && !isTitleDup(htext)) blocks.push({ type: 'h', level: parseInt(el.nodeName.substring(1)), text: htext });
        } else if (el.nodeName === 'BLOCKQUOTE') {
          var bqtext = textOf(el);
          if (bqtext.length > 0) blocks.push({ type: 'bq', text: bqtext, pullQuote: isPullQuote(el) });
        } else if (el.nodeName === 'UL' || el.nodeName === 'OL') {
          if (el.parentElement && el.parentElement.closest('ul, ol')) return;
          pushList(el, 0);
        }
      });
    } else {
      for (var child of articleEl.childNodes) {
        walk(child);
      }
    }
    var hasImage = blocks.some(function (b) { return b.type === 'img'; });
    if (!hasImage) {
      var metaImg = document.querySelector('meta[property="og:image"], meta[property="og:image:url"], meta[name="twitter:image"], meta[name="twitter:image:src"]');
      var mu = metaImg ? (metaImg.getAttribute('content') || '') : '';
      if (mu) {
        try { mu = new URL(mu, location.href).href; } catch (e) {}
        if (mu.indexOf('data:') !== 0 && mu.indexOf('http') === 0) {
          blocks.unshift({ type: 'img', src: mu, caption: null });
        }
      }
    }
    if (blocks.length > 0) {
      return { title: title, byline: byline, url: location.href, blocks: blocks };
    }
    return null;
  }

  function run() {
    var data = null;
    try { data = extractArticle(); } catch (e) { data = null; }
    try {
      if (data) port.postMessage({ type: 'article', article: data });
      else port.postMessage({ type: 'none' });
    } catch (e) {}
  }

  port.onMessage.addListener(function (msg) {
    if (msg && msg.action === 'extract') run();
  });

  /* Auto-extract once the document is ready (this script runs at document_end). */
  run();
})();
