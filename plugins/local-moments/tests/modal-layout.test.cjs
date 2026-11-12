'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'ui/index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'ui/styles.css'), 'utf8');
const app = fs.readFileSync(path.join(root, 'ui/app.js'), 'utf8');
function rule(selector) {
  const start = css.indexOf(selector + ' {');
  assert.notEqual(start, -1, 'missing rule ' + selector);
  return css.slice(start, css.indexOf('}', start) + 1);
}

// Pair with ui-smoke's real open/populate/focus/close checks. These constraints
// guard the small-screen layout that a fake DOM cannot measure.
assert.match(html, /id="settingsOtherDialog"[^>]*role="dialog"[^>]*aria-modal="true"[^>]*hidden/);
assert.doesNotMatch(html, /<dialog\b/);
for (const id of ['settingsTheme', 'settingsCaps', 'settingsData', 'settingsAbout']) {
  assert.equal([...html.matchAll(new RegExp('id="' + id + '"', 'g'))].length, 1, id + ' must be unique');
}
assert.match(rule('.oc-other-overlay'), /position:\s*fixed/);
assert.match(rule('.oc-other-overlay'), /inset:\s*0/);
assert.match(rule('.oc-other-dialog'), /height:\s*80vh/);
assert.match(rule('.oc-other-dialog'), /height:\s*80dvh/);
assert.match(rule('.oc-other-dialog'), /max-height:\s*calc\(100% - 16px\)/);
assert.match(rule('.oc-other-head'), /flex:\s*0 0 auto/);
assert.match(rule('.oc-other-body'), /min-height:\s*0/);
assert.match(rule('.oc-other-body'), /overflow-y:\s*auto/);
assert.match(css, /\[hidden\]\s*\{\s*display:\s*none\s*!important/);
assert.match(app, /function openOtherSettings\(\)\s*\{[\s\S]*?renderSettings\(\)/);
assert.doesNotMatch(app, /otherDialog\.showModal|otherDialog\.setAttribute\('open'/);
console.log('modal-layout.test: viewport bounds, scroll body and content identity checks passed');
