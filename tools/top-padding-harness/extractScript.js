// Extracts the EXACT injected script from PageBottomSpacer.kt so the harness tests the
// same bytes that ship. Mirrors buildViewportSpacerScript's Kotlin string interpolation.
const fs = require('fs');
const path = require('path');

// Resolve the shipping source relative to this tool, so the harness is portable.
const KT = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/PageBottomSpacer.kt');

// Shared: pull the triple-quoted Kotlin template out of `marker`'s function body and flatten it
// exactly the way Kotlin's .trimIndent().replace("\n","") does.
function extractTemplate(marker) {
  const src = fs.readFileSync(KT, 'utf8');
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
  // ${'$'} is Kotlin's escape for a literal $ inside a template (used in regex end-anchors like
  // /...\)${'$'}/i); collapse it to $ so the harness runs the same regex the app ships.
  tpl = tpl.replace(/\$\{'\$'\}/g, '$');
  return tpl;
}

function buildViewportSpacerScript(topPx, skipHeaderInsets) {
  let tpl = extractTemplate('internal fun buildViewportSpacerScript');
  tpl = tpl.replace('${topPx}', String(topPx));
  tpl = tpl.replace('${if (skipHeaderInsets) "true" else "false"}', skipHeaderInsets ? 'true' : 'false');
  return tpl.replace(/\n/g, '');
}

// The BOTTOM spacer ships alongside the top one — injectViewportSpacers() injects both — so any
// probe asking how they interact needs the real bytes of this one too.
function buildBottomSpacerScript(bottomPx) {
  let tpl = extractTemplate('internal fun buildBottomSpacerScript');
  tpl = tpl.replace('${bottomPx}', String(bottomPx));
  return tpl.replace(/\n/g, '');
}

module.exports = { buildViewportSpacerScript, buildBottomSpacerScript };

if (require.main === module) {
  const s = buildViewportSpacerScript(48, false);
  console.log(s.slice(0, 200));
  console.log('...len=', s.length, 'startsJs=', s.startsWith('javascript:'), 'hasNewline=', s.includes('\n'));
}
