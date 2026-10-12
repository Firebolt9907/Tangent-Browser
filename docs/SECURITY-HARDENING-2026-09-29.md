# Security hardening — 29 September 2026

Reviewed and implemented by Jeffrey Turkstra. This pass adds to the existing uncommitted
autofill/add-on review; it does not claim authorship of those changes or constitute a complete audit.

## External URL parsing

CustomTabActivity forwards IntentUrls output directly to the Custom Tabs use case. The previous
test assumed schemeless input always passed through normalizeUrl, which is not true on that path.
Embedded ASCII controls could prevent the scheme regex from detecting a privileged scheme before
an engine URL parser discarded the controls. Reject C0 and DEL characters after surrounding
whitespace is trimmed. Ordinary web addresses and search text retain their existing treatment.
Tests cover every control byte in script/file schemes and web URL paths. Device-level exploitation
was not attempted; the regression establishes rejection at the application's exported boundary.

## Backup and device transfer

The exclusion list covered selected credential databases but left other state eligible by default:
remembered form values, device-specific unattended-autofill approvals, tab engine state (including
form data), thumbnails and future stores. Both Android rule formats now include only three reviewed
preference files. The explicit include list also keeps Gecko profile and account state out without
depending on implementation-specific directory names.

This changes restore behavior: local tabs, history, bookmarks, site sessions and approvals are not
restored by Android backup/device transfer. Firefox Sync is unaffected. Existing local data is not
deleted. These rules do not retroactively erase previously uploaded backups. An XML regression checks
legacy backup, modern cloud backup and modern device transfer have the same nonempty allowlist.

Android's include-list behavior is documented in
[Auto Backup](https://developer.android.com/identity/data/autobackup).

## Passkey relying-party boundary

The bridge previously treated any dot-separated parent as registrable, including public suffixes
such as com, co.uk and github.io. Parent delegation now uses the full public suffix list already
bundled in Guava, including private hosting suffixes. Unknown parent suffixes fail closed; exact-host
RPs remain available for internal names. The localhost exception permits HTTP specifically, rather
than arbitrary schemes, and serialized origins cannot carry user info, paths, queries or fragments.

Providers consume requestJson, a second representation of the RP. The isolated script reads
page-owned options multiple times, so accessor properties could yield conflicting values. The
bridge now writes the validated RP into the provider's get/create JSON. Tests use a fake provider
to check both representations and prove rejected RPs never invoke it. Other ceremony fields and
the browser-owned client-data hash retain their existing behavior.

References: [WebAuthn RP scope](https://www.w3.org/TR/webauthn-2/#relying-party-identifier),
[Guava public suffix API](https://guava.dev/releases/33.3.1-android/api/docs/com/google/common/net/InternetDomainName.html).
The embedded PSL needs maintenance with dependency updates. The bridge currently installs only in
top-level documents (`all_frames: false`); iframe permissions behavior was not broadened.

## Log exposure

Disable the temporary Gecko web-console-to-logcat setting. Link-rectangle and theme-color parse
failures now emit constant messages without page payloads or exceptions that could repeat them.
This reduces diagnostic exposure; it is not a claim that ordinary apps can read all system logs.

## Validation and remaining work

Targeted JVM security tests passed for external URLs, backup rules, passkey bridge/provider
boundaries, WebAuthn mode preferences and authenticator behavior. Android resource processing and
Kotlin compilation passed. The first full run had 508 passes and one failure in the existing
OverviewCardMotionTest.backgroundCardsShareReleaseVelocityButHaveLessThrow animation test. The final
full run passed 511 of 512 tests, with that same single animation failure. Whitespace checks passed
for the tracked files changed by this pass.

Real-device backup/restore, Gecko intent dispatch and USB/NFC passkey ceremonies still need device
validation. No production accounts or credentials were used. Dependency CVE triage, complete native
engine analysis and a comprehensive JavaScript bridge audit were not performed in this pass.

## Continued loop: one-time-code delivery

OTP detection previously discarded the reporting tab ID, and SMS results used a global callback
that loaded the currently selected session. A tab switch or cross-origin navigation during the
SMS consent flow could therefore redirect the code. Detection now requires the selected tab and
a browser-maintained trusted origin; delivery retains that target and the captured engine session.
The JavaScript filler checks the canonical origin again before touching any fields. Page-supplied
payload URLs do not select the target. Tab/navigation changes retire the manual paste banner.

The SMS controller retains separate listening and consent callbacks. Repeated field reports cannot
replace either pending destination, consent results are consumed once, and codes expire after five
minutes from arming. An expired but still-open consent dialog blocks re-arming until its result
returns, preventing an old result from being delivered to a newer request. Disposal clears pending
delivery. This intentionally favors dropping a stale code over redirecting it; manual paste remains
available after a fresh selected-page detection. The existing GMS sender permission remains intact.

This binds tab, session and origin, not a particular document within that origin. SMS User Consent
also does not authenticate which website a message belongs to; the system consent UI still matters.

## Continued loop: image actions

Copy/share/save/OCR previously read entire network bodies into memory and decoded OCR bitmaps at
full dimensions. Transfers now enforce a 20 MiB limit on actual streamed bytes, independently of
Content-Length, with equivalent bounds before and after data-URL decoding. Non-image MIME types
are rejected instead of silently relabeled JPEG. OCR inspects dimensions first and chooses a
power-of-two sample to keep the decoded image near four million pixels. HTTP(S) URLs remain
supported, including local sites; this is resource hardening, not a private-network access policy.

Shared image filenames previously used the 32-bit URL hash and overwrote the same file on every
copy. A recipient retaining a FileProvider grant could then observe replacement content after a
different image collided or the same URL returned new bytes. Filenames now use SHA-256 of the bytes,
and complete files are published by rename, preserving deduplication without reassigning an existing
URI to different content. Existing legacy cached files are not deleted or reused by the new naming.

Images over the limit or servers missing a valid image MIME type now fail the image action. OCR
downsampling can reduce recognition of very small text in huge images. No bitmap-decoder vulnerability
or device-wide denial of service was claimed or tested.

### Continued validation

- Eleven new JVM tests cover OTP selection/origin changes, expiry, cancellation, disposal, callback
  ownership, bounded/fragmented/endless streams, MIME rejection, image identity and extreme dimensions.
- `node tools/otp-security-harness.js` executes the fill template against a synthetic DOM: foreign
  host/scheme/port and opaque origins refuse filling; matching single and split fields fill.
- Final `./gradlew :app:testDebugUnitTest --offline`: 522/523 passed, with only the same existing
  `OverviewCardMotionTest.backgroundCardsShareReleaseVelocityButHaveLessThrow` failure.
- Compilation and whitespace checks passed. No real SMS, credentials or network services were used
  by the new fixtures. Real Android SMS consent, clipboard grant and bitmap decoding tests remain.
