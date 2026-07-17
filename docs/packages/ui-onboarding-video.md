# `ui/onboarding/` + `ui/video/` — First-run flow and fullscreen video

Two unrelated small UI packages grouped here.

## `ui/onboarding/`

### `LuminaOnboarding.kt`
The first-run pager host: a welcome hero, the three feature/permission pages, and a finish page.
`onFinish` fires on Skip or on completing with "Get started". The host owns page navigation — the page
composables must not add their own nav buttons.

### `OnboardingFeaturePages.kt`
The three self-contained onboarding pages (Privacy / Search engine / Default browser), each a
vertically-centred column with a large icon, headline, supporting text, and (for the interactive ones)
controls.

## `ui/video/`

Custom fullscreen/PiP video controls that replace YouTube's own player UI (which the "clean player"
in `ui/content/YouTubeCleanPlayer.kt` hides).

### `VideoPlayer.kt`
The state + controller: `VideoPlayerManager` / `VideoPlayerController` read the currently-playing
`<video>` element's position/duration/paused state over the `prompt()` bridge (the same one-way
`javascript:` bridge the reader and link-probe use), and issue play/pause/seek commands back.
`available` stays false until the first successful read so the overlay can hide the scrubber until it
knows the duration.

### `VideoFullscreenOverlay.kt`
The Material 3 overlay: exit-fullscreen + PiP at the top, a bottom bar with play/pause, scrubber and
time labels driven by the controller. Auto-hides after a delay; a tap toggles visibility, and taps
that miss a control pass through to the video beneath.

## When to touch these

- Onboarding copy/pages → `OnboardingFeaturePages` (content) / `LuminaOnboarding` (flow).
- Video controls → `VideoFullscreenOverlay` (UI) + `VideoPlayer` (bridge/state). The page-side
  isolation that makes PiP show only the video lives in `ui/content/YouTubeCleanPlayer.kt`.
