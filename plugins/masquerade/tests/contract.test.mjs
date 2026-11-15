import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MANIFEST = path.join(ROOT, 'plugin', 'manifest.json');
const MAIN = path.join(ROOT, 'plugin', 'main.js');
const manifest = JSON.parse(fs.readFileSync(MANIFEST, 'utf8'));
const mainSrc = fs.readFileSync(MAIN, 'utf8');

function loadExports() {
  const sandbox = {
    exports: {},
    dataStore: { get: () => null, set: () => {}, del: () => {}, list: () => [] },
    config: {},
    memoryBank: { save: () => {} },
    fetch: () => ({ ok: false, status: 0, body: '{}', json: () => ({}) }),
    console: { warn() {}, log() {}, info() {}, error() {} },
  };
  vm.createContext(sandbox);
  vm.runInContext(mainSrc, sandbox, { filename: 'main.js' });
  return sandbox;
}

const REQUIRED_FIELDS = ['id', 'name', 'description', 'version', 'author', 'icon', 'entry'];

test('manifest 必填字段齐全且标识稳定', () => {
  for (const f of REQUIRED_FIELDS) assert.ok(manifest[f], `缺少 ${f}`);
  assert.equal(manifest.id, 'com.orangechat.masquerade');
  assert.equal(manifest.entry, 'main.js');
  assert.equal(fs.existsSync(path.join(ROOT, 'plugin', manifest.entry)), true);
  assert.match(manifest.version, /^\d+\.\d+\.\d+$/);
});

test('WebView 专属页面入口真实存在', () => {
  assert.equal(manifest.customPageWebView.entry, 'ui/index.html');
  const p = path.join(ROOT, 'plugin', manifest.customPageWebView.entry);
  assert.equal(fs.existsSync(p), true, 'ui/index.html 必须随插件一起打包');
});

test('每个 tools[].name 都能在 main.js 的 exports 上找到同名函数', () => {
  const { exports: api } = loadExports();
  assert.ok(manifest.tools.length >= 10);
  for (const tool of manifest.tools) {
    assert.equal(typeof api[tool.name], 'function', `${tool.name} 未导出`);
    assert.ok(tool.description && tool.description.length > 4, `${tool.name} 缺描述`);
    for (const p of tool.parameters || []) {
      assert.ok(['string', 'number', 'integer', 'boolean', 'object', 'array'].includes(p.type));
    }
  }
  for (const name of Object.keys(api)) {
    assert.ok(manifest.tools.some((t) => t.name === name), `exports 上存在未声明的工具 ${name}`);
  }
});

test('main.js 严格限定在 QuickJS ES5 子集内', () => {
  const code = mainSrc.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const forbidden = [
    [/\b(let|const)\s+/, 'let/const'],
    [/=>/, '箭头函数'],
    [/`/, '模板字符串'],
    [/\basync\b/, 'async'],
    [/\bawait\b/, 'await'],
    [/\bPromise\b/, 'Promise'],
    [/\bclass\s+\w+/, 'class'],
    [/\.\.\./, '展开运算符'],
  ];
  for (const [re, label] of forbidden) {
    assert.equal(re.test(code), false, `QuickJS 不支持 ${label}`);
  }
});

test('manifest 网络白名单与 main.js 内置白名单一致，且无通配符', () => {
  const { ALLOWED_HOSTS } = loadExports();
  assert.ok(Array.isArray(ALLOWED_HOSTS));
  assert.deepEqual([...ALLOWED_HOSTS].sort(), [...manifest.allowedHosts].sort());
  assert.equal(manifest.allowedHosts.includes('*'), false);
  assert.ok(manifest.allowedHosts.length > 0);
  for (const h of manifest.allowedHosts) assert.match(h, /^[a-z0-9.-]+$/);
});

test('权限最小化：只用 ai_chat 与 conversation_sync', () => {
  assert.deepEqual([...manifest.permissions].sort(), ['ai_chat', 'conversation_sync']);
  assert.equal(manifest.permissions.includes('provider_credentials'), false);
  assert.equal(manifest.permissions.includes('*'), false);
});

test('inject_as_prompt 默认开启，规则里写明游戏与真实关系', () => {
  const inject = manifest.config.find((c) => c.name === 'inject_as_prompt');
  assert.ok(inject);
  assert.equal(inject.type, 'boolean');
  assert.equal(inject.default, true);
  assert.ok(manifest.promptTemplate.includes('角色扮演'));
  assert.ok(manifest.promptTemplate.includes('真实情感关系'));
  assert.ok(manifest.promptTemplate.includes('落幕'));
});

test('API Key 用 password 字段声明，生产代码无硬编码密钥', () => {
  const keyField = manifest.config.find((c) => c.name === 'api_key');
  assert.equal(keyField.type, 'password');
  const prodFiles = ['plugin/main.js', 'plugin/manifest.json', 'plugin/ui/app.js', 'mcp/server.mjs'];
  for (const rel of prodFiles) {
    const p = path.join(ROOT, rel);
    if (!fs.existsSync(p)) continue;
    const text = fs.readFileSync(p, 'utf8');
    assert.equal(/\bsk-[A-Za-z0-9]{20,}\b/.test(text), false, `${rel} 里疑似硬编码密钥`);
    assert.equal(/(api[_-]?key|secret|token)\s*[:=]\s*["'][A-Za-z0-9_-]{16,}["']/i.test(text), false, `${rel} 里疑似硬编码凭据`);
  }
});
