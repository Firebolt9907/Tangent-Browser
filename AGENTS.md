# Tangent Browser — Agent & Contributor Guide

Non-obvious invariants anyone (human or AI) changing this code MUST respect to avoid regressions.
Full rationale: `IMPLEMENTATION.md`. Read after `README.md` / `ARCHITECTURE.md`.

> **Naming:** app is *Tangent Browser*, and code matches — package
> `com.firebolt9907.tangentbrowser`, classes (`TangentRoot`, `TangentApplication`), and files
> (`tangent_tabs.json`). (The project was formerly named *Lumina*, then *Expedition*; the
> `firebolt9907` package segment is the original author handle and is retained.)

## Project layout

```
app/src/main/java/com/firebolt9907/tangentbrowser/
  engine/         # GeckoEngine + BrowserStore DI graph
  state/          # Data layer (repos, snapshot cache, view models, prefs stores)
  addons/         # WebExtension install/runtime wrapper
  permissions/    # Runtime permission plumbing
  service/        # Custom Tabs service + browsing keep-alive foreground service
  sync/           # Firefox Sync, FxA, PlacesLoginsStorage (encrypted logins); sync/zen = Zen Spaces
  ui/
    chrome/       # SafariBar, BarPill, BarExpanded, BarCompact, BarEditing, aA sheet
    content/      # ContentHost (the single EngineView), page JS injection
    grid/         # Tab overview — iOS deck / pixel filmstrip / classic 2-col (TabViewMode)
    reader/       # Reader mode: extractor + view + per-site rules engine
    passwords/    # PasswordsSheet — password management UI
    sync/         # SyncedTabsSheet + spaces UI — tab/space sync
    bookmarks/ history/ downloads/  # Library sheets
    onboarding/   # First-run feature pages
    settings/     # SettingsSheet
    video/        # Fullscreen video overlay
    customtab/    # CustomTabRoot — slim surface for external-link Custom Tabs
    shape/        # SquircleShape
    addons/       # AddonsSheet UI
    theme/        # Material 3 colors/type
  TangentRoot.kt        # ~3000-line main composable — most gesture orchestration lives here
  TangentBrowserActivity.kt # shared activity base (lifecycle, ActivityDelegate, downloads)
  MainActivity.kt      # the browser (TangentRoot). Own singleTask task. LAUNCHER + home-shortcut only.
  CustomTabActivity.kt # external ACTION_VIEW/SEND links → a Custom Tab (own document task)
  IntentUrls.kt        # shared intent-URL parsing (both activities)
```

## Two activities / external links

External links from other apps (e.g. Google Discover) do NOT open in `MainActivity`. They open in
**`CustomTabActivity`** — a separate own-task activity (manifest: `taskAffinity` +
`launchMode="singleTask"` + `autoRemoveFromRecents`) hosting `ui/customtab/CustomTabRoot`. The page
is a mozilla-components **custom-tab session** (`store.state.customTabs`, via
`components.customTabsUseCases.add`), NOT a normal tab — it never appears in the tab grid,
`tangent_tabs.json`, or Firefox Sync. "Open in Tangent" migrates it to a real tab
(`customTabsUseCases.migrate`) and hands off to `MainActivity`.

Exit animation — two paths, do not collapse:
- **Resolved close animation** (CCT caller supplied `EXTRA_EXIT_ANIMATION_BUNDLE`, or our
  `R.anim.custom_tab_exit` fallback for Google-app-referrer / home-screen-Discover launches):
  `CustomTabRoot` registers a lowest-priority `PredictiveBackHandler` that drags the UI into a
  shrinking rounded card (`closeSlide`) and commits via `Activity.finish()` + `overridePendingTransition`
  (temporary `getPackageName()` override so client-APK anim resources resolve — Chrome's technique).
  **Do not** swap this for the system preview via `overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)`
  + dropping the callback — on Android 16/Pixel it then plays nothing at all.
- **Plain ACTION_VIEW** (no exit bundle, non-Google referrer): with no web history `CustomTabRoot`
  registers **no** back callback, so Back finishes the activity and Android plays its native
  predictive-back animation to the calling app.

Full mechanism: **IMPLEMENTATION.md → Custom Tab exit animation**.

Shared intent-URL parsing: `IntentUrls.kt` (both activities).

## Firefox Sync — three sync triggers (do not collapse)

`FirefoxSyncController` (`sync/`) keeps synced tabs fresh via three complementary mechanisms;
removing one degrades a different scenario:

1. **Background WorkManager** — `SyncConfig.periodicSyncConfig = PeriodicSyncConfig(240, 5)`. Every
   4h even when backgrounded/dead. Slow baseline.
2. **Foreground poll** — `startForegroundPeriodicSync()`/`stopForegroundPeriodicSync()`, driven by
   `TangentRoot`'s lifecycle observer (ON_RESUME start, ON_PAUSE/onDispose stop). Quiet full sync
   every `ForegroundSyncIntervalMs` (30 min). The **fallback** for missed pushes; keep it relaxed
   while push is the fast path.
3. **FxA push (fast path) — currently INERT; see below** — `initPush()`. In vanilla Firefox a remote
   tab change triggers an FCM push and this device re-syncs instantly. For us the subscription is
   **rejected by Mozilla's server**, so it delivers nothing; triggers 1–2 plus command polling keep
   tabs fresh.

### Why FxA push is inert (do not "fix" by retrying)

Push delivers nothing here: Mozilla's push server is an FCM bridge to *Mozilla's own* Firebase, so our
personal `google-services.json` gets `400 errno:106 "invalid app ID"` on every `subscribe()` and FxA
never gets a push endpoint. Triggers 1–2 + command polling keep tabs fresh. **Do not** re-plumb receipt
onto push for the personal project, and **do not** add a hard `R.string.gcm_defaultSenderId` reference
(breaks the no-Firebase build — `initPush()` resolves `gcm_defaultSenderId` by name at runtime, and the
Google Services plugin applies only `if (file("google-services.json").exists())`; `google-services.json`
is gitignored). Full analysis (desktop WebSocket vs FCM bridge, self-host options): **IMPLEMENTATION.md
→ Sync triggers & why FxA push is inert**.

### Receiving "Send tab" — via command polling (the real path)

`SendTabFeature` is registered **unconditionally** in `installSendTabFeature()` (from `init`, before
`accountManager.start()`), independent of push/Firebase. Commands are pulled by
`pollIncomingCommands()` → `deviceConstellation().pollForCommands()`, after every foreground sync pass
(startup, ON_RESUME handoff check, 30-min foreground loop, `refreshSyncedTabs`). `SendTabFeature`'s
observer fires per command. Latency: seconds while foregrounded / instant on app open — needs no
server. **Do not** gate `installSendTabFeature()` or `pollIncomingCommands()` behind
`firebaseSenderId()` (ties receipt to the dead push path).

A received tab does **not** auto-open. It raises a system notification (`sync/ReceivedTabNotifications`,
channel `received_tabs`, needs `POST_NOTIFICATIONS` — `MainActivity` requests it at launch); tapping
launches `MainActivity` with `EXTRA_OPEN_URL`, opening the URL as a normal tab via
`tabRepository.addTab` — the single `normalizeUrl` path (invariant #9).

## Critical invariants — do not violate

### 1. Single EngineView per foreground activity / single rebind site

Each activity hosts **exactly one** `EngineView` (created in `ContentHost`). `MainActivity`
(`TangentRoot`) and `CustomTabActivity` (`CustomTabRoot`) each have their own, never interactively
foreground at once — the backgrounded one's `EngineView` is released via its lifecycle
`onStop`/`release` in `ContentHost.kt`. Do NOT add a second live `EngineView` *within* one activity.

**The ONLY place calling `engineView.render(session)` is the `DisposableEffect(engineView,
engineSession, tabId)` block in `ContentHost.kt`.** The `AndroidView { update = … }` lambda must NOT
call `render()` — if both do, the applier rebinds before `onDispose` runs, so the leaving-tab
`captureThumbnail` samples the new session and stores it under the wrong cache key (every tab switch
overwrites the leaving tab's snapshot with the incoming one).

### 2. BarPill drag — `startDragImmediately = false`

`BarPill`'s `draggable` uses `startDragImmediately = false`. Setting `true` consumes `ACTION_DOWN`
into the drag stream before child pointer input, killing every `IconButton` (aA, Extensions, Reload)
and the URL tap. Cost: one touch-slop of travel before a swipe registers — imperceptible.

### 3. `pillInteractive` vs `tabSwitchingEnabled` — two separate flags

`tabSwitchingEnabled` flips `false` when `gridGestureActive` is set. It must NOT gate `BarPill`'s
`pointerInput` — that tears down the gesture listener mid-flight, breaking grid-open drags. Instead:
- `pillInteractive = !barCollapseCompact && !gridOpen` — controls the pill's drag listener
- `tabSwitchingEnabled` — controls the horizontal tab-switch clamp inside the listener
- `controlsEnabled = !gridEngaged` — disables URL field / buttons during grid gestures

### 4. Grid progress — two separate state variables

- `gridProgressState: Animatable<Float>` — spring animations only (open/close/cancel). **Never
  `snapTo` on the drag hot-path.**
- `gridDragProgress: Float` (`mutableFloatStateOf`) — written directly on every drag delta.

Render reads `if (gridGestureActive) gridDragProgress else gridProgressState.value`. Mixing them
causes frame-skip or stale visual position on finger-up.

### 5. Horizontal-drag overlay vs grid overlay

The horizontal-drag overlay is gated on `!gridEngaged`; the grid takes over when `gridEngaged`. Never
render both at once.

### 5a. Tab overview has three selectable modes (do not assume the 2-column grid)

Persisted `TabViewMode` (`state/GlobalPreferencesStore.tabViewModeName`, parsed by
`TabViewMode.fromName`, default `DECK`), cycled from the overview toolbar button in `TangentRoot`:
- **DECK** (default) — iOS app-switcher: centred focus card, newer card stacked on top pushed far
  right, older cards a geometrically-tapering left stack fading with depth. Geometry: `deckSlot(p)`
  in `GridModel.kt`.
- **PIXEL** — constant-scale horizontal filmstrip (`pixelSlot(p)`), no receding/stacking/fade.
- **GRID** — legacy 2-column vertical grid, recovered verbatim from the `jul-8` branch as
  `ui/grid/ClassicGrid.kt` (`ClassicTabGridOverlay`) + `gridCellRect`/`Grid*` constants in
  `GridModel.kt`.

Both `TabGridOverlay` (DECK/PIXEL) and `ClassicTabGridOverlay` (GRID) consume the **same**
layout-agnostic gesture core in `TangentRoot` (progress, release positions, invariants #4/#5) — the
mode only swaps the destination layout. Non-obvious:
- `TabGridOverlay` routes all per-card placement through one `slotProvider(p)` (deckSlot vs
  pixelSlot) read in the draw phase; drag/scroll `stepPx` is mode-aware too.
- Deck scroll is stored in **pixels** (not card-units): the spline-decay fling needs pixel-scale
  magnitudes or inertia dies; release projects the fling then springs to the nearest card.
- `ClassicGrid.kt`'s private helpers (`GridTabCard`, `GridCellChrome`, `GestureVisibleSlots`) share
  names with `Grid.kt`'s but are **file-scoped** — keep them private so they don't collide.
- Swipe-up on a deck card is distance-sensitive: gentle navigates to the older tab, decisive (~38% of
  screen) closes it.
- Captions load **real favicons** via `components.icons` (mozilla `BrowserIcons`, URL-keyed cache in
  `TangentRoot` pruned to open tabs), falling back to `ContentState.icon` then a letter tile.

### 5b. Spaces — the overview shows ONE space's tabs, not all tabs

A **Space** is a `TangentTabGroup` elevated to a first-class tab set (the evolution of Tab Groups). In
`TangentRoot`, `tabsList` is **not** `components.tabsList` directly — it is `allTabs` (the store set)
narrowed to the active space (`activeSpaceId`; null = the default "Personal" space = tabs in no group).
Because both the deck/pixel/grid overview AND horizontal pill tab-switching read `tabsList`, this one
filter scopes both to the current space. Non-obvious:
- **`activeSpaceId` follows the selected tab while the grid is CLOSED** (a `LaunchedEffect` gated on
  `!gridEngaged`), so the visible space always contains the current tab even when a tab is selected
  outside the grid (received/handoff/synced-tab open, "open in new tab"). While the grid is engaged the
  user drives `activeSpaceId` via `SpaceSwitcherStrip` — don't re-enable the follow-effect there.
- `selectedIndex`/`newSelIdx` may briefly be `-1` when the active space doesn't contain the selected
  tab (right after switching spaces in the grid). `newSelIdx` already `.coerceAtLeast(0)`s — keep that;
  it's what makes the transient tolerable (grid centres on card 0, prev/next swipe is null).
- New tabs created from the grid "+" are re-added to `activeSpaceId` (`addTabToGroup`) so they don't
  fall into Personal and vanish from the switcher they were created in.
- The switcher strip (`ui/grid/SpaceSwitcherStrip.kt`) sits above `GridOverviewControls`; long-press a
  chip / overflow "Manage Spaces" opens `TabGroupsSheet` (the same `TangentTabGroup` registry).

### 6. Snapshot capture — `EngineView.captureThumbnail`, not `EngineSession`

`EngineSession.captureThumbnail` does not exist in this mozilla-components version. All captures go
through `EngineView.captureThumbnail`.

### 7. Sticky cover clearing

Do NOT clear the sticky incoming snapshot just because `selectedContent.url` reaches the target URL —
Android Components can update URL state before Gecko repaints the surface. Clear it on a matching
paint callback, or after target URL reached + loading false + fallback delay expires (700 ms). A hard
stale-cover timeout also exists.

### 8. Snapshot cache key prefix

Disk cache uses a `v2:` prefix: `"v2:${stableUrl ?: "tab:$id"}"`. Don't change it without
clearing/migrating old cache files — migration logic is absent, so old entries become orphans that
never prune correctly.

### 9. URL normalization lives in `GeckoTabRepository.kt`

`normalizeUrl(input: String)` and `compactHost(url: String)` are `internal` in
`state/GeckoTabRepository.kt`. Every entry point taking user-typed input (loadUrl, addTab,
seedIfEmpty) goes through `normalizeUrl`. Do not add a second normalization path.

### 10. Scroll-delta batching in `ContentHost`

Gecko scroll callbacks must NOT trigger Compose work per-delta. `ContentHost` accumulates deltas and
applies them once per frame via `withFrameNanos`. Direct `barCollapsePx` mutations outside this path
can cause unbounded coroutine launches.

### 11. CSS/JS spacer injection guard

`ContentHost`/`PageBottomSpacer` inject viewport spacer JS on FCP and load-complete — a top spacer
(status bar / SafariBar safe area) and a bottom spacer (in-flow footer clearance for the pill).
`suppressJavascriptLoadCallback` prevents the `javascript:` URL itself from re-triggering injection —
**any new JS injection site must check/set this guard first.**

The script self-heals: `applyHeaderInsets` re-ensures the top spacer `<div>` and `scroll-padding-top`
`<style>` every pass (SPAs clear them on client-side nav), with an adaptive cooldown (500ms → 2000ms
once the header set is stable) to bound the steady-state full-document walk.

**Bottom inset — two complementary mechanisms, keep both:**
- **Native dynamic-toolbar clipping** (`EngineView.setDynamicToolbarMaxHeight`/`setVerticalClipping`
  in `ContentHost`) reserves pill/SafariBar space in the *visual viewport* (`svh`/`dvh`), so
  `fixed`/`sticky` bottom elements sit above the pill and the page scrolls translucently *under* it.
  Do NOT remove — viewport-relative bottom UI relies on it.
- **JS bottom spacer** (`buildBottomSpacerScript`, via `injectViewportSpacers(bottomPx=…)`) appends
  an end-of-document spacer `<div>` (+ `scroll-padding-bottom`) sized to the pill so ordinary
  **in-flow** bottom content (footer links, a "forgot password" button) gets scroll room to clear the
  always-visible compact pill. It does NOT push content up (pill stays a scrolled-under overlay),
  only lengthens the scrollable document. Marks itself `data-tangentIgnoreSafeTop` (top-inset walk
  skips it); self-heals via a bounded MutationObserver for SPAs that rebuild `<body>`. Re-added after
  being wrongly removed as a double-pad.

### 12. WebAuthn/passkeys — a three-way pref (`passkeyMode`), never a page-world JS shim

Passkeys would normally use GeckoView's built-in WebAuthn (Play Services FIDO2 / Credential Manager →
Google Password Manager); the app-side hook is the
`mozilla.components.concept.engine.activity.ActivityDelegate` that `MainActivity`/`CustomTabActivity`
register in `onStart`. But Tangent is NOT on the platform trusted-browser allowlist, so that path is
rejected — hence `GlobalPreferencesStore.passkeyMode` (`Off`/`Softtoken`/`Bitwarden`), emitted by
`writeGeckoConfig` in `Engine.kt`, migrated from the old `builtInPasskeysEnabled` boolean. The three
modes are **mutually exclusive** (one enum, not independent toggles) — Gecko reads the config once, so
a change needs a restart:
- **Off** — `security.webauth.webauthn: false`; the API is disabled and sites cleanly fall back to
  passwords.
- **Softtoken** — WebAuthn on, serviced by Gecko's INTERNAL softtoken
  (`..._enable_softtoken = true` + `..._enable_android_fido2 = false`). Spec-correct assertions, no
  allowlist, but keys live in the Gecko profile (not GPM/vault) and there is no biometric prompt.
- **Bitwarden** — WebAuthn on with **softtoken OFF**, and the built-in passkey-bridge extension
  (`assets/extensions/passkey/`, `sync/webauthn/PasskeyExtension`) overrides `navigator.credentials`
  and services ceremonies from the connected Bitwarden vault, biometric-gated. Installed only in this
  mode (`Engine.init`), so Off/Softtoken paths are untouched.

**The forbidden thing is a PAGE-WORLD JS shim, not the Bitwarden override.** Do NOT polyfill
`navigator.credentials` from page-world JS (a previous shim faked credentials in `localStorage`, never
reached a real authenticator, and produced raw-r‖s ECDSA signatures RPs reject — WebAuthn needs DER).
The Bitwarden path is categorically different and sanctioned: the override is installed from an
**isolated content script** via Firefox Xray/`exportFunction` (page CSP can't touch it), and the
crypto is real — `sync/webauthn/WebAuthnAuthenticator` produces spec-correct **DER** ES256 assertions
over **real vault key material** (`sync/bitwarden/BitwardenPasskeyStore`), never fabricated values.
See [[bitwarden-passkeys-progress]] and the `sync/webauthn` + `sync/bitwarden` sources.

Do NOT remove the pref gate or default an active mode on for an allowlisted build — both are fallbacks
for the blocked native path. If Tangent ever gets allowlisted, prefer the native path.

**Cross-client interop risk:** whether Tangent can use a passkey created on desktop Bitwarden depends
on schema details (credentialId GUID vs base64url, `keyValue` base64 variant, .NET mixed-endian byte
order) that unit tests only prove self-consistent — confirm on-device against a real vault.

### 13. Compact bar — tap to expand, no horizontal swipe

In compact mode (`barCollapseCompact = true`), `BarPill.interactive` becomes `false` after the bar
crosses halfway-to-compact. Compact is tap-to-expand only — horizontal tab switching is intentionally
disabled to prevent accidental tab changes on a narrow pill.

### 14. Logins DB key is an appservices **JWK**, not raw bytes

`PlacesLoginsStorage`'s `KeyManager.getKey()` MUST return the UTF-8 bytes of a key from appservices'
`mozilla.appservices.logins.createKey()` (a `{"kty":"oct",…}` JWK). Appservices does
`std::str::from_utf8(get_key())` then parses it as a JWK; **32 raw random bytes fail with
`InvalidKey`**, silently throwing on every encrypt/decrypt — the store then persists nothing (local
add AND incoming synced records fail), leaving the passwords UI empty. The JWK is resolved eagerly
(before the DB opens) and sealed under an AndroidKeyStore AES-GCM wrapping key; regenerating it wipes
the old, now-undecryptable DB. Do NOT swap back to a self-generated AES key.

### 15. Autofill needs the runtime autocomplete delegate — not just engine flags

Login/credit-card autofill works only because `TangentBrowserComponents` sets
`runtime.autocompleteStorageDelegate = GeckoAutocompleteStorageDelegate(ccDelegate, loginDelegate)`.
`engine.settings.loginAutofillEnabled` / `extensions.formautofill.creditCards.*` prefs only tell Gecko
to *try* — without the delegate it has no store. Logins: `TangentLoginStorageDelegate` →
`PlacesLoginsStorage`. Credit cards: mozilla-components `service-sync-autofill`
(`AutofillCreditCardsAddressesStorage`, numbers encrypted at rest, PAN never decrypted for the list UI
— only last4 shown). **Credit cards have no CVC field and never will** — GeckoView's
`Autocomplete.CreditCard` has no CVC slot (PCI-DSS forbids storing it), so autofill covers
number/name/expiry only. Both sync via standard sync15-E2E engines (`SyncEngine.Passwords` /
`SyncEngine.CreditCards`, the latter using `AutofillCrypto` as `KeyProvider`).

**The delegate is half the wiring — fill/save doorhangers are Compose prompts.** The delegate makes
Gecko *fetch* logins (`onLoginFetch`) on login-field focus, but Gecko surfaces the fill UI as
`PromptRequest.SelectLoginPrompt` and saving as `PromptRequest.SaveLoginPrompt`. These MUST be handled
in `ui/PromptHandler.kt`'s `when (promptRequest)` block. If they fall to `else` (logs
`"Unhandled prompt: …"`), fetch still succeeds but **no fill/save UI appears** — autofill looks dead
though logins are present. `SelectCreditCard`/`SaveCreditCard`/`SelectAddress` are the equivalents —
same rule, all handled in `PromptHandler` (CC/address pickers live in `ui/AutofillPickerSheets.kt`;
CC fill is biometric-gated like logins, addresses are not). Each is gated by its own pref
(`creditCardAutofillEnabled` / `addressAutofillEnabled`), both threaded into `PromptHandler` from the
two call sites. This MC version surfaces **no** `SaveAddress` doorhanger, so addresses are added
manually via `ui/passwords/AddressesSheet.kt` (overview menu → "Addresses"); cards via
`CreditCardsSheet`. Address storage shares the encrypted `creditCardsStorage`
(`AutofillCreditCardsAddressesStorage`) — its address side is enabled by the delegate's
`isAddressAutofillEnabled` lambda in `Engine.kt` (was hard-`false`). `PromptHandler` is mounted in
both surfaces (`TangentOverlaySheets`, `CustomTabRoot`); a new prompt type added to the one `when`
works in both.

**Generic form-field memory is separate — a JS bridge, not Gecko autofill.** Logins/cards/addresses
are all Gecko `formautofill`; freeform fields (a name, an age, an email outside a login) are not, so
`ui/content/FormFieldMemory.kt` handles them over the `tangent-formfill-save`/`-get` bridge messages
(routed in `ReaderExtractor.onTextPrompt` — `-get` answers **synchronously** via `prompt.confirm(json)`,
the one bridge path that reads a return value). Capture-on-submit/blur + an in-page suggestion dropdown
on focus; sensitive fields (password/cc/otp) are excluded in JS. Gated host-side by
`FormFieldMemory.enabled` (the `formFieldMemoryEnabled` pref, synced in `applyEngineSettings`), and the
store is `attach`ed once in the `Engine` init. Injected per-load in `ContentHost` next to the OTP/login
detectors.

**The `tangent-*` prompt bridge is security-hardened — do not bypass `BridgeGuard`.** The bridge is
just `window.prompt("tangent-…", payload)`, so *any* script in the page realm (incl. third-party ad
scripts) could otherwise drive our probes or read stored data. Two guards in
`ui/content/BridgeGuard.kt`, both load-bearing:
- **Nonce.** Every message our injected scripts emit is tagged `name~<nonce>` via `BridgeGuard.tag(…)`
  (per-process `SecureRandom` token, embedded only in the injected closures). `onTextPrompt` runs the
  message through `BridgeGuard.resolve()`; a `tangent-*~…` with a wrong/missing nonce is **swallowed**
  (`Rejected`), a message with no separator is `Foreign` (falls through). **Any new probe that posts
  over the bridge MUST emit `'${'$'}{BridgeGuard.tag(NAME)}'`, and any new handler MUST live inside the
  `Accepted` branch** — a bare `prompt.message == NAME` check reopens the channel to page JS.
  (`tangent-reader`/`tangent-video` are intentionally left as legacy exact-match — one-way, low-severity.)
- **Trusted-origin binding.** `FormFieldMemory.onSave`/`getSuggestions` take the `tabId` and key storage
  by `BridgeGuard.trustedOrigin(tabId)` (the tab's REAL origin, recorded by `ContentHost` on inject +
  `onLocationChange`), **never** the host in the page's payload — otherwise `evil.com` could read
  `bank.com`'s remembered values. Fails closed (empty) when no origin is recorded. Don't re-introduce a
  page-supplied host.

**`ReaderPromptDelegate` must forward EVERY prompt callback it doesn't handle.** Reader mode
(`ui/reader/ReaderExtractor.registerPromptDelegate`) installs a `GeckoSession.PromptDelegate` that
**replaces** the session's delegate to intercept `onTextPrompt` for the `tangent-*` bridge
(reader/theme/OTP/link probes). Because it replaces (not chains before) the mozilla-components
`GeckoPromptDelegate`, any callback it doesn't override hits the no-op interface default and is
**silently dropped** — exactly what made autofill look dead (`onLoginSelect`/`onLoginSave` weren't
forwarded, so `SelectLoginPrompt` never fired despite `onLoginFetch` matches). Only `onTextPrompt`
needs interception; **all** others (`onLoginSelect/Save`, `onCreditCard*`, `onAddress*`,
`onAuthPrompt`, `onColorPrompt`, `onDateTimePrompt`, `onFilePrompt`, `onPopupPrompt`, `onSharePrompt`,
plus alert/button/choice/beforeunload/repost) must pass through to `oldDelegate`.

**Fill flow (biometric + auto-submit):** `SelectLoginPrompt` renders `SelectLoginSheet` (M3 bottom
sheet). Selecting a credential runs `BiometricGate.authenticate` (fingerprint/face or device
credential; a no-op that fills immediately when the device has no secure lock, so autofill is never
un-triggerable — why `TangentBrowserActivity` extends `FragmentActivity`, the only host
`androidx.biometric.BiometricPrompt` supports). On success it calls `onConfirm(login)` **and** injects
`buildLoginSubmitScript()` to press the form's login button; that script acts only on a form
containing a filled `input[type=password]`, so it never submits a stray search box.

### 16. SMS one-time-code (2FA) autofill

Two halves via the `OtpAutofill` singleton (`ui/content`): a JS **detector** (`buildDetectScript`,
injected per-load in `ContentHost` like the other probes) reports OTP fields over the `window.prompt`
bridge (routed in `ReaderExtractor`'s prompt delegate — same `tangent-*` channel as
ThemeColorProbe/LinkRectProbe) with heuristics broader than GBoard's `autocomplete="one-time-code"`
check; and a **filler** (`buildFillScript`) that types the code (handles split single-char boxes; uses
the native value setter for React inputs). The SMS is read via Play Services **SMS User Consent**
(`OtpSmsController`, `play-services-auth-api-phone`) — **no** SMS permission, one-tap consent dialog
per code. `MainActivity` owns the SMS side (`smsListenStarter` set in onResume, cleared in onPause);
`TangentRoot` owns the page side (`codeFiller` injects into the selected session) plus the manual
"paste code" fallback banner (`OtpPasteBannerHost`). Do NOT gate receipt on `RECEIVE_SMS`
(intentionally avoided — Play Store restriction).

### 17. The built-in reader extension asset is GENERATED — never edit it in the Android tree

`app/src/main/assets/extensions/reader/extractor.js` (+ its `manifest.json`) is **build output**, not
source. A Gradle hook, `buildReaderExtension` in `app/build.gradle.kts`, runs `node extension/build.js
tangent` and is wired into **every** `merge*Assets` task (`tasks.matching { startsWith("merge") &&
endsWith("Assets") }.dependsOn(...)`), so it regenerates that file on **every compile**. Any hand-edit
to the Android-side `extractor.js` is silently overwritten the next build — this is the "a linter keeps
stripping my changes" symptom.

- **Edit the source, then regenerate.** The real source is `extension/src/extractor.core.js` (pure
  `tangentExtractArticle` + `tangentExtractSocial`) + `extension/src/glue.tangent.js` (the Port `run()`
  glue). After changing those, run `cd extension && node build.js tangent` to regenerate the asset (and
  commit both). `node build.js tangent --check` is the CI drift guard; the SAME `extractor.core.js` also
  feeds the standalone desktop extension (`build.js standard`). See [[social-reader-mode]] and
  `extension/README.md`.
- **Do NOT** point any tooling, agent loop, or edit at `assets/extensions/reader/*` directly. If Node
  isn't installed the hook warns and keeps the committed copy, so the committed asset must stay in sync
  with `extension/src` — regenerate before committing.

The **per-site reader rules** are a separate, non-generated asset: `app/src/main/assets/reader_rules.json`
is hand-editable and is the compile-time floor (invariant: it is what ships unless overridden). The
`syncReaderRules` hook only overwrites it when `tangentRulesRepo`/`TANGENT_RULES_REPO` points at an
external rules-repo clone; that property is unset by default, so a normal build **keeps the checked-in
JSON** (it logs `tangentRulesRepo/TANGENT_RULES_REPO not set; keeping checked-in asset`). Do NOT set that
env/property in a shared config — it would replace the bundled rules with the external repo's copy on
every build. Bump `version` on any edit and raise the floor in `ReaderBundledRulesTest`.

The most-violated mistakes are the bolded **do NOT** lines in the invariants above (esp. #1 `render()`
from `AndroidView.update`, #2 `startDragImmediately = true`, #4 `snapTo` on hot path, #6
`EngineSession.captureThumbnail`, #11 JS injection without the guard / spacer sizing, #12
page-world `navigator.credentials` shim (the softtoken AND the isolated-world Bitwarden override are
sanctioned pref-gated paths — see #12), #17
editing the generated `assets/extensions/reader/extractor.js` instead of `extension/src`).

## Testing

Unit tests: `app/src/test/` (JVM via `./gradlew :app:test` if no Android framework); device tests:
`app/src/androidTest/`. `normalizeUrl`/`compactHost` have JVM tests in
`app/src/test/java/com/firebolt9907/tangentbrowser/UrlNormalizationTest.kt`.

## Build notes

- Default dev build filters to `arm64-v8a` (~200 MB APK). `-PsingleAbi=false` builds all ABIs.
- Release signing uses the local debug keystore intentionally (not for distribution).
- `isMinifyEnabled`/`isShrinkResources` are `false` in release.
