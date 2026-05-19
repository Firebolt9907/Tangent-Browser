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
- **Scroll/paint plumbing**. Translates `EngineView.verticalScrollDelta` into `onPageScrollDelta`, fires `onTabPainted(tabId)` on paint callbacks so `LuminaRoot` can clear the sticky cover, and routes legacy predictive-back touch callbacks (currently gated off at the call site).
- **Lifecycle**. `engineView.setActivityContext(context)` + `Lifecycle` observer hookups; cleans up the engine view on dispose.

`ContentHost` reads its arguments and emits everything via callbacks — it owns *no* persistent state of its own outside the `engineView` `remember(engine)`.

### `PageBottomSpacer.kt`
Two `EngineSession` extension functions, both implemented as `javascript:` URLs loaded with `LoadUrlFlags.ALLOW_JAVASCRIPT_URL | LOAD_FLAGS_REPLACE_HISTORY`:

- **`injectViewportSpacers(topPx, bottomPx, skipHeaderInsets = false)`** installs:
  - A `<style id="lumina-viewport-scroll-style">` with `scroll-padding-top` / `scroll-padding-bottom` so anchor jumps don't land under chrome.
  - A top spacer `<div id="lumina-top-scroll-spacer">` prepended to `<body>`.
  - A bottom spacer `<div id="lumina-bottom-scroll-spacer">` appended to `<body>`.
  - **If `skipHeaderInsets = false`**, additionally walks `position: fixed` / `sticky` elements pinned near the top and shifts them down by `topPx`. Re-runs on a `MutationObserver` + a long-decaying schedule of `setTimeout`s (it has to keep checking because SPAs mutate the header continuously). When the per-site "top padding fix" is on, the EngineView itself is padded so we pass `skipHeaderInsets = true` to avoid restyle loops on already-padded sites.
- **`injectPageZoom(factor)`** sets `document.documentElement.style.zoom`. Empty string when `factor == 1.0f`.

Both scripts are minified to a single line because Gecko's `javascript:` parser doesn't like newlines.

### `AmoAddonInstaller.kt`
Small page-level click bridge for AMO detail pages: clicking "Add to Firefox" navigates to the add-on's `.xpi`, and `.xpi` / AMO `/downloads/latest/` requests are intercepted and forwarded to `AddonController.installFromPageRequest`.

## When to touch this package

- New page-injection behavior → `PageBottomSpacer.kt`. Watch out for the MutationObserver feedback loop (search `IMPLEMENTATION.md` for "Tried and reverted" — there's a cautionary tale).
- New capture timing → `ContentHost.kt`. Capture sites should ideally key on `tabId` not `pageUrl` to avoid re-firing on same-tab navigation.
- New engine-level callback → register on the `Observer` inside `ContentHost`; surface through a callback prop.
- **Do not** introduce a second `engineView.render(session)` call site. It will silently corrupt the snapshot cache. See `IMPLEMENTATION.md`.
