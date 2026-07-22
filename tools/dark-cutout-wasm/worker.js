/* Web Worker for web-dark-mode image cutout. Instantiates the tiny Rust->WASM kernel and keys out
   each image's light, edge-connected background off the main thread (decode via transferred
   ImageBitmap, getImageData + WASM cutout + PNG encode all here), so the page never janks.

   We emit an ALPHA MASK, not a recolored image: the kernel writes the keyed alpha into the RGBA
   buffer, and we return a tiny PNG whose alpha channel is that keyed opacity (RGB=0). The driver
   applies it to the ORIGINAL <img> via CSS mask-image, so the displayed pixels stay full-resolution
   (no downscale/re-encode blur) and a mis-key is non-destructive (the original image is untouched).

   Protocol (main <-> worker):
     -> {type:'init', wasm:<base64>, params:{maxDim,lSeed,lFloor,stepMax,minAlpha,minFrac,maxFrac}}
     <- {type:'ready'} | {type:'fail', err}
     -> {type:'img', id, bitmap:<ImageBitmap>, params?}         (bitmap is transferred)
     <- {type:'done', id, keyed:true, blob:<Blob(mask PNG)>, natW, natH}
        | {type:'done', id, keyed:false, reason}
   This file is checked in and base64-embedded into WebDarkMode.kt by build.sh — edit here, rebuild. */
(function () {
  var ex = null, mem = null, pptr = 0, cap = 0, P = null;

  function b64ToBytes(b64) {
    var s = atob(b64), a = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) a[i] = s.charCodeAt(i);
    return a;
  }

  async function init(msg) {
    try {
      var mod = await WebAssembly.instantiate(b64ToBytes(msg.wasm), {});
      ex = mod.instance.exports;
      mem = ex.memory;
      pptr = ex.pixels_ptr();
      cap = ex.capacity();
      P = msg.params;
      postMessage({ type: 'ready' });
    } catch (e) {
      postMessage({ type: 'fail', err: '' + (e && e.message) });
    }
  }

  async function handleImg(msg) {
    var id = msg.id, bmp = msg.bitmap;
    try {
      var iw = bmp.width, ih = bmp.height;
      /* Natural (source) resolution, reported back so the driver can align the mask to the
         displayed content rect under object-fit: cover/contain. */
      var natW = iw, natH = ih;
      var scale = Math.min(1, Math.sqrt(cap / (iw * ih)), P.maxDim / Math.max(iw, ih));
      var w = Math.max(1, Math.round(iw * scale)), h = Math.max(1, Math.round(ih * scale));
      var cv = new OffscreenCanvas(w, h);
      var ctx = cv.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(bmp, 0, 0, w, h);
      bmp.close();
      var img = ctx.getImageData(0, 0, w, h);
      var view = new Uint8Array(mem.buffer, pptr, w * h * 4);
      view.set(img.data);
      /* Pre-smooth RGB to kill JPEG compression noise before the flood fill — without this,
         per-pixel noise exceeding step_max creates scattered boundary pixels (grainy edges). */
      ex.smooth_rgb(w, h, 2);
      var filled = ex.cutout(w, h, P.lSeed, P.lFloor, P.stepMax, P.minAlpha);
      /* Feather the binary alpha edge. Enough passes that the gradient is visible after the
         browser downscales the processed image to display size (~3× on mobile). */
      if (filled > 0) ex.blur_alpha(w, h, 6);
      var frac = filled / (w * h);
      if (frac < P.minFrac || frac > P.maxFrac) {
        postMessage({ type: 'done', id: id, keyed: false, reason: 'frac:' + frac.toFixed(3) });
        return;
      }
      /* Collapse the buffer to an alpha mask in the kernel (RGB=0, keyed alpha preserved), then read
         it back. mask-mode:alpha reads the alpha channel (255 = subject kept, 0 = light background
         keyed out to transparent so the dark page shows through). We never emit the recolored RGB —
         the original image stays crisp; only this soft mask is upscaled to the display size. */
      ex.to_alpha_mask(w, h);
      img.data.set(view);
      ctx.putImageData(img, 0, 0);
      var blob = await cv.convertToBlob({ type: 'image/png' });
      postMessage({ type: 'done', id: id, keyed: true, blob: blob, natW: natW, natH: natH });
    } catch (e) {
      try { if (bmp) bmp.close(); } catch (e2) {}
      postMessage({ type: 'done', id: id, keyed: false, reason: 'err:' + (e && e.message) });
    }
  }

  self.onmessage = function (ev) {
    var d = ev.data;
    if (!d) return;
    if (d.type === 'init') init(d);
    else if (d.type === 'img') handleImg(d);
  };
})();
