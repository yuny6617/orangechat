'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'ui/index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'ui/styles.css'), 'utf8');
const app = fs.readFileSync(path.join(root, 'ui/app.js'), 'utf8');

// 「其他」不再是浮层弹窗：真机 WebView 上 fixed 浮层高度会被压成只剩标题栏，
// 所以收敛成设置页最底部的原生 <details> 折叠块。这些断言钉住"没有浮层依赖"。
assert.doesNotMatch(html, /id="settingsOtherDialog"/);
assert.doesNotMatch(html, /oc-other-overlay|oc-other-dialog|oc-other-body/);
assert.doesNotMatch(css, /oc-other-overlay|oc-other-dialog/);
assert.doesNotMatch(app, /openOtherSettings|closeOtherSettings|settingsOtherDialog|oc-modal-open/);

assert.match(html, /<details id="settingsOtherFold" class="oc-card oc-fold">/);
assert.match(html, /<summary class="oc-fold-summary">/);
assert.match(html, /class="oc-fold-body"/);
assert.doesNotMatch(html, /<dialog\b/);

// 四个内容容器必须唯一且都在折叠块内部（顺序：主题来源 → 能力与数据 → 关于）。
const foldStart = html.indexOf('id="settingsOtherFold"');
const foldEnd = html.indexOf('</details>', foldStart);
assert.notEqual(foldStart, -1);
assert.notEqual(foldEnd, -1);
const fold = html.slice(foldStart, foldEnd);
for (const id of ['settingsTheme', 'settingsCaps', 'settingsData', 'settingsAbout']) {
  assert.equal([...html.matchAll(new RegExp('id="' + id + '"', 'g'))].length, 1, id + ' must be unique');
  assert.ok(fold.includes('id="' + id + '"'), id + ' must live inside the fold');
}

// 折叠块样式不依赖定位/层级/视口单位：这些正是真机上塌掉的来源。
function rule(selector) {
  const start = css.indexOf(selector + ' {');
  assert.notEqual(start, -1, 'missing rule ' + selector);
  return css.slice(start, css.indexOf('}', start) + 1);
}
const summary = rule('.oc-fold > summary.oc-fold-summary');
assert.match(summary, /display:\s*flex/);
assert.match(summary, /list-style:\s*none/);
assert.doesNotMatch(summary, /position:\s*(fixed|absolute)/);
const body = rule('.oc-fold-body');
assert.doesNotMatch(body, /position:\s*(fixed|absolute)/);
assert.doesNotMatch(body, /height:\s*\d+(vh|dvh)/);
assert.match(rule('.oc-fold-body h4'), /font-size/);

// 折叠块必须在设置视图内、且在"阅读"卡片之后（最下面）。
const settingsStart = html.indexOf('id="view-settings"');
const settingsEnd = html.indexOf('</section>', settingsStart);
assert.ok(foldStart > settingsStart && foldStart < settingsEnd, 'fold must be inside the settings view');
assert.ok(html.indexOf('id="settingPageSize"') < foldStart, 'fold must come after the reading card');

console.log('modal-layout.test: bottom fold replaces the viewport modal, content identity checks passed');
