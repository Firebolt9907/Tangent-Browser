// Before/after sweep for a change to the top-strip heuristics. Loads every URL ONCE, injects the
// script at a git ref (default HEAD), screenshots, then re-injects the working-tree script into the
// same document (ContentHost re-injects on every load-complete / inset change, and a newer closure
// takes over from an older one) and screenshots again. Two separate loads were useless: carousels,
// ads and consent sheets differ between loads and swamped the signal. Reports, per site, which
// inset mechanism ran, the strip's painted colour, and how well the strip matches the same-height
// band under it. Prints only sites whose result CHANGED unless --all, so a heuristic change shows
// its exact blast radius across sites.txt instead of a pass count.
//
// Usage: node stripDiff.js [--ref=HEAD] [--all] [--shots=DIR] [--jobs=4] [topPx] [url ...]
//        (no urls: every URL in sites.txt)
const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');
const puppeteer = require('puppeteer-core');
const { PNG } = require('pngjs');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const args = process.argv.slice(2);
const opt = (k, d) => { const a = args.find(x => x.startsWith(`--${k}=`)); return a ? a.split('=').slice(1).join('=') : d; };
const ref = opt('ref', 'HEAD');
const shots = opt('shots', '');
const jobs = parseInt(opt('jobs', '4'), 10);
const showAll = args.includes('--all');
const pos = args.filter(a => !a.startsWith('--'));
const topPx = /^\d+$/.test(pos[0] || '') ? parseInt(pos.shift(), 10) : 48;
const flowTopPx = Math.round(topPx * 0.6);
const urls = pos.length ? pos : fs.readFileSync(path.join(__dirname, 'sites.txt'), 'utf8').split('\n')
  .map(l => l.replace(/#.*/, '').trim()).filter(Boolean);
if (shots) fs.mkdirSync(shots, { recursive: true });

// Build both scripts up front: extractScript reads TOP_INSET_GIT_REF at call time.
function scriptAt(gitRef) {
  const env = { ...process.env }; if (gitRef) env.TOP_INSET_GIT_REF = gitRef; else delete env.TOP_INSET_GIT_REF;
  return childProcess.execFileSync(process.execPath, ['-e',
    `process.stdout.write(require(${JSON.stringify(path.join(__dirname, 'extractScript'))}).buildViewportSpacerScript(${topPx}, false))`],
  { env, encoding: 'utf8', maxBuffer: 1 << 24 }).replace(/^javascript:/, '');
}
const SCRIPTS = { old: scriptAt(ref), new: scriptAt(null) };

async function measure(page, url, which) {
  try {
    await page.evaluate(SCRIPTS[which]);
    await new Promise(r => setTimeout(r, 1800));
    const mech = await page.evaluate(() => {
      const sp = document.getElementById('tangent-top-scroll-spacer');
      if (window.__tangentShellEl) return 'shell';
      if (window.__tangentBleedEl) return 'bleed';
      if (document.body && document.body.dataset.tangentHostPadSaved === 'true') return 'hostpad';
      if (sp && getComputedStyle(sp).display !== 'none') return sp.style.visibility === 'hidden' ? 'spacer(hidden)' : 'spacer';
      return 'none';
    });
    const buf = Buffer.from(await page.screenshot());
    if (shots) fs.writeFileSync(path.join(shots, url.replace(/[^a-z0-9]+/gi, '_').slice(0, 80) + `-${which}.png`), buf);
    const png = PNG.sync.read(buf);
    const mean = (y0, y1, x0, x1) => { const s = [0, 0, 0]; let n = 0;
      for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) { const i = (png.width * y + x) << 2; s[0] += png.data[i]; s[1] += png.data[i + 1]; s[2] += png.data[i + 2]; n++; }
      return s.map(v => Math.round(v / n)); };
    let worst = 0; const strip = mean(0, flowTopPx, 0, png.width);
    for (let i = 0; i < 4; i++) { const x0 = Math.floor(i * png.width / 4), x1 = Math.floor((i + 1) * png.width / 4);
      const a = mean(0, flowTopPx, x0, x1), b = mean(flowTopPx, flowTopPx * 2, x0, x1);
      worst = Math.max(worst, ...a.map((v, k) => Math.abs(v - b[k]))); }
    return { mech, strip: '#' + strip.map(v => v.toString(16).padStart(2, '0')).join(''), d: worst };
  } catch (e) { return { mech: 'ERROR ' + e.message.slice(0, 40), strip: '', d: -1 }; }
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 1 } });
  const rows = []; let next = 0;
  async function worker() { while (next < urls.length) { const url = urls[next++];
    const page = await browser.newPage(); let o, n;
    try {
      await page.setUserAgent(MOBILE_UA);
      await page.goto(url, { waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {});
      await new Promise(r => setTimeout(r, 1000));
      o = await measure(page, url, 'old'); n = await measure(page, url, 'new');
    } finally { await page.close().catch(() => {}); }
    // Changed = a different mechanism, or a different strip colour (what the change can affect).
    const sd = (a, b) => a && b ? Math.max(...[1, 3, 5].map(i => Math.abs(parseInt(a.substr(i, 2), 16) - parseInt(b.substr(i, 2), 16)))) : 999;
    const changed = o.mech !== n.mech || sd(o.strip, n.strip) > 6;
    rows.push({ url, o, n, changed });
    if (changed || showAll) console.log(`${changed ? '≠' : '='} ${url}\n    ${ref.padEnd(6)} ${o.mech.padEnd(14)} strip ${o.strip} Δ=${o.d}\n    work   ${n.mech.padEnd(14)} strip ${n.strip} Δ=${n.d}${n.d > o.d + 10 ? '   ⚠️ WORSE' : n.d + 10 < o.d ? '   ✅ better' : ''}`);
  } }
  await Promise.all(Array.from({ length: Math.min(jobs, urls.length) }, worker));
  await browser.close();
  const ch = rows.filter(r => r.changed);
  console.log(`----\n${rows.length} sites, ${ch.length} changed: ${ch.filter(r => r.n.d + 10 < r.o.d).length} better, ${ch.filter(r => r.n.d > r.o.d + 10).length} worse`);
})();
