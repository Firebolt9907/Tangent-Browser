/*
 * Content-side glue for the STANDARD desktop extension build only.
 *
 * Injected (as part of reader.bundle.js) into the active tab by background.js. Two entry modes:
 *   - MANUAL (toolbar click): toggle the overlay — open it, or dismiss it if already open. On a page
 *     with no article, show a failure toast so the click gives visible feedback.
 *   - AUTO   (auto-open setting, on page load): open the overlay only if the page is readable, and
 *     only once per document. Never toggles off and never toasts — silent when a page isn't an article.
 * background.js signals AUTO by setting a data-attribute on <html> just before injecting this bundle.
 *
 * Re-injection safe: state lives on the DOM (overlay presence + <html> markers), not on script
 * globals, because each injection re-runs this file fresh.
 */
(function () {
  var OVERLAY_ID = 'tangent-reader-overlay-host';
  var docEl = document.documentElement;

  // Read + clear the mode signal background.js left on <html>.
  var auto = docEl.getAttribute('data-tangent-reader-auto') === '1';
  docEl.removeAttribute('data-tangent-reader-auto');

  var existing = document.getElementById(OVERLAY_ID);
  if (existing) {
    if (auto) return; // never toggle an open reader closed on an auto pass
    try { if (existing.tangentDismiss) existing.tangentDismiss(); else existing.remove(); } catch (e) {}
    return;
  }

  // In auto mode, open at most once per document (avoids re-opening on duplicate load events or after
  // the user has already closed it). Manual clicks ignore this marker.
  if (auto && docEl.getAttribute('data-tangent-reader-shown') === '1') return;

  var rules = (typeof TANGENT_BUNDLED_RULES !== 'undefined') ? TANGENT_BUNDLED_RULES : [];
  var css = (typeof TANGENT_READER_CSS !== 'undefined') ? TANGENT_READER_CSS : '';

  var article = null;
  try { article = tangentExtractArticle(rules); } catch (e) { article = null; }

  if (!article || !article.blocks || !article.blocks.length) {
    if (!auto) tangentReaderToast('This page couldn’t be opened in Reader.');
    return;
  }

  try {
    var host = tangentRenderReader(article, OVERLAY_ID, css);
    (document.body || docEl).appendChild(host);
    if (auto) docEl.setAttribute('data-tangent-reader-shown', '1');
  } catch (e) {
    if (!auto) tangentReaderToast('Reader failed to render this page.');
  }

  /**
   * Transient failure toast, rendered inside its own shadow root so the page's CSS can't restyle or
   * hide it. A warning chip + message slides up bottom-centre, then auto-dismisses.
   */
  function tangentReaderToast(msg) {
    var thost = document.createElement('div');
    thost.style.cssText = 'all:initial;position:fixed;left:0;right:0;bottom:0;z-index:2147483647;';
    var troot = thost.attachShadow ? thost.attachShadow({ mode: 'open' }) : thost;

    var style = document.createElement('style');
    style.textContent =
      '.t{position:fixed;left:50%;bottom:32px;transform:translateX(-50%) translateY(14px) scale(.96);' +
      'display:flex;align-items:center;gap:11px;max-width:min(440px,92vw);' +
      'background:#26272b;color:#fff;padding:13px 18px;border-radius:13px;' +
      'font:500 14px/1.35 -apple-system,BlinkMacSystemFont,"Segoe UI",system-ui,sans-serif;' +
      'box-shadow:0 10px 34px rgba(0,0,0,.4);opacity:0;' +
      'transition:opacity .24s ease,transform .34s cubic-bezier(.32,1.35,.5,1)}' +
      '.t.show{opacity:1;transform:translateX(-50%) translateY(0) scale(1)}' +
      '.i{flex:none;width:22px;height:22px;border-radius:50%;background:#e6a63c;color:#241a06;' +
      'display:flex;align-items:center;justify-content:center;font:700 14px system-ui}' +
      '.t.show .i{animation:tangent-toast-pop .46s cubic-bezier(.2,.8,.2,1.2) .08s both}' +
      '@keyframes tangent-toast-pop{0%{transform:scale(.3) rotate(-12deg);opacity:0}' +
      '55%{transform:scale(1.18) rotate(4deg)}100%{transform:scale(1) rotate(0);opacity:1}}' +
      '@media(prefers-reduced-motion:reduce){.t{transition:none}.t.show .i{animation:none}}';
    troot.appendChild(style);

    var chip = document.createElement('div');
    chip.className = 't';
    var icon = document.createElement('span');
    icon.className = 'i';
    icon.textContent = '!';
    var label = document.createElement('span');
    label.textContent = msg;
    chip.appendChild(icon);
    chip.appendChild(label);
    troot.appendChild(chip);

    (document.body || docEl).appendChild(thost);
    requestAnimationFrame(function () { chip.classList.add('show'); });
    setTimeout(function () {
      chip.classList.remove('show');
      setTimeout(function () { try { thost.remove(); } catch (e) {} }, 300);
    }, 2600);
  }
})();
