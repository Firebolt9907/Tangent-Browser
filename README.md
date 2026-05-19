# Lumina Browser

An Android browser with an iOS Safari–style bottom URL/tab pill, powered by Mozilla GeckoView. Single-window, Compose-only, no chrome at the top of the screen — pages render edge-to-edge under the status bar.

This README is the entry point. For deeper material:

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — top-down tour of the code, intended for new contributors.
- [`IMPLEMENTATION.md`](IMPLEMENTATION.md) — running notes on non-obvious design decisions, gotchas, and "tried and reverted" experiments. Trust the source over the notes if they disagree.
- [`PLAN.md`](PLAN.md) — historical day-by-day build plan. Useful for context, not a roadmap.
- [`docs/packages/`](docs/packages/) — one short doc per Kotlin package describing what lives there.

## What it does

- Bottom **SafariBar** pill that's URL field, tab switcher, and action surface in one.
  - **Expanded** at rest. **Compact** when scrolling. **Editing** with full-width text field + keyboard.
  - **Horizontal swipe** on the pill switches to the adjacent tab; the page slides with it.
  - **Swipe up** on the pill opens a 2D **tab grid**.
  - **Past-last-tab swipe** reveals a new-tab affordance and creates an `about:blank` tab on release.
- **Predictive back** uses the system gesture; the previous-page snapshot animates in over the live engine view.
- **Snapshots** of every tab are cached (16 in memory, JPEG on disk) so tab switches stay visually continuous while GeckoView rebinds.
- **Firefox add-ons** can be installed from an AMO slug, AMO page URL, or direct `.xpi` URL — including via the "Add to Firefox" button on AMO detail pages.
- **Per-site preferences**: page zoom (50–200%) and an opt-in "top padding fix" for sites whose fixed headers don't respect the status-bar inset.
- **Share / view intents** open in a new tab.

Not built (intentionally): tab/profile persistence, history, bookmarks, downloads, find-in-page, reader mode, multi-window, browser/page-action toolbar surfaces for extensions.

## Stack

- Kotlin 2.2, Compose, Material 3, edge-to-edge.
- Min SDK 26, target/compile SDK 36.
- Browser engine: **GeckoView** via `mozilla-components` 147.0.4 (`browser-engine-gecko`, `browser-state`, `feature-session`, `feature-tabs`).
- Single Gradle module under `app/`.

## Build & run

```sh
./gradlew :app:installDebug
```

The dev build defaults to a **single-ABI** APK (`arm64-v8a`) to keep size around 200 MB. To build a multi-ABI APK:

```sh
./gradlew :app:assembleDebug -PsingleAbi=false
```

Release builds are signed with the local debug keystore (`~/.android/debug.keystore`) — intentional for now; not distribution-ready. ProGuard + resource shrinking are on for release.

There are no tests of interest yet (just the auto-generated `ExampleUnitTest` / `ExampleInstrumentedTest`).

## Repo layout

```
app/src/main/java/com/firebolt9907/luminabrowser/
├── LuminaApplication.kt        # Application; owns the components graph
├── MainActivity.kt             # Single activity; edge-to-edge; share/view intents
├── engine/
│   └── Engine.kt               # LuminaBrowserComponents — DI root
├── state/
│   ├── BarUiState.kt           # Bottom-bar state + BarMode enum
│   ├── LuminaTab.kt            # UI-facing tab model
│   ├── LuminaViewModel.kt      # Bridges TabRepository → SafariBar state
│   ├── TabRepository.kt        # Interface
│   ├── GeckoTabRepository.kt   # GeckoView-backed impl + URL normalization
│   ├── MockTabRepository.kt    # Compose preview backing
│   ├── SitePreferencesStore.kt # Per-host zoom + top-padding-fix
│   └── TabSnapshotCache.kt     # In-memory LRU + JPEG-on-disk
├── addons/
│   └── AddonController.kt      # Thin wrapper over Gecko WebExtensionRuntime
└── ui/
    ├── LuminaRoot.kt           # The whole UI (~1700 lines; see IMPLEMENTATION.md)
    ├── chrome/                 # SafariBar — Expanded / Compact / Editing / Pill
    ├── content/                # ContentHost — single EngineView + capture pipeline
    ├── grid/                   # 2D tab grid overlay
    ├── shape/                  # SquircleShape (iOS-style continuous corners)
    ├── addons/                 # Bottom sheet for AMO install/manage
    └── theme/                  # Material 3 colors, type, theme
```

## Where to start reading

If you're new:

1. Skim this README.
2. Read [`ARCHITECTURE.md`](ARCHITECTURE.md) — gives you the data flow and the names you'll see in every file.
3. Open `engine/Engine.kt` — it's the DI graph in 50 lines.
4. Open `state/LuminaViewModel.kt` to see how UI state is composed.
5. Then dive into whichever package matches what you're changing (see [`docs/packages/`](docs/packages/)).
6. When something in `ui/LuminaRoot.kt` looks weird, search [`IMPLEMENTATION.md`](IMPLEMENTATION.md) first — there's almost certainly a note explaining why.

## Known limitations

- Tab state is in-memory only; the three seeded demo tabs (Apple, NYT, Wikipedia) reseed on every cold start.
- No browser-action / page-action UI for extensions yet.
- No status-bar blur — GeckoView renders to a `SurfaceView`, which Compose can't blur. The status-bar area shows the page directly under the system bar icons.
- Native debug symbols are not stripped from release builds (adds ~80 MB).
