/* Main-thread driver for the dark-mode image cutout, factored out so the harness runs the EXACT
   logic that ships. In the app this is emitted (with WASM_B64 / WORKER_B64 substituted) as a
   flattened javascript: URL by WebDarkMode.buildImageCutoutScript(). It:
     - spins up the cutout worker (Blob from base64 worker source),
     - hands it the WASM + tuning params,
     - createImageBitmap()s each large <img> and transfers it to the worker,
     - on a keyed result applies the returned alpha mask to the ORIGINAL <img> via CSS mask-image
       (aligned to the displayed content rect under object-fit), leaving the image pixels crisp,
   all fail-safe: any CSP/worker/wasm failure just leaves images untouched (element recolor stands).
   window.__lumCut guards one setup per page; __lumCutSweep re-scans for lazy/late images. */
function lumCutSetup(WASM_B64, WORKER_B64, PARAMS) {
  if (window.__lumCut) { if (window.__lumCutSweep) window.__lumCutSweep(); return; }
  /* Primary path: hand large images to native ML Kit Subject Segmentation (semantic — keeps a
     light-on-light product whole). We only tag each <img> with an id and post its URL over the
     nonce-guarded bridge; native fetches/segments and injects the mask back (see DarkImageSegmenter).
     The WASM flood fill below is the offline / model-not-ready fallback. */
  if (PARAMS.mlReady && PARAMS.bridge) { lumCutSetupML(PARAMS); return; }
  var st = window.__lumCut = { ready: false, dead: false, q: [], seq: 1, cbs: {}, worker: null };

  function markSkipAll() { st.dead = true; }
  try {
    var wsrc = atob(WORKER_B64);
    var url = URL.createObjectURL(new Blob([wsrc], { type: 'text/javascript' }));
    st.worker = new Worker(url);
    URL.revokeObjectURL(url);
  } catch (e) {
    /* Worker/blob creation blocked (strict CSP with no worker-src/child-src blob:). WASM eval itself
       may still be allowed, so fall back to running the exact same kernel on the main thread, paced
       across idle callbacks so it doesn't jank. Only reached when the worker path is dead, so it can
       never regress a site the worker already handles. */
    lumCutSetupMain(WASM_B64, PARAMS); return;
  }

  st.worker.onerror = function () { markSkipAll(); };
  st.worker.onmessage = function (ev) {
    var d = ev.data; if (!d) return;
    if (d.type === 'ready') { st.ready = true; var q = st.q; st.q = []; for (var i = 0; i < q.length; i++) send(q[i]); return; }
    /* WASM failed to instantiate inside the worker — main-thread WASM would hit the same compile
       failure, so don't retry there; just skip (element recolor stands). */
    if (d.type === 'fail') { markSkipAll(); return; }
    if (d.type === 'done') {
      var img = st.cbs[d.id]; delete st.cbs[d.id];
      if (!img || !d.keyed || !d.blob) return;
      try { applyMask(img, d.blob, d.natW || 0, d.natH || 0); } catch (e) {}
    }
  };
  st.worker.postMessage({ type: 'init', wasm: WASM_B64, params: PARAMS });

  /* Apply the alpha mask to the ORIGINAL image (no src swap, so it stays full-resolution). CSS masks
     don't follow object-fit, so size/position the mask to the displayed content rect ourselves.
     natW/natH are the source's natural pixels; box is the element's laid-out size. */
  function applyMask(img, blob, natW, natH) {
    var boxW = img.clientWidth, boxH = img.clientHeight;
    if (!boxW || !boxH) return;
    var fit = '';
    try { fit = getComputedStyle(img).objectFit || 'fill'; } catch (e) { fit = 'fill'; }
    var size = '100% 100%', pos = '0 0';
    if (fit !== 'fill' && natW > 0 && natH > 0) {
      var natAspect = natW / natH, boxAspect = boxW / boxH, cw, ch;
      if (fit === 'none' || (fit === 'scale-down' && natW <= boxW && natH <= boxH)) {
        cw = natW; ch = natH;                                   /* natural size, centered */
      } else if (fit === 'cover') {
        if (natAspect > boxAspect) { ch = boxH; cw = boxH * natAspect; }
        else { cw = boxW; ch = boxW / natAspect; }              /* fills box, cropped */
      } else {                                                  /* contain / scale-down(large) */
        if (natAspect > boxAspect) { cw = boxW; ch = boxW / natAspect; }
        else { ch = boxH; cw = boxH * natAspect; }              /* fits inside box */
      }
      size = Math.round(cw) + 'px ' + Math.round(ch) + 'px';
      pos = 'center';                                           /* default object-position */
    }
    var bu = URL.createObjectURL(blob);
    /* mask-mode:alpha reads the PNG alpha (keyed background -> transparent -> dark page shows). */
    var s = img.style;
    s.setProperty('-webkit-mask-image', 'url(' + bu + ')', 'important');
    s.setProperty('mask-image', 'url(' + bu + ')', 'important');
    s.setProperty('-webkit-mask-size', size, 'important');
    s.setProperty('mask-size', size, 'important');
    s.setProperty('-webkit-mask-position', pos, 'important');
    s.setProperty('mask-position', pos, 'important');
    s.setProperty('-webkit-mask-repeat', 'no-repeat', 'important');
    s.setProperty('mask-repeat', 'no-repeat', 'important');
    s.setProperty('mask-mode', 'alpha', 'important');
    img.__lumMaskUrl = bu;
  }

  function send(job) { st.cbs[job.id] = job.img; st.worker.postMessage({ type: 'img', id: job.id, bitmap: job.bitmap }, [job.bitmap]); }

  function enqueue(img, bmp) {
    var job = { id: st.seq++, img: img, bitmap: bmp };
    if (st.dead) { try { bmp.close(); } catch (e) {} return; }
    if (st.ready) send(job); else st.q.push(job);
  }

  function process(img) {
    if (st.dead || img.__lumCut) return;
    var iw = img.naturalWidth, ih = img.naturalHeight;
    if (!iw || !ih) return;
    if (Math.min(img.clientWidth || iw, img.clientHeight || ih) < PARAMS.minSize) return;
    img.__lumCut = 1;
    /* Read pixels through an anonymous-CORS reload of the SAME source. The page's own <img> is often
       cross-origin (CDN), whose bitmap taints the worker canvas -> getImageData throws -> image left
       untouched. A detached crossOrigin='anonymous' Image re-fetches with CORS (served from cache);
       CDNs that send Access-Control-Allow-Origin decode clean, everything else hits onerror and is
       left untouched (fail-safe). Same-origin images take this path harmlessly too. */
    var src = img.currentSrc || img.src;
    if (!src) { img.__lumCut = 0; return; }
    var probe = new Image();
    probe.crossOrigin = 'anonymous';
    probe.decoding = 'async';
    probe.onload = function () {
      /* createImageBitmap decodes off the main thread. */
      createImageBitmap(probe).then(function (bmp) { enqueue(img, bmp); }).catch(function () {});
    };
    probe.onerror = function () { /* CORS refused: leave the original image untouched. */ };
    probe.src = src;
  }

  function sweep() {
    if (st.dead) return;
    var imgs = document.getElementsByTagName('img');
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i];
      if (im.complete && im.naturalWidth) process(im);
      else im.addEventListener('load', function (e) { process(e.target); }, { once: true });
    }
  }
  window.__lumCutSweep = sweep;
  sweep();
  /* Re-scan for images added after the initial sweep (SPAs, lazy-loaded sections that inject
     new <img> nodes, etc.). rAF-batched so rapid DOM mutations coalesce into one scan. */
  if (!window.__lumCutObserver) {
    var pending = false;
    var queue = [];
    function flush() {
      pending = false;
      var q = queue; queue = [];
      for (var i = 0; i < q.length; i++) {
        var node = q[i];
        if (node.nodeType !== 1) continue;
        if (node.tagName === 'IMG') {
          if (node.complete && node.naturalWidth) process(node);
          else node.addEventListener('load', function (e) { process(e.target); }, { once: true });
        }
        if (node.getElementsByTagName) {
          var imgs = node.getElementsByTagName('img');
          for (var j = 0; j < imgs.length; j++) {
            var im = imgs[j];
            if (im.complete && im.naturalWidth) process(im);
            else im.addEventListener('load', function (e) { process(e.target); }, { once: true });
          }
        }
      }
    }
    window.__lumCutObserver = new MutationObserver(function (recs) {
      for (var i = 0; i < recs.length; i++) {
        var added = recs[i].addedNodes;
        for (var j = 0; j < added.length; j++) { queue.push(added[j]); }
      }
      if (queue.length && !pending) { pending = true; requestAnimationFrame(flush); }
    });
    try { window.__lumCutObserver.observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
  }
}

/* ML path: tag each large <img> with a stable id and post its resolved URL to native over the
   nonce-guarded bridge (PARAMS.bridge). Native runs Subject Segmentation and injects the mask back
   via WebDarkMode.buildApplyMaskScript (which looks the element up by that id). No pixels leave the
   page here — only URLs — and native fetches the image itself (no CORS problem). */
function lumCutSetupML(PARAMS) {
  var st = window.__lumCut = { ml: true, seq: 1 };
  var BRIDGE = PARAMS.bridge;
  function post(items) { if (!items.length) return; try { window.prompt(BRIDGE, JSON.stringify({ items: items })); } catch (e) {} }
  function collect(img) {
    if (!img || img.__lumCut) return null;
    var iw = img.naturalWidth, ih = img.naturalHeight;
    if (!iw || !ih) return null;
    if (Math.min(img.clientWidth || iw, img.clientHeight || ih) < PARAMS.minSize) return null;
    img.__lumCut = 1;
    var src = img.currentSrc || img.src;
    if (!src) { img.__lumCut = 0; return null; }
    var id = 'lc' + (st.seq++);
    img.setAttribute('data-lumina-cut-id', id);
    return { id: id, src: src };
  }
  function sweep() {
    var imgs = document.getElementsByTagName('img'), batch = [];
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i];
      if (im.complete && im.naturalWidth) { var it = collect(im); if (it) batch.push(it); }
      else im.addEventListener('load', function (e) { var it = collect(e.target); if (it) post([it]); }, { once: true });
    }
    post(batch);
  }
  window.__lumCutSweep = sweep;
  sweep();
  if (!window.__lumCutObserver) {
    var pending = false, queue = [];
    function flush() {
      pending = false; var q = queue; queue = []; var batch = [];
      for (var i = 0; i < q.length; i++) {
        var node = q[i];
        if (node.nodeType !== 1) continue;
        if (node.tagName === 'IMG') {
          if (node.complete && node.naturalWidth) { var it = collect(node); if (it) batch.push(it); }
          else node.addEventListener('load', function (e) { var t = collect(e.target); if (t) post([t]); }, { once: true });
        }
        if (node.getElementsByTagName) {
          var imgs = node.getElementsByTagName('img');
          for (var j = 0; j < imgs.length; j++) {
            var im = imgs[j];
            if (im.complete && im.naturalWidth) { var c = collect(im); if (c) batch.push(c); }
            else im.addEventListener('load', function (e) { var t2 = collect(e.target); if (t2) post([t2]); }, { once: true });
          }
        }
      }
      post(batch);
    }
    window.__lumCutObserver = new MutationObserver(function (recs) {
      for (var i = 0; i < recs.length; i++) { var added = recs[i].addedNodes; for (var j = 0; j < added.length; j++) { queue.push(added[j]); } }
      if (queue.length && !pending) { pending = true; requestAnimationFrame(flush); }
    });
    try { window.__lumCutObserver.observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
  }
}

/* Main-thread fallback: same WASM kernel, run in the page when the Web Worker can't be created (CSP
   blocks worker/blob URLs). Instantiate the kernel once; process each large <img> off an idle
   callback (one at a time) so the flood fill never blocks a frame. Everything is fail-safe — any
   decode/CORS/WASM error leaves that image untouched (the element recolor still stands). Produces the
   same non-destructive alpha mask over the ORIGINAL <img> as the worker path (full-res preserved). */
function lumCutSetupMain(WASM_B64, PARAMS) {
  var st = window.__lumCut = { main: true, ready: false, dead: false, q: [], draining: false, ex: null, mem: null, pptr: 0, cap: 0 };
  var P = PARAMS;
  function b2u(b) { var s = atob(b), a = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); return a; }
  try {
    WebAssembly.instantiate(b2u(WASM_B64), {}).then(function (mod) {
      st.ex = mod.instance.exports; st.mem = st.ex.memory; st.pptr = st.ex.pixels_ptr(); st.cap = st.ex.capacity();
      st.ready = true; drain();
    }).catch(function () { st.dead = true; });
  } catch (e) { st.dead = true; return; }

  /* Identical geometry/mask application to the worker path's applyMask, but from a data: URL. */
  function applyMask(img, url, natW, natH) {
    var boxW = img.clientWidth, boxH = img.clientHeight;
    if (!boxW || !boxH) return;
    var fit = 'fill';
    try { fit = getComputedStyle(img).objectFit || 'fill'; } catch (e) { fit = 'fill'; }
    var size = '100% 100%', pos = '0 0';
    if (fit !== 'fill' && natW > 0 && natH > 0) {
      var natAspect = natW / natH, boxAspect = boxW / boxH, cw, ch;
      if (fit === 'none' || (fit === 'scale-down' && natW <= boxW && natH <= boxH)) { cw = natW; ch = natH; }
      else if (fit === 'cover') { if (natAspect > boxAspect) { ch = boxH; cw = boxH * natAspect; } else { cw = boxW; ch = boxW / natAspect; } }
      else { if (natAspect > boxAspect) { cw = boxW; ch = boxW / natAspect; } else { ch = boxH; cw = boxH * natAspect; } }
      size = Math.round(cw) + 'px ' + Math.round(ch) + 'px';
      pos = 'center';
    }
    var s = img.style;
    s.setProperty('-webkit-mask-image', 'url(' + url + ')', 'important');
    s.setProperty('mask-image', 'url(' + url + ')', 'important');
    s.setProperty('-webkit-mask-size', size, 'important');
    s.setProperty('mask-size', size, 'important');
    s.setProperty('-webkit-mask-position', pos, 'important');
    s.setProperty('mask-position', pos, 'important');
    s.setProperty('-webkit-mask-repeat', 'no-repeat', 'important');
    s.setProperty('mask-repeat', 'no-repeat', 'important');
    s.setProperty('mask-mode', 'alpha', 'important');
  }

  function run(img) {
    if (st.dead) return;
    var probe = new Image();
    probe.crossOrigin = 'anonymous';
    probe.decoding = 'async';
    probe.onload = function () {
      try {
        var pw = probe.naturalWidth, ph = probe.naturalHeight;
        if (!pw || !ph) return;
        /* Cap the main-thread render smaller than the worker path (P.maxDimMain) to bound the
           synchronous flood fill; the mask is upscaled to display size anyway. */
        var maxD = P.maxDimMain || P.maxDim;
        var scale = Math.min(1, Math.sqrt(st.cap / (pw * ph)), maxD / Math.max(pw, ph));
        var w = Math.max(1, Math.round(pw * scale)), h = Math.max(1, Math.round(ph * scale));
        var cv = document.createElement('canvas'); cv.width = w; cv.height = h;
        var ctx = cv.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(probe, 0, 0, w, h);
        var idata = ctx.getImageData(0, 0, w, h);
        var view = new Uint8Array(st.mem.buffer, st.pptr, w * h * 4);
        view.set(idata.data);
        st.ex.smooth_rgb(w, h, 2);
        var filled = st.ex.cutout(w, h, P.lSeed, P.lFloor, P.stepMax, P.minAlpha, P.colorBand);
        if (filled > 0) { if (P.defringeIters > 0) st.ex.defringe(w, h, P.lEdge, P.defringeIters); st.ex.blur_alpha(w, h, 6); }
        var frac = filled / (w * h);
        if (frac < P.minFrac || frac > P.maxFrac) return;
        st.ex.to_alpha_mask(w, h);
        idata.data.set(view);
        ctx.putImageData(idata, 0, 0);
        applyMask(img, cv.toDataURL('image/png'), pw, ph);
      } catch (e) {}
    };
    probe.onerror = function () {};
    probe.src = img.currentSrc || img.src;
  }

  /* Drain the queue one image per idle callback so a burst of large images can't chain into a long
     task. requestIdleCallback where available, else a short timeout. */
  function drain() {
    if (!st.ready || st.dead || st.draining) return;
    st.draining = true;
    var ric = window.requestIdleCallback || function (f) { return setTimeout(f, 16); };
    function step() {
      if (st.dead || !st.q.length) { st.draining = false; return; }
      run(st.q.shift());
      ric(step);
    }
    step();
  }

  function process(img) {
    if (st.dead || img.__lumCut) return;
    var iw = img.naturalWidth, ih = img.naturalHeight;
    if (!iw || !ih) return;
    if (Math.min(img.clientWidth || iw, img.clientHeight || ih) < P.minSize) return;
    var src = img.currentSrc || img.src;
    if (!src) return;
    img.__lumCut = 1;
    st.q.push(img);
    if (st.ready) drain();
  }

  function sweep() {
    if (st.dead) return;
    var imgs = document.getElementsByTagName('img');
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i];
      if (im.complete && im.naturalWidth) process(im);
      else im.addEventListener('load', function (e) { process(e.target); }, { once: true });
    }
  }
  window.__lumCutSweep = sweep;
  sweep();
  if (!window.__lumCutObserver) {
    var pending = false, queue = [];
    function flush() {
      pending = false; var q = queue; queue = [];
      for (var i = 0; i < q.length; i++) {
        var node = q[i];
        if (node.nodeType !== 1) continue;
        if (node.tagName === 'IMG') {
          if (node.complete && node.naturalWidth) process(node);
          else node.addEventListener('load', function (e) { process(e.target); }, { once: true });
        }
        if (node.getElementsByTagName) {
          var imgs = node.getElementsByTagName('img');
          for (var j = 0; j < imgs.length; j++) {
            var im = imgs[j];
            if (im.complete && im.naturalWidth) process(im);
            else im.addEventListener('load', function (e) { process(e.target); }, { once: true });
          }
        }
      }
    }
    window.__lumCutObserver = new MutationObserver(function (recs) {
      for (var i = 0; i < recs.length; i++) { var added = recs[i].addedNodes; for (var j = 0; j < added.length; j++) { queue.push(added[j]); } }
      if (queue.length && !pending) { pending = true; requestAnimationFrame(flush); }
    });
    try { window.__lumCutObserver.observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
  }
}
