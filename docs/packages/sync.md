# `sync/` (+ `sync/zen/`) — Firefox Sync, accounts, logins, Zen Spaces

Everything account- and sync-related. The centrepiece is `FirefoxSyncController`; the rest are the
pieces it composes (encrypted logins, received-tab notifications, push scaffolding) plus a small
self-contained Sync 1.5 client used to read Zen's custom collection. Read
[`AGENTS.md`](../../AGENTS.md) → *Firefox Sync — three sync triggers* before changing sync timing or
push — several non-obvious constraints (why push is inert, why command polling is the real path) are
documented there and easy to regress.

## `sync/`

### `FirefoxSyncController.kt`
The one controller that owns the Firefox account and keeps synced state fresh. Holds the FxA
`AccountManager`, the OAuth login flow (`FxAClientId`), the `SyncEngine` set, and the UI state
(`FirefoxSyncUiState`) the sync sheets read. Its three complementary refresh triggers — background
WorkManager, foreground poll, and (inert) FxA push — are described in `AGENTS.md`; do not collapse
them. Also drives:

- **Synced tabs** — remote devices' tabs, filtered to those used within `MaxActiveTabAgeMs` (21 days).
- **Send Tab** — `SendTabFeature`, registered unconditionally, received via `pollForCommands()` after
  every foreground sync pass (not via push). A received tab raises a notification, it does not
  auto-open.
- **Handoff** — a tab active on another device within `HandoffWindowMs` (5 min) becomes a handoff
  candidate surfaced by `HandoffBanner`; dismissals are sticky per device+URL.

Key data classes: `FirefoxSyncUiState`, `FirefoxSyncedDevice` / `FirefoxTargetDevice`,
`FirefoxSyncedTab`, `FirefoxReceivedTab`, `FirefoxHandoffTab`, and the `FirefoxSyncEvent` sealed
interface.

### `PlacesLoginsStorage.kt`
Encrypted login (password) storage — a wrapper over mozilla-components' Places logins DB. The DB
encryption key is generated once and **wrapped by an AndroidKeyStore key** (`KEYSTORE_ALIAS` /
`WRAP_KEY_ALIAS`, AES-GCM), so the key at rest is protected by hardware-backed keystore, never stored
in plaintext. Backs the passwords UI (see [`ui-library.md`](ui-library.md)) and autofill.

### `ReceivedTabNotifications.kt`
Posts the system notification for a "Send tab" push. Tapping it launches `MainActivity` with
`EXTRA_OPEN_URL` so the URL opens as a normal tab through `normalizeUrl` (invariant #9). Mirrors
vanilla Firefox: a received tab surfaces as a notification the user chooses to open, never silently
stealing the foreground.

### `TangentFirebasePushService.kt`
The FCM entry point for FxA push. The Android system instantiates it for incoming messages and the
base class forwards each to the installed `PushProcessor` (`AutoPushFeature`). **Inert on a personal
Firebase project** — see `AGENTS.md` for why; kept isolated so a future self-hosted autopush needs
only a `serverHost` change.

## `sync/zen/` — reading Zen browser's Spaces collection

Zen syncs a custom `spaces` collection that mozilla's app-services does not expose, so we read it
ourselves with a minimal Sync 1.5 client. **Read-only.**

### `Sync15Crypto.kt`
Minimal sync15 record crypto: `KeyBundle` (AES-256-CBC + HMAC-SHA256), `CollectionKeys`, and the
`EncryptedPayload` envelope. This is the exact crypto shape Firefox Sync uses; getting the
CBC/HMAC/base64 details right is fiddly (there are unit tests — see `ZenWorkspacesSyncTest`).

### `HawkAuth.kt`
Hawk v1 HTTP auth header computation, required by the Sync storage servers. Only the request-signing
subset the client needs (no payload hash — GET-only). Credentials come from the tokenserver
handshake.

### `Sync15StorageClient.kt`
The storage client: tokenserver handshake → per-request Hawk auth → fetch + decrypt BSOs from a
collection. `Sync15Record` is one decrypted BSO (`modified` is server epoch seconds).

### `ZenWorkspaces.kt`
Models + record parsing for Zen's Spaces engine (engine name "Spaces", version 2): `ZenSpace`,
`ZenContainer`, `ZenSyncedTab`, `ZenWorkspacesSnapshot`, and the record parsers/storage. Consumed by
the Zen spaces UI (see [`ui-sync.md`](ui-sync.md)). `ZEN_SPACES_TABS_UI_ENABLED` gates the
still-in-progress per-space tab listing.

## When to touch this package

- Sync timing / triggers → `FirefoxSyncController`, but read `AGENTS.md` first (the three triggers are
  complementary).
- New synced data type from a standard collection → prefer app-services APIs; only drop to
  `sync/zen/` for collections app-services doesn't expose.
- Login storage / autofill → `PlacesLoginsStorage`; never log or persist a decrypted key.
- Do **not** re-plumb receipt onto FxA push expecting it to work on a personal Firebase project.
