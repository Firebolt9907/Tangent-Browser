# `state/` — Tabs, bar state, snapshots, prefs

Everything reactive that the UI reads. No Compose imports in this package — it's pure Kotlin + coroutines + `mozilla-components`.

## Files

### `TabRepository.kt`
Interface. Five operations (`selectTab`, `selectAdjacent`, `loadUrl`, `addTab`, `removeTab`) plus two observable flows (`tabs`, `selectedTabId`). Implemented by `GeckoTabRepository` in production and `MockTabRepository` for Compose previews.

### `TangentTab.kt`
`data class TangentTab(id, url, title)` — the UI-facing projection. `title` falls back to `compactHost(url)` when the page hasn't set one.

### `GeckoTabRepository.kt`
The real implementation. Owns:

- `tabs: StateFlow<List<TangentTab>>` — derived from `BrowserState.tabs` via `toTangentTabs()`.
- `selectedTabId: StateFlow<String?>` — mirrors `BrowserState.selectedTabId`.
- `previousSelectedTabId` — tracked manually so `dismissBlankTabAndReturn()` knows where to return to when the user backs out of a freshly-created blank tab.
- `seedIfEmpty(tabs)` — called once by `TangentBrowserComponents.init`.
- `dismissBlankTabAndReturn()` — back-handler hook in `TangentRoot`. No-op unless the current tab is `about:blank` *and* there's a known previous tab still in the store.

Three top-level utilities live here because everything URL-related goes through them:

- `normalizeUrl(input)` — empty → `about:blank`; explicit scheme → pass through; host-like (`apple.com`, `localhost`, `localhost:3000`) → prepend `https://`; everything else → Google search.
- `compactHost(url)` — `Uri.host` with `www.` stripped; falls back to the input if parsing yields nothing.
- `BrowserState.toTangentTabs()` (private extension) — projection used by the state-flow subscription.

### `MockTabRepository.kt`
In-memory implementation used by `@Preview` composables. Singleton via `companion object instance`. Useful for testing layout changes to `SafariBar` without spinning up Gecko.

### `TangentViewModel.kt`
`AndroidViewModel`. Combines `repo.tabs`, `repo.selectedTabId`, an internal `mode: BarMode` flow, and an internal `editingText: String` flow into a single `BarUiState` exposed as `state: StateFlow<BarUiState>`.

Exposes intent functions for the bar:

- `onUrlTap()` — switch to `Editing` mode, seed text from current URL.
- `onEditingTextChange(text)`, `onEditingSubmit()`, `onEditingCancel()`.
- `onSwitchTab(direction)` — `+1` next, `-1` previous.
- `dismissBlankTabAndReturn()` — delegates to the repo cast.
- `onCreateTabFromEdge()` — used by the swipe-past-last-tab gesture; opens `about:blank` and immediately switches to `Editing` mode.

Note: `TangentRoot` reads `BrowserState` *directly* from the components graph, not through the ViewModel. The ViewModel only powers the bottom bar.

### `BarUiState.kt`
`data class BarUiState(displayUrl, tabCount, mode, editingText, compactDisplayUrl, pageUrl)` + `enum BarMode { Expanded, Compact, Editing }`.

`displayUrl` is the compact host; `pageUrl` is the full URL kept around so editing-mode can seed the text field with the real thing.

### `TabSnapshotCache.kt`
Bitmap LRU (default 16 entries) backed by JPEG files in `cacheDir/tab_snapshots`. Thread-safe via `@Synchronized`.

- `get(id, url)` — non-blocking. Memory hit returns synchronously; miss schedules a background disk decode and returns `null`. Negative results are remembered to avoid repeated scheduling.
- `put(id, bitmap, url)` — stores in memory immediately, writes JPEG (quality 84) on `Dispatchers.IO`, bumps `version` so Compose recomposes.
- `remove(id, url)` — evicts memory + disk; flagged as a known miss.
- `version: StateFlow<Int>` — collected by `TangentRoot` so fresh captures appear during drag without waiting for unrelated state changes.

Disk keys use the page URL when available (`v2:https://...`) and fall back to `v2:tab:{id}` for URL-less pages. The `v2:` prefix ignores old cache files written before URL-timing was tightened.

`pruneDisk()` is throttled to once every 5 s.

### `SitePreferencesStore.kt`
`SharedPreferences`-backed, keyed by host. Returns `SitePreferences(topPaddingFix, zoomFactor)`; `zoomFactor` is clamped to `[0.5, 2.0]`. Emits the changed host on `updates: SharedFlow<String>` so `TangentRoot` can react to inactive-tab changes too.

`hostFromUrl(url)` is a convenience that yields `""` for `about:blank` / empty / non-host URLs — letting callers treat "no host" as "no prefs".

### `GlobalPreferencesStore.kt`
App-wide (not per-host) preferences in `SharedPreferences` — search engine, tab-view mode, wide-bar
layout, preload mode, keep-alive-in-background, the "tint pages" boost, and the rest of the settings
sheet's toggles. Each is exposed as a value + setter; consumers observe changes where they need to.

### `TabPersistence.kt`
Lightweight on-disk persistence of the open tab set (a small JSON file) so tabs survive app restarts.
Each tab stores its last-viewed time; `load` drops any tab older than `maxAgeMillis` (72h
auto-discard). This is what turned the old "reseed three demo tabs on cold start" behavior into real
tab restoration.

### `BackgroundTabSuspender.kt`
Bounds the number of live `GeckoSession`s so a window with many tabs stays responsive. mozilla-
components lazily creates a live session (its own content process) the first time a tab is rendered
and never tears it down; after visiting 20 tabs you'd have ~20 live content processes. This suspends
the least-recently-used ones back to saved state.

### Search — `SearchEngine.kt`, `SearchSuggestionProvider.kt`, `AddressSuggestionProvider.kt`
- `SearchEngine` — the user-selectable engine: its results URL (used by `normalizeUrl` for non-URL
  input) and its suggestion endpoint. Every `suggestUrl` returns the OpenSearch/"Firefox client" JSON
  array shape so one parser handles them all. `addressBarLabel(...)` decides what the bar shows.
- `SearchSuggestionProvider` — fetches live query completions from that endpoint; empty list on blank
  query or any network/parse failure so the sheet silently falls back to local suggestions.
- `AddressSuggestionProvider` — assembles the address-sheet suggestion list from search + local history
  + bookmarks; `AddressSuggestionKind` drives each row's leading icon.

### Preloading — `SpeculativePreconnector.kt`, `ExternalLinkPreloader.kt`, `PreloadPolicy.kt`
The speed-tuning layer (see the performance-tuning memory):
- `SpeculativePreconnector` — warms DNS + TCP + TLS to origins the user is likely to hit next, so the
  first request lands on an open connection (this + the enlarged Gecko HTTP cache is what makes
  revisits feel instant).
- `ExternalLinkPreloader` — for a Custom Tabs `mayLaunchUrl` signal, actually *loads* the page in a
  short-lived headless `EngineSession` so it's warm when opened.
- `PreloadPolicy` — gates the heavy full-page preloads by data-cost preference (`PreloadMode`);
  lightweight `speculativeConnect` warmups are always allowed.

### Tab groups — `TabGroups.kt`, `TabGroupsRepository.kt`
An **organizational label** over open tabs, independent of the engine — grouping is *not* cookie
isolation (see containers below).

- `TabGroups.kt` — `TangentTabGroup(id, name, color)` + `TabGroupColor` (GRAY / BLUE / CYAN / GREEN /
  YELLOW / ORANGE / RED / PINK / PURPLE). State-layer pure (no Compose); the UI maps each color to a
  swatch. Persisted by name; unknown/legacy names fall back to `BLUE`.
- `TabGroupsRepository.kt` — owns the groups as `StateFlow<List<TangentTabGroup>>`, with two-part
  persistence mirroring how tab state is already split: the **registry** (id/name/color) is persisted
  here to `tangent_tab_groups.json`, while **membership** rides per-tab in `TabPersistence`
  (`PersistedTab.groupId`) because live tab ids are reassigned on restore — `GeckoTabRepository`
  re-links each via `assignRestored`. A tab belongs to at most one group; it drops out automatically
  when closed (the repo observes the store, so no ghost membership). Empty groups are kept — they're
  user-managed and removed only via `deleteGroup`, so a group survives all its tabs closing and can be
  repopulated. API: `createGroup` / `renameGroup` / `setColor` / `deleteGroup` / `addTabToGroup` /
  `removeTabFromGroup` / `groupIdForTab`.

### Containers — `ContainerRepository.kt`
**Firefox "contextual identities"** — real isolation, distinct from a tab group's label. Each
container is an isolated cookie/storage jar identified by a `contextId`; a tab opened in it carries
that id (`TabSessionState.contextId`) and GeckoView keeps its cookies, localStorage, cache and logins
fully separate from other containers and the default context.

The canonical runtime state lives in `store.state.containers` (`Map<contextId, ContainerState>`,
driven by `ContainerAction`). Because that map isn't part of the app's own tab persistence, this
repository also persists the container **registry** to `tangent_containers.json` and re-registers it
into the store on startup, so containers survive a process restart alongside the tabs that reference
them (persisted `contextId`). Exposes `containers: StateFlow<List<ContainerState>>` plus
`createContainer` / `removeContainer` / `containerFor`.

## When to touch this package

- New tab operation → add to `TabRepository`, implement in both impls.
- New URL normalization rule → `normalizeUrl()`. Test against `apple.com`, `localhost:3000`, multi-word search input.
- New per-site preference → add a field to `SitePreferences`, persist in `SitePreferencesStore`, wire UI in `ui/chrome/AaSheet.kt` and consumers in `ui/TangentRoot.kt` / `ui/content/ContentHost.kt`.
- New snapshot capture site → call `cache.put(id, bitmap, url)`; the version flow handles propagation.
