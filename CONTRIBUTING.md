# Contributing to Tangent Browser

Thanks for your interest! Tangent is a Compose + GeckoView Android browser. This guide covers how
to build it, the conventions to follow, and the invariants you must not break.

> The codebase still uses the project's original name, *Tangent*, in its package namespace
> (`com.firebolt9907.tangentbrowser`) and class names (`TangentRoot`, `TangentApplication`, …). Those
> symbols are Tangent — a full code rename is pending.

## Getting set up

Requirements: a recent **Android Studio** (or the command-line SDK), **JDK 17+**, and an
`arm64-v8a` device or emulator (the dev build ships that ABI only).

```sh
git clone <your-fork-url>
cd TangentBrowser
./gradlew :app:installDebug          # build + install the debug APK
./gradlew :app:test                  # JVM unit tests
```

`local.properties` (your SDK path) is generated locally and gitignored. Firebase / FxA push is
optional — see the build notes in the [README](README.md) and [`AGENTS.md`](AGENTS.md).

## Before you write code: read `AGENTS.md`

[`AGENTS.md`](AGENTS.md) is the most important file in the repo for a contributor. It documents the
non-obvious **invariants** — single-`EngineView`-per-activity, the snapshot-capture cache-key
discipline, the grid-progress state split, the two Custom Tab exit paths, why FxA push is inert, and
more. These are not stylistic; each one guards against a real, subtly-reproducing regression. If a
line of code looks strange, search `AGENTS.md` and [`IMPLEMENTATION.md`](IMPLEMENTATION.md) before
"fixing" it.

## Conventions

- **Kotlin + Compose, Material 3.** Match the style of the file you are editing — naming, comment
  density, and idiom.
- Keep the **single Gradle module** layout; put new code in the package that matches its
  responsibility (see the repo layout in the README).
- **URL handling** goes through `normalizeUrl` / `compactHost` in
  `state/GeckoTabRepository.kt`. Do not add a second normalization path.
- **New JavaScript injection sites** must respect the `suppressJavascriptLoadCallback` guard in
  `ContentHost`.
- Prefer small, focused composables. `TangentRoot.kt` is large and being incrementally decomposed —
  new UI should generally be extracted into its own file rather than added inline.

## Submitting changes

1. Branch off `main` (or the relevant feature branch).
2. Make your change; keep commits focused and messages descriptive.
3. Run `./gradlew :app:test` and, for anything touching runtime behavior, exercise the affected flow
   on a device/emulator.
4. Open a pull request describing **what** changed and **why**, and call out any invariant in
   `AGENTS.md` your change interacts with.

### Review checklist

- [ ] Builds (`./gradlew :app:assembleDebug`) and unit tests pass.
- [ ] No new `EngineView.render()` call outside the one site in `ContentHost` (invariant #1).
- [ ] Gesture / grid / snapshot changes respect the state-variable splits in `AGENTS.md` (#4, #5).
- [ ] No secrets, keystores, `google-services.json`, or extracted dependency artifacts committed.
- [ ] User-facing strings and per-site behavior degrade gracefully without a Firebase project.

## Reporting bugs

Open an issue with the device/Android version, steps to reproduce, and a logcat excerpt if it
crashes. For **security** issues, follow [`SECURITY.md`](SECURITY.md) instead of filing a public
issue.

## License

By contributing, you agree that your contributions are licensed under the project's
[GPL-3.0](LICENSE) license.
