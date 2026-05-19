# `ui/grid/` — Tab grid overlay

One file: `Grid.kt`. Renders the 2D tab switcher that opens when the user swipes up on the pill.

## Mental model

`TabGridOverlay` is a single composable that renders **every** tab simultaneously. Each tab is positioned at its true carousel slot at `progress = 0` and lerps toward its grid cell at `progress = 1`. There's no separate "browse → grid" transition — they're the same scene parameterized by progress.

For an N-tab list with `newSelIdx` and a `carouselShift` (= `offsetX` during a live drag, or the pinned handoff value during a mid-switch grab):

```
browseTranslationX_i = (i - newSelIdx) * pageStride + carouselShift
cellTranslationX_i, cellTranslationY_i  // from the grid layout
translationX = lerp(browseTranslationX_i, cellTranslationX_i, xProgress)
translationY = lerp(0,                     cellTranslationY_i, p)
scaleX = scaleY = lerp(1, cellWidth / screenWidth, p)
```

The transform pipeline is exactly `padding(top = browseTopPadPx)` → `graphicsLayer { transformOrigin = TopLeft; translationX/Y; scaleX/Y }` → `clip(SquircleShape)`. This is identical to what the horizontal switch overlay uses, so at `progress = 0` a tab in the visible viewport is pixel-equivalent to the browse-layer snapshot — the transition has no visual seam.

The corner radius passed to `SquircleShape` interpolates from `browseCornerDp` (= `topChromeInsetPx * 0.8 * 2`) to `22.dp` so the radii are exactly equal at progress=0.

## Why off-screen tabs sit at `±N * pageStride`

Originally far-away tabs stacked at the center — they popped outward as the grid opened. Now they really are off-screen at progress=0, so they slide in from the side as the grid opens, which reads as a continuous motion.

## Background opacity

The grid background opacity is `(progress * 20).coerceIn(0, 1)` — fully opaque within the first ~5% of progress. This hides GeckoView showing through the squircle corners during the ramp-up.

## Handoff with the horizontal switch

When the user grabs the pill while a horizontal-commit spring is still running, `LuminaRoot` pins the leaving + incoming tab ids and X offsets into `gridHandoff*` fields and clears the commit-overlay bitmaps. The grid then derives those two tabs' carousel positions from the handoff state instead of the live `offsetX`. Without this, the grid would snap them to their static carousel slots and the user would see a discontinuity at handoff.

## Snapshot source per cell

- The currently selected tab uses `tabSwitchCurrentSnapshot ?: cache.get(...)` — preferring the gesture-fresh capture if one exists.
- Every other tab uses `cache.get(url-key)` from `TabSnapshotCache`. Missing snapshots render as the neutral background; the cache will background-decode and `version` will bump, re-rendering.

## When to touch this package

- Changing grid layout (rows, cell aspect, gaps) → `Grid.kt`, the cell-position math near the top.
- Changing the open/close springs → those live in `LuminaRoot.kt` (`openGrid` / `closeGrid` / `springGridProgressTo`), not here.
- Adding per-cell affordances (close button, long-press menu) → add to `TabGridOverlay`; remember the cell transform is `scale + translate`, so child sizing should be in cell-local coordinates.
