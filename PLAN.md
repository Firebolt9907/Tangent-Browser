# TangentBrowser — Build Plan

Context for a fresh agent picking up mid-build. Read this top to bottom before touching code.

## What this is

Android browser app, single Gradle module, Compose UI. Target experience: an iOS 18 Safari-style bottom URL/tab pill, eventually backed by GeckoView via Mozilla Android Components.

Package root: `com.firebolt9907.tangentbrowser`. minSdk 26, compileSdk 36, Compose BOM 2024.09.00, Kotlin 2.0.21.

## What "iOS 18 Safari tab bar" means here

The Safari bottom bar is URL field + tab switcher + action surface in one pill. Distinct behaviors we're building:

1. **Expanded** (rest) — pill ~70% width, full URL/search field, leading aA/format button, trailing reload.
2. **Compact** (while scrolling page down) — shrinks to ~40% width, content collapses to just the domain (e.g., `nytimes.com`); other affordances hide.
3. **Editing** — tap URL → pill expands full width, IME comes up, suggestions list above the bar.
4. **Horizontal swipe on the bar** — switches to adjacent tab. Page content slides with it, not just the bar. Marquee Safari gesture.
5. **Swipe up on the bar** — opens tab grid (Arc-style transition).
6. **Edge-swipe past the last tab** — reveals "new tab" affordance.

There is NO `+` button on the bar. New tabs come from the grid or edge-swipe-past-end.

Explicitly **not** building in v1: tab groups, full aA formatting menu, true frosted-glass material (RenderEffect API 31+; perf caveats), in-bar suggestions list while editing.

## Day 2 — DONE ✅

Gate: app shows mock URL in the bar; horizontal-swiping the bar switches between 3 seeded mock tabs; URL updates accordingly. Tap URL → editing mode (visual only, no real submit). No engine yet.

What's in place:
- `state/TangentTab.kt`, `TabRepository.kt` (interface), `MockTabRepository.kt` seeded with apple.com / nytimes.com / wikipedia.org. `MockTabRepository.instance` singleton accessed by the VM.
- `state/BarUiState.kt` — `BarUiState(displayUrl, tabCount, mode, editingText)` + `BarMode { Expanded, Compact, Editing }`. **State-layer model**, not UI-layer.
- `state/TangentViewModel.kt` — combines repo flows + local mode/editingText into `StateFlow<BarUiState>`. Exposes `onUrlTap`, `onEditingTextChange`, `onEditingSubmit` (currently just exits edit mode), `onEditingCancel`, `onSwitchTab(direction: Int)`.
- `ui/chrome/SafariBar.kt` — `AnimatedContent` keyed by `state.mode`, spring `SizeTransform`. Bottom-centered with 24dp bottom padding.
- `ui/chrome/BarPill.kt` — shared Surface + horizontal-draggable wrapper. Owns drag accumulator (`SWITCH_THRESHOLD_PX = 120f`, `FLING_VELOCITY_PX_PER_S = 800f`). Direction convention: drag right (positive) = previous tab.
- `ui/chrome/BarExpanded.kt`, `BarCompact.kt`, `BarEditing.kt` — three sub-composables. Each `@Preview`d.
- `ui/chrome/TabCountChip.kt` — non-interactive count chip.
- `ui/TangentRoot.kt` — wires everything; uses `remember(viewModel) { BarCallbacks(...) }` for stable references; outer `Box` around SafariBar applies `imePadding()` so the bar lifts above the keyboard cleanly.
- Gradle: `kotlinx-coroutines-android`, `lifecycle-runtime-compose` added.

Known limitations carried into later days (intentional):
- `displayUrl` and the compact label are the same string. Real domain extraction (`Uri.parse(url).host?.removePrefix("www.")`) is Day 3.
- Editing submit just exits edit mode (no `loadUrl` until the engine is wired).
- No content cross-slide on horizontal swipe yet (Day 4, blocked on `capturePixels`).
- No scroll-driven Compact transition yet (Day 4).
- Tab-count chip is non-interactive; eventually it (or a swipe-up gesture) opens the grid (Day 5).
- `MockTabRepository.instance` singleton is fine for now; a `ViewModelProvider.Factory` can replace it once a real `GeckoTabRepository` exists.

## Day 3 — DONE ✅

Goal: swap `MockTabRepository` for a `GeckoTabRepository` backed by Mozilla Android Components; mount `GeckoView`; bar URL reflects `ContentState.url` from `BrowserStore`; submitting Editing actually navigates.

What's in place:
- Mozilla Android Components `147.0.4` wired via `maven.mozilla.org`:
  - `browser-engine-gecko`
  - `browser-state`
  - `concept-engine`
  - `feature-session`
  - `feature-tabs`
- Kotlin upgraded from `2.0.21` to `2.2.21` because current Android Components are compiled with Kotlin 2.2 metadata.
- `TangentApplication` owns app-scoped `TangentBrowserComponents`: `GeckoEngine`, `BrowserStore`, `SessionUseCases`, `TabsUseCases`, and `GeckoTabRepository`.
- `BrowserStore` is created with `EngineMiddleware.create(engine, scope)`, so tab creation/loading gets real `EngineSession`s.
- `GeckoTabRepository` implements `TabRepository` and maps `BrowserState.tabs` to `TangentTab`; selection/add/remove go through `TabsUseCases`; URL loads go through `SessionUseCases.loadUrl`.
- `TangentViewModel` now uses the Gecko-backed repo, submits editing text to the selected session, and computes compact host labels via `Uri.parse(...).host?.removePrefix("www.")`.
- `ContentHost` creates an Android Components `EngineView`, wraps it with `AndroidView`, renders the selected tab's `EngineSession`, and forwards lifecycle events manually.
- App seeds three real tabs (`apple.com`, `nytimes.com`, `wikipedia.org`) and selects Apple first, preserving Day 2 bar-swipe behavior.

Gate: `./gradlew :app:compileDebugKotlin` passes. Device/emulator verification still needed for real Gecko rendering and navigation behavior.

## Day 4 — Scroll-driven Compact + horizontal-swipe content cross-slide (NEXT)

Items in priority order:
1. **Scroll-driven Compact transition.** GeckoView doesn't expose Compose scroll deltas directly — observe `EngineSession.Observer.onScrollChange(scrollX, scrollY)` and feed deltas into the VM. Mode transitions: scrolling down past threshold → Compact, scrolling up or idle → Expanded. Spring-animated width/content swap (already there in `SafariBar`'s `AnimatedContent`).
2. **Horizontal-swipe content cross-slide.** During the bar drag, also translate the GeckoView (or a captured bitmap of the off-tab page) horizontally. Requires the `capturePixels` work that was originally Phase 1 spike — pull it forward. **Load-bearing risk:** if `capturePixels` latency is too high, the swipe will feel laggy on first switch. Mitigation: pre-capture adjacent tabs on selection-change.
3. **`SessionStorage` for tab persistence.**

If item 2 slips: ship Day 4 without content cross-slide (bar-only swipe) and treat cross-slide as Day 5.

Implementation note: the bar-side drag detection already lives in `BarPill.kt`. Adding content cross-slide means hoisting drag accumulator state higher (probably to the VM or a `TangentRoot`-level `remember`) so `ContentHost` can observe it and translate accordingly. Be deliberate about this — it'll touch the clean callback surface that Day 2 just landed.

## Day 5 — Tab grid + swipe-up-from-bar transition

Arc-style shared-element transition from the bar to a full-screen tab grid.

- New `ui/grid/TabGrid.kt` (the empty package already exists).
- Trigger: swipe-up on the bar (add a `Modifier.draggable(Orientation.Vertical)` to `BarPill` or a sibling). Tapping the `TabCountChip` should also open the grid.
- Use Compose's `LookaheadScope` + `SharedTransitionLayout` for the bar-pill → grid-card morph.
- Tab grid lets you select, close, and create tabs. The "new tab" entry points are: explicit `+` in the grid, and edge-swipe-past-end on the bar (item 6 of the Safari behavior list above).

Gate: open grid via swipe-up, switch tabs by tap, close tabs, create new tab. All persisted via `SessionStorage`.

## Honest schedule

Original spec was 4 days; revised to 6–7 days for true Safari parity through Day 5. Horizontal-swipe content cross-slide is the single hardest interaction in the whole app — it's where Safari's polish actually lives. Budget accordingly.

## Things to NOT do unless asked

- Do NOT add a `+` button to the main bar. New tabs come from the grid / edge-swipe.
- Do NOT add `Modifier.blur` for frosted-glass; it doesn't sample what's underneath efficiently. Save real translucent material for after Day 5 as a stretch goal.
- Do NOT add tab groups, page-zoom UI, reader mode, or in-bar suggestions list — out of v1 scope.
- Do NOT introduce Hilt/Koin to inject the repo. The current manual singleton + ctor-default pattern is sufficient until the codebase has more than one VM/repo to wire.

## Verifying changes

`./gradlew :app:compileDebugKotlin` is the cheap check. For UI changes, install on a device/emulator and verify:
- Bar position when keyboard opens (must sit just above keyboard, not behind, not at top of screen — this regressed twice during Day 2; the fix is `imePadding()` on the wrapper Box, not on `SafariBar` itself).
- Horizontal swipe across the bar switches the displayed URL.
- Tab-count chip shows the current open tab count.
