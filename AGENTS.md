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
  (data saver: state/DataSaverPolicy.kt + engine/DataSaverController.kt — see invariant #18)
  (element zap: ui/content/ElementZap.kt + state/ZappedElementsStore.kt — see invariant #19)
  (SSO chains: state/SsoFlow.kt + state/SsoFilteringHistoryDelegate.kt — see invariant #21)
  (blob: files: state/BlobFiles.kt + ui/content/BlobCapture.kt — see invariant #22)
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

**Identifier-first sign-ins are two offers on ONE document.** An enterprise IdP asks for the email,
then swaps the password field in with **no navigation** — so no load callback fires and nothing
re-injects the per-load detectors. `LoginFieldProbe` therefore reports a `Stage` (`user`/`pw`) and
keeps a **bounded MutationObserver** armed (only on pages that look like a sign-in, debounced, deduped
by field signature, self-disconnecting) so the password step is reported at all. `ProactiveLoginAutofill`
keys its de-nag on host **and** stage — a host-only key lets the email step eat the single offer and
silently suppresses the password step, which looks exactly like autofill being dead. A hand-pick at the
identifier step submits it and carries to the password step (`StageContinuationMs`), so one sign-in
costs one gesture. The identifier step is acted on only for `SsoFlow.isEnterpriseIdp` hosts. Verified
end-to-end in `tools/sso-autofill-harness` (the `entra` fixture reports **nothing at all** against the
pre-fix revision). Do NOT make the submit/fill scripts visibility-blind: every identifier-first IdP
keeps a hidden decoy `input[type=password]` in the DOM during step one.

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

`ui/content/DarkCutoutAssets.kt` is generated the same way (from `tools/dark-cutout-wasm/` via
`build.sh`) and carries the base64 WASM cutout kernel — the one unreadable blob we ship. Edit
`src/lib.rs`/`worker.js`/`driver.js`, never the `.kt`. It is kept auditable deliberately, and the
three guards are load-bearing: `DarkCutoutAssetsTest` pins the wasm sha256 + the **absence of an
import section** (no imports = the kernel cannot reach the host at all) + the export list;
`./build.sh --check` rebuilds from Rust and fails on drift (the build is byte-reproducible);
`gen.js` refuses to emit a kernel that gained imports. Changing the kernel means a reviewed
one-line `EXPECTED_WASM_SHA256` diff — do NOT "fix" a failing pin by pasting in the new hash without
knowing why the bytes moved.

### 18. Data saver blocks REQUESTS — never `setAllowJavascript(false)`

When the OS Data Saver is restricting us **on a metered connection** (`state/DataSaverPolicy.kt`
`isDataSaverActive` — both halves required; `restrictBackgroundStatus` alone stays `ENABLED` on
unmetered Wi-Fi), `engine/DataSaverController.kt` pushes a policy to the built-in blocking extension
(`assets/extensions/datasaver/`, hand-written like `passkey/` — **not** generated by
`extension/build.js`, unlike `reader/`; see #17). It cancels subresources in a blocking
`webRequest.onBeforeRequest` listener.

**Do NOT "simplify" this to `GeckoSessionSettings.setAllowJavascript(false)`** (nor the global
`javascript.enabled`). It is the obvious lever and it breaks the app, not just the page: disabling
script at the docshell also stops our own `javascript:` injection — the viewport spacers (#11), the
theme-color probe, the AMO/OTP/login/form-field detectors, and the reader extractor's fallback path.
Cancelling *requests* leaves page-world script enabled, so all of that keeps working while the bytes
never arrive. (There is no CSS equivalent to reach for either: `permissions.default.stylesheet` is
gone from modern Gecko — verified absent from `libxul.so` — so the extension is the only route.)

Three tiers, and the boundaries are load-bearing:
- `blockAll` — `image`/`media`/`font`, any party. Degraded but readable.
- `blockThirdParty` — `script`/`object`, cross-site only. **First-party script is deliberately left
  alone**; blocking it breaks the site outright for a fraction of the savings that third-party
  ad/analytics script already gives.
- `aggressiveTypes` (`script` + `stylesheet`, first-party included) — applied **only** to hosts in
  `state/ReaderHostEvidence.kt`, i.e. hosts that have actually produced a reader article. Do NOT
  widen this to all hosts: unstyled HTML is only acceptable because reader mode renders those pages
  from extracted text.

`NEVER_BLOCK` in `datasaver.js` (`main_frame`/`sub_frame`/`xmlhttprequest`/`websocket`/…) and the
fail-open `onDisconnect` handler are both pinned by `DataSaverExtensionAssetTest`, which also checks
every key `DataSaverPolicy.toJson()` writes is read by the script — the two sides are joined only by
strings over a native port, so a rename otherwise compiles and silently blocks nothing.

**Do NOT add a "block, try to extract, reload if reader fails" flow.** That pays a second full
navigation on every page reader can't handle (most of the client-rendered web). The evidence store
exists precisely so the aggressive tier is decided *before* navigating; `ReaderManager.outcomeListener`
feeds it both extraction outcomes, including the silent auto pass.

### 19. Element zap — the stylesheet is the source of truth, and the picker must not cancel scrolling

Tap-to-hide (`ui/content/ElementZap.kt` + `state/ZappedElementsStore.kt`, entered from the aA sheet's
"Hide an element"). Four load-bearing details:

- **The picker never hides the element itself.** It reports the selector; the host persists it and
  `ContentHost` re-injects a `<style>` of `display:none!important` rules (per-load `injectZaps()` plus
  a live `LaunchedEffect`). If the picker also set an inline style, "Restore hidden elements" would
  clear the store and the element would *stay* hidden until a reload.
- **Do NOT `preventDefault()` on `touchstart`/`touchmove` in the picker.** Cancelling those also
  cancels the native scroll gesture, and the element you want to zap is usually further down the page.
  The picker only `stopPropagation`s the down/up events (hiding them from the page's own handlers) and
  hard-swallows the synthesized `click`/`contextmenu` — that is what would otherwise follow a link.
- **The picker's control surface is a nonce-named global** (`__tangentZap_<BridgeGuard.nonce>`), driven
  by the banner's Widen/Hide/Cancel scripts. A fixed name would let any page script commit or cancel a
  zap; same exposure the bridge nonce (#11/#15) exists to close.
- **Stored selectors go through `isSafeZapSelector` (allowlist) on write AND read.** They come from
  page-authored ids/classes and are spliced verbatim into a rule's text, so `{`/`}`/`;`/`@`/comments
  must never survive, and `html`/`body`/`*` are rejected outright (they'd blank the site). Pinned by
  `ElementZapTest`.

### 19a. Web dark mode — the already-dark detector is ONE shared block, in three places

`darkDetectJs` in `WebDarkMode.kt` decides whether Tier 1 (the site's own dark theme, honored via the
forced `prefers-color-scheme: dark`) already handled the page. A false negative inverts an
already-dark page — the most visible way the feature breaks. It is inlined into `buildApplyScript`
**and** `buildDynamicScript`, and hand-mirrored in `assets/extensions/darkmode/darkmode.js` (a
document_start content script cannot import it); `WebDarkModeScriptTest` pins that all three agree,
thresholds included. Four load-bearing properties, each reproduced in `tools/dark-mode-harness`
(`node detect.js`; four of seven fixtures fail against the pre-change revision):

- **Sample the PAINTED surface, not just `body`/`html`.** Sites overwhelmingly leave the document
  background at the light default and paint their real surface on a full-bleed wrapper, so the
  declared background reads light however dark the page looks. `paintedLum` hit-tests five viewport
  points and walks up to the first opaque background at each.
- **That probe returns the LIGHTEST surface found, never the average.** It encodes unanimity: a light
  page with a dark hero filling the viewport must still be inverted. Do NOT "improve" it to a mean —
  that leaves light sites light with dark mode on.
- **No opaque background does NOT mean white.** Under `color-scheme: dark` (or `light dark`, which the
  forced preference resolves to dark) the *UA* paints the canvas dark. `schemeIsDark` reads the CSS
  property and the `<meta>`; this is how a large share of sites ship dark mode.
- **Measure with our own class OFF** (`pageIsDark` removes `tangent-dark-fallback` and restores it in
  a `finally`). The class puts an opaque `#fafafa` background and `color-scheme:dark` on `<html>`, so
  measuring with it on reads our own output back and the filter latches on forever.

### 20. Web dark mode's dynamic tier — read/write split, resumable walk, exact teardown

`ui/content/WebDarkMode.kt`'s `buildDynamicScript` recolors per element. Four properties are
load-bearing; each was a real "some elements stay light" or "won't turn off without a refresh" bug,
and each is pinned by `WebDarkModeScriptTest` **and** reproduced end-to-end in
`tools/dark-mode-harness` (all seven fixtures fail against the pre-rewrite revision — replay with
`WEBDARKMODE_KT=<old file> node run.js`):

- **`visit()` must never write.** It only appends `(element, property, value)` triples that `flush()`
  applies after the chunk. Interleaving a `getComputedStyle` read with a `style.setProperty` write
  forces a style recalc per element — the single biggest cost in the tier.
- **The walk is resumable and frame-budgeted, NOT capped per pass.** Do NOT reintroduce
  `Math.min(all.length, CAP)`: the old form restarted at index 0 every pass, so everything past
  element 8000 was *never* darkened however many passes ran.
- **Coverage means shadow roots and same-origin iframes too** (`adoptRoot`/`adoptFrame`), plus
  re-reading elements on `class`/`style` mutation — a first-visit-only latch keeps a stale recolor
  forever when a site swaps classes after load. Our own writes are counted in `__lumDynPend` so the
  observer can tell them from the page's and not loop.
- **Teardown restores, it does not strip.** Every write records the element's prior inline value
  (`__lumDynOrig`, registered in `window.__lumDyn.touched`) and `buildRemoveScript` puts exactly that
  back. Do NOT go back to blanket-`removeProperty` over `getElementsByTagName('*')` — it wiped colors
  the *site* set inline, so the page stayed visibly wrong until a reload (and it never saw inside
  shadow roots). The brute-force sweep remains only as a fallback for pages injected by an older build.

Color math is memoized by color string; that is where a "port it to WASM for speed" instinct should
go instead. The DOM/CSSOM calls that dominate this walk have no WASM equivalent, so a port could only
take the arithmetic while adding a crossing per element to do it — see `tools/dark-mode-harness/README.md`
for the measured numbers.

### 21. SSO chain hops are infrastructure, not destinations — but they are NOT skippable

`state/SsoFlow.kt` recognises the transient hops of a federated sign-in chain (`login.microsoftonline.com`
→ IdP → MFA → SAML-POST-back). Three consumers treat them as plumbing: `SsoFilteringHistoryDelegate`
(wrapped inside `DeArchivingHistoryDelegate` in `Engine.kt`) keeps them out of Places; `commitGoBack`
in `TangentRoot` collapses a contiguous run via `goToHistoryIndex`; `ContentHost` skips most per-load
injectors on them.

- **Do NOT try to skip, prefetch, or synthesise a hop.** Every hop carries a server-minted single-use
  value (`SAMLRequest`/`SAMLResponse`, OAuth `code`, ESTS `ctx`) plus a `Set-Cookie` the next hop
  reads — hop N+1 cannot exist without executing hop N. Likewise **do NOT** add background session
  renewal: Entra sessions here die on a Conditional Access *sign-in frequency* timer, which is
  absolute and deliberately does not reset on activity. There is no client-side state that renews it.
- **The OTP and login detectors MUST stay armed on auth hops.** The pages a chain actually *stops* on
  are the password prompt and the MFA-code prompt — exactly what `injectOtpDetector` /
  `injectLoginDetector` exist for (#15, #16). Gating them off with the rest is what makes autofill
  look dead mid-login. Conversely form-field memory must **never** run on an auth hop.
- **A settled auth hop still gets `injectSpacer()`** (#11). Only the *transient* FCP path skips it;
  an interactive sign-in page that renders under the status bar is a worse bug than the injection
  cost. It gets it with `suppressDarkMode = true`: the document_start pre-paint extension (#20)
  inverts every page before we know what it is, so on a chain that reads as a burst of half-recolored
  IdP pages. The live dark-mode `LaunchedEffect` in `ContentHost` guards on the same predicate and is
  deliberately **not** keyed on `pageUrl` — keying it there re-runs Tier-2 on every navigation.
  **Dark mode is suppressed only while a hop is TRANSIENT.** `scheduleAuthDarkModeSettle` in
  `ContentHost` re-applies it once the hop has stayed put for `AuthSettleDarkModeDelayMs` (just under
  the cover's own `SettleGraceMs`, so the recolor lands *under* the cover) and records the URL in
  `SsoSettledPage`, which the live `LaunchedEffect` also honours so a tab swap doesn't strip it. A
  password prompt left glaring white in a dark browser is the one place the suppression trade-off
  inverts — do NOT re-broaden the guard to all auth URLs.
- **The "Completing sign-in" cover uncovers on a SETTLED document, never on a host match.**
  `ui/content/SsoChainOverlay.kt` hides the chain's churn, but a chain is not uniformly transient:
  the password prompt and the Microsoft Authenticator number-matching screen *stop and ask*, and the
  latter requires **reading a two-digit number off the page**. So visibility is driven by whether the
  document settles (still loaded `SettleGraceMs` later ⇒ interactive ⇒ uncover and remember it), plus
  a landing grace and a hard ceiling. Do NOT "simplify" this to "cover while the host is an IdP" —
  that bricks the login it was meant to smooth. Its progress bar is calibrated from
  `state/SsoChainStats.kt` (an EMA of past hop counts, keyed by the chain's **first** auth host) and
  is deliberately indeterminate on a provider's first login rather than showing a guessed number.
  It is composed **before** the address sheet and the `shouldShowUi` chrome block in `TangentRoot`,
  so the pill/toolbar draw over it and stay usable — a chain can stall on a slow IdP, and trapping
  the user until it finishes is worse than the flashing. Because the chrome composes later it also
  wins hit testing over the cover's pointer-swallowing. Moving this composition later re-traps them.
  Consequence: switching tabs mid-chain must NOT be read as the chain completing — `chainTabId` /
  `chainTainted` abandon the measurement instead of recording a fragment.
- **`isAuthUrl` is deliberately narrow.** A false positive silently eats a history entry, so a bare
  `/login` path is not enough: a URL qualifies only on an identity-only host or an unambiguous
  federation marker. `SsoFlowTest`'s `notAuth` cases are the load-bearing half — widen the matcher
  only with a matching negative test.
- **Unattended autofill uses `isEnterpriseIdp`, NOT `isAuthUrl`.** `isAuthUrl` is tuned for cosmetic
  calls where a false positive is cheap, so it accepts `login.<anysite>` and any host carrying a
  federation marker — and that marker rides on the **service provider's** host mid-chain, so reusing
  it would arm password-filling across the whole web. `isEnterpriseIdp` matches only the curated
  identity-only hosts plus `idp`/`sso`/`adfs`/`shibboleth` labels, https-only, ignoring path/query.
  Do NOT collapse the two predicates.
- **Filling a password with nobody present needs all four gates** (`state/AutofillTrustStore.kt`):
  enterprise IdP, **exactly one** matching credential, ≥`AlwaysAutofillThreshold` prior *hand*-fills
  of that credential, and an explicit per-credential "Always autofill" tap. Unattended fills
  deliberately do **not** increment the counter — trust is only ever built by the user. This is a
  narrow exception to #15, not a relaxation of it: everything else still goes through the
  biometric-gated sheet.

### 22. A `blob:` URL must be captured FROM the blob document, and only from it

`blob:https://site.com/<uuid>` is not an address — it is a handle into one document's memory. Nothing
native can fetch it, it dies with the document that minted it, and shown raw it tells the user
nothing. `state/BlobFiles.kt` + `ui/content/BlobCapture.kt` copy the bytes into `cacheDir/blobs/` and
index them; three consumers read that index: `compactHost` (address bar shows "PDF file"),
`GeckoTabRepository.restoreOrSeedBlank` (a restored blob tab loads the cached `file://` copy) and the
save affordances (`BlobFileBanner` + the aA sheet row).

- **The capture window is the blob document's own lifetime.** Only same-origin script can read a blob
  URL, and the blob document qualifies precisely because it was *loaded from* the blob. So capture is
  injected on arriving at a blob URL — do NOT defer it to when the user asks to save (by then the
  bytes may be unreachable), and do NOT try to fetch a blob URL from Kotlin.
- **`begin` is refused unless the URL is the one the tab is showing** (`BlobCapture.arm`, set by
  `ContentHost` at injection time). Without it, a script that got hold of the bridge nonce could
  stream blobs of its own choosing into our cache.
- **A restored blob tab drops its engine session state.** Replaying Gecko's history would navigate
  straight back to the dead blob URL, undoing the substitution.
- Bytes cross the bridge in base64 chunks written straight to disk, with the page yielding between
  chunks — do NOT collapse this to one message (the bridge is synchronous; a big file would freeze
  the page and hold the whole thing in memory twice).

The most-violated mistakes are the bolded **do NOT** lines in the invariants above (esp. #1 `render()`
from `AndroidView.update`, #2 `startDragImmediately = true`, #4 `snapTo` on hot path, #6
`EngineSession.captureThumbnail`, #11 JS injection without the guard / spacer sizing, #12
page-world `navigator.credentials` shim (the softtoken AND the isolated-world Bitwarden override are
sanctioned pref-gated paths — see #12), #17
editing the generated `assets/extensions/reader/extractor.js` instead of `extension/src`, #19
`preventDefault` in the zap picker / hiding from the picker instead of the stylesheet, #20 writing
from the dark-mode read phase / re-capping the walk / stripping instead of restoring on teardown,
#21 gating the credential detectors off on auth hops / trying to skip or renew an SSO chain).

## Testing

Unit tests: `app/src/test/` (JVM via `./gradlew :app:test` if no Android framework); device tests:
`app/src/androidTest/`. `normalizeUrl`/`compactHost` have JVM tests in
`app/src/test/java/com/firebolt9907/tangentbrowser/UrlNormalizationTest.kt`.

## Build notes

- Default dev build filters to `arm64-v8a` (~200 MB APK). `-PsingleAbi=false` builds all ABIs.
- Release signing uses the local debug keystore intentionally (not for distribution).
- `isMinifyEnabled`/`isShrinkResources` are `false` in release.
