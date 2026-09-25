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
node gapTest.js https://www.reddit.com 48        # over-padding: content shifted by ~topPx, no double gap
node overflowTest.js https://web.telegram.org 48 # app shell / bottom UI pushed off the bottom
node resizeTest.js https://web.telegram.org/k/ 48 # inset survives a keyboard-sized viewport shrink
node latencyTest.js 48                           # how LONG a late-mounted header sits in the band
node dutyTest.js fixtures/autohide.html 48       # what FRACTION of frames the band is dirty
node bandFill.js https://github.com 48           # does the bar's colour reach the top bezel
node scrollHeaderTest.js https://www.bbc.com 48  # headers that CHANGE with scroll position
node insetChangeTest.js --seq=48,100,48          # the INSET ITSELF changing (bar collapse, rotation)
node pairTest.js https://www.reddit.com 48 64    # BOTH spacers together, as they actually ship
./fixtures.sh                                    # offline adversarial header fixtures (no network)
./anchors.sh                                     # offline scroll-padding-top anchor-jump fixtures
./shells.sh                                      # offline viewport-locked app-shell fixtures
./insets.sh --seq=48,0,48                        # every header fixture, inset changing under it
./matrix.sh                                      # deterministic geometry/lifecycle matrix
node transportTest.js                           # real javascript: URL decoding and spacer execution
node pinnedFillTest.js                          # conditional fill after scrolling and dropdown stability
```

### Offline fixtures

`fixtures/headers.html` + `./fixtures.sh` run the script against deterministic, network-free
adversarial header patterns that don't reliably occur in the wild: stacked promo+nav bars, a fixed
header inside a `transform`/`will-change` ancestor (which changes the fixed containing block), RTL,
a sticky header with absolutely-positioned content, a nested-scroll (non-body) sticky header, and a
negative-`top` header. Each fixture puts real content in the band; a PASS means the injection
cleared it. Fast and stable — good as a first gate before the live sweeps.

`matrixTest.js` is the behavioral controller gate. It starts a fresh local document for each
pattern and injects the exact script extracted from `PageBottomSpacer.kt`. The matrix covers
viewport and positive-offset fixed/sticky bars, stacked bars, absolute header children,
fixed-containing blocks (`transform`, `contain`, `will-change`), narrow controls, nested scrollers
that are already safe or unsafe, open-shadow headers, late mounts, author inline rewrites,
disable/re-enable plus inset changes, and negative overlays/full-bleed media/off-band sidebars.
It checks only observable geometry, one effective inset, declaration/`!important` restoration, and
an observer quiet window; it does not assert script substrings or private controller state.

### The probes

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

- **`gapTest.js`** — the *opposite* of `run.js`: over-padding. Matches the same content elements
  before and after injection (by intrinsic identity — text/src/id, not an index path, so SPA
  re-renders still match) and checks the median shift is ~`topPx`. A much larger median, or a
  cluster of elements shifted >1.5x `topPx`, means two inset mechanisms stacked on one subtree.
- **`overflowTest.js`** — bottom clipping. A viewport-height-locked app shell (`height:100vh`, or
  `100%` of a locked `html`/`body`) does not shrink when pushed down, so its bottom `topPx` pixels —
  the bottom nav / player / composer — leave the screen. FAIL only when the document is
  scroll-locked (`overflow:hidden`), where that strip is unreachable forever; in a scrollable
  document the same overflow is just a longer page (reported as `PASS ⚠️`). `./shells.sh` runs the
  offline `fixtures/appshell.html` cases.

- **`resizeTest.js`** — viewport changes. A phone's viewport height moves constantly (soft
  keyboard, collapsing chrome, rotation), and any inset decision made by comparing an element
  against `window.innerHeight` can stop matching. Injects, shrinks the viewport by a keyboard's
  worth, forces a pass, restores, and fails if the inset was lost **or if the mechanism swapped**
  (shell padding falling back to the in-flow spacer re-introduces bottom clipping and thrashes
  layout on every keyboard toggle). Note Chrome emulation resizes the *layout* viewport, so
  `100vh` shrinks with it — the `lockedStaticHeightShell` fixture reproduces the real phone shape,
  where `vh` keeps referring to the large viewport.

- **`latencyTest.js`** — how *long*, not whether. Every other probe snapshots after a generous
  settle, so none of them can see a header sitting under the status bar for a second after an SPA
  route change. This one idles the page until the walk's adaptive cooldown has relaxed, mounts a
  header (`fixtures/latehdr.html`, with and without a `pushState`), and polls until it is inset.
  Budget: 900ms. Variants: `--no-nav` (route swap with no `pushState`), `--sticky` (sticky rather
  than fixed header), `--shell` (scroll-locked app shell, i.e. the shell-padding inset path).
- **`liveLatency.js`** — the same timing question against a real site's own route change. In
  practice most live SPA routes re-render *inside* the already-inset layout and never put anything
  in the band, so this reports N/A a lot; the discriminating cases are `latencyTest.js`'s fixtures.

- **`scrollStabilityTest.js`** — the reader's-eye view. Every other probe injects at scroll-top, but
  ContentHost injects again on load-complete and the observer re-runs whenever the DOM changes, so
  the inset can land while someone is scrolled down reading. Scrolls with real wheel events, marks a
  flow element mid-viewport (content inside fixed/sticky containers is skipped — that is *supposed*
  to move when we inset the container), injects, forces four more passes, and fails if the reference
  moved. `fixtures/scrollanchor.html?case=none` is the case that matters: pages that opt out of
  scroll anchoring get no help from the browser.

- **`dutyTest.js`** — exposure *duty cycle*. `run.js` asks whether the band is clear once things
  settle; `latencyTest.js` asks how long after a mount. Neither can see a header that is inset,
  clobbered by the site's own inline-style writes, re-inset, clobbered again — flickering under the
  status bar for most of the time while every settled snapshot still looks clean. Samples the band
  in a rAF loop and fails above 5% dirty frames. `fixtures/autohide.html` is the discriminating
  case: `?case=transform|cssText|padding|fast` each rewrite the header's inline styles the way a
  real auto-hiding bar does.

- **`bandFill.js`** — does the strip look RIGHT, not just clear. Screenshots the viewport and
  compares actual PAINTED PIXELS in the strip against the bar right below it, so a site with a
  solid-coloured navbar must have that colour running to the top bezel. Mechanism-agnostic, unlike
  `stripColor.js`, which reads the spacer div's declared CSS and is therefore blind on the
  host-padding and app-shell paths. NEVER pass `clip` to `page.screenshot()` here: that goes through
  "capture beyond viewport", which re-renders and drops injected inline styles — a spacer forced to
  solid red still screenshots white, so every coloured site reads as a false FAIL.

- **`scrollHeaderTest.js`** — headers that CHANGE with scroll position, as opposed to `scrollTest.js`'s
  headers that merely become sticky. Drives a full scroll cycle with real wheel events and checks both
  band ink and strip colour at each phase. `fixtures/scrollhdr.html` has the four shapes:
  `?case=autohide|shrink|retop|swap`.

- **`insetChangeTest.js`** / **`insets.sh`** — the inset ITSELF changing. Every other probe injects
  one topPx and asks whether that value is honoured, but topPx is not a constant: ContentHost
  re-injects when the SafariBar collapses or expands, and rotation changes the status-bar height.
  Distinct from `resizeTest.js`, which changes the VIEWPORT and expects the inset to survive
  unchanged — here the hazards are the opposite ones, insets that COMPOUND and values latched
  grow-only that never come back down. Drives a sequence (`--seq=48,100,48`) and checks that exactly
  one mechanism carries the inset, that it equals topPx rather than a sum, and that
  `scroll-padding-top` is not stuck at a taller inset's value.

- **`pairTest.js`** — both spacers at once. Every other probe injects the top script alone, but
  `injectViewportSpacers()` injects the top AND bottom spacers, so two independent MutationObservers
  watch one document and each one's writes are the other's input. Checks that both insets still land
  and — the part no single-script probe can see — that neither observer drives the other, by counting
  passes of each over a quiet window. A feedback loop here costs battery on every page without ever
  showing up as a wrong pixel, which is exactly how one survived: the bottom spacer was re-running
  ~120x/second because writing `style.textContent` is a childList mutation its own observer treated
  as page churn.

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
