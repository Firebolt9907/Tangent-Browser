// Headless-Chrome tests for the ALREADY-DARK DETECTOR — the gate that decides whether a site's own
// dark theme (Tier 1) already handled the page and we must keep our hands off it.
//
// A false negative here is the most visible bug the feature has: we invert a page that is already
// dark, which reads as "dark mode is broken on this site". A false positive is the opposite failure —
// a light page stays light with dark mode on — so both directions are pinned here.
//
// Runs the real `buildApplyScript` output (extracted from WebDarkMode.kt) against each fixture and
// asserts on the observable contract: does <html> end up carrying the invert class?
//
//   node detect.js                 all fixtures
//   node detect.js wrapper-dark    one fixture
//   WEBDARKMODE_KT=/tmp/old.kt node detect.js     replay against another revision
//   CHROME_PATH=... node detect.js
const path = require('path');
const puppeteer = require(path.resolve(__dirname, '../top-padding-harness/node_modules/puppeteer-core'));
const { applyScript, body } = require('./extractDarkScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CLS = 'tangent-dark-fallback';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// `inverted: false` means "the page was recognised as already dark, so we left it alone".
const FIXTURES = [
  {
    name: 'wrapper-dark',
    file: 'detect-wrapper-dark.html',
    inverted: false,
    what: 'a dark surface painted on a full-bleed wrapper is recognised (body/html stay light)',
  },
  {
    name: 'scheme-dark',
    file: 'detect-scheme-dark.html',
    inverted: false,
    what: 'color-scheme:dark with no declared background means the UA canvas is dark, not white',
  },
  {
    name: 'meta-scheme',
    file: 'detect-meta-scheme.html',
    inverted: false,
    what: '<meta name=color-scheme content="light dark"> resolves dark under the forced preference',
  },
  {
    name: 'plain-dark',
    file: 'detect-plain-dark.html',
    inverted: false,
    what: 'an ordinary authored-dark page is left alone',
  },
  {
    name: 'plain-light',
    file: 'detect-plain-light.html',
    inverted: true,
    what: 'an ordinary light page is still inverted',
  },
  {
    name: 'hero',
    file: 'detect-hero.html',
    inverted: true,
    what: 'a LIGHT page whose dark hero fills the viewport is still inverted (false-positive guard)',
  },
  {
    name: 'late-dark',
    file: 'detect-late-dark.html',
    inverted: false,
    wait: 3600,
    what: 'a theme that lands at 1.8s is picked up by the late re-check',
  },
];

(async () => {
  const only = process.argv[2];
  const chosen = only ? FIXTURES.filter((f) => f.name === only) : FIXTURES;
  if (!chosen.length) {
    console.error(`no such fixture: ${only}`);
    process.exit(2);
  }

  const script = body(applyScript());
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new' });
  let failures = 0;
  for (const f of chosen) {
    const page = await browser.newPage();
    await page.setViewport({ width: 412, height: 915 });
    // Tier 1: the engine forces prefers-color-scheme: dark for all content.
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    let problem = null;
    try {
      await page.goto('file://' + path.resolve(__dirname, 'fixtures', f.file), { waitUntil: 'load' });
      await page.evaluate(script);
      await sleep(f.wait || 500);
      const inverted = await page.evaluate((c) => document.documentElement.classList.contains(c), CLS);
      if (inverted !== f.inverted) {
        problem = inverted
          ? 'inverted a page that is already dark'
          : 'left a light page un-inverted';
      }
    } catch (e) {
      problem = `threw: ${e.message}`;
    }
    if (problem) {
      failures++;
      console.log(`FAIL  ${f.file} — ${f.what}`);
      console.log(`      ${problem}`);
    } else {
      console.log(`ok    ${f.file} — ${f.what}`);
    }
    await page.close();
  }
  await browser.close();
  console.log(failures ? `\n${failures} of ${chosen.length} fixtures failed` : `\nall ${chosen.length} fixtures passed`);
  process.exit(failures ? 1 : 0);
})();
