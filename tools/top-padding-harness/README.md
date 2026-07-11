# Top-padding injection harness

A headless-Chrome harness that verifies the **top viewport-spacer JavaScript** Lumina injects on
every page (`buildViewportSpacerScript` in
`app/.../ui/content/PageBottomSpacer.kt`, see `AGENTS.md` §11) actually clears the status-bar /
SafariBar top safe area on real sites — including the subdomains and app-shell layouts that are
hard to reason about statically (`gemini.google.com`, `news.google.com`, Angular/React SPAs, …).

It extracts the **exact shipping script bytes** from the Kotlin source (no re-implementation), runs
them in a mobile-emulated Chrome page against a live URL, and reports whether any readable
content still paints in the `[0, topPx)` band after injection.

## Why Chrome and not GeckoView

The injected script is portable DOM/CSS (computed styles, `position: fixed/sticky/absolute`
geometry, `MutationObserver`, `IntersectionObserver`) — the layout questions it answers resolve the
same way in Chromium and Gecko. Chrome is drivable headless on a dev machine with no device, so it's
the fast feedback loop; on-device Gecko spot-checks remain the final word.

## Setup

```sh
cd tools/top-padding-harness
npm install                     # puppeteer-core only (uses your installed Chrome, no download)
export CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"  # if non-default
```

## Use

```sh
./verify.sh                     # sweep every URL in sites.txt at topPx=48
./verify.sh 60                  # custom inset
node run.js https://gemini.google.com 48         # one site, full before/after band dump
node run.js https://gemini.google.com 48 --show  # headed, watch it
node debug.js https://gemini.google.com 48       # dump positioned elements near the top
```

## What PASS means

A site **passes** when no readable **text, icon, image, or interactive control** paints inside the
`[0, topPx)` band after injection. A header/bar's own **opaque background** filling that strip is
expected and desired (the translucent status bar sits over it), so background-only fills are not
counted as failures. Full-bleed surfaces meant to run edge-to-edge (e.g. a map canvas) are omitted
from `sites.txt` by design.
