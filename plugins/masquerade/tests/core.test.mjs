import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAIN = path.join(ROOT, 'plugin', 'main.js');
const SRC = fs.readFileSync(MAIN, 'utf8');

/** 在 VM 里以「宿主注入全局」的方式加载 QuickJS 插件，返回可观测的 mock。 */
function load(overrides = {}) {
  const map = new Map();
  const dataStore = {
    get: (k) => (map.has(k) ? map.get(k) : null),
    set: (k, v) => map.set(k, String(v)),
    del: (k) => map.delete(k),
    list: (prefix) => Array.from(map.keys()).filter((k) => k.indexOf(prefix) === 0),
  };
  const memory = [];
  const memoryBank = {
    save: (t) => memory.push(String(t)),
    recall: () => [],
    search: () => [],
    delete: () => {},
  };
  const config = {
    inject_as_prompt: true,
    api_base: 'https://api.openai.com/v1',
    api_key: '',
    model_id: 'gpt-4o-mini',
    max_turns: 40,
    ...(overrides.config || {}),
  };
  const requests = [];
  const fetchImpl = (url, opts) => {
    requests.push({ url, opts });
    const body = JSON.stringify({
      choices: [{ message: { content: overrides.reply || '（舞台上）我应邀，这一支与你跳。' } }],
    });
    return {
      ok: overrides.httpOk === undefined ? true : overrides.httpOk,
      status: overrides.httpStatus || 200,
      body,
      json() { return JSON.parse(this.body); },
      text() { return this.body; },
    };
  };
  const exportsObj = {};
  const sandbox = {
    exports: exportsObj,
    dataStore,
    config,
    memoryBank,
    fetch: fetchImpl,
    console: { warn() {}, log() {}, info() {}, error() {} },
  };
  vm.createContext(sandbox);
  vm.runInContext(SRC, sandbox, { filename: 'main.js' });
  return { core: exportsObj, sandbox, map, memory, requests, config };
}

function playToActive(core) {
  const cards = core.list_role_cards({});
  assert.equal(cards.success, true);
  assert.equal(cards.total, 3);
  const invited = core.masquerade_invite({ card_id: cards.cards[0].id, scene: '午夜花房', user_mask: '无名来客' });
  assert.equal(invited.status, 'invited');
  return invited;
}

test('首次列出角色卡会写入三张内置示例卡', () => {
  const { core, map } = load();
  const first = core.list_role_cards({});
  assert.equal(first.success, true);
  assert.equal(first.total, 3);
  assert.ok(first.cards.every((c) => c.id && c.name && c.emoji));
  assert.equal(core.list_role_cards({}).total, 3, '第二次不得重复播种');
  assert.ok(map.has('mb:cards'));
});

test('角色卡创建/校验/裁剪：空名拒绝、字段限长、标签最多 10 个', () => {
  const { core } = load();
  const invalid = core.create_role_card({ card: { name: '', identity: '', personality: '' } });
  assert.equal(invalid.success, false);
  assert.equal(invalid.error, 'INVALID_CARD');

  const created = core.create_role_card({
    card: {
      name: 'X'.repeat(200),
      identity: 'Y'.repeat(1000),
      tags: Array.from({ length: 30 }, (_, i) => 'tag' + i),
      visibility: 'public',
    },
  });
  assert.equal(created.success, true);
  assert.equal(created.card.name.length, 40);
  assert.equal(created.card.identity.length, 400);
  assert.equal(created.card.tags.length, 10);
  assert.equal(created.card.visibility, 'public');
  assert.equal(core.list_role_cards({}).total, 4);
});

test('先建卡再列卡不会丢内置卡；删光后示例卡不会复活', () => {
  const { core } = load();
  const created = core.create_role_card({ card: { name: '自绘面具', identity: '一位旅人' } });
  assert.equal(created.success, true);
  assert.equal(core.list_role_cards({}).total, 4);

  for (const c of core.list_role_cards({}).cards) {
    assert.equal(core.delete_role_card({ card_id: c.id }).success, true);
  }
  assert.equal(core.list_role_cards({}).total, 0, '哨兵生效：删除后不重新播种');
});

test('get_model_config 绝不返回 API Key，configured 状态正确', () => {
  const withoutKey = load();
  const a = withoutKey.core.get_model_config({});
  assert.equal(a.configured, false);
  assert.equal(a.hasKey, false);
  assert.equal(Object.prototype.hasOwnProperty.call(a, 'apiKey'), false);
  assert.equal(JSON.stringify(a).includes('sk-test-secret'), false);

  const withKey = load({ config: { api_key: 'sk-test-secret' } });
  const b = withKey.core.get_model_config({});
  assert.equal(b.configured, true);
  assert.equal(b.hasKey, true);
  assert.equal(JSON.stringify(b).includes('sk-test-secret'), false, '返回值里不得出现密钥');
});

test('状态机 invite -> accept -> turn -> end，非法转换被拒绝', () => {
  const { core } = load({ config: { api_key: 'sk-test-secret' } });
  assert.equal(core.masquerade_status({}).status, 'idle');
  assert.equal(core.roleplay_turn({ line: '抢跑' }).error, 'NO_ACTIVE_SESSION');
  assert.equal(core.masquerade_accept({}).error, 'BAD_STATE');
  assert.equal(core.masquerade_end({}).error, 'NO_SESSION');

  const invited = playToActive(core);
  const cards = core.list_role_cards({});
  assert.equal(core.masquerade_invite({ card_id: cards.cards[0].id }).error, 'BAD_STATE', '进行中不得再开一场');
  assert.equal(invited.status, 'invited');
  assert.equal(core.roleplay_turn({ line: '抢跑' }).error, 'NO_ACTIVE_SESSION');

  const accepted = core.masquerade_accept({ assistant_reply: '我同意' });
  assert.equal(accepted.status, 'active');
  assert.equal(accepted.stage.isGame, true);
  assert.ok(accepted.chatHiddenContext.includes('不是真实生活'));
  assert.equal(core.masquerade_status({}).status, 'active');

  const turn = core.roleplay_turn({ line: '你把面具摘下来好吗' });
  assert.equal(turn.success, true);
  assert.equal(turn.turn, 1);
  assert.ok(turn.reply.length > 0);
});

test('落幕：助手必须知道刚才是角色扮演游戏；只写一条明确标注游戏的记忆', () => {
  const { core, memory, map } = load({ config: { api_key: 'sk-test-secret' } });
  playToActive(core);
  core.masquerade_accept({});
  core.roleplay_turn({ line: '第一支舞' });
  core.roleplay_turn({ line: '第二支舞' });

  assert.equal(memory.length, 0, '剧中对话期间不得写长期记忆');

  const ended = core.masquerade_end({ reason: '天亮了' });
  assert.equal(ended.success, true);
  assert.equal(ended.status, 'ended');
  assert.equal(ended.isGame, true);
  assert.ok(ended.closingNote.includes('角色扮演游戏'));
  assert.ok(ended.closingNote.includes('不是真实发生的事'));
  assert.ok(ended.closingNote.includes('平时依旧是原本的真实情感关系'));
  assert.equal(ended.chatHiddenContext, ended.closingNote, '落幕卡的临时系统提示词必须是完整说明');
  assert.ok(ended.chatCardText.includes('【假面时刻 · 落幕】'));
  assert.equal(ended.memoryWritten, true);
  assert.equal(memory.length, 1, '整场只写一条记忆');
  assert.equal(memory[0], ended.closingNote);
  assert.ok(map.has('mb:lastClosing'));
  assert.equal(core.masquerade_end({}).error, 'ALREADY_ENDED');
  assert.equal(core.masquerade_status({}).status, 'ended');
});

test('未应邀就收回请柬：不写记忆、不发主聊天落幕卡', () => {
  const { core, memory } = load();
  const cards = core.list_role_cards({});
  core.masquerade_invite({ card_id: cards.cards[0].id });
  const cancelled = core.masquerade_end({ reason: '收回' });
  assert.equal(cancelled.cancelled, true);
  assert.equal(cancelled.status, 'idle');
  assert.equal(cancelled.chatHiddenContext, null);
  assert.equal(memory.length, 0);
  assert.equal(core.masquerade_status({}).status, 'idle');
});

test('模型通道：拒绝明文 HTTP、拒绝非白名单域名、密钥只进请求头', () => {
  const insecure = load({ config: { api_key: 'sk-test-secret', api_base: 'http://api.openai.com/v1' } });
  playToActive(insecure.core);
  insecure.core.masquerade_accept({});
  const r1 = insecure.core.roleplay_turn({ line: '你好' });
  assert.equal(r1.error, 'INSECURE_BASE_URL');
  assert.equal(insecure.requests.length, 0, '不安全地址不得发出请求');

  const offList = load({ config: { api_key: 'sk-test-secret', api_base: 'https://evil.example.com/v1' } });
  playToActive(offList.core);
  offList.core.masquerade_accept({});
  const r2 = offList.core.roleplay_turn({ line: '你好' });
  assert.equal(r2.error, 'HOST_NOT_ALLOWED');
  assert.equal(offList.requests.length, 0);

  const good = load({ config: { api_key: 'sk-test-secret' } });
  playToActive(good.core);
  good.core.masquerade_accept({});
  const r3 = good.core.roleplay_turn({ line: '你好' });
  assert.equal(r3.success, true);
  assert.equal(good.requests.length, 1);
  assert.equal(good.requests[0].url, 'https://api.openai.com/v1/chat/completions');
  assert.equal(good.requests[0].opts.headers.Authorization, 'Bearer sk-test-secret');
  assert.ok(good.requests[0].opts.body.includes('假面时刻'));
  assert.equal(JSON.stringify(r3).includes('sk-test-secret'), false);
});

test('未配置模型时给出可操作错误，不抛异常', () => {
  const { core } = load();
  playToActive(core);
  core.masquerade_accept({});
  const r = core.roleplay_turn({ line: '你好' });
  assert.equal(r.success, false);
  assert.equal(r.error, 'NO_API_KEY');
  assert.ok(r.message.includes('模型接入'));
});

test('单场轮次上限生效', () => {
  const { core } = load({ config: { api_key: 'sk-test-secret', max_turns: 2 } });
  playToActive(core);
  core.masquerade_accept({});
  assert.equal(core.roleplay_turn({ line: '1' }).turn, 1);
  assert.equal(core.roleplay_turn({ line: '2' }).turn, 2);
  const r = core.roleplay_turn({ line: '3' });
  assert.equal(r.error, 'MAX_TURNS');
});

test('辅助函数：控制字符清理与 HTML 转义（防 XSS）', () => {
  const { sandbox } = load();
  const dirty = 'a\u0000b\u001Fc' + '<img src=x onerror=alert(1)>';
  assert.equal(sandbox.sanitizeText(dirty, 10).includes('\u0000'), false);
  assert.equal(sandbox.sanitizeText(dirty, 10).length <= 10, true);
  const escaped = sandbox.escapeHtml('<b>"\'&</b>');
  assert.equal(escaped.includes('<'), false);
  assert.equal(escaped.includes('&lt;b&gt;'), true);
  assert.equal(escaped.includes('&quot;'), true);
  assert.equal(escaped.includes('&#39;'), true);
});

test('损坏的 dataStore 内容不会导致插件抛异常', () => {
  const { core, map } = load();
  map.set('mb:cards', '{not json');
  const r = core.list_role_cards({});
  assert.equal(r.success, true);
  assert.ok(Array.isArray(r.cards));
});

test('落幕后重新邀请可开启新一场', () => {
  const { core } = load({ config: { api_key: 'sk-test-secret' } });
  playToActive(core);
  core.masquerade_accept({});
  core.masquerade_end({});
  const cards = core.list_role_cards({});
  const again = core.masquerade_invite({ card_id: cards.cards[1].id });
  assert.equal(again.success, true);
  assert.equal(again.status, 'invited');
  assert.notEqual(again.sessionId, undefined);
});
