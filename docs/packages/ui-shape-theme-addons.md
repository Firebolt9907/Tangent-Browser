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

Standard Material 3 theme setup (`Theme.kt`, `Color.kt`, `Type.kt`). Nothing app-specific — light/dark palettes and the default Compose typography. If you need to add a custom color role, do it here.

## `ui/addons/AddonsSheet.kt`

`ModalBottomSheet` exposing the `AddonController` state: an install field (slug / AMO URL / `.xpi` URL), the list of installed add-ons with enable/disable/remove controls, and an inline permission-prompt sheet when `state.pendingPrompt` is non-null. Status / error messages come from the same state. The sheet is opened from the expanded SafariBar's Extensions icon.

## When to touch these

- New shape variant (different exponent, different sampling) → add a sibling class to `ui/shape/`, don't parameterize `SquircleShape` further.
- New color role → `ui/theme/Color.kt` + `Theme.kt`.
- Changing add-on installer UX → `AddonsSheet.kt`. The controller's state shape is already rich; UI tweaks shouldn't need controller changes.
