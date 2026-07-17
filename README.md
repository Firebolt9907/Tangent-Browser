# Expedition Browser

An Android web browser with an iOS Safari–style bottom URL/tab pill, powered by Mozilla
GeckoView. Single-window, Jetpack-Compose-only, no chrome at the top of the screen — pages render
edge-to-edge under the status bar.

> **Status:** personal project, released as open source under the [GPL-3.0](LICENSE). It builds and
> runs, but it is not on any app store and the release build is signed with a local debug keystore
> (not distribution-ready). Contributions welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md).
>
> **Naming note:** the app is *Expedition Browser*, but the codebase namespace and internal
> identifiers still use the project's original name, *Lumina* (package
> `com.firebolt9907.luminabrowser`, classes like `LuminaRoot`, `LuminaApplication`). A full code
> rename hasn't happened yet — when you see "Lumina" in source paths or symbols, that's Expedition.

## What it does

**The bottom pill (`SafariBar`)** is the URL field, tab switcher, and action surface in one.
- **Expanded** at rest, **compact** when scrolling, **editing** with a full-width field + keyboard.
- **Horizontal swipe** on the pill switches to the adjacent tab; the live page slides with it.
- **Swipe up** opens the tab overview, which has three selectable layouts (`TabViewMode`): an iOS
  app-switcher **deck**, a constant-scale **pixel** filmstrip, and a classic 2-column **grid**.
- **Past-last-tab swipe** creates a new tab.

**Browsing**
- **Predictive back** — the system back gesture animates the previous-page snapshot in over the
  live engine view.
- **Per-tab snapshots** cached in memory + JPEG on disk, so tab switches stay visually continuous
  while GeckoView rebinds its single surface.
- **Reader mode** — content extraction with a per-site include/strip rules engine.
- **Custom Tabs** — links opened from other apps land in a separate slim surface
  (`CustomTabActivity`), not your main tab set; "Open in Expedition" migrates them to a real tab.
- **Downloads, history, bookmarks** with their own Compose sheets.
- **Passkeys / WebAuthn** via GeckoView's native FIDO2 / Credential Manager integration.
- **Video fullscreen** and **per-site preferences** (page zoom, opt-in status-bar top-padding fix).

**Firefox account & sync** (optional; sign in via FxA)
- **Synced tabs** from your other Firefox devices, plus **Send Tab** receipt via command polling.
- **Passwords** — encrypted login storage (`PlacesLoginsStorage`) with a management sheet.
- **Read-only Zen Spaces** sync (Sync 1.5 crypto, decrypted client-side).

**Extensions**
- Install **Firefox add-ons** from an AMO slug, AMO page URL, direct `.xpi` URL, or the "Add to
  Firefox" button on AMO detail pages.

## Stack

- **Kotlin** 2.2.21, **Jetpack Compose** (BOM 2024.09), **Material 3**, edge-to-edge.
- **AGP** 8.13.2 · **compileSdk / targetSdk** 36 · **minSdk** 26.
- **Browser engine:** GeckoView via [`mozilla-components`](https://github.com/mozilla-mobile/firefox-android)
  147.0.4 (`browser-engine-gecko`, `browser-state`, `feature-session`/`-tabs`/`-customtabs`/
  `-downloads`/`-syncedtabs`, `service-firefox-accounts`, `browser-storage-sync`, …).
- **Coil** for image loading, **BouncyCastle** for the Sync 1.5 crypto path.
- Single Gradle module under `app/`.

## Build & run

```sh
./gradlew :app:installDebug
```

The dev build defaults to a **single-ABI** APK (`arm64-v8a`) to keep the size around 200 MB
(GeckoView is large). Build a multi-ABI APK with:

```sh
./gradlew :app:assembleDebug -PsingleAbi=false
```

Notes:
- Release builds are signed with the local **debug** keystore (`~/.android/debug.keystore`) —
  intentional for local use, not distribution.
- **Firebase / FxA push is optional.** `app/google-services.json` is gitignored; without it the
  build drops the Google Services plugin and sync falls back to its foreground poll (see
  [`AGENTS.md`](AGENTS.md) → *Firefox Sync*). The app builds and syncs fine with no Firebase project.
- JVM unit tests: `./gradlew :app:test`. Instrumented tests need a device/emulator.

## Repo layout

```
app/src/main/java/com/firebolt9907/luminabrowser/
├── LuminaApplication.kt        # Application; owns the components (DI) graph
├── LuminaBrowserActivity.kt    # Shared activity base (lifecycle, delegates, downloads)
├── MainActivity.kt             # The browser — hosts LuminaRoot (singleTask, launcher)
├── CustomTabActivity.kt        # External VIEW/SEND links — hosts CustomTabRoot (own task)
├── IntentUrls.kt               # Shared intent-URL parsing
├── engine/                     # GeckoEngine + BrowserStore DI graph
├── state/                      # Data layer (repos, snapshot cache, view models, prefs)
├── addons/                     # WebExtension install/runtime wrapper
├── permissions/                # Runtime permission plumbing
├── service/                    # Custom Tabs service, keep-alive foreground service
├── sync/                       # Firefox Sync, FxA, encrypted logins; sync/zen = Zen Spaces
└── ui/
    ├── LuminaRoot.kt           # Main composable — all gesture orchestration
    ├── chrome/                 # SafariBar — Pill / Expanded / Compact / Editing / aA sheet
    ├── content/                # ContentHost — the single EngineView + capture pipeline
    ├── grid/                   # Tab overview (deck / pixel / classic grid)
    ├── reader/                 # Reader mode: extractor + view + rules engine
    ├── passwords/ sync/        # Password management + synced-tabs / spaces UI
    ├── bookmarks/ history/ downloads/   # Library sheets
    ├── onboarding/ settings/ video/ addons/  # First-run, settings, fullscreen video, AMO UI
    ├── shape/                  # SquircleShape (iOS-style continuous corners)
    └── theme/                  # Material 3 colors, type, theme
```

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — top-down tour of the code for new contributors.
- [`AGENTS.md`](AGENTS.md) — the load-bearing invariants and gotchas. **Read before changing gesture,
  snapshot, sync, or injection code.**
- [`docs/packages/`](docs/packages/) — one short doc per Kotlin package.
- [`IMPLEMENTATION.md`](IMPLEMENTATION.md) — deep running notes on non-obvious decisions and
  reverted experiments. Trust the source over the notes if they disagree.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for setup, coding conventions, and the review checklist,
and [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) for community expectations. The invariants in
[`AGENTS.md`](AGENTS.md) are not optional style preferences — several guard against subtle snapshot,
gesture, and sync regressions.

## License

[GPL-3.0](LICENSE). Expedition builds on Mozilla GeckoView and `mozilla-components` (MPL-2.0), which
remain under their own licenses. You are free to use, study, modify, and redistribute Expedition; if
you distribute a modified version, it must stay open under the GPL.
```
