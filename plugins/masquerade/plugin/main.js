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

var idSeq = 0;

function cfg() { return (typeof config === 'undefined' || !config) ? {} : config; }
function ds() { return (typeof dataStore === 'undefined' || !dataStore) ? null : dataStore; }
function mb() { return (typeof memoryBank === 'undefined' || !memoryBank) ? null : memoryBank; }
function log(msg) { if (typeof console !== 'undefined' && console.warn) console.warn('[masquerade] ' + msg); }

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

function callRoleplayModel(systemPrompt, history, line) {
  var cred = resolveCredentials();
  if (!cred.key) return fail('NO_API_KEY', '尚未接入模型：请在「模型接入」里填写 API Key，或选择宿主的备用模型。');
  if (!/^https:\/\//.test(cred.base)) return fail('INSECURE_BASE_URL', '模型接口必须使用 HTTPS，避免密钥明文外泄。');
  if (ALLOWED_HOSTS.indexOf(hostOf(cred.base)) < 0) {
    return fail('HOST_NOT_ALLOWED', '域名不在插件网络白名单内；请把域名加入 manifest.allowedHosts 后重新导入插件。');
  }
  var messages = [{ role: 'system', content: systemPrompt }];
  var start = history.length > 12 ? history.length - 12 : 0;
  for (var i = start; i < history.length; i++) {
    messages.push({ role: history[i].role === 'user' ? 'user' : 'assistant', content: history[i].text });
  }
  messages.push({ role: 'user', content: sanitizeText(line, 1200) });
  var body = JSON.stringify({ model: cred.model, messages: messages, temperature: 0.85, max_tokens: 500 });
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

function get_model_config(params) {
  var c = resolveCredentials();
  return {
    success: true,
    configured: !!c.key,
    hasKey: !!c.key,
    baseUrl: c.base,
    modelId: c.model,
    allowedHosts: ALLOWED_HOSTS.slice(0),
    /* apiKey 永不返回 */
    error: null
  };
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
    recent: recent, error: null
  };
}

function masquerade_invite(params) {
  var p = asObject(params);
  var next = nextStatus(currentStatus(), 'invite');
  if (!next) return fail('BAD_STATE', '已经有一场进行中的假面时刻，请先落幕。');
  var cardId = sanitizeText(p.card_id, 80);
  var card = readCard(cardId);
  if (!card) return fail('NOT_FOUND', '请先选择或创建一张角色卡。');
  var session = {
    id: newId('mask'),
    status: next,
    cardId: cardId,
    cardSnapshot: card,
    scene: sanitizeText(p.scene, 200) || card.scenario || '午夜的玻璃花房，灯刚好熄了一半。',
    userMask: sanitizeText(p.user_mask, 60) || '一位不愿透露姓名的来客',
    turns: 0,
    startedAt: nowIso(),
    endedAt: null,
    contextNote: null
  };
  storeSet(PREFIX + 'session', session);
  storeSet(PREFIX + 'transcript:' + session.id, []);
  var invitation = '今夜的假面舞会，我留了一个位子。\n' +
    '舞台：' + session.scene + '\n' +
    '你将以「' + card.name + '」的身份赴约；我戴上「' + session.userMask + '」这副面具。\n' +
    '说好了：这只是我们约好一起演的一场戏，天亮时面具归还，我们还是我们。\n\n' +
    '愿意陪我跳这一支吗？';
  return {
    success: true, sessionId: session.id, status: next,
    partner: { name: card.name, emoji: card.emoji, opening: card.opening },
    invitation: invitation,
    rules: '剧中身份与承诺都不是真实关系；随时可以摘下面具。',
    error: null
  };
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
  storeSet(PREFIX + 'session', s);
  var opening = s.cardSnapshot.opening || s.assistantReply || '……我应邀。这一支，与你跳。';
  appendTranscript(s.id, 'assistant', opening);
  return {
    success: true, status: 'active', sessionId: s.id,
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
  var history = appendTranscript(s.id, 'user', line);
  var result = callRoleplayModel(buildStageSystemPrompt(s), history.slice(0, history.length - 1), line);
  if (!result.success) {
    return {
      success: false, error: result.error, message: result.message,
      status: 'active', turns: s.turns, isGame: true
    };
  }
  appendTranscript(s.id, 'assistant', result.data);
  s.turns = s.turns + 1;
  storeSet(PREFIX + 'session', s);
  return {
    success: true, reply: result.data, turn: s.turns,
    maxTurns: maxTurns(), isGame: true, error: null
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
  storeSet(PREFIX + 'session', s);
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
    chatCardText: buildChatCard('close', s, s.summary),
    chatHiddenContext: closingNote,
    memoryWritten: memoryWritten,
    isGame: true,
    /* 由页面负责：Bridge.sendChatMessage(conversationId, chatCardText, chatHiddenContext) */
    error: null
  };
}

exports.list_role_cards = list_role_cards;
exports.get_role_card = get_role_card;
exports.create_role_card = create_role_card;
exports.update_role_card = update_role_card;
exports.delete_role_card = delete_role_card;
exports.get_model_config = get_model_config;
exports.masquerade_status = masquerade_status;
exports.masquerade_invite = masquerade_invite;
exports.masquerade_accept = masquerade_accept;
exports.roleplay_context = roleplay_context;
exports.roleplay_turn = roleplay_turn;
exports.masquerade_end = masquerade_end;
