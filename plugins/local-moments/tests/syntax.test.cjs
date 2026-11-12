'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
let checks = 0;

function check(name, fn) {
  try {
    fn();
    checks += 1;
    console.log('ok ' + name);
  } catch (error) {
    console.error('FAIL ' + name);
    throw error;
  }
}

function read(relative) {
  return fs.readFileSync(path.join(ROOT, relative), 'utf8');
}

const JS_FILES = ['main.js', 'ui/core.js', 'ui/bridge.js', 'ui/app.js'];

check('all plugin JavaScript passes node --check', () => {
  for (const file of JS_FILES) {
    execFileSync(process.execPath, ['--check', path.join(ROOT, file)], { stdio: 'inherit' });
  }
});

check('manifest is strict, local-only and points at real files', () => {
  const manifest = JSON.parse(read('manifest.json'));
  const allowed = new Set([
    'id', 'name', 'description', 'version', 'author', 'icon', 'entry',
    'permissions', 'allowedHosts', 'customPageWebView'
  ]);
  for (const key of Object.keys(manifest)) {
    assert.ok(allowed.has(key), 'unexpected manifest field: ' + key);
  }
  assert.equal(manifest.id, 'com.orangechat.localmoments');
  assert.match(manifest.version, /^\d+\.\d+\.\d+$/);
  assert.equal(manifest.entry, 'main.js');
  assert.equal(manifest.customPageWebView.entry, 'ui/index.html');
  assert.deepEqual(manifest.allowedHosts, []);
  assert.deepEqual([...manifest.permissions].sort(), [
    'localdata.backup',
    'localdata.chat_snapshot',
    'localdata.comment',
    'localdata.like',
    'localdata.media',
    'localdata.publish',
    'localdata.read',
    'localdata.write'
  ].sort());
  assert.equal(Object.prototype.hasOwnProperty.call(manifest, 'tools'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(manifest, 'config'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(manifest, 'hooks'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(manifest, 'promptTemplate'), false);
  for (const file of ['main.js', 'ui/index.html', 'ui/styles.css', 'ui/core.js', 'ui/bridge.js', 'ui/app.js']) {
    assert.ok(fs.statSync(path.join(ROOT, file)).isFile(), 'missing ' + file);
  }
});

check('main.js stays ES5 and does not register model tools', () => {
  const main = read('main.js');
  assert.equal(/\b(?:const|let)\s/.test(main), false, 'main.js must use var');
  assert.equal(/=>/.test(main), false, 'main.js must not use arrows');
  assert.equal(/`/.test(main), false, 'main.js must not use template literals');
  assert.match(main, /exports\.MOMENTS_PLUGIN/);
  assert.equal(/\bmoments_(?:list|get|search|comments|create|like|comment)\b/.test(main), false);
});

check('HTML has a strict CSP, external scripts and no inline handlers', () => {
  const html = read('ui/index.html');
  assert.match(html, /default-src 'none'/);
  assert.match(html, /script-src 'self'/);
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /img-src 'self' https:\/\/localdata\.invalid data:/);
  assert.match(html, /meta name="referrer" content="no-referrer"/);
  assert.match(html, /frame-src bridge:/);
  assert.equal(/<script\b(?![^>]*\bsrc=)/i.test(html), false, 'no inline script tags');
  assert.equal(/\son[a-z]+\s*=/i.test(html), false, 'no inline event handlers');
  const scripts = [...html.matchAll(/<script src="([^"]+)"/g)].map(match => match[1]);
  assert.deepEqual(scripts, ['core.js', 'bridge.js', 'app.js']);
  assert.match(html, /<link rel="stylesheet" href="styles.css">/);
});

check('UI JavaScript does not use browser persistence, network or unsafe HTML APIs', () => {
  const files = ['ui/core.js', 'ui/bridge.js', 'ui/app.js'];
  const patterns = [
    ['localStorage usage', /(?:window\.)?localStorage\s*[.[]/],
    ['sessionStorage usage', /(?:window\.)?sessionStorage\s*[.[]/],
    ['IndexedDB usage', /\bindexedDB\s*[.[]/],
    ['fetch call', /\bfetch\s*\(/],
    ['XMLHttpRequest', /\bXMLHttpRequest\b/],
    ['WebSocket', /\bWebSocket\b/],
    ['sendBeacon', /\bsendBeacon\s*\(/],
    ['callAI call', /\bcallAI\s*\(/],
    ['call_ai call', /\bcall_ai\s*\(/],
    ['eval', /\beval\s*\(/],
    ['new Function', /\bnew\s+Function\s*\(/],
    ['innerHTML', /\.innerHTML\b/],
    ['outerHTML', /\.outerHTML\b/],
    ['insertAdjacentHTML', /\.insertAdjacentHTML\b/],
    ['document.write', /\bdocument\.write\s*\(/]
  ];
  for (const file of files) {
    const source = read(file);
    for (const [label, pattern] of patterns) {
      assert.equal(pattern.test(source), false, file + ' must not use ' + label);
    }
  }
});

check('UI JavaScript never self-asserts an actor identity in request objects', () => {
  const files = ['ui/bridge.js', 'ui/app.js'];
  const pattern = /["'](?:actorId|actor_id|actorKind|viewerId|asAssistant|isAssistant|actingAssistantId|assistantActorId)["']\s*:/;
  for (const file of files) {
    assert.equal(pattern.test(read(file)), false, file + ' contains an actor-assertion key');
  }
});

check('UI has no mock/sample/fake reply data paths', () => {
  const files = ['ui/core.js', 'ui/bridge.js', 'ui/app.js'];
  const pattern = /\b(?:mock|fake|sample)(?:Reply|Post|Comment|Data|List)?\b/i;
  for (const file of files) {
    assert.equal(pattern.test(read(file)), false, file + ' must not ship mock/sample data');
  }
});

check('only the controlled localdata origin appears in shipped JS/HTML/CSS', () => {
  const files = ['ui/core.js', 'ui/bridge.js', 'ui/app.js', 'ui/index.html', 'ui/styles.css'];
  for (const file of files) {
    const source = read(file);
    const urls = [...source.matchAll(/https?:\/\/[^\s"'<>)]+/g)].map(match => match[0]);
    for (const url of urls) {
      assert.ok(
        url === 'https://localdata.invalid' || url.startsWith('https://localdata.invalid/'),
        file + ' contains unexpected URL ' + url
      );
    }
  }
});

check('app version/id and declared permissions match the manifest', () => {
  const manifest = JSON.parse(read('manifest.json'));
  const app = read('ui/app.js');
  const version = /PLUGIN_VERSION\s*=\s*'([^']+)'/.exec(app);
  const id = /PLUGIN_ID\s*=\s*'([^']+)'/.exec(app);
  assert.ok(version, 'PLUGIN_VERSION missing');
  assert.ok(id, 'PLUGIN_ID missing');
  assert.equal(version[1], manifest.version);
  assert.equal(id[1], manifest.id);
  for (const permission of manifest.permissions) {
    assert.ok(app.includes("'" + permission + "'"), 'app does not declare ' + permission);
  }
});

check('the default page size is 3 and node budgets remain bounded', () => {
  const core = read('ui/core.js');
  assert.match(core, /DEFAULT_PAGE_SIZE\s*=\s*3/);
  assert.match(core, /MAX_LIST_POSTS\s*=\s*30/);
  assert.match(core, /MAX_DETAIL_COMMENTS\s*=\s*60/);
  assert.match(core, /MAX_CHAT_MESSAGES_LIST\s*=\s*6/);
  assert.match(core, /MAX_CHAT_MESSAGES_DETAIL\s*=\s*20/);
});

check('every element id used by app.js exists in index.html', () => {
  const app = read('ui/app.js');
  const html = read('ui/index.html');
  const used = [...app.matchAll(/\$\('([^']+)'\)/g)].map(match => match[1]);
  const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]));
  const missing = [...new Set(used)].filter(id => !ids.has(id));
  assert.deepEqual(missing, []);
});

check('theme application uses host CSS variables and keeps the background transparent', () => {
  const css = read('ui/styles.css');
  assert.match(css, /--oc-bg:\s*transparent/);
  assert.match(css, /background:\s*var\(--oc-bg\)/);
  assert.match(css, /var\(--oc-card-top\)/);
  assert.match(css, /var\(--oc-card-bottom\)/);
  assert.match(read('ui/app.js'), /setProperty\(properties\[j\]\.key, properties\[j\]\.value\)/);
});

console.log('syntax.test: ' + checks + ' checks passed');
