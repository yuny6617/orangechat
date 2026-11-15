/*
 * 启动垫片：让同一份 main.js 既能被宿主 QuickJS 加载，也能在浏览器预览页里跑。
 * 生产（存在 window.Bridge）时不注入任何凭据，页面一律走 Bridge.callTool。
 * 仅预览模式（无 Bridge）注入本地数据层与 demo:true 标记；
 * 预览页绝不注入伪 API Key，剧中台词由 main.js 的 demoRoleplayReply 明确标注为演示剧本。
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
    /* demo 只在这里注入：宿主 QuickJS 沙箱没有 window，config 也不含 demo 字段。 */
    demo: true,
    inject_as_prompt: true,
    api_base: 'https://api.openai.com/v1',
    model_id: 'gpt-4o-mini',
    max_turns: 40
    /* 刻意不提供任何密钥字段：预览页不联网、不调用任何真实模型 */
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
  /* 预览页不注入 fetch：真有代码误调模型也会直接失败，不会拿到伪造回复。 */
})();
