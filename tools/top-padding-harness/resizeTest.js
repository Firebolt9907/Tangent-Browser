// VIEWPORT-CHANGE probe. On a phone the viewport height changes constantly — the soft keyboard
// opens, the browser chrome collapses, the device rotates. Any inset decision that was made by
// comparing an element's height against `window.innerHeight` can silently stop matching, and the
// inset it applied gets torn back off (content jumps under the status bar mid-typing).
//
// Injects, snapshots, shrinks the viewport (keyboard-sized), pokes the DOM so the script's
// MutationObserver re-runs a pass, then checks the inset SURVIVED — and that it comes back
// correctly when the viewport is restored.
//
// Usage: node resizeTest.js <url> [topPx] [shrinkPx]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const shrink = parseInt(process.argv[4] || '380', 10);
if (!url) { console.error('usage: node resizeTest.js <url> [topPx] [shrinkPx]'); process.exit(1); }

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// The inset is "present" if the top of the page's first real content still clears the band. Reuse a
// minimal version of run.js's ink test plus the script's own bookkeeping.
const STATE = `(() => {
  const SPACER = 'tangent-top-scroll-spacer';
  const sp = document.getElementById(SPACER);
  const shell = window.__tangentShellEl || null;
  const r = shell ? shell.getBoundingClientRect() : null;
  return {
    vh: window.innerHeight,
    spacerH: sp ? Math.round(sp.getBoundingClientRect().height) : -1,
    spacerDisplay: sp ? getComputedStyle(sp).display : 'absent',
    bodyPad: parseFloat(getComputedStyle(document.body).paddingTop) || 0,
    shell: shell ? { pad: getComputedStyle(shell).paddingTop, top: Math.round(r.top), h: Math.round(r.height) } : null,
    capped: (window.__tangentShellCapped || []).length,
  };
})()`;

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 },
  });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav warn:', e.message));
    await new Promise(r => setTimeout(r, 1500));
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 1800));
    const full = await page.evaluate(STATE);

    // Soft keyboard: same width, much shorter viewport. Then force a pass.
    await page.setViewport({ width: 412, height: 915 - shrink, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 });
    await new Promise(r => setTimeout(r, 400));
    await page.evaluate(`document.body.appendChild(document.createElement('span'))`);
    await new Promise(r => setTimeout(r, 2500));
    const small = await page.evaluate(STATE);

    await page.setViewport({ width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 });
    await new Promise(r => setTimeout(r, 400));
    await page.evaluate(`document.body.appendChild(document.createElement('span'))`);
    await new Promise(r => setTimeout(r, 2500));
    const back = await page.evaluate(STATE);

    console.log(`\n=== ${url}  topPx=${topPx}  shrink=${shrink} ===`);
    const line = (n, s) => console.log(`${n.padEnd(9)} vh=${s.vh} spacer=${s.spacerH}(${s.spacerDisplay}) bodyPad=${s.bodyPad} shell=${s.shell ? `pad=${s.shell.pad} top=${s.shell.top} h=${s.shell.h}` : 'none'} capped=${s.capped}`);
    line('full', full); line('shrunk', small); line('restored', back);

    // The inset is applied by exactly one of: shell padding, host padding, or the in-flow spacer.
    const mech = s => (s.shell && parseFloat(s.shell.pad) >= topPx - 2) ? 'shell'
      : s.bodyPad >= topPx - 2 ? 'hostpad'
      : (s.spacerDisplay !== 'none' && s.spacerH >= topPx - 2) ? 'spacer' : 'none';
    const m = { full: mech(full), small: mech(small), back: mech(back) };
    console.log(`mechanism: full=${m.full} shrunk=${m.small} restored=${m.back}`);
    // Losing the inset is the obvious failure; SWAPPING mechanism is one too — a shell that falls
    // back to the in-flow spacer gets pushed down again, re-introducing the bottom clipping the
    // shell path exists to avoid, and thrashes layout on every keyboard toggle.
    const lost = m.small === 'none' || m.back === 'none';
    const swapped = m.full !== 'none' && (m.small !== m.full || m.back !== m.full);
    console.log(`\nRESULT: ${m.full === 'none' ? 'N/A ⚠️  no inset applied at full height'
      : lost ? `FAIL ❌ inset LOST when the viewport ${m.small === 'none' ? 'shrank' : 'was restored'}`
      : swapped ? `FAIL ❌ inset mechanism swapped (${m.full} -> ${m.small} -> ${m.back}) on viewport change`
      : 'PASS ✅ inset survives the viewport change, same mechanism'}`);
  } finally {
    await browser.close();
  }
})();
