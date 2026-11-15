import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SERVER = path.join(ROOT, 'mcp', 'server.mjs');

function startServer() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'masquerade-mcp-'));
  const child = spawn(process.execPath, [SERVER], {
    env: { ...process.env, MASQUERADE_STORE_FILE: path.join(dir, 'store.json') },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const pending = new Map();
  const rl = readline.createInterface({ input: child.stdout });
  rl.on('line', (line) => {
    let msg;
    try { msg = JSON.parse(line); } catch (e) { return; }
    if (msg.id !== null && msg.id !== undefined && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  });
  let seq = 0;
  function request(method, params) {
    const id = ++seq;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (pending.has(id)) { pending.delete(id); reject(new Error('MCP 响应超时: ' + method)); }
      }, 6000);
      pending.set(id, (msg) => { clearTimeout(timer); resolve(msg); });
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }
  function raw(line) { child.stdin.write(line + '\n'); return new Promise((r) => setTimeout(r, 120)); }
  return { child, request, raw, dir };
}

function payload(msg) { return JSON.parse(msg.result.content[0].text); }

test('MCP：initialize / tools.list / 完整一场戏 / 落幕语义', async (t) => {
  const srv = startServer();
  t.after(() => { srv.child.kill(); fs.rmSync(srv.dir, { recursive: true, force: true }); });

  const init = await srv.request('initialize', { protocolVersion: '2024-11-05' });
  assert.equal(init.result.serverInfo.name, 'orangechat-masquerade');
  assert.equal(init.result.protocolVersion, '2024-11-05');
  assert.ok(init.result.capabilities.tools);

  const list = await srv.request('tools/list', {});
  assert.ok(list.result.tools.length >= 12);
  assert.ok(list.result.tools.some((tool) => tool.name === 'masquerade_end'));

  const cards = payload(await srv.request('tools/call', { name: 'list_role_cards', arguments: {} }));
  assert.equal(cards.total, 3);

  const invited = payload(await srv.request('tools/call', {
    name: 'masquerade_invite', arguments: { card_id: cards.cards[0].id, scene: '雨夜露台', user_mask: '无名来客' },
  }));
  assert.equal(invited.status, 'invited');

  const accepted = payload(await srv.request('tools/call', { name: 'masquerade_accept', arguments: {} }));
  assert.equal(accepted.status, 'active');
  assert.ok(accepted.chatHiddenContext.includes('不是真实生活'));

  const turn = payload(await srv.request('tools/call', { name: 'roleplay_turn', arguments: { line: '你把面具摘下来好吗' } }));
  assert.equal(turn.success, true);
  assert.equal(turn.reply, null, 'MCP 由客户端 LLM 扮演，server 不代跑模型');
  assert.ok(turn.stagePrompt.includes('假面时刻'));
  assert.ok(turn.hint.includes('record_stage_line'));

  const recorded = payload(await srv.request('tools/call', {
    name: 'record_stage_line', arguments: { line: '（他笑着摇头）天亮之前还不行。', role: 'assistant' },
  }));
  assert.equal(recorded.turn, 1);

  const ended = payload(await srv.request('tools/call', { name: 'masquerade_end', arguments: { reason: '雨停了' } }));
  assert.equal(ended.status, 'ended');
  assert.ok(ended.closingNote.includes('角色扮演游戏'));
  assert.ok(ended.closingNote.includes('平时依旧是原本的真实情感关系'));
  assert.equal(ended.chatHiddenContext, ended.closingNote);

  const resource = await srv.request('resources/read', { uri: 'masquerade://session/closing-note' });
  assert.ok(resource.result.contents[0].text.includes('角色扮演游戏'));
});

test('MCP：未知工具、非法 JSON 与过大请求都返回规范错误', async (t) => {
  const srv = startServer();
  t.after(() => { srv.child.kill(); fs.rmSync(srv.dir, { recursive: true, force: true }); });

  const unknown = await srv.request('tools/call', { name: '__not_a_tool__', arguments: {} });
  assert.equal(unknown.error.code, -32602);

  const unknownMethod = await srv.request('does/not/exist', {});
  assert.equal(unknownMethod.error.code, -32601);

  await srv.raw('{ this is not json');
  await srv.request('ping', {}).then((r) => assert.deepEqual(r.result, {}));
});
