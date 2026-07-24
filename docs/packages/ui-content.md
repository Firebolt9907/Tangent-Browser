# `ui/content/` — The engine surface and page injection

This is where Compose meets GeckoView.

## File map

### `ContentHost.kt`
The single host for the live `EngineView`. Wraps it in an `AndroidView` and rebinds it to the currently selected `EngineSession` in one `DisposableEffect(engineView, engineSession, tabId)` block.

Key responsibilities:

- **Render**. The `AndroidView` `update` lambda deliberately does *not* call `engineView.render(session)` — only the `DisposableEffect` does. This guarantees that the leaving-tab capture (run in `onDispose` before rebind) samples the *old* session, not the incoming one.
- **Insets**. Sets Gecko's top/bottom dynamic toolbar insets so page content respects the SafariBar. When `topPaddingFixActive` is true the EngineView gets `padding(top = topChromeInsetPx)` and renders below the status bar.
- **Page injection**. Calls `EngineSession.injectViewportSpacers(topPx, bottomPx)` on first contentful paint and load-complete. Also calls `injectPageZoom(factor)` whenever `pageZoomFactor` changes.
- **Capture pipeline**. Registers an `EngineSession.Observer` and runs `EngineView.captureThumbnail` at six different moments (see ARCHITECTURE.md → "The capture pipeline"). Every capture lands in `onSnapshotCaptured` *and* `TabSnapshotCache`.
- **Scroll/paint plumbing**. Translates `EngineView.verticalScrollDelta` into `onPageScrollDelta`, fires `onTabPainted(tabId)` on paint callbacks so `TangentRoot` can clear the sticky cover, and routes legacy predictive-back touch callbacks (currently gated off at the call site).
- **Lifecycle**. `engineView.setActivityContext(context)` + `Lifecycle` observer hookups; cleans up the engine view on dispose.

`ContentHost` reads its arguments and emits everything via callbacks — it owns *no* persistent state of its own outside the `engineView` `remember(engine)`.

### `PageBottomSpacer.kt`
Two `EngineSession` extension functions, both implemented as `javascript:` URLs loaded with `LoadUrlFlags.ALLOW_JAVASCRIPT_URL | LOAD_FLAGS_REPLACE_HISTORY`:

- **`injectViewportSpacers(topPx, bottomPx, skipHeaderInsets = false)`** installs:
  - A `<style id="tangent-viewport-scroll-style">` with `scroll-padding-top` / `scroll-padding-bottom` so anchor jumps don't land under chrome.
  - A top spacer `<div id="tangent-top-scroll-spacer">` prepended to `<body>`.
  - A bottom spacer `<div id="tangent-bottom-scroll-spacer">` appended to `<body>`.
  - **If `skipHeaderInsets = false`**, additionally walks `position: fixed` / `sticky` elements pinned near the top and shifts them down by `topPx`. Re-runs on a `MutationObserver` + a long-decaying schedule of `setTimeout`s (it has to keep checking because SPAs mutate the header continuously). When the per-site "top padding fix" is on, the EngineView itself is padded so we pass `skipHeaderInsets = true` to avoid restyle loops on already-padded sites.
- **`injectPageZoom(factor)`** sets `document.documentElement.style.zoom`. Empty string when `factor == 1.0f`.

Both scripts are minified to a single line because Gecko's `javascript:` parser doesn't like newlines.

### `AmoAddonInstaller.kt`
Small page-level click bridge for AMO detail pages: clicking "Add to Firefox" navigates to the add-on's `.xpi`, and `.xpi` / AMO `/downloads/latest/` requests are intercepted and forwarded to `AddonController.installFromPageRequest`.

### `JsInjection.kt`
The single `EngineSession.evaluate`-style helper every injector uses: runs a script via a
`javascript:` URL with the one flag combination (`ALLOW_JAVASCRIPT_URL | REPLACE_HISTORY`) so the
`javascript:` URL never lands in session history (Back never stops on it). Viewport spacers, page
zoom, reader extraction, video control, the AMO installer and the YouTube clean player all go through
it.

### The `prompt()` bridge probes
GeckoView only offers one-way `javascript:` injection (no return value), so these read data *back* from
the page by having injected JS call `prompt()`, which native intercepts:

- **`ThemeColorProbe.kt`** — reads the page's `<meta name="theme-color">` (what Safari samples to tint
  its status bar) so the per-site top strip / status bar can be painted that color; reports `null` when
  the page declares none.
- **`LinkRectProbe.kt`** — reads the on-screen rectangle of a long-pressed link so `LinkPeekCard` can
  do a true shared-element morph *from that element*, not from the bare finger point.

### Link & image glances
- **`LinkPeekCard.kt`** — the long-press link "glance": a shared-element card that morphs out of the
  link and previews the destination.
- **`PeekEngineHolder.kt`** — owns a **single reusable** peek `EngineSession` + `EngineView` for that
  preview. Reusing one session across long-presses (re-`loadUrl` instead of create/close each time)
  avoids the content-process churn that fragments native memory and causes GC hitches.
- **`UtilityWebCard.kt`** — a floating card that runs an isolated `EngineSession`/`EngineView` *never*
  added to the store (same isolation as the peek), for add-on popups / options pages / sign-in flows
  that should float over the current page without navigating away.
- **`ImageContextSheet.kt`** — long-press action sheet for a bare `<img>` (open in new tab, save, copy
  address, share). Linked images keep the `LinkPeekCard` glance instead.

### Other surfaces
- **`CrashRestoreOverlay.kt`** — the recovery surface when a tab's content process is killed
  (`ContentState.crashed`); Restore re-links a fresh session and reloads the last URL.
- **`PullRefreshContainer.kt`** — pull-to-refresh for the single `EngineView`, implemented as a
  `NestedScrollingParent3` (GeckoView handles touch async and disallows interception, so touch
  interception can't be used).
- **`SiteTint.kt`** — provides the active "tint pages to my system color" overlay color near the root
  so every place that draws a page snapshot (grid/deck cards, switch covers, scaled backdrop) can
  overlay it at draw time without threading a color through each signature. Tracks the toggle live so
  snapshots never disagree with the live page.
- **`YouTubeCleanPlayer.kt`** — hides YouTube's own UI so fullscreen/PiP show only the raw `<video>`
  (PiP captures the whole surface). DOM-structure-agnostic on purpose.
- **`CacheHtmlDebug.kt`** — hidden `debug:cache` command: dumps the page's parsed HTML as a data URI
  for diagnosing Google-Cache paywall-bypass consistency.

## When to touch this package

- New page-injection behavior → `PageBottomSpacer.kt`. Watch out for the MutationObserver feedback loop (search `IMPLEMENTATION.md` for "Tried and reverted" — there's a cautionary tale).
- New capture timing → `ContentHost.kt`. Capture sites should ideally key on `tabId` not `pageUrl` to avoid re-firing on same-tab navigation.
- New engine-level callback → register on the `Observer` inside `ContentHost`; surface through a callback prop.
- **Do not** introduce a second `engineView.render(session)` call site. It will silently corrupt the snapshot cache. See `IMPLEMENTATION.md`.
