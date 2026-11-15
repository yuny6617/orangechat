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
    currentArchive: null, editingId: null, deleteArmed: false
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
   * 宿主不把 conversationId 传进 WebView，唯一通道是 listConversations()：
   * 返回 {id,title,messageCount,updatedAt} 且按 updateAt 倒序。
   * 「最新有对话的上下文」= 优先 messageCount>0 且 updatedAt 最大的一条。
   */
  function resolveConversationId() {
    if (!Bridge || typeof Bridge.listConversations !== 'function') return Promise.resolve(null);
    return Promise.resolve()
      .then(function () { return Bridge.listConversations(); })
      .then(parseBridge)
      .then(function (r) {
        var list = (r && r.conversations) || [];
        var withMessages = list.filter(function (c) {
          return c && c.id && Number(c.messageCount) > 0;
        });
        var chosen = withMessages.length ? withMessages[0] : (list[0] || null);
        return chosen && chosen.id ? chosen.id : null;
      })
      .catch(function () { return null; });
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

  function syncToChat(cardText, hiddenContext) {
    if (!cardText || !hiddenContext) return Promise.resolve({ synced: false, reason: 'NO_CARD' });
    if (studio) {
      studioEmit('card', cardText);
      if (hiddenContext.indexOf('落幕') >= 0) {
        studioEmit('ai', '我知道啦——刚才那是一场角色扮演游戏。面具摘下，我们还是我们。');
      } else if (hiddenContext.indexOf('请柬') >= 0) {
        studioEmit('ai', '我愿意。面具借我，这一支与你跳——不过说好了，天亮要还给我。');
      }
      return Promise.resolve({ synced: true, studio: true });
    }
    return resolveConversationId().then(function (id) {
      if (!id || typeof Bridge.sendChatMessage !== 'function') {
        return { synced: false, reason: 'NO_CONVERSATION' };
      }
      return Promise.resolve()
        .then(function () { return Bridge.sendChatMessage(id, cardText, hiddenContext); })
        .then(parseBridge)
        .then(function (r) { return { synced: !(r && r.success === false) }; })
        .catch(function () { return { synced: false, reason: 'BRIDGE_ERROR' }; });
    });
  }

  /* ---------- 舞会流程 ---------- */

  /*
   * 关键：请柬由 Bridge.sendChatMessage 发进主聊天（answer=true 会触发一次助手回复），
   * 助手就在用户看得见的对话框里应邀。旧版用一次独立的 AI 请求生成应邀台词，
   * 主聊天助手全程没参与，这是「对话框里邀请不到助手」的根因。
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
      text($('#invitation-text'), r.chatInviteCard || r.invitation);
      text($('#assistant-consent'), '正在把请柬送进当前聊天……');
      showPane('invited');
      return syncToChat(r.chatInviteCard, r.chatHiddenContext).then(function (s) {
        setBusy(false);
        if (s.synced) {
          text($('#assistant-consent'), s.studio
            ? '✓ 演示模式：请柬已放进右侧聊天流，助手在那里回应你。'
            : '✓ 请柬已发进当前聊天，助手正在对话框里回应。看到它应邀后再点「启幕」。');
        } else {
          text($('#assistant-consent'), '未同步到当前聊天；也可以直接在聊天里对助手说「我们玩一场假面舞会吧」，它会调用插件工具应邀。');
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

  function doAccept() {
    setBusy(true);
    callTool('masquerade_accept', { assistant_reply: state.consent || '' }).then(function (r) {
      setBusy(false);
      if (!r.success) { toast(failure(r)); return; }
      state.stage = r.stage;
      renderStage(r.stage || {});
      showPane('active');
      if (r.modelWarning) {
        appendBubble('system', r.modelWarning);
        toast('模型未配置：不会生成剧中台词');
      } else if (r.modelStatus === 'demo') {
        appendBubble('system', '浏览器预览：台词为本地演示剧本，不是真实模型回复。');
      }
      return syncToChat(r.chatCardText, r.chatHiddenContext);
    }).catch(function (e) { setBusy(false); toast(String(e)); });
  }

  function doCancelInvite() {
    callTool('masquerade_end', { reason: '未获应邀，收回请柬' }).then(function (r) {
      if (!r.success) { toast(failure(r)); return; }
      toast('请柬已收回');
      return loadStatus();
    });
  }

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
      text($('#sync-state'), '正在把压缩概要送进助手最新有对话的聊天……');
      $('#sync-state').className = 'sync-state';
      showPane('ended');
      return syncToChat(r.chatCardText, r.chatHiddenContext).then(function (s) {
        var el = $('#sync-state');
        if (s.synced) {
          el.className = 'sync-state';
          text(el, s.studio
            ? '✓ 演示模式：含压缩概要的落幕卡已放进右侧聊天流预览'
            : '✓ 压缩概要已作为可见消息写进助手最新有对话的聊天，后续对话能看见；完整台词只在演绎档案里');
        } else {
          el.className = 'sync-state warn';
          text(el, '未同步到聊天；落幕说明已写入助手长期记忆，完整记录可在「演绎档案」里回看。');
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
        if (!state.invitation) text($('#invitation-text'), '请柬已发进聊天，正在等助手应邀。');
        if (!state.consent) text($('#assistant-consent'), '助手会在当前聊天里回应；看到它应邀后点「启幕」。');
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
  $('#btn-accept').addEventListener('click', doAccept);
  $('#btn-cancel-invite').addEventListener('click', doCancelInvite);
  $('#btn-send-line').addEventListener('click', doSendLine);
  $('#line-input').addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSendLine(); }
  });
  $('#btn-end').addEventListener('click', doEnd);
  $('#btn-again').addEventListener('click', function () {
    state.invitation = null; state.consent = ''; state.closing = null;
    showPane('idle');
  });
  $('#btn-new-card').addEventListener('click', function () { openEditor(null); });
  $('#btn-save-card').addEventListener('click', saveCard);
  $('#btn-view-archive').addEventListener('click', function () {
    switchTab('archive');
    if (state.closing && state.closing.archiveId) openArchive(state.closing.archiveId);
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
