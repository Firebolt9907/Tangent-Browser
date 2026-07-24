// Extracts the EXACT injected script from PageBottomSpacer.kt so the harness tests the
// same bytes that ship. Mirrors buildViewportSpacerScript's Kotlin string interpolation.
const fs = require('fs');
const path = require('path');

// Resolve the shipping source relative to this tool, so the harness is portable.
const KT = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/PageBottomSpacer.kt');

function buildViewportSpacerScript(topPx, skipHeaderInsets) {
  const src = fs.readFileSync(KT, 'utf8');
  // Grab the triple-quoted template inside buildViewportSpacerScript's return.
  const marker = 'internal fun buildViewportSpacerScript';
  const start = src.indexOf(marker);
  if (start < 0) throw new Error('function not found');
  const q1 = src.indexOf('"""', start);
  const q2 = src.indexOf('"""', q1 + 3);
  let tpl = src.slice(q1 + 3, q2);
  // Kotlin does .trimIndent().replace("\n","") — emulate: strip common indent then join lines.
  const lines = tpl.split('\n');
  // drop leading/trailing blank lines
  while (lines.length && lines[0].trim() === '') lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const indent = Math.min(...lines.filter(l => l.trim()).map(l => l.match(/^\s*/)[0].length));
  tpl = lines.map(l => l.slice(indent)).join('\n');
  // Kotlin interpolations
  tpl = tpl.replace('${topPx}', String(topPx));
  tpl = tpl.replace('${if (skipHeaderInsets) "true" else "false"}', skipHeaderInsets ? 'true' : 'false');
  return tpl.replace(/\n/g, '');
}

module.exports = { buildViewportSpacerScript };

if (require.main === module) {
  const s = buildViewportSpacerScript(48, false);
  console.log(s.slice(0, 200));
  console.log('...len=', s.length, 'startsJs=', s.startsWith('javascript:'), 'hasNewline=', s.includes('\n'));
}
