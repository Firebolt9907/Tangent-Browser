// Headless-Chrome harness for proactive login autofill (`ui/content/LoginFieldProbe.kt`).
//
// Runs the exact detect/fill/submit scripts extracted from the Kotlin source against fixtures that
// reproduce the ways autofill used to die on an enterprise SSO sign-in. The central assertion is the
// one the unit tests cannot make: that after the identifier step is submitted, the password step —
// which arrives with NO navigation, so nothing re-injects anything — is still reported.
//
//   node run.js              all fixtures
//   node run.js entra        one fixture
//   CHROME_PATH=... node run.js
const path = require('path');
const puppeteer = require(path.resolve(__dirname, '../top-padding-harness/node_modules/puppeteer-core'));
const { detectScript, fillScript, submitScript, TEST_TAG } = require('./extractProbeScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const USER = 'rishu@purdue.edu';
const PASS = 'correct-horse-battery-staple';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Collects what the page posted over the `tangent-*` prompt bridge. The probe posts through a
 * throwaway iframe's `prompt()`, which surfaces here as a page dialog.
 */
function bridge(page) {
  const seen = [];
  page.on('dialog', async (d) => {
    if (d.message() === TEST_TAG) seen.push(d.defaultValue());
    await d.dismiss().catch(() => {});
  });
  return seen;
}

const FIXTURES = [
  {
    name: 'entra',
    file: 'entra.html',
    what: 'identifier-first SSO: both steps are reported, and the second needs no re-injection',
    run: async (page, reports, fail) => {
      await page.evaluate(detectScript());
      await sleep(150);

      // Step one. The hidden decoy password field must NOT be read as the password stage.
      if (reports.length !== 1) return fail(`expected 1 report on load, got ${reports.length}: ${reports}`);
      if (!reports[0].startsWith('field|user|')) {
        return fail(`load report should be the identifier stage, got: ${reports[0]}`);
      }

      // The submit script must refuse to fire while the identifier box is empty.
      await page.evaluate(submitScript('user'));
      await sleep(300);
      if (await page.evaluate(() => window.__nextPressedWith !== undefined)) {
        return fail('submitted the identifier step with an empty field');
      }

      await page.evaluate(fillScript(USER, PASS, 'user'));
      await sleep(150);
      const emailValue = await page.$eval('#i0116', (el) => el.value);
      if (emailValue !== USER) return fail(`identifier not filled, got "${emailValue}"`);
      // The secret must not reach a page that has nowhere to put it.
      if (await page.evaluate(() => document.documentElement.innerHTML.indexOf('battery-staple') >= 0)) {
        return fail('password leaked into the identifier step');
      }

      await page.evaluate(submitScript('user'));
      await page.waitForFunction('window.__pwViewShown===1', { timeout: 4000 })
        .catch(() => fail('Next was never pressed — the identifier step did not advance'));

      // THE regression. No navigation happened, so nothing re-injected the detector; only the
      // mutation watcher can see the password field arrive.
      await sleep(600);
      const pwReports = reports.filter((r) => r.startsWith('field|pw|'));
      if (pwReports.length !== 1) {
        return fail(`password step not reported after the view swap (reports: ${reports.join(' , ')})`);
      }

      await page.evaluate(fillScript(USER, PASS, 'pw'));
      await sleep(150);
      const pwValue = await page.$eval('#i0118', (el) => el.value);
      if (pwValue !== PASS) return fail(`password not filled, got "${pwValue}"`);

      await page.evaluate(submitScript('pw'));
      await sleep(600);
      const pressedWith = await page.evaluate(() => window.__signInPressedWith);
      if (pressedWith !== PASS) return fail(`Sign in not pressed with the filled password (${pressedWith})`);
    },
  },
  {
    name: 'classic',
    file: 'classic.html',
    what: 'an ordinary one-step form still fills and submits unchanged',
    run: async (page, reports, fail) => {
      await page.evaluate(detectScript());
      await sleep(150);
      if (reports.length !== 1 || !reports[0].startsWith('field|pw|')) {
        return fail(`expected one password-stage report, got: ${reports.join(' , ')}`);
      }
      await page.evaluate(fillScript(USER, PASS, 'pw'));
      await sleep(150);
      const values = await page.evaluate(() => ({
        user: document.querySelector('input[name=username]').value,
        pass: document.querySelector('input[name=password]').value,
        news: document.querySelector('input[name=newsletter]').value,
      }));
      if (values.user !== USER) return fail(`username not filled, got "${values.user}"`);
      if (values.pass !== PASS) return fail(`password not filled, got "${values.pass}"`);
      // The username guess must stay inside the login form.
      if (values.news !== '') return fail('filled the newsletter box outside the login form');

      await page.evaluate(submitScript('pw'));
      await sleep(600);
      if (!(await page.evaluate(() => window.__submitted >= 1))) return fail('form was never submitted');
    },
  },
  {
    name: 'nologin',
    file: 'nologin.html',
    what: 'a churning page with no login never arms the watcher and never reports',
    run: async (page, reports, fail) => {
      await page.evaluate(detectScript());
      await sleep(1200); // ~40 mutations
      if (reports.length !== 0) return fail(`reported on a page with no login: ${reports.join(' , ')}`);
      const armed = await page.evaluate(() => !!window.__tangentLoginBound);
      if (!armed) return fail('the focus listener should still be bound');
    },
  },
];

(async () => {
  const only = process.argv[2];
  const chosen = only ? FIXTURES.filter((f) => f.name === only) : FIXTURES;
  if (!chosen.length) {
    console.error(`no such fixture: ${only}`);
    process.exit(2);
  }

  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new' });
  let failures = 0;
  for (const fixture of chosen) {
    const page = await browser.newPage();
    const reports = bridge(page);
    const problems = [];
    const fail = (msg) => problems.push(msg);
    try {
      await page.goto('file://' + path.resolve(__dirname, 'fixtures', fixture.file), {
        waitUntil: 'load',
      });
      await fixture.run(page, reports, fail);
    } catch (e) {
      fail(`threw: ${e.message}`);
    }
    if (problems.length) {
      failures++;
      console.log(`FAIL ${fixture.name} — ${fixture.what}`);
      for (const p of problems) console.log(`     ${p}`);
    } else {
      console.log(`ok   ${fixture.name} — ${fixture.what}`);
    }
    await page.close();
  }
  await browser.close();
  process.exit(failures ? 1 : 0);
})();
