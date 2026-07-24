# Security Policy

Tangent Browser is a web browser: it handles untrusted content, stores encrypted credentials, and
integrates with Firefox Sync. Security reports are taken seriously.

## Reporting a vulnerability

**Do not open a public issue for security vulnerabilities.**

Instead, use GitHub's **private vulnerability reporting** ("Report a vulnerability" under the
repository's *Security* tab) so the report stays confidential until a fix is available. Please
include:

- affected version / commit and device + Android version,
- a clear description and, ideally, a minimal reproduction,
- the impact you believe it has.

You'll get an acknowledgement as soon as is practical. This is a volunteer-run project, so please
allow reasonable time for a fix before any public disclosure.

## Scope and caveats

- **Not distribution-hardened.** Release builds are signed with a local **debug** keystore and
  minification/shrinking are off. Tangent is not published to any store. Treat self-built APKs
  accordingly.
- **Credentials** are stored via Mozilla's `PlacesLoginsStorage` (encrypted at rest). Sync data uses
  the Sync 1.5 crypto path; keys are derived from the Firefox account and never committed.
- **Passkeys / WebAuthn** go through GeckoView's native FIDO2 / Credential Manager path. Do not
  report the absence of a JavaScript `navigator.credentials` shim as a bug — that is intentional
  (see [`AGENTS.md`](AGENTS.md), invariant #12).
- **Firefox account push (FxA)** is intentionally inert on a personal Firebase project; this is
  documented behavior, not a vulnerability (see [`AGENTS.md`](AGENTS.md) → *Firefox Sync*).
- Underlying engine vulnerabilities in **GeckoView** / `mozilla-components` should be reported to
  Mozilla; keeping the `mozilla-components` version current is the mitigation on our side.

## Secrets

Never commit `google-services.json`, keystores, or account tokens. `google-services.json` is
gitignored; the build degrades gracefully without it.
