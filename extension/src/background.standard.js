/*
 * Background event page for the STANDARD desktop extension build (Firefox MV3).
 *
 * Two responsibilities:
 *   1. Toolbar click -> inject the reader bundle into the active tab (MANUAL mode). The bundle toggles
 *      the overlay itself, so a second click dismisses it.
 *   2. Auto-open (opt-in) -> when the "autoOpen" setting is on, inject on every completed http(s) load
 *      in AUTO mode; the bundle then opens the reader only if the page is a readable article (silent
 *      otherwise). AUTO is signalled by setting a data-attribute on <html> just before injection.
 *
 * Injecting on demand (rather than a content script on every page) keeps the extractor off pages the
 * user never reads. Firefox MV3 runs this as a non-persistent event page; the autoOpen flag is cached
 * in a module global but always re-hydrated from storage on wake, so no persistent state is assumed.
 */
const api = (typeof browser !== 'undefined') ? browser : chrome;

let autoOpen = false;

async function hydrateAutoOpen() {
  try {
    const r = await api.storage.local.get('autoOpen');
    autoOpen = !!(r && r.autoOpen);
  } catch (e) { /* storage unavailable — stay off */ }
}
hydrateAutoOpen();

api.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.autoOpen) autoOpen = !!changes.autoOpen.newValue;
});

/** Inject the reader bundle. When `auto`, flag <html> first so the bundle opens silently-if-eligible. */
async function injectReader(tabId, auto) {
  if (auto) {
    await api.scripting.executeScript({
      target: { tabId },
      func: () => { document.documentElement.setAttribute('data-tangent-reader-auto', '1'); },
    });
  }
  await api.scripting.executeScript({ target: { tabId }, files: ['reader.bundle.js'] });
}

// (1) Manual toggle on toolbar click.
api.action.onClicked.addListener(async (tab) => {
  if (!tab || !tab.id) return;
  try {
    await injectReader(tab.id, false);
  } catch (e) {
    // Restricted pages (about:, addons.mozilla.org, view-source, PDF viewer) reject injection.
    console.warn('[Tangent Reader] injection blocked on this page:', e && e.message);
  }
});

// (2) Auto-open on page load when enabled. Event pages get onUpdated for every tab update; we act only
// on the final "complete" of a top-level http(s) navigation.
api.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  if (!autoOpen || changeInfo.status !== 'complete') return;
  const url = tab && tab.url;
  if (!url || !/^https?:/i.test(url)) return;
  try {
    await injectReader(tabId, true);
  } catch (e) {
    // Restricted/priv page or the host permission isn't granted yet — nothing to do.
  }
});
