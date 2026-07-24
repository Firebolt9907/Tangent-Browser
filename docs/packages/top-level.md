# Top level — activities, application, intents

The five files at the package root (`com.firebolt9907.tangentbrowser`) that bootstrap the app and own
the two Android activities. See [`AGENTS.md`](../../AGENTS.md) → *Two activities / external links* for
the invariants these must respect.

## Files

### `TangentApplication.kt`
The `Application`. Lazily constructs and owns the `TangentBrowserComponents` DI graph (see
[`engine.md`](engine.md)); everything downstream pulls dependencies from `application.components`. This
is the single process-wide root — activities come and go, this doesn't.

### `TangentBrowserActivity.kt`
The **shared base class** for both browser activities — all the engine-hosting plumbing a Gecko page
needs regardless of which surface shows it:

- the engine `ActivityDelegate` (WebAuthn / passkeys — GeckoView hands us a `PendingIntent` for the
  system passkey sheet, which we launch and return the result of; see [`AGENTS.md`](../../AGENTS.md)
  invariant #12),
- `DownloadsFeature` wiring, so a download started from any page routes to the platform
  `DownloadManager`,
- the prompt / permission feature registration shared by both surfaces.

Both `MainActivity` and `CustomTabActivity` extend this so the two can't drift on the engine
contract.

### `MainActivity.kt`
The browser. Hosts `TangentRoot` (see [`ui-root.md`](ui-root.md)). Its own `singleTask` task; the
`LAUNCHER` entry point and home-screen shortcut target. Requests `POST_NOTIFICATIONS` at launch (for
"Send tab" receipts) and handles `EXTRA_OPEN_URL` — a received/handoff tab opens here as a normal tab
through the single `normalizeUrl` path (invariant #9).

### `CustomTabActivity.kt`
Hosts an external link (from another app, e.g. a Google Discover article) as a **Custom Tab** — a
mozilla-components `CustomTabSessionState` that lives *outside* the normal tab list, grid, persistence
and Firefox Sync. Launched as its own task layered over the calling app (dedicated `taskAffinity` +
`singleTask` + `autoRemoveFromRecents`). Renders `ui/customtab/CustomTabRoot` (see
[`ui-customtab.md`](ui-customtab.md)). "Open in Tangent" migrates the session into a real tab and
hands off to `MainActivity`.

### `IntentUrls.kt`
Shared URL extraction for incoming intents, used by **both** activities (`CustomTabActivity` for
external `ACTION_VIEW` / `ACTION_SEND` links, `MainActivity` for its own "Add to Home Screen" shortcut
intents). One place so the two activities can't parse a shared-text URL differently.

## When to touch these

- New engine-level feature both surfaces need (e.g. another `ActivityResultLauncher`) → the base
  `TangentBrowserActivity`, not one activity.
- New external-intent entry point → `IntentUrls` for parsing, then the relevant activity.
- Changing which surface an external link opens in → this is a security/UX boundary; read
  [`AGENTS.md`](../../AGENTS.md) → *Two activities* first.
