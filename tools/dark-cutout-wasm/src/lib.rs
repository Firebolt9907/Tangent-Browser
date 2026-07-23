//! In-page dark-mode image cutout kernel (no_std, no allocator — static arenas only, so the
//! compiled binary stays tiny enough to embed as base64 in the injected dark-mode script).
//!
//! `cutout` keys out an image's **light, edge-connected background**: a 4-connected flood fill seeded
//! from every border pixel that is light (luminance >= `l_thresh`) and low-saturation
//! (`max-min channel <= s_max`). Filled pixels get alpha 0 so the dark page shows through; the
//! foreground subject — and any light detail *not* connected to the border background — is preserved.
//! Runs on a RGBA buffer the JS host writes straight into WASM linear memory at `pixels_ptr()`.

#![no_std]

use core::ptr::addr_of_mut;

/// Max pixels processed per image. The host downscales larger images when drawing to the canvas,
/// so this bounds worst-case memory/time. 1536*1536 RGBA + scratch ≈ 21 MB of zero-init bss — lets
/// full-bleed hero/product images key at native 2×-DPR resolution instead of a soft 768px downscale.
const CAP: usize = 1536 * 1536;

static mut PIX: [u8; CAP * 4] = [0; CAP * 4];
static mut VIS: [u8; CAP] = [0; CAP];
static mut STK: [u32; CAP] = [0; CAP];

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    loop {}
}

/// Address of the RGBA scratch buffer in linear memory. The host copies pixels in and reads them back
/// through a `Uint8Array` view over the exported `memory` at this offset.
#[no_mangle]
pub extern "C" fn pixels_ptr() -> *mut u8 {
    addr_of_mut!(PIX) as *mut u8
}

/// Max pixel count (w*h) the kernel accepts; the host caps its downscale to this.
#[no_mangle]
pub extern "C" fn capacity() -> u32 {
    CAP as u32
}

#[inline(always)]
fn lum_of(pix: &[u8], i: usize) -> u32 {
    let o = i * 4;
    let r = pix[o] as u32;
    let g = pix[o + 1] as u32;
    let b = pix[o + 2] as u32;
    // Integer Rec.709 luminance: 54+183+19 = 256, so (…) >> 8 is a 0..255 luma.
    (r * 54 + g * 183 + b * 19) >> 8
}

/// A border seed: opaque and bright enough to be page background rather than subject.
#[inline(always)]
fn is_seed(pix: &[u8], i: usize, l_seed: u32, min_alpha: u32) -> bool {
    pix[i * 4 + 3] as u32 >= min_alpha && lum_of(pix, i) >= l_seed
}

/// Sum-of-absolute per-channel difference between two pixels — the local "edge strength".
#[inline(always)]
fn step_diff(pix: &[u8], a: usize, b: usize) -> u32 {
    let (oa, ob) = (a * 4, b * 4);
    let d = |k: usize| (pix[oa + k] as i32 - pix[ob + k] as i32).unsigned_abs();
    d(0) + d(1) + d(2)
}

/// Sum-of-absolute per-channel distance from the measured background color `(br,bg,bb)`. This is the
/// *global* constraint that makes the keyer adaptive rather than tuned to one site's contrast: the
/// fill may only stay in pixels whose color is still within `color_band` of the background sampled
/// from the border, so a smooth gradient that drifts off the real background (white easing into a
/// light-grey product surface) is halted when it leaves the band — regardless of absolute contrast.
#[inline(always)]
fn bg_dist(pix: &[u8], i: usize, br: u32, bg: u32, bb: u32) -> u32 {
    let o = i * 4;
    let d = |c: u32, b: u32| (c as i32 - b as i32).unsigned_abs();
    d(pix[o] as u32, br) + d(pix[o + 1] as u32, bg) + d(pix[o + 2] as u32, bb)
}

/// Try to grow the region from `cur` into neighbor `nb`. Three complementary gates:
/// - `nb` must be opaque and not too dark (>= `l_floor`, so a soft edge into a dark subject doesn't
///   leak);
/// - the local step `cur`→`nb` must be below the edge barrier (`step_max`) — halts at a sharp outline;
/// - `nb` must be within `color_band` of the measured background color `(br,bg,bb)` — halts gradient
///   *creep* into an off-background region even when every local step is tiny (the adaptive gate).
#[inline(always)]
#[allow(clippy::too_many_arguments)]
fn grow(
    pix: &[u8],
    vis: &mut [u8],
    stk: &mut [u32],
    sp: &mut usize,
    cur: usize,
    nb: usize,
    l_floor: u32,
    step_max: u32,
    min_alpha: u32,
    br: u32,
    bg: u32,
    bb: u32,
    color_band: u32,
) {
    if vis[nb] != 0 {
        return;
    }
    if (pix[nb * 4 + 3] as u32) < min_alpha || lum_of(pix, nb) < l_floor {
        return;
    }
    if step_diff(pix, cur, nb) > step_max {
        return;
    }
    if bg_dist(pix, nb, br, bg, bb) > color_band {
        return;
    }
    vis[nb] = 1;
    stk[*sp] = nb as u32;
    *sp += 1;
}

/// Pre-smooth the RGB channels (leaving alpha untouched) with a separable 3×3 box blur.
/// Eliminates per-pixel JPEG compression noise so the flood fill in `cutout` produces a clean,
/// contiguous mask instead of scattered boundary pixels. Must be called before `cutout`.
/// Uses `STK` as scratch (needs n*3 bytes; STK is CAP*4 bytes).
#[no_mangle]
pub extern "C" fn smooth_rgb(w: usize, h: usize, passes: u32) {
    let n = w * h;
    if n == 0 || n > CAP || w < 2 || h < 2 {
        return;
    }
    let pix = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(PIX) as *mut u8, n * 4) };
    let stk_u8 = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(STK) as *mut u8, n * 3) };

    for _ in 0..passes {
        // Horizontal pass: blur RGB in each row, write result to stk_u8.
        let mut t: usize = 0;
        let mut y: usize = 0;
        while y < h {
            let mut x: usize = 0;
            while x < w {
                let (x0, x1) = (if x > 0 { x - 1 } else { 0 }, if x + 1 < w { x + 1 } else { w - 1 });
                let mut r: u32 = 0;
                let mut g: u32 = 0;
                let mut b: u32 = 0;
                let mut cnt: u32 = 0;
                let mut xx = x0;
                while xx <= x1 {
                    let o = (y * w + xx) * 4;
                    r += pix[o] as u32;
                    g += pix[o + 1] as u32;
                    b += pix[o + 2] as u32;
                    cnt += 1;
                    xx += 1;
                }
                stk_u8[t] = (r / cnt) as u8;
                stk_u8[t + 1] = (g / cnt) as u8;
                stk_u8[t + 2] = (b / cnt) as u8;
                t += 3;
                x += 1;
            }
            y += 1;
        }
        // Vertical pass: blur the horizontally-blurred rows, write back to PIX RGB.
        y = 0;
        while y < h {
            let mut x: usize = 0;
            while x < w {
                let (y0, y1) = (if y > 0 { y - 1 } else { 0 }, if y + 1 < h { y + 1 } else { h - 1 });
                let mut r: u32 = 0;
                let mut g: u32 = 0;
                let mut b: u32 = 0;
                let mut cnt: u32 = 0;
                let mut yy = y0;
                while yy <= y1 {
                    let t2 = (yy * w + x) * 3;
                    r += stk_u8[t2] as u32;
                    g += stk_u8[t2 + 1] as u32;
                    b += stk_u8[t2 + 2] as u32;
                    cnt += 1;
                    yy += 1;
                }
                let o = (y * w + x) * 4;
                pix[o] = (r / cnt) as u8;
                pix[o + 1] = (g / cnt) as u8;
                pix[o + 2] = (b / cnt) as u8;
                x += 1;
            }
            y += 1;
        }
    }
}

/// Remove the residual light **halo ring** the flood fill leaves where it stopped just short of the
/// subject. The fill halts one pixel before the outline (a step over `step_max`, or a drift past
/// `color_band`), so the last ring of anti-aliased pixels — background color blended against the
/// subject edge, still light — stays opaque and reads as a bright fringe/glow on the dark page.
///
/// **Subject-aware**, so it removes the halo around a *dark* subject without eroding a *light* one
/// (a white product on a white background, where the fill also halts one pixel out): a candidate is
/// an opaque, still-light (luminance >= `l_edge`) pixel that borders an already-transparent (keyed)
/// pixel AND whose **inward** neighbor — the pixel on the far side, away from the keyed edge — is
/// dark (< `l_edge`). The inward-dark test is the discriminator: a true halo sits between keyed
/// background and a dark subject (inward is the dark subject → keyed out), whereas the edge of a light
/// subject has a light interior (inward is light → preserved). At the image border (no inward pixel)
/// the candidate is background fringe, so it's keyed. `iters` rings; marks are collected per pass
/// (into `VIS`) and applied together so a pass can't cascade inward within itself — and because each
/// new ring re-tests inward darkness, peeling naturally stops at the subject outline regardless of
/// `iters`. Call after `cutout`, before `blur_alpha`. Uses `VIS` as scratch.
#[no_mangle]
pub extern "C" fn defringe(w: usize, h: usize, l_edge: u32, iters: u32) {
    let n = w * h;
    if n == 0 || n > CAP || w < 2 || h < 2 {
        return;
    }
    let pix = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(PIX) as *mut u8, n * 4) };
    let mark = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(VIS) as *mut u8, n) };

    // True iff the pixel one step in `(dx,dy)` from (x,y) is dark, treating out-of-bounds (the image
    // border, i.e. background side) as dark so a border fringe pixel is still peeled.
    let inward_dark = |pix: &[u8], x: usize, y: usize, dx: isize, dy: isize| -> bool {
        let nx = x as isize + dx;
        let ny = y as isize + dy;
        if nx < 0 || ny < 0 || nx >= w as isize || ny >= h as isize {
            return true;
        }
        lum_of(pix, ny as usize * w + nx as usize) < l_edge
    };

    for _ in 0..iters {
        for m in mark[..n].iter_mut() {
            *m = 0;
        }
        for y in 0..h {
            for x in 0..w {
                let i = y * w + x;
                // Only opaque, still-light pixels are candidates.
                if (pix[i * 4 + 3] as u32) == 0 || lum_of(pix, i) < l_edge {
                    continue;
                }
                // Keyed neighbor on one side + dark neighbor on the OPPOSITE (inward) side ⇒ halo
                // before a dark subject. A light interior on the inward side ⇒ edge of a light
                // subject ⇒ leave it.
                let eat = (x > 0 && pix[(i - 1) * 4 + 3] == 0 && inward_dark(pix, x, y, 1, 0))
                    || (x + 1 < w && pix[(i + 1) * 4 + 3] == 0 && inward_dark(pix, x, y, -1, 0))
                    || (y > 0 && pix[(i - w) * 4 + 3] == 0 && inward_dark(pix, x, y, 0, 1))
                    || (y + 1 < h && pix[(i + w) * 4 + 3] == 0 && inward_dark(pix, x, y, 0, -1));
                if eat {
                    mark[i] = 1;
                }
            }
        }
        let mut i = 0;
        let mut any = false;
        while i < n {
            if mark[i] != 0 {
                pix[i * 4 + 3] = 0;
                any = true;
            }
            i += 1;
        }
        if !any {
            break;
        }
    }
}

/// Apply a multi-pass box blur to the alpha channel only, feathering the hard binary edge left by the
/// flood fill. Fully-transparent and fully-opaque regions are unchanged (their neighbors are uniform),
/// only the boundary gets a smooth gradient.
///
/// **Separable** (like [smooth_rgb]): each pass is a horizontal 1D box blur then a vertical 1D box
/// blur, which composes to the same 3×3 box average at 3+3 = 6 taps per pixel instead of 9 — a third
/// less work, and this is the heaviest stage (most passes). Uses `VIS` as the horizontal-pass scratch
/// (the visited map is no longer needed after `cutout`).
#[no_mangle]
pub extern "C" fn blur_alpha(w: usize, h: usize, passes: u32) {
    let n = w * h;
    if n == 0 || n > CAP || w < 2 || h < 2 {
        return;
    }
    let pix = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(PIX) as *mut u8, n * 4) };
    let tmp = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(VIS) as *mut u8, n) };

    for _ in 0..passes {
        // Horizontal 1D box blur of the alpha channel: PIX alpha -> tmp (edges clamp).
        for y in 0..h {
            let row = y * w;
            for x in 0..w {
                let x0 = if x > 0 { x - 1 } else { 0 };
                let x1 = if x + 1 < w { x + 1 } else { w - 1 };
                let sum = pix[(row + x0) * 4 + 3] as u32
                    + pix[(row + x) * 4 + 3] as u32
                    + pix[(row + x1) * 4 + 3] as u32;
                tmp[row + x] = (sum / 3) as u8;
            }
        }
        // Vertical 1D box blur of the horizontally-blurred alpha: tmp -> PIX alpha (edges clamp).
        for y in 0..h {
            let y0 = if y > 0 { y - 1 } else { 0 };
            let y1 = if y + 1 < h { y + 1 } else { h - 1 };
            for x in 0..w {
                let sum = tmp[y0 * w + x] as u32 + tmp[y * w + x] as u32 + tmp[y1 * w + x] as u32;
                pix[(y * w + x) * 4 + 3] = (sum / 3) as u8;
            }
        }
    }
}

/// Collapse the RGBA buffer in place into an **alpha mask**: zero the RGB channels, keep the
/// (keyed + feathered) alpha. The host PNG-encodes the result and the driver applies it as a CSS
/// `mask-image` (`mask-mode: alpha`) on the *original* image, so the displayed image stays
/// full-resolution — only the soft alpha is upscaled. Call after `cutout` + `blur_alpha`.
#[no_mangle]
pub extern "C" fn to_alpha_mask(w: usize, h: usize) {
    let n = w * h;
    if n == 0 || n > CAP {
        return;
    }
    let pix = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(PIX) as *mut u8, n * 4) };
    let mut i = 0;
    while i < n {
        let o = i * 4;
        pix[o] = 0;
        pix[o + 1] = 0;
        pix[o + 2] = 0;
        // pix[o + 3] (alpha) preserved.
        i += 1;
    }
}

/// Key out the light, edge-connected background in the RGBA buffer at `pixels_ptr()` (`w`x`h`).
///
/// Seeds every opaque border pixel brighter than `l_seed`, then edge-aware-grows: crossing into a
/// neighbor requires it be opaque, at least `l_floor` bright, within `step_max` sum-abs channel
/// distance of the current pixel (the local edge barrier), AND within `color_band` sum-abs channel
/// distance of the **measured background color** (the mean of the seed pixels). That last, adaptive
/// gate is what stops the fill creeping through a smooth fade into a light-but-off-background surface
/// (a MacBook's silver deck) even when no sharp edge separates them — without hardcoding the site's
/// contrast. Filled pixels become transparent. Returns the count made transparent (0 when nothing
/// qualified — e.g. a full-bleed photo with no light border — so the host leaves such images alone).
#[no_mangle]
pub extern "C" fn cutout(
    w: usize,
    h: usize,
    l_seed: u32,
    l_floor: u32,
    step_max: u32,
    min_alpha: u32,
    color_band: u32,
) -> u32 {
    let n = w * h;
    if n == 0 || n > CAP || w == 0 || h == 0 {
        return 0;
    }
    let pix: &mut [u8] = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(PIX) as *mut u8, n * 4) };
    let vis: &mut [u8] = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(VIS) as *mut u8, n) };
    let stk: &mut [u32] = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(STK) as *mut u32, n) };

    for v in vis.iter_mut() {
        *v = 0;
    }

    let mut sp: usize = 0;
    let mut seed = |i: usize, vis: &mut [u8], stk: &mut [u32], sp: &mut usize| {
        if vis[i] == 0 && is_seed(pix, i, l_seed, min_alpha) {
            vis[i] = 1;
            stk[*sp] = i as u32;
            *sp += 1;
        }
    };
    for x in 0..w {
        seed(x, vis, stk, &mut sp); // top row
        seed((h - 1) * w + x, vis, stk, &mut sp); // bottom row
    }
    for y in 0..h {
        seed(y * w, vis, stk, &mut sp); // left col
        seed(y * w + (w - 1), vis, stk, &mut sp); // right col
    }

    // Measure the background color from the seed (border) pixels — the mean R/G/B over stk[0..sp].
    // Done before the fill consumes the stack. If there are no seeds we never enter the loop below.
    let (mut sr, mut sg, mut sb): (u64, u64, u64) = (0, 0, 0);
    for k in 0..sp {
        let o = stk[k] as usize * 4;
        sr += pix[o] as u64;
        sg += pix[o + 1] as u64;
        sb += pix[o + 2] as u64;
    }
    let seeds = sp.max(1) as u64;
    let (br, bg, bb) = ((sr / seeds) as u32, (sg / seeds) as u32, (sb / seeds) as u32);

    let mut filled: u32 = 0;
    while sp > 0 {
        sp -= 1;
        let i = stk[sp] as usize;
        pix[i * 4 + 3] = 0; // key to transparent
        filled += 1;
        let x = i % w;
        let y = i / w;
        if x > 0 {
            grow(pix, vis, stk, &mut sp, i, i - 1, l_floor, step_max, min_alpha, br, bg, bb, color_band);
        }
        if x + 1 < w {
            grow(pix, vis, stk, &mut sp, i, i + 1, l_floor, step_max, min_alpha, br, bg, bb, color_band);
        }
        if y > 0 {
            grow(pix, vis, stk, &mut sp, i, i - w, l_floor, step_max, min_alpha, br, bg, bb, color_band);
        }
        if y + 1 < h {
            grow(pix, vis, stk, &mut sp, i, i + w, l_floor, step_max, min_alpha, br, bg, bb, color_band);
        }
    }
    filled
}
