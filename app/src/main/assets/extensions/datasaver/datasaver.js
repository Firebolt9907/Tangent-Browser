/**
 * Tangent Data Saver — background blocking script.
 *
 * Cancels heavy subresource requests while the OS Data Saver is active. This runs as a *blocking*
 * `webRequest` listener rather than the more obvious `GeckoSessionSettings.setAllowJavascript(false)`
 * for a load-bearing reason: disabling JS at the docshell also stops the app's own `javascript:`
 * injection (the top/bottom viewport spacers, the theme-color probe, the reader extractor's fallback
 * path — see AGENTS.md invariant #11). Cancelling *requests* leaves page-world script execution
 * enabled, so all of that machinery keeps working while the bytes it would have downloaded never
 * arrive.
 *
 * The policy is pushed from the app over the native port (see engine/DataSaverController.kt); this
 * script holds no policy of its own and blocks nothing until told to.
 */
(function () {
  'use strict';

  // Must match DataSaverController.PORT_NAME. Same naming convention as 'TangentReader' /
  // 'TangentPasskey'.
  var PORT_NAME = 'TangentDataSaver';

  // Request types we never cancel regardless of policy. `main_frame`/`sub_frame` are the documents
  // themselves; `xmlhttprequest`/`websocket`/`beacon` carry the content a page fetches about itself,
  // and cancelling those breaks sites outright (an SPA's article text arrives over XHR) for savings
  // that are typically small. `csp_report`/`ping` are negligible.
  var NEVER_BLOCK = {
    main_frame: true,
    sub_frame: true,
    xmlhttprequest: true,
    websocket: true,
    beacon: true,
    csp_report: true,
    ping: true,
  };

  // Effective policy, replaced wholesale on every host push. `enabled:false` is the inert default, so
  // an install that never receives a policy (or whose port dies) blocks nothing.
  var policy = {
    enabled: false,
    blockAll: {},
    blockThirdParty: {},
    aggressiveTypes: {},
    aggressiveHosts: {},
    exemptHosts: {},
  };

  var blocked = 0;
  var reportTimer = null;
  var port = null;

  /** Array -> set-like object, for O(1) membership on the request hot path. */
  function toSet(list) {
    var set = {};
    if (!list) return set;
    for (var i = 0; i < list.length; i++) set[list[i]] = true;
    return set;
  }

  function hostOf(url) {
    if (!url) return null;
    try {
      var u = new URL(url);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
      return u.hostname.replace(/^www\./, '');
    } catch (e) {
      return null;
    }
  }

  /**
   * Approximate registrable domain — the last two labels, with a third kept for the common two-part
   * public suffixes (`co.uk`, `com.au`, …). This only feeds the first-party/third-party split, where
   * being wrong costs one subresource decision, so the full Public Suffix List isn't worth shipping.
   */
  var TWO_PART_SUFFIX = /\.(co|com|net|org|gov|edu|ac|or|ne|go)\.[a-z]{2}$/;
  function registrable(host) {
    if (!host) return null;
    var labels = host.split('.');
    var keep = TWO_PART_SUFFIX.test(host) ? 3 : 2;
    if (labels.length <= keep) return host;
    return labels.slice(labels.length - keep).join('.');
  }

  /** True when [host] is, or is a subdomain of, any entry in [set]. */
  function hostListed(host, set) {
    if (!host) return false;
    if (set[host]) return true;
    var labels = host.split('.');
    for (var i = 1; i < labels.length - 1; i++) {
      if (set[labels.slice(i).join('.')]) return true;
    }
    return false;
  }

  function reportSoon() {
    if (reportTimer !== null) return;
    reportTimer = setTimeout(function () {
      reportTimer = null;
      if (!port) return;
      try {
        port.postMessage({ type: 'stats', blocked: blocked });
      } catch (e) {
        /* port closed — counters keep accruing for the next connection */
      }
    }, 2000);
  }

  function onBeforeRequest(details) {
    if (!policy.enabled) return {};
    var type = details.type;
    if (NEVER_BLOCK[type]) return {};

    var target = hostOf(details.url);
    if (!target) return {}; // non-http(s): about:, moz-extension:, resource:, data:, blob:

    // The document the request belongs to. `originUrl` is absent for some request types, and for a
    // top-level document request it points at the *previous* page, so fall back to documentUrl and
    // finally to the request itself (treated as first-party).
    var docHost = hostOf(details.documentUrl) || hostOf(details.originUrl) || target;

    if (hostListed(docHost, policy.exemptHosts)) return {};

    var block = false;
    if (policy.aggressiveTypes[type] && hostListed(docHost, policy.aggressiveHosts)) {
      block = true;
    } else if (policy.blockAll[type]) {
      block = true;
    } else if (policy.blockThirdParty[type] && registrable(target) !== registrable(docHost)) {
      block = true;
    }

    if (!block) return {};
    blocked++;
    reportSoon();
    return { cancel: true };
  }

  browser.webRequest.onBeforeRequest.addListener(
    onBeforeRequest,
    { urls: ['<all_urls>'] },
    ['blocking'],
  );

  function applyPolicy(next) {
    policy = {
      enabled: !!next.enabled,
      blockAll: toSet(next.blockAll),
      blockThirdParty: toSet(next.blockThirdParty),
      aggressiveTypes: toSet(next.aggressiveTypes),
      aggressiveHosts: toSet(next.aggressiveHosts),
      exemptHosts: toSet(next.exemptHosts),
    };
    if (next.resetStats) blocked = 0;
  }

  function connect() {
    if (typeof browser === 'undefined' || !browser.runtime || !browser.runtime.connectNative) return;
    try {
      port = browser.runtime.connectNative(PORT_NAME);
    } catch (e) {
      return;
    }
    port.onMessage.addListener(function (msg) {
      if (msg && msg.type === 'policy') applyPolicy(msg);
    });
    port.onDisconnect.addListener(function () {
      port = null;
      // The host went away (process death / engine teardown). Fail OPEN: a data-saver feature must
      // never be the reason a page silently renders without its assets once the controller that owns
      // the policy is gone.
      policy.enabled = false;
    });
  }

  connect();
})();
