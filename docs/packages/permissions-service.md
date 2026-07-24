# `permissions/` + `service/` — Site permissions and background services

Two small non-UI packages: the content-permission decision layer, and the two Android `Service`s the
app declares.

## `permissions/`

### `SitePermissionController.kt`
Decides content-permission requests (geolocation, camera, microphone, notifications, autoplay…) and
remembers the user's answers. Applies remembered decisions and the autoplay policy *before* the UI is
ever shown, so `ui/SitePermissionDialog.kt` only surfaces genuinely-new requests.

**Security boundary:** a remembered decision is keyed by the request's **full origin**
(`scheme://host[:port]`, lower-cased), not by bare host. A camera grant to `https://example.com` must
never be silently reused for `http://example.com` — otherwise an active-network attacker who
downgrades or MITMs the http origin would inherit the grant. Preserve origin-scoping if you touch the
key.

## `service/`

### `TangentCustomTabsService.kt`
The `CustomTabsService` the app advertises (see the manifest `<service>`). Registering it is what
makes callers that speak the Custom Tabs protocol — most notably the Google app / Discover —
recognize Tangent as a Custom Tabs provider and route article links to it (rather than a plain
`ACTION_VIEW`). Also the entry point for the `mayLaunchUrl` warmup handshake that feeds
`ExternalLinkPreloader` (see [`state.md`](state.md)).

### `BrowsingKeepAliveService.kt`
A minimal foreground service whose only job is to raise the process's importance while the app is
backgrounded, so Android's low-memory killer is less likely to reap the browser (and discard open
tabs) under memory pressure. **Strictly opt-in** via
`GlobalPreferencesStore.keepAliveInBackground`; uses `FOREGROUND_SERVICE_SPECIAL_USE`.

## When to touch these

- New content-permission type → `SitePermissionController` (decision + remember logic) and
  `ui/SitePermissionDialog.kt` (presentation). Keep origin-scoped storage.
- Custom Tabs protocol behaviour (warmups, session callbacks) → `TangentCustomTabsService`.
- The keep-alive is deliberately dumb; don't grow it into a general background worker — periodic sync
  already has WorkManager (see [`sync.md`](sync.md)).
