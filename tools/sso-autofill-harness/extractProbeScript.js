// Extracts the EXACT detect/fill/submit scripts from LoginFieldProbe.kt, so the harness exercises
// the same bytes that ship instead of a paraphrase that can drift.
//
// Mirrors Kotlin's `""" ... """.trimIndent().replace("\n", " ")`, and resolves interpolations in the
// same order Kotlin does: the template is fully substituted FIRST, then trimIndent runs over the
// result (which is why the shared JS block's shallower indent sets the common indent for the whole
// script).
const fs = require('fs');
const path = require('path');

// LOGINPROBE_KT overrides the source, so a fixture can be replayed against an older revision to
// confirm it actually catches the bug it claims to (see README).
const KT = process.env.LOGINPROBE_KT || path.resolve(
  __dirname,
  '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/LoginFieldProbe.kt',
);

/** A stand-in for the per-process BridgeGuard nonce; the harness only needs it to be stable. */
const TEST_TAG = 'tangent-login~testnonce';

function source() {
  return fs.readFileSync(KT, 'utf8');
}

/** Every `const val NAME = <number>` in the file. */
function constants(src) {
  const out = {};
  const re = /(?:private |internal )?const val ([A-Za-z_0-9]+)(?:\s*:\s*\w+)?\s*=\s*([0-9_]+)/g;
  let m;
  while ((m = re.exec(src))) out[m[1]] = m[2].replace(/_/g, '');
  return out;
}

/**
 * The raw text of the triple-quoted template that follows `marker`, un-trimmed.
 *
 * `opener` anchors on `return ("""` rather than the next `"""`, because a builder may declare
 * triple-quoted *values* (the submit script's label regexes) before the script template itself.
 */
function rawTemplate(src, marker, opener = 'return ("""') {
  const start = src.indexOf(marker);
  if (start < 0) throw new Error(`not found: ${marker}`);
  const q1 = src.indexOf(opener, start);
  if (q1 < 0) throw new Error(`no ${opener} after ${marker}`);
  const from = q1 + opener.length;
  const q2 = src.indexOf('"""', from);
  return src.slice(from, q2);
}

/** Kotlin trimIndent(): drop blank edge lines, strip the common indent of the remaining ones. */
function trimIndent(tpl) {
  const lines = tpl.split('\n');
  while (lines.length && lines[0].trim() === '') lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const indent = Math.min(...lines.filter((l) => l.trim()).map((l) => l.match(/^\s*/)[0].length));
  return lines.map((l) => l.slice(indent)).join('\n');
}

function build(marker, extra = {}) {
  const src = source();
  const consts = constants(src);
  const shared = rawTemplate(src, 'private val sharedJs', 'private val sharedJs = """');

  let tpl = rawTemplate(src, marker);
  tpl = tpl.replace(/\$sharedJs/g, shared);
  tpl = tpl.replace(/\$\{BridgeGuard\.tag\(BRIDGE_MESSAGE\)\}/g, TEST_TAG);
  for (const [name, value] of Object.entries(extra)) {
    tpl = tpl.split('${' + name + '}').join(value).split('$' + name).join(value);
  }
  tpl = tpl.replace(/\$([A-Za-z_][A-Za-z_0-9]*)/g, (whole, name) => {
    if (name in consts) return consts[name];
    throw new Error(`unresolved interpolation $${name} in ${marker}`);
  });
  const left = tpl.match(/\$\{[^}]*\}/);
  if (left) throw new Error(`unresolved interpolation ${left[0]} in ${marker}`);

  return trimIndent(tpl).replace(/\n/g, ' ').replace(/^javascript:/, '');
}

const PASSWORD_LABELS = String.raw`/log\s?in|sign\s?in|submit|continue|verify/`;
const USER_LABELS = String.raw`/next|continue|sign\s?in|log\s?in|submit/`;

module.exports = {
  TEST_TAG,
  detectScript: () => build('fun buildDetectScript'),
  fillScript: (username, password, stage) => build('fun buildFillScript', {
    u: JSON.stringify(username),
    p: JSON.stringify(stage === 'pw' ? password : ''),
    wantPassword: String(stage === 'pw'),
  }),
  submitScript: (stage) => build('fun buildSubmitScript', {
    wantPassword: String(stage === 'pw'),
    labels: stage === 'pw' ? PASSWORD_LABELS : USER_LABELS,
  }),
};
