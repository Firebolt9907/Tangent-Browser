// Extracts the EXACT reader-extraction script from ReaderExtractor.kt (same bytes that ship),
// inlining the bundled reader_rules.json the way ReaderRules.parseRuleset would, so the harness
// exercises the real extractor + real per-site rules against live DOM.
const fs = require('fs');
const path = require('path');

const KT = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/reader/ReaderExtractor.kt');
const RULES = path.resolve(__dirname, '../../app/src/main/assets/reader_rules.json');

// Mirror ReaderRules.parseRuleset: emit the compact rules[] array (match/include/strip only).
function compactRules() {
  const doc = JSON.parse(fs.readFileSync(RULES, 'utf8'));
  const clean = (v) => {
    const items = Array.isArray(v) ? v : (typeof v === 'string' ? [v] : []);
    return items.map(s => String(s).trim())
      .filter(s => s && !s.includes('//') && !s.includes('\n') && !s.includes('\r'));
  };
  const out = [];
  for (const r of (doc.rules || [])) {
    const match = clean(r.match);
    if (!match.length) continue;
    const o = { match };
    const inc = clean(r.include); if (inc.length) o.include = inc;
    const strip = clean(r.strip); if (strip.length) o.strip = strip;
    out.push(o);
  }
  return JSON.stringify(out);
}

function buildReaderExtractorScript(rulesJson) {
  const src = fs.readFileSync(KT, 'utf8');
  const marker = 'internal fun buildReaderExtractorScript';
  const start = src.indexOf(marker);
  if (start < 0) throw new Error('function not found');
  const q1 = src.indexOf('"""', start);
  const q2 = src.indexOf('"""', q1 + 3);
  let tpl = src.slice(q1 + 3, q2);
  const lines = tpl.split('\n');
  while (lines.length && lines[0].trim() === '') lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const indent = Math.min(...lines.filter(l => l.trim()).map(l => l.match(/^\s*/)[0].length));
  tpl = lines.map(l => l.slice(indent)).join('\n');
  // Kotlin ${'$'} -> literal $
  tpl = tpl.replace(/\$\{'\$'\}/g, '$');
  // Inline the rules array where the Kotlin does `var __tangentRules = $rulesJson;`
  tpl = tpl.replace('$rulesJson', rulesJson);
  return tpl.replace(/\n/g, ' ');
}

module.exports = { buildReaderExtractorScript, compactRules };
