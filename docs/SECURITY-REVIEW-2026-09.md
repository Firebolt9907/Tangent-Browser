# Security review: proactive autofill and add-on interception

This source review fixes application-level trust boundaries. It is not a complete browser audit or
an assessment of Gecko's native implementation or dependency CVEs.

## Proactive credentials crossed origin boundaries

`ProactiveLoginAutofill` queried `getByBaseDomain` and only filtered out empty passwords. A base-domain
result is a candidate list, not authorization to disclose a credential to every sibling host, port,
or HTTP version of its saved origin. The proactive path bypasses Gecko's normal fill decision. A
single candidate with an existing Always Autofill opt-in could also enter the unattended IdP path.

The probe additionally accepted the URL from the message payload while discarding the delegate's tab
ID. The nonce guard remains, but a valid bridge message must not choose the credential lookup origin.

The fix derives the target from the delegate's tab ID and the browser-maintained trusted-origin
registry. Only the selected tab can raise an offer, and credentials must match its exact web origin.
Default HTTP/HTTPS ports normalize to their implicit equivalents. Unknown or opaque origins fail
closed. This deliberately makes proactive suggestions narrower; sibling-host credentials are no
longer proactively offered.

## Pending fills could move to another session or origin

The proactive picker used `rememberUpdatedState(engineSession)` as its fill destination after storage
queries and biometric callbacks. A tab switch during those waits could send the chosen password to
the new tab. Navigation in the same session could similarly replace the destination document.

The fix captures the originating engine session and tab/origin target. It checks the live selection
and origin after lookup and again after biometric approval. Delayed submit uses that same captured
session. The injected fill and submit scripts check `location.origin` on every execution/retry,
covering navigation between native dispatch and script execution. Identifier-to-password continuation
is also bound to tab and origin. Invalid fill-script origins fail closed.

The lookup effect now consumes its pending event after asynchronous work completes: consuming its
own key before the first suspension could cancel the lookup on recomposition.

## Automatic add-on interception accepted unauthenticated schemes

`isAddonDownloadUrl` validated an AMO hostname and download path without validating the scheme. For
example, `http://addons.mozilla.org/x.xpi` and `file://addons.mozilla.org/x.xpi` passed the filter.
Automatic interception now requires HTTPS, the default HTTPS port, and no user-info component, in
addition to the existing AMO host/path checks. Mixed-case HTTPS remains supported through resolution.

This closes an application policy gap; it is not evidence that Gecko's signature verification or
extension permission prompt could be bypassed. Explicit manual installation retains its existing
URL policy.

## Validation

- `./gradlew :app:testDebugUnitTest --offline`
- `node tools/sso-autofill-harness/security.js`: synthetic, locally intercepted origins; mismatched
  host/scheme/port refuse filling and delayed submission; the matching origin fills and submits.
- `node tools/sso-autofill-harness/run.js`: existing identifier-first, classic login, and non-login
  fixtures pass.

JVM regressions cover forged payload URLs, absent trusted origins, exact credential matching,
selection/navigation invalidation, script guards, and unsafe add-on schemes/authorities. Browser
fixtures execute in headless Chrome; device-level Gecko/Compose biometric race testing remains to
be performed. No real credentials, accounts, or remote services were used in the fixtures.
