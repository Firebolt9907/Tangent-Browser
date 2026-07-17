# `ui/sync/` — Synced tabs, handoff, Zen spaces

The UI over `sync/` (see [`sync.md`](sync.md)). Four surfaces, all reading `FirefoxSyncController`'s
state.

## Files

### `SyncedTabsSheet.kt`
The main Firefox Sync surface: sign-in / account state, the list of other devices and their open
tabs, and the "Send tab to device" picker. Opened from the tab-overview toolbar's "Synced" button.

### `HandoffBanner.kt`
A handoff-style banner floating above the bottom bar that offers to continue a tab just active on
another Sync device (within `HandoffWindowMs`). Tapping the card opens the tab; the X dismisses it
(sticky per device+URL via `FirefoxSyncController.dismissHandoff`). Rendered as a `fillMaxSize`
overlay so it can bottom-align itself.

### `SpacesCarousel.kt`
An Arc-style horizontal deck of Zen `ZenSpace`s: one accent-coloured card per space, swipeable, with
neighbours peeking and non-centred cards scaled + faded. A dot indicator tracks the pager. **Read-only
mirror** of what Zen synced — each space lists its `zenWorkspace`-tagged tabs; tapping one opens it
here, it is not a live space switcher.

### `SpacesSheet.kt`
A focused bottom sheet showing just the `SpacesCarousel`, summoned by swiping up on the tab-overview
toolbar (Arc mobile's space-switcher gesture). Kept separate from `SyncedTabsSheet` so the gesture
lands directly on the spaces deck rather than the full Sync surface.

## When to touch this package

- Account / device / send-tab UI → `SyncedTabsSheet`.
- Handoff presentation → `HandoffBanner` (the candidacy logic is in `FirefoxSyncController`).
- Zen spaces → `SpacesCarousel` / `SpacesSheet`; the data + parsing live in `sync/zen/` (see
  [`sync.md`](sync.md)). The per-space live tab list is gated by `ZEN_SPACES_TABS_UI_ENABLED`.
