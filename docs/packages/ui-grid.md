# `ui/grid/` — Tab overview

The tab overview that opens when the user swipes up on the pill. It has **three selectable layouts**
(`TabViewMode`, persisted in `GlobalPreferencesStore`, cycled from the overview toolbar); all three
share the same layout-agnostic gesture core in `LuminaRoot` (progress, release, invariants #4/#5) and
only swap the destination geometry. See [`AGENTS.md`](../../AGENTS.md) → invariant #5a.

## Files

### `Grid.kt` — `TabGridOverlay` (DECK + PIXEL modes)
The default overview. **DECK** is the iOS app-switcher look: a horizontal receding 3D deck where the
newest tab is largest and furthest right (the front), older tabs shrink and tuck behind-left, and only
~`DeckVisibleDepth` cards read as visible. **PIXEL** is a constant-scale horizontal filmstrip (no
receding/stacking/fade). One `slotProvider(p)` (`deckSlot` vs `pixelSlot`) routes all per-card
placement, read in the draw phase; the drag/scroll `stepPx` is mode-aware.

### `ClassicGrid.kt` — `ClassicTabGridOverlay` (GRID mode)
The legacy 2-column vertical grid, recovered verbatim from the `jul-8` branch. Its private helpers
(`GridTabCard`, `GridCellChrome`, `GestureVisibleSlots`) share names with `Grid.kt`'s but are
**file-scoped** — keep them private so they don't collide.

### `GridModel.kt`
The non-composable model, geometry, and helpers for both overlays (`deckSlot`, `pixelSlot`,
`gridCellRect`, the `GridTab` model, `Grid*` constants). Split out so the overlays hold rendering and
this holds plumbing.

## The shared positioning trick

Every card starts at its **true carousel position** (`GridTab.browseBaseX` + the live carousel shift,
matching the browse-layer overlay pixel-for-pixel) at `progress = 0` and lerps toward its destination
slot at `progress = 1`. Because the transform pipeline (`padding` → `graphicsLayer` translate/scale →
`clip(SquircleShape)`) is identical to the horizontal-switch overlay's, a visible tab at `progress = 0`
is pixel-equivalent to the browse snapshot — the transition has no seam. Corner radius interpolates so
the radii match exactly at `progress = 0`.

## Non-obvious rules

- **Deck scroll is stored in pixels**, not card-units: the spline-decay fling needs pixel-scale
  magnitudes or inertia dies; release projects the fling then springs to the nearest card.
- **Swipe-up on a deck card is distance-sensitive**: a gentle swipe navigates to the older tab; a
  decisive one (~38% of the screen) closes it.
- **Handoff with the horizontal switch**: if the user grabs the pill while a horizontal-commit spring
  is still running, `LuminaRoot` pins the leaving/incoming tab ids + X offsets into `gridHandoff*`
  fields; the overlay derives their carousel positions from that instead of the live `offsetX`, so
  there's no discontinuity at handoff.
- **Captions load real favicons** via `components.icons` (URL-keyed cache in `LuminaRoot`, pruned to
  open tabs), falling back to `ContentState.icon` then a letter tile.
- **Snapshot source per cell**: the selected tab prefers the gesture-fresh capture
  (`tabSwitchCurrentSnapshot ?: cache.get(...)`); others use `TabSnapshotCache.get(url-key)`, rendering
  the neutral background until a background decode bumps `version`.

## When to touch this package

- Deck/pixel geometry → `Grid.kt` + `GridModel.kt` (`deckSlot` / `pixelSlot`).
- Classic 2-col layout → `ClassicGrid.kt` (keep its helpers file-scoped).
- Open/close springs live in `LuminaRoot.kt`, not here.
- Both overlays consume the same gesture core — a new mode means a new slot function + a render path,
  not a new gesture pipeline.
