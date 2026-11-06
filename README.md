# Tangent Browser

A gesture-driven Android browser with a Safari-style bottom bar, Firefox add-ons,
and Zen Spaces sync. Powered by Mozilla GeckoView.

<p align="center">
  <img src="https://github.com/user-attachments/assets/8a3ed115-ceb1-44d1-8914-573bc61c5264" alt="Tangent Browser screenshot 1" width="230">
  <img src="https://github.com/user-attachments/assets/bb4f1676-a3b8-4150-9e3c-ecd160086e0e" alt="Tangent Browser screenshot 2" width="230">
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/2442c31c-9373-41d1-9635-43c285009cd1" alt="Tangent Browser screenshot 3" width="230">
  <img src="https://github.com/user-attachments/assets/05bc319e-e658-41be-80ef-19cc3c5002ac" alt="Tangent Browser screenshot 4" width="230">
</p>

## Installation

Download the `arm64-v8a.apk` from [GitHub Releases](https://github.com/Firebolt9907/Tangent-Browser/releases)
and open it to install. Requires **Android 8.0+** and an **arm64** device.

For automatic update checks, add Tangent to [Obtainium](https://github.com/ImranR98/Obtainium)
using the button below. Enable **Include prereleases** for beta updates.

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.firebolt9907.tangentbrowser%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FFirebolt9907%2FTangent-Browser%22%2C%22author%22%3A%22Firebolt9907%22%2C%22name%22%3A%22Tangent%20Browser%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%7D%22%2C%22overrideSource%22%3A%22GitHub%22%7D)

## Features

- **Bottom-bar gestures** — swipe sideways to switch tabs, up to open the overview,
  or past the last tab to create one. The bar shrinks as you scroll.
- **Three tab layouts** — a stacked deck, horizontal filmstrip, or two-column grid.
- **Spaces** — keep separate sets of tabs and swipe within the active Space.
- **Zen integration** — sync spaces, pinned tabs, Essentials, and tabs in folders or
  splits. Phone tabs appear in a dedicated Space on desktop.
- **Firefox add-ons and Sync** — install from AMO or an XPI, and sign in to access
  synced tabs and passwords.
- **Reader mode and element hiding** — read without clutter, or hide individual
  page elements across visits through the aA menu.
- **Passkeys and security keys** — Android credential providers and USB/NFC FIDO2 keys.
- **Continue on** — hand a page to another device's browser on Android 17+.

Also includes bookmarks, history, downloads, fullscreen video, and per-site page zoom.

## The technical stuff

Kotlin, Jetpack Compose, and Material 3, with GeckoView through Mozilla Android
Components. Each foreground activity uses one live engine view; cached page snapshots
keep tab transitions and predictive back visually continuous. External links open in a
separate Custom Tab until you choose **Open in Tangent**.

Firefox accounts and encrypted sync use Mozilla components, with Zen Spaces layered
on top. Enable tab syncing in Zen to use the integration. Send Tab receipt uses polling.

### Build and test

Use JDK 21 and Android SDK 37. The default APK targets arm64;
add `-PsingleAbi=false` to build all supported ABIs.

```sh
./gradlew :app:installDebug
./gradlew :app:test
```

Device tests use the isolated `uiTest` app: `./gradlew :app:connectedUiTestAndroidTest`.
Firebase is optional. Local builds use debug signing unless a release keystore is supplied.

### Further reading

- [Architecture](ARCHITECTURE.md) · [Package guide](docs/packages/) · [Implementation notes](IMPLEMENTATION.md)
- [Contributing](CONTRIBUTING.md) · [Contributor invariants](AGENTS.md) · [Code of conduct](CODE_OF_CONDUCT.md)
- [Signing and releases](docs/OBTAINIUM.md)

<details>
<summary>Backup and device transfer</summary>

Android backup includes preferences, site appearance, and hidden-element rules.
Local tabs, history, bookmarks, sessions, credentials, form memory, and approvals
are excluded. Firefox Sync handles supported data separately; sign in again on
a new device.

</details>

## License

[GPL-3.0](LICENSE). Mozilla GeckoView and Android Components retain their [MPL-2.0](https://www.mozilla.org/MPL/2.0/) licenses.
