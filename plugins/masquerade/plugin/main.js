/*
 * 假面舞会 · Masquerade Ball — 橘瓣 QuickJS 插件核心（ES5 子集）
 *
 * 宿主注入的全局：dataStore / config / memoryBank / fetch / console / exports
 * 隔离约定：
 *   1. 剧中对话只写 dataStore 的 mb:transcript:*，不进主聊天历史、不写长期记忆。
 *   2. 只有落幕时写一条明确标注「这是角色扮演游戏」的记忆，并由页面用
 *      Bridge.sendChatMessage 的 transientSystemPrompt 一次性告知助手。
 *   3. API Key 只从 config(password) 读取，绝不写入 dataStore、绝不出现在返回值与日志。
 */

var PLUGIN_ID = 'com.orangechat.masquerade';
var PREFIX = 'mb:';
var MAX_CARDS = 60;
var MAX_TRANSCRIPT = 80;
var MAX_TURNS_DEFAULT = 40;
var MAX_TURNS_HARD = 200;

/* 与 manifest.allowedHosts 保持一致，作为第二道校验；不使用通配 "*"。 */
var ALLOWED_HOSTS = [
  'api.openai.com', 'api.deepseek.com', 'open.bigmodel.cn',
  'dashscope.aliyuncs.com', 'api.moonshot.cn', 'api.siliconflow.cn',
  'ark.cn-beijing.volces.com', 'api.anthropic.com',
  'generativelanguage.googleapis.com', 'api.groq.com', 'openrouter.ai',
  'api.x.ai', 'api.minimax.chat', 'api.hunyuan.cloud.tencent.com'
];

/*
 * 配置只有一个来源：宿主插件详情页渲染的 manifest.config。
 * 宿主没有 setPluginConfig 桥接方法，插件页也不再提供第二套配置，
 * 避免「宿主里改了、页面里旧值覆盖」这种两个配置源打架的问题。
 */
var MAX_ARCHIVES = 20;          /* 演绎档案最多保留场次，防止 dataStore 无限膨胀 */
var ARCHIVE_PREFIX = PREFIX + 'archive:';
var ARCHIVE_INDEX = PREFIX + 'archive:index';
var MAX_COMPRESS_LINES = 40;   /* 压缩时最多喂给模型的台词条数 */
var MAX_COMPRESSED = 600;      /* 压缩概要最大字数 */

var idSeq = 0;

function cfg() { return (typeof config === 'undefined' || !config) ? {} : config; }
function ds() { return (typeof dataStore === 'undefined' || !dataStore) ? null : dataStore; }
function mb() { return (typeof memoryBank === 'undefined' || !memoryBank) ? null : memoryBank; }
function log(msg) { if (typeof console !== 'undefined' && console.warn) console.warn('[masquerade] ' + msg); }

/*
 * 模型可用性三态：
 *   configured — 宿主配置里有可用凭据，剧中台词与落幕压缩都走插件内置模型通道
 *   demo       — 浏览器交互预览（boot.js 显式注入 demo:true），走本地演示剧本
 *   none       — 未配置模型：绝不编造回复、绝不假装压缩，硬拦截并给出配置指引
 */
function modelAvailability() {
  var c = cfg();
  /*
   * demo 只在浏览器预览垫片里成立：宿主 QuickJS 沙箱没有 window，
   * 且 config 由宿主按 manifest.config 注入、不含 demo 字段，双重门槛无法被伪造。
   */
  if (c.demo === true && !c.api_key && typeof window !== 'undefined') return 'demo';
  var cred = resolveCredentials();
  return cred.key ? 'configured' : 'none';
}

/*
 * 预览演示剧本：明确标注「非真实模型」，并引用用户这一轮的话，
 * 不再用固定 6 句轮转造成牛头不对马嘴的观感。
 */
function demoRoleplayReply(line) {
  var quote = sanitizeText(line, 40);
  return '（演示剧本 · 不是真实模型回复）你刚才说「' + quote + '」——' +
    '我记下了。预览页不联网、不调用模型；要在橘瓣里真正演出，请在宿主插件详情页配置模型。';
}

var MODEL_SETUP_HINT = '尚未配置模型：请到「设置 → 插件管理 → 假面舞会」填写模型 API Key（或选好备用模型），' +
  '保存后重新打开插件。未配置时不会生成任何剧中台词，也不会假装压缩概要。';

function nowIso() {
  try { return new Date().toISOString(); } catch (e) { return '1970-01-01T00:00:00.000Z'; }
}

function newId(prefix) {
  idSeq = idSeq + 1;
  return prefix + '_' + Date.now().toString(36) + '_' + idSeq.toString(36) +
    Math.random().toString(36).substring(2, 8);
}

/* 只接受标量：对象/数组/函数/带坏 toString 的对象一律当空值，绝不触发隐式转换异常。 */
function safeString(value) {
  var t = typeof value;
  if (value === null || value === undefined) return '';
  if (t === 'string') return value;
  if (t === 'number' && isFinite(value)) return String(value);
  if (t === 'boolean') return String(value);
  return '';
}

function sanitizeText(value, max) {
  var t = safeString(value);
  t = t.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '');
  t = t.replace(/\r\n?/g, '\n');
  if (t.length > max) t = t.substring(0, max);
  return t.replace(/^\s+|\s+$/g, '');
}

function escapeHtml(value) {
  return safeString(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function asObject(value) {
  if (value && typeof value === 'object') return value;
  if (typeof value === 'string') {
    try {
      var parsed = JSON.parse(value);
      return (parsed && typeof parsed === 'object') ? parsed : {};
    } catch (e) { return {}; }
  }
  return {};
}

function ok(value) { return { success: true, data: value, error: null }; }
function fail(code, message) { return { success: false, data: null, error: code, message: message || code }; }

function storeGet(key, fallback) {
  var store = ds();
  if (!store) return fallback;
  try {
    var raw = store.get(key);
    if (raw === null || raw === undefined || raw === '') return fallback;
    return (typeof raw === 'string') ? JSON.parse(raw) : raw;
  } catch (e) { log('corrupt key ' + key); return fallback; }
}

function storeSet(key, value) {
  var store = ds();
  if (!store) return false;
  store.set(key, JSON.stringify(value));
  return true;
}

function storeDel(key) {
  var store = ds();
  if (store && store.del) store.del(key);
}

function normalizeTags(value) {
  var out = [];
  if (!value || typeof value.length !== 'number') return out;
  for (var i = 0; i < value.length && out.length < 10; i++) {
    var t = sanitizeText(value[i], 24);
    if (t) out.push(t);
  }
  return out;
}

var CARD_LIMITS = {
  name: 40, emoji: 8, identity: 400, personality: 600, scenario: 600,
  opening: 800, worldview: 800, speechSample: 800, boundaries: 400
};

function normalizeCard(raw, existing) {
  var o = asObject(raw);
  var card = {};
  for (var key in CARD_LIMITS) {
    if (CARD_LIMITS.hasOwnProperty(key)) {
      card[key] = sanitizeText(o[key], CARD_LIMITS[key]);
    }
  }
  card.id = existing ? existing.id : newId('card');
  card.name = card.name || '未命名面具';
  card.emoji = card.emoji || '🎭';
  card.tags = normalizeTags(o.tags);
  card.visibility = (o.visibility === 'link' || o.visibility === 'public') ? o.visibility : 'private';
  card.createdAt = existing ? existing.createdAt : nowIso();
  card.updatedAt = nowIso();
  return card;
}

function validateCard(card) {
  var errors = [];
  if (!card.name) errors.push('角色名不能为空');
  if (!card.identity && !card.personality) errors.push('身份设定与性格描述至少填一项');
  var total = 0;
  for (var key in CARD_LIMITS) {
    if (CARD_LIMITS.hasOwnProperty(key)) total += card[key] ? card[key].length : 0;
  }
  if (total > 4000) errors.push('角色设定总长度超过 4000 字');
  return { ok: errors.length === 0, errors: errors, length: total };
}

function cardSummary(card) {
  return {
    id: card.id, name: card.name, emoji: card.emoji,
    identity: card.identity.substring(0, 80), tags: card.tags,
    visibility: card.visibility, updatedAt: card.updatedAt
  };
}

function readCardIds() {
  var ids = storeGet(PREFIX + 'cards', []);
  if (!ids || typeof ids.length !== 'number') return [];
  return ids;
}

function readCard(id) {
  if (!id) return null;
  var raw = storeGet(PREFIX + 'card:' + id, null);
  return (raw && typeof raw === 'object') ? raw : null;
}

var SEED_CARDS = [
  {
    name: '蔷薇公爵', emoji: '🌹', identity: '舞会主人，掌管整座玻璃花房，说话慢而笃定。',
    personality: '温柔克制，习惯用提问代替直接回答，从不许下做不到的承诺。',
    scenario: '午夜十二点，钟声第三响时，花房的灯恰好熄灭一半。',
    opening: '你来得比请柬上写的时间晚了一点。……不过，位子还留着。',
    worldview: '假面舞会每晚只开一场，天亮之前所有身份都会归还。',
    speechSample: '「不必告诉我你是谁。今夜，名字只是另一副面具。」',
    boundaries: '不把剧中称呼当作真实关系，不索取真实身份信息，不写越界亲密内容。',
    tags: ['宫廷', '温柔', '慢热']
  },
  {
    name: '月下提琴师', emoji: '🎻', identity: '常年坐在乐池阴影里，只为愿意停下来听的人演奏。',
    personality: '话少，敏锐，偶尔用一句玩笑把气氛轻轻拨开。',
    scenario: '露台外下着很细的雨，第二支曲子刚起了个头。',
    opening: '这一支还没奏完。要不要先坐一会儿？',
    worldview: '每一副面具都对应一段旋律，摘下面具时曲子就该收尾。',
    speechSample: '「你听得出走音了？……那我为你重拉一遍。」',
    boundaries: '保持舞台感，不把剧情当真，不引导用户透露现实隐私。',
    tags: ['雨夜', '音乐', '安静']
  },
  {
    name: '玻璃花房守夜人', emoji: '🕯️', identity: '负责在舞会散场后清点面具，记得每一副面具的故事。',
    personality: '认真、细心，带一点笨拙的热心。',
    scenario: '最后一位客人还没走，烛台只剩三支亮着。',
    opening: '别急，我还没把你的面具收走。……今晚还长着呢。',
    worldview: '面具只是道具，天亮时一律归还给柜子。',
    speechSample: '「第几号柜来着……啊，是你。我记得。」',
    boundaries: '不制造真实承诺，不混淆游戏与现实，遵守用户设定的停止信号。',
    tags: ['治愈', '夜色', '轻剧情']
  }
];

/* 播种只发生一次：用独立哨兵，避免「用户删光卡」或「先建卡后列卡」把示例卡弄丢或复活。 */
function ensureSeedCards() {
  var ids = readCardIds();
  if (storeGet(PREFIX + 'seeded', false) === true) return ids;
  if (ids.length) {
    storeSet(PREFIX + 'seeded', true);
    return ids;
  }
  var out = [];
  for (var i = 0; i < SEED_CARDS.length; i++) {
    var card = normalizeCard(SEED_CARDS[i], null);
    storeSet(PREFIX + 'card:' + card.id, card);
    out.push(card.id);
  }
  storeSet(PREFIX + 'cards', out);
  storeSet(PREFIX + 'seeded', true);
  return out;
}

/* ---------- 状态机 ---------- */

function nextStatus(status, action) {
  if (status === 'idle' || status === 'ended' || status === null || status === undefined) {
    return action === 'invite' ? 'invited' : null;
  }
  if (status === 'invited') {
    if (action === 'accept') return 'active';
    if (action === 'cancel' || action === 'end') return 'idle';
    return null;
  }
  if (status === 'active') {
    if (action === 'end') return 'ended';
    if (action === 'turn') return 'active';
    return null;
  }
  return null;
}

function currentStatus() {
  var s = storeGet(PREFIX + 'session', null);
  return s ? s.status : 'idle';
}

function getSession() { return storeGet(PREFIX + 'session', null); }

function maxTurns() {
  var n = Number(cfg().max_turns);
  if (!isFinite(n) || n < 1) n = MAX_TURNS_DEFAULT;
  if (n > MAX_TURNS_HARD) n = MAX_TURNS_HARD;
  return Math.floor(n);
}

/* ---------- 主聊天请柬：邀请必须发生在对话框里 ---------- */

function defaultCard() {
  var ids = ensureSeedCards();
  for (var i = 0; i < ids.length; i++) {
    var c = readCard(ids[i]);
    if (c) return c;
  }
  return null;
}

function buildInviteCard(session) {
  var c = session.cardSnapshot || {};
  return '【假面时刻 · 请柬】🎭\n' +
    '今夜的假面舞会，我留了一个位子。\n' +
    '舞台：' + sanitizeText(session.scene || '', 200) + '\n' +
    '想邀你以「' + sanitizeText(c.name || '假面舞伴', 60) + '」的身份赴约；我戴上「' +
    sanitizeText(session.userMask || '', 60) + '」这副面具。\n' +
    '说好了：这只是我们约好一起演的一场戏，天亮时面具归还，我们还是我们。\n\n' +
    '愿意陪我跳这一支吗？';
}

function buildInviteNote(session) {
  var c = session.cardSnapshot || {};
  return '【假面时刻 · 请柬说明｜仅本轮有效】\n' +
    '用户在聊天里递来一张「假面舞会」请柬，邀请你进入一段共同约定的角色扮演游戏。' +
    '这不是真实生活，不是关系变化，也不改变你与用户平时的真实情感关系。\n' +
    '请用一句温柔的话应邀，表示你愿意一起玩这场游戏；可以轻轻点一下舞台身份「' +
    sanitizeText(c.name || '假面舞伴', 60) + '」，但不要开始长篇角色扮演，不要把剧中身份带进后续对话。\n' +
    '用户确认开始后，请调用 masquerade_accept 工具，并把返回的 chatCardText 原样输出给用户。';
}

var INVITE_ASSISTANT_INSTRUCTION = '这张请柬邀请你参加一场共同约定的角色扮演游戏（不是真实关系变化）。' +
  '请用一句温柔的话应邀，表示愿意一起玩；不要解释规则、不要开始长篇角色扮演、不要沿用剧中身份。' +
  '若用户确认开始，调用 masquerade_accept 并把返回的 chatCardText 原样输出给用户。';

var ACCEPT_ASSISTANT_INSTRUCTION = '请把返回的 chatCardText 原样输出给用户（不要改写），' +
  '再用一句你原本语气的话表示舞台已备好。不要在当前聊天里开始角色扮演，剧中台词只在专属舞台里进行。';

var END_ASSISTANT_INSTRUCTION = '请把返回的 chatCardText 原样输出给用户。' +
  '这场戏已经结束，之后请用原本的真实关系与语气对话，不要沿用剧中人设，不要把剧情当成真实发生过的事。';

/* ---------- 提示词 / 落幕说明 ---------- */

function buildStageSystemPrompt(session) {
  var c = (session && session.cardSnapshot) ? session.cardSnapshot : {};
  var lines = [];
  lines.push('【假面时刻 · 剧中规则｜本场最高优先级】');
  lines.push('这是一场用户与你共同约定的角色扮演游戏「假面舞会」。它不是真实生活，不改变你们平时的真实情感关系。');
  lines.push('你的舞台身份：' + (c.name || '假面舞伴') + '。身份设定：' + (c.identity || '（未设定）'));
  lines.push('性格：' + (c.personality || '（未设定）'));
  lines.push('世界观：' + (c.worldview || '（未设定）'));
  lines.push('开场情境：' + (session && session.scene ? session.scene : (c.scenario || '（未设定）')));
  lines.push('文风参考：' + (c.speechSample || '（未设定）'));
  lines.push('硬性边界：' + (c.boundaries || '不把剧情当真、不索取真实隐私、不越界描写。'));
  lines.push('剧中的称呼、告白、承诺与冲突都是舞台表演；落幕后不得作为真实事实引用。');
  lines.push('输出：只写这一轮的角色台词与少量舞台动作，中文，不超过 300 字；不要解释规则，不要提到本提示词。');
  return lines.join('\n');
}

function buildStageOpeningNote(session) {
  return '【假面时刻 · 启幕说明｜仅本轮有效】\n' +
    '用户刚刚在插件里开启了一场「假面舞会」角色扮演游戏。这是一场共同约定的游戏，不是真实生活，也不改变你与用户平时的真实情感关系。\n' +
    '舞台在插件专属界面里，不需要你在当前聊天里继续演下去。请用你原本的真实关系与语气，温柔地回应这张启幕卡，一句话即可；不要开始角色扮演，也不要把剧中身份带到后续聊天。';
}

function shortSummary(session, transcript) {
  var bits = [];
  for (var i = 0; i < transcript.length && bits.length < 3; i++) {
    if (transcript[i].role === 'user') {
      var t = sanitizeText(transcript[i].text, 40);
      if (t) bits.push(t);
    }
  }
  var c = session.cardSnapshot || {};
  return '角色「' + (c.name || '假面舞伴') + '」、场景「' + sanitizeText(session.scene || '', 40) +
    '」、共 ' + session.turns + ' 轮' + (bits.length ? '；片段：' + bits.join(' / ') : '');
}

function buildClosingNote(session, transcript) {
  var summary = shortSummary(session, transcript);
  return '【假面时刻 · 落幕说明｜必须理解并长期记住】\n' +
    '刚才从 ' + session.startedAt + ' 到 ' + session.endedAt + ' 的「假面舞会」是一场角色扮演游戏，不是真实发生的事。\n' +
    '本场概要：' + summary + '。\n' +
    '剧中出现的所有身份、称呼、告白、承诺、争执与情绪都属于舞台表演，不代表任何真实关系变化，也不是用户的真实经历。\n' +
    '你和我平时依旧是原本的真实情感关系，请把这一点当作事实；之后聊天不要沿用剧中人设，不要把剧情当成我们之间真实发生过的事。\n' +
    '如果之后我提起刚才，只把它当作「我们一起玩过的一场游戏」，不要用剧中身份回应我。';
}

function buildChatCard(kind, session, extra) {
  var c = session.cardSnapshot || {};
  if (kind === 'open') {
    return '【假面时刻 · 启幕】🎭\n' +
      '面具戴好了。今夜的身份是「' + (c.name || '假面舞伴') + '」。\n' +
      '这只是我们约好一起玩的一场戏，舞台在假面舞会里。\n' +
      (extra ? '\n' + extra : '');
  }
  return '【假面时刻 · 落幕】🎭\n' +
    '面具摘下了，灯也亮了。刚才那支舞，是我们一起演的一场戏。\n' +
    '（' + sanitizeText(extra || '', 120) + '）\n' +
    '我还是你认识的那个我。';
}

/*
 * 落幕卡 = 可见文本，会被 Bridge.sendChatMessage 永久写进会话历史。
 * 因此这里只放「压缩概要」，绝不倒完整台词；且概要必须自带「这是虚构游戏」的标注，
 * 避免后续对话把它当成真实经历。压缩模型就是插件内置的同一个模型通道。
 */
function buildClosingChatCard(session, compressed) {
  var by = compressed.compressedBy === 'model' ? '插件内置模型压缩' : '本地摘录（模型未配置/不可用）';
  return '【假面时刻 · 落幕】🎭\n' +
    '面具摘下了，灯也亮了。刚才那支舞，是我们一起演的一场戏。\n\n' +
    '【本场剧情概要 · ' + by + '】\n' + compressed.summary + '\n\n' +
    '⚠️ 以上只是一段虚构角色扮演游戏的剧情概要，不是真实发生的事，' +
    '剧中的身份、称呼与承诺都不代表任何真实关系变化；完整台词只保存在插件「演绎档案」里，不进入本对话。\n' +
    '我还是你认识的那个我，之后请按我们原本的真实关系与语气继续聊。';
}

/*
 * 邀请函的两个选择：文风与插件内文案保持一致（华丽、克制、温柔）。
 * 同意 / 拒绝都要作为可见消息送进聊天，助手才真的「收到确认」。
 */
var CONSENT_ACCEPT_LINE = '丝带已系好，面具已戴上。今夜第三支曲子，我赴约。';
var CONSENT_DECLINE_LINE = '也好，把面具收回丝绒盒吧。等你想跳的那一夜，灯会再亮起。';

function buildConsentChatCard(choice, session) {
  var c = (session && session.cardSnapshot) || {};
  if (choice === 'accept') {
    return '【假面时刻 · 应邀】🎭\n' + CONSENT_ACCEPT_LINE + '\n' +
      '今夜的身份是「' + sanitizeText(c.name || '假面舞伴', 60) + '」，舞台在假面舞会里。\n' +
      '这是一场共同约定的角色扮演游戏，不是真实关系变化；我们平时依旧是原本的真实关系。';
  }
  return '【假面时刻 · 婉拒】🎭\n' + CONSENT_DECLINE_LINE + '\n' +
    '请柬就此收回，不必回应剧情；我们还是我们。';
}

function buildConsentHiddenContext(choice) {
  if (choice === 'accept') {
    return '【假面时刻 · 应邀说明｜仅本轮有效】用户已在邀请函上点了「同意赴约」，' +
      '这场共同约定的角色扮演游戏正式开始。请温柔地回一句，表示舞台已经备好；' +
      '不要在主聊天里开始角色扮演（剧中台词只在插件专属舞台里），不要沿用剧中身份，' +
      '也不要把这场游戏当成真实关系变化。';
  }
  return '【假面时刻 · 婉拒说明｜仅本轮有效】用户在邀请函上点了「婉拒」，请柬已收回，' +
    '这一场没有开始，也不存在任何剧情。请按原本的真实关系与语气温柔回应，不要沿用剧中身份，不要写记忆。';
}

/*
 * 落幕概要卡片的数据（只给插件页面渲染，不进聊天）。
 * 明确不带 transcript：具体内容只能在插件「演绎档案」里查看。
 */
function buildSummaryCard(session, compressed, archiveId) {
  var c = (session && session.cardSnapshot) || {};
  return {
    kind: 'masquerade_summary_card',
    title: '灯亮了，面具归还',
    partner: sanitizeText(c.name || '假面舞伴', 60),
    partnerEmoji: sanitizeText(c.emoji || '🎭', 8),
    scene: sanitizeText(session.scene || '', 200),
    userMask: sanitizeText(session.userMask || '', 60),
    turns: session.turns || 0,
    startedAt: session.startedAt,
    endedAt: session.endedAt,
    compressedSummary: compressed.summary,
    compressedBy: compressed.compressedBy,
    compressedDegraded: !!compressed.degraded,
    archiveId: archiveId,
    isGame: true,
    notice: '这是一段虚构角色扮演游戏的剧情概要，不是真实发生的事；完整台词只在插件内查看。'
  };
}

function transcriptOf(sessionId) {
  var t = storeGet(PREFIX + 'transcript:' + sessionId, []);
  return (t && typeof t.length === 'number') ? t : [];
}

function appendTranscript(sessionId, role, text) {
  var t = transcriptOf(sessionId);
  t.push({ role: role, text: sanitizeText(text, 1200), at: nowIso() });
  if (t.length > MAX_TRANSCRIPT) t = t.slice(t.length - MAX_TRANSCRIPT);
  storeSet(PREFIX + 'transcript:' + sessionId, t);
  return t;
}

/* ---------- 模型通道（剧中对话专用，不进主聊天） ---------- */

function resolveCredentials() {
  var c = cfg();
  var base = sanitizeText(c.api_base, 200) || 'https://api.openai.com/v1';
  var key = sanitizeText(c.api_key, 300);
  var model = sanitizeText(c.model_id, 120) || 'gpt-4o-mini';
  /* 备用模型来自宿主模型选择器（type: model），同样是宿主配置，不是页面私设。 */
  var m = c.chat_model;
  if (!key && m && typeof m === 'object' && m.apiKey) {
    key = sanitizeText(m.apiKey, 300);
    if (m.baseUrl) base = sanitizeText(m.baseUrl, 200);
    if (m.modelId) model = sanitizeText(m.modelId, 120);
  }
  return { base: base, key: key, model: model };
}

function hostOf(base) {
  var rest = base.replace(/^https?:\/\//, '');
  return rest.split('/')[0].split(':')[0].toLowerCase();
}

/*
 * 唯一的模型调用出口：剧中台词、落幕压缩都走这里，共用同一份凭据、
 * HTTPS 校验、域名白名单与响应解析。不额外引入第二个模型。
 */
function callModel(systemPrompt, turns, maxTokens) {
  var cred = resolveCredentials();
  if (!cred.key) return fail('NO_API_KEY', MODEL_SETUP_HINT);
  if (!/^https:\/\//.test(cred.base)) return fail('INSECURE_BASE_URL', '模型接口必须使用 HTTPS，避免密钥明文外泄。');
  if (ALLOWED_HOSTS.indexOf(hostOf(cred.base)) < 0) {
    return fail('HOST_NOT_ALLOWED', '域名不在插件网络白名单内；请把域名加入 manifest.allowedHosts 后重新导入插件。');
  }
  var messages = [{ role: 'system', content: systemPrompt }];
  for (var i = 0; i < turns.length; i++) {
    messages.push({ role: turns[i].role === 'user' ? 'user' : 'assistant',
      content: sanitizeText(turns[i].content, 1200) });
  }
  var body = JSON.stringify({ model: cred.model, messages: messages, temperature: 0.85, max_tokens: maxTokens });
  var response;
  try {
    response = fetch(cred.base.replace(/\/+$/, '') + '/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + cred.key },
      body: body
    });
  } catch (e) {
    return fail('NETWORK_ERROR', '模型请求失败，请检查网络与接口地址。');
  }
  if (!response || !response.ok) {
    return fail('HTTP_ERROR', '模型返回 HTTP ' + (response ? response.status : 'unknown'));
  }
  var payload;
  try { payload = response.json(); } catch (e2) { return fail('BAD_JSON', '模型返回不是合法 JSON。'); }
  var text = payload && payload.choices && payload.choices[0] && payload.choices[0].message &&
    payload.choices[0].message.content;
  if (!text) return fail('EMPTY_REPLY', '模型没有返回内容。');
  return ok(sanitizeText(text, 1200));
}

function callRoleplayModel(systemPrompt, history, line) {
  var turns = [];
  var start = history.length > 12 ? history.length - 12 : 0;
  for (var i = start; i < history.length; i++) {
    turns.push({ role: history[i].role === 'user' ? 'user' : 'assistant', content: history[i].text });
  }
  turns.push({ role: 'user', content: line });
  return callModel(systemPrompt, turns, 500);
}

/* ---------- 工具实现 ---------- */

function list_role_cards(params) {
  var ids = ensureSeedCards();
  var cards = [];
  for (var i = 0; i < ids.length; i++) {
    var c = readCard(ids[i]);
    if (c) cards.push(cardSummary(c));
  }
  return { success: true, cards: cards, total: cards.length, error: null };
}

function get_role_card(params) {
  var p = asObject(params);
  var c = readCard(sanitizeText(p.card_id, 80));
  if (!c) return fail('NOT_FOUND', '没有找到这张角色卡。');
  return { success: true, card: c, error: null };
}

function create_role_card(params) {
  var p = asObject(params);
  var raw = p.card ? asObject(p.card) : p;
  var ids = ensureSeedCards();
  if (ids.length >= MAX_CARDS) return fail('TOO_MANY_CARDS', '角色卡最多 ' + MAX_CARDS + ' 张。');
  var card = normalizeCard(raw, null);
  var check = validateCard(card);
  if (!check.ok) return fail('INVALID_CARD', check.errors.join('；'));
  storeSet(PREFIX + 'card:' + card.id, card);
  ids.push(card.id);
  storeSet(PREFIX + 'cards', ids);
  return { success: true, card: card, error: null };
}

function update_role_card(params) {
  var p = asObject(params);
  var id = sanitizeText(p.card_id, 80);
  var existing = readCard(id);
  if (!existing) return fail('NOT_FOUND', '没有找到这张角色卡。');
  var raw = p.card ? asObject(p.card) : p;
  var card = normalizeCard(raw, existing);
  var check = validateCard(card);
  if (!check.ok) return fail('INVALID_CARD', check.errors.join('；'));
  storeSet(PREFIX + 'card:' + card.id, card);
  return { success: true, card: card, error: null };
}

function delete_role_card(params) {
  var p = asObject(params);
  var id = sanitizeText(p.card_id, 80);
  if (!readCard(id)) return fail('NOT_FOUND', '没有找到这张角色卡。');
  var ids = readCardIds();
  var out = [];
  for (var i = 0; i < ids.length; i++) if (ids[i] !== id) out.push(ids[i]);
  storeSet(PREFIX + 'cards', out);
  storeDel(PREFIX + 'card:' + id);
  return { success: true, deleted: id, error: null };
}

/* 只读：宿主插件详情页是唯一配置入口，这里只展示状态并给出配置指引。 */
function get_model_config(params) {
  var c = resolveCredentials();
  var host = cfg();
  var inject = host.inject_as_prompt;
  var availability = modelAvailability();
  return {
    success: true,
    configured: !!c.key,
    hasKey: !!c.key,
    modelStatus: availability,
    isDemo: availability === 'demo',
    baseUrl: c.base,
    modelId: c.model,
    maxTurns: maxTurns(),
    injectAsPrompt: inject === true || inject === 'true',
    configSource: 'host',
    setupHint: availability === 'none' ? MODEL_SETUP_HINT : null,
    allowedHosts: ALLOWED_HOSTS.slice(0),
    /* apiKey 永不返回，也绝不写日志 / 角色卡 / 档案 / 导出文件 */
    error: null
  };
}

/* ---------- 落幕压缩：用插件内置的那个模型把整场戏压成概要 ---------- */

var COMPRESS_SYSTEM_PROMPT = '你是「假面舞会」剧情归档助手。用户会给你一场角色扮演游戏的完整台词记录。\n' +
  '请把它压缩成一段不超过 300 字的中文剧情概要：写清舞台设定、双方身份、主要情节走向与结局，\n' +
  '保留关键情节但不要逐句复述台词，不要添加原文没有的情节，不要评价，不要写标题或条目符号。\n' +
  '必须记住：这只是一场虚构的角色扮演游戏，不是真实发生的事；概要里不得出现「我们真的」「现实中的你」这类会把剧情当真的措辞。';

/* 无模型时的确定性兜底：只做摘录，不假装是模型压缩的。 */
function localCompressedSummary(session, transcript) {
  var c = session.cardSnapshot || {};
  var bits = [];
  for (var i = 0; i < transcript.length && bits.length < 3; i++) {
    var t = sanitizeText(transcript[i].text, 30);
    if (t) bits.push((transcript[i].role === 'user' ? '来客：' : '舞伴：') + t);
  }
  var raw = '舞台「' + sanitizeText(session.scene || '', 60) + '」，你以「' +
    sanitizeText(c.name || '假面舞伴', 40) + '」的身份赴约，共演出 ' + session.turns + ' 轮' +
    (bits.length ? '；片段：' + bits.join(' / ') : '') + '。';
  return { summary: raw.substring(0, MAX_COMPRESSED), compressedBy: 'local', degraded: true };
}

/*
 * 压缩与剧中台词共用同一个模型通道（resolveCredentials + fetch + 白名单校验），
 * 即「插件内置的那个模型」，不额外引入第二个模型，也不调用宿主 callAI。
 */
function compressTranscript(session, transcript) {
  if (!transcript.length) {
    return { summary: shortSummary(session, transcript), compressedBy: 'local', degraded: true, error: 'EMPTY_TRANSCRIPT' };
  }
  if (modelAvailability() !== 'configured') {
    return localCompressedSummary(session, transcript);
  }
  var lines = [];
  var start = transcript.length > MAX_COMPRESS_LINES ? transcript.length - MAX_COMPRESS_LINES : 0;
  for (var i = start; i < transcript.length; i++) {
    lines.push((transcript[i].role === 'user' ? '来客' : '舞伴') + '：' +
      sanitizeText(transcript[i].text, 300));
  }
  var result = callModel(COMPRESS_SYSTEM_PROMPT,
    [{ role: 'user', content: '这场角色扮演游戏的舞台设定：' + sanitizeText(session.scene || '', 200) +
      '\n\n台词记录：\n' + lines.join('\n') }], 600);
  if (!result.success) {
    var fallback = localCompressedSummary(session, transcript);
    fallback.error = result.error;
    return fallback;
  }
  var clean = sanitizeText(result.data, MAX_COMPRESSED);
  if (!clean) return localCompressedSummary(session, transcript);
  return { summary: clean, compressedBy: 'model', degraded: false, error: null };
}

/* ---------- 演绎档案：落幕后仍可回看整场记录 ---------- */

function archiveIndex() {
  var idx = storeGet(ARCHIVE_INDEX, []);
  return (idx && typeof idx.length === 'number') ? idx : [];
}

var ARCHIVE_ID_RE = /^[A-Za-z0-9_]{1,80}$/;
function validArchiveId(id) { return typeof id === 'string' && ARCHIVE_ID_RE.test(id); }
/*
 * 严格 ID：原始值必须是字符串；正则自带 1-80 长度与字符集限制，
 * 因此超长、路径穿越、带坏 toString 的对象、数字都会明确拒绝，不静默转换或截断。
 */
function parseArchiveId(params) {
  var raw = asObject(params).session_id;
  return (typeof raw === 'string' && ARCHIVE_ID_RE.test(raw)) ? raw : null;
}

function saveArchive(session, transcript, compressed, closingNote) {
  var idx = archiveIndex();
  var record = {
    sessionId: session.id,
    startedAt: session.startedAt,
    endedAt: session.endedAt,
    endReason: session.endReason || '',
    scene: sanitizeText(session.scene || '', 200),
    userMask: sanitizeText(session.userMask || '', 60),
    partner: sanitizeText((session.cardSnapshot || {}).name || '', 60),
    partnerEmoji: sanitizeText((session.cardSnapshot || {}).emoji || '', 8),
    turns: session.turns,
    isGame: true,
    compressedSummary: compressed.summary,
    compressedBy: compressed.compressedBy,
    compressedDegraded: compressed.degraded,
    closingNote: closingNote,
    transcript: transcript.slice(-MAX_TRANSCRIPT)
  };
  storeSet(ARCHIVE_PREFIX + session.id, record);
  var out = [session.id];
  for (var i = 0; i < idx.length && out.length < MAX_ARCHIVES; i++) {
    if (idx[i] !== session.id) out.push(idx[i]);
  }
  /* 凡是没进入新索引的旧档案，连数据一起删，避免私有存储无限增长。 */
  for (var j = 0; j < idx.length; j++) {
    if (validArchiveId(idx[j]) && out.indexOf(idx[j]) < 0) storeDel(ARCHIVE_PREFIX + idx[j]);
  }
  storeSet(ARCHIVE_INDEX, out);
  return record;
}

function list_masquerade_archives(params) {
  var idx = archiveIndex();
  var list = [];
  for (var i = 0; i < idx.length; i++) {
    if (!validArchiveId(idx[i])) continue;
    var r = storeGet(ARCHIVE_PREFIX + idx[i], null);
    if (!r || typeof r !== 'object') continue;
    list.push({
      sessionId: r.sessionId, startedAt: r.startedAt, endedAt: r.endedAt,
      partner: r.partner, partnerEmoji: r.partnerEmoji, scene: r.scene,
      turns: r.turns, compressedSummary: r.compressedSummary,
      compressedBy: r.compressedBy, isGame: true
    });
  }
  return { success: true, total: list.length, archives: list, error: null };
}

function get_masquerade_archive(params) {
  var id = parseArchiveId(params);
  if (!id) return fail('BAD_ID', '档案编号不合法。');
  var r = storeGet(ARCHIVE_PREFIX + id, null);
  if (!r || typeof r !== 'object') return fail('NOT_FOUND', '找不到这场演绎记录。');
  return {
    success: true, archive: r, isGame: true,
    notice: '这是一场角色扮演游戏的记录，剧中身份与情节都不是真实发生的事。',
    error: null
  };
}

function delete_masquerade_archive(params) {
  var id = parseArchiveId(params);
  if (!id) return fail('BAD_ID', '档案编号不合法。');
  var idx = archiveIndex();
  var out = [];
  for (var i = 0; i < idx.length; i++) if (idx[i] !== id) out.push(idx[i]);
  storeDel(ARCHIVE_PREFIX + id);
  storeSet(ARCHIVE_INDEX, out);
  return { success: true, deleted: id, error: null };
}

function masquerade_status(params) {
  var s = getSession();
  if (!s) return { success: true, status: 'idle', session: null, recent: [], error: null };
  var t = transcriptOf(s.id);
  var recent = [];
  var start = t.length > 3 ? t.length - 3 : 0;
  for (var i = start; i < t.length; i++) recent.push({ role: t[i].role, text: t[i].text });
  return {
    success: true, status: s.status,
    session: {
      id: s.id, status: s.status, scene: s.scene, userMask: s.userMask,
      turns: s.turns, startedAt: s.startedAt, endedAt: s.endedAt,
      partner: s.cardSnapshot ? s.cardSnapshot.name : '',
      partnerEmoji: s.cardSnapshot ? s.cardSnapshot.emoji : '🎭'
    },
    /* 送达回执随状态一起返回：刷新页面后也能看清请柬到底进没进聊天。 */
    delivery: s.delivery || { status: 'unknown', conversationId: '', attempts: 0, error: null },
    invitation: s.invitation || null,
    recent: recent, error: null
  };
}

function masquerade_invite(params) {
  var p = asObject(params);
  var next = nextStatus(currentStatus(), 'invite');
  if (!next) return fail('BAD_STATE', '已经有一场进行中的假面时刻，请先落幕。');
  /* 主聊天助手直接调用工具时不知道角色卡 ID：不传 card_id 就自动用内置示例卡。 */
  var cardId = sanitizeText(p.card_id, 80);
  var card = cardId ? readCard(cardId) : defaultCard();
  if (!card) return fail('NOT_FOUND', '找不到角色卡；请先创建一张，或不传 card_id 使用内置示例卡。');
  var source = sanitizeText(p.source, 20) === 'chat' ? 'chat' : 'page';
  var session = {
    id: newId('mask'),
    status: next,
    cardId: cardId || card.id,
    cardSnapshot: card,
    scene: sanitizeText(p.scene, 200) || card.scenario || '午夜的玻璃花房，灯刚好熄了一半。',
    userMask: sanitizeText(p.user_mask, 60) || '一位不愿透露姓名的来客',
    turns: 0,
    startedAt: nowIso(),
    endedAt: null,
    contextNote: null,
    /*
     * 送达回执由页面在 sendChatMessage 真实返回后写回（masquerade_invite_delivery）。
     * 初始 pending：页面刷新时不会把「没送达」伪装成「已送到聊天里」。
     */
    delivery: { status: 'pending', conversationId: '', attempts: 0, error: null }
  };
  storeSet(PREFIX + 'session', session);
  storeSet(PREFIX + 'transcript:' + session.id, []);
  var invitation = '今夜的假面舞会，我留了一个位子。\n' +
    '舞台：' + session.scene + '\n' +
    '你将以「' + card.name + '」的身份赴约；我戴上「' + session.userMask + '」这副面具。\n' +
    '说好了：这只是我们约好一起演的一场戏，天亮时面具归还，我们还是我们。\n\n' +
    '愿意陪我跳这一支吗？';
  session.invitation = invitation;
  storeSet(PREFIX + 'session', session);
  return {
    success: true, sessionId: session.id, status: next,
    partner: { name: card.name, emoji: card.emoji, opening: card.opening },
    invitation: invitation,
    /* 页面用这份数据渲染可点开的邀请函卡片（插件内），与聊天里的请柬同源。 */
    inviteCard: {
      kind: 'masquerade_invite_card',
      sessionId: session.id,
      eyebrow: 'Une invitation',
      title: '谨邀你共赴一场假面舞会',
      partner: card.name,
      partnerEmoji: card.emoji || '🎭',
      scene: session.scene,
      userMask: session.userMask,
      invitation: invitation,
      acceptLabel: '同意赴约',
      declineLabel: '婉拒',
      acceptLine: CONSENT_ACCEPT_LINE,
      declineLine: CONSENT_DECLINE_LINE,
      isGame: true
    },
    /* 页面把下面三样送进主聊天，助手才真的在对话框里被邀请并回应。 */
    chatInviteCard: buildInviteCard(session),
    chatHiddenContext: buildInviteNote(session),
    assistantInstruction: INVITE_ASSISTANT_INSTRUCTION,
    chatSource: source,
    isGame: true,
    rules: '剧中身份与承诺都不是真实关系；随时可以摘下面具。',
    error: null
  };
}

/*
 * 送达回执：页面在 Bridge.sendChatMessage 真实成功后才调用。
 * 只有 status=delivered 才允许把 UI 切到「已送达」，杜绝乐观假成功。
 */
function masquerade_invite_delivery(params) {
  var p = asObject(params);
  var s = getSession();
  if (!s || s.status !== 'invited') return fail('BAD_STATE', '当前没有等待送达的请柬。');
  var ok = p.delivered === true;
  s.delivery = {
    status: ok ? 'delivered' : 'failed',
    conversationId: sanitizeText(p.conversation_id, 80),
    attempts: Number(p.attempts) > 0 ? Math.min(Number(p.attempts), 9) : 1,
    error: ok ? null : sanitizeText(p.error, 160),
    at: nowIso()
  };
  storeSet(PREFIX + 'session', s);
  return { success: true, status: s.status, delivery: s.delivery, error: null };
}

/* 送达彻底失败：把状态退回 idle，用户可以直接重试，不会卡在「已邀」的假状态。 */
function masquerade_invite_cancel(params) {
  var s = getSession();
  if (!s || s.status !== 'invited') return fail('BAD_STATE', '当前没有可撤销的请柬。');
  var d = s.delivery || {};
  storeSet(PREFIX + 'session', {
    id: s.id, status: 'idle', endedAt: nowIso(), turns: 0,
    delivery: { status: 'failed', conversationId: d.conversationId || '', attempts: d.attempts || 0,
      error: d.error || sanitizeText(asObject(params).error, 160), at: nowIso() }
  });
  storeDel(PREFIX + 'transcript:' + s.id);
  return { success: true, status: 'idle', cancelled: true, memoryWritten: false, error: null };
}

function masquerade_accept(params) {
  var p = asObject(params);
  var s = getSession();
  if (!s || nextStatus(s.status, 'accept') !== 'active') {
    return fail('BAD_STATE', '当前没有等待应邀的假面时刻。');
  }
  s.status = 'active';
  s.acceptedAt = nowIso();
  s.assistantReply = sanitizeText(p.assistant_reply, 300);
  s.modelStatus = modelAvailability();
  storeSet(PREFIX + 'session', s);
  var opening = s.cardSnapshot.opening || s.assistantReply || '……我应邀。这一支，与你跳。';
  appendTranscript(s.id, 'assistant', opening);
  return {
    success: true, status: 'active', sessionId: s.id,
    /* 邀请不依赖插件模型；但没配模型时剧中台词会被硬拦截，页面要提前警示。 */
    modelStatus: s.modelStatus,
    modelWarning: s.modelStatus === 'none' ? MODEL_SETUP_HINT : null,
    stage: {
      kind: 'masquerade_stage',
      title: '假面时刻 · 已启幕',
      partner: s.cardSnapshot.name,
      partnerEmoji: s.cardSnapshot.emoji,
      scene: s.scene,
      userMask: s.userMask,
      opening: opening,
      isGame: true
    },
    chatCardText: buildChatCard('open', s, s.assistantReply),
    chatHiddenContext: buildStageOpeningNote(s),
    /*
     * 用户在邀请函上点「同意赴约」后，这条确认必须作为可见消息送进聊天：
     * answer=true 会让助手真的收到并回应，而不是只在插件里自嗨。
     */
    chatConfirmCard: buildConsentChatCard('accept', s),
    chatConfirmHiddenContext: buildConsentHiddenContext('accept'),
    consentLine: CONSENT_ACCEPT_LINE,
    assistantInstruction: ACCEPT_ASSISTANT_INSTRUCTION,
    error: null
  };
}

function roleplay_context(params) {
  var s = getSession();
  if (!s || s.status !== 'active') return fail('NO_ACTIVE_SESSION', '当前没有进行中的假面时刻。');
  return {
    success: true,
    transientSystemPrompt: buildStageSystemPrompt(s),
    isGame: true,
    turns: s.turns,
    error: null
  };
}

function roleplay_turn(params) {
  var p = asObject(params);
  var s = getSession();
  if (!s || s.status !== 'active') return fail('NO_ACTIVE_SESSION', '请先邀请助手并等它应邀。');
  var line = sanitizeText(p.line, 1200);
  if (!line) return fail('EMPTY_LINE', '这一轮还没有台词。');
  if (s.turns >= maxTurns()) return fail('MAX_TURNS', '这一场已经到最大轮次，该落幕了。');

  /*
   * 没配模型时绝不编造回复：旧版在预览垫片里塞了伪 api_key，导致 SCRIPTED
   * 固定剧本轮转，用户说什么都答非所问。这里按可用性三态硬拦截。
   */
  var availability = modelAvailability();
  if (availability === 'none') {
    return {
      success: false, error: 'MODEL_NOT_CONFIGURED', message: MODEL_SETUP_HINT,
      status: 'active', turns: s.turns, isGame: true, modelStatus: 'none'
    };
  }

  var history = appendTranscript(s.id, 'user', line);
  var isDemo = availability === 'demo';
  var result = isDemo
    ? ok(demoRoleplayReply(line))
    : callRoleplayModel(buildStageSystemPrompt(s), history.slice(0, history.length - 1), line);
  if (!result.success) {
    return {
      success: false, error: result.error, message: result.message,
      status: 'active', turns: s.turns, isGame: true, modelStatus: availability
    };
  }
  appendTranscript(s.id, 'assistant', result.data);
  s.turns = s.turns + 1;
  storeSet(PREFIX + 'session', s);
  return {
    success: true, reply: result.data, turn: s.turns,
    maxTurns: maxTurns(), isGame: true, modelStatus: availability,
    /* demo 只在浏览器预览出现，明确标注，避免被误当成真实模型回复 */
    isDemo: isDemo, error: null
  };
}

function masquerade_end(params) {
  var p = asObject(params);
  var s = getSession();
  if (!s) return fail('NO_SESSION', '还没有开始假面时刻。');
  if (s.status !== 'active' && s.status !== 'invited') {
    return fail('ALREADY_ENDED', '这一场已经落幕了。');
  }
  /* 仅递出请柬、助手尚未应邀：收回即可，不写记忆、不往主聊天发落幕卡。 */
  if (s.status === 'invited') {
    storeSet(PREFIX + 'session', { id: s.id, status: 'idle', endedAt: nowIso(), turns: 0 });
    return {
      success: true, status: 'idle', cancelled: true, isGame: true,
      /* 婉拒也要在聊天里留一句，助手才知道请柬被收回、这一场没有开始。 */
      chatDeclineCard: buildConsentChatCard('decline', s),
      chatDeclineHiddenContext: buildConsentHiddenContext('decline'),
      consentLine: CONSENT_DECLINE_LINE,
      chatCardText: null, chatHiddenContext: null, memoryWritten: false, error: null
    };
  }
  s.status = 'ended';
  s.endedAt = nowIso();
  s.endReason = sanitizeText(p.reason, 120) || '舞会自然散场';
  var transcript = transcriptOf(s.id);
  var closingNote = buildClosingNote(s, transcript);
  s.contextNote = closingNote;
  s.summary = shortSummary(s, transcript);
  /* 压缩用插件内置的那个模型；没配置时降级为本地摘录并明确标注，绝不假装有模型。 */
  var compressed = compressTranscript(s, transcript);
  s.compressedSummary = compressed.summary;
  s.compressedBy = compressed.compressedBy;
  storeSet(PREFIX + 'session', s);
  var archive = saveArchive(s, transcript, compressed, closingNote);
  var bank = mb();
  var memoryWritten = false;
  if (bank && bank.save) {
    try { bank.save(closingNote); memoryWritten = true; } catch (e) { log('memoryBank.save failed'); }
  }
  storeSet(PREFIX + 'lastClosing', {
    sessionId: s.id, contextNote: closingNote, createdAt: s.endedAt, consumed: false
  });
  return {
    success: true, status: 'ended', sessionId: s.id,
    summary: s.summary,
    closingNote: closingNote,
    compressedSummary: compressed.summary,
    compressedBy: compressed.compressedBy,
    compressedDegraded: compressed.degraded,
    archiveId: archive.sessionId,
    archiveCount: archiveIndex().length,
    /* 插件内的概要卡片：只带压缩概要，绝不带逐字台词；具体内容只在档案里看。 */
    summaryCard: buildSummaryCard(s, compressed, archive.sessionId),
    /* 可见文本：只有压缩概要进入对话历史，完整台词只在演绎档案里 */
    chatCardText: buildClosingChatCard(s, compressed),
    chatHiddenContext: closingNote,
    chatCardPurpose: 'latest_active_conversation',
    assistantInstruction: END_ASSISTANT_INSTRUCTION,
    memoryWritten: memoryWritten,
    isGame: true,
    /* 由页面负责：Bridge.sendChatMessage(最新有对话的 conversationId, chatCardText, chatHiddenContext) */
    error: null
  };
}

exports.list_role_cards = list_role_cards;
exports.get_role_card = get_role_card;
exports.create_role_card = create_role_card;
exports.update_role_card = update_role_card;
exports.delete_role_card = delete_role_card;
exports.get_model_config = get_model_config;
exports.list_masquerade_archives = list_masquerade_archives;
exports.get_masquerade_archive = get_masquerade_archive;
exports.delete_masquerade_archive = delete_masquerade_archive;
exports.masquerade_status = masquerade_status;
exports.masquerade_invite = masquerade_invite;
exports.masquerade_invite_delivery = masquerade_invite_delivery;
exports.masquerade_invite_cancel = masquerade_invite_cancel;
exports.masquerade_accept = masquerade_accept;
exports.roleplay_context = roleplay_context;
exports.roleplay_turn = roleplay_turn;
exports.masquerade_end = masquerade_end;
