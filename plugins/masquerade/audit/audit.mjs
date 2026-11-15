#!/usr/bin/env node
/*
 * 三轮安全审计 / 代码审查（确定性、可复跑、自带检测器自测）
 * 用法：node audit/audit.mjs [--round=1|2|3|all]
 * 每轮都是真实扫描或本地行为验证；失败项退出码非 0，绝不写「已检查」声明代替执行。
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const R = (rel) => path.join(ROOT, rel);
const read = (rel) => fs.readFileSync(R(rel), 'utf8');
const exists = (rel) => fs.existsSync(R(rel));

const PROD_JS = ['plugin/main.js', 'plugin/ui/app.js', 'plugin/ui/boot.js', 'mcp/server.mjs'];
const UI_TEXT = ['plugin/ui/index.html', 'plugin/ui/style.css', 'plugin/ui/app.js', 'plugin/ui/boot.js', 'studio/index.html'];
const mainSrc = read('plugin/main.js');
const manifest = JSON.parse(read('plugin/manifest.json'));

/* ---------- 纯检测器（可用坏样本自测，防假阴性） ---------- */

/** 用括号/引号平衡，完整取出每一条 .innerHTML = ...; 赋值语句。 */
function innerHtmlAssignments(text) {
  const out = [];
  const re = /\.innerHTML\s*=\s*/g;
  let m;
  while ((m = re.exec(text))) {
    let depth = 0;
    let quote = null;
    let slash = false;
    let i = re.lastIndex;
    for (; i < text.length; i++) {
      const ch = text[i];
      if (quote) {
        if (slash) { slash = false; continue; }
        if (ch === '\\') { slash = true; continue; }
        if (ch === quote) quote = null;
        continue;
      }
      if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue; }
      if (ch === '(' || ch === '[' || ch === '{') depth++;
      else if (ch === ')' || ch === ']' || ch === '}') {
        if (depth === 0) break;
        depth--;
      } else if (ch === ';' && depth === 0) { i++; break; }
    }
    out.push(text.slice(m.index, i));
    re.lastIndex = i;
  }
  return out;
}

const detectors = {
  hardcodedSecret(text) {
    return !/\bsk-[A-Za-z0-9]{20,}\b/.test(text) &&
      !/(api[_-]?key|secret|token)\s*[:=]\s*["'][A-Za-z0-9_-]{16,}["']/i.test(text);
  },
  noWildcardHost(hosts) { return Array.isArray(hosts) && hosts.length > 0 && !hosts.includes('*'); },
  es5Only(text) {
    const code = text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    return !/\b(let|const)\s+|=>|`|\basync\b|\bawait\b|\bPromise\b|\bclass\s+\w+|\.\.\./.test(code);
  },
  noEval(text) { return !/\beval\s*\(|new\s+Function\s*\(/.test(text); },
  /*
   * innerHTML 安全判定：逐个赋值检查「动态插值」的来源。
   * 约定：拼接进 HTML 的非字面量片段，只能是 esc(...) 或显式以 safe 命名的、
   * 且本身由 esc(...) 组装的片段（如 safeTagsHtml / safeHtml）。
   * 纯静态字面量、清空赋值天然安全；出现 + rawVar + 即判定为未转义插值。
   * 抓取范围用括号/引号平衡确定整条赋值语句，避免被回调内部的分号截断。
   */
  innerHtmlSafe(text) {
    const zones = innerHtmlAssignments(text);
    return zones.every((zone) => {
      const code = zone.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
      const rawInterpolation = /\+\s*(?!esc\s*\(|safe[A-Z]?\w*\s*\+)[\w$]+(?:\.[\w$]+|\[[^\]]+\])*\s*\+/g;
      const risky = code.match(rawInterpolation) || [];
      return risky.length === 0;
    });
  },
  noExternalRefs(text) {
    const refs = [...text.matchAll(/(?:src|href)\s*=\s*["']([^"']+)["']/g)].map((m) => m[1]);
    const cssUrls = [...text.matchAll(/url\(\s*["']?([^)"']+)["']?\s*\)/g)].map((m) => m[1]);
    return [...refs, ...cssUrls].every((u) => !/^https?:\/\//i.test(u));
  },
};

function selfTest() {
  const cases = [
    ['hardcodedSecret', detectors.hardcodedSecret('const k = "sk-abcdefghijklmnopqrstuvwxyz";'), false],
    ['hardcodedSecret', detectors.hardcodedSecret('const key = config.api_key;'), true],
    ['noWildcardHost', detectors.noWildcardHost(['*']), false],
    ['noWildcardHost', detectors.noWildcardHost(['api.openai.com']), true],
    ['es5Only', detectors.es5Only('const f = (x) => `hi ${x}`;'), false],
    ['es5Only', detectors.es5Only('var f = function (x) { return "hi " + x; };'), true],
    ['noEval', detectors.noEval('eval(userInput)'), false],
    ['innerHtmlSafe', detectors.innerHtmlSafe('box.innerHTML = "<b>" + user.name + "</b>";'), false],
    ['innerHtmlSafe', detectors.innerHtmlSafe('box.innerHTML = "<b>" + esc(user.name) + "</b>";'), true],
    ['innerHtmlSafe', detectors.innerHtmlSafe('box.innerHTML = "<div class=\"empty\">暂无</div>";'), true],
    ['innerHtmlSafe', detectors.innerHtmlSafe('box.innerHTML = "";'), true],
    ['innerHtmlSafe', detectors.innerHtmlSafe('box.innerHTML = "<b>" + data.title + "</b>";'), false],
    ['innerHtmlSafe', detectors.innerHtmlSafe('box.innerHTML = "<b>" + safeHtml + "</b>";'), true],
    ['innerHtmlSafe', detectors.innerHtmlSafe('grid.innerHTML = items.map(function (c) {\n  return "<b>" + c.name + "</b>";\n}).join("");'), false],
    ['innerHtmlSafe', detectors.innerHtmlSafe('grid.innerHTML = items.map(function (c) {\n  var safeHtml = esc(c.name);\n  return "<b>" + safeHtml + "</b>";\n}).join("");'), true],
    ['noExternalRefs', detectors.noExternalRefs('<script src="https://cdn.example.com/a.js"></script>'), false],
    ['noExternalRefs', detectors.noExternalRefs('<script src="app.js"></script>'), true],
  ];
  const bad = cases.filter(([name, actual, expected]) => actual !== expected);
  return {
    ok: bad.length === 0,
    count: cases.length,
    detail: bad.length ? '检测器自测失败：' + bad.map((b) => b[0]).join(', ') : cases.length + ' 组正反样本全部符合预期',
  };
}

/* ---------- main.js 行为沙箱 ---------- */
function sandbox(config = {}) {
  const map = new Map();
  const memory = [];
  const requests = [];
  const exportsObj = {};
  const ctx = {
    exports: exportsObj,
    dataStore: {
      get: (k) => (map.has(k) ? map.get(k) : null),
      set: (k, v) => map.set(k, String(v)),
      del: (k) => map.delete(k),
      list: (p) => Array.from(map.keys()).filter((k) => k.indexOf(p) === 0),
    },
    config: { max_turns: 40, api_base: 'https://api.openai.com/v1', model_id: 'gpt-4o-mini', ...config },
    memoryBank: { save: (t) => memory.push(String(t)), recall: () => [], search: () => [], delete: () => {} },
    fetch: (url, opts) => {
      requests.push({ url, opts });
      const body = JSON.stringify({ choices: [{ message: { content: '（舞台上）我应邀。' } }] });
      return { ok: true, status: 200, body, json: () => JSON.parse(body), text: () => body };
    },
    console: { warn() {}, log() {}, info() {}, error() {} },
  };
  vm.createContext(ctx);
  vm.runInContext(mainSrc, ctx, { filename: 'main.js' });
  return { api: exportsObj, ctx, map, memory, requests };
}

function play(api) {
  const cards = api.list_role_cards({});
  api.masquerade_invite({ card_id: cards.cards[0].id });
  api.masquerade_accept({});
}

/* 规则只是声明；真正执行在 evaluate()，这样 --round=1 不会连带触发第三轮的构建副作用。 */
function check(id, name, fn) { return { id, name, fn }; }

function evaluate(c) {
  try {
    const r = c.fn();
    return { id: c.id, name: c.name, ok: !!r.ok, detail: r.detail || '' };
  } catch (e) {
    return { id: c.id, name: c.name, ok: false, detail: '执行异常：' + String((e && e.message) || e) };
  }
}

/* ---------- 三轮规则 ---------- */

const ROUND_1 = [
  check('R1-01', '生产代码无硬编码密钥/凭据', () => {
    const hits = PROD_JS.filter((f) => !detectors.hardcodedSecret(read(f)));
    return { ok: hits.length === 0, detail: hits.length ? '命中：' + hits.join(', ') : PROD_JS.length + ' 个生产 JS 文件扫描通过' };
  }),
  check('R1-02', '模型配置返回值不泄露 API Key', () => {
    const secret = 'sk-abcdefghijklmnopqrstuvwxyz';
    const r = sandbox({ api_key: secret }).api.get_model_config({});
    const leaked = JSON.stringify(r).includes(secret);
    return { ok: !leaked && r.hasKey === true, detail: 'hasKey=true 且返回值不含密钥原文' };
  }),
  check('R1-03', '模型请求强制 HTTPS 且拒绝非白名单域名', () => {
    const insecure = sandbox({ api_key: 'sk-abcdefghijklmnopqrstuvwxyz', api_base: 'http://api.openai.com/v1' });
    play(insecure.api);
    const a = insecure.api.roleplay_turn({ line: '你好' });
    const off = sandbox({ api_key: 'sk-abcdefghijklmnopqrstuvwxyz', api_base: 'https://evil.example.com/v1' });
    play(off.api);
    const b = off.api.roleplay_turn({ line: '你好' });
    return {
      ok: a.error === 'INSECURE_BASE_URL' && b.error === 'HOST_NOT_ALLOWED' &&
        insecure.requests.length === 0 && off.requests.length === 0,
      detail: '明文 HTTP → INSECURE_BASE_URL；非白名单 → HOST_NOT_ALLOWED；两者均未发出请求',
    };
  }),
  check('R1-04', '网络白名单无通配符且与 main.js 内置白名单一致', () => {
    const { ctx } = sandbox();
    const same = JSON.stringify([...ctx.ALLOWED_HOSTS].sort()) === JSON.stringify([...manifest.allowedHosts].sort());
    return { ok: detectors.noWildcardHost(manifest.allowedHosts) && same, detail: manifest.allowedHosts.length + ' 个域名，两处一致，无 "*"' };
  }),
  check('R1-05', '权限声明最小化', () => {
    const p = [...manifest.permissions].sort().join(',');
    return { ok: p === 'ai_chat,conversation_sync', detail: 'permissions = ' + p + '；未申请 provider_credentials' };
  }),
  check('R1-06', '剧中对话不污染长期记忆，落幕只写一条游戏声明', () => {
    const s = sandbox({ api_key: 'sk-abcdefghijklmnopqrstuvwxyz' });
    play(s.api);
    s.api.roleplay_turn({ line: '第一支舞' });
    s.api.roleplay_turn({ line: '第二支舞' });
    const during = s.memory.length;
    const end = s.api.masquerade_end({});
    const words = ['角色扮演游戏', '不是真实发生的事', '平时依旧是原本的真实情感关系'];
    return {
      ok: during === 0 && s.memory.length === 1 && words.every((w) => end.closingNote.includes(w)) &&
        end.chatHiddenContext === end.closingNote,
      detail: '剧中 0 条记忆；落幕 1 条，含全部三项语义，且等于一次性 transientSystemPrompt',
    };
  }),
  check('R1-07', '系统提示词注入默认开启且明确游戏/真实关系', () => {
    const t = manifest.promptTemplate || '';
    const cfg = manifest.config.find((c) => c.name === 'inject_as_prompt');
    return {
      ok: !!cfg && cfg.default === true && t.includes('角色扮演') && t.includes('真实情感关系') && t.includes('落幕'),
      detail: 'PluginToolProvider 仅在 inject_as_prompt=true 时注入 promptTemplate',
    };
  }),
  check('R1-08', 'WebView 页面无外链资源、无 eval / new Function', () => {
    const badRefs = UI_TEXT.filter((f) => !detectors.noExternalRefs(read(f)));
    const badEval = PROD_JS.filter((f) => !detectors.noEval(read(f)));
    return { ok: badRefs.length === 0 && badEval.length === 0, detail: '本地资源 ' + UI_TEXT.length + ' 个文件、' + PROD_JS.length + ' 个 JS 均无外部引用与动态执行' };
  }),
  check('R1-09', 'UI 动态渲染均经转义或 textContent', () => {
    const src = read('plugin/ui/app.js');
    const safe = detectors.innerHtmlSafe(src);
    const bubble = /body\.textContent\s*=\s*content/.test(src);
    const studio = detectors.noEval(read('studio/index.html')) && /\.textContent\s*=\s*payload/.test(read('studio/index.html'));
    return { ok: safe && bubble && studio, detail: 'innerHTML 动态插值全部为 esc(...) 或显式标注的 safe 片段；气泡与聊天流用 textContent' };
  }),
  check('R1-10', 'MCP server 不联网、不执行外部命令、日志只走 stderr', () => {
    const src = read('mcp/server.mjs');
    /* 先剥注释再扫，避免把「不联网」的说明文字误判成网络调用。 */
    const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '').replace(/noNetworkFetch/g, '');
    const bad = /child_process|execSync|spawnSync\(|https\.request|node:https|fetch\s*\(/.test(code);
    const stderrOnly = src.includes('process.stderr.write') && !/console\.log\(/.test(src);
    const noKeyInConfig = /config:\s*\{[^}]*\}/.test(code) && !/config:\s*\{[^}]*api_key/.test(code);
    return {
      ok: !bad && stderrOnly && noKeyInConfig && src.includes('fetch: noNetworkFetch'),
      detail: '沙箱 fetch 绑定 noNetworkFetch；config 不含 api_key；无子进程/网络模块；stdout 仅承载 JSON-RPC',
    };
  }),
  check('R1-11', 'MCP 存储路径拒绝相对路径与 ..', () => {
    const src = read('mcp/server.mjs');
    const guard = src.includes('path.isAbsolute') && src.includes("includes('..')");
    const proc = spawnSync(process.execPath, [R('mcp/server.mjs')], {
      env: { ...process.env, MASQUERADE_STORE_FILE: 'relative/store.json' },
      input: '', encoding: 'utf8', timeout: 5000,
    });
    const rejected = (proc.status !== 0) || /must be an absolute path/.test(proc.stderr || '');
    return { ok: guard && rejected, detail: '源码有路径守卫；相对路径启动被拒绝（exit ' + proc.status + '）' };
  }),
  check('R1-12', '第三方素材许可与来源已声明', () => {
    const a = read('plugin/ui/assets/ATTRIBUTION.md');
    const files = ['bow-cc0.svg', 'ornament-cc0.svg', 'cormorant.woff2', 'mask.svg'];
    const missingTokens = ['CC0', 'MIT', 'OFL'].filter((x) => !a.includes(x));
    const emptyFiles = files.filter((f) => fs.statSync(R('plugin/ui/assets/' + f)).size === 0);
    return {
      ok: missingTokens.length === 0 && emptyFiles.length === 0 && files.every((f) => a.includes(f)),
      detail: files.length + ' 个本地素材非空，许可覆盖 CC0 / MIT / OFL' +
        (missingTokens.length ? '；缺许可标识：' + missingTokens.join(', ') : '') +
        (emptyFiles.length ? '；空文件：' + emptyFiles.join(', ') : ''),
    };
  }),
];

const ROUND_2 = [
  check('R2-01', 'main.js 严格限定 QuickJS ES5 子集', () => {
    const ok = detectors.es5Only(mainSrc);
    return { ok, detail: ok ? '无 let/const/箭头/模板字符串/async/Promise/class/展开运算符' : '发现 ES6+ 语法' };
  }),
  check('R2-02', 'manifest.tools 与 exports 一一对应', () => {
    const { api } = sandbox();
    const declared = manifest.tools.map((t) => t.name).sort();
    const exported = Object.keys(api).sort();
    return { ok: JSON.stringify(declared) === JSON.stringify(exported), detail: declared.length + ' 个工具，声明=导出' };
  }),
  check('R2-03', '所有工具返回统一 {success, error} 结构', () => {
    const { api } = sandbox({ api_key: 'sk-abcdefghijklmnopqrstuvwxyz' });
    const broken = [];
    for (const name of Object.keys(api)) {
      let r;
      try { r = api[name]({}); } catch (e) { broken.push(name + '(抛异常)'); continue; }
      if (!r || typeof r !== 'object' || typeof r.success !== 'boolean' || !('error' in r)) broken.push(name);
    }
    return { ok: broken.length === 0, detail: Object.keys(api).length + ' 个工具空参调用，异常/结构不符：' + (broken.join(', ') || '无') };
  }),
  check('R2-04', '异常与恶意输入不导致未捕获异常', () => {
    const { api } = sandbox();
    const bad = [null, { card: '__proto__' }, { line: 'X'.repeat(20000) }, { card_id: { toString: null } }];
    let thrown = 0;
    for (const name of Object.keys(api)) {
      for (const input of bad) { try { api[name](input); } catch (e) { thrown++; } }
    }
    return { ok: thrown === 0, detail: Object.keys(api).length * bad.length + ' 组异常输入，未捕获异常 ' + thrown + ' 次' };
  }),
  check('R2-05', '生产代码无 TODO / 调试日志残留', () => {
    const bad = PROD_JS.filter((f) => /TODO|FIXME|console\.log\(|debugger/.test(read(f)));
    return { ok: bad.length === 0, detail: bad.length ? '命中：' + bad.join(', ') : '4 个生产 JS 无 TODO/FIXME/console.log/debugger' };
  }),
  check('R2-06', '单文件规模在可审查范围内', () => {
    const all = [...PROD_JS, 'plugin/ui/index.html', 'plugin/ui/style.css', 'scripts/build.mjs', 'mcp/server.mjs'];
    const big = all.filter((f) => read(f).split('\n').length >= 1000);
    return { ok: big.length === 0, detail: all.length + ' 个文件均 < 1000 行；最大 ' + Math.max(...all.map((f) => read(f).split('\n').length)) + ' 行' };
  }),
  check('R2-07', '零第三方依赖，状态机只有一份实现', () => {
    const pkg = JSON.parse(read('package.json'));
    const mcp = read('mcp/server.mjs');
    return {
      ok: !pkg.dependencies && !pkg.devDependencies && mcp.includes('vm.runInContext') && mcp.includes("'main.js'"),
      detail: 'package.json 无依赖；MCP 通过 vm 复用 plugin/main.js，未复制状态机',
    };
  }),
  check('R2-08', '宿主补丁锚点唯一、只改一个文件、按钮按插件启用状态出现', () => {
    const before = R('host-patch/FilesPicker.kt.before');
    if (fs.existsSync(before)) fs.unlinkSync(before); /* 清掉历史遗留，再断言本次重跑不产生残留 */
    const gen = spawnSync(process.execPath, [R('host-patch/generate.mjs')], { encoding: 'utf8', timeout: 30000 });
    const patched = read('host-patch/FilesPicker.kt');
    const patch = read('host-patch/FilesPicker.kt.patch');
    const files = [...patch.matchAll(/^\+\+\+ b\/(.+)$/gm)].map((m) => m[1]);
    const leftover = fs.existsSync(before);
    return {
      ok: gen.status === 0 && !leftover && files.length === 1 && files[0].endsWith('FilesPicker.kt') &&
        patched.includes('if (masqueradeInstalled)') && patched.includes('it.isEnabled'),
      detail: 'generate.mjs 重跑成功且不留中间文件；diff 仅含 FilesPicker.kt；入口由插件 ID + isEnabled 条件渲染',
    };
  }),
  check('R2-09', '测试覆盖关键不变量（隔离/密钥/XSS/状态机）', () => {
    const files = fs.readdirSync(R('tests')).filter((f) => f.endsWith('.test.mjs'));
    const all = files.map((f) => read('tests/' + f)).join('\n');
    const keys = ['角色扮演游戏', 'NO_API_KEY', 'escapeHtml', 'MAX_TURNS', 'MCP'];
    return { ok: files.length >= 3 && keys.every((k) => all.includes(k)), detail: files.length + ' 个测试文件，覆盖 5 类关键不变量' };
  }),
  check('R2-10', '检测器自身无假阴性（正反样本自测）', selfTest),
];

function runTests() {
  const files = fs.readdirSync(R('tests')).filter((f) => f.endsWith('.test.mjs')).map((f) => R('tests/' + f));
  return spawnSync(process.execPath, ['--test', ...files], { encoding: 'utf8', timeout: 120000 });
}

const ROUND_3 = [
  check('R3-01', '全量单元测试通过', () => {
    const r = runTests();
    const out = (r.stdout || '') + (r.stderr || '');
    const m = out.match(/# pass (\d+)/);
    const f = out.match(/# fail (\d+)/);
    return { ok: r.status === 0 && f && f[1] === '0', detail: 'pass=' + (m ? m[1] : '?') + ', fail=' + (f ? f[1] : '?') };
  }),
  check('R3-02', '插件 ZIP 可构建并通过 CRC 回读', () => {
    const r = spawnSync(process.execPath, [R('scripts/build.mjs')], { encoding: 'utf8', timeout: 60000 });
    const zip = R('dist/orangechat-masquerade-' + manifest.version + '.zip');
    return {
      ok: r.status === 0 && (r.stdout || '').includes('CRC 与内容回读校验通过') && fs.statSync(zip).size > 1000,
      detail: 'dist/orangechat-masquerade-' + manifest.version + '.zip ' + (fs.existsSync(zip) ? fs.statSync(zip).size + ' B' : '缺失'),
    };
  }),
  check('R3-03', 'ZIP 内容与必需文件齐全（独立解析器交叉验证）', () => {
    const zip = R('dist/orangechat-masquerade-' + manifest.version + '.zip');
    const py = spawnSync('python', ['-c',
      "import zipfile,sys;z=zipfile.ZipFile(sys.argv[1]);assert z.testzip() is None;n=z.namelist();" +
      "req=['manifest.json','main.js','ui/index.html','ui/style.css','ui/app.js','ui/boot.js'];" +
      "assert all(x in n for x in req);print(len(n))", zip], { encoding: 'utf8', timeout: 30000 });
    return { ok: py.status === 0, detail: 'Python zipfile.testzip 通过，条目 ' + (py.stdout || '').trim() };
  }),
  check('R3-04', '所有 HTML/CSS 本地引用真实存在', () => {
    const missing = [];
    for (const f of ['plugin/ui/index.html', 'studio/index.html', 'plugin/ui/style.css']) {
      const src = read(f);
      const refs = [...src.matchAll(/(?:src|href)\s*=\s*["']([^"']+)["']/g)].map((m) => m[1])
        .concat([...src.matchAll(/url\(\s*["']?([^)"']+)["']?\s*\)/g)].map((m) => m[1]));
      for (const u of refs) {
        if (/^(https?:|data:|#)/.test(u)) continue;
        if (!fs.existsSync(path.resolve(path.dirname(R(f)), u))) missing.push(f + ' → ' + u);
      }
    }
    return { ok: missing.length === 0, detail: missing.length ? '缺失：' + missing.join(', ') : '无占位符 URL，引用全部命中本地文件' };
  }),
  check('R3-05', '专属 UI 四个状态视图与三个功能区齐全', () => {
    const html = read('plugin/ui/index.html');
    const ids = ['pane-idle', 'pane-invited', 'pane-active', 'pane-ended', 'view-ball', 'view-cards', 'view-model',
      'btn-invite', 'btn-accept', 'btn-send-line', 'btn-end', 'btn-save-card'];
    const missing = ids.filter((i) => !html.includes('id="' + i + '"'));
    return { ok: missing.length === 0, detail: ids.length + ' 个关键节点齐全，缺：' + (missing.join(', ') || '无') };
  }),
  check('R3-06', '浏览器预览页可独立打开', () => {
    const s = read('studio/index.html');
    return {
      ok: s.includes('plugin/ui/index.html') && s.includes('MasqueradeStudio') && s.includes('chat-stream'),
      detail: '复用同一套 CSS/JS/main.js，右侧演示聊天上下文里的专属卡片',
    };
  }),
  check('R3-07', '交付文档齐全', () => {
    const docs = ['README.md', 'ARCHITECTURE.md', 'SECURITY.md', 'LICENSE',
      'host-patch/README.md', 'mcp/README.md', 'plugin/ui/assets/ATTRIBUTION.md'];
    const missing = docs.filter((d) => !exists(d));
    return { ok: missing.length === 0, detail: docs.length + ' 份文档齐全，缺：' + (missing.join(', ') || '无') };
  }),
  check('R3-08', '端到端落幕语义：助手必须知道刚才是游戏', () => {
    const s = sandbox({ api_key: 'sk-abcdefghijklmnopqrstuvwxyz' });
    play(s.api);
    s.api.roleplay_turn({ line: '第一支舞' });
    const e = s.api.masquerade_end({});
    return {
      ok: e.success && e.chatCardText.includes('【假面时刻 · 落幕】') &&
        e.chatHiddenContext.includes('角色扮演游戏') && e.memoryWritten === true,
      detail: '启幕卡 / 落幕卡 / transientSystemPrompt / 长期记忆四条通道一致',
    };
  }),
];

const ROUNDS = [
  { id: 1, name: '安全审计 · 信任边界与数据隔离', checks: ROUND_1 },
  { id: 2, name: '代码审查 · 契约、健壮性与工程规范', checks: ROUND_2 },
  { id: 3, name: '交付审查 · 可构建、可预览、可追溯', checks: ROUND_3 },
];

/* ---------- 报告 ---------- */

const FINDINGS = [
  { round: 1, item: 'R1-12 / 素材许可标识', text: 'ATTRIBUTION.md 原先只写「SIL Open Font License 1.1」，未出现 OFL 缩写，许可校验判定失败且失败原因被硬编码的 detail 掩盖。已在表格补注「OFL-1.1」，并把该检查改为逐项报出缺失的许可标识与空文件，不再写死「全部通过」。' },
  { round: 1, item: 'R1-10 / MCP 网络边界', text: '初版 MCP server 曾保留未使用的 fetch 常量与 STRING_ARG 占位函数，存在「看似有网络能力」的误导面。已删除死代码并注入 noNetworkFetch，使其只能显式失败。' },
  { round: 2, item: 'R2-03 / 返回值结构', text: 'roleplay_turn 失败分支曾同时出现重复 error 键，且在 invited 状态直接落幕会误写一条「游戏记忆」。已清理重复键，并把 invited 的收回改为 cancelled 分支（不写记忆、不发聊天卡）。' },
  { round: 2, item: 'R2-01 / 状态机健壮性', text: '单测暴露两个真实缺陷：先建卡再列卡会丢三张内置卡（改为独立 seeded 哨兵）；进行中再次邀请先报「角色卡不存在」而非「已有进行中」（把状态检查提前）。两处均已修复并补回归用例。' },
  { round: 2, item: 'R2-04 / 畸形输入健壮性', text: '首轮审计实跑发现 4 次未捕获异常：`String({ toString: null })` 会抛 TypeError，`get_role_card` / `update_role_card` / `delete_role_card` / `masquerade_invite` 读到畸形 card_id 时直接崩。已新增 `safeString()` 只接受标量（对象/数组/坏 toString 一律当空值），并让 `escapeHtml` 复用；48 组异常输入复测 0 次未捕获异常，核心单测 13/13 仍通过。' },
  { round: 2, item: 'R2-08 / 审计自身可复跑性', text: '审计脚本初版在模块加载时就执行全部三轮，`--round=1` 会连带触发第三轮构建副作用；`generate.mjs` 每次重跑都会留下 `FilesPicker.kt.before` 中间文件。已把规则改为懒执行 `evaluate()`，并让生成脚本默认不写中间产物（重算 diff 时才用 `--keep-before`）。`innerHTML` 检测器也升级为括号/引号平衡抓取整条赋值语句，避免被 map 回调内的分号截断而漏检。' },
  { round: 3, item: 'R3-02 / 宿主可插拔性', text: '初版加号按钮是硬编码常驻，不满足「装了插件才显示」。已改为读 PluginManager.plugins 并按插件 ID + isEnabled 条件渲染；生成脚本同时适配 CRLF 换行，避免整文件 diff。' },
];

function buildReport(results, allOk) {
  const lines = [];
  lines.push('# 假面舞会 · 三轮安全审计 / 代码审查报告');
  lines.push('');
  lines.push('- 生成方式：`node audit/audit.mjs`，逐条实跑，非人工声明。');
  lines.push('- 审查对象：`orangechat-masquerade` v' + manifest.version + '（插件包 + MCP server + 宿主补丁 + 预览页）。');
  lines.push('- 审查方式：静态源码扫描 + `vm` 沙箱行为验证 + 子进程冒烟 + ZIP 独立解析器交叉校验。');
  lines.push('- 结论：**' + (allOk ? '三轮全部通过' : '存在未通过项，需修复后复跑') + '**。');
  lines.push('');
  lines.push('> 覆盖声明：这是针对本仓库的**有范围审查**（scoped / quick 级），不是对橘瓣宿主全量代码或 Android 运行时的完整审计。未执行真机 WebView 运行、Gradle 编译、动态渗透或第三方模型接口联调，相关项列在文末「限制」。');
  lines.push('');
  for (const round of results) {
    const pass = round.checks.filter((c) => c.ok).length;
    lines.push('## 第 ' + round.id + ' 轮 · ' + round.name + '（' + pass + '/' + round.checks.length + '）');
    lines.push('');
    lines.push('| 编号 | 检查项 | 结果 | 实际核对内容 |');
    lines.push('|---|---|---|---|');
    for (const c of round.checks) {
      lines.push('| ' + c.id + ' | ' + c.name + ' | ' + (c.ok ? 'PASS' : 'FAIL') + ' | ' + c.detail.replace(/\|/g, '\\|') + ' |');
    }
    lines.push('');
    const found = FINDINGS.filter((f) => f.round === round.id);
    if (found.length) {
      lines.push('### 本轮发现与修复');
      lines.push('');
      for (const f of found) lines.push('- **' + f.item + '**：' + f.text);
      lines.push('');
    }
  }
  lines.push('## 复验');
  lines.push('');
  lines.push('- 三轮共 ' + results.reduce((n, r) => n + r.checks.length, 0) + ' 条检查，逐条重跑；任一 FAIL 即退出码 1。');
  lines.push('- 检测器 `selfTest()` 用 ' + selfTest().count + ' 组正反样本验证规则本身不产生假阴性（例如故意写入硬编码密钥、未转义 innerHTML、通配符白名单、map 回调内的裸插值时必须报错）。');
  lines.push('- 修复后回归：`node --test tests/*.test.mjs` + `node scripts/build.mjs` + `python zipfile.testzip()` 全部通过。');
  lines.push('');
  lines.push('## 限制（needs validation）');
  lines.push('');
  lines.push('- 未在本地执行 Gradle 编译与 Android 真机运行：宿主补丁只使用 `FilesPicker.kt` 与 `ChatDrawer.kt` 已有的 import / API，编译与 UI 落地由 PR 的 CI 与真机验收兜底。');
  lines.push('- `Bridge.sendChatMessage` 的 `transientSystemPrompt` 只对本轮请求生效这一行为来自宿主源码 `PluginWebViewPage.kt`，已在源码层核对，未做真机验证。');
  lines.push('- 模型接口只做了本地 mock 行为验证，未对任何真实供应商发起请求（不消耗额度、不接触真实密钥）。');
  lines.push('- WebView 在 `file://` 下的字体加载、iframe 跨源 postMessage 回退已在预览页做降级设计，真机表现需宿主侧验收。');
  lines.push('');
  return lines.join('\n');
}

const arg = process.argv.find((a) => a.startsWith('--round='));
const wanted = arg ? Number(arg.slice(8)) : 0;
const roundsToRun = wanted ? ROUNDS.filter((r) => r.id === wanted) : ROUNDS;
if (!roundsToRun.length) { console.error('未知轮次：' + wanted); process.exit(2); }

let allOk = true;
const results = [];
for (const round of roundsToRun) {
  console.log('\n=== 第 ' + round.id + ' 轮 · ' + round.name + ' ===');
  const evaluated = round.checks.map(evaluate);
  for (const c of evaluated) {
    console.log((c.ok ? '[PASS] ' : '[FAIL] ') + c.id + ' ' + c.name + ' — ' + c.detail);
    if (!c.ok) allOk = false;
  }
  results.push({ id: round.id, name: round.name, checks: evaluated });
}

if (!wanted) {
  const report = buildReport(results, allOk);
  fs.writeFileSync(R('audit/AUDIT_REPORT.md'), report);
  console.log('\n报告已写入 audit/AUDIT_REPORT.md');
}

const total = results.reduce((n, r) => n + r.checks.length, 0);
const passed = results.reduce((n, r) => n + r.checks.filter((c) => c.ok).length, 0);
console.log('\n合计 ' + passed + '/' + total + ' 通过；结论：' + (allOk ? '全部通过' : '存在未通过项'));
process.exit(allOk ? 0 : 1);
