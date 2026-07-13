package com.firebolt9907.luminabrowser.ui.grid

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.ui.shape.squirclePath
import kotlin.math.min
import kotlin.math.pow

/**
 * Non-composable model, geometry, and small helpers for the tab deck — split out of `Grid.kt`
 * so [TabGridOverlay] there holds the rendering and this holds the plumbing.
 */

// ── Receding 3D deck (iOS app-switcher style) tunables ─────────────────────────────────────────
// The "all tabs" overview is a horizontal deck of cards, all vertically centred (like the iOS app
// switcher). The focused card sits dead-centre, large, covering the majority of the screen. Older
// tabs form a tapering stack to the LEFT — the nearest showing ~20% of its width and each deeper
// one showing less (geometric decay) behind the card in front of it. The single newer card sits to
// the RIGHT, flung far off so only a thin left sliver shows on top of the focus. Every value here is
// consumed only inside [deckSlot], run in the draw phase.

/** On-screen scale of the front (focused) card, applied uniformly (the card is a scaled-down full
 * screenshot). High enough that the focus dominates the screen, but with real margin on both sides
 * so the neighbours' slivers have room and the card reads as a card, not a zoomed-in page. */
const val DeckFrontScale = 0.72f
/** Geometric scale ratio between adjacent deck slots (scale(p) = DeckFrontScale * step^p). Gentle:
 * the older stack recedes only slightly per card (so each still reads as a full card edge) and the
 * newer card is only slightly larger than the focus. */
const val DeckDepthScaleStep = 0.95f
/** Screen-X of the focused card's centre, as a fraction of width. Dead centre: the most-visible
 * card is always the one nearest screen centre (iOS), with the deck receding away on both sides. */
const val DeckFrontCenterXFrac = 0.5f
/** Screen-Y of every card's centre, as a fraction of height. All cards are vertically centred (no
 * per-depth rise) so the deck reads as a clean horizontal row like iOS. */
const val DeckFrontCenterYFrac = 0.50f
/** First left step (fraction of width) for the nearest OLDER card (p = 1): the first left sliver
 * shows ~15% with the rest hidden by the focus. */
const val DeckLeftFirstStepFrac = 0.13f
/** Geometric decay of the left step per deeper card. Steep (≈1/3), so the visible left slivers
 * collapse fast — roughly 15% (p=1), 5% (p=2), ~0% (p=3), then culled — instead of a long tail. */
const val DeckLeftDecay = 0.5f
/** Centre-to-centre step (fraction of width) for NEWER cards (p < 0), which sit to the RIGHT.
 * The newer card is stacked ON TOP of the focus and pushed right so a sliver of its left edge shows;
 * moderate (not flung far off) so a bit more of it peeks in while the focus keeps most of its width. */
const val DeckRightStepFrac = 0.7f
/** Drag→slot ratio (fraction of width of finger travel per card) for the horizontal deck scroll,
 * decoupled from the (asymmetric) visual steps above so scrolling stays responsive rather than
 * tracking the wide right-side step. Kept tight (a third of the width per card) so the focused card
 * advances close to the finger instead of lagging well behind it on a side-to-side swipe. */
const val DeckDragStepFrac = 0.34f
/** Depth (p) beyond which older left-stack cards start going transparent. Cards up to one slot left
 * of the focus (p ≤ 1) stay fully opaque; past that they fade, like the iOS app switcher. */
const val DeckFadeStartDepth = 1f
/** Alpha lost per card of depth beyond [DeckFadeStartDepth], down to [DeckMinAlpha]. */
const val DeckFadePerDepth = 0.4f
/** Floor the deep left-stack cards fade to (never fully invisible, so the stack still reads). */
const val DeckMinAlpha = 0.15f
/** Roughly how many older cards read as a visible left stack — drives cull windows only. With the
 * steep decay the 3rd card (p=3) is already ~0%, so composing to ~p=3 and culling beyond suffices. */
const val DeckVisibleDepth = 2f
/** Card crop aspect (height / width). Set high so `min(W*aspect, H)` clamps to the full viewport
 * height on phones/tablets — each card is a scaled-down full screenshot, like the app switcher. */
const val DeckCropAspect = 2.4f
/** Scale floor the card shrinks to while the finger drags up (before the fan-into-deck spring).
 * Shared with LuminaRoot's release capture so the commit spring starts from the drawn scale. */
const val DeckDragMinScale = 0.62f
/** Fraction of screen height the finger pulls up for a full open — sets drag sensitivity. Lower =
 * a shorter pull opens the deck. Kept below half a screen so a comfortable thumb flick (which rarely
 * travels more than ~40% of the height) reaches a full open without the hand leaving the pill. */
const val DeckTravelFrac = 0.4f
/** Drop-shadow elevation behind each deck card at full open, so every card reads as lifted off the
 * surfaceDim backdrop. Applied inside the card's (scaled) transform layer and ramped by open
 * progress; the card's squircle crop is reused as the shadow outline. */
val DeckCardShadowDp = 30.dp

// ── Pixel-style (constant-scale filmstrip) tunables ────────────────────────────────────────────
// An alternative overview ([TabViewMode.PIXEL]): every card is the SAME size, laid out in a plain
// horizontal row that scrolls normally (like Android/Pixel recents) — no receding, no stacking, no
// depth fade. Consumed only inside [pixelSlot], run in the draw phase.

/** Constant on-screen scale of every card in Pixel mode (a scaled-down full screenshot). Sized so
 * the centred card dominates but the previous/next cards still peek ~10% on each side, like the
 * Android/Pixel recents filmstrip. */
const val DeckPixelScale = 0.72f
/** Gap between adjacent Pixel-mode cards, as a fraction of width. The card centre-to-centre step is
 * `DeckPixelScale + DeckPixelGapFrac`, so this is also the drag→slot ratio for that mode. */
const val DeckPixelGapFrac = 0.05f

/**
 * Which "all tabs" overview to render. The gesture core in `LuminaRoot` is layout-agnostic — it
 * only drives open/close progress — so each mode is just a different per-card slot function
 * ([deckSlot] / [pixelSlot]) plus, for [GRID], the legacy 2-column [gridCellRect] recovered from
 * the `jul-8` branch. Persisted as a user setting.
 */
enum class TabViewMode {
    /** iOS app-switcher horizontal receding deck (the current default). */
    DECK,

    /** Legacy 2-column vertical scrolling grid (from `jul-8`). */
    GRID,

    /** Pixel/Android-recents constant-scale horizontal filmstrip. */
    PIXEL,
    ;

    companion object {
        /** Parse a persisted mode name; unknown/null falls back to [DECK]. */
        fun fromName(name: String?): TabViewMode =
            entries.firstOrNull { it.name == name } ?: DECK
    }
}

// ── Legacy 2-column grid ([TabViewMode.GRID]) tunables + geometry ──────────────────────────────
// The original vertical-scrolling 2-column grid (recovered from the `jul-8` branch), rendered by
// ClassicTabGridOverlay in ClassicGrid.kt. Cards are a fixed 3:4 crop laid out in rows.

val GridSideMarginDp = 16.dp
val GridTopMarginDp = 64.dp
// Breathing-room gap between the last grid row and the top of the floating bottom controls. The
// controls' full measured height (which already includes the nav-bar inset, and the handoff banner
// when shown) is added on top of this in ClassicGrid.kt, so this is only the gap.
val GridBottomMarginDp = 24.dp
val GridGapDp = 14.dp
const val GridColumns = 2

data class GridRect(val left: Float, val top: Float, val width: Float, val height: Float)

fun gridCellRect(
    index: Int,
    containerSize: IntSize,
    columns: Int,
    horizontalGapPx: Float,
    verticalGapPx: Float,
    sideMarginPx: Float,
    topMarginPx: Float,
): GridRect {
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    if (w <= 0f || h <= 0f) return GridRect(0f, 0f, 0f, 0f)
    val cellW = (w - 2 * sideMarginPx - (columns - 1) * horizontalGapPx) / columns
    // Tab cards are a fixed 3:4 (portrait) aspect — the page is cropped to its top region rather
    // than showing the full (very tall) viewport. See ClassicTabGridOverlay for the crop/morph math.
    val cellH = cellW * 4f / 3f
    val col = index % columns
    val row = index / columns
    val left = sideMarginPx + col * (cellW + horizontalGapPx)
    val top = topMarginPx + row * (cellH + verticalGapPx)
    return GridRect(left, top, cellW, cellH)
}

/**
 * Frame-stable description of one tab card. Deliberately carries NO per-frame values: everything
 * that changes while the finger moves (carousel shift, drag position, scale, progress) is read
 * inside [TabGridOverlay]'s per-card `graphicsLayer` blocks via providers, so the list — and with
 * it the whole composition — stays untouched during the drag. It only changes when tabs, the
 * selection, or a mid-switch handoff change. Snapshots are looked up per card via the overlay's
 * provider, NOT carried here: a Bitmap field would make this class (and every List of it)
 * unstable, defeating card skipping.
 */
data class GridTab(
    val id: String,
    val url: String,
    val title: String,
    /**
     * Static X of this tab's carousel slot (px): `(i - selectedIndex) * pageStride`, plus the
     * frozen mid-switch handoff offset when one is active. The overlay adds the live carousel
     * shift on top in the draw phase.
     */
    val browseBaseX: Float = 0f,
    /** Top padding (px) the browse layer applies (site-prefs top inset). */
    val browseTopPadPx: Int = 0,
    /** Slot offset from the selected tab; spreads cards around the finger during the gesture. */
    val slotDelta: Int = 0,
    /** Recency rank: 0 = newest (front of the deck), larger = older (further back-left). */
    val recencyRank: Int = 0,
)

/** Draw-phase result of placing a card at continuous deck position [DeckSlot]. */
data class DeckSlot(val tx: Float, val ty: Float, val scale: Float, val alpha: Float)

/**
 * Transform for a card at continuous deck position `p` (0 = front/largest/right; increasing =
 * recede up-left/smaller). Cards are full-container-sized boxes drawn with `TransformOrigin(0, 0)`
 * and clipped to [cropHeightPx] from the top, so ([DeckSlot.tx], [DeckSlot.ty]) is the scaled
 * card's top-left and the visible slice is `cropHeightPx * scale` tall.
 *
 * Asymmetric, exactly like the iOS app switcher. Scale is **monotonic** in p (`step^p`, step < 1),
 * and z-order (set by the draw order in [TabGridOverlay]) is newest-on-top:
 *  - The focused card (p = 0) is centred and covers the majority of the screen.
 *  - Older cards (p > 0) form a tapering LEFT STACK: offsets compound geometrically
 *    ([DeckLeftFirstStepFrac] decayed by [DeckLeftDecay]^k), so the visible slivers shrink with
 *    depth (~15%, ~5%, ~0%, then culled) into a vanishing point, each drawn BEHIND the newer card.
 *  - Newer cards (p < 0) are slightly LARGER and drawn ON TOP of the focus, but pushed far to the
 *    RIGHT by the linear [DeckRightStepFrac] so only a thin sliver of the left edge shows and the
 *    focus keeps its high visible width.
 *
 * The horizontal step is therefore **asymmetric** (geometric/tapering left, linear/far right), and
 * continuous at p = 0 (both sides pass through the centre with zero offset). Every card is
 * vertically centred. No alpha fade anywhere: cards enter and leave only by sliding across the
 * viewport edges (right) or tapering into the vanishing point (left), like iOS.
 */
fun deckSlot(
    p: Float,
    containerW: Float,
    containerH: Float,
    cropHeightPx: Float,
    /** Space (px) reserved at the TOP of the viewport (status-bar / display-cutout + the caption
     * band that hangs above each card). Cards centre BELOW it so the caption text clears the hole
     * punch. */
    topInsetPx: Float = 0f,
    /** Space (px) reserved at the BOTTOM of the viewport (e.g. for the floating overview controls).
     * Cards are vertically centred within the band between [topInsetPx] and `containerH -
     * bottomInsetPx`, so they clear both the cutout above and the controls below. */
    bottomInsetPx: Float = 0f,
): DeckSlot {
    val scale = (DeckFrontScale * DeckDepthScaleStep.toDouble().pow(p.toDouble()).toFloat())
        .coerceIn(0.04f, 1f)
    // Older (p > 0) cards stack to the left with a geometric (decaying) offset — the partial sum
    // firstStep * (1 - r^p)/(1 - r) of the shrinking per-card steps. Newer (p < 0) cards are flung
    // far right with a linear offset so they slide clean off the right edge.
    val offsetFrac = if (p >= 0f) {
        val r = DeckLeftDecay
        val rp = r.toDouble().pow(p.toDouble()).toFloat()
        DeckLeftFirstStepFrac * (1f - rp) / (1f - r)
    } else {
        DeckRightStepFrac * p
    }
    val cx = containerW * DeckFrontCenterXFrac - offsetFrac * containerW
    val cy = topInsetPx + (containerH - topInsetPx - bottomInsetPx) * DeckFrontCenterYFrac
    val tx = cx - containerW * scale / 2f
    val ty = cy - cropHeightPx * scale / 2f
    // Depth transparency: fully opaque up to one slot left of the focus, then fading with depth into
    // the left stack (iOS-style). The right/newer side (p < 0) and the focus stay fully opaque.
    val alpha = if (p <= DeckFadeStartDepth) {
        1f
    } else {
        (1f - (p - DeckFadeStartDepth) * DeckFadePerDepth).coerceIn(DeckMinAlpha, 1f)
    }
    return DeckSlot(tx = tx, ty = ty, scale = scale, alpha = alpha)
}

/**
 * [TabViewMode.PIXEL] placement: a plain constant-scale horizontal filmstrip (Android/Pixel recents
 * style). Every card is the same size ([DeckPixelScale]), vertically centred, spaced evenly on both
 * sides of the focus, with no receding/stacking and no depth fade (alpha always 1). Same draw-phase
 * contract and `TransformOrigin(0, 0)` convention as [deckSlot], so it is a drop-in alternative in
 * the per-card `graphicsLayer`.
 */
fun pixelSlot(
    p: Float,
    containerW: Float,
    containerH: Float,
    cropHeightPx: Float,
    /** Space (px) reserved at the TOP of the viewport (status-bar / display-cutout + caption band);
     * cards centre below it so their captions clear the hole punch. */
    topInsetPx: Float = 0f,
    /** Space (px) reserved at the BOTTOM of the viewport; cards centre within the band between
     * [topInsetPx] and `containerH - bottomInsetPx` so they clear the cutout and the controls. */
    bottomInsetPx: Float = 0f,
): DeckSlot {
    val scale = DeckPixelScale
    // Centre-to-centre step = card width + gap; older cards (p > 0) to the left, newer to the right.
    val stepFrac = DeckPixelScale + DeckPixelGapFrac
    val cx = containerW * DeckFrontCenterXFrac - stepFrac * containerW * p
    val cy = topInsetPx + (containerH - topInsetPx - bottomInsetPx) * DeckFrontCenterYFrac
    val tx = cx - containerW * scale / 2f
    val ty = cy - cropHeightPx * scale / 2f
    return DeckSlot(tx = tx, ty = ty, scale = scale, alpha = 1f)
}

/**
 * A squircle outline cropped to [cropHeightPx] regardless of the layout height. Lets a card keep
 * a constant layout size (no per-frame measure/layout) while the visible page slice animates from
 * the full viewport down to the 3:4 grid cell purely in the draw phase (assigned to
 * `graphicsLayer.shape` each frame).
 */
internal class CardCropShape(
    private val radiusPx: Float,
    private val cropHeightPx: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = cropHeightPx.coerceIn(1f, size.height)
        val r = radiusPx.coerceAtMost(min(size.width, h) / 2f)
        if (r <= 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, h))
        return Outline.Generic(squirclePath(size.width, h, r))
    }
}

internal class SnapshotTracker(initial: Bitmap?) {
    var previous by mutableStateOf<Bitmap?>(null)
    var current by mutableStateOf<Bitmap?>(initial)

    fun update(next: Bitmap?) {
        if (next != current) {
            previous = current
            current = next
        }
    }
}

internal fun hostLabel(url: String): String {
    if (url.isBlankTabUrl()) return ""
    return try {
        url.toUri().host.orEmpty().removePrefix("www.")
    } catch (_: Throwable) {
        ""
    }
}

internal fun String.isBlankTabUrl(): Boolean = isBlank() || this == "about:blank"

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Easing applied to the finger-drag progress before it drives a deck card's SHRINK (scale, and with
 * it the card top and the neighbour spread) during a swipe-up-to-open. A decelerating ease-out
 * (quadratic): the card responds immediately as the pull begins, then eases as it approaches the
 * deck, so the open reads as a curved, iOS-like motion instead of a flat linear shrink. The card's
 * *position* still tracks the finger 1:1 — only the scale is eased. Both the drawn scale
 * ([TabGridOverlay]'s isGesture branch) and the release-capture scale (LuminaRoot) run through this,
 * so the commit spring starts from exactly the scale the drag last drew (no pop on release).
 */
fun deckDragEase(x: Float): Float {
    val inv = 1f - x.coerceIn(0f, 1f)
    return 1f - inv * inv
}
