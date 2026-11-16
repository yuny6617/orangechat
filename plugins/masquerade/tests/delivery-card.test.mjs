/*
 * v1.3.0 端到端链路回归：请柬从页面到助手聊天的真实送达 + 邀请函卡片 / 同意拒绝。
 *
 * 这里不是静态文本断言：用轻量 DOM stub 与模拟宿主 Bridge（Promise 回调契约，
 * 与 PluginWebViewPage.kt 的 bridgeJavascript 一致）真正执行 plugin/ui/app.js，
 * 逐条核对 sendChatMessage 的调用与状态机回执。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const manifest = JSON.parse(read('plugin/manifest.json'));

function makeCore(config = {}, fetchImpl) {
  const map = new Map();
  const mem = [];
  const exportsObj = {};
  const ctx = {
    exports: exportsObj,
    dataStore: {
      get: (k) => (map.has(k) ? map.get(k) : null),
      set: (k, v) => map.set(k, String(v)),
      del: (k) => map.delete(k),
      list: (p) => Array.from(map.keys()).filter((k) => k.indexOf(p) === 0),
    },
    config,
    memoryBank: { save: (t) => mem.push(String(t)), recall: () => [], search: () => [], delete: () => {} },
    fetch: fetchImpl || (() => ({ ok: false, status: 503, body: '{}', json: () => ({}), text: () => '{}' })),
    console: { warn() {}, log() {}, info() {}, error() {} },
  };
  vm.createContext(ctx);
  vm.runInContext(read('plugin/main.js'), ctx, { filename: 'main.js' });
  return { api: exportsObj, map, mem };
}

const KEY = 'sk-DELIVERY-CARD-TEST-SECRET-1234567890';
const MOCK_MODEL = () => ({
  ok: true, status: 200,
  body: JSON.stringify({ choices: [{ message: { content: '剧情概要：雨夜共舞，天亮归还面具。' } }] }),
  json() { return JSON.parse(this.body); }, text() { return this.body; },
});
/* 带模型的 core：让 roleplay_turn 真的写入一句台词，隔离断言才有意义。 */
function makeCoreWithModel() {
  return makeCore({ api_key: KEY, api_base: 'https://api.openai.com/v1', model_id: 'gpt-4o-mini' }, MOCK_MODEL);
}

function makeNode(id) {
  return {
    id, value: '', textContent: '', innerHTML: '', hidden: false, className: '',
    dataset: {}, files: [], style: {}, scrollTop: 0, scrollHeight: 0, handlers: {},
    classList: { add() {}, remove() {}, toggle() {} },
    appendChild() {}, setAttribute() {}, removeAttribute() {}, focus() {}, click() {},
    querySelector() { return null; }, querySelectorAll() { return []; },
    addEventListener(type, fn) { this.handlers[type] = fn; },
    getContext() { return null; }, toDataURL() { return null; },
  };
}

const CONVERSATIONS = [
  { id: 'conv-current', title: '今夜', messageCount: 0, updatedAt: '2026-11-16T10:00:00Z' },
  { id: 'conv-older', title: '旧聊天', messageCount: 6, updatedAt: '2026-11-15T10:00:00Z' },
];

function makePage(core, opts = {}) {
  const nodes = new Map();
  const get = (sel) => {
    if (!nodes.has(sel)) nodes.set(sel, makeNode(sel));
    return nodes.get(sel);
  };
  /* 还原 index.html 里带 hidden 属性的初始节点，避免 stub 一律 false 造成假阳性。 */
  ['#btn-retry-delivery', '#btn-retry-invited', '#delivery-error', '#copy-hint',
    '#pane-invited', '#pane-active', '#pane-ended', '#archive-detail', '#card-editor',
    '#import-file', '#model-alert', '#model-status', '#archive-empty'].forEach((s) => {
    get(s).hidden = true;
  });
  const sent = [];
  const calls = [];
  let n = 0;
  const Bridge = {
    callTool(name, params) {
      const p = JSON.parse(params);
      calls.push({ name, params: p });
      const fn = core.api[name];
      return Promise.resolve(JSON.stringify(fn ? fn(p) : { success: false, error: 'NO_TOOL' }));
    },
    listConversations() {
      return Promise.resolve(JSON.stringify(opts.listResult !== undefined
        ? opts.listResult
        : { success: true, conversations: opts.conversations || CONVERSATIONS }));
    },
    sendChatMessage(id, text, hidden, image) {
      n++;
      const call = { id, text, hidden, image: image || null };
      sent.push(call);
      const r = opts.sendResult ? opts.sendResult(call, n) : { success: true };
      return Promise.resolve(JSON.stringify(r));
    },
  };
  const document = {
    body: { classList: { add() {}, remove() {}, toggle() {} } },
    querySelector: get,
    querySelectorAll() { return []; },
    createElement(tag) {
      if (tag === 'canvas') {
        return {
          width: 0, height: 0,
          getContext() { return opts.canvasContext || null; },
          toDataURL() { return opts.imageData || null; },
        };
      }
      return makeNode(tag);
    },
    addEventListener() {},
  };
  const window = {
    Bridge, document, localStorage: { getItem() { return null; }, setItem() {} },
    setTimeout, clearTimeout, Promise, JSON, Math, Date,
  };
  window.window = window;
  window.parent = window;
  const ctx = {
    window, document, Bridge, Promise, JSON, Math, Date, String, Number,
    Array, Object, RegExp, isFinite, setTimeout, clearTimeout,
    console: { warn() {}, log() {}, error() {} },
  };
  ctx.window = window;
  vm.createContext(ctx);
  vm.runInContext(read('plugin/ui/app.js'), ctx, { filename: 'app.js' });
  return {
    get, sent, calls, core,
    fire(sel, type = 'click') {
      const h = get(sel).handlers[type];
      if (!h) throw new Error('缺少事件处理器：' + sel);
      return h();
    },
    cardId() { return core.api.list_role_cards({}).cards[0].id; },
  };
}

async function settle() {
  for (let i = 0; i < 40; i++) await Promise.resolve();
  await new Promise((r) => setTimeout(r, 0));
}

async function invite(page, extra) {
  const g = page.get;
  g('#invite-card').value = page.cardId();
  g('#invite-scene').value = '雨夜的玻璃花房';
  g('#invite-mask').value = '一位不愿透露姓名的来客';
  await page.fire('#btn-invite');
  await settle();
  return extra ? extra(page) : page;
}

/* ---------- 1. 请柬真实送达 ---------- */

test('端到端：请柬真的经 sendChatMessage 写进当前聊天，状态与回执一致', async () => {
  const core = makeCore();
  const page = makePage(core);
  await invite(page);
  assert.equal(page.sent.length, 1, '必须真的调用一次 sendChatMessage');
  assert.equal(page.sent[0].id, 'conv-current');
  assert.ok(page.sent[0].text.includes('【假面时刻 · 请柬】'));
  assert.ok(page.sent[0].hidden.includes('角色扮演游戏'));
  assert.equal(page.get('#pane-invited').hidden, false, '送达成功后才切到已邀界面');
  assert.ok(page.get('#invited-heading').textContent.includes('已送到聊天里'));
  assert.ok(page.get('#invite-delivery').textContent.includes('✓'));
  assert.ok(page.get('#invite-delivery').className.includes('ok'));
  const st = core.api.masquerade_status({});
  assert.equal(st.status, 'invited');
  assert.equal(st.delivery.status, 'delivered');
  assert.equal(st.delivery.conversationId, 'conv-current');
  assert.ok(page.calls.some((c) => c.name === 'masquerade_invite_delivery' && c.params.delivered === true));
});

test('请柬发到列表第一条（当前空会话），不再误投到别的有消息会话', async () => {
  const page = makePage(makeCore());
  await invite(page);
  assert.equal(page.sent[0].id, 'conv-current', '旧实现会发到 conv-older，用户因此看不到请柬');
});

test('邀请函卡片纯展示，同意/婉拒直接平铺，且请柬文本可复制贴进聊天', async () => {
  const page = makePage(makeCore());
  await invite(page);
  assert.ok(page.get('#invite-card-partner').textContent.length > 0);
  assert.ok(page.get('#invite-card-scene').textContent.includes('雨夜'));
  assert.ok(page.get('#invite-card-copy').textContent.includes('愿意陪我跳这一支吗'));
  assert.ok(page.get('#accept-line').textContent.includes('赴约'));
  assert.ok(page.get('#decline-line').textContent.includes('丝绒盒'));
  assert.ok(page.get('#invite-copy-text').value.includes('【假面时刻 · 请柬】'),
    '送不进聊天时用户可自行复制请柬贴进对话');
  const html = read('plugin/ui/index.html');
  assert.equal(html.includes('id="invite-modal"'), false, '1.4.0 起不再有全屏弹窗');
  assert.equal(html.includes('id="btn-modal-accept"'), false);
});

/* ---------- 2. 送达失败不假成功 ---------- */

test('权限被拒时如实标注「未送达」，但不阻断在插件/聊天里继续玩', async () => {
  const core = makeCore();
  const page = makePage(core, {
    listResult: { success: false, error: 'Permission denied: conversation_sync permission not declared' },
  });
  await invite(page);
  assert.equal(page.sent.length, 0, '拿不到会话就不该发送');
  assert.ok(page.get('#invite-delivery').textContent.includes('未送达'), '绝不伪装成已送达');
  assert.ok(page.get('#invite-delivery').textContent.includes('Permission denied'));
  assert.ok(page.get('#invited-heading').textContent.includes('未送达'));
  assert.equal(page.get('#btn-retry-delivery').hidden, false, '要给出重试入口');
  /* 1.4.0：邀请在插件内已成立，同步失败不回滚 idle，用户仍能同意/婉拒或复制请柬。 */
  assert.equal(page.get('#pane-invited').hidden, false);
  assert.equal(core.api.masquerade_status({}).status, 'invited');
  assert.equal(core.api.masquerade_status({}).delivery.status, 'failed');
  assert.equal(core.mem.length, 0);
});

test('sendChatMessage 返回失败：自动降级重试（去掉 hiddenContext / 图片），仍失败只标注不回滚', async () => {
  const core = makeCore();
  const page = makePage(core, { sendResult: () => ({ success: false, error: 'Conversation no longer accepts messages' }) });
  await invite(page);
  assert.equal(page.sent.length, 2, '首次 + 一次降级重试');
  assert.ok(page.sent[0].hidden.length > 0);
  assert.equal(page.sent[1].hidden, '', '降级重试必须去掉长 hiddenContext');
  assert.equal(page.sent[1].image, null);
  assert.ok(page.sent[1].text.length < page.sent[0].text.length);
  assert.equal(page.get('#pane-invited').hidden, false, '同步失败不阻断玩法');
  assert.equal(core.api.masquerade_status({}).status, 'invited');
  assert.equal(core.api.masquerade_status({}).delivery.status, 'failed');
});

test('首次失败、降级重试成功：按真实回执标记送达（不误报也不漏报）', async () => {
  const core = makeCore();
  const page = makePage(core, {
    sendResult: (call, n) => n === 1 ? { success: false, error: 'transient' } : { success: true },
  });
  await invite(page);
  assert.equal(page.sent.length, 2);
  assert.equal(page.get('#pane-invited').hidden, false);
  assert.equal(core.api.masquerade_status({}).delivery.status, 'delivered');
  assert.equal(core.api.masquerade_status({}).delivery.attempts, 2);
  assert.ok(page.get('#invite-delivery').textContent.includes('降级重试后送达'));
});

test('会话列表为空时不静默当成功，明确给出 NO_CONVERSATION', async () => {
  const page = makePage(makeCore(), { conversations: [] });
  await invite(page);
  assert.equal(page.sent.length, 0);
  assert.ok(page.get('#invite-delivery').textContent.includes('NO_CONVERSATION'));
});

/* ---------- 3. 同意 / 婉拒 ---------- */

test('同意赴约：应邀确认作为可见消息进聊天，助手确实收到，状态推进 active', async () => {
  const core = makeCore();
  const page = makePage(core);
  await invite(page);
  await page.fire('#btn-accept');
  await settle();
  const confirm = page.sent[page.sent.length - 1];
  assert.ok(confirm.text.includes('【假面时刻 · 应邀】'), '确认卡必须送进聊天');
  assert.ok(confirm.text.includes('丝带已系好'), '要有插件文风的应邀提示语');
  assert.ok(confirm.text.includes('【假面时刻 · 启幕】'));
  assert.ok(confirm.hidden.includes('用户已在邀请函上点了「同意赴约」'));
  assert.equal(core.api.masquerade_status({}).status, 'active');
  assert.equal(page.get('#pane-active').hidden, false);
});

test('婉拒：婉拒卡进聊天、状态回 idle、不写记忆、不产生剧情', async () => {
  const core = makeCore();
  const page = makePage(core);
  await invite(page);
  await page.fire('#btn-decline');
  await settle();
  const decline = page.sent[page.sent.length - 1];
  assert.ok(decline.text.includes('【假面时刻 · 婉拒】'));
  assert.ok(decline.text.includes('把面具收回丝绒盒'));
  assert.ok(decline.hidden.includes('这一场没有开始'));
  assert.equal(core.api.masquerade_status({}).status, 'idle');
  assert.equal(core.mem.length, 0, '婉拒不写长期记忆');
  assert.equal(page.get('#pane-idle').hidden, false);
});

test('同意与拒绝各有独立文风提示语，且分别来自状态机', () => {
  const core = makeCore();
  const inv = core.api.masquerade_invite({ scene: '雪夜', source: 'page' });
  assert.equal(inv.inviteCard.acceptLine, '丝带已系好，面具已戴上。今夜第三支曲子，我赴约。');
  assert.equal(inv.inviteCard.declineLine, '也好，把面具收回丝绒盒吧。等你想跳的那一夜，灯会再亮起。');
  const acc = core.api.masquerade_accept({});
  assert.equal(acc.consentLine, inv.inviteCard.acceptLine);
  assert.ok(acc.chatConfirmCard.includes('应邀'));

  /* 婉拒发生在 invited 状态：单独一场，不要复用已经 active 的那场。 */
  const declining = makeCore();
  declining.api.masquerade_invite({ scene: '雪夜' });
  const dec = declining.api.masquerade_end({ reason: '婉拒' });
  assert.equal(dec.cancelled, true);
  assert.equal(dec.consentLine, inv.inviteCard.declineLine);
  assert.ok(dec.chatDeclineCard.includes('婉拒'));
  assert.equal(declining.mem.length, 0);
});

/* ---------- 4. 落幕：概要进上下文，具体内容不进 ---------- */

test('落幕：压缩概要进聊天，剧中具体内容绝不进上下文，概要卡片只放概要', async () => {
  const core = makeCoreWithModel();
  const page = makePage(core);
  await invite(page);
  await page.fire('#btn-accept');
  await settle();
  page.get('#line-input').value = '假如我此刻说喜欢你';
  await page.fire('#btn-send-line');
  await settle();
  await page.fire('#btn-end');
  await settle();
  const end = page.sent[page.sent.length - 1];
  assert.equal(end.id, 'conv-older', '落幕概要发到最新有对话的会话');
  assert.ok(end.text.includes('【假面时刻 · 落幕】'));
  assert.ok(end.text.includes('本场剧情概要'));
  assert.ok(end.text.includes('不是真实发生的事'));
  assert.equal(end.text.includes('假如我此刻说喜欢你'), false, '逐字台词绝不进上下文');
  assert.ok(page.get('#summary-card-text').textContent.length > 0, '概要卡片要显示压缩概要');
  assert.equal(page.get('#summary-card-text').textContent.includes('假如我此刻说喜欢你'), false,
    '概要卡片也不能带具体台词');
  assert.equal(page.get('#pane-ended').hidden, false);
  assert.equal(core.api.masquerade_status({}).status, 'ended');
  assert.equal(core.mem.length, 1, '整场只写一条游戏记忆');
});

test('summaryCard 数据结构不含 transcript 字段，只有档案 ID 供插件内查看', () => {
  const core = makeCoreWithModel();
  core.api.masquerade_invite({ scene: '雪夜' });
  core.api.masquerade_accept({});
  core.api.roleplay_turn({ line: '秘密台词AAA' });
  const end = core.api.masquerade_end({});
  assert.ok(end.summaryCard);
  assert.equal(end.summaryCard.kind, 'masquerade_summary_card');
  assert.equal(end.summaryCard.transcript, undefined);
  assert.equal(JSON.stringify(end.summaryCard).includes('秘密台词AAA'), false);
  assert.ok(end.summaryCard.archiveId, '完整内容只能凭档案 ID 在插件内查看');
  assert.equal(end.summaryCard.isGame, true);
  const archive = core.api.get_masquerade_archive({ session_id: end.archiveId });
  assert.ok(archive.archive.transcript.some((m) => m.text === '秘密台词AAA'), '完整台词只在档案里');
});

/* ---------- 5. 聊天里的卡片图 + 契约 ---------- */

test('聊天里附邀请函卡片图，Canvas 不可用时自动降级为纯文本', async () => {
  const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==';
  const fakeCtx = {
    createLinearGradient() { return { addColorStop() {} }; },
    fillRect() {}, strokeRect() {}, beginPath() {}, moveTo() {}, lineTo() {}, stroke() {},
    fillText() {}, arc() {}, fill() {}, measureText() { return { width: 10 }; },
  };
  const withImage = makePage(makeCore(), { canvasContext: fakeCtx, imageData: png });
  await invite(withImage);
  assert.equal(withImage.sent[0].image, png, '邀请函图片要随请柬一起送进聊天');

  const noCanvas = makePage(makeCore());
  await invite(noCanvas);
  assert.equal(noCanvas.sent[0].image, null, 'Canvas 不可用就降级纯文本，不影响送达');
  assert.ok(noCanvas.sent[0].text.includes('【假面时刻 · 请柬】'));
});

test('HTML / manifest 契约：无弹窗、双按钮平铺、概要卡片与工具输出契约齐全', () => {
  const html = read('plugin/ui/index.html');
  for (const id of ['btn-accept', 'btn-decline', 'btn-copy-invite', 'invite-copy-text',
    'invite-card-preview', 'invite-delivery', 'delivery-error', 'btn-retry-delivery', 'btn-retry-invited',
    'summary-card', 'summary-card-text', 'btn-summary-archive', 'accept-line', 'decline-line']) {
    assert.ok(html.includes(`id="${id}"`), `缺少节点 ${id}`);
  }
  for (const gone of ['invite-modal', 'btn-modal-accept', 'btn-modal-decline', 'modal-sheet']) {
    assert.equal(html.includes(`id="${gone}"`), false, `不应再有弹窗节点 ${gone}`);
  }
  assert.ok(html.includes('同意赴约') && html.includes('婉拒'));
  const names = manifest.tools.map((t) => t.name);
  assert.ok(names.includes('masquerade_invite_delivery'));
  assert.ok(names.includes('masquerade_invite_cancel'));
  const core = makeCore();
  assert.deepEqual(Object.keys(core.api).sort(), names.slice().sort(), 'manifest.tools 与 exports 一一对应');
  /* 1.4.0 核心：工具描述必须写清触发条件与原样输出契约，不依赖 promptTemplate 注入。 */
  for (const name of ['masquerade_invite', 'masquerade_accept', 'masquerade_end']) {
    const def = manifest.tools.find((t) => t.name === name);
    assert.ok(def.description.includes('必须调用的场景'), `${name} 缺触发条件`);
    assert.ok(def.description.includes('replyToUser'), `${name} 缺原样输出契约`);
    assert.ok(def.description.includes('一字不改'), `${name} 缺原样输出的强约束`);
  }
  assert.ok(manifest.promptTemplate.includes('plg_'), '要说明宿主工具名前缀');
});
