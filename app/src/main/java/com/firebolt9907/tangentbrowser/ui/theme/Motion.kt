package com.firebolt9907.tangentbrowser.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * The **Material 3 Expressive** motion scheme, as app-side constants.
 *
 * Expressive motion is spring-based, not duration-based, and splits into two families:
 *
 * - **Spatial** — anything that moves, resizes or reshapes (slide, expand/shrink, offset, scale).
 *   Under-damped, so it overshoots slightly and settles. That overshoot *is* the expressive
 *   quality; a `tween` cannot produce it at any duration or easing.
 * - **Effects** — anything that does not move (alpha, colour, elevation). Critically damped
 *   (`dampingRatio = 1f`), because a bouncing opacity reads as a flicker rather than as energy.
 *
 * Each family has fast/default/slow tiers. Pick by the size of the change, not by taste: `fast` for
 * small controls (a chip, a switch, an icon), `default` for the common case (a row expanding, a
 * pane sliding), `slow` for large surfaces covering much of the screen.
 *
 * ### Why these are constants and not `MaterialTheme.motionScheme`
 *
 * material3 1.4.0 ships the expressive scheme but does not publish it — `MotionScheme.expressive()`,
 * `MaterialExpressiveTheme` and even `ExperimentalMaterial3ExpressiveApi` are all `internal` in that
 * release, so no call site can install or read it (trying gives "Cannot access … it is internal in
 * file"). The values below are lifted from the library's own `ExpressiveMotionTokens`, so app motion
 * matches what M3 components will animate on once the API opens up. **If material3 is bumped to a
 * release where `MaterialTheme.motionScheme` is public,** point [TangentBrowserTheme] at
 * `MaterialExpressiveTheme` and re-point these functions at the theme value — the names here
 * deliberately mirror the `MotionScheme` interface so that becomes a rename, not a rewrite.
 *
 * ### Do NOT use these for motion that must track an M3 component
 *
 * Because the expressive scheme cannot actually be installed, every material3 component in the app
 * still animates on the **standard** scheme. Anything that has to move in lockstep with one — the
 * scaled-page backdrop behind a `ModalBottomSheet`, a companion overlay riding a sheet — must keep
 * using the standard-scheme mirrors in `TangentMotion` (`SheetShow`/`SheetHide`), or it visibly
 * desyncs. These expressive specs are for **app-owned** motion, where nothing else is animating
 * alongside on a library spring.
 */
object ExpressiveMotion {
    // One instance per tier, reused across every call. A SpringSpec carries no type-specific state
    // — the `TwoWayConverter` is supplied later, at vectorization — so a single `SpringSpec<Any>` is
    // safe to hand out as any `FiniteAnimationSpec<T>`. This is exactly how material3's own
    // MotionScheme implementations do it, and it keeps these callable from composition without
    // allocating a fresh spec on every recomposition.
    private val DefaultSpatial = spring<Any>(dampingRatio = 0.8f, stiffness = 380f)
    private val FastSpatial = spring<Any>(dampingRatio = 0.6f, stiffness = 800f)
    private val SlowSpatial = spring<Any>(dampingRatio = 0.8f, stiffness = 200f)
    private val DefaultEffects = spring<Any>(dampingRatio = 1f, stiffness = 1600f)
    private val FastEffects = spring<Any>(dampingRatio = 1f, stiffness = 3800f)
    private val SlowEffects = spring<Any>(dampingRatio = 1f, stiffness = 800f)
    private val DefaultSpatialPx =
        spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = 0.5f)
    private val DefaultEffectsPx =
        spring(dampingRatio = 1f, stiffness = 1600f, visibilityThreshold = 0.5f)

    /** Spatial motion for the common case — a row expanding, a pane sliding in. */
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = DefaultSpatial.cast()

    /** Spatial motion for small controls — a chip, a switch, an icon rotating. */
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = FastSpatial.cast()

    /**
     * [defaultSpatialSpec] for a value measured in **raw pixels** (a drag offset, a translation).
     *
     * Identical spring, but `visibilityThreshold` is half a pixel instead of the `Float` default of
     * `0.01`. That default assumes a normalized 0..1 value; against a pixel offset it is a hundredth
     * of a pixel, so the spring keeps emitting frames long after the motion is visually finished.
     * Prefer this for anything animating a `translationX`/`translationY`-style quantity.
     */
    fun defaultSpatialPxSpec(): FiniteAnimationSpec<Float> = DefaultSpatialPx

    /** Spatial motion for large surfaces — a sheet, a full-screen overlay. */
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = SlowSpatial.cast()

    /** Non-spatial motion (alpha, colour, elevation) for the common case. */
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = DefaultEffects.cast()

    /**
     * [defaultEffectsSpec] for a value measured in **raw pixels** — the elevation case.
     *
     * Same rationale as [defaultSpatialPxSpec]: the `Float` default threshold of `0.01` is a
     * hundredth of a pixel, so a shadow settling from tens of pixels keeps emitting frames well
     * after it has visually arrived. Still critically damped — an elevation must not bounce.
     */
    fun defaultEffectsPxSpec(): FiniteAnimationSpec<Float> = DefaultEffectsPx

    /** Non-spatial motion for small, immediate feedback — a press tint, a hover highlight. */
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = FastEffects.cast()

    /** Non-spatial motion for large or ambient changes — a backdrop scrim, a theme recolour. */
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = SlowEffects.cast()

    @Suppress("UNCHECKED_CAST")
    private fun <T> SpringSpec<Any>.cast(): FiniteAnimationSpec<T> = this as FiniteAnimationSpec<T>
}
