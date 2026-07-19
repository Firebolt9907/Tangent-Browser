# Expedition Browser — Agent & Contributor Guide

Architecture context and non-obvious invariants that anyone (human or AI agent) changing this code
MUST respect to avoid regressions. For full rationale see `IMPLEMENTATION.md`; this file captures the
rules most likely to be violated. New contributors should read this after `README.md` /
`ARCHITECTURE.md`.

> **Naming:** the app is *Expedition Browser*; the codebase still uses the original name *Lumina* in
> its package (`com.firebolt9907.luminabrowser`), class names (`LuminaRoot`, `LuminaApplication`),
> and on-disk files (`lumina_tabs.json`). Those are Expedition — a full code rename is pending, so
> this guide keeps the real symbol names.

## Project layout

```
app/src/main/java/com/firebolt9907/luminabrowser/
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
  LuminaRoot.kt        # ~3000-line main composable — most gesture orchestration lives here
  LuminaBrowserActivity.kt # shared activity base (lifecycle, ActivityDelegate, downloads)
  MainActivity.kt      # the browser (LuminaRoot). Own singleTask task. LAUNCHER + home-shortcut only.
  CustomTabActivity.kt # external ACTION_VIEW/SEND links → a Custom Tab (own document task)
  IntentUrls.kt        # shared intent-URL parsing (both activities)
```

## Two activities / external links

External links from other apps (e.g. a Google Discover article) do NOT open in `MainActivity`.
They open in **`CustomTabActivity`**, a separate own-task activity (manifest: dedicated
`taskAffinity` + `launchMode="singleTask"` + `autoRemoveFromRecents`) that hosts
`ui/customtab/CustomTabRoot`. The page is a mozilla-components **custom-tab session**
(`store.state.customTabs`, created via `components.customTabsUseCases.add`) — it is NOT a normal tab,
so it never appears in the tab grid, `lumina_tabs.json`, or Firefox Sync. "Open in Expedition" migrates
it into a real tab (`customTabsUseCases.migrate`) and hands off to `MainActivity`.

Exit animation (two paths — do not collapse them):
- **Resolved close animation** (CCT caller supplied `EXTRA_EXIT_ANIMATION_BUNDLE`, or our own
  `R.anim.custom_tab_exit` fallback for Google-app-referrer launches — the home-screen Discover
  panel sends plain VIEW intents): `CustomTabRoot` registers a lowest-priority
  `PredictiveBackHandler` that shrinks the whole UI into an opaque rounded-corner card tracking the
  gesture (`closeSlide`, M3 predictive-back style) and routes the commit through `Activity.finish()`, which plays the
  animation via `overridePendingTransition` with a temporary `getPackageName()` override so
  client-APK anim resources resolve against the *client's* package (Chrome's technique; keys
  `android:activity.packageName`/`animEnterRes`/`animExitRes`). This intercept is required for
  home-screen Discover launches: their close is a cross-task transition down to the embedded
  "-1 screen", and the launcher animates nothing for this icon-less, recents-excluded task.
  **Do not** try to get the *system* predictive preview here by pre-registering the animation with
  `overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)` and dropping the callback — tried on
  Android 16/Pixel: the close then plays nothing at all (no drag preview, and the override
  suppresses even the canned animation). The in-app predictive drag + `finish()` animation is the
  working combination.
- **Plain ACTION_VIEW** (no exit bundle, non-Google referrer): when the page has no web history
  `CustomTabRoot` registers **no** back callback, so Back finishes the activity and Android plays
  its native predictive-back animation straight back to the calling app.

(Shared intent-URL parsing lives in `IntentUrls.kt`; used by both activities.)

## Firefox Sync — three sync triggers (do not collapse)

`FirefoxSyncController` (in `sync/`) keeps synced tabs fresh via three layered mechanisms. They are
complementary; removing one degrades a different scenario:

1. **Background WorkManager** — `SyncConfig.periodicSyncConfig = PeriodicSyncConfig(240, 5)`. Runs
   every 4 hours even when the app is backgrounded/dead. The slow baseline.
2. **Foreground poll** — `startForegroundPeriodicSync()` / `stopForegroundPeriodicSync()`, driven by
   `LuminaRoot`'s lifecycle observer (ON_RESUME start, ON_PAUSE / onDispose stop). A quiet full sync
   every `ForegroundSyncIntervalMs` (30 min). This is the **fallback** for missed pushes — it was
   10 min before push existed; keep it relaxed while push is the fast path.
3. **FxA push (fast path) — currently INERT for us; see below** — `initPush()`. In vanilla Firefox a
   remote tab change triggers an FCM push and this device re-syncs instantly. For us the push
   subscription is **rejected by Mozilla's server**, so this trigger delivers nothing; triggers 1–2
   plus command polling are what actually keep tabs fresh.

### Why FxA push doesn't work here (and must not be "fixed" by retrying it)

- Mozilla's hosted push server (`updates.push.services.mozilla.com`) is an FCM **bridge to Mozilla's
  own Firebase project**. Registering a subscription for a *third-party* sender id returns
  `400 {"errno":106,"message":"User has invalid app ID <senderId>"}`. Our `google-services.json` is a
  personal Firebase project, so every autopush `subscribe()` fails and FxA never gets a push endpoint
  for this device (`currentDevice.subscription == null`). FCM token retrieval itself succeeds — the
  wall is Mozilla's bridge, not Google.
- Desktop Firefox/Zen get instant push because desktop uses a **direct WebSocket** to autopush (no
  FCM, no app-id gate). Mobile Firefox (Fenix) uses the FCM bridge but with **Mozilla's** sender id /
  Firebase project, which the bridge accepts. F-Droid forks (Fennec, Mull) ship **no** FCM and rely
  on polling — which is what we do.
- The only ways to get real push on our own project: self-host an autopush bridge to our Firebase and
  point `PushConfig(serverHost=…)` at it, or piggyback Mozilla's sender id (works but is against
  Mozilla's ToS and fragile). **Do not** re-plumb receipt onto push expecting it to work with the
  personal project.
- `initPush()` scaffolding is intentionally kept (isolated, failure-swallowing) so a future
  self-hosted autopush needs only a `serverHost` change. It stays inert without a real
  `app/google-services.json`: the Google Services Gradle plugin is applied conditionally
  (`if (file("google-services.json").exists())`), and `initPush()` looks up `gcm_defaultSenderId`
  **by name at runtime** (`resources.getIdentifier`) — **do not add a hard `R.string.gcm_defaultSenderId`
  reference** (breaks the no-Firebase build). `google-services.json` is gitignored.

### Receiving "Send tab" — via command polling (the real path)

`SendTabFeature` is registered **unconditionally** in `installSendTabFeature()` (from `init`, before
`accountManager.start()`), independent of push/Firebase. Incoming commands are pulled by
`pollIncomingCommands()` → `deviceConstellation().pollForCommands()`, called after every foreground
sync pass (startup, the ON_RESUME handoff check, the 30-min foreground loop, `refreshSyncedTabs`).
`SendTabFeature`'s observer fires per command found. Latency is seconds-while-foregrounded / instant
on app open — not push-instant, but it needs no server. **Do not** gate `installSendTabFeature()` or
`pollIncomingCommands()` behind `firebaseSenderId()`; that would make receipt depend on the dead push
path.

A received tab does **not** auto-open. It raises a system notification (`sync/ReceivedTabNotifications`,
channel `received_tabs`, needs `POST_NOTIFICATIONS` — `MainActivity` requests it at launch); tapping
it launches `MainActivity` with `EXTRA_OPEN_URL`, which opens the URL as a normal tab via
`tabRepository.addTab` — still the single `normalizeUrl` path, invariant #9.

## Critical invariants — do not violate

### 1. Single EngineView per foreground activity / single rebind site

Each activity hosts **exactly one** `EngineView` (created inside `ContentHost`). `MainActivity`
(via `LuminaRoot`) and `CustomTabActivity` (via `CustomTabRoot`) each have their own, but they are
never interactively foreground at the same time — the backgrounded activity's `EngineView` is
released through its lifecycle `onStop`/`release` in `ContentHost.kt`. Do NOT add a second live
`EngineView` *within* one activity: `EngineSession.render()` rebinds the single view when the
selected tab changes, and the snapshot-cache corruption below is a within-activity, tab-switch
concern.

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

### 5a. Tab overview has three selectable modes (do not assume the 2-column grid)

The "all tabs" overview is a persisted `TabViewMode` (`state/GlobalPreferencesStore.tabViewModeName`,
parsed by `TabViewMode.fromName`, default `DECK`), cycled from the overview toolbar button in
`LuminaRoot`:

- **DECK** (default) — iOS app-switcher: centred focus card, the newer card stacked on top pushed
  far right, older cards a geometrically-tapering left stack that fades with depth. Geometry is
  `deckSlot(p)` in `GridModel.kt`.
- **PIXEL** — constant-scale horizontal filmstrip (`pixelSlot(p)`), no receding/stacking/fade.
- **GRID** — the legacy 2-column vertical grid, recovered verbatim from the `jul-8` branch as
  `ui/grid/ClassicGrid.kt` (`ClassicTabGridOverlay`) + `gridCellRect`/`Grid*` constants in
  `GridModel.kt`.

Both `TabGridOverlay` (DECK/PIXEL) and `ClassicTabGridOverlay` (GRID) consume the **same**
layout-agnostic gesture core in `LuminaRoot` (progress, release positions, invariants #4/#5) — the
mode only swaps the destination layout. Non-obvious rules:

- `TabGridOverlay` routes ALL per-card placement through one `slotProvider(p)` (deckSlot vs
  pixelSlot) read in the draw phase; the drag/scroll `stepPx` is mode-aware too.
- Deck scroll is stored in **pixels** (not card-units): the spline-decay fling needs pixel-scale
  magnitudes or inertia dies; release projects the fling then springs to the nearest card.
- `ClassicGrid.kt`'s private helpers (`GridTabCard`, `GridCellChrome`, `GestureVisibleSlots`) share
  names with `Grid.kt`'s but are **file-scoped**, so they don't collide — keep them private.
- Swipe-up on a deck card is distance-sensitive: a gentle swipe navigates to the older tab, a
  decisive one (~38% of the screen) closes it.
- Captions load **real favicons** via `components.icons` (mozilla `BrowserIcons`, URL-keyed cache in
  `LuminaRoot` pruned to open tabs), falling back to `ContentState.icon` then a letter tile.

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

`ContentHost` / `PageBottomSpacer` inject viewport spacer JavaScript on FCP and load-complete — a
top spacer (status bar / SafariBar top safe area) and a bottom spacer (in-flow footer clearance for
the pill; see below). `suppressJavascriptLoadCallback` prevents the `javascript:` URL itself from
re-triggering injection. If you add a new JS injection site, you must check/set this guard first.

The injected script self-heals: `applyHeaderInsets` re-ensures the top spacer `<div>` and the
`scroll-padding-top` `<style>` on every pass (SPAs can clear them on client-side navigation), and
uses an adaptive cooldown (500ms → 2000ms once the chosen header set is stable) to bound its
steady-state full-document walk.

**Bottom inset — two complementary mechanisms, keep both:**
- **Native dynamic-toolbar clipping** (`EngineView.setDynamicToolbarMaxHeight` /
  `setVerticalClipping` in `ContentHost`) reserves the pill / SafariBar space in the *visual
  viewport* (`svh`/`dvh`), so `position: fixed`/`sticky` bottom elements sit above the pill and the
  page still scrolls translucently *under* it. Do NOT remove this — it is what viewport-relative
  bottom UI relies on.
- **JS bottom spacer** (`buildBottomSpacerScript`, injected by `injectViewportSpacers(bottomPx=…)`)
  appends an end-of-document spacer `<div>` (+ `scroll-padding-bottom`) sized to the pill so
  ordinary **in-flow** content at the very bottom of a page (footer links, a "forgot password"
  button) gets enough scroll room to be brought clear of the always-visible compact pill and
  tapped. This was previously removed as a double-pad, then deliberately re-added: it does NOT push
  content up (the pill stays a scrolled-under overlay), it only lengthens the scrollable document.
  It marks itself `data-luminaIgnoreSafeTop` so the top-inset walk skips it, and self-heals via a
  bounded MutationObserver for SPAs that rebuild `<body>`.

Any NEW JS injection site must still check/set `suppressJavascriptLoadCallback` first (it prevents
the `javascript:` URL from re-triggering injection).

### 12. WebAuthn/passkeys are native — no JS shim, no pref overrides

Passkeys go through GeckoView's built-in WebAuthn support (Play Services FIDO2 / Credential
Manager → Google Password Manager). The only app-side requirement is the
`mozilla.components.concept.engine.activity.ActivityDelegate` that `MainActivity` and
`CustomTabActivity` register in `onStart` (GeckoView hands us a `PendingIntent` for the system
passkey sheet; we launch it and return the activity result). Do NOT:
- polyfill/override `navigator.credentials` with injected JS (a previous shim faked credentials
  in `localStorage`; it never reached GPM and produced raw-r||s ECDSA signatures that RPs reject
  — WebAuthn requires DER);
- force `security.webauth.webauthn_enable_softtoken` to `true` — that routes requests to Gecko's
  silent *virtual test authenticator* (success with no system prompt, nothing stored in GPM).
  Gecko's Android defaults are already correct, including
  `security.webauthn.webauthn_enable_android_fido2.residentkey = true`.

### 13. Compact bar — tap to expand, no horizontal swipe

When the bar is in compact mode (`barCollapseCompact = true`), `BarPill.interactive` becomes
`false` after the bar crosses halfway-to-compact. Compact mode is tap-to-expand only — horizontal
tab switching is intentionally disabled there to prevent accidental tab changes on a narrow pill.

### 14. Logins DB key is an appservices **JWK**, not raw bytes

`PlacesLoginsStorage`'s `KeyManager.getKey()` MUST return the UTF-8 bytes of a key string produced by
appservices' `mozilla.appservices.logins.createKey()` (a `{"kty":"oct",…}` JWK). Appservices does
`std::str::from_utf8(get_key())` then parses it as a JWK; **32 raw random bytes fail that parse with
`InvalidKey`**, which silently throws on every encrypt/decrypt — the store then never persists
anything (local add AND every incoming synced record fail), so the passwords UI stays empty. The JWK
is resolved eagerly (before the DB is opened) and kept sealed under an AndroidKeyStore AES-GCM
wrapping key; regenerating it wipes the old, now-undecryptable DB. Do NOT swap it back to a
self-generated AES key.

### 15. Autofill needs the runtime autocomplete delegate — not just the engine flags

Login/credit-card autofill only works because `LuminaBrowserComponents` sets
`runtime.autocompleteStorageDelegate = GeckoAutocompleteStorageDelegate(ccDelegate, loginDelegate)`.
`engine.settings.loginAutofillEnabled` / the `extensions.formautofill.creditCards.*` prefs only tell
Gecko to *try* — without the delegate it has no store to read from or save to. Logins go through
`LuminaLoginStorageDelegate` → `PlacesLoginsStorage`; credit cards through mozilla-components'
`service-sync-autofill` (`AutofillCreditCardsAddressesStorage`, card numbers encrypted at rest, PAN
never decrypted for the list UI — only last4 is shown). **Credit cards have no CVC field and never
will**: GeckoView's `Autocomplete.CreditCard` model has no CVC slot (PCI-DSS forbids storing it), so
autofill covers number/name/expiry only. Passwords + credit cards both sync via the standard
sync15-E2E Firefox Sync engines (`SyncEngine.Passwords` / `SyncEngine.CreditCards`, the latter
configured with `AutofillCrypto` as its `KeyProvider`).

**The delegate is only half the wiring — the fill/save doorhangers are Compose prompts.** Attaching
the storage delegate makes Gecko *fetch* logins (`onLoginFetch`) when a login field is focused, but
Gecko then surfaces the actual autofill UI as a `PromptRequest.SelectLoginPrompt` (pick a saved login
to fill) and saving as a `PromptRequest.SaveLoginPrompt` (offer to persist a submitted credential).
These MUST be handled in `ui/PromptHandler.kt` — its `when (promptRequest)` block. If they fall
through to the `else` branch (which just logs `"Unhandled prompt: …"`), fetch still succeeds but **no
fill/save UI ever appears** and autofill looks completely dead even though logins are synced and
present. `SelectCreditCard` / `SelectAddress` are the equivalent doorhangers for the other autofill
kinds — same rule. `PromptHandler` is mounted in both surfaces (`LuminaOverlaySheets` for the browser,
`CustomTabRoot` for custom tabs); a new prompt type must be added to the one `when` to work in both.

**`ReaderPromptDelegate` must forward EVERY prompt callback it doesn't itself handle.** Reader mode
(`ui/reader/ReaderExtractor.registerPromptDelegate`) installs its own `GeckoSession.PromptDelegate`
that **replaces** the session's delegate so it can intercept `onTextPrompt` for the `lumina-*` bridge
messages (reader/theme/OTP/link probes). Because it replaces (not chains before) the mozilla-components
`GeckoPromptDelegate`, any callback it does not override hits the no-op interface default and is
**silently dropped** — the wrapped delegate that raises the `PromptRequest`s above never runs. This is
exactly what made password autofill look dead: `onLoginSelect`/`onLoginSave` weren't forwarded, so the
`SelectLoginPrompt` was never raised even though `onLoginFetch` returned matches. Only `onTextPrompt`
needs interception; **all** other callbacks (`onLoginSelect/Save`, `onCreditCard*`, `onAddress*`,
`onAuthPrompt`, `onColorPrompt`, `onDateTimePrompt`, `onFilePrompt`, `onPopupPrompt`, `onSharePrompt`,
plus the alert/button/choice/beforeunload/repost ones) must pass through to `oldDelegate`.

**Fill flow (biometric + auto-submit):** `SelectLoginPrompt` renders `SelectLoginSheet` (Material 3
bottom sheet). Selecting a credential runs `BiometricGate.authenticate` (fingerprint/face or device
credential; a no-op that fills immediately when the device has no secure lock, so autofill is never
impossible to trigger — this is why `LuminaBrowserActivity` extends `FragmentActivity`, the only host
`androidx.biometric.BiometricPrompt` supports). On success it calls `onConfirm(login)` **and** injects
`buildLoginSubmitScript()` to press the form's login button; that script only acts on a form that
actually contains a filled `input[type=password]`, so it never submits a stray search box.

### 16. SMS one-time-code (2FA) autofill

Two halves wired through the `OtpAutofill` singleton (in `ui/content`): a JS **detector**
(`buildDetectScript`, injected per-load in `ContentHost` like the other probes) reports OTP fields
over the shared `window.prompt` bridge (routed in `ReaderExtractor`'s prompt delegate — the same
`lumina-*` channel as ThemeColorProbe/LinkRectProbe) using heuristics far broader than GBoard's
`autocomplete="one-time-code"`-only check; and a **filler** (`buildFillScript`) that types the code
in (handles split single-char boxes; uses the native value setter for React inputs). The SMS is read
via Play Services **SMS User Consent** (`OtpSmsController`, `play-services-auth-api-phone`) — **no**
SMS permission, a one-tap system consent dialog per code. `MainActivity` owns the SMS side
(`smsListenStarter` set in onResume, cleared in onPause); `LuminaRoot` owns the page side
(`codeFiller` injects into the selected session) plus the manual "paste code" fallback banner
(`OtpPasteBannerHost`). Do NOT gate receipt on `RECEIVE_SMS` — it was intentionally avoided (Play
Store restriction on SMS permissions).

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
| Sizing the JS bottom spacer to push content up / removing the native dynamic-toolbar clipping | Breaks the scroll-under-pill look and `fixed`/`sticky` bottom UI; the spacer is scroll room only, the native clipping owns the visual viewport (see #11) |
| Overriding `navigator.credentials` via JS or enabling `webauthn_enable_softtoken` | Fake/silent passkeys that never touch Google Password Manager; RPs reject the signatures |

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
