/*
 * 启动垫片：让同一份 main.js 既能被宿主 QuickJS 加载，也能在浏览器预览页里跑。
 * 生产（存在 window.Bridge）时不注入任何凭据，页面一律走 Bridge.callTool。
 * 仅预览模式（无 Bridge）注入内存数据层与「演示剧本」，不发起任何真实网络请求。
 */
(function () {
  'use strict';
  window.MasqueradeCore = {};
  window.exports = window.MasqueradeCore;
  if (window.Bridge) return;

  var KEY = 'masquerade.studio.store.v1';
  var mem = {};
  try { mem = JSON.parse(window.localStorage.getItem(KEY) || '{}') || {}; } catch (e) { mem = {}; }
  function persist() {
    try { window.localStorage.setItem(KEY, JSON.stringify(mem)); } catch (e) { /* 无存储权限时仅内存 */ }
  }
  window.dataStore = {
    get: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; },
    set: function (k, v) { mem[k] = String(v); persist(); },
    del: function (k) { delete mem[k]; persist(); },
    list: function (p) {
      return Object.keys(mem).filter(function (k) { return k.indexOf(p) === 0; });
    }
  };
  window.config = {
    inject_as_prompt: true,
    api_base: 'https://api.openai.com/v1',
    /* 仅为让演示走通本地剧本分支；不是真实凭据，也不会写进插件配置。 */
    api_key: 'studio-demo',
    model_id: 'gpt-4o-mini',
    max_turns: 40
  };
  window.memoryBank = {
    save: function (t) {
      try {
        var k = 'masquerade.studio.memory.v1';
        var arr = JSON.parse(window.localStorage.getItem(k) || '[]');
        arr.push(String(t));
        window.localStorage.setItem(k, JSON.stringify(arr));
      } catch (e) { /* 忽略 */ }
    },
    recall: function () { return []; },
    search: function () { return []; },
    delete: function () {}
  };

  var SCRIPTED = [
    '（他把面具往灯下抬了抬）……你来得正好，第三支曲子还没开始。',
    '「不必告诉我你是谁。今夜，名字只是另一副面具。」',
    '（烛火晃了一下）我记下这句了。等天亮，我们都不提。',
    '「再跳一支？……好，就一支。」',
    '（他替你把披风拢好）风从露台那边来，别站太久。',
    '「面具摘下之前，还有一句话想留给你——就留在舞台上吧。」'
  ];
  /* 同步 mock：与宿主 QuickJS 的 fetch 返回结构一致，绝不联网。 */
  window.fetch = function (url, options) {
    var count = 0;
    try { count = JSON.parse(options.body).messages.length; } catch (e) { count = 0; }
    var content = SCRIPTED[count % SCRIPTED.length];
    var body = JSON.stringify({ choices: [{ message: { content: content } }] });
    return {
      ok: true, status: 200, body: body,
      json: function () { return JSON.parse(this.body); },
      text: function () { return this.body; }
    };
  };
})();
