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

/// Try to grow the region from `cur` into neighbor `nb`: cross only if `nb` is opaque, not too dark
/// (>= `l_floor`, so a soft edge into a dark subject doesn't leak), and the step from `cur` to `nb`
/// is below the edge barrier (`step_max`) — so the fill flows across the smooth background gradient
/// but halts at the subject's outline.
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

/// Apply a multi-pass 3×3 box blur to the alpha channel only, feathering the hard binary edge
/// left by the flood fill. Fully-transparent and fully-opaque regions are unchanged (their
/// neighbors are uniform), only the boundary gets a smooth ~1–2 px gradient.
/// Uses `VIS` as scratch (the visited map is no longer needed after `cutout`).
#[no_mangle]
pub extern "C" fn blur_alpha(w: usize, h: usize, passes: u32) {
    let n = w * h;
    if n == 0 || n > CAP || w < 2 || h < 2 {
        return;
    }
    let pix = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(PIX) as *mut u8, n * 4) };
    let tmp = unsafe { core::slice::from_raw_parts_mut(addr_of_mut!(VIS) as *mut u8, n) };

    for _ in 0..passes {
        // 3×3 box blur: read alpha from PIX, write averaged result to tmp.
        for y in 0..h {
            for x in 0..w {
                let mut sum: u32 = 0;
                let mut cnt: u32 = 0;
                let y0 = if y > 0 { y - 1 } else { 0 };
                let y1 = if y + 1 < h { y + 1 } else { h - 1 };
                let x0 = if x > 0 { x - 1 } else { 0 };
                let x1 = if x + 1 < w { x + 1 } else { w - 1 };
                let mut yy = y0;
                while yy <= y1 {
                    let mut xx = x0;
                    while xx <= x1 {
                        sum += pix[(yy * w + xx) * 4 + 3] as u32;
                        cnt += 1;
                        xx += 1;
                    }
                    yy += 1;
                }
                tmp[y * w + x] = (sum / cnt) as u8;
            }
        }
        // Copy blurred alpha back into PIX.
        let mut i = 0;
        while i < n {
            pix[i * 4 + 3] = tmp[i];
            i += 1;
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
/// neighbor requires it be opaque, at least `l_floor` bright, and within `step_max` sum-abs channel
/// distance of the current pixel (the edge barrier). Filled pixels become transparent. Returns the
/// count made transparent (0 when nothing qualified — e.g. a full-bleed photo with no light border —
/// so the host leaves such images untouched).
#[no_mangle]
pub extern "C" fn cutout(
    w: usize,
    h: usize,
    l_seed: u32,
    l_floor: u32,
    step_max: u32,
    min_alpha: u32,
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

    let mut filled: u32 = 0;
    while sp > 0 {
        sp -= 1;
        let i = stk[sp] as usize;
        pix[i * 4 + 3] = 0; // key to transparent
        filled += 1;
        let x = i % w;
        let y = i / w;
        if x > 0 {
            grow(pix, vis, stk, &mut sp, i, i - 1, l_floor, step_max, min_alpha);
        }
        if x + 1 < w {
            grow(pix, vis, stk, &mut sp, i, i + 1, l_floor, step_max, min_alpha);
        }
        if y > 0 {
            grow(pix, vis, stk, &mut sp, i, i - w, l_floor, step_max, min_alpha);
        }
        if y + 1 < h {
            grow(pix, vis, stk, &mut sp, i, i + w, l_floor, step_max, min_alpha);
        }
    }
    filled
}
