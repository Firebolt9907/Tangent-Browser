package com.firebolt9907.luminabrowser.ui.reader

import android.util.Log
import com.firebolt9907.luminabrowser.ui.content.loadJavascriptUrl
import mozilla.components.concept.engine.EngineSession
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult
import org.json.JSONObject
import org.json.JSONArray

object ReaderExtractor {
    private fun getGeckoSession(engineSession: EngineSession): GeckoSession? {
        if (engineSession !is GeckoEngineSession) return null
        try {
            val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
            field.isAccessible = true
            return field.get(engineSession) as? GeckoSession
        } catch (e: Exception) {
            for (field in GeckoEngineSession::class.java.declaredFields) {
                if (GeckoSession::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    return field.get(engineSession) as? GeckoSession
                }
            }
            for (method in GeckoEngineSession::class.java.declaredMethods) {
                if (GeckoSession::class.java.isAssignableFrom(method.returnType) && method.parameterCount == 0) {
                    method.isAccessible = true
                    return method.invoke(engineSession) as? GeckoSession
                }
            }
        }
        return null
    }

    fun registerPromptDelegate(engineSession: EngineSession, tabId: String) {
        val geckoSession = getGeckoSession(engineSession)
        if (geckoSession == null) {
            Log.d("ReaderDbg", "registerPromptDelegate: NO geckoSession tab=$tabId")
            return
        }
        val oldDelegate = geckoSession.promptDelegate

        // Avoid wrapping multiple times
        if (oldDelegate is ReaderPromptDelegate) return

        Log.d("ReaderDbg", "registerPromptDelegate wrapping old=${oldDelegate?.javaClass?.simpleName} tab=$tabId")
        geckoSession.promptDelegate = ReaderPromptDelegate(oldDelegate, tabId)
    }

    fun extractReaderContent(engineSession: EngineSession, tabId: String) {
        engineSession.loadJavascriptUrl(buildReaderExtractorScript(ReaderRules.cached()))
    }

    /**
     * Builds the injected reader-extraction `javascript:` URL as a plain flattened string, so its
     * hard-won invariants can be pinned by JVM unit tests (the script runs in Gecko and otherwise
     * has no coverage). Pure — no [EngineSession] receiver, no side effects.
     *
     * NOTE: this script is flattened to a single line via .replace("\n", " ") before it is
     * loaded as a javascript: URL. Do NOT use `//` line comments anywhere inside it — once
     * newlines are stripped, a `//` comments out the entire rest of the script (including the
     * final prompt() bridge call), silently breaking all reader extraction. Use /* */ instead.
     *
     * [rulesJson] is the compact per-site override array from [ReaderRules] (see that file). It is
     * inlined verbatim as `var __luminaRules = <rulesJson>;` and matched against `location.hostname`
     * inside the script, so a matching rule's `include` selector can force the article container and
     * its `strip` selectors can exclude out-of-article regions. The default "[]" means no overrides
     * — behaviour identical to the pure heuristic — which is what the pinned unit tests exercise.
     */
    internal fun buildReaderExtractorScript(rulesJson: String = "[]"): String {
        return """
            javascript:(function() {
                function luminaExtract() {
                /* Hand the extracted article back to the app WITHOUT touching the page's own
                   window.prompt. Some sites (e.g. TechCrunch's anti-adblock) override window.prompt
                   with a function that pops their own wall / swallows the call, so a direct prompt()
                   never reaches GeckoView's PromptDelegate AND spuriously triggers their UI. Instead
                   we take a pristine native prompt from a throwaway same-origin iframe (a fresh realm
                   the page can't have patched); GeckoView still routes that prompt to the session
                   delegate, and the page's override is bypassed entirely. */
                function luminaBridge(msg, val) {
                    var ifr = null;
                    try {
                        ifr = document.createElement('iframe');
                        ifr.setAttribute('aria-hidden', 'true');
                        ifr.style.cssText = 'display:none!important;width:0;height:0;border:0;position:absolute;left:-9999px;';
                        (document.body || document.documentElement).appendChild(ifr);
                        var w = ifr.contentWindow;
                        if (w && typeof w.prompt === 'function') {
                            return w.prompt(msg, val);
                        }
                    } catch (e) {
                    } finally {
                        if (ifr && ifr.parentNode) ifr.parentNode.removeChild(ifr);
                    }
                    return null;
                }
                function findArticleElement() {
                    /* Per-site override (from ReaderRules): an `include` selector names the real
                       article container for sites the heuristic gets wrong (fails to trigger, grabs
                       only images, or over-captures a too-broad wrapper). First visible match with
                       any text wins and short-circuits all scoring below. */
                    if (__rule && __rule.include) {
                        const incs = Array.isArray(__rule.include) ? __rule.include : [__rule.include];
                        for (let isel of incs) {
                            let inc = null;
                            try { inc = document.querySelector(isel); } catch (e) {}
                            if (inc && isVisible(inc) && inc.textContent.trim().length > 0) return inc;
                        }
                    }
                    /* schema.org's [itemprop="articleBody"] marks the article body exactly, so when
                       a page provides it (NewsArticle / BlogPosting microdata, very common on news
                       and blog CMSes) it's the highest-precision signal — prefer it over the
                       heuristic <article>/paragraph scoring below. */
                    const artBody = document.querySelector('[itemprop="articleBody"]');
                    if (artBody && isVisible(artBody) && artBody.textContent.trim().length > 400) {
                        return artBody;
                    }
                    let bestElement = null;
                    let bestScore = 0;
                    const articles = document.querySelectorAll('article');
                    for (let art of articles) {
                        if (isStripped(art)) continue;
                        const pCount = art.querySelectorAll('p').length;
                        const textLen = art.textContent.length;
                        const score = pCount * 100 + textLen;
                        if (score > bestScore) {
                            bestScore = score;
                            bestElement = art;
                        }
                    }
                    if (bestElement && bestScore > 200) {
                        return bestElement;
                    }
                    /* Readability-style fallback. Many sites (e.g. Android Authority) wrap every
                       paragraph in its own <div>, so no container has multiple *direct* <p>
                       children and a direct-child count would pick a single-paragraph wrapper.
                       Instead, score each substantive <p> and propagate that score to its parent
                       and (halved) grandparent; the ancestor that accumulates the most prose wins.
                       Discount by link density so nav/share lists never outscore the body. */
                    const scores = new Map();
                    function addScore(el, amount) {
                        if (!el || el.nodeType !== 1) return;
                        const tag = el.nodeName;
                        if (tag === 'BODY' || tag === 'HTML') return;
                        scores.set(el, (scores.get(el) || 0) + amount);
                    }
                    for (let p of document.querySelectorAll('p')) {
                        if (!isVisible(p)) continue;
                        if (isStripped(p)) continue;
                        const text = p.textContent.trim();
                        if (text.length < 25) continue;
                        let base = 1 + Math.min(Math.floor(text.length / 100), 3);
                        base += (text.match(/,/g) || []).length;
                        const parent = p.parentElement;
                        addScore(parent, base);
                        addScore(parent ? parent.parentElement : null, base / 2);
                    }
                    let topEl = null;
                    let topScore = 0;
                    for (let entry of scores) {
                        const el = entry[0];
                        const scaled = entry[1] * (1 - linkDensity(el));
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
                /* Pick the highest-resolution candidate from a srcset string. */
                function bestFromSrcset(ss) {
                    if (!ss) return '';
                    let best = '', bestW = -1;
                    for (let part of ss.split(',')) {
                        const t = part.trim().split(/\s+/);
                        const u = t[0];
                        if (!u) continue;
                        let w = 0;
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
                    /* currentSrc reflects what the browser actually chose (handles <picture>/srcset),
                       but lazy-loaded images expose the real URL only via data-* attributes. */
                    let s = img.currentSrc || img.getAttribute('src') || img.getAttribute('data-src') ||
                        img.getAttribute('data-src-medium') || img.getAttribute('data-original') ||
                        img.getAttribute('data-lazy-src') || img.getAttribute('data-hi-res-src') ||
                        img.getAttribute('data-lazy') || img.getAttribute('data-echo') ||
                        img.getAttribute('data-image') || img.getAttribute('data-url') ||
                        img.getAttribute('data-native-src') || img.getAttribute('data-full-src') || '';
                    /* Skip placeholder/blank/data-URI sources that stand in before lazy-load. */
                    if (s.indexOf('data:') === 0) s = '';
                    if (!s) s = bestFromSrcset(img.getAttribute('srcset') || img.getAttribute('data-srcset') ||
                        img.getAttribute('data-lazy-srcset') || '');
                    if (!s) {
                        const pic = img.closest('picture');
                        if (pic) {
                            for (let source of pic.querySelectorAll('source')) {
                                s = bestFromSrcset(source.getAttribute('srcset') || source.getAttribute('data-srcset') || '');
                                if (s) break;
                            }
                        }
                    }
                    if (!s) {
                        /* Many lazy-load libraries ship the real <img> inside a <noscript> fallback next
                           to the placeholder. With scripting on, Gecko does not parse <noscript> into
                           the DOM, so its markup is only reachable as raw text. */
                        const par = img.parentElement;
                        const nos = par ? par.querySelector('noscript') : null;
                        if (nos) {
                            const html = nos.textContent || '';
                            let m = html.match(/srcset\s*=\s*["']([^"']+)["']/i);
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
                    const src = imgSrc(img);
                    if (!src) return null;
                    const w = img.naturalWidth || img.width || 0;
                    const h = img.naturalHeight || img.height || 0;
                    /* Skip icons, spacers and tracking pixels. */
                    if (w && h && w < 150 && h < 150) return null;
                    /* Do NOT use the img's alt/title as a caption: many sites stuff filenames,
                       SEO keywords or boilerplate into alt, which reads as noise below the image.
                       Only a real <figcaption> (set in figureBlock) becomes a visible caption. */
                    const block = { type: 'img', src: src, caption: null };
                    /* Carry intrinsic dimensions so the reader can reserve aspect-ratio space and
                       avoid layout shift as each image streams in. */
                    if (w > 0 && h > 0) { block.w = w; block.h = h; }
                    return block;
                }
                /* Extract a data <table> into rows of cell text. Rejects layout tables (single
                   column with no headers) and link-dense tables (nav grids). Bounds rows/cols so a
                   pathological table can't blow up the payload. Returns null for non-content tables
                   or tables that themselves contain a nested table (the caller recurses instead). */
                function tableBlock(table) {
                    if (table.querySelector('table')) return null;
                    if (linkDensity(table) > 0.6) return null;
                    const rows = [];
                    let hasHeader = false;
                    const trs = table.querySelectorAll('tr');
                    for (let tr of trs) {
                        if (!isVisible(tr)) continue;
                        const cells = [];
                        let rowIsHeader = false;
                        for (let c of tr.children) {
                            const tag = c.nodeName;
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
                    let maxCols = 0;
                    rows.forEach(r => { if (r.length > maxCols) maxCols = r.length; });
                    if (maxCols < 2 && !hasHeader) return null;
                    return { type: 'table', rows: rows, hasHeader: hasHeader };
                }
                /* Emit a <ul>/<ol> as list-item blocks, recursing into nested lists with an
                   increasing depth so sub-items indent instead of being flattened into their
                   parent line. Each item's own text excludes any nested list markup. */
                function pushList(node, depth) {
                    if (linkDensity(node) > 0.5) return;
                    const ordered = (node.nodeName === 'OL');
                    let index = 0;
                    for (let child of node.children) {
                        if (child.nodeName !== 'LI' || !isVisible(child)) continue;
                        let liText;
                        if (child.querySelector('ul, ol')) {
                            const clone = child.cloneNode(true);
                            for (let n of clone.querySelectorAll('ul, ol')) n.remove();
                            liText = textOf(clone);
                        } else {
                            liText = textOf(child);
                        }
                        if (liText.length > 0) {
                            blocks.push({ type: 'li', text: liText, ordered: ordered, index: index++, depth: depth });
                        }
                        for (let sub of child.children) {
                            if (sub.nodeName === 'UL' || sub.nodeName === 'OL') pushList(sub, depth + 1);
                        }
                    }
                }
                function figureBlock(fig) {
                    const im = fig.querySelector('img');
                    const b = im ? imgBlock(im) : null;
                    if (!b) return null;
                    const fc = fig.querySelector('figcaption');
                    if (fc) { const t = textOf(fc); if (t) b.caption = t; }
                    return b;
                }
                /* Elements that are laid out but visually hidden (display/visibility/opacity, aria,
                   or collapsed to a 1px screen-reader box) are page chrome, not article prose. */
                function isVisible(el) {
                    if (!el || el.nodeType !== 1) return true;
                    if (el.getAttribute('aria-hidden') === 'true') return false;
                    const s = window.getComputedStyle(el);
                    if (!s) return true;
                    if (s.display === 'none' || s.visibility === 'hidden' || s.visibility === 'collapse') return false;
                    if (parseFloat(s.opacity || '1') === 0) return false;
                    const r = el.getBoundingClientRect();
                    if (r.width <= 1 || r.height <= 1) return false;
                    return true;
                }
                /* Share bars, breadcrumbs, related-links, newsletter/subscribe, comments, ads,
                   sponsor/affiliate widgets, image credits and category chips are all boilerplate
                   even when they live inside the article container. */
                const BOILERPLATE_SEL = 'nav, aside, footer, form, [role="navigation"], [role="complementary"], [role="banner"], [role="contentinfo"], [class*="share" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="social" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="related" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="newsletter" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="subscribe" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="comment" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="breadcrumb" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="advert" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="sponsor" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="promo" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="credit" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="disclaimer" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="affiliate" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="hawk" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="category" i]:not([class*="has-" i]):not([class*="with-" i]), [class*="toolbar" i]:not([class*="has-" i]):not([class*="with-" i])';
                function isBoilerplate(el) {
                    if (!el || el.nodeType !== 1) return false;
                    /* Only inner wrappers count as boilerplate: walk up to (but not including) the
                       article container so its own taxonomy classes (WordPress category-*, tag-*,
                       hentry, etc.) don't wrongly disqualify the entire article body. */
                    let n = el;
                    while (n && n !== articleEl && n !== document.body && n.nodeType === 1) {
                        if (n.nodeName === 'ARTICLE' || n.nodeName === 'MAIN') {
                            n = n.parentElement;
                            continue;
                        }
                        if (n.matches(BOILERPLATE_SEL)) return true;
                        n = n.parentElement;
                    }
                    return false;
                }
                /* Fraction of an element's text that sits inside links. Share menus, breadcrumbs and
                   nav lists are almost entirely links; article prose is almost entirely not. */
                function linkDensity(el) {
                    const total = el.textContent.trim().length;
                    if (total === 0) return 1;
                    let linkLen = 0;
                    const anchors = el.querySelectorAll('a');
                    for (let a of anchors) { linkLen += a.textContent.length; }
                    return linkLen / total;
                }
                function keep(el) {
                    return isVisible(el) && !isBoilerplate(el) && !isStripped(el);
                }
                /* textContent naively includes the source of inline <script>/<style> children (e.g.
                   CNN embeds an image-fallback script inside the byline). Strip those, and collapse
                   whitespace so extracted prose is clean. */
                function textOf(el) {
                    if (!el) return '';
                    let src = el;
                    if (el.querySelector && el.querySelector('script, style, noscript, template')) {
                        src = el.cloneNode(true);
                        for (let n of src.querySelectorAll('script, style, noscript, template')) n.remove();
                    }
                    return (src.textContent || '').replace(/\s+/g, ' ').trim();
                }
                function richTextOf(el) {
                    if (!el) return [];
                    let src = el;
                    if (el.querySelector && el.querySelector('script, style, noscript, template')) {
                        src = el.cloneNode(true);
                        for (let n of src.querySelectorAll('script, style, noscript, template')) n.remove();
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
                /* Code/pre content must keep its line breaks and indentation, so unlike textOf it
                   does NOT collapse whitespace. Strip inline script/style, normalize CRLF, and trim
                   only leading/trailing blank lines. */
                function codeText(el) {
                    if (!el) return '';
                    let src = el;
                    if (el.querySelector && el.querySelector('script, style')) {
                        src = el.cloneNode(true);
                        for (let n of src.querySelectorAll('script, style')) n.remove();
                    }
                    let t = (src.textContent || '').replace(/\r\n?/g, '\n');
                    return t.replace(/^\n+/, '').replace(/\s+${'$'}/, '');
                }
                /* Per-site override table (declarative data, never code — see ReaderRules.kt). Inlined
                   as the full compact array; the matching rule is picked here by hostname so Kotlin
                   needs no per-URL plumbing and the same table drives any future isolated-world path.
                   Empty array (the default) makes every check below a no-op. */
                var __luminaRules = $rulesJson;
                function luminaHost() {
                    try { return location.hostname.replace(/^www\./, '').toLowerCase(); } catch (e) { return ''; }
                }
                function luminaMatchedRule() {
                    const host = luminaHost();
                    if (!host) return null;
                    for (let r of __luminaRules) {
                        let pats = r && r.match;
                        if (!pats) continue;
                        if (!Array.isArray(pats)) pats = [pats];
                        for (let pat of pats) {
                            pat = String(pat).toLowerCase().replace(/^\*\./, '').replace(/^www\./, '');
                            if (!pat) continue;
                            if (host === pat || host.endsWith('.' + pat)) return r;
                        }
                    }
                    return null;
                }
                var __rule = luminaMatchedRule();
                var __stripRoots = [];
                if (__rule && __rule.strip) {
                    let ssel = Array.isArray(__rule.strip) ? __rule.strip.join(',') : __rule.strip;
                    try { __stripRoots = Array.prototype.slice.call(document.querySelectorAll(ssel)); } catch (e) {}
                }
                function isStripped(el) {
                    if (!__stripRoots.length || !el || el.nodeType !== 1) return false;
                    for (let sr of __stripRoots) { if (sr === el || sr.contains(el)) return true; }
                    return false;
                }
                const articleEl = findArticleElement();
                if (!articleEl) return;
                const paragraphs = articleEl.querySelectorAll('p');
                if (paragraphs.length < 2 && articleEl.textContent.length < 1000) {
                    return;
                }
                let title = '';
                const h1 = articleEl.querySelector('h1') || document.querySelector('h1');
                if (h1) {
                    title = textOf(h1);
                } else {
                    title = document.title;
                    /* document.title usually carries a trailing site-name segment ("Article Title |
                       The Site", "… - The Site", "… – The Site"). Strip only the LAST spaced
                       separator segment, and only when a meaningful title remains, so real titles
                       that contain a dash aren't gutted. h1 titles are already clean, so this only
                       runs on the document.title fallback. */
                    const cleaned = title.replace(/\s+[|–—»·-]\s+[^|–—»·]+${'$'}/, '').trim();
                    if (cleaned.length >= 8) title = cleaned;
                }
                /* The title is shown in its own header, so drop any heading/paragraph in the body
                   that just repeats it (e.g. the article's own <h1>, or a duplicated hero title). */
                function isTitleDup(t) {
                    return title && t === title;
                }
                /* A byline container often stacks author / date / time as separate child elements
                   with no whitespace between them, so a flat textContent yields
                   "July 3, 20262:39 AM ET By The Associated Press" with no separators. Emit each
                   leaf block on its own line so date, time and author stay distinct. */
                function bylineText(el) {
                    if (!el) return '';
                    const parts = [];
                    const seen = new Set();
                    function push(t) {
                        t = (t || '').replace(/\s+/g, ' ').trim();
                        if (t.length > 0 && !seen.has(t)) { seen.add(t); parts.push(t); }
                    }
                    const BLOCK = 'div, p, li, address, time, bsp-timestamp';
                    const blocks = el.querySelectorAll(BLOCK);
                    for (let b of blocks) {
                        /* Only leaf blocks (no nested block descendants) so we never emit both a
                           wrapper and the pieces it contains. */
                        if (b.querySelector(BLOCK)) continue;
                        if (!isVisible(b)) continue;
                        push(textOf(b));
                    }
                    if (parts.length === 0) push(textOf(el));
                    return parts.join('\n');
                }
                let byline = null;
                const bylineEl = document.querySelector('[class*="author" i], [class*="byline" i], [itemprop="author"], [rel="author"]');
                if (bylineEl) {
                    byline = bylineText(bylineEl);
                    /* The author selector sometimes matches an author BIO card (a whole paragraph
                       of prose), not a byline. A real byline is short; if what we extracted is
                       long it's a bio, so drop it rather than render a paragraph in the header.
                       (Length is across the whole multi-line byline, newlines included.) */
                    if (byline && byline.replace(/\n/g, ' ').length > 160) {
                        byline = null;
                    }
                }
                const blocks = [];
                function walk(node) {
                    if (!node) return;
                    const name = node.nodeName;
                    if (node.nodeType === 1 && (name === 'P' || /^H[1-6]${'$'}/.test(name) || name === 'BLOCKQUOTE' || name === 'PRE' || name === 'TABLE' || name === 'UL' || name === 'OL' || name === 'FIGURE' || name === 'IMG')) {
                        if (!keep(node)) return;
                    }
                    if (name === 'TABLE') {
                        if (node.querySelector('table')) { for (let child of node.childNodes) walk(child); return; }
                        const t = tableBlock(node);
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
                        const code = codeText(node);
                        if (code.length > 0) {
                            blocks.push({ type: 'pre', text: code });
                        }
                        return;
                    }
                    if (name === 'P') {
                        if (linkDensity(node) > 0.7) return;
                        const text = textOf(node);
                        if (text.length > 0 && !isTitleDup(text)) {
                            const spans = richTextOf(node);
                            blocks.push({ type: 'p', text: text, spans: spans });
                        }
                    } else if (/^H[1-6]${'$'}/.test(name)) {
                        const level = parseInt(name.substring(1));
                        const text = textOf(node);
                        if (text.length > 0 && !isTitleDup(text)) {
                            blocks.push({ type: 'h', level: level, text: text });
                        }
                    } else if (name === 'BLOCKQUOTE') {
                        const text = textOf(node);
                        if (text.length > 0) {
                            blocks.push({ type: 'bq', text: text });
                        }
                    } else if (name === 'FIGURE') {
                        const b = figureBlock(node);
                        if (b) blocks.push(b);
                    } else if (name === 'IMG') {
                        if (!node.closest('figure')) {
                            const b = imgBlock(node);
                            if (b) blocks.push(b);
                        }
                    } else if (name === 'UL' || name === 'OL') {
                        /* A list dominated by link text is a share bar / breadcrumb / nav, not content. */
                        pushList(node, 0);
                    } else {
                        if (node.nodeType === 1 && !keep(node)) return;
                        for (let child of node.childNodes) {
                            walk(child);
                        }
                    }
                }
                if (articleEl === document.body) {
                    const contentEls = articleEl.querySelectorAll('p, h1, h2, h3, h4, h5, h6, blockquote, pre, table, hr, ul, ol, figure, img');
                    contentEls.forEach(el => {
                        if (el.parentElement && (el.parentElement.nodeName === 'UL' || el.parentElement.nodeName === 'OL' || el.parentElement.nodeName === 'BLOCKQUOTE' || el.parentElement.nodeName === 'PRE')) {
                            return;
                        }
                        /* Skip cells / rows / nested content of a table — the whole table is emitted
                           as one block by tableBlock; also skip tables nested inside another table. */
                        if (el.closest && el.closest('td, th')) return;
                        if (el.nodeName === 'TABLE') {
                            if (el.parentElement && el.parentElement.closest && el.parentElement.closest('table')) return;
                            const t = tableBlock(el);
                            if (t) blocks.push(t);
                            return;
                        }
                        if (el.nodeName === 'PRE') {
                            const code = codeText(el);
                            if (code.length > 0) blocks.push({ type: 'pre', text: code });
                            return;
                        }
                        if (el.nodeName === 'HR') {
                            if (blocks.length > 0 && blocks[blocks.length - 1].type !== 'hr') blocks.push({ type: 'hr' });
                            return;
                        }
                        if (!keep(el)) return;
                        if (el.nodeName === 'FIGURE') {
                            const b = figureBlock(el);
                            if (b) blocks.push(b);
                        } else if (el.nodeName === 'IMG') {
                            if (!el.closest('figure')) {
                                const b = imgBlock(el);
                                if (b) blocks.push(b);
                            }
                        } else if (el.nodeName === 'P') {
                            if (linkDensity(el) > 0.7) return;
                            const text = textOf(el);
                            if (text.length > 0 && !isTitleDup(text)) {
                                const spans = richTextOf(el);
                                blocks.push({ type: 'p', text: text, spans: spans });
                            }
                        } else if (/^H[1-6]${'$'}/.test(el.nodeName)) {
                            const text = textOf(el);
                            if (text.length > 0 && !isTitleDup(text)) blocks.push({ type: 'h', level: parseInt(el.nodeName.substring(1)), text: text });
                        } else if (el.nodeName === 'BLOCKQUOTE') {
                            const text = textOf(el);
                            if (text.length > 0) blocks.push({ type: 'bq', text: text });
                        } else if (el.nodeName === 'UL' || el.nodeName === 'OL') {
                            /* Nested lists are emitted by pushList's recursion from their ancestor
                               list, so skip a list that lives inside another list to avoid dupes. */
                            if (el.parentElement && el.parentElement.closest('ul, ol')) return;
                            pushList(el, 0);
                        }
                    });
                } else {
                    for (let child of articleEl.childNodes) {
                        walk(child);
                    }
                }
                /* Many sites render the hero/lead image outside the article container (a full-bleed
                   banner above it, or a CMS field), so text-first extraction drops it entirely. When
                   we found NO images at all, fall back to the social-share meta image (og:image /
                   twitter:image) as a lead image so the reader still shows the article's key visual.
                   Only when zero images were extracted, to avoid duplicating an existing inline hero. */
                const hasImage = blocks.some(function(b) { return b.type === 'img'; });
                if (!hasImage) {
                    const metaImg = document.querySelector('meta[property="og:image"], meta[property="og:image:url"], meta[name="twitter:image"], meta[name="twitter:image:src"]');
                    let mu = metaImg ? (metaImg.getAttribute('content') || '') : '';
                    if (mu) {
                        try { mu = new URL(mu, location.href).href; } catch (e) {}
                        if (mu.indexOf('data:') !== 0 && mu.indexOf('http') === 0) {
                            blocks.unshift({ type: 'img', src: mu, caption: null });
                        }
                    }
                }
                if (blocks.length > 0) {
                    const articleData = {
                        title: title,
                        byline: byline,
                        url: location.href,
                        blocks: blocks
                    };
                    /* Late-hydration guard. News SPAs (e.g. NYT's "optimistic truncation":
                       isOptimisticallyTruncated) inject the tail of the article — bullet lists, later
                       paragraphs — into the DOM AFTER load fires, so an extraction pass that runs at
                       FCP or load-complete can capture a truncated body. The observer below re-runs
                       this function as content grows; we keep only the RICHEST pass per URL, so a
                       transient smaller re-render never clobbers a fuller article already handed off. */
                    let luminaMeasure = 0;
                    for (let li = 0; li < blocks.length; li++) {
                        const lb = blocks[li];
                        luminaMeasure += (lb && lb.text) ? lb.text.length : 24;
                    }
                    const luminaUrl = location.href;
                    const luminaPrev = window.__luminaReaderBest;
                    if (luminaPrev && luminaPrev.url === luminaUrl && luminaMeasure <= luminaPrev.len) {
                        return;
                    }
                    window.__luminaReaderBest = { url: luminaUrl, len: luminaMeasure };
                    luminaBridge("lumina-reader", JSON.stringify(articleData));
                }
                }
                luminaExtract();
                /* Re-extract as late-arriving article content lands, then stop. Installed once per
                   page (guarded) so repeated injections (FCP + load-complete + manual) and the
                   observer's own re-runs never stack observers. A MutationObserver only marks the DOM
                   dirty; a bounded interval does the actual (throttled) re-extraction, so a page that
                   mutates continuously (ads) can't starve a debounce and also can't spin extraction
                   every frame. The window self-terminates after ~12s. */
                if (!window.__luminaReaderObserverInstalled) {
                    window.__luminaReaderObserverInstalled = true;
                    let luminaDirty = false;
                    const luminaObserver = new MutationObserver(function() { luminaDirty = true; });
                    try {
                        luminaObserver.observe(document.body || document.documentElement, { childList: true, subtree: true, characterData: true });
                    } catch (e) {}
                    let luminaTicks = 0;
                    const luminaTimer = setInterval(function() {
                        luminaTicks++;
                        if (luminaDirty) {
                            luminaDirty = false;
                            try { luminaExtract(); } catch (e) {}
                        }
                        if (luminaTicks >= 15) {
                            clearInterval(luminaTimer);
                            try { luminaObserver.disconnect(); } catch (e) {}
                        }
                    }, 800);
                }
            })();
        """.trimIndent().replace("\n", " ")
    }

    private class ReaderPromptDelegate(
        private val oldDelegate: GeckoSession.PromptDelegate?,
        private val tabId: String
    ) : GeckoSession.PromptDelegate {
        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            if (prompt.message == "lumina-video") {
                prompt.defaultValue?.let {
                    com.firebolt9907.luminabrowser.ui.video.VideoPlayerManager.onStatePayload(it)
                }
                return GeckoResult.fromValue(prompt.confirm("ok"))
            }
            if (prompt.message == com.firebolt9907.luminabrowser.ui.content.LinkRectProbe.BRIDGE_MESSAGE) {
                prompt.defaultValue?.let {
                    com.firebolt9907.luminabrowser.ui.content.LinkRectProbe.onPayload(it)
                }
                return GeckoResult.fromValue(prompt.confirm("ok"))
            }
            if (prompt.message == com.firebolt9907.luminabrowser.ui.content.ThemeColorProbe.BRIDGE_MESSAGE) {
                prompt.defaultValue?.let {
                    com.firebolt9907.luminabrowser.ui.content.ThemeColorProbe.onPayload(tabId, it)
                }
                return GeckoResult.fromValue(prompt.confirm("ok"))
            }
            if (prompt.message == "lumina-reader") {
                val defaultValue = prompt.defaultValue
                if (defaultValue != null) {
                    try {
                        val article = parseArticleJson(defaultValue)
                        if (article != null) {
                            ReaderManager.instance.setReaderEligible(tabId, article)
                        }
                    } catch (e: Exception) {
                        Log.w("ReaderExtractor", "Failed to parse reader article JSON", e)
                    }
                }
                return GeckoResult.fromValue(prompt.confirm("ok"))
            }
            return oldDelegate?.onTextPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onAlertPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onButtonPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onChoicePrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onBeforeUnloadPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }

        override fun onRepostConfirmPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return oldDelegate?.onRepostConfirmPrompt(session, prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }
    }

    internal fun parseArticleJson(jsonStr: String): ReaderArticle? {
        val root = JSONObject(jsonStr)
        val title = root.optString("title", "")
        val byline = root.optString("byline").takeIf { it.isNotEmpty() && it != "null" }
        val sourceUrl = root.optString("url").takeIf { it.isNotEmpty() && it != "null" }
        val blocksJson = root.optJSONArray("blocks") ?: return null

        val blocks = ArrayList<ReaderBlock>()
        for (i in 0 until blocksJson.length()) {
            val obj = blocksJson.optJSONObject(i) ?: continue
            val type = obj.optString("type")
            when (type) {
                "p" -> {
                    val text = obj.optString("text")
                    val spansJson = obj.optJSONArray("spans")
                    val spans = if (spansJson != null) {
                        ArrayList<ReaderLinkSpan>(spansJson.length()).apply {
                            for (s in 0 until spansJson.length()) {
                                val spanObj = spansJson.optJSONObject(s) ?: continue
                                val spanText = spanObj.optString("text", "")
                                val href = spanObj.optString("href", "")
                                if (spanText.isNotEmpty() && href.isNotEmpty() && href != "null") {
                                    add(ReaderLinkSpan(spanText, href))
                                }
                            }
                        }
                    } else emptyList()
                    blocks.add(ReaderBlock.Paragraph(text, spans))
                }
                "h" -> {
                    val level = obj.optInt("level", 2)
                    val text = obj.optString("text")
                    blocks.add(ReaderBlock.Heading(level, text))
                }
                "bq" -> {
                    val text = obj.optString("text")
                    val pullQuote = obj.optBoolean("pullQuote", false)
                    blocks.add(ReaderBlock.Blockquote(text, pullQuote))
                }
                "pre" -> {
                    val text = obj.optString("text")
                    if (text.isNotEmpty()) blocks.add(ReaderBlock.Code(text))
                }
                "hr" -> blocks.add(ReaderBlock.Divider)
                "table" -> {
                    val rowsJson = obj.optJSONArray("rows")
                    if (rowsJson != null) {
                        val rows = ArrayList<List<String>>(rowsJson.length())
                        for (r in 0 until rowsJson.length()) {
                            val cellsJson = rowsJson.optJSONArray(r) ?: continue
                            val cells = ArrayList<String>(cellsJson.length())
                            for (c in 0 until cellsJson.length()) cells.add(cellsJson.optString(c))
                            if (cells.isNotEmpty()) rows.add(cells)
                        }
                        if (rows.size >= 2) {
                            blocks.add(ReaderBlock.Table(rows, obj.optBoolean("hasHeader", false)))
                        }
                    }
                }
                "li" -> {
                    val text = obj.optString("text")
                    val ordered = obj.optBoolean("ordered", false)
                    val index = obj.optInt("index", 0)
                    val depth = obj.optInt("depth", 0)
                    blocks.add(ReaderBlock.ListItem(text, ordered, index, depth))
                }
                "img" -> {
                    val src = obj.optString("src")
                    if (src.isNotEmpty()) {
                        val caption = obj.optString("caption").takeIf { it.isNotEmpty() && it != "null" }
                        val width = obj.optInt("w", 0).takeIf { it > 0 }
                        val height = obj.optInt("h", 0).takeIf { it > 0 }
                        blocks.add(ReaderBlock.Image(src, caption, width, height))
                    }
                }
            }
        }
        return ReaderArticle(title, byline, blocks, sourceUrl)
    }
}
