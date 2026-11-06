# Tangent Browser

An Android browser that brings Safari-style gestures and a bottom address pill to
Mozilla's Gecko engine. Swipe between pages, keep tabs in Spaces, and pick up your
Zen Browser tabs on your phone.

Tangent is a personal open-source project under the [GPL-3.0](LICENSE). It is available
through GitHub Releases and is not on an app store.

---

## Installation

Tangent isn't on any app store. Pushes to `codex/beta` publish beta releases; version tags publish stable releases. Both attach a signed `arm64-v8a` APK to
[GitHub Releases](https://github.com/Firebolt9907/Tangent-Browser/releases), which
[Obtainium](https://github.com/ImranR98/Obtainium) can install and keep updated.

With Obtainium already installed, one tap adds it with the source pre-filled:

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.firebolt9907.tangentbrowser%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FFirebolt9907%2FTangent-Browser%22%2C%22author%22%3A%22Firebolt9907%22%2C%22name%22%3A%22Tangent%20Browser%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D)

### With Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium/releases), then tap the badge above,
   or choose **Add App** and paste `https://github.com/Firebolt9907/Tangent-Browser`.
2. Enable **Include prereleases** for the beta feed.
3. Install Tangent and enable update checks in Obtainium.

### Install the APK directly

1. Open [GitHub Releases](https://github.com/Firebolt9907/Tangent-Browser/releases) and choose
   the release you want, including a prerelease for the latest beta.
2. Download the asset ending in `arm64-v8a.apk`.
3. Open it on your phone and allow installation from that source when Android asks.

**Requirements:** Android 8.0 or newer and an arm64 device for the published APK.
Updates must use the same signing key as the installed app; a local debug build and
a GitHub release may not be compatible as an in-place update.

Signing and release setup for maintainers: [`docs/OBTAINIUM.md`](docs/OBTAINIUM.md).


---

## What makes it different?

If you're coming from Chrome or Firefox on Android, these are the reasons to try Tangent:

| Feature | What you get |
| --- | --- |
| **Safari-style bottom pill** | The address field, tab switching, and actions share one control at the bottom. It shrinks as you scroll, leaving the page visible edge-to-edge. |
| **Three tab overviews** | Choose a stacked app-switcher deck, a horizontal filmstrip, or a classic two-column grid. Swipe up from the pill to open it. |
| **Spaces on your phone** | Keep separate sets of tabs; both the overview and sideways tab switching follow your active Space. |
| **Zen Spaces sync** | Bring over Zen spaces, pinned tabs, Essentials, and tabs inside folders or splits through Mozilla Sync. Phone tabs appear in a dedicated **Tangent · device** space on desktop. |
| **Hide parts of a site** | Choose **Hide an element** from the aA menu, select the unwanted element, and keep it hidden on later visits. Use **Restore hidden elements** to undo it. |
| **Reader mode with site rules** | Read extracted content with per-site rules for what to include or strip. |

Tangent uses GeckoView, the same engine family as Firefox. Firefox add-on support and
Firefox Sync are shared strengths with Firefox on Android; Tangent combines them with
its own interface and Zen integration. Chrome on Android does not support Firefox add-ons.

---

## Usage

| Action | Result |
| --- | --- |
| Tap the address pill | Edit the URL or enter a search. |
| Swipe sideways on the expanded pill | Switch to an adjacent tab in the active Space; swiping past the last tab creates a new one. |
| Swipe up from the expanded pill | Open the tab overview. |
| Tap the compact pill | Expand it before switching tabs or opening the overview. |
| Change the overview layout | Switch between deck, filmstrip, and grid. |
| Open a link from another app | Browse in a separate Custom Tab; **Open in Tangent** moves it into your main tabs. |

### Firefox and Zen sync

Sign in with a Firefox account to access synced tabs and encrypted password storage.
Send Tab receipt uses polling, so delivery may wait until Tangent is opened or refreshed.

For Zen Spaces, enable normal tab syncing in Zen (integration built for Zen 1.22.1b).
When spaces are detected, Tangent shows a Spaces view in place of the device list.
Sync is optional; browsing does not require an account.

### Add-ons and everyday browsing

Install Firefox add-ons using an AMO slug, an AMO page URL, a direct `.xpi` URL, or
the **Add to Firefox** button on AMO detail pages. Compatibility depends on the add-on.

Tangent also includes downloads, history, bookmarks, fullscreen video, page zoom,
and per-site appearance preferences. Passkeys work through Android Credential Manager
(subject to the provider's trusted-browser policy), a local authenticator, or USB/NFC
FIDO2 security keys. **Continue on** can hand a web URL to another device's browser on
Android 17 or newer; private tabs and local/non-web pages are excluded.

### Backup and device transfer

Android backup preserves global preferences, site appearance preferences, and hidden-element
rules. It does **not** include local tabs, history, bookmarks, site sessions, credentials,
remembered form values, or permission/autofill approvals. Firefox Sync is a separate
opt-in route for supported data; sign in again on a new device.

---

## The technical stuff

The interface is built in Jetpack Compose. Each foreground activity uses a single live
Gecko engine view. Per-tab snapshots in memory and on disk cover the surface while it
rebinds during tab switches, and provide the previous-page preview for predictive back.

External links use a separate `CustomTabActivity` and custom-tab session, keeping them out
of the main tab set until the user chooses **Open in Tangent**. Firefox accounts, encrypted
logins, and sync use Mozilla components; Zen Spaces adds a layer over Mozilla Sync.

### Stack

- **Kotlin** 2.4.10, **Jetpack Compose** (BOM 2026.09.00), **Material 3**, edge-to-edge.
- **AGP** 9.1.1 · **compileSdk** 37 · **targetSdk** 36 · **minSdk** 26.
- **Browser engine:** GeckoView via [`mozilla-components`](https://github.com/mozilla-mobile/firefox-android)
  156.0.1 (`browser-engine-gecko`, `browser-state`, `feature-session`/`-tabs`/`-customtabs`/
  `-downloads`/`-syncedtabs`, `service-firefox-accounts`, `browser-storage-sync`, …).
- **Coil** for image loading, **BouncyCastle** for the Sync 1.5 crypto path.
- Single Gradle module under `app/`.


### Build & run

```sh
./gradlew :app:installDebug
```

The dev build defaults to a **single-ABI** APK (`arm64-v8a`) to keep the size around 200 MB
(GeckoView is large). Build a multi-ABI APK with:

```sh
./gradlew :app:assembleDebug -PsingleAbi=false
```

Notes:
- Release builds are signed with the local **debug** keystore (`~/.android/debug.keystore`) unless
  an upload keystore is supplied (`TANGENT_KEYSTORE_FILE` — see
  [`docs/OBTAINIUM.md`](docs/OBTAINIUM.md)); the debug fallback is for local use, not distribution.
- **Firebase / FxA push is optional.** `app/google-services.json` is gitignored; without it the
  build drops the Google Services plugin and sync falls back to its foreground poll (see
  [`AGENTS.md`](AGENTS.md) → *Firefox Sync*). The app builds and syncs fine with no Firebase project.
- JVM unit tests: `./gradlew :app:test`. Instrumented tests need a device/emulator.

Compose instrumentation uses a separate `uiTest` build with application ID
`com.firebolt9907.tangentbrowser.uitest` and a minimal test host. It does not initialize Firebase
or the browser's data/services. Run it with `./gradlew :app:connectedUiTestAndroidTest` (or
`:app:connectedAndroidTest`). Gradle may uninstall the test host during cleanup; the regular
browser package and its data are separate. `connectedDebugAndroidTest` is intentionally unavailable.
Run only one instrumentation invocation per device/test package at a time: another runner's
installation or cleanup can terminate an active suite.

### Repo layout

```
app/src/main/java/com/firebolt9907/tangentbrowser/
├── TangentApplication.kt        # Application; owns the components (DI) graph
├── TangentBrowserActivity.kt    # Shared activity base (lifecycle, delegates, downloads)
├── MainActivity.kt             # The browser — hosts TangentRoot (singleTask, launcher)
├── CustomTabActivity.kt        # External VIEW/SEND links — hosts CustomTabRoot (own task)
├── IntentUrls.kt               # Shared intent-URL parsing
├── engine/                     # GeckoEngine + BrowserStore DI graph
├── state/                      # Data layer (repos, snapshot cache, view models, prefs)
├── addons/                     # WebExtension install/runtime wrapper
├── permissions/                # Runtime permission plumbing
├── service/                    # Custom Tabs service, keep-alive foreground service
├── sync/                       # Firefox Sync, FxA, encrypted logins; sync/zen = Zen Spaces
└── ui/
    ├── TangentRoot.kt           # Main composable — all gesture orchestration
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

[GPL-3.0](LICENSE). Tangent builds on Mozilla GeckoView and `mozilla-components` (MPL-2.0), which
remain under their own licenses. You are free to use, study, modify, and redistribute Tangent; if
you distribute a modified version, it must stay open under the GPL.
