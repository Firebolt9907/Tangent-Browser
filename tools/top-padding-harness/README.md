# Top-padding injection harness

A headless-Chrome harness that verifies the **top viewport-spacer JavaScript** Tangent injects on
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
./verify.sh                     # sweep every URL in sites.txt at topPx=48 (initial load)
./verify.sh 60                  # custom inset
node run.js https://gemini.google.com 48         # one site, full before/after band dump
node run.js https://gemini.google.com 48 --show  # headed, watch it
node debug.js https://gemini.google.com 48       # dump positioned elements near the top
node spaTest.js https://x.com 48                 # invariant #11: spacer self-heals across SPA nav
node scrollTest.js https://medium.com 48         # scroll-activated sticky headers get inset
node anchorTest.js https://en.wikipedia.org/wiki/CSS '#History'   # scroll-padding-top anchor jump
./fixtures.sh                                    # offline adversarial header fixtures (no network)
./anchors.sh                                     # offline scroll-padding-top anchor-jump fixtures
```

### Offline fixtures

`fixtures/headers.html` + `./fixtures.sh` run the script against deterministic, network-free
adversarial header patterns that don't reliably occur in the wild: stacked promo+nav bars, a fixed
header inside a `transform`/`will-change` ancestor (which changes the fixed containing block), RTL,
a sticky header with absolutely-positioned content, a nested-scroll (non-body) sticky header, and a
negative-`top` header. Each fixture puts real content in the band; a PASS means the injection
cleared it. Fast and stable — good as a first gate before the live sweeps.

### The three probes

- **`run.js`** — initial page load. Fails if readable content/icons paint in the `[0, topPx)` band
  after injection. `verify.sh` sweeps `sites.txt` with this one.
- **`spaTest.js`** — AGENTS.md **invariant #11**. Injects once, drives a client-side (pushState)
  navigation, and re-probes *without* re-injecting: the spacer div + `scroll-padding-top` must
  persist and the scroll-top band stay clear. Full page loads are reported N/A (that's ContentHost's
  re-inject path). Probes at scroll-top (content scrolling under a translucent bar is normal).
- **`scrollTest.js`** — scroll-activated sticky/fixed headers. Uses real wheel events (down, then
  up) so scroll-direction detection fires and a condensed/re-pinned header actually reveals; then
  checks that no *pinned* header (fixed/sticky, sitting at the top and short enough to be a bar —
  not a full-page `sticky; top:auto` container) has readable content in the band. Normal scrolled
  flow content in the band is expected and ignored.
- **`anchorTest.js`** — the `scroll-padding-top` half of the injection. Triggers an in-page anchor
  jump (clicks a link, sets `location.hash`, or — in **`auto`** mode — follows the first real
  below-fold `#`-link on the page) and asserts the target lands below the inset **and is not covered
  by a pinned fixed/sticky header** (occlusion check via `elementFromPoint` at the target's top
  edge), not merely `top >= topPx`. The injected script sets `scroll-padding-top` and raises it to
  the bottom of any wide top-pinned nav bar (see `PageBottomSpacer` invariants).

## Known limitation — scroll-dependent headers with a custom smooth-scroll

Sites whose top header **grows taller when scrolled** (e.g. investopedia.com: a 96px header that
expands to 108px on scroll) **and** that run their **own** JS smooth-scroll to a self-computed offset
can defeat the `scroll-padding-top` approach: the anchor jump uses the at-rest header height, the
site's animation lands the target at its own (too-small) offset, and the header then expands over the
target's top ~12px. `scroll-padding-top` is raised correctly (to 108) but the site's own scroll wins.
A corrective re-scroll was prototyped and **rejected** — the trigger (hashchange / history hooks)
is unreliable because such sites set the hash via a cached `history` reference, and a scroll-settle
re-scroll fights the site's own animation and risks yanking the user. Impact is a ~12px clip that
self-corrects on the next interaction (the grow-only `scroll-padding-top` latch converges). Treated
as an accepted edge, not a shipped fix.

## What PASS means

A site **passes** when no readable **text, icon, image, or interactive control** paints inside the
`[0, topPx)` band after injection. A header/bar's own **opaque background** filling that strip is
expected and desired (the translucent status bar sits over it), so background-only fills are not
counted as failures. Likewise a **large full-bleed top media surface** (a hero image, map, or video
canvas — tall, ≥60% width, pinned to the very top) is meant to run edge-to-edge under a translucent
status bar, so it is not counted either; the injected script deliberately leaves such surfaces
alone (its `bandShift` is bounded to ≤160px). Only small media (logos/icons) count as band content.
