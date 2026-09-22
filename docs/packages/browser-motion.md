# Browser motion

`ui/motion/BrowserMotionEngine.kt` is the activity-scoped frame driver for the main browser's
explicit scalar animations and Animatable transitions. Custom Tabs use another instance for their
own lifecycle. It sleeps when idle and samples active transitions against the Compose frame clock;
existing Animatable holders receive that same timestamp through a broadcast frame clock.

A scalar channel names a physical coordinate (`tab-switch`, `back`, `pull-distance`), not a
commit/cancel animation. Retarget at the rendered position to inherit velocity, or supply the
finger's measured release velocity. Gesture deltas still write plain state directly. `track`
provides signed velocity for system predictive-back events and rejects stale gesture samples.
Cancellation removes only the flight it owns and cannot run its completion action. Engine disposal
cancels waiting animations. The driver's scalar path honors MotionDurationScale; Animatable keeps
Compose's own duration-scale support.

`BrowserPhysics` owns the common critical-damping/stiffness response for page transitions, overview,
bar collapse, card dismiss/cancel, pull retraction, reader takeover and layout reveals. Velocity changes the physical state, not the
spring coefficients. Pixel and normalized coordinates use different completion tolerances.
Overview opening scales X velocity by the remaining Y distance so their normalized phases match.
The separate overview drag floats and spring-only Animatable holders remain intact (AGENTS #4).

Scope: this consolidates the main gesture choreography, not every animation in the app.
Declarative child transitions, deck scroll/decay, and Material components still use their existing
Compose drivers. Keyboard tracking, Material sheet companions and Custom Tab window-exit handoffs
retain their externally dictated curves. Do not replace those with an independent spring without
also changing their owner. Loading progress is informational and retains its linear sweep.

JVM coverage checks retarget continuity, gesture velocity, stale samples, coordinated completion,
replacement cancellation, and overview phase matching. On-device validation is still required for
rapid switch→overview re-grabs, open/close interruption, back cancellation, all overview modes,
60/120 Hz, and disabled animations. This does not eliminate Gecko/bitmap/layout frame costs.

Visual tuning uses stiffness 440 with critical damping: a zero-velocity release travels about 90%
of its distance in 185 ms and 99% in 315 ms (not a fixed duration; gesture velocity still matters).
Wide-screen tab swipe depth is restrained to 93% scale and 22% maximum dimming. Those visuals
follow the existing gesture progress directly, so they add no extra animation or delayed settle.

## Interruption

`interrupt(channel)` and `interrupt(axis)` cancel the owning continuation before input writes a
new pose. `track` interrupts scalar motion automatically. Samples survive cancellation so a new
spring at the rendered pose inherits momentum. Axis ownership is identity-checked: cleanup from an
older animation cannot unregister its replacement. `MotionInterruptedException` distinguishes
user takeover from a cancelled system back gesture; takeover must not launch a stale rollback.

Pill re-grabs stop settling motion at drag recognition (touch slop remains intact for button taps),
retain incoming-tab horizontal position, and clear the overview's pending-close gate. Already
committed tab intentions still flush before snapshot teardown, per AGENTS.md. An uncreated new-tab
preview is cancelled when its close is re-grabbed. Programmatic overview reopening preserves both
axis poses/velocities; predictive back captures the current opening pose rather than assuming 1.
Tapping a collapsing bar reverses its spring instead of snapping it expanded.

Overview release geometry uses the independent 0→1 X spring, rather than dividing by the
remaining overview progress. A drag at progress 1 still animates its captured position/scale
back into the deck or grid. Deck/pixel captions use `overviewCardPose` just like their snapshot,
including release, cancel and closing transforms; their reveal follows the release spring.
