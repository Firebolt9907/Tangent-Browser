# `ui/reader/` — Reader mode

Safari-style reader mode: strip a page down to its article, render it as native Compose, and layer
extras on top (table of contents, listen-to-page TTS, long-screenshot capture). Extraction runs in an
isolated content script; the view is entirely native, which is what lets features like scroll-capture
work where a web page can't.

## The extraction pipeline

1. **`ReaderExtractor.kt`** (~900 lines) — the generic article-extraction heuristic (readability-style
   scoring of the DOM) plus the content-script glue. This is the biggest file; it decides what counts
   as the article body, title, byline, lead image, etc.
2. **`ReaderRules.kt`** — the per-site override layer over that heuristic. `include` = CSS selector(s)
   for the real article container (bypasses the heuristic for pages it fails on); strip rules remove
   junk. These are the "some sites need a nudge" fixes. (There's a signed remote update channel for
   these rules — see the reader-rules memory / `docs/`.)
3. **`ReaderPortHandler.kt`** — receives the extracted article JSON from the content script over a
   native messaging `Port` (the script runs in an isolated world the page can't observe) and routes it
   to `ReaderManager.setReaderEligible`.
4. **`ReaderModeState.kt`** — the `Context`-less `ReaderManager` singleton + persisted appearance
   (theme / font size / font / Google-cache toggle). Both surfaces hydrate it on launch (main browser
   mirrors changes back to the store; Custom Tabs may start without `MainActivity`).

## The view

- **`ReaderView.kt`** (~980 lines) — the reader surface itself: renders the extracted article with the
  chosen theme/typography, owns scroll and layout.
- **`ReaderViewHelpers.kt`** — non-composable helpers for it (image-request building, read-time
  estimate, host formatting, font resolution) — unit-testable plumbing kept out of the composable.
- **`ReaderToggle.kt`** — the reader on/off affordance. Also holds the **allow-list of privileged
  schemes** a reader-content link may never be handed to `startActivity` (a reader tap fires an
  implicit intent directly, unlike an in-browser navigation, so `file:`/`content:`/`intent:` links in
  a hostile article are a real threat — this is a security boundary).
- **`ReaderImageViewer.kt`** — fullscreen gallery with focal-point pinch zoom (1–6×),
  bounded panning, swipe/arrow navigation, captions, retry, share-link and open-original actions.
  Entrance/exit and gallery paging animate; tapping the image toggles controls. Zoom uses pinch
  gestures only, with a read-only percentage indicator.
- **`ReaderZoom.kt`** — shared pinch arbitration and fitted-image pan geometry. Article pinches commit
  the existing persisted text-size preference (50–200%) at gesture end, reflow text and retain the
  approximate reading position. Single-finger scroll and text selection stay available.
- **`ReaderTableOfContents.kt`** — the floating TOC button + its bottom-sheet dialog; tapping a heading
  smooth-scrolls the article to that heading's captured offset.

## Extras

- **`PageSpeaker.kt`** — "Listen to Page": reads the extracted article aloud via platform
  `TextToSpeech`. Singleton (playback survives the aA sheet opening/closing); application-context, so
  not tied to an Activity.
- **`ReaderScrollCapture.kt`** — bridges reader mode into Android's system "long screenshot" (scroll
  capture). Web pages can't participate (GeckoView renders to an out-of-process Surface), but reader
  mode is native Compose, so it can implement `ScrollCaptureCallback`.

## When to touch this package

- A site doesn't enter reader mode, or includes junk → add a `ReaderRules` override, not a change to
  the generic heuristic.
- Reader appearance / typography → `ReaderView` + `ReaderViewHelpers` + `ReaderModeState` (persisted
  prefs).
- Never relax the `ReaderToggle` privileged-scheme allow-list without understanding the intent-launch
  threat above.
