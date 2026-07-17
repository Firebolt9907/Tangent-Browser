<!-- Thanks for contributing to Expedition Browser! -->

## What & why

<!-- What does this change do, and why? Link any issue it closes (e.g. "Closes #12"). -->

## Testing

<!-- How did you verify it? e.g. "ran on Pixel 7 / Android 14, exercised the tab-switch gesture". -->

## Invariant check (see AGENTS.md)

- [ ] No new `EngineView.render()` call outside `ContentHost` (invariant #1).
- [ ] Gesture / grid / snapshot changes respect the state-variable splits (#4, #5).
- [ ] Any new JS injection sets the `suppressJavascriptLoadCallback` guard (#11).
- [ ] No secrets, keystores, `google-services.json`, or extracted dependency artifacts committed.
- [ ] `./gradlew :app:testDebugUnitTest` passes locally.
