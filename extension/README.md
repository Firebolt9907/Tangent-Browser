# Tangent Reader — extension

The reader-mode extractor as a standalone browser extension, built from **one shared source** with a
compile-time target switch.

```
node build.js standard    # standalone desktop extension (Firefox MV3), in-page reader overlay
node build.js tangent     # the built-in extension the Tangent Android app ships
node build.js tangent --check   # CI guard: fail if the committed app asset drifted from src/
```

## Why one source, two targets

`tangentExtractArticle()` (`src/extractor.core.js`) is a pure function: given the compact per-site
rules, it walks the live DOM and returns the article model (`{ title, byline, url, blocks }`) — the
same JSON the app parses in `ReaderExtractor.parseArticleJson`. Both targets embed *this exact
extractor*; they differ only in **transport (the glue) and manifest**:

| | `standard` | `tangent` |
|---|---|---|
| Glue | `src/glue.standard.js` — toolbar click toggles an in-page overlay | `src/glue.tangent.js` — posts blocks to the native app over a `connectNative` Port |
| Renders where | in the page, via `src/render.js` + `src/reader.css` (shadow DOM) | native Compose (`ui/reader/ReaderView.kt`) |
| Per-site rules | **bundled** at build time (compacted from `reader_rules.json`) | pushed at runtime by `ReaderPortHandler` over the Port |
| Manifest | `src/manifest.standard.json` — **MV3** (Firefox event page + `action` + `scripting`) | `src/manifest.tangent.json` — MV2 (GeckoView built-in requires it) |
| Output | `dist/standard/` | `app/src/main/assets/extensions/reader/` (the app's shipping asset) |

Because the extractor is shared, a change to extraction behaviour lands in both targets from the one
file — no more hand-mirroring `extractor.js` against the Kotlin path.

## Manifest versions

- **`standard` is Manifest V3.** Firefox MV3 shape: non-persistent `background.scripts` event page,
  `action` toolbar button, on-demand `scripting.executeScript` injection (gated by `activeTab`, so no
  content script runs until you click).
- **`tangent` is Manifest V2 — and must stay V2.** GeckoView installs it via
  `installBuiltInWebExtension` with the `geckoViewAddons` + `nativeMessaging` permissions, which are
  MV2-only in this GeckoView. This is not the desktop extension; it never leaves the app.

## Install the standalone (Firefox-based browsers)

1. `node build.js standard`
2. Firefox → `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on** → pick
   `dist/standard/manifest.json`.
3. Click the toolbar button on any article: a ~640px reader **sheet slides up from the bottom** over a
   dimmed backdrop. Click again (or the ✕, Esc, or the backdrop) to slide it away. On a page it can't
   read, the click shows a brief failure toast instead. Works the same in LibreWolf / Zen / Floorp /
   Mullvad / Waterfox.

### Auto-open

Open the extension's **Preferences** (Firefox `about:addons` → Tangent Reader → Preferences) and enable
**"Automatically open Reader when a page is readable."** With it on, the reader opens by itself as each
article finishes loading; dismissing it won't reopen it for that page, and non-articles stay silent.
Auto-open injects on every completed page, so Firefox requires the extension's **access to website
data** to be granted (host permission) — the manual toolbar toggle works without it via `activeTab`.

For a persistent (unsigned) install, use a Developer/Nightly/ESR build with
`xpinstall.signatures.required = false`, or submit `dist/standard/` to AMO for signing.

## Optional: match the Zen space colour (`zen-mod/`)

On **Zen Browser**, the companion Sine mod in [`zen-mod/`](zen-mod/) exposes the active space's accent
as the `--zen-space-color` CSS variable on every page. With it installed, the reader sheet tints its
links and top edge to your current space; without it, the reader is unchanged. Zen's space colour is
chrome-only and never reaches web content, so a privileged chrome mod (Sine, not the CSS-only Zen Mods
store) is required — see [`zen-mod/README.md`](zen-mod/README.md). The extension side is pure CSS
(`var(--zen-space-color, …)` in `src/reader.css`).

## Source files

```
src/
  extractor.core.js      SHARED pure extractor (tangentExtractArticle). Single source of truth.
  glue.tangent.js        Port transport for the Android app (built-in target only).
  glue.standard.js       Overlay toggle + auto/manual mode + failure toast (standalone only).
  render.js              Article blocks -> shadow-DOM bottom sheet (standalone only).
  reader.css             Sheet + backdrop stylesheet, inlined into the standalone bundle.
  background.standard.js  MV3 event page: toolbar-click inject + auto-open on page load.
  options.html/.js        Preferences page: the auto-open toggle (browser.storage.local).
  manifest.standard.json  MV3 (Firefox).
  manifest.tangent.json   MV2 (GeckoView built-in).
build.js                 Target switch (see top).
test-render.js           Browserless test of render.js across every block type.
```

## Editing rules

- The **built-in target's output is the app's shipping asset.** After editing anything under `src/`
  that affects the built-in build, run `node build.js tangent` and commit the regenerated
  `app/src/main/assets/extensions/reader/`. `node build.js tangent --check` fails the build if you
  forget.
- Keep `src/extractor.core.js` behaviourally in step with the Kotlin prompt-bridge extractor
  (`ReaderExtractor.buildReaderExtractorScript`) — see `AGENTS.md` invariant #11 and the
  `tools/top-padding-harness/readerRun.js` cross-check.
- `dist/` is generated; do not edit or commit it.
```
