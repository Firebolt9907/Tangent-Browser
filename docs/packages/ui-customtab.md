# `ui/customtab/` — The external-link surface

### `CustomTabRoot.kt`
The slim UI for a Custom Tab, hosted by `CustomTabActivity` (see [`top-level.md`](top-level.md)). It is
deliberately **not** `TangentRoot` — there is no tab grid, no tab-switching, and no address bar. It is:

- a single `ui/content/ContentHost` bound to the external link's custom-tab session,
- an optional reader overlay,
- a bottom bar (domain, reload, share, "Open in Tangent", close).

"Open in Tangent" migrates the custom-tab session into a real tab and hands off to `MainActivity`.

### Exit animation (two paths — do not collapse)
This surface owns the subtle back-gesture behaviour documented at length in
[`AGENTS.md`](../../AGENTS.md) → *Two activities / external links*:

- **Resolved close animation** (caller supplied an exit-animation bundle, or a Google-app referrer):
  `CustomTabRoot` registers a lowest-priority `PredictiveBackHandler` that shrinks the whole UI into an
  opaque rounded card tracking the gesture, then commits through `Activity.finish()` with the client
  APK's animation. Required for home-screen Discover launches, whose close the launcher animates
  nothing for.
- **Plain `ACTION_VIEW`**: with no web history, `CustomTabRoot` registers **no** back callback, so Back
  finishes the activity and Android plays its native predictive-back animation straight back to the
  caller.

## When to touch this package

- Custom Tab chrome (bottom-bar actions, reader toggle) → here.
- The exit-animation logic is a known-good, hard-won combination — read the `AGENTS.md` section (and
  its "tried and reverted" notes) before changing it.
