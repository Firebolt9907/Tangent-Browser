# Add About:Config Flags to Settings

> **STATUS: IMPLEMENTED** (compiles). This plan was implemented directly, with extra hardcoded flags
> added beyond the original draft. As-built:
> - **Hardcoded flags (Engine.kt YAML):** TLS 1.2 min, HW video decode, WebRender, cryptomining +
>   fingerprinting + **socialtracking** blocking, **network-state partitioning**, referer
>   defaultPolicy 2 + **XOriginTrimmingPolicy 2**, **IDN punycode**, **pdf.js scripting off**,
>   **no remote download reputation**, **AVIF**, **mixed-content upgrade**.
> - **User toggles (restart to apply):** Block all autoplay, Web notifications, Service workers,
>   Hide local IP in WebRTC, **Allow analytics beacons** (`beacon.enabled`).
> - **Not done (own investigation):** EME/Widevine DRM — needs GeckoRuntime GMP work, not just a pref.

## Goal
Add beneficial GeckoView `about:config` flags — some hardcoded as safe defaults, others exposed as user-facing toggles in the Advanced settings page.

## ⚠️ How Gecko prefs actually get set in this app (read first)

This app has **no runtime API for arbitrary `about:config` prefs**. `engine.settings` only exposes
*typed, named* properties (e.g. `engine.settings.crliteChannel`), and `mozilla.components`' `Settings`
class has **no** generic `setAttribute(String, String)` — do not call it, it will not compile.

The **only** mechanism for arbitrary prefs is the YAML file written by `writeGeckoConfig()` in
`Engine.kt`. Gecko reads that file **once**, at `GeckoRuntime.create()`. Consequences:

- Every flag we add — hardcoded *or* user-toggled — goes into that YAML.
- `writeGeckoConfig()` is rewritten on every launch and already receives `prefs`, so a toggle can be
  read into the YAML — but the change only takes effect on the **next app launch**. The user-facing
  toggles must therefore say **"Restart required"**.

## Files to modify

1. `app/src/main/java/com/firebolt9907/tangentbrowser/state/GlobalPreferencesStore.kt`
2. `app/src/main/java/com/firebolt9907/tangentbrowser/engine/Engine.kt`
3. `app/src/main/java/com/firebolt9907/tangentbrowser/ui/settings/SettingsSheet.kt`

## Step 1: Add prefs to `GlobalPreferencesStore.kt`

Add these 4 properties after the existing `crliteChannel` property (~line 401):

```kotlin
var blockAllAutoplay: Boolean
    get() = prefs.getBoolean("blockAllAutoplay", true)
    set(value) = prefs.edit { putBoolean("blockAllAutoplay", value) }

var webNotificationsEnabled: Boolean
    get() = prefs.getBoolean("webNotificationsEnabled", false)
    set(value) = prefs.edit { putBoolean("webNotificationsEnabled", value) }

var serviceWorkersEnabled: Boolean
    get() = prefs.getBoolean("serviceWorkersEnabled", true)
    set(value) = prefs.edit { putBoolean("serviceWorkersEnabled", value) }

var webRtcIpsHidden: Boolean
    get() = prefs.getBoolean("webRtcIpsHidden", true)
    set(value) = prefs.edit { putBoolean("webRtcIpsHidden", value) }
```

## Step 2: Add hardcoded flags to `Engine.kt` `writeGeckoConfig()`

In `writeGeckoConfig()`, append these flags inside the YAML string, after the existing
`security.webauth.webauthn: false` line (~line 766) and **before** the closing `"""` on line 767:

```yaml
          # --- Security: enforce TLS 1.2 minimum ---
          # Block TLS 1.0/1.1 which have known vulnerabilities. All modern sites support TLS 1.2+.
          # (3 = TLS 1.2.)
          security.tls.version.min: 3
          # --- Performance: ensure hardware video decoding ---
          # HW decode is usually auto-detected on Android but explicit is safer — helps 1080p/4K
          # playback battery life.
          media.hardware-video-decoding.enabled: true
          # --- Compatibility: force GPU WebRender compositing ---
          # Already the default on modern Android; pin it so a driver-quirk fallback can't drop us to
          # software compositing.
          gfx.webrender.all: true
          # --- Privacy: block cryptominers ---
          # Explicit cryptomining-script blocking (complements Enhanced Tracking Protection).
          privacy.trackingprotection.cryptomining.enabled: true
          # --- Privacy: block fingerprinting scripts ---
          # Explicit fingerprinting-script blocking (complements the fingerprinting-protection toggle).
          privacy.trackingprotection.fingerprinting.enabled: true
          # --- Privacy: stricter referrer policy ---
          # 2 = strict-origin-when-cross-origin-style: send only the origin (not the full path/query)
          # to cross-origin resources, full referer only same-origin. Stops leaking full URLs to
          # third parties.
          network.http.referer.defaultPolicy: 2
```

> **Pruned from the original draft (do not add):** `browser.backforward_cache.enabled` (not a real
> Firefox pref — BFCache is already configured here via `browser.sessionhistory.max_total_viewers` +
> `fission.bfcacheInParent`), `layout.css.has-selector.enabled` (`:has()` ships on-by-default; pref is
> gone), and `media.ffmpeg.vp9.enabled` (a desktop-ffmpeg pref, inert on Android GeckoView). They are
> no-ops and were removed.

## Step 3: Fold the 4 user-facing prefs into `writeGeckoConfig()`

**There is no runtime path** (see the warning at the top), so these prefs are written into the same
YAML, read from `prefs`. They apply on the next launch.

### 3a. Compute the pref-derived values

At the **top** of `writeGeckoConfig()`, next to the existing `capacityKb` / `cacheParent` locals
(~line 491), add:

```kotlin
    // User-facing about:config toggles. Written into the YAML below so they apply on next launch
    // (Gecko reads this file once at GeckoRuntime.create()). The settings toggles note "Restart
    // required" for this reason.
    val autoplayDefault = if (prefs.blockAllAutoplay) 5 else 1      // 5 = block all, 1 = block audible
    val blockAutoplayBg = prefs.blockAllAutoplay                    // also block until foregrounded
    val webNotifications = prefs.webNotificationsEnabled
    val serviceWorkers = prefs.serviceWorkersEnabled
    val webRtcHideIp = prefs.webRtcIpsHidden
```

### 3b. Emit them in the YAML

Append this block in the same place as Step 2 (after `security.webauth.webauthn: false`, before the
closing `"""`):

```yaml
          # --- Media: autoplay policy (user toggle "Block all autoplay") ---
          # 5 = block ALL autoplay incl. muted video; 1 = block only audible. Also block autoplay in
          # background tabs until foregrounded when the stricter mode is on. Battery savings.
          media.autoplay.default: $autoplayDefault
          media.block-autoplay-until-in-foreground: $blockAutoplayBg
          # --- Web notifications + Push API (user toggle "Web notifications") ---
          # Off by default for battery; when on, sites may request notification permission and use
          # the Push API for background messages.
          dom.webnotifications.enabled: $webNotifications
          dom.push.enabled: $webNotifications
          dom.push.connection.enabled: $webNotifications
          # --- Service Workers (user toggle "Service workers") ---
          # On by default (PWAs / modern web apps). Off saves battery/privacy.
          dom.serviceWorkers.enabled: $serviceWorkers
          # --- WebRTC local-IP hiding (user toggle "Hide local IP in WebRTC") ---
          # Prevents leaking local network IPs in ICE candidates. On by default for privacy; off if a
          # WebRTC call needs local-network access.
          media.peerconnection.ice.no_host: $webRtcHideIp
          media.peerconnection.ice.default_address_only: $webRtcHideIp
```

> Booleans interpolate as `true`/`false` and integers as their digits, which is valid YAML for
> Gecko's pref file — matching how the existing `browser.cache.disk.smart_size.enabled: false` and
> numeric `capacityKb` lines are written.

## Step 4: Hoist state in `SettingsSheet.kt` `SettingsSheet()`

Add these `remember` blocks after the existing `crliteChannel` state (~line 117):

```kotlin
    var blockAllAutoplay by remember { mutableStateOf(globalPrefsStore.blockAllAutoplay) }
    var webNotificationsEnabled by remember { mutableStateOf(globalPrefsStore.webNotificationsEnabled) }
    var serviceWorkersEnabled by remember { mutableStateOf(globalPrefsStore.serviceWorkersEnabled) }
    var webRtcIpsHidden by remember { mutableStateOf(globalPrefsStore.webRtcIpsHidden) }
```

Pass them to `AdvancedSettingsPage` — add these parameters to the call (~line 135):

```kotlin
                blockAllAutoplay = blockAllAutoplay,
                onBlockAllAutoplayChange = { blockAllAutoplay = it },
                webNotificationsEnabled = webNotificationsEnabled,
                onWebNotificationsEnabledChange = { webNotificationsEnabled = it },
                serviceWorkersEnabled = serviceWorkersEnabled,
                onServiceWorkersEnabledChange = { serviceWorkersEnabled = it },
                webRtcIpsHidden = webRtcIpsHidden,
                onWebRtcIpsHiddenChange = { webRtcIpsHidden = it },
```

## Step 5: Add toggles to `AdvancedSettingsPage` in `SettingsSheet.kt`

Add parameters to the `AdvancedSettingsPage` function signature (after existing params, ~line 877):

```kotlin
    blockAllAutoplay: Boolean,
    onBlockAllAutoplayChange: (Boolean) -> Unit,
    webNotificationsEnabled: Boolean,
    onWebNotificationsEnabledChange: (Boolean) -> Unit,
    serviceWorkersEnabled: Boolean,
    onServiceWorkersEnabledChange: (Boolean) -> Unit,
    webRtcIpsHidden: Boolean,
    onWebRtcIpsHiddenChange: (Boolean) -> Unit,
```

These prefs only take effect on the next launch (Step 3), so every subtitle ends with
**"Restart to apply."**

Add toggles in the **Content** section (after the "Auto-archive tabs" picker, ~line 1054):

```kotlin
        SheetRowDivider()
        SettingsToggle(
            title = "Block all autoplay",
            subtitle = "Prevent all media from auto-playing, including muted video. Saves battery. Restart to apply.",
            checked = blockAllAutoplay,
            onCheckedChange = {
                onBlockAllAutoplayChange(it)
                globalPrefsStore.blockAllAutoplay = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Web notifications",
            subtitle = "Allow sites to request notification permission. Disabled by default to save battery. Restart to apply.",
            checked = webNotificationsEnabled,
            onCheckedChange = {
                onWebNotificationsEnabledChange(it)
                globalPrefsStore.webNotificationsEnabled = it
                onSettingChanged()
            },
        )
        SheetRowDivider()
        SettingsToggle(
            title = "Service workers",
            subtitle = "Enable service workers for PWAs and modern web apps. Disable to save battery. Restart to apply.",
            checked = serviceWorkersEnabled,
            onCheckedChange = {
                onServiceWorkersEnabledChange(it)
                globalPrefsStore.serviceWorkersEnabled = it
                onSettingChanged()
            },
        )
```

Add toggle in the **Additional protections** section (after "Email Tracker Blocking", ~line 1012):

```kotlin
        SheetRowDivider()
        SettingsToggle(
            title = "Hide local IP in WebRTC",
            subtitle = "Prevent websites from seeing your local network IP during video calls. Restart to apply.",
            checked = webRtcIpsHidden,
            onCheckedChange = {
                onWebRtcIpsHiddenChange(it)
                globalPrefsStore.webRtcIpsHidden = it
                onSettingChanged()
            },
        )
```

## Summary

| Change | Count |
|--------|-------|
| Hardcoded about:config flags (Step 2) | 6 |
| New GlobalPreferencesStore prefs | 4 |
| User-toggle prefs folded into writeGeckoConfig YAML (Step 3) | 4 (8 pref lines) |
| New settings toggles in Advanced UI | 4 |
| Files modified | 3 |

## Verification

1. Build the project: `./gradlew assembleDebug` — Step 3 must compile (no `setAttribute` calls).
2. Open Settings > Advanced — verify 4 new toggles appear, each with "Restart to apply" in its subtitle.
3. Toggle a setting, **restart the app**, then confirm the underlying about:config value changed
   (inspect the generated `geckoview-config.yaml` in the app's `filesDir`, or via `about:config` if
   `aboutConfigEnabled` is temporarily turned on in `buildRuntime()`).
4. Autoplay: with "Block all autoplay" on, restart, verify muted video does not auto-play; turn off,
   restart, verify it does.
5. Notifications: enable, restart, visit a push-notification site, verify the permission prompt appears.
6. WebRTC: disable the toggle, restart, make a WebRTC call, verify the local IP is visible; enable,
   restart, verify it's hidden.
