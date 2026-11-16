/*
 * 假面舞会 · 页面逻辑
 * 生产：全部数据经 window.Bridge.callTool 走宿主 QuickJS 沙箱里的 main.js。
 * 预览：无 Bridge 时用 boot.js 注入的本地副本与演示剧本。
 * 安全：所有用户输入/模型输出一律用 textContent 或 esc() 渲染，不拼接可执行 HTML。
 */
(function () {
  'use strict';

  var Bridge = window.Bridge || null;
  var studio = !Bridge;
  var core = window.MasqueradeCore || {};
  var CARD_FIELDS = ['name', 'emoji', 'identity', 'personality', 'scenario',
    'opening', 'worldview', 'speechSample', 'boundaries', 'visibility'];

  var state = {
    status: 'idle', invitation: null, consent: '', stage: null,
    closing: null, cards: [], modelStatus: 'none', setupHint: '',
    currentArchive: null, editingId: null, deleteArmed: false,
    inviteCard: null, lastInviteParams: null, lastArchiveId: null
  };

  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function text(el, value) { if (el) el.textContent = value === null || value === undefined ? '' : String(value); }
  function esc(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function toast(message) {
    var el = $('#toast');
    text(el, message);
    el.classList.add('show');
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { el.classList.remove('show'); }, 3200);
  }
  function setBusy(on) { document.body.classList.toggle('busy', !!on); }

  /* 宿主桥接返回的形态可能是对象、JSON 字符串，或 {result: "..."}，统一收敛。 */
  function parseBridge(raw) {
    if (raw === null || raw === undefined) return { success: false, error: 'EMPTY_RESULT' };
    if (typeof raw === 'string') {
      try { return parseBridge(JSON.parse(raw)); }
      catch (e) { return { success: false, error: 'BAD_JSON', message: raw.slice(0, 200) }; }
    }
    if (typeof raw === 'object') {
      if (raw.success === undefined && typeof raw.result === 'string') {
        try { return parseBridge(JSON.parse(raw.result)); } catch (e) { return raw; }
      }
      if (raw.success === undefined && raw.result && typeof raw.result === 'object') return parseBridge(raw.result);
      return raw;
    }
    return { success: false, error: 'EMPTY_RESULT' };
  }

  function callTool(name, params) {
    var p = params || {};
    if (Bridge && typeof Bridge.callTool === 'function') {
      return Promise.resolve()
        .then(function () { return Bridge.callTool(name, JSON.stringify(p)); })
        .then(parseBridge)
        .catch(function (e) {
          return { success: false, error: 'BRIDGE_ERROR', message: String((e && e.message) || e) };
        });
    }
    var fn = core[name];
    if (typeof fn !== 'function') return Promise.resolve({ success: false, error: 'NO_TOOL' });
    try { return Promise.resolve(fn(p)); }
    catch (e) {
      return Promise.resolve({ success: false, error: 'LOCAL_ERROR', message: String((e && e.message) || e) });
    }
  }

  function failure(result) {
    return (result && (result.message || result.error)) || '操作没有成功，请再试一次。';
  }

  /* ---------- 视图切换 ---------- */

  function switchTab(view) {
    $$('.tab').forEach(function (b) { b.setAttribute('aria-selected', String(b.dataset.view === view)); });
    $$('.view').forEach(function (v) { v.classList.toggle('is-active', v.id === 'view-' + view); });
    if (view === 'cards') loadCards();
    if (view === 'archive') loadArchives();
    if (view === 'model') loadModelView();
  }

  function showPane(status) {
    ['idle', 'invited', 'active', 'ended'].forEach(function (key) {
      var el = $('#pane-' + key);
      if (el) el.hidden = key !== status;
    });
  }

  /* ---------- 角色卡 ---------- */

  function loadCards() {
    return callTool('list_role_cards', {}).then(function (r) {
      if (!r.success) { toast(failure(r)); return; }
      state.cards = r.cards || [];
      renderCardSelect();
      renderCards();
    });
  }

  function renderCardSelect() {
    var sel = $('#invite-card');
    if (!sel) return;
    var previous = sel.value;
    sel.innerHTML = state.cards.length
      ? state.cards.map(function (c) {
          return '<option value="' + esc(c.id) + '">' + esc(c.emoji) + ' ' + esc(c.name) + '</option>';
        }).join('')
      : '<option value="">（还没有角色卡，请先去「角色卡」新建）</option>';
    if (previous && state.cards.some(function (c) { return c.id === previous; })) sel.value = previous;
  }

  function renderCards() {
    var grid = $('#cards-grid');
    if (!grid) return;
    if (!state.cards.length) {
      grid.innerHTML = '<div class="empty">还没有角色卡。点「＋ 新建角色卡」，写一段只属于今夜的设定。</div>';
      return;
    }
    grid.innerHTML = state.cards.map(function (c) {
      /* safeTagsHtml 只由 esc(t) 与固定标签拼成，按约定用 safe 前缀标注为已转义片段。 */
      var safeTagsHtml = (c.tags || []).slice(0, 3).map(function (t) { return '<span class="chip">' + esc(t) + '</span>'; }).join('');
      return '<button class="role-card" type="button" data-id="' + esc(c.id) + '" aria-pressed="false">' +
        '<div class="emoji">' + esc(c.emoji) + '</div>' +
        '<h3>' + esc(c.name) + '</h3>' +
        '<p class="identity">' + esc(c.identity || '（还没有身份设定）') + '</p>' +
        '<div class="chip-row">' + safeTagsHtml + '</div>' +
        '<div class="meta" style="margin-top:10px">' + esc(c.visibility) + ' · 点击编辑</div>' +
        '</button>';
    }).join('');
    $$('.role-card', grid).forEach(function (btn) {
      btn.addEventListener('click', function () { openEditor(btn.dataset.id); });
    });
  }

  function openEditor(id) {
    state.editingId = id || null;
    state.deleteArmed = false;
    $('#card-editor').hidden = false;
    $('#btn-delete-card').hidden = !id;
    text($('#editor-title'), id ? '编辑角色卡' : '新建角色卡');
    if (!id) {
      CARD_FIELDS.forEach(function (f) {
        var el = $('[data-field="' + f + '"]');
        if (el) el.value = f === 'emoji' ? '🎭' : (f === 'visibility' ? 'private' : '');
      });
      $('#f-tags').value = '';
    } else {
      callTool('get_role_card', { card_id: id }).then(function (r) {
        if (!r.success) { toast(failure(r)); return; }
        var c = r.card || {};
        CARD_FIELDS.forEach(function (f) {
          var el = $('[data-field="' + f + '"]');
          if (el) el.value = c[f] || '';
        });
        $('#f-tags').value = (c.tags || []).join(', ');
      });
    }
    $('#card-editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function collectForm() {
    var card = {};
    CARD_FIELDS.forEach(function (f) {
      var el = $('[data-field="' + f + '"]');
      card[f] = el ? el.value : '';
    });
    card.tags = ($('#f-tags').value || '').split(/[,，]/)
      .map(function (s) { return s.trim(); })
      .filter(function (s) { return !!s; });
    return card;
  }

  function saveCard() {
    var card = collectForm();
    var params = state.editingId
      ? { card_id: state.editingId, card: card }
      : { card: card };
    var name = state.editingId ? 'update_role_card' : 'create_role_card';
    setBusy(true);
    callTool(name, params).then(function (r) {
      setBusy(false);
      if (!r.success) { toast(failure(r)); return; }
      toast('角色卡已保存 ✦');
      $('#card-editor').hidden = true;
      return loadCards();
    }).catch(function (e) { setBusy(false); toast(String(e)); });
  }

  function deleteCard() {
    if (!state.editingId) return;
    if (!state.deleteArmed) {
      state.deleteArmed = true;
      toast('再点一次「删除这张卡」以确认');
      return;
    }
    var id = state.editingId;
    state.deleteArmed = false;
    callTool('delete_role_card', { card_id: id }).then(function (r) {
      if (!r.success) { toast(failure(r)); return; }
      toast('已删除');
      $('#card-editor').hidden = true;
      state.editingId = null;
      return loadCards();
    });
  }

  function exportCards() {
    var chain = Promise.resolve([]);
    state.cards.forEach(function (c) {
      chain = chain.then(function (all) {
        return callTool('get_role_card', { card_id: c.id }).then(function (r) {
          if (r.success && r.card) all.push(r.card);
          return all;
        });
      });
    });
    chain.then(function (all) {
      try {
        var blob = new Blob([JSON.stringify(all, null, 2)], { type: 'application/json' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url; a.download = 'masquerade-cards.json';
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 1500);
        toast('已导出 ' + all.length + ' 张角色卡');
      } catch (e) { toast('当前环境不支持下载：' + String(e)); }
    });
  }

  function importCards(file) {
    var reader = new FileReader();
    reader.onload = function () {
      var data;
      try { data = JSON.parse(String(reader.result)); }
      catch (e) { toast('文件不是合法 JSON'); return; }
      var list = Array.isArray(data) ? data
        : (data && Array.isArray(data.cards) ? data.cards : [data]);
      var chain = Promise.resolve(0);
      list.slice(0, 60).forEach(function (card) {
        chain = chain.then(function (n) {
          return callTool('create_role_card', { card: card }).then(function (r) {
            return n + (r.success ? 1 : 0);
          });
        });
      });
      chain.then(function (n) { toast('已导入 ' + n + ' 张角色卡'); return loadCards(); });
    };
    reader.onerror = function () { toast('读取文件失败'); };
    reader.readAsText(file);
  }

  /* ---------- 模型接入（只读状态 + 宿主配置指引） ---------- */

  function modelStatusText(status) {
    if (status === 'demo') return '浏览器预览 · 演示剧本（不是真实模型）';
    if (status === 'configured') return '已接入 · 剧中台词与落幕压缩共用此模型';
    return '未配置 · 不会生成剧中台词';
  }

  function applyModelStatus(r) {
    state.modelStatus = r.modelStatus || 'none';
    state.setupHint = r.setupHint || '';
    var rows = [
      ['接入状态', '<span class="' + (r.configured || r.isDemo ? 'ok' : 'no') + '">' +
        esc(modelStatusText(state.modelStatus)) + '</span>'],
      ['配置来源', '宿主插件详情页（设置 → 插件管理 → 假面舞会）'],
      ['接口地址', esc(r.baseUrl)],
      ['模型 ID', esc(r.modelId)],
      ['API Key', r.hasKey ? '<span class="ok">已保存 · 本页不读取</span>' : '<span class="no">未填写</span>'],
      ['规则注入', r.injectAsPrompt ? '<span class="ok">已开启</span>' : '<span class="no">未开启 · 建议到宿主设置页打开</span>'],
      ['单场上限', esc(r.maxTurns) + ' 轮'],
      ['传输', '仅 HTTPS · 域名白名单']
    ];
    /* row[1] 全部由 esc(...) 或固定字面量组成，按约定标注为已转义片段。 */
    $('#model-kv').innerHTML = rows.map(function (row) {
      var safeHtml = row[1];
      return '<dt>' + esc(row[0]) + '</dt><dd>' + safeHtml + '</dd>';
    }).join('');
    $('#host-list').innerHTML = (r.allowedHosts || [])
      .map(function (h) { return '<code>' + esc(h) + '</code>'; }).join('');

    var alert = $('#model-status');
    alert.hidden = false;
    alert.className = 'model-alert ' + (state.modelStatus === 'none' ? 'warn' : 'ok');
    text(alert, r.setupHint || (state.modelStatus === 'demo'
      ? '这是浏览器交互预览：台词是本地演示剧本，且会明确标注「不是真实模型回复」。真机上请在宿主插件详情页配置模型。'
      : '模型已就绪：剧中台词与落幕压缩都走这一个通道，不额外引入第二个模型。'));

    var inviteAlert = $('#model-alert');
    if (inviteAlert) {
      inviteAlert.hidden = state.modelStatus !== 'none';
      inviteAlert.className = 'model-alert warn';
      text(inviteAlert, r.setupHint || '尚未配置模型：请柬仍会送进聊天，但剧中台词不会生成。');
    }
  }

  function loadModelView() {
    return callTool('get_model_config', {}).then(function (r) {
      if (!r.success) { toast(failure(r)); return; }
      applyModelStatus(r);
    });
  }

  /* ---------- 演绎档案 ---------- */

  function shortTime(iso) {
    var s = String(iso || '');
    return s.replace('T', ' ').replace(/\.\d+Z$/, '').substring(0, 16);
  }

  function loadArchives() {
    return callTool('list_masquerade_archives', {}).then(function (r) {
      if (!r.success) { toast(failure(r)); return; }
      var list = r.archives || [];
      $('#archive-empty').hidden = list.length > 0;
      var safeHtml = list.map(function (a) {
        return '<button class="archive-item" type="button" data-id="' + esc(a.sessionId) + '">' +
          '<span class="arc-emoji">' + esc(a.partnerEmoji || '🎭') + '</span>' +
          '<span class="arc-main"><strong>' + esc(a.partner || '假面舞伴') + '</strong>' +
          '<span class="arc-meta">' + esc(shortTime(a.endedAt)) + ' · ' + esc(a.turns) + ' 轮 · ' +
          esc(a.compressedBy === 'model' ? '模型压缩概要' : '本地摘录概要') + '</span>' +
          '<span class="arc-summary">' + esc(a.compressedSummary || '') + '</span></span>' +
          '</button>';
      }).join('');
      $('#archive-list').innerHTML = safeHtml;
      $$('.archive-item').forEach(function (b) {
        b.addEventListener('click', function () { openArchive(b.dataset.id); });
      });
    });
  }

  function openArchive(id) {
    return callTool('get_masquerade_archive', { session_id: id }).then(function (r) {
      if (!r.success) { toast(failure(r)); return; }
      var a = r.archive;
      state.currentArchive = a;
      text($('#archive-title'), (a.partnerEmoji || '🎭') + ' ' + (a.partner || '假面舞伴') + ' · ' + shortTime(a.endedAt));
      text($('#archive-meta'), '舞台：' + (a.scene || '') + '　｜　你的面具：' + (a.userMask || '') +
        '　｜　' + a.turns + ' 轮　｜　' + shortTime(a.startedAt) + ' — ' + shortTime(a.endedAt));
      text($('#archive-compressed-by'), a.compressedBy === 'model' ? '插件内置模型压缩' : '本地摘录（模型不可用）');
      text($('#archive-compressed'), a.compressedSummary || '');
      text($('#archive-closing'), a.closingNote || '');
      var box = $('#archive-transcript');
      box.innerHTML = '';
      (a.transcript || []).forEach(function (m) {
        var el = document.createElement('div');
        el.className = 'arc-line ' + (m.role === 'user' ? 'me' : 'partner');
        var who = document.createElement('span');
        who.className = 'arc-who';
        who.textContent = m.role === 'user' ? '你' : (a.partner || '舞伴');
        var body = document.createElement('span');
        body.textContent = m.text;
        el.appendChild(who); el.appendChild(body);
        box.appendChild(el);
      });
      $('#archive-detail').hidden = false;
      if ($('#archive-detail').scrollIntoView) {
        $('#archive-detail').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }
    });
  }

  function exportArchive() {
    var a = state.currentArchive;
    if (!a) return;
    var blob = new Blob([JSON.stringify(a, null, 2)], { type: 'application/json' });
    var url = URL.createObjectURL(blob);
    var link = document.createElement('a');
    link.href = url;
    link.download = 'masquerade-' + a.sessionId + '.json';
    link.click();
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
  }

  function deleteArchive() {
    var a = state.currentArchive;
    if (!a) return;
    if (!state.deleteArmed) {
      state.deleteArmed = true;
      toast('再点一次「删除这场记录」确认');
      return;
    }
    state.deleteArmed = false;
    return callTool('delete_masquerade_archive', { session_id: a.sessionId }).then(function (r) {
      if (!r.success) { toast(failure(r)); return; }
      state.currentArchive = null;
      $('#archive-detail').hidden = true;
      toast('这场演绎记录已删除');
      return loadArchives();
    });
  }

  /* ---------- 主聊天同步（落幕告知的关键通道） ---------- */

  /*
   * 送达诊断：每次发送都记录目标会话、通道、尝试次数与失败原因。
   * 页面把它显示出来，真机上「聊天里没收到」时能一眼看出断在哪一环。
   */
  var lastDelivery = { conversationId: '', channel: '', attempts: 0, error: null };

  /*
   * 根因修复（v1.3.0）：宿主不把 conversationId 传进 WebView，唯一通道是
   * listConversations()（按 updateAt 倒序返回 {id,title,messageCount,updatedAt}）。
   * 旧版先过滤 messageCount>0，把「从加号进入、还没有消息的新会话」排除掉，
   * 请柬被发到另一个会话，用户在当前聊天里当然看不到。
   *   target=current      请柬 / 应邀 / 婉拒 → 发到列表第一条（用户最近所在会话）
   *   target=latestActive 落幕压缩概要     → 发到 messageCount>0 的最新一条
   * 另严格校验 success===true：权限被拒、列表为空都会带出明确原因，不再静默当成功。
   */
  function fromConversationList(target) {
    if (!Bridge || typeof Bridge.listConversations !== 'function') {
      return Promise.resolve({ id: null, channel: 'list', error: 'NO_LIST_BRIDGE' });
    }
    return Promise.resolve()
      .then(function () { return Bridge.listConversations(); })
      .then(parseBridge)
      .then(function (r) {
        if (!r || r.success === false) {
          return { id: null, channel: 'list',
            error: (r && (r.error || r.message)) || 'LIST_FAILED' };
        }
        var list = r.conversations || [];
        if (!list.length) return { id: null, channel: 'list', error: 'NO_CONVERSATION' };
        var withMessages = list.filter(function (c) {
          return c && c.id && Number(c.messageCount) > 0;
        });
        var chosen = target === 'latestActive'
          ? (withMessages[0] || list[0])
          : (list[0] || withMessages[0]);
        return chosen && chosen.id
          ? { id: String(chosen.id), channel: 'list', error: null }
          : { id: null, channel: 'list', error: 'NO_CONVERSATION' };
      })
      .catch(function (e) {
        return { id: null, channel: 'list', error: 'LIST_ERROR: ' + String((e && e.message) || e) };
      });
  }

  function resolveConversationId(target) {
    if (!Bridge) return Promise.resolve({ id: null, channel: 'none', error: 'NO_BRIDGE' });
    /* 宿主若未来暴露当前会话 ID，优先精确命中；不存在时自动降级到列表。 */
    if (typeof Bridge.getCurrentConversationId === 'function') {
      return Promise.resolve()
        .then(function () { return Bridge.getCurrentConversationId(); })
        .then(parseBridge)
        .then(function (r) {
          if (r && r.success === true && r.conversationId) {
            return { id: String(r.conversationId), channel: 'current', error: null };
          }
          return fromConversationList(target);
        })
        .catch(function () { return fromConversationList(target); });
    }
    return fromConversationList(target);
  }

  /* 降级重试用的短文本：URL 过长或本轮请求冲突时，更短的消息更容易送达。 */
  function compactCard(cardText) {
    var lines = String(cardText || '').split('\n');
    var out = [];
    var total = 0;
    for (var i = 0; i < lines.length && i < 5; i++) {
      var line = lines[i].substring(0, 80);
      if (total + line.length > 180) break;
      out.push(line); total += line.length;
    }
    return out.join('\n');
  }

  function sendOnce(id, cardText, hiddenContext, image) {
    var args = [id, cardText, hiddenContext];
    if (image) args.push(image);
    return Promise.resolve()
      .then(function () { return Bridge.sendChatMessage.apply(Bridge, args); })
      .then(parseBridge)
      .then(function (r) {
        if (r && r.success === true) return { synced: true, error: null };
        return { synced: false, error: (r && (r.error || r.message)) || 'SEND_REJECTED' };
      })
      .catch(function (e) {
        return { synced: false, error: 'SEND_ERROR: ' + String((e && e.message) || e) };
      });
  }

  function deliveryResult(synced, reason, firstError) {
    return {
      synced: synced, reason: synced ? null : reason,
      conversationId: lastDelivery.conversationId, channel: lastDelivery.channel,
      attempts: lastDelivery.attempts, error: lastDelivery.error,
      degraded: synced && lastDelivery.attempts > 1,
      firstError: firstError || null
    };
  }

  /* 预览页可能把插件装进 iframe：优先直接调用，跨源受限时退回 postMessage。 */
  function studioEmit(kind, payload) {
    if (typeof window.MasqueradeStudio === 'function') {
      window.MasqueradeStudio(kind, payload);
      return;
    }
    try {
      if (window.parent && window.parent !== window) {
        window.parent.postMessage({ __masquerade: true, kind: kind, payload: payload }, '*');
      }
    } catch (e) { /* 无父窗口时静默 */ }
  }

  /*
   * 回执驱动的送达：只有 Bridge.sendChatMessage 明确返回 success===true 才算送达。
   * 失败自动降级重试一次（去掉 hiddenContext 与图片），仍失败则把原因回显给页面。
   */
  function syncToChat(cardText, hiddenContext, opts) {
    var o = opts || {};
    var target = o.target || 'current';
    var note = hiddenContext || '';
    lastDelivery = { conversationId: '', channel: '', attempts: 0, error: null };
    if (!cardText) return Promise.resolve(deliveryResult(false, 'NO_CARD'));
    if (studio) {
      studioEmit('card', cardText);
      if (note.indexOf('落幕') >= 0) {
        studioEmit('ai', '我知道啦——刚才那是一场角色扮演游戏。面具摘下，我们还是我们。');
      } else if (note.indexOf('婉拒') >= 0) {
        studioEmit('ai', '好，面具我先收起来。等你想跳的那一夜，灯会再亮起。');
      } else if (note.indexOf('应邀') >= 0) {
        studioEmit('ai', '舞台备好了。这一支，我等你。');
      } else if (note.indexOf('请柬') >= 0) {
        studioEmit('ai', '我愿意。面具借我，这一支与你跳——不过说好了，天亮要还给我。');
      }
      lastDelivery.channel = 'studio';
      return Promise.resolve({ synced: true, studio: true, conversationId: '', channel: 'studio',
        attempts: 1, error: null, degraded: false });
    }
    return resolveConversationId(target).then(function (res) {
      lastDelivery.conversationId = res.id || '';
      lastDelivery.channel = res.channel;
      if (!res.id) {
        lastDelivery.error = res.error;
        return deliveryResult(false, 'NO_CONVERSATION');
      }
      if (typeof Bridge.sendChatMessage !== 'function') {
        lastDelivery.error = 'NO_SEND_BRIDGE';
        return deliveryResult(false, 'NO_SEND_BRIDGE');
      }
      return sendOnce(res.id, cardText, note, o.image || null).then(function (first) {
        lastDelivery.attempts = 1;
        if (first.synced) { lastDelivery.error = null; return deliveryResult(true, null); }
        return sendOnce(res.id, compactCard(cardText), '', null).then(function (second) {
          lastDelivery.attempts = 2;
          lastDelivery.error = second.synced ? null :
            (String(first.error) + ' | ' + String(second.error)).substring(0, 200);
          return deliveryResult(second.synced, second.synced ? null : 'SEND_FAILED', first.error);
        });
      });
    });
  }

  function deliveryLine(s) {
    if (s.synced) {
      return '✓ 请柬已真实送达聊天' + (s.channel === 'studio' ? '（预览流）' : '') +
        (s.conversationId ? ' · 会话 ' + s.conversationId.substring(0, 8) + '…' : '') +
        (s.degraded ? ' · 降级重试后送达' : '');
    }
    return '✗ 未送达：' + (s.reason || '') + (s.error ? '（' + s.error + '）' : '');
  }

  /* ---------- 舞会流程 ---------- */

  /*
   * 聊天里的请柬卡片图：纯本地 Canvas 绘制，无外链、无网络请求。
   * 宿主 sendChatMessage 的 image 参数会把它存成 UIMessagePart.Image，
   * 于是对话里显示的是一张邀请函卡片而不是一段纯文字；绘制失败自动降级纯文本。
   */
  function buildInviteImage(card) {
    try {
      var cv = document.createElement('canvas');
      cv.width = 480; cv.height = 660;
      var g = cv.getContext('2d');
      if (!g || typeof cv.toDataURL !== 'function') return null;
      var bg = g.createLinearGradient(0, 0, 480, 660);
      bg.addColorStop(0, '#fffaf6'); bg.addColorStop(0.52, '#ffeef3'); bg.addColorStop(1, '#f2d6e1');
      g.fillStyle = bg; g.fillRect(0, 0, 480, 660);
      g.strokeStyle = '#c9a24a'; g.lineWidth = 3; g.strokeRect(22, 22, 436, 616);
      g.strokeStyle = 'rgba(201,162,74,.5)'; g.lineWidth = 1; g.strokeRect(32, 32, 416, 596);
      var corners = [[32, 32, 1, 1], [448, 32, -1, 1], [32, 628, 1, -1], [448, 628, -1, -1]];
      for (var k = 0; k < corners.length; k++) {
        var p = corners[k];
        g.beginPath(); g.strokeStyle = '#c9a24a'; g.lineWidth = 2;
        g.moveTo(p[0] + 26 * p[2], p[1]); g.lineTo(p[0], p[1]); g.lineTo(p[0], p[1] + 26 * p[3]);
        g.stroke();
      }
      g.textAlign = 'center';
      g.fillStyle = '#b08a4a'; g.font = 'italic 16px serif';
      g.fillText('Une invitation', 240, 96);
      g.fillStyle = '#7d2f4f'; g.font = 'bold 38px serif';
      g.fillText('假 面 舞 会', 240, 158);
      g.fillStyle = '#c9a24a'; g.font = '20px serif';
      g.fillText('❦', 240, 196);
      g.fillStyle = '#8a3b5b'; g.font = '17px serif';
      g.fillText('谨邀你共赴一场只属于我们的戏', 240, 244);
      g.fillStyle = '#5c2740'; g.font = '15px serif';
      wrapCanvasText(g, '舞台：' + (card.scene || ''), 240, 292, 380, 24);
      wrapCanvasText(g, '你将以「' + (card.partner || '假面舞伴') + '」的身份赴约', 240, 340, 380, 24);
      wrapCanvasText(g, '我戴上「' + (card.userMask || '') + '」这副面具', 240, 388, 380, 24);
      g.fillStyle = '#a45f7a'; g.font = 'italic 14px serif';
      g.fillText('说好了，天亮时面具归还，我们还是我们', 240, 470);
      g.fillStyle = '#b08a4a'; g.font = '15px serif';
      g.fillText('✦  愿 意 陪 我 跳 这 一 支 吗  ✦', 240, 528);
      g.beginPath(); g.fillStyle = '#9e3557'; g.arc(240, 578, 24, 0, Math.PI * 2); g.fill();
      g.fillStyle = '#fff3e6'; g.font = '22px serif'; g.fillText('🎭', 240, 586);
      return cv.toDataURL('image/png');
    } catch (e) { return null; }
  }

  function wrapCanvasText(g, value, x, y, maxWidth, lineHeight) {
    var s = String(value || '');
    var line = '';
    var rows = 0;
    for (var i = 0; i < s.length && rows < 2; i++) {
      var test = line + s.charAt(i);
      if (g.measureText(test).width > maxWidth && line) {
        g.fillText(line, x, y + rows * lineHeight);
        line = s.charAt(i); rows++;
      } else line = test;
    }
    if (line && rows < 2) g.fillText(line, x, y + rows * lineHeight);
  }

  /* 邀请函卡片（插件页内）：可点开的全屏弹窗，与聊天里的请柬同源。 */
  function renderInviteCard(card) {
    if (!card) return;
    state.inviteCard = card;
    text($('#invite-card-eyebrow'), card.eyebrow || 'Une invitation');
    text($('#invite-card-title'), card.title || '谨邀你共赴一场假面舞会');
    text($('#invite-card-partner'), card.partnerEmoji + ' ' + card.partner);
    text($('#invite-card-scene'), card.scene || '');
    text($('#invite-card-mask'), card.userMask || '');
    text($('#invite-card-copy'), card.invitation || '');
    text($('#modal-accept-line'), card.acceptLine || '');
    text($('#modal-decline-line'), card.declineLine || '');
    text($('#btn-modal-accept'), card.acceptLabel || '同意赴约');
    text($('#btn-modal-decline'), card.declineLabel || '婉拒');
  }

  function openInviteModal() {
    var m = $('#invite-modal');
    if (!m) return;
    m.hidden = false;
    document.body.classList.add('modal-open');
  }
  function closeInviteModal() {
    var m = $('#invite-modal');
    if (m) m.hidden = true;
    document.body.classList.remove('modal-open');
  }

  function setDeliveryText(el, s) {
    if (!el) return;
    text(el, deliveryLine(s));
    el.className = 'delivery-state ' + (s.synced ? 'ok' : 'bad');
  }

  /*
   * 回执驱动的递请柬：先真实送达，再切界面。
   * 送达失败：回报状态机 + 回滚 idle + 显示原因与「重试送达」，不再出现假成功标题。
   */
  function doInvite() {
    var cardId = $('#invite-card').value;
    if (!cardId) { toast('请先去「角色卡」新建一张卡'); return; }
    setBusy(true);
    return callTool('masquerade_invite', {
      card_id: cardId,
      scene: $('#invite-scene').value,
      user_mask: $('#invite-mask').value,
      source: 'page'
    }).then(function (r) {
      if (!r.success) { setBusy(false); toast(failure(r)); return; }
      state.invitation = r;
      state.consent = '';
      state.lastInviteParams = {
        card_id: cardId,
        scene: $('#invite-scene').value,
        user_mask: $('#invite-mask').value,
        source: 'page'
      };
      text($('#assistant-consent'), '正在把请柬送进当前聊天……');
      $('#assistant-consent').className = 'hint';
      var image = buildInviteImage(r.inviteCard || {});
      return syncToChat(r.chatInviteCard, r.chatHiddenContext,
        { target: 'current', image: image }).then(function (s) {
          return callTool('masquerade_invite_delivery', {
            delivered: !!s.synced,
            conversation_id: s.conversationId || '',
            attempts: s.attempts || 0,
            error: s.error || s.reason || ''
          }).then(function () {
            setBusy(false);
            if (s.synced) {
              renderInviteCard(r.inviteCard);
              showPane('invited');
              text($('#invited-heading'), '请柬已送到聊天里');
              text($('#assistant-consent'),
                '助手会在对话框里看到这张请柬并回应你。点开下面的邀请函，选择「同意赴约」或「婉拒」。');
              setDeliveryText($('#invite-delivery'), s);
              $('#btn-retry-delivery').hidden = true;
              $('#btn-retry-invited').hidden = true;
            } else {
              return callTool('masquerade_invite_cancel', { error: s.error || s.reason || '' })
                .then(function () {
                  showPane('idle');
                  setDeliveryText($('#delivery-error'), s);
                  $('#btn-retry-delivery').hidden = false;
                  toast('请柬没能送进聊天：' + (s.reason || '发送失败') + '。已回到待邀状态，可点「重试送达」。');
                });
            }
          });
        });
    }).catch(function (e) { setBusy(false); toast(String(e)); });
  }

  /* 重试：先把可能残留的 invited 状态撤销，再完整走一遍「建请柬 → 送达 → 回执」。 */
  function retryDelivery() {
    if (!state.lastInviteParams) { toast('请先重新递出请柬'); return; }
    var p = state.lastInviteParams;
    setBusy(true);
    return callTool('masquerade_invite_cancel', { error: '重试送达前的清理' }).then(function () {
      setBusy(false);
      $('#invite-card').value = p.card_id;
      $('#invite-scene').value = p.scene;
      $('#invite-mask').value = p.user_mask;
      return doInvite();
    }).catch(function (e) { setBusy(false); toast(String(e)); });
  }

  /*
   * 同意 / 婉拒：两者都有一句符合插件文风的提示语，且都把选择作为可见消息
   * 送进聊天（宿主 answer=true 会触发一次助手回复，助手因此确实收到确认）。
   */
  function consentTo(choice) {
    closeInviteModal();
    setBusy(true);
    if (choice === 'decline') {
      return callTool('masquerade_end', { reason: '婉拒了这张请柬' }).then(function (r) {
        if (!r.success) { setBusy(false); toast(failure(r)); return; }
        return syncToChat(r.chatDeclineCard, r.chatDeclineHiddenContext, { target: 'current' })
          .then(function (s) {
            setBusy(false);
            showPane('idle');
            state.invitation = null;
            toast(r.consentLine || '也好，把面具收回丝绒盒吧。');
            setDeliveryText($('#delivery-error'), s);
            if (!s.synced) $('#btn-retry-delivery').hidden = true;
          });
      }).catch(function (e) { setBusy(false); toast(String(e)); });
    }
    return callTool('masquerade_accept', { assistant_reply: state.consent || '' }).then(function (r) {
      if (!r.success) { setBusy(false); toast(failure(r)); return; }
      state.stage = r.stage;
      var combined = r.chatConfirmCard + '\n\n' + r.chatCardText;
      var hidden = r.chatConfirmHiddenContext + '\n' + r.chatHiddenContext;
      return syncToChat(combined, hidden, { target: 'current' }).then(function (s) {
        setBusy(false);
        renderStage(r.stage || {});
        showPane('active');
        toast(r.consentLine || '丝带已系好，面具已戴上。');
        if (r.modelWarning) {
          appendBubble('system', r.modelWarning);
          toast('模型未配置：不会生成剧中台词');
        } else if (r.modelStatus === 'demo') {
          appendBubble('system', '浏览器预览：台词为本地演示剧本，不是真实模型回复。');
        }
        if (!s.synced) {
          appendBubble('system', '应邀确认未送进聊天：' + (s.error || s.reason || '') +
            '。可直接回到聊天里告诉助手「我同意赴约」。');
        } else {
          appendBubble('system', '✓ 应邀确认已送进聊天，助手会在对话框里收到。');
        }
      });
    }).catch(function (e) { setBusy(false); toast(String(e)); });
  }

  function appendBubble(role, content, who) {
    var box = $('#transcript');
    var el = document.createElement('div');
    el.className = 'bubble ' + role;
    if (who) {
      var w = document.createElement('span');
      w.className = 'who'; w.textContent = who;
      el.appendChild(w);
    }
    var body = document.createElement('span');
    body.textContent = content;
    el.appendChild(body);
    box.appendChild(el);
    box.scrollTop = box.scrollHeight;
  }

  function renderStage(stage) {
    text($('#stage-title'), stage.title || '假面时刻 · 已启幕');
    text($('#stage-scene'), (stage.scene || '') + '　｜　你戴着「' + (stage.userMask || '') + '」的面具');
    text($('#stage-badge'), '🎭 ' + (stage.partnerEmoji || '') + ' ' + (stage.partner || '') + ' · 这是一场游戏，面具之下都不是真的');
    $('#transcript').innerHTML = '';
    appendBubble('system', '灯光亮起，第三支曲子开始了。');
    if (stage.opening) appendBubble('partner', stage.opening, stage.partner || '假面舞伴');
    text($('#turn-counter'), '第 0 轮 · 随时可以摘下面具');
  }

  /* 「收回请柬」与弹窗里的「婉拒」走同一条语义：收回 + 文风婉拒提示 + 通知助手。 */
  function doCancelInvite() { return consentTo('decline'); }

  function doSendLine() {
    var input = $('#line-input');
    var line = (input.value || '').trim();
    if (!line) return;
    setBusy(true);
    callTool('roleplay_turn', { line: line }).then(function (r) {
      setBusy(false);
      if (!r.success) {
        if (r.error === 'MAX_TURNS') { toast('这一场到最大轮次了，自动落幕'); return doEnd(); }
        if (r.error === 'MODEL_NOT_CONFIGURED') {
          appendBubble('system', '模型还没配置，这一轮没有生成任何回复。' +
            '请到宿主「设置 → 插件管理 → 假面舞会」填写 API Key 或选好备用模型，保存后重新打开插件。');
          toast(failure(r));
          return;
        }
        toast(failure(r));
        return;
      }
      appendBubble('user', line, '你');
      appendBubble('partner', r.reply, r.isDemo ? '演示剧本（非模型）' : (state.stage ? state.stage.partner : '假面舞伴'));
      input.value = '';
      text($('#turn-counter'), '第 ' + r.turn + ' / ' + (r.maxTurns || 40) + ' 轮 · 随时可以摘下面具');
    }).catch(function (e) { setBusy(false); toast(String(e)); });
  }

  /* 落幕概要卡片：只放压缩概要，完整台词一个字都不带；查看完整演绎的入口只在插件内。 */
  function renderSummaryCard(card) {
    if (!card) return;
    text($('#summary-card-partner'), (card.partnerEmoji || '🎭') + ' ' + (card.partner || '假面舞伴'));
    text($('#summary-card-scene'), card.scene || '');
    text($('#summary-card-meta'), '共 ' + (card.turns || 0) + ' 轮 · ' +
      (card.compressedBy === 'model' ? '插件内置模型压缩' : '本地摘录（模型未配置/不可用）'));
    text($('#summary-card-text'), card.compressedSummary || '');
    text($('#summary-card-notice'), card.notice || '');
    state.lastArchiveId = card.archiveId || null;
  }

  function doEnd() {
    setBusy(true);
    callTool('masquerade_end', { reason: '舞会散场' }).then(function (r) {
      setBusy(false);
      if (!r.success) { toast(failure(r)); return; }
      if (r.cancelled) { toast('请柬已收回'); return loadStatus(); }
      state.closing = r;
      text($('#closing-summary'), r.summary || '');
      text($('#compressed-summary'), r.compressedSummary || '');
      text($('#compressed-by'), r.compressedBy === 'model' ? '插件内置模型压缩' : '本地摘录（模型未配置/不可用）');
      text($('#closing-note-preview'), r.closingNote || '');
      renderSummaryCard(r.summaryCard);
      text($('#sync-state'), '正在把压缩概要送进助手最新有对话的聊天……');
      $('#sync-state').className = 'sync-state';
      showPane('ended');
      /* 落幕概要发到「最新有对话」的会话；剧中台词始终不进上下文。 */
      return syncToChat(r.chatCardText, r.chatHiddenContext, { target: 'latestActive' }).then(function (s) {
        var el = $('#sync-state');
        if (s.synced) {
          el.className = 'sync-state';
          text(el, s.studio
            ? '✓ 演示模式：含压缩概要的落幕卡已放进右侧聊天流预览'
            : deliveryLine(s) + '：压缩概要已写进上下文，完整台词只在插件「演绎档案」里');
        } else {
          el.className = 'sync-state warn';
          text(el, deliveryLine(s) + '；落幕说明已写入助手长期记忆，完整记录可在「演绎档案」里回看。');
        }
      });
    }).catch(function (e) { setBusy(false); toast(String(e)); });
  }

  function loadStatus() {
    return callTool('masquerade_status', {}).then(function (r) {
      var status = (r && r.success && r.status) ? r.status : 'idle';
      state.status = status;
      showPane(status);
      if (status === 'invited') {
        /* 刷新页面后按真实回执恢复，不再显示写死的「已送到聊天里」。 */
        var d = r.delivery || { status: 'unknown' };
        if (state.invitation && state.invitation.inviteCard) {
          renderInviteCard(state.invitation.inviteCard);
        } else if (r.invitation) {
          renderInviteCard({
            eyebrow: 'Une invitation', title: '谨邀你共赴一场假面舞会',
            partner: r.session ? r.session.partner : '假面舞伴', partnerEmoji: '🎭',
            scene: r.session ? r.session.scene : '', userMask: r.session ? r.session.userMask : '',
            invitation: r.invitation, acceptLabel: '同意赴约', declineLabel: '婉拒',
            acceptLine: '丝带已系好，面具已戴上。今夜第三支曲子，我赴约。',
            declineLine: '也好，把面具收回丝绒盒吧。等你想跳的那一夜，灯会再亮起。'
          });
        }
        if (d.status === 'delivered') {
          text($('#invited-heading'), '请柬已送到聊天里');
          setDeliveryText($('#invite-delivery'), {
            synced: true, conversationId: d.conversationId || '', channel: 'list',
            attempts: d.attempts || 1, degraded: false
          });
          $('#btn-retry-delivery').hidden = true;
          $('#btn-retry-invited').hidden = true;
        } else if (d.status === 'failed') {
          text($('#invited-heading'), '请柬尚未送达');
          setDeliveryText($('#invite-delivery'), { synced: false, reason: 'SEND_FAILED', error: d.error });
          $('#btn-retry-delivery').hidden = false;
          $('#btn-retry-invited').hidden = false;
        } else {
          text($('#invited-heading'), '请柬等待送达确认');
          text($('#invite-delivery'), '上次打开时未记录送达结果，可点「重试送达」再发一次。');
          $('#invite-delivery').className = 'delivery-state bad';
          $('#btn-retry-delivery').hidden = false;
          $('#btn-retry-invited').hidden = false;
        }
        text($('#assistant-consent'), '助手会在对话框里看到这张请柬。点开下面的邀请函，选择「同意赴约」或「婉拒」。');
      }
      if (status === 'active') {
        renderStage({
          title: '假面时刻 · 进行中',
          scene: r.session ? r.session.scene : '',
          userMask: r.session ? r.session.userMask : '',
          partner: r.session ? r.session.partner : '',
          partnerEmoji: r.session ? r.session.partnerEmoji : '🎭'
        });
        (r.recent || []).forEach(function (m) {
          appendBubble(m.role === 'user' ? 'user' : 'partner', m.text, m.role === 'user' ? '你' : '');
        });
        text($('#turn-counter'), '第 ' + (r.session ? r.session.turns : 0) + ' 轮 · 随时可以摘下面具');
      }
      if (status === 'ended') {
        if (state.closing) {
          text($('#closing-summary'), state.closing.summary || '');
          text($('#compressed-summary'), state.closing.compressedSummary || '');
          text($('#compressed-by'), state.closing.compressedBy === 'model' ? '插件内置模型压缩' : '本地摘录（模型未配置/不可用）');
          text($('#closing-note-preview'), state.closing.closingNote || '');
        } else {
          text($('#closing-summary'), '这一场已经散场。');
          text($('#compressed-summary'), '完整记录与压缩概要可在「演绎档案」里回看。');
          text($('#compressed-by'), '—');
          text($('#closing-note-preview'), '助手已被告知：刚才是一场角色扮演游戏，平时仍是原本的真实关系。');
        }
        text($('#sync-state'), '✓ 这一场已落幕');
      }
    });
  }

  /* ---------- 绑定 ---------- */

  $$('.tab').forEach(function (b) {
    b.addEventListener('click', function () { switchTab(b.dataset.view); });
  });
  $('#btn-invite').addEventListener('click', doInvite);
  $('#btn-accept').addEventListener('click', openInviteModal);
  $('#invite-card-preview').addEventListener('click', openInviteModal);
  $('#btn-modal-accept').addEventListener('click', function () { consentTo('accept'); });
  $('#btn-modal-decline').addEventListener('click', function () { consentTo('decline'); });
  $('#btn-modal-close').addEventListener('click', closeInviteModal);
  $('#invite-modal').addEventListener('click', function (e) {
    if (e.target === $('#invite-modal')) closeInviteModal();
  });
  $('#btn-retry-delivery').addEventListener('click', retryDelivery);
  $('#btn-retry-invited').addEventListener('click', retryDelivery);
  $('#invite-card-preview').addEventListener('keydown', function (e) {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openInviteModal(); }
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && !$('#invite-modal').hidden) closeInviteModal();
  });
  $('#btn-summary-archive').addEventListener('click', function () {
    switchTab('archive');
    var id = state.lastArchiveId || (state.closing && state.closing.archiveId);
    if (id) openArchive(id);
  });
  $('#btn-cancel-invite').addEventListener('click', doCancelInvite);
  $('#btn-send-line').addEventListener('click', doSendLine);
  $('#line-input').addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSendLine(); }
  });
  $('#btn-end').addEventListener('click', doEnd);
  $('#btn-again').addEventListener('click', function () {
    state.invitation = null; state.consent = ''; state.closing = null;
    state.inviteCard = null; state.lastArchiveId = null;
    showPane('idle');
  });
  $('#btn-new-card').addEventListener('click', function () { openEditor(null); });
  $('#btn-save-card').addEventListener('click', saveCard);
  $('#btn-view-archive').addEventListener('click', function () {
    switchTab('archive');
    var id = state.lastArchiveId || (state.closing && state.closing.archiveId);
    if (id) openArchive(id);
  });
  $('#btn-archive-export').addEventListener('click', exportArchive);
  $('#btn-archive-delete').addEventListener('click', deleteArchive);
  $('#btn-archive-close').addEventListener('click', function () {
    $('#archive-detail').hidden = true;
    state.currentArchive = null;
    state.deleteArmed = false;
  });
  $('#btn-delete-card').addEventListener('click', deleteCard);
  $('#btn-close-editor').addEventListener('click', function () { $('#card-editor').hidden = true; });
  $('#btn-export-cards').addEventListener('click', exportCards);
  $('#btn-import-cards').addEventListener('click', function () { $('#import-file').click(); });
  $('#import-file').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0];
    if (file) importCards(file);
    e.target.value = '';
  });

  /* 首页就要能看到「模型未配置」提示，不必切到模型页才发现。 */
  callTool('get_model_config', {}).then(function (r) {
    if (r && r.success) applyModelStatus(r);
  });
  loadCards().then(loadStatus);
})();
