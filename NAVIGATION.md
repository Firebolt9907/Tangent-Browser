# Tangent Browser — Navigation Map

A table-of-contents for reading the codebase yourself. Each entry: one-line description,
the file(s), the entry-point class/function to read first, and where to start a trace.
See `AGENTS.md` for the invariants that back these systems.

---

## ⭐ Priority 1 — Tab management & UI/session decoupling

**One-line:** UI tab list (`TangentTab`) is a lightweight projection of mozilla-components'
`BrowserStore`; live GeckoSessions are lazily created on selection and LRU-suspended down to ~4,
so most "tabs" are session-less placeholders.

**Files:**
- `state/TabRepository.kt` — the interface (the UI-facing contract)
- `state/TangentTab.kt` — the placeholder model (just id/url/title, **no session**)
- `state/GeckoTabRepository.kt` — implementation that projects `BrowserState → List<TangentTab>`
- `state/BackgroundTabSuspender.kt` — the LRU that tears live sessions back down
- `ui/TangentRoot.kt` — where a selected placeholder gets forced a live session
- `state/TabPersistence.kt` — disk persistence + 72h auto-discard

**Entry points to read:**
- `GeckoTabRepository.storeSubscription` (the `toTangentTabs()` projection) and `restoreOrSeedBlank`
- `BackgroundTabSuspender.onStateChange` → `liveBackgroundTabs` → `suspendOverBudgetTabs`
  (`DEFAULT_KEEP_ALIVE_BACKGROUND = 3`)
- `TangentRoot.kt` `LaunchedEffect(actualSelectedTabId, selectedEngineSession == null)` at **line 1804**
  — the "selected tab must have a live session" guarantee, dispatching `EngineAction.CreateEngineSessionAction`

**Trace end-to-end, in order:** `TangentTab.kt` → `TabRepository.kt` → `GeckoTabRepository.kt`
(projection + selection) → `TangentRoot.kt:1804` (session creation) → `BackgroundTabSuspender.kt`
(session destruction). Mental model: **selection creates a session (TangentRoot), quiet-period +
over-budget destroys it (Suspender); the store is the single source of truth, `TangentTab` is a
read-only shadow.**

---

## ⭐ Priority 2 — Thumbnail / snapshot system for transitions

**One-line:** `EngineView.captureThumbnail` snapshots each page into a URL-keyed memory+disk LRU;
during tab switches / grid / predictive-back the live GeckoView is covered by a bitmap `Image`,
and the cover is cleared only on a real paint signal so the user never sees a blank Gecko surface.

**Files:**
- `ui/content/ContentHost.kt` — **capture** site (only place `captureThumbnail` and `render()` are called)
- `state/TabSnapshotCache.kt` — two-tier (RAM LRU + JPEG disk) store, `v2:` key
- `ui/TangentRoot.kt` — **consumer**: sticky covers, incoming-snapshot swap during gestures
- `ui/grid/GridModel.kt` (`SnapshotTracker`, `CardCropShape`) + `ui/grid/Grid.kt` — grid thumbnails

**Entry points to read:**
- `ContentHost.captureSnapshot` / `storeSnapshotAsync`, and the
  `DisposableEffect(engineView, engineSession, tabId)` at **line 253** — the *single rebind site*
  (`engineView.render(session)`) whose `onDispose` captures the leaving tab
- `TabSnapshotCache.get` / `put` / `snapshotKey` (the `v2:` prefix, AGENTS #8)
- `TangentRoot.kt`: `snapshotFor` (line 735), `clearStickyCover` / `fadeOutStickyCover` (607/623),
  the `incomingSnapshotBitmap` block (~1128–1300), and `currentSnapshotRequestId` /
  `SnapshotRequestPurpose` (the "capture exactly what's on screen now" request path)

**Trace end-to-end, in order:** `ContentHost.kt` capture path (`captureSnapshot` → `storeSnapshotAsync`)
→ `TabSnapshotCache.kt` (`put`/`get`) → `TangentRoot.kt` (`snapshotFor` → cover `Image`s ~2305/2342/2362
+ sticky-cover lifecycle). Invariants: **render() lives in exactly one `DisposableEffect` (AGENTS #1),
and covers clear on paint, not on URL change (AGENTS #7).**

---

## The rest — features & subsystems

### App / DI / entry points
- **DI graph** — Central `TangentBrowserComponents` wiring engine, store, use-cases, repos, controllers.
  → `engine/Engine.kt` (`TangentBrowserComponents`, line 40) + GeckoView prefs (~line 284). Start here for the object graph.
- **App + activities** — Process init, trim-memory, the two activities.
  → `TangentApplication.kt`, `MainActivity.kt` (`ActivityDelegate`), `CustomTabActivity.kt`.
- **Root composable** — ~3700-line gesture/state orchestrator for the whole browser.
  → `ui/TangentRoot.kt` (`fun TangentRoot`, line 212).
- **ViewModel** — Holds repository-backed UI state. → `state/TangentViewModel.kt` (`TangentViewModel`).

### Chrome / address bar
- **Safari-style bar** — The pill/expanded/compact/editing bar states.
  → `ui/chrome/SafariBar.kt` (`SafariBar`), `BarPill.kt`, `BarExpanded.kt`, `BarCompact.kt`, `BarEditing.kt`.
- **Address suggestions** — Merges search/history/bookmark suggestions as you type.
  → `state/AddressSuggestionProvider.kt` (`addressSuggestions`), `state/SearchSuggestionProvider.kt`, UI in `ui/chrome/AddressSuggestions.kt`.
- **Search engines** — Enum of engines + search/suggest URL builders. → `state/SearchEngine.kt` (`enum SearchEngine`).
- **"aA" page menu** — Per-page tools sheet + "open in app" resolver.
  → `ui/chrome/AaSheet.kt`, `AaSheetAppResolver.kt`, `AaSheetComponents.kt`.
- **IME control** — Drives the keyboard-tracking editing sheet. → `ui/chrome/ImeSheetController.kt` (`ImeSheetController`).
- **Find in page** — → `ui/chrome/FindInPageBar.kt`.

### Tab grid
- **Tab grid overlay** — Full-screen thumbnail grid with drag-to-open/close.
  → `ui/grid/Grid.kt` (`TabGridOverlay`, line 84) + geometry/state in `ui/grid/GridModel.kt` (`gridCellRect`, `SnapshotTracker`).

### Content / page integration
- **Page JS injection** — Viewport spacer, page zoom, YouTube clean player, AMO installer.
  → `ui/content/ContentHost.kt` (the `DisposableEffect` observer, line 328) + `PageBottomSpacer.kt`, `YouTubeCleanPlayer.kt`, `AmoAddonInstaller.kt`.
- **Pull-to-refresh** — Nested-scroll parent over GeckoView. → `ui/content/PullRefreshContainer.kt`.

### Reader mode
- **Reader extraction + view** — Extracts article content (optionally via Google Cache) and renders a custom reader.
  → `ui/reader/ReaderExtractor.kt` (`object ReaderExtractor`), state in `ReaderModeState.kt`
  (`ReaderManager`, `shouldRedirectToGoogleCache`), UI in `ReaderView.kt` (`ReaderView`), plus
  `ReaderPortHandler.kt`, `ReaderScrollCapture.kt`, `ReaderTableOfContents.kt`, `PageSpeaker.kt`. Start at `ReaderManager`.

### Performance / preloading
- **Background session suspension** — (see Priority 1) `state/BackgroundTabSuspender.kt`.
- **Speculative preconnect** — Warms connections for likely-next URLs. → `state/SpeculativePreconnector.kt`.
- **External-link preload** — Prewarms external links per policy. → `state/ExternalLinkPreloader.kt`, `state/PreloadPolicy.kt` (`isPreloadAllowed`).

### Sync (Firefox + Zen)
- **Firefox Sync controller** — 3 sync triggers (WorkManager / foreground poll / FxA push).
  → `sync/FirefoxSyncController.kt` (`FirefoxSyncController`, line 84). Start here.
- **FCM push entry** — → `sync/TangentFirebasePushService.kt`.
- **Encrypted logins store** — → `sync/PlacesLoginsStorage.kt`.
- **Zen read-only sync** — Custom sync15 client + crypto for Zen spaces.
  → `sync/zen/ZenWorkspaces.kt`, `Sync15StorageClient.kt`, `Sync15Crypto.kt`, `HawkAuth.kt`.
- **Synced-tabs UI + handoff** — → `ui/sync/SyncedTabsSheet.kt`, `ui/sync/HandoffBanner.kt`.

### Add-ons / WebExtensions
- **Add-on runtime** — Install/enable/action-popup wrapper over GeckoEngine.
  → `addons/AddonController.kt` (`AddonController`), `addons/AddonInstallUrls.kt`, UI in `ui/addons/AddonsSheet.kt`, `AddonPermissionSheet.kt`.

### Permissions / prompts
- **Site permissions** — Content/app permission request routing.
  → `permissions/SitePermissionController.kt` (`SitePermissionController`), UI in `ui/SitePermissionDialog.kt`, prefs in `state/SitePreferencesStore.kt`.
- **Gecko prompts** — JS dialogs, auth, file pickers. → `ui/PromptHandler.kt`, `ui/PromptDialogs.kt`.

### Custom Tabs (external links)
- **Custom Tab surface** — Slim external-link browser with the predictive-back exit animation.
  → `ui/customtab/CustomTabRoot.kt` (`CustomTabRoot`, line 86), service in `service/TangentCustomTabsService.kt`, shared parsing in `IntentUrls.kt`.

### Media
- **Video fullscreen / player** — → `ui/video/VideoFullscreenOverlay.kt` (`VideoFullscreenOverlay`), `ui/video/VideoPlayer.kt`.

### Sheets (management UIs)
- **Bookmarks / History / Downloads / Passwords / Settings**
  → `ui/bookmarks/BookmarksSheet.kt`, `ui/history/HistorySheet.kt`, `ui/downloads/DownloadsSheet.kt`,
  `ui/passwords/PasswordsSheet.kt`, `ui/settings/SettingsSheet.kt`. Shared shell: `ui/components/SheetScaffold.kt`.

### Cross-cutting
- **Haptics vocabulary** — → `ui/TangentHaptics.kt` (`TangentHaptics`, `DragRamp`).
- **Custom shape** — → `ui/shape/SquircleShape.kt`.
- **Theme** — → `ui/theme/Theme.kt`, `Color.kt`, `Type.kt`.
- **Keep-alive service** — → `service/BrowsingKeepAliveService.kt`.

---

## Suggested 2-hour reading order

1. **`AGENTS.md`** (10 min) — the invariants; read as this map's legend.
2. **`engine/Engine.kt`** (10 min) — `TangentBrowserComponents`: the object graph everything references.
3. **`state/TangentTab.kt` → `TabRepository.kt` → `GeckoTabRepository.kt`** (15 min) — the UI/session decoupling foundation.
4. **`state/BackgroundTabSuspender.kt`** (10 min) — the other half of decoupling (session teardown).
5. **`state/TabSnapshotCache.kt`** (10 min) — small, self-contained; storage layer for #6.
6. **`ui/content/ContentHost.kt`** (20 min) — the single EngineView: `render()` rebind + `captureThumbnail`
   + JS injection observer. Read the two `DisposableEffect`s at lines 253 and 328 carefully.
7. **`ui/TangentRoot.kt`** (30 min) — don't read linearly; jump to anchors: session-creation `LaunchedEffect`
   (1804), `snapshotFor` (735), sticky-cover lifecycle (607–730), tab-switch/incoming-snapshot block
   (1128–1300), the `ContentHost(...)` call site (1994), cover `Image`s (2305+).
8. **`ui/grid/GridModel.kt` → `ui/grid/Grid.kt`** (15 min) — how snapshots become the grid.
9. **Pick one vertical to finish**: `ui/reader/ReaderModeState.kt` (`ReaderManager`) or
   `sync/FirefoxSyncController.kt` (10 min) — enough to see how a feature subsystem plugs into the store.

Steps 1–7 (~105 min) give you the core architecture; 8–9 are the highest-value extras.
