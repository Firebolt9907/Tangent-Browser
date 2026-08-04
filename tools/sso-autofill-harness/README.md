# sso-autofill-harness

Headless-Chrome tests for proactive login autofill (`ui/content/LoginFieldProbe.kt` —
`buildDetectScript` / `buildFillScript` / `buildSubmitScript`). Runs the *exact* scripts extracted
from the Kotlin source — `extractProbeScript.js` resolves the interpolations and flattens the
template the same way Kotlin does — so the harness can't drift from what ships.

```
node run.js             all fixtures
node run.js entra       one fixture
CHROME_PATH=... node run.js
```

Reuses `../top-padding-harness/node_modules/puppeteer-core`; run that harness's install once if it
isn't there.

## What each fixture pins

| fixture | the bug it reproduces |
| --- | --- |
| `entra.html` | An identifier-first enterprise sign-in: **one** document, two views. A hidden `input[type=password]` is present during the email step, and pressing Next swaps in the password view with no navigation. The detector only ever ran on load, so step one (no visible password field) reported nothing and step two (no load callback, so no re-injection) reported nothing either — autofill was silent on the entire flow. |
| `classic.html` | An ordinary one-step form, to pin that the identifier-first work didn't change the common case: still one password-stage report, still fills the login form's username rather than the newsletter box above it, still submits. |
| `nologin.html` | A page with no login that mutates ~30 times a second. The mutation watcher must not be armed here — a watcher that attached unconditionally would put a debounced DOM scan behind every mutation on the whole web. |

The `entra` fixture also asserts two things that are security properties rather than behaviour: the
password is never emitted into the identifier step (which has nowhere to put it), and the submit
script refuses to fire while the stage's visible field is empty.

## Proving the fixtures actually catch something

`LOGINPROBE_KT` points the extractor at another revision of the source. Against the revision before
this work, the `entra` fixture's detector reports **nothing at all** — not on load, and not after the
password view appears:

```
git show <rev>:app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/LoginFieldProbe.kt > /tmp/old.kt
LOGINPROBE_KT=/tmp/old.kt node run.js entra
```

(That revision has no `buildSubmitScript` and no stage-aware `buildFillScript`, so the replay stops
at the first extraction of a builder it doesn't have — the detector half is the part worth replaying,
and it is the part that was broken.)
