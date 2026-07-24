# Package guides

One doc per Kotlin package (or small cluster of related packages). Read these alongside the file
you're editing — they explain *what lives where and how the pieces relate*, not as a substitute for
reading the code. For the big picture first, read [`ARCHITECTURE.md`](../../ARCHITECTURE.md); for the
load-bearing invariants, [`AGENTS.md`](../../AGENTS.md).

> The codebase still uses the project's original name, **Tangent**, in its package and class names.
> The app is now **Tangent Browser**; a full code rename is pending.

## Non-UI layers

- [`top-level.md`](top-level.md) — the two activities, their shared base, `TangentApplication`, `IntentUrls`.
- [`engine.md`](engine.md) — `engine/` — the DI root (`TangentBrowserComponents`) + error-page interceptor.
- [`state.md`](state.md) — `state/` — tabs, bar state, snapshot cache, persistence, search, prefs, preloaders.
- [`addons.md`](addons.md) — `addons/` — Firefox add-on install/manage.
- [`sync.md`](sync.md) — `sync/` + `sync/zen/` — Firefox Sync, FxA, encrypted logins, Zen Spaces.
- [`permissions-service.md`](permissions-service.md) — `permissions/` + `service/` — site permissions, Custom Tabs service, keep-alive.

## UI

- [`ui-root.md`](ui-root.md) — `ui/` root — `TangentRoot` and its extracted overlays, state holders, haptics, prompts.
- [`ui-chrome.md`](ui-chrome.md) — `ui/chrome/` — the SafariBar bottom pill, address sheet, aA sheet.
- [`ui-content.md`](ui-content.md) — `ui/content/` — the single EngineView host, page JS injection, link/image glances, probes.
- [`ui-grid.md`](ui-grid.md) — `ui/grid/` — the tab overview (deck / pixel / classic grid).
- [`ui-reader.md`](ui-reader.md) — `ui/reader/` — reader mode: extractor, view, rules engine, TTS.
- [`ui-sync.md`](ui-sync.md) — `ui/sync/` — synced-tabs sheet, handoff banner, Zen spaces carousel.
- [`ui-library.md`](ui-library.md) — `ui/bookmarks/`, `ui/history/`, `ui/downloads/`, `ui/passwords/` — library sheets.
- [`ui-containers-groups.md`](ui-containers-groups.md) — `ui/groups/` + `ui/containers/` — tab-group and container management sheets.
- [`ui-onboarding-video.md`](ui-onboarding-video.md) — `ui/onboarding/` + `ui/video/` — first-run flow and fullscreen video.
- [`ui-customtab.md`](ui-customtab.md) — `ui/customtab/` — the slim external-link surface.
- [`ui-components.md`](ui-components.md) — `ui/components/` — shared sheet scaffolding, favicon, empty-state.
- [`ui-shape-theme-addons.md`](ui-shape-theme-addons.md) — `ui/shape/`, `ui/theme/`, `ui/addons/` — leaf packages.
