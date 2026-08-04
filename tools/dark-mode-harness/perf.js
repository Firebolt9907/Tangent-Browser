// Measures the dynamic tier's cost on a large page, and how long the main thread is blocked.
//
// Two numbers matter and they are different:
//   total    wall time from injection until the walk has darkened everything
//   maxTask  the longest single main-thread task — what the user actually feels as jank
//
// The old walk did all its work in one synchronous pass (and stopped early at a fixed cap, so it
// scored a deceptively good "total" by leaving most of the page light). The new one is frame-
// budgeted, so it may take more wall time but must never hold the thread for a long task.
//
//   node perf.js                     current implementation
//   WEBDARKMODE_KT=/tmp/old.kt node perf.js
const path = require('path');
const puppeteer = require(path.resolve(__dirname, '../top-padding-harness/node_modules/puppeteer-core'));
const { dynamicScript, body } = require('./extractDarkScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FIXTURE = process.argv[2] || 'long.html';
const RUNS = 5;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--allow-file-access-from-files', '--hide-scrollbars'],
  });
  const script = body(dynamicScript());
  const totals = [];
  const tasks = [];
  let darkened = 0;

  for (let run = 0; run < RUNS; run++) {
    const page = await browser.newPage();
    await page.setViewport({ width: 900, height: 900 });
    await page.goto('file://' + path.resolve(__dirname, 'fixtures', FIXTURE), { waitUntil: 'load' });

    // Record every long task so we can report the worst main-thread block.
    await page.evaluate(`window.__tasks=[];
      try{new PerformanceObserver(function(l){l.getEntries().forEach(function(e){window.__tasks.push(e.duration);});})
        .observe({entryTypes:['longtask']});}catch(e){}`);

    const t0 = Date.now();
    await page.evaluate(script);
    await page
      .waitForFunction('!window.__lumDyn || window.__lumDyn.q.length===0', { timeout: 20000, polling: 20 })
      .catch(() => {});
    totals.push(Date.now() - t0);

    await sleep(60);
    const worst = await page.evaluate('Math.max.apply(null,[0].concat(window.__tasks||[]))');
    tasks.push(worst);

    // Coverage matters when comparing: finishing fast by giving up is not faster.
    darkened = await page.evaluate(`(function(){
      var els=document.querySelectorAll('.row'),n=0;
      for(var i=0;i<els.length;i++){
        var m=getComputedStyle(els[i]).backgroundColor.match(/rgba?\\(([^)]+)\\)/);
        if(!m)continue;
        var p=m[1].split(',').map(parseFloat);
        if(0.2126*p[0]+0.7152*p[1]+0.0722*p[2]<110)n++;
      }
      return n+'/'+els.length;
    })()`);
    await page.close();
  }
  await browser.close();

  const med = (a) => a.slice().sort((x, y) => x - y)[Math.floor(a.length / 2)];
  console.log(`fixture      ${FIXTURE}`);
  console.log(`source       ${process.env.WEBDARKMODE_KT || 'current WebDarkMode.kt'}`);
  console.log(`elements dark ${darkened}`);
  console.log(`total  (median of ${RUNS})  ${med(totals)} ms   [${totals.join(', ')}]`);
  console.log(`maxTask(median of ${RUNS})  ${Math.round(med(tasks))} ms   [${tasks.map(Math.round).join(', ')}]`);
})();
