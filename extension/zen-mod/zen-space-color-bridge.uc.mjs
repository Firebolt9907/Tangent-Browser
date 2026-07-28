/*
 * Zen Space Color Sync — chrome -> content bridge (Sine mod, Zen only).
 *
 * WHAT: mirrors the active Zen space's accent colour into every web page as the inherited CSS
 * custom property `--zen-space-color` on the content document root. In-page UI can then theme
 * itself to the browser chrome purely in CSS — the Tangent Reader desktop extension reads it in
 * extension/src/reader.css (custom properties inherit across shadow-DOM boundaries, and `all:
 * initial` on the reader's :host does NOT reset them, so no extension JS is involved).
 *
 * WHY a chrome mod: Zen's per-space colour lives only in chrome (the `--zen-primary-color` var on
 * the chrome root + the `zen.theme.accent-color` pref). It is never exposed to web content or the
 * WebExtension `theme` API, so a privileged chrome script is the only thing that can carry the
 * value across the chrome/content boundary. Zen Mods (store) are CSS-only; Sine runs this JS.
 *
 * HOW: a tiny data:-URI frame script (loaded per <browser>) applies the last colour chrome sent and
 * re-applies on every new document. Chrome re-pushes on tab select, page load, and whenever Zen
 * rewrites `--zen-primary-color` (space switch / gradient edit — caught via a MutationObserver on
 * the chrome root's style attribute) or the accent pref changes.
 *
 * Globals (Services, gBrowser, window, document) are provided by the fx-autoconfig/Sine loader in
 * the browser.xhtml scope — same convention as other Sine .uc.mjs mods.
 */
(function () {
  "use strict";

  const PREF_ENABLED = "extensions.zen-space-color.enabled";
  const MSG = "ZenSpaceColor:set";

  function enabled() {
    try {
      return Services.prefs.getBoolPref(PREF_ENABLED, true);
    } catch (e) {
      return true;
    }
  }

  // The live accent Zen keeps for the *active* space, as an inline var on the chrome root — Zen
  // rewrites it on every space switch. Fall back to the persisted accent pref.
  function activeAccent() {
    try {
      const v = window
        .getComputedStyle(document.documentElement)
        .getPropertyValue("--zen-primary-color")
        .trim();
      if (v) return v;
    } catch (e) {}
    try {
      const p = Services.prefs.getStringPref("zen.theme.accent-color", "").trim();
      if (p) return p;
    } catch (e) {}
    return "";
  }

  // Stringified into a data: frame script and run in the content process. Applies the last colour
  // chrome sent as --zen-space-color on the page root, re-applying on each new document.
  function contentFrame() {
    let color = "";
    function apply() {
      try {
        const el = content.document && content.document.documentElement;
        if (!el) return;
        if (color) el.style.setProperty("--zen-space-color", color);
        else el.style.removeProperty("--zen-space-color");
        // Optional signal for JS consumers; the reader itself needs only the CSS var.
        content.dispatchEvent(
          new content.CustomEvent("ZenSpaceColorChange", { detail: { color: color } })
        );
      } catch (e) {}
    }
    addMessageListener("ZenSpaceColor:set", function (msg) {
      color = (msg.data && msg.data.color) || "";
      apply();
    });
    addEventListener("pageshow", apply, true);
    addEventListener("DOMContentLoaded", apply, true);
  }

  const FRAME_URI =
    "data:application/javascript;charset=utf-8," +
    encodeURIComponent("(" + contentFrame.toString() + ")();");

  function ensureFrame(browser) {
    if (!browser || browser.__zenSpaceColorFrame) return;
    try {
      // allowDelayedLoad=true: also (re)loads into future content processes for this browser.
      browser.messageManager.loadFrameScript(FRAME_URI, true);
      browser.__zenSpaceColorFrame = true;
    } catch (e) {}
  }

  function pushTo(browser, color) {
    if (!browser || !browser.messageManager) return;
    ensureFrame(browser);
    try {
      browser.messageManager.sendAsyncMessage(MSG, { color: color });
    } catch (e) {}
  }

  function pushAll() {
    const color = enabled() ? activeAccent() : "";
    try {
      for (const b of gBrowser.browsers) pushTo(b, color);
    } catch (e) {}
  }

  function pushActive() {
    const color = enabled() ? activeAccent() : "";
    try {
      pushTo(gBrowser.selectedBrowser, color);
    } catch (e) {}
  }

  // Coalesce the bursts Zen emits while dragging the gradient picker.
  let pending = false;
  function schedulePushAll() {
    if (pending) return;
    pending = true;
    window.setTimeout(function () {
      pending = false;
      pushAll();
    }, 60);
  }

  const tabsProgressListener = {
    onLocationChange(browser) {
      pushTo(browser, enabled() ? activeAccent() : "");
    },
  };

  const prefObserver = { observe: schedulePushAll };
  let rootObserver = null;

  function start() {
    if (!window.gBrowser || !gBrowser.tabContainer || !gBrowser.browsers) {
      window.setTimeout(start, 300);
      return;
    }

    gBrowser.tabContainer.addEventListener("TabSelect", pushActive);
    gBrowser.addTabsProgressListener(tabsProgressListener);

    rootObserver = new MutationObserver(schedulePushAll);
    rootObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["style"],
    });

    Services.prefs.addObserver("zen.theme.accent-color", prefObserver);
    Services.prefs.addObserver(PREF_ENABLED, prefObserver);

    pushAll();
    window.addEventListener("unload", stop, { once: true });
  }

  function stop() {
    try {
      gBrowser.tabContainer.removeEventListener("TabSelect", pushActive);
    } catch (e) {}
    try {
      gBrowser.removeTabsProgressListener(tabsProgressListener);
    } catch (e) {}
    try {
      rootObserver && rootObserver.disconnect();
    } catch (e) {}
    try {
      Services.prefs.removeObserver("zen.theme.accent-color", prefObserver);
    } catch (e) {}
    try {
      Services.prefs.removeObserver(PREF_ENABLED, prefObserver);
    } catch (e) {}
  }

  start();
})();
