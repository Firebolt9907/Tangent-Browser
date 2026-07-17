# `ui/components/` — Shared UI building blocks

Small, presentation-only composables shared across the bottom sheets so every surface (Settings,
Bookmarks, History, Downloads, Passwords, Sync, Add-ons) reads the same. Behavior stays in each sheet;
these hold only the shared look.

## Files

### `SheetScaffold.kt`
The shared sheet chrome: the header, section labels, and grouped-card containers every bottom sheet is
built from. Use these instead of hand-rolling headers so sheets stay visually consistent.

### `EmptyState.kt`
The shared "nothing here yet" placeholder — a muted icon, a short title, and an optional one-line hint
telling the user what to do next. Used by Add-ons, Passwords, Downloads, etc.

### `Favicon.kt`
A site favicon for list rows (bookmarks, history). Loads the origin's `/favicon.ico` and shows a
themed globe while loading or when the site has none, so every row has a consistent leading glyph
(Safari-style). Lives inside `LazyColumn` items so only visible rows fetch; Coil caches by URL so
scrolling doesn't refetch. Fetched from the site itself — no third-party favicon service — so it leaks
nothing a normal page visit wouldn't.

## When to touch this package

- New shared sheet element (a new row style, a new section container) → add it here so all sheets pick
  it up.
- Keep these presentation-only. Anything that talks to a data source belongs in the sheet, not here.
