# Architecture

A top-down tour of Tangent Browser, written for a contributor who's never seen the code before. Read this once; then use [`docs/packages/`](docs/packages/) for the per-package detail and [`IMPLEMENTATION.md`](IMPLEMENTATION.md) when a particular behavior looks odd in the source.

## One-screen mental model

```
┌───────────────────────────────────────────────────────────────────┐
│  MainActivity (single Activity, edge-to-edge)                     │
│  └── TangentRoot()  — the whole UI                                 │
│       ├── ContentHost   ── one EngineView, rebinds per tab        │
│       ├── TabGridOverlay                                          │
│       ├── snapshot/parallax layers (predictive back, switch)      │
│       └── SafariBar     ── the bottom pill (Expanded / Compact /  │
│                            Editing)                               │
└───────────────────────────────────────────────────────────────────┘
                │                          ▲
                │ user intents             │ state
                ▼                          │
┌───────────────────────────────────────────────────────────────────┐
│  TangentViewModel  (combines flows for the bottom bar)             │
└───────────────────────────────────────────────────────────────────┘
                │                          ▲
                ▼                          │
┌───────────────────────────────────────────────────────────────────┐
│  TabRepository  (GeckoTabRepository is the prod impl)             │
└───────────────────────────────────────────────────────────────────┘
                │                          ▲
                ▼                          │
┌───────────────────────────────────────────────────────────────────┐
│  BrowserStore  (mozilla-components: tabs, selection, content)     │
│  GeckoEngine   (Mozilla GeckoView)                                │
└───────────────────────────────────────────────────────────────────┘
```

Around that core sit four supporting subsystems:

- **`TabSnapshotCache`** — bitmap cache so tab switches and back gestures stay visually continuous while Gecko rebinds.
- **`SitePreferencesStore`** — per-host zoom + top-padding-fix, surfaced via the `aA` sheet.
- **`AddonController`** — thin layer over Gecko's `WebExtensionRuntime`.
- **`PageBottomSpacer`** — JavaScript injected into each page so document content sits inside the status-bar and bottom-bar safe areas.

## Data flow

1. **`TangentApplication.components`** lazily builds **`TangentBrowserComponents`** (`engine/Engine.kt`), which is the DI root. It constructs the `GeckoEngine`, the `BrowserStore`, two use-case bundles (`SessionUseCases`, `TabsUseCases`), the `GeckoTabRepository`, the snapshot cache, the site preferences store, the addon controller, and tab persistence. Tabs are restored from disk (`state/TabPersistence`) on launch; starter tabs are only seeded when there's nothing to restore.

2. **`BrowserStore`** is the mozilla-components source of truth — it holds every tab's state, the current selection, content URL, history, loading flags, etc. Tangent mirrors it through `store.observeManually` into a `StateFlow<BrowserState>` (used by `TangentRoot`) and a flat `StateFlow<List<TangentTab>>` (used by `TangentViewModel`).

3. **`GeckoTabRepository`** is the UI-facing façade. It exposes the `TabRepository` interface (`tabs`, `selectedTabId`, `selectTab`, `selectAdjacent`, `loadUrl`, `addTab`, `removeTab`) plus the Gecko-specific `dismissBlankTabAndReturn()` used by back-gesture handling. It also owns `normalizeUrl()` and `compactHost()`, the two URL utilities every screen uses.

4. **`TangentViewModel`** combines `repo.tabs`, `repo.selectedTabId`, an internal `mode` flow, and an internal `editingText` flow into a single `BarUiState`. It's the only thing the bottom bar reads.

5. **`TangentRoot`** reads `browserState` *directly* from the components graph (not via the ViewModel) because it needs the full `BrowserState`: `selectedTab.content.{url,title,loading,history,canGoBack}`, the engine session, the loading progress, etc. Think of the ViewModel as a small bottom-bar projection, and `TangentRoot` as the rich engine-aware consumer.

6. **`ContentHost`** wraps the **single** `EngineView` in an `AndroidView` and rebinds it to the currently selected `EngineSession`. Only one engine view exists for the whole app — see [`IMPLEMENTATION.md`](IMPLEMENTATION.md) for why. `ContentHost` also runs the snapshot capture pipeline (post-bind, first-contentful-paint, load-complete, pre-navigation, gesture-start, dispose) and translates Gecko scroll/observer callbacks into Compose state.

## Key state objects

| Type | Where | Purpose |
|---|---|---|
| `BrowserState` | `mozilla.components` | Authoritative store: every tab, every URL, content/history. |
| `TangentTab` | `state/` | UI projection — `(id, url, title)`. |
| `BarUiState` + `BarMode` | `state/` | Drives `SafariBar`. `BarMode` is `Expanded / Compact / Editing`. |
| `SitePreferences` | `state/` | `(topPaddingFix, zoomFactor)` keyed by host. |

All of these are immutable data classes; reactivity is `StateFlow` everywhere.

## The UI: `TangentRoot.kt`

This file is ~3,000 lines and holds the gesture orchestration; most rendered surfaces and state
clusters have been extracted into sibling files in `ui/` (see [`docs/packages/ui-root.md`](docs/packages/ui-root.md)).
Don't read it linearly. Mental model:

- **One `Box`** in z-order, bottom to top:
  1. `ContentHost` (the live `EngineView`).
  2. Predictive-back snapshot layers (previous-page parallax + outgoing current-page).
  3. Horizontal tab-switch overlay layers (adjacent tab + moving current page).
  4. Tab-switch commit overlay (post-release spring slide).
  5. `TabGridOverlay` (renders every tab at its true carousel position, lerped toward grid cells).
  6. Sticky "incoming snapshot" cover used to bridge the moment of session rebind.
  7. `SafariBar` with previous-tab ghost, active pill, next-tab ghost.

- **Two coordinate spaces:**
  - `offsetX` — live finger position during horizontal tab swipes.
  - `gridDragProgress` (or `gridProgressState` during animation) — 0→1 grid-open progress for the upward swipe.
  - These can be active **simultaneously** (the single `BarPill` pointer-input dispatches a 2D `Offset` delta). On release, whichever crossed its commit threshold wins.

- **Multiple `BackHandler`s, gated by state:**
  - When the grid is open → close grid.
  - Else when `canGoBack` → predictive-back / `goBack`.
  - Else when URL is `about:blank` → `dismissBlankTabAndReturn()`.

Everything else in `TangentRoot` is animation plumbing for those three things. [`IMPLEMENTATION.md`](IMPLEMENTATION.md) covers the non-obvious decisions (single engine view, snapshot capture timing, the dual-offset commit slide, the velocity-keyed damping helper, the corner-radius interpolation).

## The capture pipeline

Tab switching and predictive back both rely on having a recent bitmap for every tab. `ContentHost` captures at six different moments:

1. **Delayed post-bind** (~600 ms after a session is rendered, keyed on `tabId`) — catches already-loaded fast-painting pages.
2. **First contentful paint** — early warm-up.
3. **Load complete** (`onLoadingStateChange(loading=false)`).
4. **Outgoing-page capture** on `onLoadRequest` / `onLocationChange` — warms the page being left.
5. **Gesture-start** (predictive back or tab swipe) — gesture-local snapshot at the current scroll position.
6. **`onDispose`** when the session is about to unbind — best-effort.

Captures go through `EngineView.captureThumbnail` (not `EngineSession.captureThumbnail` — that doesn't exist), then into `TabSnapshotCache` keyed by URL (or tab id for URL-less pages). The cache exposes a `version: StateFlow<Int>` so a freshly arrived bitmap can show up mid-drag.

## The single rebind point

`AndroidView`'s `update` lambda inside `ContentHost` does **not** call `engineView.render(session)`. The session is rebound in one `DisposableEffect(engineView, engineSession, tabId)` block. The `onDispose` runs the leaving-tab capture *before* rebind, so the bitmap stored for the outgoing tab is its actual final frame and not a transitional frame of the incoming tab. This is the single most load-bearing constraint in the code — see "Tried and reverted" in [`IMPLEMENTATION.md`](IMPLEMENTATION.md).

## Gesture choreography

| Gesture | Owned by | Behavior |
|---|---|---|
| Horizontal swipe on pill | `BarPill` pointer-input → `TangentRoot` | `offsetX` tracks finger; commit = `viewModel.onSwitchTab(direction)`; cancel = spring back. |
| Vertical swipe up on pill | same | `gridDragProgress` accumulates; commit at ≥50% travel or ≥1000 px/s. |
| Past-last-tab swipe | same | Adjacent-pane area reveals a centered `+` icon; commit creates `about:blank`. |
| Predictive back | system `PredictiveBackHandler` | Previous-page bitmap parallax + current-page slide-out. |
| Page scroll | `ContentHost` collects `EngineView.verticalScrollDelta` | Drives `BarExpanded → BarCompact` hybrid preview/snap collapse. |

`BarPill` uses a single `awaitEachGesture { … }` block that does **not** axis-lock — every move emits a 2D `Offset`. Pre-slop pointer events are not consumed, so child `IconButton`s and the URL `Row.clickable` keep firing for taps.

## Glossary

- **Pill** — the rounded-rectangle SafariBar surface. There's an "active" pill that's interactive, plus optional "ghost" pills on either side that track adjacent tabs during a swipe.
- **Squircle** — the iOS-style continuous-corner shape used to clip switch/grid panes. Implemented in `ui/shape/SquircleShape.kt` as a sampled superellipse (n=5).
- **Sticky incoming snapshot** — the full-screen bitmap covering the engine view for ~700 ms after a tab switch commits, hiding the blank frame while GeckoView rebinds.
- **Top chrome inset** — `WindowInsets.statusBars` height in px. Used both for the squircle corner radius (`0.8 ×`) and as the CSS top spacer height injected into pages.
- **AMO** — addons.mozilla.org. Add-on install inputs are normalized to AMO `/firefox/downloads/latest/{slug}/latest.xpi` URLs.
- **Compact host** — `Uri.host` with `www.` stripped; the short label shown in the compact pill.

## Where things live, one-liner edition

- **Want to change the URL bar look?** `ui/chrome/BarExpanded.kt` (also covers compact via `collapseProgress=1`).
- **Want to change how a tab switch animates?** `ui/TangentRoot.kt`, the `dragCommit` / `dragCancel` blocks and `dampingForVelocity`.
- **Want to change the grid?** `ui/grid/Grid.kt` (render) + `ui/TangentRoot.kt` (gesture).
- **Want to change page-edge spacers?** `ui/content/PageBottomSpacer.kt`.
- **Want to add a new tab repository operation?** `state/TabRepository.kt` interface, then `GeckoTabRepository.kt` + `MockTabRepository.kt`.
- **Want to add a new per-site preference?** `state/SitePreferencesStore.kt` + `ui/chrome/AaSheet.kt`.
- **Want to change add-on install behavior?** `addons/AddonController.kt`.
