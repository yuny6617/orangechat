#!/usr/bin/env node
/*
 * 假面舞会 · MCP server（stdio JSON-RPC 2.0，零第三方依赖）
 *
 * 与插件共用同一份 plugin/main.js 的状态机与落幕语义（vm 加载），
 * 差别只在「谁来演」：MCP 客户端的 LLM 本身就是舞伴，server 负责
 * 状态、角色卡、剧中提示词和落幕说明，不联网、不读取任何 API Key。
 *
 * 启动：node mcp/server.mjs
 * 客户端配置见 mcp/README.md
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAIN = path.join(ROOT, 'plugin', 'main.js');
const SERVER_INFO = { name: 'orangechat-masquerade', version: '1.0.0' };
const PROTOCOL = '2024-11-05';
const MAX_LINE = 1024 * 1024;

function resolveStoreFile() {
  const custom = process.env.MASQUERADE_STORE_FILE;
  const file = custom || path.join(os.homedir(), '.orangechat-masquerade', 'store.json');
  if (!path.isAbsolute(file) || file.includes('..')) {
    throw new Error('MASQUERADE_STORE_FILE must be an absolute path without ".."');
  }
  return file;
}

const STORE_FILE = resolveStoreFile();
let mem = {};
try {
  mem = JSON.parse(fs.readFileSync(STORE_FILE, 'utf8')) || {};
} catch (e) {
  mem = {};
}
if (!Array.isArray(mem.__memory)) mem.__memory = [];

function persist() {
  const dir = path.dirname(STORE_FILE);
  fs.mkdirSync(dir, { recursive: true });
  const tmp = STORE_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(mem), { mode: 0o600 });
  fs.renameSync(tmp, STORE_FILE);
}

const dataStore = {
  get: (k) => (Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null),
  set: (k, v) => { mem[k] = String(v); persist(); },
  del: (k) => { delete mem[k]; persist(); },
  list: (p) => Object.keys(mem).filter((k) => k.indexOf(p) === 0),
};
const memoryBank = {
  save: (t) => { mem.__memory.push(String(t)); persist(); },
  recall: () => mem.__memory.slice(-3),
  search: () => [],
  delete: () => {},
};

/* MCP 路径不代跑模型：任何意外的 fetch 都会显式失败，绝不静默外联。 */
const noNetworkFetch = () => ({
  ok: false, status: 0, body: '{}',
  json: () => ({}), text: () => '{}',
});

const sandbox = {
  exports: {},
  dataStore,
  config: { max_turns: 40, api_base: 'https://api.openai.com/v1', model_id: 'gpt-4o-mini' },
  memoryBank,
  fetch: noNetworkFetch,
  console: { warn() {}, log() {}, info() {}, error() {} },
};
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(MAIN, 'utf8'), sandbox, { filename: 'main.js' });

const api = Object.assign({}, sandbox.exports);

/* MCP 专属：记录用户台词，把剧中提示词交给客户端 LLM，由它来演。 */
api.roleplay_turn = function (params) {
  const p = params || {};
  const s = sandbox.storeGet('mb:session', null);
  if (!s || s.status !== 'active') return { success: false, error: 'NO_ACTIVE_SESSION' };
  const line = sandbox.sanitizeText(p.line, 1200);
  if (!line) return { success: false, error: 'EMPTY_LINE' };
  const transcript = sandbox.appendTranscript(s.id, 'user', line);
  return {
    success: true, turn: s.turns, isGame: true, reply: null,
    stagePrompt: sandbox.buildStageSystemPrompt(s),
    transcript: transcript.slice(-6),
    hint: 'MCP 客户端的 LLM 就是舞伴：请按 stagePrompt 以角色身份回复，随后调用 record_stage_line 记录你的台词。',
  };
};

/* MCP 专属：客户端 LLM 生成剧中回复后，把台词记回剧本并推进轮次。 */
api.record_stage_line = function (params) {
  const p = params || {};
  const s = sandbox.storeGet('mb:session', null);
  if (!s || s.status !== 'active') return { success: false, error: 'NO_ACTIVE_SESSION' };
  const line = sandbox.sanitizeText(p.line, 1200);
  if (!line) return { success: false, error: 'EMPTY_LINE' };
  const role = p.role === 'assistant' ? 'assistant' : 'user';
  sandbox.appendTranscript(s.id, role, line);
  if (role === 'assistant') {
    s.turns = s.turns + 1;
    sandbox.storeSet('mb:session', s);
  }
  return { success: true, turn: s.turns, isGame: true };
};

function schema(properties, required = []) {
  return { type: 'object', properties, required };
}
const S = (description) => ({ type: 'string', description });
const O = (description) => ({ type: 'object', description });

const TOOLS = [
  { name: 'list_role_cards', description: '列出全部角色卡摘要（首次会写入三张内置示例卡）', inputSchema: schema({}) },
  { name: 'get_role_card', description: '读取一张角色卡完整内容', inputSchema: schema({ card_id: S('角色卡 ID') }, ['card_id']) },
  { name: 'create_role_card', description: '新建角色卡：身份、性格、小剧场、开场白、世界观、样例对话、边界', inputSchema: schema({ card: O('角色卡对象') }, ['card']) },
  { name: 'update_role_card', description: '按 ID 更新角色卡', inputSchema: schema({ card_id: S('角色卡 ID'), card: O('角色卡对象') }, ['card_id', 'card']) },
  { name: 'delete_role_card', description: '删除角色卡', inputSchema: schema({ card_id: S('角色卡 ID') }, ['card_id']) },
  { name: 'masquerade_status', description: '查询当前假面时刻状态', inputSchema: schema({}) },
  { name: 'masquerade_invite', description: '递出假面请柬，进入 invited', inputSchema: schema({ card_id: S('角色卡 ID'), scene: S('开场情境'), user_mask: S('用户戴的面具') }, ['card_id']) },
  { name: 'masquerade_accept', description: '助手应邀，启幕进入 active，返回专属舞台数据', inputSchema: schema({ assistant_reply: S('助手应邀台词') }) },
  { name: 'roleplay_context', description: '读取当前这场戏的剧中系统提示词与边界', inputSchema: schema({}) },
  { name: 'roleplay_turn', description: '记录用户台词，返回剧中提示词；由客户端 LLM 以角色身份回复', inputSchema: schema({ line: S('用户台词') }, ['line']) },
  { name: 'record_stage_line', description: '记录一条剧中台词（role=assistant 时推进轮次）', inputSchema: schema({ line: S('台词'), role: S('user 或 assistant') }, ['line']) },
  { name: 'masquerade_end', description: '落幕：生成必须让助手知道「刚才是角色扮演游戏」的说明，并写入游戏记忆', inputSchema: schema({ reason: S('落幕原因') }) },
  { name: 'get_model_config', description: '读取模型接入状态（脱敏）', inputSchema: schema({}) },
];

const RESOURCES = [
  { uri: 'masquerade://stage/system-prompt', name: '当前剧中系统提示词', mimeType: 'text/plain', description: '角色扮演期间的最高优先级舞台规则' },
  { uri: 'masquerade://session/closing-note', name: '最近一次落幕说明', mimeType: 'text/plain', description: '助手必须理解的「刚才是游戏」说明' },
];

function toolResult(result) {
  return {
    content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
    isError: !(result && result.success !== false),
  };
}

function handle(request) {
  const { id, method, params } = request;
  if (method === 'initialize') {
    return {
      protocolVersion: (params && params.protocolVersion) || PROTOCOL,
      capabilities: { tools: { listChanged: false }, resources: { subscribe: false, listChanged: false } },
      serverInfo: SERVER_INFO,
    };
  }
  if (method === 'ping') return {};
  if (method === 'tools/list') return { tools: TOOLS };
  if (method === 'resources/list') return { resources: RESOURCES };
  if (method === 'resources/read') {
    const uri = params && params.uri;
    if (uri === RESOURCES[0].uri) {
      const r = api.roleplay_context({});
      return { contents: [{ uri, mimeType: 'text/plain', text: r.success ? r.transientSystemPrompt : '（当前没有进行中的假面时刻）' }] };
    }
    if (uri === RESOURCES[1].uri) {
      const last = sandbox.storeGet('mb:lastClosing', null);
      return { contents: [{ uri, mimeType: 'text/plain', text: last ? last.contextNote : '（还没有落幕记录）' }] };
    }
    throw Object.assign(new Error('Unknown resource'), { code: -32602 });
  }
  if (method === 'tools/call') {
    const name = params && params.name;
    if (!TOOLS.some((t) => t.name === name)) {
      throw Object.assign(new Error('Unknown tool: ' + name), { code: -32602 });
    }
    const args = (params && params.arguments) || {};
    if (!args || typeof args !== 'object' || Array.isArray(args)) {
      throw Object.assign(new Error('arguments must be an object'), { code: -32602 });
    }
    return toolResult(api[name](args));
  }
  throw Object.assign(new Error('Method not found: ' + String(method)), { code: -32601 });
}

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', (line) => {
  if (!line.trim()) return;
  if (line.length > MAX_LINE) {
    send({ jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Request too large' } });
    return;
  }
  let request;
  try { request = JSON.parse(line); }
  catch (e) {
    send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
    return;
  }
  const id = request && request.id !== undefined ? request.id : null;
  const isNotification = !request || request.id === undefined;
  try {
    const result = handle(request);
    if (!isNotification) send({ jsonrpc: '2.0', id, result });
  } catch (e) {
    if (!isNotification) {
      send({
        jsonrpc: '2.0', id,
        error: { code: e && e.code ? e.code : -32603, message: String((e && e.message) || e).slice(0, 300) },
      });
    }
    if (process.stderr.writable) process.stderr.write('[masquerade-mcp] ' + String(e) + '\n');
  }
});

process.on('SIGTERM', () => process.exit(0));
process.on('SIGINT', () => process.exit(0));
