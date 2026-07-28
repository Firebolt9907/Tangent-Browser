package com.firebolt9907.tangentbrowser.ui.reader

import com.firebolt9907.tangentbrowser.ui.content.loadJavascriptUrl
import mozilla.components.concept.engine.EngineSession

/**
 * The social-media reader path — a sibling to [ReaderExtractor] for pages whose content model is a
 * post + threaded replies rather than prose. The generic article extractor keys off `<article>`/`<p>`
 * scoring, which produces garbage on Reddit/X/Mastodon/Bluesky (SPA shells, upvote chrome, infinite
 * feeds). This extractor instead reads each platform's known DOM structure into attributed
 * [ReaderBlock.SocialPost] / [ReaderBlock.SocialComment] blocks.
 *
 * ## Flow (reuses the reader transport)
 *
 * [extract] injects [buildSocialExtractorScript] as a `javascript:` URL. The script walks the page,
 * builds `{title, byline, url, mode:"social", blocks:[…]}`, and hands it back over the **same**
 * `tangent-reader` prompt bridge the article extractor uses (via a pristine same-origin iframe prompt,
 * so a page that overrides `window.prompt` — Reddit/X both do — can't swallow it). The bridge is
 * routed in [ReaderExtractor]'s prompt delegate; [ReaderExtractor.parseArticleJson] recognises the
 * social block types. No new native channel is introduced.
 *
 * ## Routing
 *
 * [isSocialUrl] gates which pages use this path instead of the article path — matched on host **and**
 * a post-specific path segment (`/comments/`, `/status/`, `/post/`) so feeds/timelines don't hijack
 * the generic reader. The article content-script (`extensions/reader/extractor.js`) and the
 * javascript: article extractor both early-return on social hosts (a broader, host-only check) so they
 * never race a competing (bad) "article" onto the same tab.
 *
 * ## SPA hydration
 *
 * These sites hydrate late, so the script installs a bounded MutationObserver+interval that re-runs
 * extraction as content lands, keeping the richest pass per URL and only reporting "unavailable" once,
 * after the retry window (~12s) closes with nothing found.
 */
object SocialExtractor {

    /** Popular Mastodon instances (host-agnostic detection is done in-page via `.detailed-status`). */
    private val MASTODON_HOSTS = setOf(
        "mastodon.social", "mastodon.online", "mastodon.world", "mstdn.social", "mas.to",
        "fosstodon.org", "hachyderm.io", "infosec.exchange", "techhub.social", "universeodon.com",
        "mstdn.jp", "pawoo.net", "mastodon.art", "chaos.social", "troet.cafe",
    )

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.removePrefix("www.")?.lowercase()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun pathOf(url: String): String = runCatching {
        (java.net.URI(url).path ?: "").lowercase()
    }.getOrDefault("")

    private fun isMastodonHost(host: String): Boolean =
        host in MASTODON_HOSTS || host.startsWith("mastodon.") || host.startsWith("mstdn.")

    /**
     * Whether [url] is a *social host* at all (Reddit/X/Mastodon/Bluesky), independent of the specific
     * page. Used by the article extractors to bow out so they don't produce a junk article on a social
     * shell. Broader than [isSocialUrl] — it does not require a post-specific path.
     */
    fun isSocialHost(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return isRedditHost(host) || isTwitterHost(host) || isBlueskyHost(host) || isMastodonHost(host)
    }

    private fun isRedditHost(host: String) = host == "reddit.com" || host.endsWith(".reddit.com")
    private fun isTwitterHost(host: String) =
        host == "twitter.com" || host.endsWith(".twitter.com") || host == "x.com" || host.endsWith(".x.com")
    private fun isBlueskyHost(host: String) = host == "bsky.app" || host.endsWith(".bsky.app")

    /**
     * Whether [url] is a specific social *post* page the social reader should handle. Host + a
     * post-specific path segment, so timelines/feeds/profiles fall through to the (host-guarded, and
     * therefore inert) article path rather than showing the first random post in a feed.
     */
    fun isSocialUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val path = pathOf(url)
        return when {
            isRedditHost(host) -> path.contains("/comments/")
            isTwitterHost(host) -> path.contains("/status/")
            isBlueskyHost(host) -> path.contains("/post/")
            // Mastodon post URLs vary by instance (/@user/id, /web/statuses/id, /users/x/statuses/id);
            // rely on in-page `.detailed-status` detection instead of a path pattern.
            isMastodonHost(host) -> true
            else -> false
        }
    }

    /** Inject the social extractor into [engineSession]; results return over the tangent-reader bridge. */
    fun extract(engineSession: EngineSession, tabId: String) {
        engineSession.loadJavascriptUrl(buildSocialExtractorScript())
    }

    /**
     * The injected social-extraction `javascript:` URL as a flattened single-line string.
     *
     * NOTE: flattened via `.replace("\n", " ")` before injection, so — exactly as in
     * [ReaderExtractor.buildReaderExtractorScript] — NO `//` line comments may appear (a `//` would
     * comment out the rest of the one-liner, including the final bridge call). Use block comments only.
     * The script also deliberately avoids the `$` character throughout (no template literals, no regex
     * end-anchors) so it needs no Kotlin string escaping and can be pinned by JVM tests verbatim.
     */
    internal fun buildSocialExtractorScript(): String {
        return """
            javascript:(function() {
                function tangentBridge(msg, val) {
                    var ifr = null;
                    try {
                        ifr = document.createElement('iframe');
                        ifr.setAttribute('aria-hidden', 'true');
                        ifr.style.cssText = 'display:none!important;width:0;height:0;border:0;position:absolute;left:-9999px;';
                        (document.body || document.documentElement).appendChild(ifr);
                        var w = ifr.contentWindow;
                        if (w && typeof w.prompt === 'function') { return w.prompt(msg, val); }
                    } catch (e) {
                    } finally {
                        if (ifr && ifr.parentNode) ifr.parentNode.removeChild(ifr);
                    }
                    return null;
                }
                function txt(el) { return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
                function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return u; } }
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
                var GIF_RE = /\.gif(\?|${'$'})/i;
                /* Dedupe key = the URL's last path segment sans query, so Reddit's preview.redd.it
                   backdrop + i.redd.it original (and X's name=small/large variants) collapse to one. */
                function mediaKey(u) {
                    try { var p = new URL(u, location.href); return (p.pathname.split('/').filter(Boolean).pop() || p.pathname).toLowerCase(); }
                    catch (e) { return u; }
                }
                /* Collect inline media from <img> (and <video poster> as a static frame), skipping
                   avatars/emoji/spacers and aria-hidden backdrops, deduping by [mediaKey], flagging
                   animated GIFs. Bounded so a media-heavy post can't bloat the payload. */
                function collectMedia(list) {
                    var out = [], seen = {};
                    for (var i = 0; i < list.length && out.length < 12; i++) {
                        var im = list[i];
                        if (im.getAttribute && im.getAttribute('aria-hidden') === 'true') continue;
                        var s = '';
                        if (im.nodeName === 'VIDEO') { s = im.getAttribute('poster') || ''; }
                        else { s = im.currentSrc || im.getAttribute('src') || ''; if (!s) s = bestSrcset(im.getAttribute('srcset') || ''); }
                        if (!s || s.indexOf('data:') === 0) continue;
                        s = abs(s);
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
                function redditMediaEls(scope) {
                    var container = scope.querySelector('[slot="post-media-container"]');
                    if (container) return container.querySelectorAll('img, video[poster]');
                    var pi = scope.querySelector('#post-image');
                    if (pi) return [pi];
                    return scope.querySelectorAll('img, video[poster]');
                }
                /* Link spans within a body element ({text, href}); the reader maps each back into the
                   plain text by substring match (same shape as the article paragraph spans). */
                function richSpans(el) {
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
                function host() { try { return location.hostname.replace(/^www\./, '').toLowerCase(); } catch (e) { return ''; } }

                function extractReddit() {
                    if (document.querySelector('shreddit-post')) return extractRedditShreddit();
                    if (document.querySelector('.thing.link, #siteTable .thing')) return extractRedditOld();
                    return null;
                }
                function redditBody(scope) {
                    return scope.querySelector('[slot="text-body"]') || scope.querySelector('[slot="comment"]') || scope.querySelector('.md');
                }
                function extractRedditShreddit() {
                    var post = document.querySelector('shreddit-post');
                    if (!post) return null;
                    var title = post.getAttribute('post-title') || txt(post.querySelector('h1'));
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
                    var blocks = [{ type: 'social_post', author: author || sub || 'Reddit', handle: author ? ('u/' + author) : sub, avatar: null, timestamp: ts, meta: meta.join(' · '), title: title, text: bodyEl ? txt(bodyEl) : '', spans: bodyEl ? richSpans(bodyEl) : [], media: collectMedia(redditMediaEls(post)) }];
                    var opAuthor = author;
                    var cEls = document.querySelectorAll('shreddit-comment');
                    var count = 0;
                    for (var i = 0; i < cEls.length && count < 80; i++) {
                        var c = cEls[i];
                        var a = c.getAttribute('author');
                        if (!a || a === '[deleted]') continue;
                        var body = c.querySelector('[slot="comment"]') || c.querySelector('.md');
                        var t = body ? txt(body) : '';
                        if (!t) continue;
                        var depth = parseInt(c.getAttribute('depth') || '0') || 0;
                        var cs = c.getAttribute('score');
                        blocks.push({ type: 'social_comment', author: a, handle: 'u/' + a, avatar: null, timestamp: '', meta: cs ? (fmtCount(cs) + ' points') : null, text: t, spans: body ? richSpans(body) : [], media: body ? collectMedia(body.querySelectorAll('img, video[poster]')) : [], depth: Math.min(depth, 8), op: a === opAuthor });
                        count++;
                    }
                    return { title: title, byline: sub || null, blocks: blocks };
                }
                function extractRedditOld() {
                    var thing = document.querySelector('#siteTable .thing.link') || document.querySelector('.thing.link');
                    if (!thing) return null;
                    var title = txt(thing.querySelector('a.title'));
                    var author = txt(thing.querySelector('.author'));
                    var sub = txt(thing.querySelector('.subreddit'));
                    var scoreEl = thing.querySelector('.score.unvoted');
                    var score = scoreEl ? (scoreEl.getAttribute('title') || txt(scoreEl)) : null;
                    var bodyEl = thing.querySelector('.usertext-body .md');
                    var meta = [];
                    if (score) meta.push(fmtCount(score) + ' points');
                    if (sub) meta.push(sub.charAt(0) === 'r' ? sub : ('r/' + sub));
                    var blocks = [{ type: 'social_post', author: author || 'Reddit', handle: author ? ('u/' + author) : null, avatar: null, timestamp: '', meta: meta.join(' · '), title: title, text: bodyEl ? txt(bodyEl) : '', spans: bodyEl ? richSpans(bodyEl) : [], media: collectMedia(thing.querySelectorAll('.usertext-body img')) }];
                    var cEls = document.querySelectorAll('.commentarea .comment');
                    var count = 0;
                    for (var i = 0; i < cEls.length && count < 80; i++) {
                        var c = cEls[i];
                        var a = txt(c.querySelector('.entry .author'));
                        if (!a) continue;
                        var body = c.querySelector('.entry .usertext-body .md');
                        var t = body ? txt(body) : '';
                        if (!t) continue;
                        var depth = 0, p = c.parentElement;
                        while (p) { if (p.classList && p.classList.contains('comment')) depth++; p = p.parentElement; }
                        var cScoreEl = c.querySelector('.entry .score.unvoted');
                        var cs = cScoreEl ? (cScoreEl.getAttribute('title') || txt(cScoreEl)) : null;
                        blocks.push({ type: 'social_comment', author: a, handle: 'u/' + a, avatar: null, timestamp: '', meta: cs ? (fmtCount(cs) + ' points') : null, text: t, spans: body ? richSpans(body) : [], media: body ? collectMedia(body.querySelectorAll('img')) : [], depth: Math.min(depth, 8), op: a === author });
                        count++;
                    }
                    return { title: title, byline: sub || null, blocks: blocks };
                }

                function twName(un) {
                    var whole = txt(un);
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
                        var t = textEl ? txt(textEl) : '';
                        var media = collectMedia(art.querySelectorAll('[data-testid="tweetPhoto"] img, [data-testid="tweetPhoto"] video[poster], [data-testid="videoPlayer"] video[poster]'));
                        if (!t && !media.length) continue;
                        var un = art.querySelector('[data-testid="User-Name"]');
                        var nm = un ? twName(un) : { name: '', handle: '' };
                        var timeEl = art.querySelector('time');
                        var ts = timeEl ? (timeEl.getAttribute('datetime') || txt(timeEl)) : '';
                        var g = art.querySelector('[role="group"][aria-label]');
                        var stats = g ? g.getAttribute('aria-label') : null;
                        var spans = richSpans(textEl);
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
                    var name = txt(scope.querySelector('.display-name__html')) || txt(scope.querySelector('.display-name bdi'));
                    var acct = txt(scope.querySelector('.display-name__account'));
                    if (!name) name = txt(scope.querySelector('.display-name'));
                    return { name: name, acct: acct };
                }
                function extractMastodon() {
                    var det = document.querySelector('.detailed-status');
                    if (!det) return null;
                    var a = mastoAuthor(det);
                    var contentEl = det.querySelector('.status__content');
                    var timeEl = det.querySelector('time');
                    var ts = timeEl ? (timeEl.getAttribute('datetime') || txt(timeEl)) : '';
                    var blocks = [{ type: 'social_post', author: a.name || a.acct || 'Post', handle: a.acct || null, avatar: null, timestamp: ts, meta: null, title: null, text: contentEl ? txt(contentEl) : '', spans: richSpans(contentEl), media: collectMedia(det.querySelectorAll('.media-gallery img, .media-gallery__item img, video[poster]')) }];
                    var opAcct = a.acct;
                    var sts = document.querySelectorAll('.status:not(.detailed-status)');
                    var count = 0;
                    for (var i = 0; i < sts.length && count < 80; i++) {
                        var s = sts[i];
                        var ca = mastoAuthor(s);
                        var cc = s.querySelector('.status__content');
                        var t = cc ? txt(cc) : '';
                        if (!t) continue;
                        var cTime = s.querySelector('time');
                        blocks.push({ type: 'social_comment', author: ca.name || ca.acct || 'Reply', handle: ca.acct || null, avatar: null, timestamp: cTime ? (cTime.getAttribute('datetime') || '') : '', meta: null, text: t, spans: richSpans(cc), media: collectMedia(s.querySelectorAll('.media-gallery img, .media-gallery__item img')), depth: 0, op: ca.acct && ca.acct === opAcct });
                        count++;
                    }
                    return { title: '', byline: a.acct || 'Mastodon', blocks: blocks };
                }

                function bskyText(item) {
                    var el = item.querySelector('[data-testid="postText"]');
                    if (el) return { text: txt(el), el: el };
                    var cands = item.querySelectorAll('div[dir="auto"]');
                    var best = null, bestLen = 0;
                    for (var i = 0; i < cands.length; i++) { var l = (cands[i].textContent || '').length; if (l > bestLen) { bestLen = l; best = cands[i]; } }
                    return { text: best ? txt(best) : '', el: best };
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
                        var name = nameEl ? txt(nameEl) : handle;
                        var spans = richSpans(bt.el);
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

                function extract() {
                    var h = host();
                    try {
                        if (h === 'reddit.com' || h.indexOf('.reddit.com') >= 0) return extractReddit();
                        if (h === 'twitter.com' || h.indexOf('.twitter.com') >= 0 || h === 'x.com' || h.indexOf('.x.com') >= 0) return extractTwitter();
                        if (h === 'bsky.app' || h.indexOf('.bsky.app') >= 0) return extractBluesky();
                        return extractMastodon();
                    } catch (e) { return null; }
                }
                function measure(blocks) {
                    var m = 0;
                    for (var i = 0; i < blocks.length; i++) { var b = blocks[i]; m += (b && b.text) ? b.text.length : 8; if (b && b.title) m += b.title.length; }
                    return m;
                }
                function run() {
                    var r = extract();
                    if (!r || !r.blocks || !r.blocks.length) return;
                    var m = measure(r.blocks);
                    var prev = window.__tangentSocialBest;
                    if (prev && prev.url === location.href && m <= prev.len) return;
                    window.__tangentSocialBest = { url: location.href, len: m };
                    r.url = location.href;
                    r.mode = 'social';
                    tangentBridge('tangent-reader', JSON.stringify(r));
                }
                run();
                if (!window.__tangentSocialObserverInstalled) {
                    window.__tangentSocialObserverInstalled = true;
                    var dirty = false;
                    var obs = new MutationObserver(function() { dirty = true; });
                    try { obs.observe(document.body || document.documentElement, { childList: true, subtree: true, characterData: true }); } catch (e) {}
                    var ticks = 0;
                    var timer = setInterval(function() {
                        ticks++;
                        if (dirty) { dirty = false; try { run(); } catch (e) {} }
                        if (ticks >= 15) {
                            clearInterval(timer);
                            try { obs.disconnect(); } catch (e) {}
                            if (!window.__tangentSocialBest && !window.__tangentSocialSentNone) {
                                window.__tangentSocialSentNone = true;
                                tangentBridge('tangent-reader', '');
                            }
                        }
                    }, 800);
                }
            })();
        """.trimIndent().replace("\n", " ")
    }
}
