# `engine/` — Components graph

The DI root. One class: `LuminaBrowserComponents`.

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

`browserState: StateFlow<BrowserState>` mirrors the store via `observeManually` so Compose can collect it. The component's `init` block resumes that subscription and calls `tabRepository.seedIfEmpty(...)` with three demo tabs (Apple, NYT, Wikipedia).

`scope` is `SupervisorJob() + Dispatchers.Main.immediate` — used by the addon controller for state updates that must reach Compose without a thread bounce.

## When to touch this file

- Adding a new application-scoped collaborator (e.g. a downloads manager, a history store).
- Changing the seeded tabs.
- Wiring a new `mozilla-components` feature that needs the engine or store at construction time.

Anything that's lifecycle-scoped (Activity / ViewModel) does **not** belong here.
