// Execute the OTP fill template against a minimal synthetic DOM. No network or real codes.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname,
  '../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/OtpAutofill.kt'), 'utf8');
const shared = source.match(/private val sharedJs = """([\s\S]*?)"""/)[1];
const fill = source.slice(source.indexOf('fun buildFillScript(')).match(/return \("""([\s\S]*?)"""/)[1]
  .replace('$sharedJs', shared).replace('$originLiteral', JSON.stringify('https://bank.example'))
  .replace('$safe', '123456').trim().replace(/^javascript:/, '');

function run(origin, split = false) {
  class Input {
    constructor() { this.tagName = 'INPUT'; this.type = 'text'; this.value = ''; }
    getAttribute(name) { return ({ autocomplete: 'one-time-code', maxlength: split ? '1' : '6', inputmode: 'numeric' })[name] || ''; }
    getBoundingClientRect() { return { width: 100, height: 30 }; }
    focus() {}
    dispatchEvent() {}
  }
  const fields = Array.from({ length: split ? 6 : 1 }, () => new Input());
  const context = {
    location: { origin }, HTMLInputElement: Input,
    document: { querySelectorAll: () => fields, activeElement: fields[0] },
    window: { getComputedStyle: () => ({ visibility: 'visible', display: 'block', opacity: '1' }) },
    Event: class {}, KeyboardEvent: class {},
  };
  vm.runInNewContext(fill, context, { timeout: 1000 });
  return fields.map(field => field.value).join('');
}
for (const origin of ['https://evil.example', 'http://bank.example', 'https://bank.example:8443', 'null']) {
  assert.equal(run(origin), '', origin);
  assert.equal(run(origin, true), '', origin);
}
assert.equal(run('https://bank.example'), '123456');
assert.equal(run('https://bank.example', true), '123456');
console.log('PASS: OTP refuses foreign origins; matching single and split inputs fill');
