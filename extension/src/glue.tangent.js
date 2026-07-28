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
