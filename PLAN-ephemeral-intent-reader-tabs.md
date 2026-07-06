> **SUPERSEDED (2026-07).** External links no longer open as ephemeral "preview" tabs inside the
> main browser. They now open in a separate document-task `CustomTabActivity` as a mozilla-components
> custom-tab session (see `AGENTS.md` → "Two activities / external links"), which gives a native
> predictive-back exit to the calling app. The ephemeral-tab machinery described below
> (`addEphemeralTab`/`promoteTab`/`discardEphemeralTab`, `_ephemeralTabIds`, the `isEphemeral`/"Open"
> button) has been removed. Kept for historical context only.

# Plan: Ephemeral (isolated) reader-preview tabs for external intents

## Goal

When Lumina is opened by an **external intent** (a link from another app: `ACTION_VIEW`,
or a shared URL via `ACTION_SEND`), the URL should open in an **ephemeral / isolated tab** that:

1. **Attempts reader mode** automatically (opens straight into reader when the page is eligible).
2. Is **not saved** to the persisted tab list, and behaves as a throwaway preview.
3. Is **promoted to a normal, saved tab only when the user taps an "Open" affordance in the tab bar.**
   Until then it is discarded when the user leaves it (see lifecycle rules below).

This is conceptually similar to a Chrome Custom Tab / "preview" surface: read the linked article
without polluting the user's real tab set, and keep it only if they choose to.

---

## Current architecture (verified anchors)

- **Intent entry point:** `MainActivity.handleIncomingIntent(intent)`
  (`app/src/main/java/com/firebolt9907/luminabrowser/MainActivity.kt:126`) →
  currently `app.components.tabRepository.addTab(url = url, title = "")` (line 129).
  `extractUrl` handles `ACTION_VIEW` (dataString) and `ACTION_SEND` (EXTRA_TEXT → first URL).
  Called from `onCreate` only on fresh start (`savedInstanceState == null`, line 84) and from
  `onNewIntent` (line 120-124).

- **Tab model / persistence:** `state/GeckoTabRepository.kt` wraps mozilla-components `BrowserStore`.
  - `addTab(url, title)` (line 136) and `addPreparedTab(...)` (line 139) create tabs via `TabsUseCases`.
  - `schedulePersist()` (line 97) writes a debounced snapshot of `store.state.tabs`
    (filtered to non-blank URLs) to disk via `TabPersistence` (`lumina_tabs.json`).
    **This is the single place that decides what is "saved."** Excluding a tab id here makes it
    unsaved.
  - `storeSubscription` (line 46) mirrors `store.state` into `_tabs` / `_selectedTabId` StateFlows
    and prunes `lastAccessById` for dead tabs — the pattern to copy for pruning an ephemeral-id set.
  - `TabPersistence` (`state/TabPersistence.kt`) is URL-only; ephemeral tabs simply must not appear
    in the snapshot passed to `save()`.

- **Reader activation:** `ui/reader/ReaderModeState.kt` `ReaderManager` (singleton, `instance`).
  - `requestReaderActivation(tabId)` adds to `pendingActivationTabs`; when extraction later calls
    `setReaderEligible(tabId, article)` the tab auto-activates. This is exactly the hook to "attempt
    reader mode" for a freshly opened tab.
  - Reader auto-engages by default on any eligible page unless the host is opted out
    (`SitePreferencesStore.isReaderAutoDisabled`); see the `LaunchedEffect(eligibleReaderTabs)` in
    `LuminaRoot.kt:422`. So for article pages reader will come up even without an explicit request,
    but requesting activation makes the intent case deterministic and covers the pending window.

- **Chrome / tab bar:** `ui/chrome/SafariBar.kt` (`fun SafariBar` line 28) → renders
  `BarExpanded` / `BarPill` / `BarCompact` / `BarEditing`. Reader state is already threaded through as
  `isReaderEligible` / `isReaderActive` (SafariBar params line 47-48; BarExpanded line 59-60). Add the
  "Open" affordance the same way. **Four `SafariBar(` call sites** in `LuminaRoot.kt` (lines 2786,
  2811, 2834, 2861) — the compact/expanded/editing variants; update the relevant ones.
  - ⚠️ Respect `AGENTS.md` invariants #2, #3, #12: `BarPill` uses `startDragImmediately = false`;
    do NOT gate `BarPill.pointerInput` on any new flag; compact mode is tap-only. Put the "Open"
    button in `BarExpanded` (near the Reload `IconButton`, BarExpanded.kt:205) so it doesn't touch the
    pill's drag machinery.

- **Grid tab list:** `ui/grid/Grid.kt` takes `tabs: List<GridTab>` (line 112). The list is built in
  `LuminaRoot.kt` around the `GridTab(` construction (line ~2304) from the repo's tab list.

- **Selection / new-tab plumbing:** `LuminaViewModel.onOpenNewTab(...)` (line 104),
  `selectCreatedTab(...)` (line 98); `LuminaRoot.openTabToRight(...)` (line 1001). Use these as
  reference for how a created tab id flows into selection.

- **Firefox Sync:** local open tabs are published to Firefox Sync via the mozilla-components Tabs
  sync engine, which reads `BrowserStore` directly (`sync/FirefoxSyncController.kt:90`,
  `GlobalSyncableStoreProvider.configureStore(SyncEngine.Tabs ...)`). Ephemeral tabs live in the
  store, so they may sync unless excluded. See "Open decision D4."

---

## Proposed design

### 1. Ephemeral registry in `GeckoTabRepository`

Add a tracked set of ephemeral tab ids (it already owns persistence, so this keeps the "what is
saved" logic in one place):

```kotlin
private val _ephemeralTabIds = MutableStateFlow<Set<String>>(emptySet())
val ephemeralTabIds: StateFlow<Set<String>> = _ephemeralTabIds.asStateFlow()

/** Create a throwaway tab for an external intent. Not persisted until promoted. */
fun addEphemeralTab(url: String, title: String = ""): String {
    // Single-ephemeral policy: discard any existing ephemeral tab first (see D3).
    _ephemeralTabIds.value.forEach { removeTab(it) }
    val id = tabsUseCases.addTab.invoke(
        url = normalizeUrl(url), selectTab = true, title = title,
        source = SessionState.Source.External.ActionView(null), // or Internal.NewTab; see note
    )
    _ephemeralTabIds.update { setOf(id) }
    return id
}

/** Promote the ephemeral tab to a normal saved tab. */
fun promoteTab(id: String) {
    if (_ephemeralTabIds.value.contains(id)) {
        _ephemeralTabIds.update { it - id }
        schedulePersist()
    }
}

/** Discard an unpromoted ephemeral tab entirely. */
fun discardEphemeralTab(id: String) {
    if (_ephemeralTabIds.value.contains(id)) {
        _ephemeralTabIds.update { it - id }
        com.firebolt9907.luminabrowser.ui.reader.ReaderManager.instance.clearReaderState(id)
        removeTab(id)
    }
}
```

Then:
- In `schedulePersist()` (line 97-117) add `it.id !in _ephemeralTabIds.value` to the `.filter { }`
  so ephemeral tabs never reach disk.
- In `storeSubscription` (line 46), prune the ephemeral set to live tab ids (mirror the
  `lastAccessById` cleanup at line 59-62) so a closed ephemeral tab drops out.
- `addEphemeralTab` / `promoteTab` are new methods on the concrete class. Either add them to the
  `TabRepository` interface, or have callers cast (`components.tabRepository as? GeckoTabRepository`).
  The interface is cleaner; MockTabRepository was removed, so only the real impl exists.

### 2. Route external intents through the ephemeral path

In `MainActivity.handleIncomingIntent` (line 126-130):

```kotlin
private fun handleIncomingIntent(intent: Intent?) {
    val url = extractUrl(intent) ?: return
    if (app.components.firefoxSyncController.handleOAuthRedirect(url)) return
    val repo = app.components.tabRepository as? GeckoTabRepository
    if (repo != null) {
        val id = repo.addEphemeralTab(url = url, title = "")
        ReaderManager.instance.requestReaderActivation(id)   // attempt reader mode
    } else {
        app.components.tabRepository.addTab(url = url, title = "")
    }
}
```

Note on cold start: on a fresh launch via intent, `restoreOrSeedBlank` may run and seed a blank tab.
Confirm ordering so the ephemeral tab is added after restore and ends up selected (it is created with
`selectTab = true`). If a leftover blank `about:blank` tab is present, consider removing it (reuse
`dismissBlankTabAndReturn` semantics) — verify empirically.

### 3. "Open" affordance in the chrome bar

- In `LuminaRoot`: collect `val ephemeralTabIds by components.tabRepository.ephemeralTabIds
  .collectAsStateWithLifecycle()` and derive
  `val isSelectedTabEphemeral = selectedTabId != null && selectedTabId in ephemeralTabIds`.
- Thread two new params through `SafariBar` → `BarExpanded`:
  `isEphemeral: Boolean = false` and `onOpenTab: () -> Unit = {}`.
- In `BarExpanded`, when `isEphemeral`, render an **"Open"** button (text or icon+label) near the
  Reload `IconButton` (BarExpanded.kt ~line 205). `onClick = onOpenTab`.
- Wire `onOpenTab = { selectedTabId?.let { components.tabRepository.promoteTab(it) } }` at the
  `SafariBar(` call sites (lines 2786/2811/2834/2861 — the expanded variant at minimum).
- Follow the pill invariants: keep this inside `BarExpanded`'s button row; do not add it to `BarPill`'s
  draggable/pointerInput path.

Optional polish: also style the bar (e.g., a subtle "Preview" label or accent) while ephemeral, and/or
show a one-line hint ("Tap Open to keep this tab"). Flag with the user.

### 4. Lifecycle / discard rules

Implement in `LuminaRoot` (or the repo) — an ephemeral tab that is never promoted must not linger:

- **On selection moving away:** if the previously-selected tab was ephemeral and still unpromoted,
  discard it. Watch `selectedTabId` changes; when it changes away from an ephemeral id that is still in
  the set, call `discardEphemeralTab(oldId)`. Be careful around the single-EngineView / snapshot
  invariants in `AGENTS.md` (#1, #6-8) — discarding a background tab is fine, but do not interfere with
  the leaving-tab snapshot capture in `ContentHost`. Prefer discarding after the switch settles.
- **On a new external intent:** `addEphemeralTab` already discards the prior ephemeral tab.
- **On promote:** tab becomes normal; nothing to discard; persistence now includes it.
- **Reader cleanup:** `discardEphemeralTab` calls `ReaderManager.clearReaderState(id)`.
- **Process death:** ephemeral tab isn't persisted, so it's simply gone on next launch — acceptable.

### 5. Grid visibility — see Open decision D1.

---

## Status: implemented

All recommended decisions below were taken: ephemeral tab stays visible in the grid but is
excluded from persistence (D1); only the "Open" button promotes it (D2); at most one ephemeral tab,
a new intent replaces the prior (D3); Firefox Sync exclusion left out of scope for v1 (D4); "Open"
button rendered in `BarExpanded`, near Reload (D5).

Concrete changes:
- `GeckoTabRepository`: `_ephemeralTabIds` StateFlow + `addEphemeralTab`/`promoteTab`/
  `discardEphemeralTab`; persist filter excludes ephemeral ids; `storeSubscription` prunes dead ids.
  (`components.tabRepository` is the concrete `GeckoTabRepository`, so no interface change needed.)
- `MainActivity.handleIncomingIntent`: routes external intents to `addEphemeralTab` +
  `ReaderManager.requestReaderActivation`.
- `SafariBar`/`BarExpanded`: new `isEphemeral`/`onOpenTab`; "Open" button rendered when ephemeral.
- `LuminaRoot`: collects `ephemeralTabIds`, derives `isSelectedTabEphemeral`, wires `promoteTab`,
  and discards the prior ephemeral tab on selection-change.

## Open decisions (confirm with the user before/while implementing)

- **D1 — Does the ephemeral tab appear in the tab grid?**
  - (Recommended) Keep it visible in the grid but excluded from persistence + sync; "not saved" means
    it won't survive restart and shows the "Open" prompt. Simplest, least surprising.
  - Or: hide it from the grid entirely (truly isolated); then opening the grid + selecting another tab
    discards it. Matches "isolated" more literally but hides the surface. If chosen, filter ephemeral
    ids out of the `tabs` list feeding `Grid.kt`.

- **D2 — What promotes the tab, besides the "Open" button?**
  - (Recommended) Only the explicit "Open" button.
  - Alternatives to consider: promote automatically when the user navigates within the tab (types a
    URL, follows a link), or when they interact past reader. Keep off unless requested.

- **D3 — Multiple external intents / how many ephemeral tabs at once?**
  - (Recommended) At most one ephemeral tab; a new intent replaces (discards) the previous one.

- **D4 — Firefox Sync:** Should ephemeral tabs be kept out of the synced/remote tab list? The Tabs sync
  engine reads `BrowserStore` directly, so excluding them may be non-trivial. Recommended: treat as out
  of scope for v1 (accept that an unpromoted ephemeral tab may briefly sync) unless the user wants strict
  isolation — then investigate filtering at the sync layer.

- **D5 — "Open" placement/label:** button in `BarExpanded` labeled "Open" (recommended), vs. a banner
  above the bar, vs. an entry in the aA sheet. Confirm copy/placement.

---

## Suggested implementation order

1. `GeckoTabRepository`: ephemeral set, `addEphemeralTab`/`promoteTab`/`discardEphemeralTab`,
   persist-filter, prune in `storeSubscription`. Add methods to `TabRepository` interface.
2. `MainActivity.handleIncomingIntent`: route intents to `addEphemeralTab` + `requestReaderActivation`.
   Verify cold-start ordering vs. `restoreOrSeedBlank`.
3. Thread `isEphemeral` / `onOpenTab` through `SafariBar` → `BarExpanded`; render "Open"; wire
   `promoteTab` at the call sites.
4. Lifecycle: discard-on-leave + reader cleanup.
5. (Per D1) grid filtering if hiding ephemeral tabs.
6. Manual verification (below).

## Manual verification

- `adb shell am start -a android.intent.action.VIEW -d "https://arstechnica.com/<an-article>"
  -n com.firebolt9907.luminabrowser/.MainActivity` → opens in reader, shows "Open", not persisted.
- Kill/relaunch app without pressing "Open" → tab is gone.
- Press "Open" → tab persists across relaunch and appears as a normal tab.
- Fire a second intent while an ephemeral tab is open → previous ephemeral tab is replaced.
- Switch to another tab without promoting → ephemeral tab is discarded; reader state cleared.
- Share a URL via `ACTION_SEND` → same behavior.
- Build/deploy: `./gradlew :app:installRelease` (release build uses the debug keystore; arm64-only by
  default — see `AGENTS.md` build notes). Confirm no regressions to the single-EngineView, snapshot,
  and BarPill-drag invariants in `AGENTS.md`.

## Files likely to change

- `state/GeckoTabRepository.kt` (registry, persist filter, prune)
- `state/TabRepository.kt` (interface methods) — verify exact path/name
- `MainActivity.kt` (intent routing + reader request)
- `ui/chrome/SafariBar.kt`, `ui/chrome/BarExpanded.kt` (the "Open" button + params)
- `ui/LuminaRoot.kt` (collect ephemeral state, wire `onOpenTab`, lifecycle discard, optional grid filter)
- (Maybe) `ui/reader/ReaderModeState.kt` — no change expected; reuse `requestReaderActivation`.
