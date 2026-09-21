/*
 * Reader block -> DOM renderer for the STANDARD desktop extension build only.
 *
 * Consumes the article model produced by tangentExtractArticle() and renders it into a shadow-DOM
 * overlay. The shadow root + inlined TANGENT_READER_CSS keep the page's styles out and ours in.
 *
 * Block types handled (must stay in step with extractor.core.js): h, p (spans + imath), li, bq, pre,
 * table, hr, img, figure_rich.
 */

function tangentRenderReader(article, hostId, cssText) {
  var host = document.createElement('div');
  host.id = hostId;
  // Shadow DOM isolates the reader from the host page's CSS (and vice-versa).
  var root = host.attachShadow ? host.attachShadow({ mode: 'open' }) : host;

  var style = document.createElement('style');
  style.textContent = cssText || '';
  root.appendChild(style);

  // Dimmed backdrop, fixed behind everything.
  var backdrop = document.createElement('div');
  backdrop.className = 'tangent-reader-backdrop';
  root.appendChild(backdrop);

  // Full-viewport scroller (so its scrollbar sits at the far right of the window). The sheet lives
  // INSIDE it as a normal block with a top-margin gap, so the gap scrolls off with the sheet.
  var scroll = document.createElement('div');
  scroll.className = 'tangent-reader-scroll';
  root.appendChild(scroll);

  // The reader sheet: a centered card that slides up from the bottom, below a scrollable top gap.
  var sheet = document.createElement('div');
  sheet.className = 'tangent-reader-sheet';
  scroll.appendChild(sheet);

  // Animated dismissal: slide the sheet back down + fade the backdrop out, then remove. Falls back to
  // an immediate remove under reduced motion, and a timeout guarantees removal if animationend never
  // fires (e.g. the sheet is display:none by the time it would run).
  var closing = false;
  // The fixed top-viewport chrome (close ✕, settings gear, settings panel) that should slide up and out
  // alongside the sheet on dismiss. Populated as each element is created (the gear/panel from
  // tangentBuildSettings), then tagged with .tangent-reader-closing here to trigger their exit CSS.
  var topChrome = [];
  var closing = false;
  function dismiss() {
    if (closing) return;
    closing = true;
    for (var i = 0; i < topChrome.length; i++) {
      try { topChrome[i].classList.add('tangent-reader-closing'); } catch (e) {}
    }
    var reduce = false;
    try { reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) {}
    if (reduce) { try { host.remove(); } catch (e) {} return; }
    var removed = false;
    var finish = function () {
      if (removed) return;
      removed = true;
      sheet.removeEventListener('animationend', onEnd);
      try { host.remove(); } catch (e) {}
    };
    // animationend BUBBLES: the staggered content reveals (.tangent-reader-reveal) are children of the
    // sheet, so if the reader is closed while any reveal is still running/pending (~first 1.6s), a
    // child reveal's animationend would otherwise fire finish() early and swallow the slide-down. Only
    // the sheet's OWN slide-out animation may complete the dismissal.
    var onEnd = function (e) {
      if (e.target !== sheet) return;
      if (e.animationName && e.animationName !== 'tangent-reader-sheet-out') return;
      finish();
    };
    sheet.addEventListener('animationend', onEnd);
    setTimeout(finish, 520);
    sheet.classList.add('tangent-reader-closing');
    backdrop.classList.add('tangent-reader-closing');
  }
  // Exposed so the toolbar toggle (glue.standard.js) dismisses with the same animation as the ✕/Esc.
  host.tangentDismiss = dismiss;
  // Clicking the exposed backdrop area (the gap / side margins, target === the scroller) dismisses.
  scroll.addEventListener('click', function (e) { if (e.target === scroll) dismiss(); });

  // Close button stays fixed to the top-right of the viewport (not inside the transformed sheet).
  var close = document.createElement('button');
  close.className = 'tangent-reader-close';
  close.setAttribute('aria-label', 'Close reader');
  close.textContent = '✕';
  close.addEventListener('click', dismiss);
  root.appendChild(close);
  topChrome.push(close);

  // Settings gear, sitting just left of the close button. Opens a small popover to tune reader theme,
  // text size, and auto-open — all persisted (and shared with the options page / background auto-open).
  // Its gear + panel are registered in topChrome so they exit with the close button on dismiss.
  tangentBuildSettings(root, host, topChrome);

  var article_ = document.createElement('article');
  article_.className = 'tangent-reader-article';
  sheet.appendChild(article_);

  if (article.title) {
    var h1 = document.createElement('h1');
    h1.className = 'tangent-reader-title';
    h1.textContent = article.title;
    article_.appendChild(h1);
  }
  if (article.byline) {
    var by = document.createElement('p');
    by.className = 'tangent-reader-byline';
    by.textContent = article.byline;
    article_.appendChild(by);
  }

  var blocks = article.blocks || [];
  for (var i = 0; i < blocks.length; i++) {
    if (blocks[i] && blocks[i].type === 'li') {
      // Coalesce a run of flattened `li` blocks back into nested <ul>/<ol> structure.
      var run = [];
      while (i < blocks.length && blocks[i] && blocks[i].type === 'li') run.push(blocks[i++]);
      i--;
      article_.appendChild(renderList(run));
      continue;
    }
    var el = renderBlock(blocks[i]);
    if (el) article_.appendChild(el);
  }

  // Staggered entrance: reveal each top-level element (title, byline, every block) in sequence as the
  // sheet slides up. The base delay lets the card mostly arrive first; the per-item step is capped so
  // long articles don't leave far-down elements mid-animation when the reader scrolls to them.
  var reduceMotion = false;
  try { reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) {}
  if (!reduceMotion) {
    var BASE_DELAY = 0.26;   // seconds — roughly when the slide-up settles
    var STEP = 0.045;        // per-element stagger
    var MAX_DELAY = 1.1;     // cap so off-screen items aren't caught mid-reveal
    var kids = article_.children;
    for (var k = 0; k < kids.length; k++) {
      var delay = Math.min(BASE_DELAY + k * STEP, MAX_DELAY);
      kids[k].classList.add('tangent-reader-reveal');
      kids[k].style.setProperty('--tangent-reveal-delay', delay.toFixed(3) + 's');
    }
  }

  // Full-screen image viewer (zoom + pan). Tapping any article image opens it; it lives in the same
  // shadow root so page CSS can't touch it.
  var lightbox = tangentBuildLightbox(root);
  if (article_.querySelectorAll) {
    var zimgs = article_.querySelectorAll('img');
    for (var zi = 0; zi < zimgs.length; zi++) {
      (function (el) {
        // Inline math glyphs sit on the text baseline — not something you'd zoom.
        if (el.classList && el.classList.contains('tangent-reader-math-inline')) return;
        if (el.classList) el.classList.add('tangent-reader-zoomable');
        el.addEventListener('click', function () {
          lightbox.open(el.currentSrc || el.getAttribute('src'));
        });
      })(zimgs[zi]);
    }
  }

  // Close on Escape: the lightbox first (if open), otherwise the whole reader.
  root.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { if (lightbox.isOpen()) lightbox.close(); else dismiss(); }
  });

  return host;
}

/**
 * Reader settings popover: a gear button (beside the close ✕) that toggles a small panel to adjust the
 * reader theme (Auto/Light/Dark), text size (Small/Medium/Large), and the auto-open preference. All
 * three persist in browser.storage.local — `readerTheme` + `readerFontScale` are read back the next
 * time the reader opens, and `autoOpen` is the SAME key the options page and background auto-open use,
 * so toggling it here keeps every surface in step (background listens on storage.onChanged).
 *
 * Applied by toggling classes / a custom property on [host] (the overlay's host element): theme via
 * :host(.tangent-theme-light|dark) selectors, size via the inherited --tangent-font-scale var. Runs in
 * the injected content-script world, where `browser`/`chrome`.storage is available; degrades to a
 * session-only panel (still functional, just not remembered) if storage is missing.
 */
function tangentBuildSettings(root, host, topChrome) {
  var api = (typeof browser !== 'undefined') ? browser : (typeof chrome !== 'undefined' ? chrome : null);
  var store = (api && api.storage && api.storage.local) ? api.storage.local : null;

  // Current settings, seeded with defaults and overwritten once storage resolves.
  var state = { readerTheme: 'auto', readerFontScale: 1, autoOpen: false };

  function applyTheme(theme) {
    host.classList.remove('tangent-theme-light', 'tangent-theme-dark');
    if (theme === 'light') host.classList.add('tangent-theme-light');
    else if (theme === 'dark') host.classList.add('tangent-theme-dark');
  }
  function applyFontScale(scale) {
    host.style.setProperty('--tangent-font-scale', String(scale));
  }
  function persist(patch) {
    for (var k in patch) state[k] = patch[k];
    if (store) { try { store.set(patch); } catch (e) {} }
  }

  applyTheme(state.readerTheme);
  applyFontScale(state.readerFontScale);

  var gear = document.createElement('button');
  gear.className = 'tangent-reader-settings-btn';
  gear.type = 'button';
  gear.setAttribute('aria-label', 'Reader settings');
  gear.setAttribute('aria-expanded', 'false');
  // Inline gear glyph so it renders identically regardless of the page's/system's emoji font.
  gear.innerHTML =
    '<svg viewBox="0 0 24 24" width="19" height="19" aria-hidden="true" fill="none" ' +
    'stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' +
    '<circle cx="12" cy="12" r="3"/>' +
    '<path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 ' +
    '1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 ' +
    '1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 ' +
    '4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 ' +
    '0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 ' +
    '1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>';
  root.appendChild(gear);

  var panel = document.createElement('div');
  panel.className = 'tangent-reader-settings-panel';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-label', 'Reader settings');
  root.appendChild(panel);

  // Register the gear + panel so dismiss() slides them out with the close button.
  if (topChrome) { topChrome.push(gear); topChrome.push(panel); }

  // Build a labelled segmented control; onPick(value) is called on selection. Returns a sync(value)
  // that re-marks the active segment (used once storage hydrates the real values).
  function segmented(labelText, options, getValue, onPick) {
    var group = document.createElement('div');
    group.className = 'tangent-settings-group';
    var lbl = document.createElement('div');
    lbl.className = 'tangent-settings-label';
    lbl.textContent = labelText;
    group.appendChild(lbl);
    var seg = document.createElement('div');
    seg.className = 'tangent-settings-seg';
    var btns = [];
    options.forEach(function (opt) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'tangent-settings-seg-btn';
      b.textContent = opt.label;
      b.addEventListener('click', function () {
        onPick(opt.value);
        mark(opt.value);
      });
      b._value = opt.value;
      btns.push(b);
      seg.appendChild(b);
    });
    group.appendChild(seg);
    function mark(value) {
      for (var i = 0; i < btns.length; i++) {
        var on = btns[i]._value === value;
        btns[i].classList.toggle('is-active', on);
        btns[i].setAttribute('aria-pressed', on ? 'true' : 'false');
      }
    }
    mark(getValue());
    panel.appendChild(group);
    return mark;
  }

  var syncTheme = segmented('Theme',
    [{ label: 'Auto', value: 'auto' }, { label: 'Light', value: 'light' }, { label: 'Dark', value: 'dark' }],
    function () { return state.readerTheme; },
    function (v) { persist({ readerTheme: v }); applyTheme(v); });

  var syncSize = segmented('Text size',
    [{ label: 'Small', value: 0.9 }, { label: 'Medium', value: 1 }, { label: 'Large', value: 1.18 }],
    function () { return state.readerFontScale; },
    function (v) { persist({ readerFontScale: v }); applyFontScale(v); });

  // Auto-open toggle row (mirrors the options page's single checkbox).
  var autoRow = document.createElement('label');
  autoRow.className = 'tangent-settings-toggle';
  var autoText = document.createElement('span');
  autoText.textContent = 'Auto-open on articles';
  var autoBox = document.createElement('input');
  autoBox.type = 'checkbox';
  autoBox.checked = !!state.autoOpen;
  autoBox.addEventListener('change', function () { persist({ autoOpen: autoBox.checked }); });
  autoRow.appendChild(autoText);
  autoRow.appendChild(autoBox);
  panel.appendChild(autoRow);

  var open = false;
  function setOpen(next) {
    open = next;
    panel.classList.toggle('is-open', open);
    gear.classList.toggle('is-active', open);
    gear.setAttribute('aria-expanded', open ? 'true' : 'false');
  }
  gear.addEventListener('click', function (e) { e.stopPropagation(); setOpen(!open); });
  // Any click outside the panel/gear closes it.
  root.addEventListener('click', function (e) {
    if (!open) return;
    if (e.target === gear || gear.contains(e.target) || panel.contains(e.target)) return;
    setOpen(false);
  });

  // Hydrate from storage, then reconcile the controls with the persisted values.
  if (store) {
    try {
      store.get(['readerTheme', 'readerFontScale', 'autoOpen']).then(function (r) {
        if (!r) return;
        if (r.readerTheme === 'light' || r.readerTheme === 'dark' || r.readerTheme === 'auto') {
          state.readerTheme = r.readerTheme; applyTheme(r.readerTheme); syncTheme(r.readerTheme);
        }
        if (typeof r.readerFontScale === 'number' && r.readerFontScale > 0) {
          state.readerFontScale = r.readerFontScale; applyFontScale(r.readerFontScale); syncSize(r.readerFontScale);
        }
        state.autoOpen = !!r.autoOpen;
        autoBox.checked = state.autoOpen;
      }).catch(function () {});
    } catch (e) {}
  }
}

/**
 * Build the full-screen image viewer overlay inside the reader's shadow [root]. Supports wheel-zoom
 * (about the cursor), drag-to-pan when zoomed, double-click to toggle zoom, two-finger pinch on
 * touch, and a bottom control bar (−/percentage/+). Returns { open(src), close(), isOpen() }.
 */
function tangentBuildLightbox(root) {
  var lb = document.createElement('div');
  lb.className = 'tangent-reader-lightbox';

  var img = document.createElement('img');
  img.className = 'tangent-reader-lightbox-img';
  img.alt = '';
  lb.appendChild(img);

  var bar = document.createElement('div');
  bar.className = 'tangent-reader-lightbox-bar';
  function mkBtn(label, aria, wide) {
    var b = document.createElement('button');
    b.type = 'button';
    b.textContent = label;
    b.setAttribute('aria-label', aria);
    if (wide) b.className = 'wide';
    return b;
  }
  var zoomOut = mkBtn('−', 'Zoom out');
  var reset = mkBtn('100%', 'Reset zoom', true);
  var zoomIn = mkBtn('+', 'Zoom in');
  bar.appendChild(zoomOut);
  bar.appendChild(reset);
  bar.appendChild(zoomIn);
  lb.appendChild(bar);

  var close = document.createElement('button');
  close.type = 'button';
  close.className = 'tangent-reader-lightbox-close';
  close.setAttribute('aria-label', 'Close image');
  close.textContent = '✕';
  lb.appendChild(close);

  root.appendChild(lb);

  var scale = 1, tx = 0, ty = 0, isOpen = false;
  var MIN = 1, MAX = 8;
  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }
  function apply() {
    img.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + scale + ')';
    reset.textContent = Math.round(scale * 100) + '%';
    if (scale > 1) lb.classList.add('zoomed'); else lb.classList.remove('zoomed');
  }
  function resetView() { scale = 1; tx = 0; ty = 0; apply(); }
  // Zoom by `factor` while keeping the point (cx, cy) — viewport px — fixed under the cursor. The
  // image is centred, so positions are measured from the viewport centre.
  function zoomAt(cx, cy, factor) {
    var ns = clamp(scale * factor, MIN, MAX);
    if (ns === scale) return;
    var Cx = window.innerWidth / 2, Cy = window.innerHeight / 2;
    tx = cx - Cx - (cx - Cx - tx) * (ns / scale);
    ty = cy - Cy - (cy - Cy - ty) * (ns / scale);
    scale = ns;
    if (scale === 1) { tx = 0; ty = 0; }
    apply();
  }
  function open(src) {
    if (!src) return;
    img.src = src;
    resetView();
    isOpen = true;
    lb.classList.add('open');
  }
  function closeLb() {
    isOpen = false;
    lb.classList.remove('open');
    // Drop the src after the fade so a large decode isn't retained.
    setTimeout(function () { if (!isOpen) img.src = ''; }, 300);
  }

  lb.addEventListener('wheel', function (e) {
    e.preventDefault();
    zoomAt(e.clientX, e.clientY, e.deltaY < 0 ? 1.18 : 1 / 1.18);
  }, { passive: false });

  img.addEventListener('dblclick', function (e) {
    e.preventDefault();
    if (scale > 1) resetView(); else zoomAt(e.clientX, e.clientY, 2.5);
  });

  // Pointer events cover both mouse drag-pan and touch pinch/pan.
  var pts = {}, pinchDist = 0, pinchMid = null;
  lb.addEventListener('pointerdown', function (e) {
    if (e.target === close || bar.contains(e.target)) return;
    pts[e.pointerId] = { x: e.clientX, y: e.clientY };
    try { lb.setPointerCapture(e.pointerId); } catch (_) {}
    if (Object.keys(pts).length === 1) lb.classList.add('grabbing');
  });
  lb.addEventListener('pointermove', function (e) {
    var prev = pts[e.pointerId];
    if (!prev) return;
    var dx = e.clientX - prev.x, dy = e.clientY - prev.y;
    pts[e.pointerId] = { x: e.clientX, y: e.clientY };
    var ids = Object.keys(pts);
    if (ids.length >= 2) {
      var a = pts[ids[0]], b = pts[ids[1]];
      var dist = Math.hypot(a.x - b.x, a.y - b.y);
      var mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      if (pinchDist) zoomAt(mid.x, mid.y, dist / pinchDist);
      if (pinchMid) { tx += (mid.x - pinchMid.x); ty += (mid.y - pinchMid.y); apply(); }
      pinchDist = dist; pinchMid = mid;
    } else if (scale > 1) {
      tx += dx; ty += dy; apply();
    }
  });
  function endPointer(e) {
    delete pts[e.pointerId];
    var n = Object.keys(pts).length;
    if (n < 2) { pinchDist = 0; pinchMid = null; }
    if (n === 0) lb.classList.remove('grabbing');
  }
  lb.addEventListener('pointerup', endPointer);
  lb.addEventListener('pointercancel', endPointer);

  // Click on the empty backdrop (not the image or controls) closes.
  lb.addEventListener('click', function (e) { if (e.target === lb) closeLb(); });
  close.addEventListener('click', closeLb);
  zoomIn.addEventListener('click', function () { zoomAt(window.innerWidth / 2, window.innerHeight / 2, 1.4); });
  zoomOut.addEventListener('click', function () { zoomAt(window.innerWidth / 2, window.innerHeight / 2, 1 / 1.4); });
  reset.addEventListener('click', resetView);

  return { open: open, close: closeLb, isOpen: function () { return isOpen; } };
}

/** Render one ReaderBlock to a DOM node (or null to skip). */
function renderBlock(b) {
  if (!b || !b.type) return null;
  switch (b.type) {
    case 'h': {
      var lvl = Math.min(Math.max(b.level || 2, 2), 6);
      var h = document.createElement('h' + lvl);
      h.textContent = b.text || '';
      return h;
    }
    case 'p': {
      var p = document.createElement('p');
      fillParagraph(p, b);
      return p;
    }
    case 'bq': {
      var bq = document.createElement('blockquote');
      bq.textContent = b.text || '';
      return bq;
    }
    case 'pre': {
      var pre = document.createElement('pre');
      var code = document.createElement('code');
      code.textContent = b.text || '';
      pre.appendChild(code);
      return pre;
    }
    case 'table':
      return renderTable(b);
    case 'hr': {
      var hr = document.createElement('hr');
      hr.className = 'tangent-reader-hr';
      return hr;
    }
    case 'img':
      return renderImage(b, false);
    case 'figure_rich':
      return renderImage(b, true);
    default:
      return null;
  }
}

/** Paragraph text: link spans, with inline-math sentinels replaced by <img> in order. */
function fillParagraph(p, b) {
  var imath = b.imath || [];
  var sentinel = (typeof TANGENT_MATH_SENTINEL !== 'undefined') ? TANGENT_MATH_SENTINEL : '';

  // The common case (no inline math): render link spans faithfully.
  if (!imath.length) {
    var spans = b.spans;
    if (spans && spans.length) {
      for (var i = 0; i < spans.length; i++) {
        var s = spans[i];
        if (s.href) {
          var a = document.createElement('a');
          a.href = s.href;
          a.target = '_blank';
          a.rel = 'noopener noreferrer';
          a.textContent = s.text || '';
          p.appendChild(a);
        } else {
          p.appendChild(document.createTextNode(s.text || ''));
        }
      }
    } else {
      p.textContent = b.text || '';
    }
    return;
  }

  // Inline math present: walk the plain text, swapping each sentinel for the next rendered glyph.
  var text = b.text || '';
  var mi = 0;
  var buf = '';
  for (var c = 0; c < text.length; c++) {
    if (text[c] === sentinel) {
      if (buf) { p.appendChild(document.createTextNode(buf)); buf = ''; }
      var m = imath[mi++];
      if (m && m.src) {
        var img = document.createElement('img');
        img.className = 'tangent-reader-math-inline';
        img.src = m.src;
        if (m.w) img.width = m.w;
        if (m.h) img.height = m.h;
        p.appendChild(img);
      }
    } else {
      buf += text[c];
    }
  }
  if (buf) p.appendChild(document.createTextNode(buf));
}

/**
 * Rebuild a nested list from a run of flattened `li` blocks. Each block carries `depth` (0 = top),
 * `ordered`, and `text`. We keep a stack of open lists keyed by depth; the first item at a depth
 * decides whether that level is <ul> or <ol>.
 */
function renderList(items) {
  var rootList = document.createElement(items[0].ordered ? 'ol' : 'ul');
  // stack[d] = the list element at depth d; lastLi[d] = the <li> to hang deeper levels under.
  var stack = [rootList];
  var lastLi = [];
  for (var i = 0; i < items.length; i++) {
    var it = items[i];
    var depth = Math.max(0, it.depth || 0);
    // Ascend/descend to the target depth's list, creating nested lists as needed.
    while (stack.length - 1 > depth) stack.pop();
    while (stack.length - 1 < depth) {
      var parentLi = lastLi[stack.length - 1];
      var nested = document.createElement(it.ordered ? 'ol' : 'ul');
      if (parentLi) parentLi.appendChild(nested); else stack[stack.length - 1].appendChild(nested);
      stack.push(nested);
    }
    var li = document.createElement('li');
    li.textContent = it.text || '';
    stack[depth].appendChild(li);
    lastLi[depth] = li;
  }
  return rootList;
}

function renderTable(b) {
  var wrap = document.createElement('div');
  wrap.className = 'tangent-reader-table-wrap';
  var table = document.createElement('table');
  var rows = b.rows || [];
  for (var r = 0; r < rows.length; r++) {
    var tr = document.createElement('tr');
    var isHead = b.hasHeader && r === 0;
    var cells = rows[r] || [];
    for (var c = 0; c < cells.length; c++) {
      var cell = document.createElement(isHead ? 'th' : 'td');
      cell.textContent = cells[c] || '';
      tr.appendChild(cell);
    }
    table.appendChild(tr);
  }
  wrap.appendChild(table);
  return wrap;
}

function renderImage(b, rich) {
  if (!b.src) return null;
  var fig = document.createElement('figure');
  fig.className = 'tangent-reader-figure';
  var img = document.createElement('img');
  img.src = b.src;
  img.loading = 'lazy';
  if (rich && b.equation) img.className = 'tangent-reader-math';
  if (b.w) img.width = b.w;
  if (b.h) img.height = b.h;
  fig.appendChild(img);
  if (b.caption) {
    var cap = document.createElement('figcaption');
    cap.className = 'tangent-reader-caption';
    cap.textContent = b.caption;
    fig.appendChild(cap);
  }
  return fig;
}

if (typeof module === 'object' && module.exports) {
  module.exports = { tangentRenderReader: tangentRenderReader };
}
