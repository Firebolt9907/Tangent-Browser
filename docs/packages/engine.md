# `engine/` — Components graph

The DI root (`Engine.kt` = `LuminaBrowserComponents`), plus `LuminaRequestInterceptor.kt`.

`LuminaApplication.components` lazily constructs an instance; everything downstream pulls its dependencies from there. Construction order matters and is fixed.

```
GeckoEngine
  ↓ used by
BrowserStore (with EngineMiddleware)
  ↓ used by
SessionUseCases, TabsUseCases
  ↓ used by
GeckoTabRepository
```

Alongside those:

- `TabSnapshotCache(application.cacheDir)` — bitmap LRU + JPEG-on-disk.
- `SitePreferencesStore(application)` — per-host zoom + top-padding-fix.
- `AddonController(engine, scope)` — wraps Gecko's `WebExtensionRuntime`.
- `TabPersistence(application)` — passed into `GeckoTabRepository` so the open tab set restores across restarts.

`browserState: StateFlow<BrowserState>` mirrors the store via `observeManually` so Compose can collect it. The component's `init` block resumes that subscription; the repository restores tabs from `TabPersistence` and only `seedIfEmpty(...)` seeds starter tabs when there's nothing to restore (the old "reseed demo tabs every cold start" behavior is gone — see `TabPersistence` in [`state.md`](state.md)).

`scope` is `SupervisorJob() + Dispatchers.Main.immediate` — used by the addon controller for state updates that must reach Compose without a thread bounce.

## `LuminaRequestInterceptor.kt`

Serves a branded, localized error page for failed navigations (no connection, DNS failure, timeout, bad cert…) instead of GeckoView's raw built-in page — the same approach Fenix uses. The HTML lives in `assets/error_page_js.html`; `ErrorPages.createUrlEncodedErrorPage` fills it with the localized strings `browser-errorpages` ships per `ErrorType`.

## When to touch this file

- Adding a new application-scoped collaborator (e.g. a downloads manager, a history store).
- Changing the seeded tabs.
- Wiring a new `mozilla-components` feature that needs the engine or store at construction time.

Anything that's lifecycle-scoped (Activity / ViewModel) does **not** belong here.
