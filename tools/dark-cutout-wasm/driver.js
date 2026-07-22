/* Main-thread driver for the dark-mode image cutout, factored out so the harness runs the EXACT
   logic that ships. In the app this is emitted (with WASM_B64 / WORKER_B64 substituted) as a
   flattened javascript: URL by WebDarkMode.buildImageCutoutScript(). It:
     - spins up the cutout worker (Blob from base64 worker source),
     - hands it the WASM + tuning params,
     - createImageBitmap()s each large <img> and transfers it to the worker,
     - on a keyed result swaps the <img> to the returned blob (neutralizing <picture>/srcset),
   all fail-safe: any CSP/worker/wasm failure just leaves images untouched (element recolor stands).
   window.__lumCut guards one setup per page; __lumCutSweep re-scans for lazy/late images. */
function lumCutSetup(WASM_B64, WORKER_B64, PARAMS) {
  if (window.__lumCut) { if (window.__lumCutSweep) window.__lumCutSweep(); return; }
  var st = window.__lumCut = { ready: false, dead: false, q: [], seq: 1, cbs: {}, worker: null };

  function markSkipAll() { st.dead = true; }
  try {
    var wsrc = atob(WORKER_B64);
    var url = URL.createObjectURL(new Blob([wsrc], { type: 'text/javascript' }));
    st.worker = new Worker(url);
    URL.revokeObjectURL(url);
  } catch (e) { markSkipAll(); return; }

  st.worker.onerror = function () { markSkipAll(); };
  st.worker.onmessage = function (ev) {
    var d = ev.data; if (!d) return;
    if (d.type === 'ready') { st.ready = true; var q = st.q; st.q = []; for (var i = 0; i < q.length; i++) send(q[i]); return; }
    if (d.type === 'fail') { markSkipAll(); return; }
    if (d.type === 'done') {
      var img = st.cbs[d.id]; delete st.cbs[d.id];
      if (!img || !d.keyed || !d.blob) return;
      try {
        var par = img.parentNode;
        if (par && par.tagName === 'PICTURE') { var ss = par.getElementsByTagName('source'); for (var k = ss.length - 1; k >= 0; k--) ss[k].parentNode.removeChild(ss[k]); }
        img.removeAttribute('srcset'); img.removeAttribute('sizes');
        var bu = URL.createObjectURL(d.blob);
        img.addEventListener('load', function () { try { URL.revokeObjectURL(bu); } catch (e) {} }, { once: true });
        img.src = bu;
        img.style.setProperty('background', 'transparent', 'important');
      } catch (e) {}
    }
  };
  st.worker.postMessage({ type: 'init', wasm: WASM_B64, params: PARAMS });

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
