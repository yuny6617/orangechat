/*
 * 专项回归：v1.2.0 的四条真机反馈
 *  1. 配置回归宿主单一来源（撤销页面覆盖层与内置预设）
 *  2. 前端助手可被邀请 + 落幕压缩概要进入助手最新有对话的上下文
 *  3. 演绎档案：落幕后能回看完整台词
 *  4. 无模型时硬拦截，不再给出牛头不对马嘴的假回复
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

function sandbox(config = {}, fetchImpl) {
  const map = new Map();
  const mem = [];
  const exportsObj = {};
  const requests = [];
  const ctx = {
    exports: exportsObj,
    dataStore: {
      get: (k) => (map.has(k) ? map.get(k) : null),
      set: (k, v) => map.set(k, String(v)),
      del: (k) => map.delete(k),
      list: (p) => Array.from(map.keys()).filter((k) => k.indexOf(p) === 0),
    },
    config,
    memoryBank: {
      save: (t) => mem.push(String(t)),
      recall: () => [], search: () => [], delete: () => {},
    },
    fetch: fetchImpl || ((url, opts) => {
      requests.push({ url, opts });
      return { ok: true, status: 200, body: '{}', json: () => ({}), text: () => '{}' };
    }),
    console: { warn() {}, log() {}, info() {}, error() {} },
  };
  vm.createContext(ctx);
  vm.runInContext(read('plugin/main.js'), ctx, { filename: 'main.js' });
  return { api: exportsObj, map, mem, requests };
}

const KEY = 'sk-CHAT-INVITE-TEST-SECRET-9876543210';

function play(fetchImpl) {
  const s = sandbox({ api_key: KEY }, fetchImpl);
  s.api.masquerade_invite({ source: 'chat' });
  s.api.masquerade_accept({});
  return s;
}

/* ---------- 1. 配置单一来源 ---------- */

test('配置只来自宿主：页面不再有第二套配置入口', () => {
  const src = read('plugin/main.js');
  const ui = read('plugin/ui/app.js');
  const html = read('plugin/ui/index.html');
  const boot = read('plugin/ui/boot.js');
  assert.equal(/\bset_model_config\b/.test(src), false, '页面写配置的工具必须移除');
  assert.equal(/\blist_model_presets\b/.test(src), false, '内置供应商预设必须移除');
  assert.equal(/\beffectiveConfig\b/.test(src), false, '配置覆盖层必须移除');
  assert.equal(/\bset_model_config\b/.test(ui), false);
  for (const id of ['cfg-preset', 'cfg-base', 'cfg-key', 'btn-save-config', 'btn-clear-key']) {
    assert.equal(html.includes(`id="${id}"`), false, `不应再有表单节点 ${id}`);
  }
  assert.equal(/api_key\s*:/.test(boot), false, '预览垫片不得注入伪 API Key');
  assert.ok(boot.includes('demo: true'), '预览模式必须显式标记 demo');
  assert.ok(html.includes('设置 → 插件管理 → 假面舞会'), '要给出宿主配置入口指引');
});

test('get_model_config 只读脱敏，三态准确且给出宿主配置指引', () => {
  assert.equal(sandbox({}).api.get_model_config({}).modelStatus, 'none');
  const none = sandbox({}).api.get_model_config({});
  assert.equal(none.configured, false);
  assert.equal(none.configSource, 'host');
  assert.ok(none.setupHint.includes('插件管理'));
  assert.equal(JSON.stringify(none).includes(KEY), false);

  const ready = sandbox({ api_key: KEY }).api.get_model_config({});
  assert.equal(ready.modelStatus, 'configured');
  assert.equal(ready.setupHint, null);
  assert.equal(JSON.stringify(ready).includes(KEY), false, '密钥绝不回显');

  /* demo 必须同时满足 demo:true、无 api_key、有 window；Node 沙箱里没有 window */
  const noWindow = sandbox({ demo: true }).api.get_model_config({});
  assert.equal(noWindow.modelStatus, 'none', '宿主 QuickJS 沙箱里伪造 demo 不生效');
});

test('manifest.config 不再声明内置供应商下拉，且工具集与 exports 一致', () => {
  assert.equal(manifest.config.some((c) => c.name === 'provider_preset'), false);
  const names = manifest.tools.map((t) => t.name).sort();
  const s = sandbox({});
  assert.deepEqual(Object.keys(s.api).sort(), names);
  for (const n of ['list_masquerade_archives', 'get_masquerade_archive', 'delete_masquerade_archive']) {
    assert.ok(names.includes(n), `缺少演绎档案工具 ${n}`);
  }
});

/* ---------- 2. 前端助手被邀请 + 压缩概要进入上下文 ---------- */

test('主聊天邀请：不传 card_id 也能用内置示例卡，并把请柬送进对话框', () => {
  const s = sandbox({ api_key: KEY });
  const r = s.api.masquerade_invite({ source: 'chat', scene: '雨夜的旧剧院' });
  assert.equal(r.success, true, '主聊天助手直接调工具时必须能成功（它不知道角色卡 ID）');
  assert.equal(r.chatSource, 'chat');
  assert.equal(r.isGame, true);
  assert.ok(r.partner && r.partner.name);
  assert.ok(r.chatInviteCard.startsWith('【假面时刻 · 请柬】'));
  assert.ok(r.chatInviteCard.includes('雨夜的旧剧院'));
  assert.ok(r.chatHiddenContext.includes('角色扮演游戏'));
  assert.ok(r.chatHiddenContext.includes('masquerade_accept'), '要引导主聊天助手应邀');
  assert.ok(r.assistantInstruction.includes('应邀'));
});

test('请柬页把 chatInviteCard 交给回执驱动的 sendChatMessage，助手才能在对话框里应邀', () => {
  const ui = read('plugin/ui/app.js');
  assert.ok(ui.includes('syncToChat(r.chatInviteCard, r.chatHiddenContext'));
  assert.ok(ui.includes("target: 'current'"), '请柬发到用户当前所在会话');
  assert.equal(/\bBridge\.callAI\s*\(/.test(ui), false, '独立的 AI 应邀请求必须已删除');
  assert.ok(!ui.includes('requestConsent('));
  assert.ok(ui.includes('r.success === true'), '只有宿主明确返回 success=true 才算送达');
  assert.ok(ui.includes('masquerade_invite_delivery'), '送达结果必须回报状态机');
  assert.ok(ui.includes('masquerade_invite_cancel'), '送达失败必须能回滚 idle');
  assert.ok(!ui.includes('请柬已送到聊天里</h2>'), '不能留写死的「已送达」静态标题');
});

test('请柬发到当前会话、落幕概要发到最新有对话会话，且严格校验权限回执', () => {
  const ui = read('plugin/ui/app.js');
  assert.ok(ui.includes("target === 'latestActive'"), '按目标区分会话选择');
  assert.ok(ui.includes('(withMessages[0] || list[0])'), '落幕概要优先最新有对话会话');
  assert.ok(ui.includes('(list[0] || withMessages[0])'), '请柬优先当前会话（含无消息的新会话）');
  assert.ok(ui.includes('r.success === false'), '权限被拒 / 列表失败要带出明确原因');
  assert.ok(ui.includes("target: 'current'") && ui.includes("target: 'latestActive'"));
  assert.ok(ui.includes('Bridge.listConversations()'), '宿主不传 conversationId，只能走列表');
  assert.ok(!ui.includes('location.search'), '不能假设 URL query 里有会话 ID');
});

test('落幕压缩用插件内置的那个模型，且只把概要写进可见落幕卡', () => {
  let calls = 0;
  let compressHit = false;
  const s = play((url, opts) => {
    calls++;
    const body = JSON.parse(opts.body);
    if (body.messages[0].content.includes('剧情归档助手')) compressHit = true;
    return {
      ok: true, status: 200,
      body: JSON.stringify({ choices: [{ message: { content: compressHit ? '压缩概要：雨夜共舞，天亮归还面具。' : '（欠身）这一支与你跳。' } }] }),
      json() { return JSON.parse(this.body); }, text() { return this.body; },
    };
  });
  s.api.roleplay_turn({ line: '第一支舞，请' });
  const end = s.api.masquerade_end({});
  assert.equal(end.success, true);
  assert.equal(end.compressedBy, 'model');
  assert.equal(end.compressedDegraded, false);
  assert.equal(compressHit, true, '压缩必须走同一个模型通道');
  assert.equal(calls, 2, '一轮台词 + 一次压缩，不额外引入第二个模型');
  assert.ok(end.chatCardText.includes('本场剧情概要'));
  assert.ok(end.chatCardText.includes('不是真实发生的事'), '概要必须自带虚构标注');
  assert.equal(end.chatCardText.includes('第一支舞，请'), false, '完整台词绝不倒进主聊天');
  assert.equal(end.chatHiddenContext, end.closingNote);
  assert.equal(s.mem.length, 1, '整场仍只写一条游戏记忆');
  assert.equal(JSON.stringify(end).includes(KEY), false);
});

test('压缩与台词共用同一模型凭据与白名单校验', () => {
  const src = read('plugin/main.js');
  assert.ok(src.includes('function callModel('));
  assert.ok(/function compressTranscript[\s\S]*?callModel\(/.test(src), '压缩必须调用 callModel');
  assert.ok(/function callRoleplayModel[\s\S]*?callModel\(/.test(src), '台词必须调用 callModel');
});

test('未配置模型时压缩降级为本地摘录并明确标注，不假装是模型压的', () => {
  const s = sandbox({});
  s.api.masquerade_invite({ source: 'chat' });
  s.api.masquerade_accept({});
  const end = s.api.masquerade_end({});
  assert.equal(end.compressedBy, 'local');
  assert.equal(end.compressedDegraded, true);
  assert.ok(end.chatCardText.includes('本地摘录'));
  assert.equal(s.requests.length, 0, '没模型时一次网络请求都不能发');
});

test('系统提示词把落幕卡里的概要解释成虚构游戏，并引导主聊天助手主动应邀', () => {
  const t = manifest.promptTemplate;
  assert.ok(t.includes('masquerade_invite'));
  assert.ok(t.includes('chatCardText'));
  assert.ok(t.includes('本场剧情概要'));
  assert.ok(t.includes('不是真实发生的事'));
  assert.ok(t.includes('不进本对话'), '完整台词不进主聊天');
  assert.ok(t.includes('不要在主聊天里调用 `roleplay_turn`'));
  assert.ok(t.includes('真实情感关系'));
});

/* ---------- 3. 演绎档案 ---------- */

test('落幕写入可回看的完整档案，列表不含完整台词', () => {
  const s = sandbox({ api_key: KEY }, () => ({
    ok: true, status: 200, body: JSON.stringify({ choices: [{ message: { content: '舞台上的一句回应' } }] }),
    json() { return JSON.parse(this.body); }, text() { return this.body; },
  }));
  s.api.masquerade_invite({ source: 'chat' });
  s.api.masquerade_accept({});
  s.api.roleplay_turn({ line: '假如我此刻说喜欢你' });
  const end = s.api.masquerade_end({});
  assert.ok(end.archiveId);
  const list = s.api.list_masquerade_archives({});
  assert.equal(list.total, 1);
  assert.equal(list.archives[0].isGame, true);
  assert.equal(JSON.stringify(list).includes('假如我此刻说喜欢你'), false, '列表摘要不能带逐字台词');
  const one = s.api.get_masquerade_archive({ session_id: end.archiveId });
  assert.equal(one.success, true);
  assert.equal(one.archive.isGame, true);
  assert.ok(one.notice.includes('角色扮演游戏'));
  assert.ok(one.archive.transcript.some((m) => m.text === '假如我此刻说喜欢你'), '详情能回看完整台词');
  assert.ok(one.archive.closingNote.includes('角色扮演游戏'));
  assert.equal(JSON.stringify(one).includes(KEY), false, '档案不得混入密钥');
});

test('档案 CRUD 边界：非法 ID 拒绝、删除生效、索引限量', () => {
  const s = sandbox({});
  const badIds = ['../evil', 'a/b', '', 'x'.repeat(200), { toString: null }, null, 12345];
  for (let i = 0; i < badIds.length; i++) {
    const r = s.api.get_masquerade_archive({ session_id: badIds[i] });
    assert.equal(r.error, 'BAD_ID', `第 ${i} 个畸形 ID 应被拒绝`);
    assert.equal(s.api.delete_masquerade_archive({ session_id: badIds[i] }).error, 'BAD_ID');
  }
  assert.equal(s.api.get_masquerade_archive({ session_id: 'mask_nope' }).error, 'NOT_FOUND');
  /* 连续 25 场，索引与数据都不得超过 MAX_ARCHIVES */
  for (let i = 0; i < 25; i++) {
    s.api.masquerade_invite({ source: 'chat' });
    s.api.masquerade_accept({});
    s.api.masquerade_end({});
  }
  const list = s.api.list_masquerade_archives({});
  assert.equal(list.total, 20, '最多保留 20 场');
  const stored = Array.from(s.map.keys()).filter((k) => k.startsWith('mb:archive:') && k !== 'mb:archive:index');
  assert.equal(stored.length, 20, '超量旧档案的数据也要删掉');
  const first = list.archives[0].sessionId;
  assert.equal(s.api.delete_masquerade_archive({ session_id: first }).success, true);
  assert.equal(s.api.list_masquerade_archives({}).total, 19);
});

test('剧中台词隔离语义不因新链路破坏，档案也不写长期记忆', () => {
  const s = sandbox({ api_key: KEY }, () => ({
    ok: true, status: 200, body: JSON.stringify({ choices: [{ message: { content: '剧中回应' } }] }),
    json() { return JSON.parse(this.body); }, text() { return this.body; },
  }));
  s.api.masquerade_invite({ source: 'chat' });
  s.api.masquerade_accept({});
  s.api.roleplay_turn({ line: '假如我此刻说喜欢你' });
  assert.equal(s.mem.length, 0, '剧中不得写长期记忆');
  s.api.masquerade_end({});
  assert.equal(s.mem.length, 1, '只有落幕写一条游戏记忆');
  assert.equal(JSON.stringify(Array.from(s.map.values())).includes(KEY), false);
});

/* ---------- 4. 无模型硬拦截 ---------- */

test('无模型三态：邀请仍可用，启幕警示，台词硬拦截且不产生回复', () => {
  const s = sandbox({});
  const inv = s.api.masquerade_invite({ source: 'chat' });
  assert.equal(inv.success, true, '前端邀请不依赖插件模型');
  const acc = s.api.masquerade_accept({});
  assert.equal(acc.success, true);
  assert.equal(acc.modelStatus, 'none');
  assert.ok(acc.modelWarning);
  const turn = s.api.roleplay_turn({ line: '你好' });
  assert.equal(turn.success, false);
  assert.equal(turn.error, 'MODEL_NOT_CONFIGURED');
  assert.equal(turn.reply, undefined);
  assert.equal(s.requests.length, 0);
});

test('预览 demo 不会渗进真机：QuickJS 沙箱无 window 时 demo 标记无效', () => {
  const s = sandbox({ demo: true });
  assert.equal(s.api.get_model_config({}).modelStatus, 'none');
  s.api.masquerade_invite({ source: 'chat' });
  s.api.masquerade_accept({});
  assert.equal(s.api.roleplay_turn({ line: '你好' }).error, 'MODEL_NOT_CONFIGURED');
});

test('页面在无模型时不追加任何舞伴气泡，并给出配置指引', () => {
  const ui = read('plugin/ui/app.js');
  assert.ok(ui.includes("r.error === 'MODEL_NOT_CONFIGURED'"));
  assert.ok(ui.includes('这一轮没有生成任何回复'));
  assert.ok(ui.includes('applyModelStatus'), '首页要显示未配置提示');
  assert.ok(ui.includes('r.isDemo ?'), '预览演示剧本要显式标注');
});

test('权限收敛：邀请与落幕都只走 conversation_sync', () => {
  assert.deepEqual([...manifest.permissions], ['conversation_sync']);
});

/* ---------- 页面结构 ---------- */

test('演绎档案 tab 与落幕概要区真实存在', () => {
  const html = read('plugin/ui/index.html');
  for (const id of ['view-archive', 'archive-list', 'archive-detail', 'archive-transcript',
    'btn-archive-export', 'btn-archive-delete', 'compressed-summary', 'compressed-by',
    'btn-view-archive', 'model-alert', 'model-status']) {
    assert.ok(html.includes(`id="${id}"`), `缺少节点 ${id}`);
  }
});
