# dark-mode-harness

Headless-Chrome tests for web dark mode's **dynamic tier** (`ui/content/WebDarkMode.kt`,
`buildDynamicScript` + `buildRemoveScript`). Runs the *exact* scripts extracted from the Kotlin
source — `extractDarkScript.js` resolves the `${CONST}` interpolations and flattens the template the
same way Kotlin does — so the harness can't drift from what ships.

```
node run.js             all fixtures (dynamic tier)
node run.js shadow      one fixture
node detect.js          the already-dark detector (see below)
node perf.js            cost + main-thread blocking on a 12k-element page
CHROME_PATH=... node run.js
```

## What each fixture pins

Every fixture reproduces a specific way the tier used to leave elements light. Each asserts two
things: **coverage** (the elements it points at end up dark) and **restore** (after the teardown
script every element's inline declarations are identical to before injection, across shadow roots
and same-origin iframes).

| fixture | the bug it reproduces |
| --- | --- |
| `long.html` | 12,000 rows. The old walk took `min(length, 8000)` from index 0 on *every* pass, so rows past 8000 were never reached no matter how many passes ran. |
| `shadow.html` | Open shadow roots are invisible to `querySelectorAll`, so component subtrees stayed light. |
| `classswap.html` | An element that gains a light background 400 ms after load. Elements were latched done on first visit and attribute mutations weren't observed. |
| `pseudo.html` | `::before` can't take an inline style, so a light pseudo survived the recolor. |
| `iframe.html` | Same-origin iframes are separate documents the walk never entered. |
| `bgimage.html` | A light `url()` background stayed glaring (and text over it went invisible); also asserts a 16px icon is *not* scrimmed. |
| `inline.html` | Teardown blanket-removed color properties from every element, wiping colors the **page** had set inline — the page stayed wrong until a reload, which is what made dark mode look like it couldn't be turned off live. |

## Proving the fixtures actually catch something

`WEBDARKMODE_KT` points the extractor at another revision of the source, so any fixture can be
replayed against the implementation it was written to catch. All seven fail against the revision
before the rewrite:

```
git show <rev>:app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/WebDarkMode.kt > /tmp/old.kt
WEBDARKMODE_KT=/tmp/old.kt node run.js     # 7/7 fixtures failed
node run.js                                 # all 7 fixtures passed
```

Do this whenever you add a fixture — a fixture that passes against the buggy code is testing
nothing. (`classswap.html` originally did exactly that: the panel started *dark*, so the old walk
pinned that dark color inline with `!important`, which masked the later light class by accident.
It only discriminates because the panel now starts transparent.)

## detect.js — the already-dark detector

`node detect.js` runs the real `buildApplyScript` output against a second fixture set and asserts the
observable contract: does `<html>` end up carrying the invert class? A **false negative** here (we
invert a page that is already dark) is the most visible way the whole feature breaks, so both
directions are pinned.

| fixture | what it pins |
| --- | --- |
| `detect-wrapper-dark.html` | The document background stays at the light default and the dark surface is painted on a full-bleed wrapper — how most sites, Google included, are actually built. Reading `body`/`html` alone calls this light and inverts it on top of its own dark theme. |
| `detect-scheme-dark.html` | `:root{color-scheme:dark}` with nothing declaring an opaque background. "No background" does **not** mean white: the UA paints the canvas, and under a dark color-scheme that canvas is dark. |
| `detect-meta-scheme.html` | Same, via `<meta name="color-scheme" content="light dark">`, which the forced dark preference resolves to dark. |
| `detect-late-dark.html` | A theme that lands at 1.8 s, past the old last re-check at 1.2 s. |
| `detect-hero.html` | **The false-positive guard.** A genuinely *light* page whose dark hero fills the viewport must still be inverted. This is why the viewport probe reports the *lightest* surface it found rather than the average — that encodes unanimity, and biases toward applying the filter. |
| `detect-plain-light.html` / `detect-plain-dark.html` | The ordinary cases still decide the way they always did. |

The first four fail against the revision before this work; the last three pass on both:

```
git show <rev>:app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/WebDarkMode.kt > /tmp/old.kt
WEBDARKMODE_KT=/tmp/old.kt node detect.js     # 4 of 7 fixtures failed
```

## Reading perf.js

Two numbers, and they mean different things:

- **total** — wall time until the walk has darkened everything. Compare it *only* alongside the
  reported coverage; finishing early by giving up is not faster.
- **maxTask** — the longest single main-thread task, i.e. what a user feels as jank. The frame
  budget exists to keep this small; the pre-rewrite walk did everything in one synchronous pass.

Measured on `long.html` (12,000 elements) at the time of the rewrite:

| | elements darkened | total | worst task |
| --- | --- | --- | --- |
| before | 7,999 / 12,000 | 55 ms | 52 ms (cold run) |
| after | 12,000 / 12,000 | 65 ms | none over 50 ms |

50% more elements in 18% more wall time — about a 21% lower per-element cost, while also doing
strictly more work per element (pseudo probes, background-image checks, shadow/iframe adoption).
The long task is gone.
