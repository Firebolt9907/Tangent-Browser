// Extracts the EXACT dynamic-tier and teardown scripts from WebDarkMode.kt, so the harness
// exercises the same bytes that ship instead of a paraphrase that can drift.
//
// Mirrors Kotlin's `""" ... """.trimIndent().replace("\n", " ")` and resolves the `${CONST}`
// interpolations from the `private const val` declarations in the same file.
const fs = require('fs');
const path = require('path');

// WEBDARKMODE_KT overrides the source, so the same fixtures can be replayed against an older
// revision to confirm they actually catch the bugs they claim to (see README).
const KT = process.env.WEBDARKMODE_KT || path.resolve(
  __dirname,
  '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/WebDarkMode.kt',
);

function source() {
  return fs.readFileSync(KT, 'utf8');
}

/** Every `private const val NAME = <literal>` in the file, quotes stripped. */
function constants(src) {
  const out = {};
  const re = /(?:private |internal )?const val ([A-Z_0-9]+)(?:\s*:\s*\w+)?\s*=\s*("([^"]*)"|[0-9]+)/g;
  let m;
  while ((m = re.exec(src))) out[m[1]] = m[3] !== undefined ? m[3] : m[2];
  return out;
}

/** The raw text of the triple-quoted template that follows `marker`. */
function rawTemplate(src, marker) {
  const start = src.indexOf(marker);
  if (start < 0) throw new Error(`not found: ${marker}`);
  const q1 = src.indexOf('"""', start);
  const q2 = src.indexOf('"""', q1 + 3);
  return src.slice(q1 + 3, q2);
}

function extract(marker, params = {}) {
  const src = source();
  const consts = constants(src);
  let tpl = rawTemplate(src, marker);

  // Builder parameters (`${aggressive}`, `${analyze}`) — supplied by the caller, since they are
  // Kotlin arguments rather than file constants.
  for (const [name, value] of Object.entries(params)) {
    tpl = tpl.split('${' + name + '}').join(String(value));
  }

  // `$darkDetectJs` is the shared "is this page already dark?" block, interpolated into both script
  // builders. Inline it before anything else so the harness runs the same detector that ships.
  if (tpl.includes('$darkDetectJs')) {
    tpl = tpl.split('$darkDetectJs').join(rawTemplate(src, 'private val darkDetectJs'));
  }

  // Kotlin trimIndent(): drop blank edge lines, strip the common indent.
  const lines = tpl.split('\n');
  while (lines.length && lines[0].trim() === '') lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const indent = Math.min(...lines.filter((l) => l.trim()).map((l) => l.match(/^\s*/)[0].length));
  tpl = lines.map((l) => l.slice(indent)).join('\n');

  tpl = tpl.replace(/\$\{'\$'\}/g, '$');
  tpl = tpl.replace(/\$\{([A-Z_0-9]+)\}/g, (whole, name) => {
    if (!(name in consts)) throw new Error(`unresolved interpolation \${${name}} in ${marker}`);
    return consts[name];
  });

  const left = tpl.match(/\$\{[^}]*\}/) || tpl.match(/\$[A-Za-z_][A-Za-z_0-9]*/);
  if (left) throw new Error(`unresolved interpolation ${left[0]} in ${marker}`);

  // Kotlin does .replace("\n", " ") for these two scripts.
  return tpl.replace(/\n/g, ' ');
}

/** Strip the `javascript:` scheme so the body can be handed to page.evaluate. */
function body(script) {
  return script.replace(/^javascript:/, '');
}

module.exports = {
  constants: () => constants(source()),
  dynamicScript: () => extract('internal fun buildDynamicScript'),
  removeScript: () => extract('internal fun buildRemoveScript'),
  applyScript: (aggressive = false, analyze = false) =>
    extract('internal fun buildApplyScript', { aggressive, analyze }),
  /** The shared already-dark detector on its own, for `detect.js`. */
  detectScript: () => extract('private val darkDetectJs'),
  body,
};
