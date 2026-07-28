// Browserless test for src/render.js. render.js builds DOM via a small surface of the document API,
// so we stub just that surface, run tangentRenderReader over a synthetic article touching every block
// type, serialize the result, and assert the shape (list nesting, inline-math sentinel swap, table
// header, link spans). No Chrome/Firefox needed — this validates the renderer's logic directly.
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

// ---- minimal DOM stub ----
const VOID = new Set(['img', 'hr', 'br', 'input']);
class El {
  constructor(tag) {
    this.tag = tag; this.children = []; this.attrs = {}; this._text = '';
    this.style = { setProperty(k, v) { this[k] = v; } };
  }
  // classList backed by attrs.class (space-joined), matching how serialize() reads it.
  get classList() {
    const el = this;
    const list = () => (el.attrs.class ? el.attrs.class.split(' ').filter(Boolean) : []);
    return {
      add(c) { const cur = list(); if (!cur.includes(c)) { cur.push(c); el.attrs.class = cur.join(' '); } },
      remove(...cs) { el.attrs.class = list().filter((x) => !cs.includes(x)).join(' '); },
      contains(c) { return list().includes(c); },
      toggle(c, force) {
        const has = list().includes(c);
        const on = force === undefined ? !has : !!force;
        if (on) this.add(c); else this.remove(c);
        return on;
      },
    };
  }
  // Faithful to the DOM: setting textContent replaces children with a single text node.
  set textContent(v) { this.children = String(v) === '' ? [] : [documentStub.createTextNode(String(v))]; }
  get textContent() { return this.tag === '#text' ? this._text : this.children.map((c) => c.textContent).join(''); }
  set className(v) { this.attrs.class = v; }
  get className() { return this.attrs.class || ''; }
  set id(v) { this.attrs.id = v; }
  set src(v) { this.attrs.src = v; }
  set href(v) { this.attrs.href = v; }
  set width(v) { this.attrs.width = v; }
  set height(v) { this.attrs.height = v; }
  set loading(v) { this.attrs.loading = v; }
  setAttribute(k, v) { this.attrs[k] = v; }
  appendChild(c) { this.children.push(c); return c; }
  addEventListener() {}
  attachShadow() { this.shadow = new El('#shadow'); return this.shadow; }
  remove() {}
  querySelector() { return null; }
  serialize() {
    if (this.tag === '#text') return this._text;
    const cls = this.attrs.class ? ` .${this.attrs.class}` : '';
    if (VOID.has(this.tag)) return `<${this.tag}${cls}>`;
    const kids = this.children.map((c) => c.serialize()).join('');
    return `<${this.tag}${cls}>${kids}</${this.tag}>`;
  }
}
const documentStub = {
  createElement: (t) => new El(t),
  createTextNode: (t) => { const e = new El('#text'); e._text = String(t); return e; },
};

// ---- load render.js into a sandbox with the stub + the math sentinel from extractor.core.js ----
const sentinelLine = fs.readFileSync(path.join(__dirname, 'src/extractor.core.js'), 'utf8')
  .split('\n').find((l) => l.includes('TANGENT_MATH_SENTINEL ='));
const sandbox = { module: { exports: {} }, document: documentStub, requestAnimationFrame: () => {}, setTimeout: () => {} };
vm.createContext(sandbox);
vm.runInContext(sentinelLine + '\n' + fs.readFileSync(path.join(__dirname, 'src/render.js'), 'utf8'), sandbox);
const SENT = vm.runInContext('TANGENT_MATH_SENTINEL', sandbox);
const render = sandbox.module.exports.tangentRenderReader;

// ---- synthetic article covering every block type ----
const article = {
  title: 'Test Title',
  byline: 'By A. Writer',
  blocks: [
    { type: 'h', level: 2, text: 'Section' },
    { type: 'p', text: 'Hello world', spans: [{ text: 'Hello ', href: null }, { text: 'world', href: 'https://x.test' }] },
    { type: 'p', text: 'x ' + SENT + ' y', imath: [{ src: 'm.png', w: 10, h: 8 }] },
    { type: 'li', text: 'top a', ordered: false, depth: 0 },
    { type: 'li', text: 'nested', ordered: false, depth: 1 },
    { type: 'li', text: 'top b', ordered: false, depth: 0 },
    { type: 'bq', text: 'a quote' },
    { type: 'pre', text: 'code()' },
    { type: 'table', hasHeader: true, rows: [['H1', 'H2'], ['a', 'b']] },
    { type: 'hr' },
    { type: 'img', src: 'i.png', caption: 'cap' },
    { type: 'figure_rich', src: 'eq.svg', equation: true },
  ],
};

const host = render(article, 'tangent-reader-overlay-host', '.x{}');
const out = host.shadow.serialize();

const checks = [
  ['backdrop present', /<div \.tangent-reader-backdrop>/.test(out)],
  ['scroll wraps sheet+article', /<div \.tangent-reader-scroll><div \.tangent-reader-sheet><article \.tangent-reader-article>/.test(out)],
  ['dismiss exposed', typeof host.tangentDismiss === 'function'],
  // Top-level article children each carry an entrance-reveal class (see render.js), so class-bearing
  // matches allow extra classes via [^>]*.
  ['title', /<h1 \.tangent-reader-title[^>]*>Test Title<\/h1>/.test(out)],
  ['byline', /tangent-reader-byline[^>]*>By A\. Writer/.test(out)],
  ['heading h2', /<h2[^>]*>Section<\/h2>/.test(out)],
  ['link span', /<a>world<\/a>/.test(out)],
  ['inline math img', /<p[^>]*>x <img \.tangent-reader-math-inline> y<\/p>/.test(out)],
  ['nested list', /<ul[^>]*><li>top a<ul><li>nested<\/li><\/ul><\/li><li>top b<\/li><\/ul>/.test(out)],
  ['blockquote', /<blockquote[^>]*>a quote<\/blockquote>/.test(out)],
  ['pre/code', /<pre[^>]*><code>code\(\)<\/code><\/pre>/.test(out)],
  ['table header th', /<th>H1<\/th><th>H2<\/th>/.test(out) && /<td>a<\/td><td>b<\/td>/.test(out)],
  ['hr', /<hr \.tangent-reader-hr[^>]*>/.test(out)],
  ['figure img+caption', /tangent-reader-figure[^>]*><img[^>]*><figcaption[^>]*>cap</.test(out)],
  ['equation figure', /<figure \.tangent-reader-figure[^>]*><img \.tangent-reader-math>/.test(out)],
  // Entrance animation: every top-level element gets a staggered reveal — title + byline + 10 block
  // children (the three `li` blocks coalesce into one <ul>) = 12.
  ['reveal on every child', (out.match(/tangent-reader-reveal/g) || []).length === 12],
];

let fail = 0;
for (const [name, ok] of checks) { if (!ok) fail++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`); }
if (fail) { console.log('\n--- serialized output ---\n' + out); process.exit(1); }
console.log('\nall render checks passed');
