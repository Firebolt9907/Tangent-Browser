# `ui/chrome/` — The SafariBar bottom pill

All bottom-bar rendering. No engine, no Gecko — just `BarUiState` in, composed pixels out.

## File map

| File | What it owns |
|---|---|
| `SafariBar.kt` | Public composable. Picks one of `BarExpanded` / `BarEditing` based on `state.mode`; animates the swap. Reports its measured height so `TangentRoot` can size the Gecko bottom inset. |
| `BarExpanded.kt` | The single interpolated surface from full expanded → compact. Width, height, corner radius, padding, side controls, and tab-count chip all driven by `collapseProgress: Float (0..1)`. Compact mode just renders `BarExpanded` with `collapseProgress = 1f`. |
| `BarCompact.kt` | Currently unused at runtime; kept as a reference / preview composable for the compact-only state. |
| `BarEditing.kt` | Full-width text field + cancel button shown in `BarMode.Editing`. Auto-selects the existing address on entry so replacement typing is the default. |
| `BarPill.kt` | The interactive rounded `Surface`. Owns the 2D pointer-input that powers tab switching + grid open. See "Gesture model" below. Its `PillMorph` describes the collapse as two endpoints + a `progress` provider read in the layout/draw phase (never during composition), so the spring drives every intermediate value without recomposing. |
| `TabCountChip.kt` | The little number that fades out as the bar collapses. |
| `AddressSuggestions.kt` | The iOS-style **address sheet** shown in editing mode: an opaque backdrop hides the live engine, a scaled-down snapshot of the current page peeks below the status bar, and the search/history/bookmark suggestions list sits above the keyboard. |
| `ImeSheetController.kt` | Frame-locked manual control of the soft-keyboard inset for the address sheet's drag-to-dismiss (wraps `WindowInsetsAnimationController`, API 30+) so a downward drag pushes the keyboard in lock-step with the finger. |
| `ScaledPageBackdrop.kt` | The "card behind a sheet" backdrop: an opaque cover plus the current-page snapshot scaled down as a foregrounded sheet rises. `fraction` is read lazily in the draw phase. Shared by the address sheet and other rising sheets. |
| `AaSheet.kt` | The **"aA" appearance sheet** (Safari's aA button): per-site zoom, top-padding-fix, reader appearance, Reading List, "Open in…" app hand-off, share. The largest chrome file. |
| `AaSheetComponents.kt` / `AaSheetAppResolver.kt` | Split out of `AaSheet`: reusable stateless UI pieces, and the non-Compose `PackageManager` logic for the "Open in…" browser/app resolution (run off the main thread). |
| `FindInPageBar.kt` | Compact find-in-page bar (query field, match counter, prev/next, close). Stateless — the host wires the actions to the session's `findAll` / `findNext` / `clearFindMatches`. |
| `WideBarLayout.kt` | How the bottom bar arranges on wide screens (tablets, landscape): `SPLIT` (buttons left, width-capped pill) vs `CORNER`. Persisted in `GlobalPreferencesStore.wideBarLayoutName`; narrow/portrait phones use the classic centred bar. |

## Gesture model (`BarPill`)

One `pointerInput { awaitEachGesture { … } }` block that:

1. Listens at `PointerEventPass.Initial` — parent-before-child. This lets the drag see and consume moves before child `IconButton`s / `Row.clickable` get them.
2. Does **not** consume the initial `Down` or pre-slop moves — so taps on the URL row, `aA`, Extensions, and Reload still work.
3. Once total movement exceeds `viewConfiguration.touchSlop` in either axis, starts emitting `onDragStart` / `onDragDelta(Offset)` and consumes the change. **No axis lock** — vertical and horizontal accumulate in parallel.
4. On `Up`, emits `onDragEnd(totalDelta, velocity)` and consumes the up so a tap doesn't fire at the end of a drag.

When `interactive = false` (ghost pills or compact mode past halfway), the gesture modifier is omitted entirely.

## `collapseProgress` choreography

`TangentRoot` owns a `barCollapseProgressState: Animatable<Float>` and passes it down. Everything that interpolates with the collapse — bar width, height, corner radius, side-control padding, tab-count chip alpha — reads the same value. See `IMPLEMENTATION.md` for the hybrid preview/snap model that produces those numbers from raw `EngineView.verticalScrollDelta`.

## Why `BarExpanded` does both expanded and compact

Originally there were two separate composables. Switching between them dropped a frame and the corner radius animation didn't carry over. Folding compact into `BarExpanded(collapseProgress = 1f)` made every visible property a single interpolation, which animates cleanly.

`AnimatedContent` in `SafariBar` still swaps in `BarEditing` because that one *is* a structurally different layout.

## When to touch this package

- New affordance in the bar → `BarExpanded.kt`. Make sure it fades out by ~`collapseProgress = 0.6`.
- Changing pill drag behavior → `BarPill.kt`. The Initial-pass + no-pre-slop-consume rules matter — read the comment in the file before editing.
- Changing the editing field → `BarEditing.kt`.
- Changing bar measurement reporting → `SafariBar.kt` — `onBottomInsetMeasured` feeds Gecko's bottom inset.
