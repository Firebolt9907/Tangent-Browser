# `ui/shape/`, `ui/theme/`, `ui/addons/` — Small leaf packages

## `ui/shape/SquircleShape.kt`

A `Shape` that produces an iOS-style continuous-corner rounded rectangle (a sampled superellipse, not the circular fillet that `RoundedCornerShape` draws).

```kotlin
SquircleShape(radius: Dp, exponent: Float = 5f, cornerSamples: Int = 16)
```

- `exponent` is the superellipse `n`. `n = 2` is a circle; `n = 5` is the iOS look.
- `cornerSamples` controls path subdivision per corner — 16 is enough to look smooth at typical app radii.
- `radius` is clamped to `min(w, h) / 2`.

Used by:
- Tab-switch overlay panes (`switchPaneShape`).
- Tab-grid cells.
- The current-page outgoing snapshot in predictive back.

The radius itself is derived from `topChromeInsetPx * 0.8` so corner curvature scales with the device's status-bar / cutout inset.

## `ui/theme/`

Material 3 theme setup (`Theme.kt`, `Color.kt`, `Type.kt`) — light/dark palettes and typography, plus
two app-specific hooks worth knowing:

- **Dynamic (Material You) accent** — `Theme.kt` reads the device's system accent directly from the
  platform (not from `MaterialTheme.colorScheme.primary`), so it stays the *true* system color even
  when the in-app primary has been repainted (below). Pre-Android-12 devices fall back to the brand
  primary.
- **`siteColor` override** — `LuminaBrowserTheme` can repaint the in-app primary toward a page's
  declared theme color / the user's accent. This powers the "Tint pages to my system color" boost,
  whose draw-time overlay lives in `ui/content/SiteTint.kt`. So the theme is *not* purely static;
  changing color roles here can interact with that override.

## `ui/addons/AddonsSheet.kt`

`ModalBottomSheet` exposing the `AddonController` state: an install field (slug / AMO URL / `.xpi` URL), the list of installed add-ons with enable/disable/remove controls, and an inline permission-prompt sheet when `state.pendingPrompt` is non-null. Status / error messages come from the same state. The sheet is opened from the expanded SafariBar's Extensions icon.

`AddonPermissionSheet.kt` is split out of `AddonsSheet` and holds the "review what this add-on can access" install/permission prompt content and its human-readable permission/origin strings.

## When to touch these

- New shape variant (different exponent, different sampling) → add a sibling class to `ui/shape/`, don't parameterize `SquircleShape` further.
- New color role → `ui/theme/Color.kt` + `Theme.kt`.
- Changing add-on installer UX → `AddonsSheet.kt`. The controller's state shape is already rich; UI tweaks shouldn't need controller changes.
