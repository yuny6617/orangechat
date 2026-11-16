/*
 * 1.4.0 专项：主聊天助手通过插件工具应邀，并把邀约卡原样写进对话。
 *
 * 宿主 ToolSurfaceBuilder 会把 manifest.tools 注册给 LLM；工具执行结果作为
 * tool message 回给模型，模型据此生成用户看得见的回复。因此「邀约是否进对话」
 * 取决于：工具描述是否写清触发条件 + 返回值是否有必须原样输出的 replyToUser。
 * 这里不依赖插件页面的 sendChatMessage，也不依赖 promptTemplate 是否注入。
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
const tool = (name) => manifest.tools.find((t) => t.name === name);

function core(config = {}, fetchImpl) {
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
  return { api: exportsObj, mem };
}

const KEY = 'sk-CHAT-TOOL-TEST-SECRET-1234567890';
const MOCK_MODEL = () => ({
  ok: true, status: 200,
  body: JSON.stringify({ choices: [{ message: { content: '剧情概要：雨夜共舞，天亮归还面具。' } }] }),
  json() { return JSON.parse(this.body); }, text() { return this.body; },
});

/* ---------- 1. 工具描述：必然发给 LLM 的那条通道 ---------- */

test('三个流程工具的描述都写清「何时必须调用」与「调用后原样输出」', () => {
  const triggers = {
    masquerade_invite: '我们玩一场假面舞会吧',
    masquerade_accept: '我愿意',
    masquerade_end: '摘下面具',
  };
  for (const [name, phrase] of Object.entries(triggers)) {
    const d = tool(name).description;
    assert.ok(d.includes('必须调用的场景'), `${name} 缺触发标题`);
    assert.ok(d.includes(phrase), `${name} 缺真实用户措辞「${phrase}」`);
    assert.ok(d.includes('replyToUser'), `${name} 缺输出契约字段`);
    assert.ok(d.includes('一字不改'), `${name} 缺原样输出的强约束`);
    assert.ok(d.includes('不要输出 JSON'), `${name} 要禁止复述工具结果`);
    assert.ok(d.includes('角色扮演'), `${name} 要带虚构游戏语义`);
  }
});

test('工具描述自带语义边界，不依赖 promptTemplate 是否注入', () => {
  /* 宿主 getPluginPromptInjections() 只在用户显式保存 inject_as_prompt=true 时才注入模板，
     所以关键规则必须落在 buildDescription() 原样发出的 toolDef.description 里。 */
  for (const name of ['masquerade_invite', 'masquerade_accept', 'masquerade_end']) {
    const d = tool(name).description;
    assert.ok(d.includes('不是真实关系变化') || d.includes('不是真实发生的事'), `${name} 缺关系边界`);
    assert.ok(d.includes('剧中'), `${name} 要说明剧中内容边界`);
  }
  assert.ok(tool('roleplay_turn').description.includes('不要在主聊天对话里调用'));
});

test('promptTemplate 说明宿主工具名前缀，且输出契约与工具描述一致', () => {
  const t = manifest.promptTemplate;
  assert.ok(t.includes('plg_'), '要说明 ToolNaming 的插件前缀');
  assert.ok(t.includes('replyToUser') && t.includes('一字不改'));
  assert.ok(t.includes('不要') && t.includes('JSON'));
  assert.ok(t.includes('已经出现'), '要避免重复输出插件页已发的卡片');
  assert.equal(t.includes('把返回里的 `chatCardText` **原样输出**'), false,
    '旧契约字段名已废弃，不能与 replyToUser 冲突');
});

/* ---------- 2. 工具返回值：助手要原样输出的那段文本 ---------- */

test('邀请：replyToUser 就是请柬卡，含游戏标注，可直接作为助手回复', () => {
  const r = core().api.masquerade_invite({ scene: '雨夜的玻璃花房', source: 'chat' });
  assert.equal(r.success, true);
  assert.equal(r.replyToUser, r.chatInviteCard);
  assert.ok(r.replyToUser.startsWith('【假面时刻 · 请柬】'));
  assert.ok(r.replyToUser.includes('雨夜的玻璃花房'));
  assert.ok(r.replyToUser.includes('愿意陪我跳这一支吗'));
  assert.ok(r.replyInstruction.includes('一字不改') && r.replyInstruction.includes('不要输出 JSON'));
  assert.ok(r.replyInstruction.includes('角色扮演'));
});

test('启幕：replyToUser 就是启幕卡，助手应邀后把它写进对话', () => {
  const api = core().api;
  api.masquerade_invite({});
  const r = api.masquerade_accept({});
  assert.equal(r.replyToUser, r.chatCardText);
  assert.ok(r.replyToUser.startsWith('【假面时刻 · 启幕】'));
  assert.ok(r.replyInstruction.includes('一字不改'));
});

test('落幕：replyToUser 只含压缩概要，不含任何逐字台词', () => {
  const api = core({ api_key: KEY, api_base: 'https://api.openai.com/v1', model_id: 'gpt-4o-mini' }, MOCK_MODEL).api;
  api.masquerade_invite({});
  api.masquerade_accept({});
  api.roleplay_turn({ line: '舞台上才该出现的秘密台词XYZ' });
  const r = api.masquerade_end({});
  assert.equal(r.replyToUser, r.chatCardText);
  assert.ok(r.replyToUser.startsWith('【假面时刻 · 落幕】'));
  assert.ok(r.replyToUser.includes('本场剧情概要'));
  assert.ok(r.replyToUser.includes('不是真实发生的事'));
  assert.equal(r.replyToUser.includes('舞台上才该出现的秘密台词XYZ'), false);
  assert.ok(r.replyInstruction.includes('一字不改'));
});

test('婉拒：replyToUser 明确「这一场没有开始」，不写记忆', () => {
  const c = core();
  c.api.masquerade_invite({});
  const r = c.api.masquerade_end({ reason: '婉拒' });
  assert.equal(r.cancelled, true);
  assert.equal(r.replyToUser, r.chatDeclineCard);
  assert.ok(r.replyToUser.startsWith('【假面时刻 · 婉拒】'));
  assert.ok(r.chatDeclineHiddenContext.includes('这一场没有开始'));
  assert.equal(c.mem.length, 0);
});

/* ---------- 3. 模拟宿主：LLM 按名称找到工具 → 调用 → 拿到要输出的内容 ---------- */

test('端到端：用户说一句邀请 → 助手按工具名调用 → 请柬文本进对话', () => {
  const api = core().api;
  /* 宿主 PluginToolProvider.getTools() 枚举 manifest.tools 并注册给 LLM；
     助手按描述里的触发条件选中 masquerade_invite、按 parameters 调用。 */
  const def = tool('masquerade_invite');
  const params = def.parameters.reduce((acc, p) => {
    if (p.name === 'source') acc[p.name] = 'chat';
    else if (p.name === 'scene') acc[p.name] = '午夜的玻璃花房';
    return acc;
  }, {});
  const result = api[def.name](params);
  assert.equal(result.success, true);
  /* 这就是会被 Assistant 消息写进对话历史的文本。 */
  const assistantReply = result.replyToUser;
  assert.ok(assistantReply.includes('【假面时刻 · 请柬】'));
  assert.ok(assistantReply.includes('午夜的玻璃花房'));
  assert.ok(assistantReply.includes('这只是一场') || assistantReply.includes('约好一起演的一场戏'));
  assert.equal(assistantReply.includes('{'), false, '回复里不能混进工具 JSON');
});

test('完整三拍：邀请 → 同意 → 落幕，三段 replyToUser 依次进对话', () => {
  const api = core().api;
  const invite = api.masquerade_invite({ scene: '雪夜的旧剧院' });
  const accept = api.masquerade_accept({});
  const end = api.masquerade_end({});
  const dialogue = [invite.replyToUser, accept.replyToUser, end.replyToUser];
  assert.ok(dialogue[0].includes('请柬'));
  assert.ok(dialogue[1].includes('启幕'));
  assert.ok(dialogue[2].includes('落幕') && dialogue[2].includes('本场剧情概要'));
  assert.equal(api.masquerade_status({}).status, 'ended');
});

/* ---------- 4. 页面不再依赖弹窗，且送达失败不阻断玩法 ---------- */

test('页面已移除消息内弹窗，改为平铺按钮 + 可复制请柬兜底', () => {
  const html = read('plugin/ui/index.html');
  const css = read('plugin/ui/style.css');
  const ui = read('plugin/ui/app.js');
  assert.equal(html.includes('id="invite-modal"'), false);
  assert.equal(html.includes('id="btn-modal-accept"'), false);
  assert.equal(/\.modal-sheet\s*\{/.test(css), false, '弹窗样式必须删除');
  assert.equal(/backdrop-filter/.test(css), false, '真机 WebView 上会塌陷的滤镜必须删除');
  assert.equal(/max-height:\s*92dvh/.test(css), false);
  assert.ok(html.includes('id="btn-accept"') && html.includes('id="btn-decline"'));
  assert.ok(html.includes('id="btn-copy-invite"') && html.includes('id="invite-copy-text"'));
  assert.equal(/openInviteModal|closeInviteModal/.test(ui), false);
  assert.ok(/function copyInviteText\(/.test(ui));
});

test('送达失败不再回滚 idle：状态保持 invited，页面仍可同意/婉拒', () => {
  const ui = read('plugin/ui/app.js');
  assert.equal(/masquerade_invite_cancel[\s\S]{0,200}showPane\('idle'\)/.test(ui), false,
    'doInvite 的失败分支不能再回滚 idle');
  assert.ok(ui.includes('请柬未送达聊天 · 仍可继续'));
  assert.ok(ui.includes('也可以复制下面的请柬文本贴进聊天'));
});
