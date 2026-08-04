// Headless-Chrome harness for web dark mode's DYNAMIC tier.
//
// Runs the exact scripts extracted from WebDarkMode.kt against fixtures that reproduce each way the
// tier used to leave elements light, and asserts two things per fixture:
//
//   COVERAGE — the elements the fixture points at actually end up dark.
//   RESTORE  — after the teardown script, every element's inline style is byte-identical to what it
//              was before injection. This is the "turn dark mode off without refreshing" contract:
//              the old teardown blanket-removed color properties from every element, wiping colors
//              the page itself had set inline.
//
//   node run.js            all fixtures
//   node run.js shadow     one fixture
//   CHROME_PATH=... node run.js
const path = require('path');
const puppeteer = require(path.resolve(__dirname, '../top-padding-harness/node_modules/puppeteer-core'));
const { dynamicScript, removeScript, body } = require('./extractDarkScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

/** Luminance ceiling for "this element is actually dark now". */
const DARK_MAX = 110;

const FIXTURES = [
  {
    file: 'long.html',
    what: 'every row is darkened, including past the old 8000-element cap',
    check: (probes) => probes.filter((p) => lum(p.bg) > DARK_MAX).map((p) => `${p.name} still light (${p.bg})`),
  },
  {
    file: 'shadow.html',
    what: 'elements inside an open shadow root are darkened',
    check: (probes) => probes.filter((p) => lum(p.bg) > DARK_MAX).map((p) => `${p.name} still light (${p.bg})`),
  },
  {
    file: 'classswap.html',
    what: 'an element that turns light after load is re-darkened',
    settle: async (page) => {
      await page.waitForFunction('window.__swapped===1', { timeout: 5000 });
      await sleep(400);
    },
    check: (probes) => probes.filter((p) => lum(p.bg) > DARK_MAX).map((p) => `${p.name} still light (${p.bg})`),
  },
  {
    file: 'pseudo.html',
    what: 'a light ::before is darkened via a generated rule',
    check: (probes) => probes.filter((p) => lum(p.bg) > DARK_MAX).map((p) => `${p.name} still light (${p.bg})`),
  },
  {
    file: 'iframe.html',
    what: 'a same-origin iframe document is darkened',
    check: (probes) => probes.filter((p) => lum(p.bg) > DARK_MAX).map((p) => `${p.name} still light (${p.bg})`),
  },
  {
    file: 'bgimage.html',
    what: 'a large url() background gets a scrim; a 16px icon does not',
    check: (probes) => {
      const errs = [];
      const hero = probes.find((p) => p.name === 'hero');
      const icon = probes.find((p) => p.name === 'icon');
      if (!/linear-gradient/.test(hero.bgImage)) errs.push('hero background image was not scrimmed');
      if (/linear-gradient/.test(icon.bgImage)) errs.push('icon was scrimmed but is below the size floor');
      return errs;
    },
  },
  {
    file: 'inline.html',
    what: "the page's own inline colors are darkened, then restored exactly",
    check: (probes) => probes.filter((p) => lum(p.bg) > DARK_MAX).map((p) => `${p.name} still light (${p.bg})`),
  },
];

function lum(rgb) {
  const m = String(rgb).match(/rgba?\(([^)]+)\)/);
  if (!m) return 255;
  const [r, g, b, a] = m[1].split(',').map(parseFloat);
  if (a === 0) return -1; // transparent: inherits an already-dark ancestor
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Serialize every element's inline style across the main document, open shadow roots and
 * same-origin iframes. This is the before/after fingerprint the restore check compares.
 *
 * Compares DECLARATIONS (property/value/priority read back through the CSSOM), not the raw `style`
 * attribute text: writing and removing a property makes the browser re-serialize the attribute
 * (trailing `;`, `rgb(1,2,3)` respaced to `rgb(1, 2, 3)`), so attribute text differs even when every
 * declaration is intact. Declarations are the contract that matters — a lost or altered value shows
 * up here, cosmetic re-serialization does not.
 */
const SNAPSHOT = `(function(){
  var out=[];
  function decls(el){
    var s=el.style,a=[];
    for(var i=0;i<s.length;i++){
      var p=s[i];
      a.push(p+':'+s.getPropertyValue(p)+(s.getPropertyPriority(p)?'!important':''));
    }
    return a.sort().join(';');
  }
  function walk(root,prefix){
    var els=root.querySelectorAll('*');
    for(var i=0;i<els.length;i++){
      var el=els[i];
      out.push(prefix+i+'|'+el.tagName+'|'+decls(el));
      if(el.shadowRoot)walk(el.shadowRoot,prefix+i+'>s>');
      if(el.tagName==='IFRAME'){try{var d=el.contentDocument;if(d)walk(d,prefix+i+'>f>');}catch(e){}}
    }
  }
  walk(document,'');
  return out.join('\\n');
})()`;

/** Wait until the resumable walk has drained its queue (or the budget expires). */
async function settleWalk(page) {
  await page
    .waitForFunction('!window.__lumDyn || window.__lumDyn.q.length===0', { timeout: 15000, polling: 100 })
    .catch(() => {});
  await sleep(150);
}

async function runFixture(browser, fx) {
  const page = await browser.newPage();
  await page.setViewport({ width: 900, height: 900 });
  const errs = [];
  try {
    await page.goto('file://' + path.resolve(__dirname, 'fixtures', fx.file), { waitUntil: 'load' });

    const before = await page.evaluate(SNAPSHOT);

    await page.evaluate(body(dynamicScript()));
    await settleWalk(page);
    if (fx.settle) await fx.settle(page);
    await settleWalk(page);

    const active = await page.evaluate('!!window.__lumDynActive');
    if (!active) errs.push('dynamic tier never engaged (page was judged already-dark?)');

    const probes = await page.evaluate('window.__probe()');
    errs.push(...fx.check(probes));

    await page.evaluate(body(removeScript()));
    await sleep(80);

    const after = await page.evaluate(SNAPSHOT);
    if (after !== before) {
      const b = before.split('\n');
      const a = after.split('\n');
      let shown = 0;
      for (let i = 0; i < Math.max(a.length, b.length) && shown < 4; i++) {
        if (a[i] !== b[i]) {
          errs.push(`restore mismatch\n      was: ${b[i]}\n      now: ${a[i]}`);
          shown++;
        }
      }
      if (!shown) errs.push('restore mismatch (element count changed)');
    }
  } catch (e) {
    errs.push('threw: ' + e.message);
  } finally {
    await page.close();
  }
  return errs;
}

(async () => {
  const only = process.argv[2];
  const list = only ? FIXTURES.filter((f) => f.file.startsWith(only)) : FIXTURES;
  if (!list.length) {
    console.error('no fixture matches ' + only);
    process.exit(2);
  }
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--allow-file-access-from-files', '--hide-scrollbars'],
  });
  let failed = 0;
  for (const fx of list) {
    const errs = await runFixture(browser, fx);
    if (errs.length) {
      failed++;
      console.log(`FAIL  ${fx.file} — ${fx.what}`);
      errs.forEach((e) => console.log('      ' + e));
    } else {
      console.log(`ok    ${fx.file} — ${fx.what}`);
    }
  }
  await browser.close();
  console.log(failed ? `\n${failed}/${list.length} fixtures failed` : `\nall ${list.length} fixtures passed`);
  process.exit(failed ? 1 : 0);
})();
