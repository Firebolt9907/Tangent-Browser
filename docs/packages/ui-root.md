# `ui/` (root) — `LuminaRoot` and its extracted pieces

The `ui/` package root holds the main composable and the files progressively extracted out of it. The
architecture here is deliberate: `LuminaRoot` is a large stateful gesture orchestrator, and the way it
stays maintainable is by moving **state into holder objects** and **render blocks into their own
files**, each of which reads that state without owning gesture logic. If you're adding UI, extract it
the same way rather than growing `LuminaRoot`.

Read [`AGENTS.md`](../../AGENTS.md) invariants #1, #4, #5, #7 before touching anything here — the
gesture/snapshot state splits are load-bearing.

## The orchestrator

### `LuminaRoot.kt` (~3,000 lines)
The whole main-browser UI and *all* of its gesture orchestration: bar scroll-collapse, horizontal
tab-switch, swipe-up-to-grid, predictive back, snapshot capture/cover sequencing, new-tab-from-edge,
pull-to-refresh, and the wiring of every overlay and sheet. It reads `BrowserState` directly from the
components graph (the `LuminaViewModel` only powers the bar). Most of its bulk is the gesture math and
the `remember`/`LaunchedEffect` state machine; the rendered surfaces are delegated to the files below.

### `LuminaRootHelpers.kt` / `LuminaRootTuning.kt`
`LuminaRootHelpers` holds small non-composable helpers pulled out of the root. `LuminaRootTuning`
holds the tuning constants (spring stiffnesses, overscroll falloff fractions, thresholds) — the knobs
you'd tweak to change feel, gathered in one place instead of scattered as magic numbers.

## State holders (the refactor that made extraction possible)

Each groups a large cluster of the root's closed-over local state into one object whose fields are
`MutableState` / `Animatable`, so `LuminaRoot` keeps local reactivity while the render blocks that
consume the cluster can be extracted without threading two dozen parameters.

- **`SnapshotCoverState.kt`** — the ~24-field tab-switch / predictive-back snapshot + sticky-cover
  state machine (invariant #7).
- **`GridGestureState.kt`** — the ~21 grid open/close gesture vars (invariants #4, #5, #5a).
- **`HorizontalGestureState.kt`** — the horizontal tab-switch / predictive-back drag vars (invariant
  #5).

## Extracted render blocks (pure, stateless consumers)

Each was lifted verbatim from `LuminaRoot`'s composition tree; each reads a state holder (and, where
noted, keeps per-frame reads in the layout/draw phase for performance) and owns no gesture logic.

- **`SwitchSnapshotCovers.kt`** — the tab-switch / predictive-back cover stack: sticky-incoming cover,
  its cross-fade-out, frozen leaving/incoming snapshots, new-tab commit fade, predictive-back cover,
  cold-start grey cover. Driven purely by `SnapshotCoverState` (invariant #7).
- **`HorizontalSwitchOverlay.kt`** — the sliding leaving/incoming page panes drawn during a horizontal
  drag and its commit-spring (invariant #5). The per-frame `offsetX` read stays in
  `graphicsLayer` / `drawWithContent`.
- **`PredictiveBackOverlay.kt`** — layers the previous-history page (parallax/dim) under the current
  page (slid off right with a shadow) during a back-swipe.
- **`RoundedCornerViewportMask.kt`** — paints the four rounded-corner cut-outs over the live GeckoView
  surface (which can't be clipped to a rounded shape directly), keeping the viewport's rounded corners
  visible at all times; fades out only as the grid opens.
- **`GridOverviewControls.kt`** — the tab-overview's floating bottom chrome: the Sync handoff card and
  the toolbar row (New Tab, Synced, overflow → layout mode / reopen-closed / bookmarks / history /
  downloads / passwords / settings). Pure chrome, no gesture/snapshot invariants.
- **`BottomBarLayer.kt`** — the Arc/Zen-style pull-refresh indicator (a thin centre-out line) plus the
  full-width bottom bar layer host.
- **`LuminaOverlaySheets.kt`** — hosts *all* of the root's modal sheets and floating overlays that are
  independent of the tab overview: Settings / History / Bookmarks / Downloads / Passwords / Add-ons /
  Synced-tabs / the aA sheet, plus the site-permission and prompt dialogs, the standalone
  `UtilityWebCard`, the `LinkPeekCard` link glance, and the image context sheet.

## Prompts, permissions, dialogs

- **`PromptHandler.kt`** — routes each engine `PromptRequest` (JS alert/confirm/prompt, beforeunload,
  HTTP auth, identity-credential, choice pickers) to the right dialog.
- **`PromptDialogs.kt`** — the dialog composables `PromptHandler` shows (presentation split out of the
  routing).
- **`SitePermissionDialog.kt`** — the content-permission prompt (geolocation, camera, mic,
  notifications…); depends only on `SitePermissionController`, so it lives standalone.
- **`TimeSelectionPrompt.kt`** — native date/time picker for `<input type=date|time|…>` (mirrors
  Fenix; without it those inputs did nothing).
- **`ColorPickerDialog.kt`** — compact RGB dialog for `<input type=color>` (Android has no platform
  color picker).

## Small utilities

- **`LuminaHaptics.kt`** — a vocabulary of *semantic* haptics routed through the raw `Vibrator` (see
  [semantic-haptics memory]; Compose's `HapticFeedback` only exposes sharp taps, so every gesture felt
  identical). Respects the system haptics toggle; needs `VIBRATE`.
- **`ShareUrl.kt`** — the single system-share-sheet path behind every share affordance; no-ops on
  blank/`about:blank`; adds `NEW_TASK` when the context isn't an Activity.
- **`FullscreenSystemBars.kt`** — hides/restores the system bars for fullscreen video; shared by
  `LuminaRoot` and `CustomTabRoot`.

## When to touch this package

- New overlay/surface → add a state holder field (or a new holder) + a new extracted render-block
  file; wire it from `LuminaRoot`. Don't inline it into the composable.
- Changing gesture feel → `LuminaRootTuning.kt` constants first.
- New engine prompt type → `PromptHandler` (routing) + `PromptDialogs` (UI).
- Anything touching snapshot covers or grid/horizontal drag → re-read the invariants in `AGENTS.md`;
  these are exactly the places subtle regressions hide.
