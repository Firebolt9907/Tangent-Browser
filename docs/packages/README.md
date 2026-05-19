# Package guides

One short doc per Kotlin package. Read these alongside the file you're editing — they're written to explain *what lives here and how the pieces relate*, not to substitute for reading the code.

- [`engine.md`](engine.md) — `engine/` — DI root (`LuminaBrowserComponents`).
- [`state.md`](state.md) — `state/` — Tabs, bar state, snapshot cache, site prefs.
- [`addons.md`](addons.md) — `addons/` — Firefox add-on install/manage.
- [`ui-chrome.md`](ui-chrome.md) — `ui/chrome/` — SafariBar bottom pill.
- [`ui-content.md`](ui-content.md) — `ui/content/` — EngineView host + page injection.
- [`ui-grid.md`](ui-grid.md) — `ui/grid/` — 2D tab grid overlay.
- [`ui-shape-theme-addons.md`](ui-shape-theme-addons.md) — Three small leaf packages bundled.

Not covered separately:

- `ui/LuminaRoot.kt` lives at the package root. It's the only file in `ui/` proper. See [`ARCHITECTURE.md`](../../ARCHITECTURE.md) → "The UI" and [`IMPLEMENTATION.md`](../../IMPLEMENTATION.md) for that one.
- `MainActivity.kt` and `LuminaApplication.kt` are two-screen-readable; no separate doc.
