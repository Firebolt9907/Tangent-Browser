# Lumina Browser — Claude Code Guide

Architecture context and non-obvious invariants that agents MUST respect to avoid regressions.
For full rationale see `IMPLEMENTATION.md`; this file captures the rules an agent is most likely
to violate.

## Project layout

```
app/src/main/java/com/firebolt9907/luminabrowser/
  engine/         # GeckoEngine + BrowserStore DI graph
  state/          # Data layer (repos, cache, view models)
  addons/         # WebExtension install/runtime wrapper
  sync/           # Firefox Sync + PlacesLoginsStorage (encrypted password store)
  ui/
    chrome/       # SafariBar, BarPill, BarExpanded, BarCompact, BarEditing
    content/      # ContentHost (the single EngineView), page JS injection
    grid/         # Tab grid overlay
    shape/        # SquircleShape
    addons/       # AddonsSheet UI
    theme/        # Material 3 colors/type
    passwords/    # PasswordsSheet — password management UI
    sync/         # SyncedTabsSheet — tab sync UI
  LuminaRoot.kt   # ~2000-line main composable — all gesture orchestration lives here
```

## Critical invariants — do not violate

### 1. Single EngineView / single rebind site

There is **exactly one** `EngineView` for the entire app.
`EngineSession.render()` rebinds it when the selected tab changes.

**The ONLY place that calls `engineView.render(session)` is the `DisposableEffect(engineView,
engineSession, tabId)` block in `ContentHost.kt`.**

The `AndroidView { update = … }` lambda must NOT call `render()`.
If both call it, the applier rebinds before `onDispose` runs, so the leaving-tab
`captureThumbnail` samples the new session and stores it under the wrong cache key — every tab
switch will overwrite the leaving tab's snapshot with the incoming tab.

### 2. BarPill drag — `startDragImmediately = false`

`BarPill`'s `draggable` modifier uses `startDragImmediately = false`.
Setting it to `true` consumes the `ACTION_DOWN` event into the drag stream before child pointer
input fires, which kills every `IconButton` (aA, Extensions, Reload) and the URL tap.
The cost is one touch-slop of travel before a swipe registers, which is imperceptible.

### 3. `pillInteractive` vs `tabSwitchingEnabled` — two separate flags

`tabSwitchingEnabled` flips `false` the moment `gridGestureActive` is set.
It must NOT gate the `BarPill`'s `pointerInput` — doing so tears down the gesture listener
mid-flight, breaking grid-open drags. Instead:

- `pillInteractive = !barCollapseCompact && !gridOpen` — controls the pill's drag listener
- `tabSwitchingEnabled` — controls the horizontal tab-switch clamp logic inside the listener
- `controlsEnabled = !gridEngaged` — disables URL field / buttons during grid gestures

### 4. Grid progress — two separate state variables

- `gridProgressState: Animatable<Float>` — only for spring animations (open / close / cancel).
  **Never call `snapTo` on the drag hot-path.**
- `gridDragProgress: Float` (`mutableFloatStateOf`) — written directly on every drag delta.

Render reads `if (gridGestureActive) gridDragProgress else gridProgressState.value`.
Mixing these causes either frame-skip or stale visual position on finger-up.

### 5. Horizontal-drag overlay vs grid overlay

The horizontal-drag overlay block is gated on `!gridEngaged`.
The grid takes over rendering when `gridEngaged` is true.
Never render both simultaneously.

### 6. Snapshot capture — `EngineView.captureThumbnail`, not `EngineSession`

`EngineSession.captureThumbnail` does not exist in this version of mozilla-components.
All snapshot captures go through `EngineView.captureThumbnail`.

### 7. Sticky cover clearing

Do NOT clear the sticky incoming snapshot just because `selectedContent.url` changes to the
target URL. Android Components can update URL state before Gecko has repainted the surface.
Clear it on a matching paint callback, or after target URL is reached + loading is false + the
fallback delay expires (currently 700 ms). There is also a hard stale-cover timeout.

### 8. Snapshot cache key prefix

The disk cache uses a `v2:` prefix: `"v2:${stableUrl ?: "tab:$id"}"`.
Do not change this prefix without also clearing or migrating old cache files — old entries with
no prefix will silently shadow the new ones via SHA-256 filename collision (they won't, but the
migration logic is absent, so old entries become orphans that never get pruned correctly).

### 9. URL normalization lives in `GeckoTabRepository.kt`

`normalizeUrl(input: String)` and `compactHost(url: String)` are `internal` functions in
`state/GeckoTabRepository.kt`. Every entry point that accepts user-typed input (loadUrl, addTab,
seedIfEmpty) goes through `normalizeUrl`. Do not add a second normalization path elsewhere.

### 10. Scroll-delta batching in `ContentHost`

Gecko scroll callbacks must NOT trigger Compose work directly per-delta.
`ContentHost` accumulates deltas and applies them once per frame via `withFrameNanos`.
Adding direct `barCollapsePx` mutations outside this path can cause unbounded coroutine launches.

### 11. CSS/JS spacer injection guard

`ContentHost` / `PageBottomSpacer` inject a viewport spacer JavaScript on FCP and load-complete.
`suppressJavascriptLoadCallback` prevents the `javascript:` URL itself from re-triggering
injection. If you add a new JS injection site, you must check/set this guard first.

### 12. Compact bar — tap to expand, no horizontal swipe

When the bar is in compact mode (`barCollapseCompact = true`), `BarPill.interactive` becomes
`false` after the bar crosses halfway-to-compact. Compact mode is tap-to-expand only — horizontal
tab switching is intentionally disabled there to prevent accidental tab changes on a narrow pill.

## Common mistakes to avoid

| Mistake | Why it breaks |
|---|---|
| Calling `render()` from `AndroidView.update` | Races with `DisposableEffect.onDispose`; corrupts snapshot cache |
| `startDragImmediately = true` on `BarPill` | Swallows `ACTION_DOWN`; kills all chrome button taps |
| `gridProgressState.snapTo()` on drag hot path | Launches coroutine per frame; drops frames |
| Clearing sticky cover on URL change | Premature cover removal; user sees blank Gecko surface |
| Using `EngineSession.captureThumbnail` | Compile error — method does not exist |
| Adding a JS injection without `suppressJavascriptLoadCallback` guard | Injection loop; corrupts snapshot captures with transitional frames |
| Gating `BarPill`'s `pointerInput` on `tabSwitchingEnabled` | Tears down gesture listener mid-drag |

## Testing

Unit tests live in `app/src/test/`. Tests that don't touch Android framework can run on the JVM
with `./gradlew :app:test`. Tests in `app/src/androidTest/` require a device/emulator.

`normalizeUrl` and `compactHost` have JVM unit tests in
`app/src/test/java/com/firebolt9907/luminabrowser/UrlNormalizationTest.kt`.

## Build notes

- Default dev build filters to `arm64-v8a` only (~200 MB APK). Pass `-PsingleAbi=false` to build
  all ABIs.
- Release signing uses the local debug keystore intentionally (not for distribution).
- `isMinifyEnabled` and `isShrinkResources` are currently `false` in the release build type.
