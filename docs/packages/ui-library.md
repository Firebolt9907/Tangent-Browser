# `ui/bookmarks/`, `ui/history/`, `ui/downloads/`, `ui/passwords/` — Library sheets

The four "library" bottom sheets opened from the tab-overview overflow. Each is a self-contained
`ModalBottomSheet` over one data source, built on the shared scaffolding in `ui/components/` (see
[`ui-components.md`](ui-components.md)) so they read consistently.

## Files

### `ui/bookmarks/BookmarksSheet.kt`
The bookmarks list, backed by mozilla-components' Places bookmarks storage. Also the store behind the
Safari-style Reading List (a folder under the Mobile bookmarks root — see the `AaSheet` note).

### `ui/history/HistorySheet.kt`
Browsing history grouped into "Today" / "Yesterday" / a weekday-and-date header (the year is added
only when it isn't the current one). Backed by Places history storage.

### `ui/downloads/DownloadsSheet.kt`
The in-app downloads list. The app routes file downloads to the platform `DownloadManager`
(`LuminaBrowserActivity`'s `DownloadsFeature` / `AndroidDownloadManager`), so this sheet reads back
from that same queue — `DownloadManager.query` only returns this app's downloads, and the OS keeps the
list across process death. It polls while running downloads exist so progress bars advance without a
manual refresh.

### `ui/passwords/PasswordsSheet.kt`
The saved-logins manager over `PlacesLoginsStorage` (see [`sync.md`](sync.md)). Notable detail:
copying a password flags the clip as **sensitive** (Android 13+) via the platform clipboard so the OS
redacts it from the clipboard preview and excludes it from history — Compose's `ClipboardManager`
can't set that flag, so this uses the platform API directly.

## When to touch this package

- Row layout / grouping → the individual sheet; shared header/section/card chrome lives in
  `ui/components/SheetScaffold.kt`.
- New library surface → add a sheet here, reuse `SheetScaffold` + `EmptyState` + `Favicon`, and wire
  it into the overflow menu in `ui/GridOverviewControls.kt`.
- Anything touching stored passwords → keep the sensitive-clipboard flag and never log plaintext.
