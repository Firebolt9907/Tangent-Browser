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
import androidx.compose.ui.unit.LayoutDirection
import com.firebolt9907.luminabrowser.ui.shape.squirclePath
import kotlin.math.min
import kotlin.math.pow

/**
 * Non-composable model, geometry, and small helpers for the tab deck — split out of `Grid.kt`
 * so [TabGridOverlay] there holds the rendering and this holds the plumbing.
 */

// ── Receding 3D deck (iOS app-switcher style) tunables ─────────────────────────────────────────
// The "all tabs" overview is a horizontal deck of cards, all vertically centred (like the iOS app
// switcher). The focused card sits centre-right, large and fully visible; older tabs recede to the
// left, shrinking and tucking behind it. Only ~[DeckVisibleDepth] read as visible before the rest
// slide off the left edge. Every value here is consumed only inside [deckSlot], run in the draw
// phase.

/** On-screen scale of the front (focused) card, applied uniformly (the card is a scaled-down full
 * screenshot). The card is a bit narrower than the viewport so the neighbours sit beside it nearly
 * edge-to-edge, iOS-style. */
const val DeckFrontScale = 0.62f
/** Geometric scale ratio between adjacent deck slots (scale(p) = DeckFrontScale * step^p). Gentle:
 * neighbours are only slightly larger (newer/right) or smaller (older/left) than the focus, not a
 * steep recede. */
const val DeckDepthScaleStep = 0.93f
/** Screen-X of the focused card's centre, as a fraction of width. Dead centre: the most-visible
 * card is always the one nearest screen centre (iOS), with the deck receding away on both sides. */
const val DeckFrontCenterXFrac = 0.5f
/** Screen-Y of every card's centre, as a fraction of height. All cards are vertically centred (no
 * per-depth rise) so the deck reads as a clean horizontal row like iOS. */
const val DeckFrontCenterYFrac = 0.50f
/** Horizontal centre-to-centre step per slot (fraction of width). Close to [DeckFrontScale] so the
 * cards sit nearly edge-to-edge — the newer (right) card is stacked ON TOP of the focus but pushed
 * far enough right that the overlap is tiny (~90% of the focused card stays visible). Linear (not
 * compounded) so far cards keep translating and slide clean off the edges like iOS — no
 * vanishing-point pile-up. This is ALSO the drag→slot ratio (px of finger travel per card), so the
 * focused card tracks the finger 1:1 during a horizontal scroll. */
const val DeckDxStepFrac = 0.56f
/** Roughly how many cards read as on-screen before they slide off — drives cull windows only. */
const val DeckVisibleDepth = 4f
/** Card crop aspect (height / width). Set high so `min(W*aspect, H)` clamps to the full viewport
 * height on phones/tablets — each card is a scaled-down full screenshot, like the app switcher. */
const val DeckCropAspect = 2.4f
/** Scale floor the card shrinks to while the finger drags up (before the fan-into-deck spring).
 * Shared with LuminaRoot's release capture so the commit spring starts from the drawn scale. */
const val DeckDragMinScale = 0.62f
/** Fraction of screen height the finger pulls up for a full open — sets drag sensitivity. */
const val DeckTravelFrac = 0.55f

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
data class DeckSlot(val tx: Float, val ty: Float, val scale: Float)

/**
 * Transform for a card at continuous deck position `p` (0 = front/largest/right; increasing =
 * recede up-left/smaller). Cards are full-container-sized boxes drawn with `TransformOrigin(0, 0)`
 * and clipped to [cropHeightPx] from the top, so ([DeckSlot.tx], [DeckSlot.ty]) is the scaled
 * card's top-left and the visible slice is `cropHeightPx * scale` tall.
 *
 * Asymmetric, exactly like the iOS app switcher. Scale is **monotonic** in p (`step^p`, step < 1),
 * and z-order (set by the draw order in [TabGridOverlay]) is newest-on-top:
 *  - The focused card (p = 0) is centred and covers the majority of the screen.
 *  - Older cards (p > 0) recede LEFT — slightly smaller, drawn BEHIND the focus, peeking on the
 *    left where the focus doesn't cover them.
 *  - Newer cards (p < 0) are slightly LARGER and drawn ON TOP of the focus, but pushed far to the
 *    RIGHT (cards sit nearly edge-to-edge) so the overlap is tiny — ~90% of the focused card stays
 *    visible and only the newer card's left portion shows.
 *
 * Position translates **linearly** with p, so far cards keep marching and slide clean off the
 * screen edges rather than piling up at a vanishing point. Every card is vertically centred. No
 * alpha fade anywhere: cards enter and leave only by sliding across the viewport edges, like iOS.
 */
fun deckSlot(
    p: Float,
    containerW: Float,
    containerH: Float,
    cropHeightPx: Float,
): DeckSlot {
    val scale = (DeckFrontScale * DeckDepthScaleStep.toDouble().pow(p.toDouble()).toFloat())
        .coerceIn(0.04f, 1f)
    val cx = containerW * DeckFrontCenterXFrac - DeckDxStepFrac * containerW * p
    val cy = containerH * DeckFrontCenterYFrac
    val tx = cx - containerW * scale / 2f
    val ty = cy - cropHeightPx * scale / 2f
    return DeckSlot(tx = tx, ty = ty, scale = scale)
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
