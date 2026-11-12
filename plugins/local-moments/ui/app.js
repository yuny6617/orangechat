/*
 * 本机朋友圈 · WebView 页面控制器
 *
 * 数据只走 window.OrangeBridge.localData（由宿主 lead 接线），
 * 助手互动只走 window.OrangeBridge.localDataTask 的真实 v1 method envelope。
 * 不读取模型、不调用 callAI、不使用 localStorage；浏览路径不触发模型。
 *
 * 页面只作为“本机用户”操作：请求参数里没有 actor/actorId/asAssistant，
 * 发布权限、助手配置和助手身份全部由宿主决定。
 */
(function () {
  'use strict';

  var C = window.MomentsCore;
  var B = window.MomentsBridge;

  var PLUGIN_VERSION = '1.0.0';
  var PLUGIN_ID = 'com.orangechat.localmoments';
  var DECLARED_PERMISSIONS = [
    'localdata.read',
    'localdata.write',
    'localdata.publish',
    'localdata.comment',
    'localdata.like',
    'localdata.media',
    'localdata.chat_snapshot',
    'localdata.backup'
  ];
  var HOST_WAIT_MS = 10000;

  var THEME_ALIASES = [
    { target: '--oc-bg', keys: ['--oc-bg', '--moments-background', '--background', '--color-background', '--surface'] },
    { target: '--oc-surface', keys: ['--oc-surface', '--moments-surface', '--cardBackground', '--card-background', '--color-surface', '--surface-container', '--surfaceContainer'] },
    { target: '--oc-card-top', keys: ['--oc-card-top', '--moments-card-top', '--primary-container', '--primaryContainer', '--surface-container-low', '--surfaceContainerLow'] },
    { target: '--oc-card-bottom', keys: ['--oc-card-bottom', '--moments-card-bottom', '--secondary-container', '--secondaryContainer', '--surface-container-high', '--surfaceContainerHigh'] },
    { target: '--oc-text', keys: ['--oc-text', '--moments-text', '--on-background', '--onBackground', '--on-surface', '--onSurface', '--color-on-surface', '--color-text'] },
    { target: '--oc-muted', keys: ['--oc-muted', '--moments-muted', '--on-surface-variant', '--onSurfaceVariant', '--color-on-surface-variant', '--mutedText', '--muted-text'] },
    { target: '--oc-accent', keys: ['--oc-accent', '--moments-accent', '--primary', '--color-primary'] },
    { target: '--oc-accent-text', keys: ['--oc-accent-text', '--on-primary', '--onPrimary', '--on-primary-container', '--onPrimaryContainer'] },
    { target: '--oc-border', keys: ['--oc-border', '--moments-border', '--outline-variant', '--outlineVariant', '--outline', '--color-outline', '--cardBorder', '--card-border'] },
    { target: '--oc-shadow', keys: ['--oc-shadow', '--shadow'] },
    { target: '--oc-danger', keys: ['--oc-danger', '--error'] }
  ];

  function emptyFilters() {
    return { query: '', authorKind: '', authorId: '', type: '', dateFrom: 0, dateTo: 0 };
  }

  var state = {
    route: '',
    wired: false,
    boot: {
      localReady: false,
      host: null,
      info: null,
      hostError: '',
      infoError: '',
      fatal: ''
    },
    prefs: { pageSize: C.DEFAULT_PAGE_SIZE },
    themeApplied: [],
    list: {
      items: [],
      cursor: '',
      hasMore: false,
      truncated: false,
      loading: false,
      loaded: false,
      error: null,
      filters: emptyFilters(),
      dropped: 0,
      cursorInvalid: false,
      requestId: 0
    },
    detail: {
      id: '',
      post: null,
      comments: [],
      cursor: '',
      hasMore: false,
      commentCount: 0,
      loading: false,
      commentLoading: false,
      error: null,
      replyToId: '',
      replyToName: '',
      commentClientKey: '',
      requestId: 0
    },
    drafts: {
      items: [],
      cursor: '',
      hasMore: false,
      loading: false,
      loaded: false,
      error: null,
      requestId: 0
    },
    author: {
      kind: '',
      id: '',
      author: null,
      posts: [],
      cursor: '',
      hasMore: false,
      loading: false,
      loaded: false,
      error: null,
      authorError: null,
      requestId: 0
    },
    editor: null,
    task: {
      busy: false,
      text: '宿主原生“互动”设置决定参与助手、预算与审核；本页只读，浏览不会触发模型。',
      kind: '',
      taskId: '',
      postId: '',
      status: null,
      statuses: [],
      settings: null,
      settingsError: null,
      settingsLoaded: false,
      settingsLoading: false,
      reviews: [],
      reviewsError: null,
      reviewsLoaded: false,
      reviewsLoading: false,
      reviewBusyId: ''
    },
    viewer: null,
    toastTimer: null,
    previewTimer: null,
    lastError: ''
  };

  function $(id) { return document.getElementById(id); }

  function clear(node) {
    if (!node) return;
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function el(tag, className, value) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined && value !== null) node.textContent = String(value);
    return node;
  }

  function show(node, visible) {
    if (node) node.hidden = !visible;
  }

  function button(label, className, onClick) {
    var node = el('button', className, label);
    node.type = 'button';
    if (onClick) node.addEventListener('click', guard(onClick));
    return node;
  }

  function guard(fn) {
    return function (event) {
      if (event && event.preventDefault) event.preventDefault();
      Promise.resolve().then(function () { return fn(event); }).catch(showError);
    };
  }

  function setStatus(id, message, kind) {
    var node = $(id);
    if (!node) return;
    node.textContent = message || '';
    if (kind) node.dataset.kind = kind; else node.removeAttribute('data-kind');
    node.hidden = !message;
  }

  function showNotice(message, kind) {
    var node = $('hostNotice');
    if (!node) return;
    node.textContent = message || '';
    if (kind) node.dataset.kind = kind; else node.removeAttribute('data-kind');
    node.hidden = !message;
  }

  function toast(message, kind) {
    var node = $('toast');
    if (!node) return;
    node.textContent = message || '';
    if (kind) node.dataset.kind = kind; else node.removeAttribute('data-kind');
    node.hidden = !message;
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(function () { node.hidden = true; }, kind === 'error' ? 5200 : 3200);
  }

  function showError(error) {
    var message = C.humanError(error);
    state.lastError = error && error.code ? String(error.code) : 'unknown';
    if (window.console && console.error) console.error('moments error:', state.lastError);
    showNotice(message, 'error');
    toast(message, 'error');
    if (state.route === 'settings') renderSettings();
  }

  function setBusy(node, busy, busyLabel) {
    if (!node) return;
    if (busy) {
      if (!node.dataset.idleLabel) node.dataset.idleLabel = node.textContent;
      node.disabled = true;
      if (busyLabel) node.textContent = busyLabel;
    } else {
      node.disabled = false;
      if (node.dataset.idleLabel) {
        node.textContent = node.dataset.idleLabel;
        delete node.dataset.idleLabel;
      }
    }
  }

  function emptyState(title, message, actionLabel, action) {
    var box = el('div', 'oc-empty');
    box.appendChild(el('strong', '', title));
    if (message) box.appendChild(el('p', 'oc-note', message));
    if (actionLabel && action) box.appendChild(button(actionLabel, 'oc-btn oc-btn-soft', action));
    return box;
  }

  /* ------------------------------------------------------------------ */
  /* 主题                                                                */
  /* ------------------------------------------------------------------ */

  function applyTheme(properties) {
    var root = document.documentElement;
    var applied = state.themeApplied || [];
    for (var i = 0; i < applied.length; i++) root.style.removeProperty(applied[i]);

    var keys = [];
    var provided = {};
    properties = Array.isArray(properties) ? properties : [];
    for (var j = 0; j < properties.length; j++) {
      root.style.setProperty(properties[j].key, properties[j].value);
      keys.push(properties[j].key);
      provided[properties[j].key] = true;
    }

    var computed = window.getComputedStyle ? window.getComputedStyle(root) : null;
    for (var g = 0; g < THEME_ALIASES.length; g++) {
      var group = THEME_ALIASES[g];
      if (provided[group.target]) continue;
      for (var k = 0; k < group.keys.length; k++) {
        if (!provided[group.keys[k]]) continue;
        var value = computed ? computed.getPropertyValue(group.keys[k]).trim() : '';
        if (value) {
          root.style.setProperty(group.target, value);
          keys.push(group.target);
          break;
        }
      }
    }

    state.themeApplied = keys;
    root.dataset.ocTheme = properties.length ? 'host' : 'fallback';
  }

  /* ------------------------------------------------------------------ */
  /* 数据访问                                                            */
  /* ------------------------------------------------------------------ */

  function loadInfoAndHost() {
    B.info().then(function (raw) {
      state.boot.info = C.normalizeInfo(raw);
      state.boot.infoError = '';
      if (state.route === 'settings') renderSettings();
    }).catch(function (error) {
      state.boot.infoError = C.humanError(error);
      if (state.route === 'settings') renderSettings();
    });

    B.hostInfo().then(function (host) {
      state.boot.host = host;
      state.boot.hostError = '';
      applyTheme(host.theme);
      populateAuthorFilter();
      showNotice('', '');
      if (state.route === 'settings') renderSettings();
      if (state.route === 'editor' && state.editor) {
        renderVisibility();
        if (!state.editor.dirty) renderEditor();
      }
    }).catch(function (error) {
      state.boot.host = null;
      state.boot.hostError = C.humanError(error);
      applyTheme([]);
      populateAuthorFilter();
      showNotice('宿主身份/主题接口不可用：' + C.humanError(error) + ' 动态读取仍会尝试。', 'warn');
      if (state.route === 'settings') renderSettings();
    });
  }

  function waitForLocalData(timeoutMs) {
    return new Promise(function (resolve) {
      var started = Date.now();
      (function tick() {
        if (B && B.ready()) return resolve(true);
        if (Date.now() - started >= timeoutMs) return resolve(false);
        setTimeout(tick, 120);
      })();
    });
  }

  function connect() {
    if (!B) {
      renderBridgeFatal('桥接脚本没有加载，无法调用 window.OrangeBridge。');
      return;
    }
    state.boot.localReady = false;
    setStatus('listStatus', '正在连接宿主 localData…', '');
    waitForLocalData(HOST_WAIT_MS).then(function (ok) {
      if (!ok) {
        renderBridgeFatal('未检测到 window.OrangeBridge.localData。需要宿主 lead 接线后，本页才会读取真实数据。');
        return;
      }
      state.boot.localReady = true;
      state.boot.fatal = '';
      setBusy($('btnCompose'), false);
      applyTheme([]);
      loadInfoAndHost();
      route();
    }).catch(showError);
  }

  function renderBridgeFatal(message) {
    state.boot.fatal = message;
    state.boot.localReady = false;
    showView('list');
    updateTabs('list');
    clear($('postList'));
    $('postList').appendChild(emptyState(
      '宿主尚未接线 localData',
      message + ' 本页不会显示示例动态、示例评论或模拟回复。',
      '重试连接',
      function () { connect(); }
    ));
    setStatus('listStatus', '未连接宿主；没有可显示的数据。', 'error');
    show('loadMore', false);
    show('restartList', false);
    setBusy($('btnCompose'), true);
    showNotice(message, 'error');
  }

  /* ------------------------------------------------------------------ */
  /* 路由                                                                */
  /* ------------------------------------------------------------------ */

  function parseRoute() {
    var raw = location.hash.replace(/^#\/?/, '');
    if (!raw || raw === 'list') return { view: 'list' };
    var parts = raw.split('/');
    try {
      for (var i = 0; i < parts.length; i++) parts[i] = decodeURIComponent(parts[i]);
    } catch (error) {
      return { view: 'list', invalid: true };
    }
    if (parts[0] === 'drafts') return { view: 'drafts' };
    if (parts[0] === 'settings') return { view: 'settings' };
    if (parts[0] === 'editor') return { view: 'editor' };
    if (parts[0] === 'post' && parts[1]) return { view: 'detail', id: parts[1] };
    if (parts[0] === 'author' && parts[1] && parts[2]) {
      return { view: 'author', kind: parts[1], id: parts[2] };
    }
    return { view: 'list', invalid: true };
  }

  function go(path) {
    var next = '#/' + path;
    if (location.hash === next) route(); else location.hash = next;
  }

  function navigate(path) {
    if (state.route === 'editor' && state.editor && state.editor.dirty && path !== 'editor') {
      if (!window.confirm('离开会丢失未保存的正文或图片。继续？')) return;
    }
    go(path);
  }

  function showView(name) {
    closeOtherSettings();
    var views = ['list', 'detail', 'editor', 'drafts', 'author', 'settings'];
    for (var i = 0; i < views.length; i++) {
      show($('view-' + views[i]), views[i] === name);
    }
    state.route = name;
    window.scrollTo(0, 0);
  }

  function updateTabs(active) {
    var tabs = document.querySelectorAll('[data-nav]');
    for (var i = 0; i < tabs.length; i++) {
      var nav = tabs[i].getAttribute('data-nav');
      var isActive = nav === active || (active === 'detail' || active === 'editor' || active === 'author') && nav === 'list';
      tabs[i].classList.toggle('is-active', isActive);
      tabs[i].setAttribute('aria-current', isActive ? 'page' : 'false');
    }
  }

  function releaseRoute(previous, next) {
    if (previous === 'editor' && next !== 'editor') {
      state.editor = null;
    }
    if (previous === 'detail' && next !== 'detail') {
      state.detail.requestId += 1;
      state.detail.loading = false;
      state.detail.post = null;
      state.detail.comments = [];
      state.detail.cursor = '';
      state.detail.hasMore = false;
      state.detail.id = '';
      state.detail.error = null;
      state.detail.commentClientKey = '';
      state.detail.replyToId = '';
      state.detail.replyToName = '';
    }
    if (previous === 'author' && next !== 'author') {
      state.author.requestId += 1;
      state.author.loading = false;
      state.author.posts = [];
      state.author.cursor = '';
      state.author.hasMore = false;
      state.author.loaded = false;
      state.author.error = null;
      state.author.authorError = null;
      state.author.author = null;
    }
  }

  function route() {
    var parsed = parseRoute();
    var next = parsed.view;
    if (parsed.invalid) {
      showNotice('地址无法识别，已回到动态列表。', 'warn');
      next = 'list';
    }
    var previous = state.route;
    releaseRoute(previous, next);
    if (state.boot.fatal && !state.boot.localReady) {
      renderBridgeFatal(state.boot.fatal);
      return;
    }
    showView(next);
    updateTabs(next);

    if (next === 'list') {
      if (!state.boot.localReady) {
        if (state.boot.fatal) renderBridgeFatal(state.boot.fatal);
        return;
      }
      if (!state.list.loaded && !state.list.loading) loadList(true);
      else renderList();
      return;
    }

    if (next === 'detail') {
      if (!C.safeRecordId(parsed.id)) {
        showNotice('动态编号无效。', 'error');
        go('list');
        return;
      }
      if (state.detail.id !== parsed.id || !state.detail.post) loadDetail(parsed.id, true);
      else renderDetail();
      return;
    }

    if (next === 'drafts') {
      if (!state.boot.localReady) return;
      if (!state.drafts.loaded && !state.drafts.loading) loadDrafts(true);
      else renderDrafts();
      return;
    }

    if (next === 'author') {
      if (!C.safeRecordId(parsed.id) || (parsed.kind !== 'user' && parsed.kind !== 'assistant')) {
        showNotice('作者编号无效。', 'error');
        go('list');
        return;
      }
      if (state.author.kind !== parsed.kind || state.author.id !== parsed.id || !state.author.loaded) {
        loadAuthor(parsed.kind, parsed.id);
      } else {
        renderAuthor();
      }
      return;
    }

    if (next === 'editor') {
      if (!state.editor) openEditor(null); else renderEditor();
      return;
    }

    if (next === 'settings') {
      if (!state.task.settingsLoaded) loadTaskSettings(false);
      if (!state.task.reviewsLoaded) loadTaskReviews(false);
      renderSettings();
    }
  }

  /* ------------------------------------------------------------------ */
  /* 筛选                                                                */
  /* ------------------------------------------------------------------ */

  function populateAuthorFilter() {
    var select = $('filterAuthor');
    if (!select) return;
    var current = select.value;
    clear(select);
    var all = el('option', '', '全部作者');
    all.value = '';
    select.appendChild(all);

    var host = state.boot.host;
    if (!host) {
      var missing = el('option', '', '宿主未提供作者列表');
      missing.value = '';
      missing.disabled = true;
      select.appendChild(missing);
      select.value = '';
      return;
    }
    if (host.user) {
      var user = el('option', '', '本机用户' + (host.user.name ? ' · ' + host.user.name : ''));
      user.value = 'user:';
      select.appendChild(user);
    }
    for (var i = 0; i < host.assistants.length; i++) {
      var assistant = host.assistants[i];
      var option = el('option', '', '助手 · ' + (assistant.name || assistant.id));
      option.value = 'assistant:' + assistant.id;
      select.appendChild(option);
    }
    if (current) {
      for (var j = 0; j < select.options.length; j++) {
        if (select.options[j].value === current) { select.value = current; break; }
      }
    }
  }

  function updateFilterSummary() {
    var filters = state.list.filters;
    var bits = [];
    if (filters.query) bits.push('“' + filters.query + '”');
    if (filters.authorKind) {
      var label = filters.authorKind === 'user' ? '本机用户' : '助手';
      if (filters.authorId) {
        var host = state.boot.host;
        var name = '';
        if (host) {
          for (var i = 0; i < host.assistants.length; i++) {
            if (host.assistants[i].id === filters.authorId) name = host.assistants[i].name;
          }
        }
        label += name ? ' · ' + name : '';
      }
      bits.push(label);
    }
    if (filters.type) bits.push(C.typeLabel(filters.type));
    if (filters.dateFrom || filters.dateTo) {
      var from = filters.dateFrom ? new Date(filters.dateFrom) : null;
      var to = filters.dateTo ? new Date(filters.dateTo) : null;
      bits.push((from ? from.toLocaleDateString() : '…') + ' → ' + (to ? to.toLocaleDateString() : '…'));
    }
    $('filterSummary').textContent = bits.length ? bits.join(' · ') : '全部动态';
  }

  function readFilters() {
    var query = $('filterQuery').value.trim();
    if (query.length > C.MAX_SEARCH_CHARS) throw C.apiError('invalid_request', '关键词最长 100 字');
    var authorValue = $('filterAuthor').value;
    var authorKind = '';
    var authorId = '';
    if (authorValue) {
      var index = authorValue.indexOf(':');
      if (index > 0) {
        authorKind = authorValue.slice(0, index);
        authorId = authorValue.slice(index + 1);
      }
    }
    var type = $('filterType').value;
    var dateFrom = C.dateInputToMs($('filterFrom').value, false);
    var dateTo = C.dateInputToMs($('filterTo').value, true);
    if (dateFrom && dateTo && dateFrom > dateTo) throw C.apiError('invalid_request', '起始日期不能晚于结束日期');
    return {
      query: query,
      authorKind: authorKind,
      authorId: authorId,
      type: type,
      dateFrom: dateFrom,
      dateTo: dateTo
    };
  }

  function applyFilters() {
    state.list.filters = readFilters();
    updateFilterSummary();
    loadList(true);
  }

  function resetFilters() {
    $('filterQuery').value = '';
    $('filterAuthor').value = '';
    $('filterType').value = '';
    $('filterFrom').value = '';
    $('filterTo').value = '';
    state.list.filters = emptyFilters();
    updateFilterSummary();
    loadList(true);
  }

  /* ------------------------------------------------------------------ */
  /* 动态列表                                                            */
  /* ------------------------------------------------------------------ */

  function loadList(reset) {
    if (!state.boot.localReady) return;
    if (!reset && state.list.loading) return;
    if (!reset && (!state.list.hasMore || !state.list.cursor)) return;
    if (!reset && state.list.items.length >= C.MAX_LIST_POSTS) {
      renderList();
      return;
    }
    if (reset) {
      state.list.requestId += 1;
      state.list.items = [];
      state.list.cursor = '';
      state.list.hasMore = true;
      state.list.truncated = false;
      state.list.loaded = false;
      state.list.dropped = 0;
      state.list.cursorInvalid = false;
      state.list.error = null;
    }
    var requestId = state.list.requestId;
    state.list.loading = true;
    setStatus('listStatus', '正在读取宿主本地 store…', '');
    var filters = state.list.filters;
    var search = !!filters.query;
    var params = C.buildListParams(filters, state.list.cursor, state.prefs.pageSize, search);
    var method = search ? 'moments.search' : 'moments.list';

    B.call(method, params).then(function (raw) {
      if (requestId !== state.list.requestId) return;
      var page = C.normalizePage(raw);
      var appended = C.boundedAppend(state.list.items, page.items, C.MAX_LIST_POSTS);
      state.list.items = appended.items;
      state.list.dropped += appended.dropped;
      state.list.cursor = page.nextCursor;
      state.list.hasMore = page.hasMore && !!page.nextCursor;
      state.list.truncated = page.truncated;
      state.list.cursorInvalid = page.cursorInvalid;
      state.list.loaded = true;
      state.list.loading = false;
      renderList();
    }).catch(function (error) {
      if (requestId !== state.list.requestId) return;
      state.list.loading = false;
      state.list.loaded = true;
      state.list.error = error;
      renderList();
      showError(error);
    });
  }

  function renderList() {
    clear($('postList'));
    var items = state.list.items;
    var filtersActive = !!(state.list.filters.query || state.list.filters.authorKind || state.list.filters.type || state.list.filters.dateFrom || state.list.filters.dateTo);

    if (state.list.loading && !items.length) {
      $('postList').appendChild(emptyState('正在读取…', '只查询宿主本地 store，不触发模型。'));
    } else if (state.list.error && !items.length) {
      $('postList').appendChild(emptyState('读取失败', C.humanError(state.list.error), '重试', function () { loadList(true); }));
    } else if (!items.length && state.list.loaded) {
      $('postList').appendChild(emptyState(
        filtersActive ? '没有匹配的动态' : '还没有动态',
        filtersActive ? '调整关键词、作者、类型或日期后再试。' : '发布第一条本机动态，或稍后刷新。',
        filtersActive ? '清除筛选' : '发布第一条',
        filtersActive ? function () { resetFilters(); } : function () { navigate('editor'); openEditor(null); }
      ));
    }

    for (var i = 0; i < items.length; i++) {
      $('postList').appendChild(renderPostCard(items[i], { context: 'list' }));
    }

    var atLimit = items.length >= C.MAX_LIST_POSTS;
    show($('loadMore'), !atLimit && state.list.hasMore && !state.list.loading);
    $('loadMore').disabled = state.list.loading;
    $('loadMore').textContent = state.list.loading ? '读取中…' : '加载更多';
    show($('restartList'), atLimit || state.list.cursorInvalid);

    var meta = ['已显示 ' + items.length + ' 条', '每页 ' + state.prefs.pageSize + ' 条'];
    if (state.list.truncated) meta.push('本页有截断');
    if (atLimit) meta.push('已达本次节点上限');
    $('listMeta').textContent = meta.join(' · ');

    if (atLimit) {
      setStatus('listStatus', '本次浏览最多保留 ' + C.MAX_LIST_POSTS + ' 条动态节点；更早内容请用搜索/筛选，或重新开始浏览。', 'warn');
    } else if (state.list.cursorInvalid) {
      setStatus('listStatus', '宿主返回了无法识别的游标，已停止继续加载；不会假装还有下一页。', 'warn');
    } else if (state.list.loading) {
      setStatus('listStatus', '正在读取宿主本地 store…', '');
    } else if (items.length && !state.list.hasMore) {
      setStatus('listStatus', '已到末尾；没有更多可见动态。', '');
    } else if (!items.length) {
      setStatus('listStatus', '', '');
    } else {
      setStatus('listStatus', '', '');
    }

    updateFilterSummary();
  }

  /* ------------------------------------------------------------------ */
  /* 动态卡片                                                            */
  /* ------------------------------------------------------------------ */

  function avatarNode(author, sizeClass) {
    var node = el('div', 'oc-avatar' + (sizeClass ? ' ' + sizeClass : ''));
    node.setAttribute('aria-hidden', 'true');
    if (author && author.avatar && C.safeAvatarUrl(author.avatar)) {
      var img = document.createElement('img');
      img.src = author.avatar;
      img.alt = '';
      img.loading = 'lazy';
      img.decoding = 'async';
      img.addEventListener('error', function () {
        clear(node);
        node.textContent = C.initials(author.name || C.authorKindLabel(author.kind));
      });
      node.appendChild(img);
    } else {
      node.textContent = C.initials(author && author.name ? author.name : '');
    }
    return node;
  }

  function openViewer(media, title) {
    if (!media) return;
    var original = media.originalUrl || media.thumbUrl;
    var thumb = media.thumbUrl || '';
    if (!C.safeMediaUrl(original)) {
      showNotice('图片地址不是受控的 https://localdata.invalid 媒体 URL；已拒绝加载。', 'error');
      return;
    }
    var image = $('viewerImage');
    $('viewerTitle').textContent = title || '图片';
    $('viewerNote').textContent = '按屏幕尺寸加载原图；关闭后释放 WebView 图片内存。';
    image.onerror = function () {
      if (thumb && image.src !== thumb) {
        image.src = thumb;
        $('viewerNote').textContent = '原图加载失败，已退回缩略图。';
      } else {
        $('viewerNote').textContent = '图片加载失败：宿主可能未接入 localdata 媒体拦截，或资源已被清理。';
      }
    };
    image.src = original;
    state.viewer = { media: media };
    show($('viewer'), true);
    $('viewerClose').focus();
  }

  function closeViewer() {
    if (!state.viewer && $('viewer').hidden) return;
    var image = $('viewerImage');
    image.onerror = null;
    image.removeAttribute('src');
    state.viewer = null;
    show($('viewer'), false);
  }

  function mediaItem(media, onOpen, label) {
    var item = el('button', 'oc-media-item');
    item.type = 'button';
    item.setAttribute('aria-label', label || '查看图片');
    if (!media || !media.available || !media.thumbUrl) {
      item.classList.add('is-broken');
      item.textContent = '图片不可用';
      return item;
    }
    var img = document.createElement('img');
    img.src = media.thumbUrl;
    img.alt = '';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.addEventListener('error', function () {
      item.classList.add('is-broken');
      clear(item);
      item.textContent = '缩略图加载失败';
      item.title = '宿主可能未接入 localdata 媒体拦截，或资源已被清理。';
    });
    item.appendChild(img);
    item.addEventListener('click', guard(function () { onOpen(media); }));
    return item;
  }

  function renderMediaGrid(mediaList, max, onOpen, onMore) {
    var grid = el('div', 'oc-media-grid');
    var list = Array.isArray(mediaList) ? mediaList : [];
    var count = Math.min(list.length, max);
    for (var i = 0; i < count; i++) {
      var hasMore = i === count - 1 && list.length > count;
      var handler = hasMore && onMore ? onMore : onOpen;
      var item = mediaItem(list[i], handler, hasMore ? '还有 ' + (list.length - count) + ' 张图片，查看详情' : '查看第 ' + (i + 1) + ' 张图片');
      if (hasMore) {
        var more = el('span', 'oc-media-more', '+' + (list.length - count));
        item.appendChild(more);
      }
      grid.appendChild(item);
    }
    return grid;
  }

  function chatImageItem(mediaId, onOpen) {
    var media = C.normalizeMedia({
      resourceId: mediaId,
      thumbnailUrl: C.mediaThumbForId(mediaId),
      originalUrl: C.mediaOriginalForId(mediaId)
    });
    if (!media) return null;
    return mediaItem(media, onOpen, '查看聊天记录图片');
  }

  function chatAvatarNode(message) {
    var node = el('div', 'oc-chat-avatar');
    node.setAttribute('aria-hidden', 'true');
    var mediaId = message.avatarMediaId;
    if (mediaId && C.safeMediaId(mediaId)) {
      var img = document.createElement('img');
      // Only the controlled localdata thumb origin, never the original avatar URL.
      img.src = C.mediaThumbForId(mediaId);
      img.alt = '';
      img.loading = 'lazy';
      img.decoding = 'async';
      img.addEventListener('error', function () {
        clear(node);
        node.textContent = C.initials(message.name || '');
      });
      node.appendChild(img);
    } else if (message.avatarEmoji) {
      node.textContent = message.avatarEmoji;
    } else {
      node.textContent = C.initials(message.name || '');
    }
    return node;
  }

  function renderChatCard(snapshot, messageLimit, charLimit, onOpen) {
    if (!snapshot || !snapshot.messages.length) return null;
    var box = el('div', 'oc-chatcard');
    var head = el('div', 'oc-chatcard-title');
    head.appendChild(el('strong', '', '结构化的聊天记录'));
    if (snapshot.title) head.appendChild(el('span', 'oc-meta', snapshot.title));
    box.appendChild(head);

    var used = 0;
    var shown = 0;
    var truncated = false;
    var imageBudget = 4;
    var imageOmitted = false;
    for (var i = 0; i < snapshot.messages.length; i++) {
      if (shown >= messageLimit || used >= charLimit) { truncated = true; break; }
      var message = snapshot.messages[i];
      var remaining = charLimit - used;
      var text = message.text || '';
      if (text.length > remaining) {
        text = text.slice(0, Math.max(0, remaining));
        truncated = true;
      }
      used += text.length;
      shown++;

      var line = el('div', 'oc-chat-line');
      line.appendChild(chatAvatarNode(message));
      var column = el('div', 'oc-chat-body');
      var role = el('span', 'oc-chat-role', message.role === 'user' ? '你' : (message.name || '助手'));
      column.appendChild(role);
      if (text) column.appendChild(el('p', 'oc-chat-text', text));
      if (message.media && message.media.length && imageBudget > 0) {
        var images = el('div', 'oc-chat-images');
        var renderedImages = 0;
        for (var j = 0; j < message.media.length && imageBudget > 0; j++) {
          var imageNode = chatImageItem(message.media[j], onOpen);
          if (imageNode) { images.appendChild(imageNode); imageBudget--; renderedImages++; }
        }
        if (images.firstChild) column.appendChild(images);
        if (message.media.length > renderedImages) imageOmitted = true;
      } else if (message.media && message.media.length) {
        imageOmitted = true;
      }
      line.appendChild(column);
      box.appendChild(line);
    }

    if (truncated || shown < snapshot.messages.length) {
      box.appendChild(el('p', 'oc-note', '按页面预算结构化显示 ' + shown + ' / ' + snapshot.messages.length + ' 条；其余不生成聊天长图。'));
    }
    if (imageOmitted) box.appendChild(el('p', 'oc-note', '聊天图片按页面预算只展开前 4 张缩略图；其余不自动加载。'));
    return box;
  }

  function commentPreviewNode(comment) {
    var row = el('div', 'oc-comment');
    var head = el('div', 'oc-comment-head');
    head.appendChild(el('span', 'oc-comment-name', comment.author.name || C.authorKindLabel(comment.author.kind)));
    head.appendChild(el('span', 'oc-comment-time', C.formatTime(comment.createdAt)));
    if (comment.replyToId) head.appendChild(el('span', 'oc-comment-reply', '回复某条评论'));
    row.appendChild(head);
    row.appendChild(el('p', 'oc-comment-body', comment.body));
    return row;
  }

  function renderPostCard(post, options) {
    options = options || {};
    var context = options.context || 'list';
    var preview = context === 'preview';
    var card = el('article', 'oc-card oc-post');
    card.setAttribute('aria-label', '动态：' + (post.author.name || C.authorKindLabel(post.author.kind)));

    var head = el('div', 'oc-post-head');
    var avatar = avatarNode(post.author, '');
    if (!preview) {
      var avatarButton = button('', 'oc-link-plain', function () {
        navigate('author/' + post.author.kind + '/' + encodeURIComponent(post.author.id));
      });
      avatarButton.appendChild(avatar);
      avatarButton.setAttribute('aria-label', '查看作者 ' + (post.author.name || C.authorKindLabel(post.author.kind)));
      head.appendChild(avatarButton);
    } else {
      head.appendChild(avatar);
    }

    var authorBox = el('div', 'oc-post-author');
    var nameText = post.author.name || C.authorKindLabel(post.author.kind);
    if (!preview) {
      var nameButton = button(nameText, 'oc-link-plain oc-post-name', function () {
        navigate('author/' + post.author.kind + '/' + encodeURIComponent(post.author.id));
      });
      authorBox.appendChild(nameButton);
    } else {
      authorBox.appendChild(el('p', 'oc-post-name', nameText));
    }
    var subBits = [C.authorKindLabel(post.author.kind)];
    if (post.createdAt) subBits.push(context === 'detail' ? C.formatFullTime(post.createdAt) : C.formatTime(post.createdAt));
    subBits.push(C.visibilityLabel(post.visibility));
    authorBox.appendChild(el('p', 'oc-post-sub', subBits.join(' · ')));
    head.appendChild(authorBox);

    if (!preview) {
      var moreButton = button('⋯', 'oc-icon-btn oc-post-more', function () {});
      moreButton.setAttribute('aria-label', '更多操作');
      moreButton.setAttribute('aria-expanded', 'false');
      var menu = el('div', 'oc-menu');
      menu.hidden = true;
      menu.appendChild(button('查看作者', 'oc-btn oc-btn-soft', function () {
        navigate('author/' + post.author.kind + '/' + encodeURIComponent(post.author.id));
      }));
      if (post.body) {
        menu.appendChild(button('复制正文', 'oc-btn oc-btn-soft', function () { return copyText(post.body); }));
      }
      if (post.author.kind === 'user') {
        menu.appendChild(button('删除动态', 'oc-btn oc-btn-soft is-danger', function () { return deletePost(post); }));
      }
      moreButton.addEventListener('click', function () {
        menu.hidden = !menu.hidden;
        moreButton.setAttribute('aria-expanded', menu.hidden ? 'false' : 'true');
      });
      var menuWrap = el('div', '');
      menuWrap.appendChild(moreButton);
      menuWrap.appendChild(menu);
      head.appendChild(menuWrap);
    }
    card.appendChild(head);

    if (post.body) card.appendChild(el('p', 'oc-post-body', post.body));
    else if (!post.media.length && !post.chatSnapshot) card.appendChild(el('p', 'oc-note', '（无正文）'));

    var notes = C.truncationNotes(post);
    for (var n = 0; n < notes.length; n++) card.appendChild(el('p', 'oc-note oc-note-warn', notes[n]));

    if (post.media.length) {
      var maxMedia = options.maxMedia || (context === 'list' ? C.MAX_LIST_PREVIEW_MEDIA : C.MAX_MEDIA_PER_POST);
      card.appendChild(renderMediaGrid(post.media, maxMedia, function (media) {
        openViewer(media, '图片 · ' + (post.author.name || C.authorKindLabel(post.author.kind)));
      }, context === 'list' ? function () {
        navigate('post/' + encodeURIComponent(post.id));
      } : null));
    }

    if (post.chatSnapshot) {
      var chat = renderChatCard(
        post.chatSnapshot,
        context === 'list' ? C.MAX_CHAT_MESSAGES_LIST : C.MAX_CHAT_MESSAGES_DETAIL,
        context === 'list' ? C.MAX_CHAT_CHARS_LIST : C.MAX_CHAT_CHARS_DETAIL,
        function (media) { openViewer(media, '聊天记录图片'); }
      );
      if (chat) card.appendChild(chat);
    }

    if (!preview) {
      var actions = el('div', 'oc-post-actions');
      var likeButton = button('', 'oc-action-btn', function () { return toggleLike(post, likeButton); });
      updateLikeButton(likeButton, post);
      actions.appendChild(likeButton);

      var commentButton = button('评论 ' + (post.commentCount || 0), 'oc-action-btn', function () {
        if (context === 'detail') {
          var body = $('commentBody');
          if (body) body.focus();
        } else {
          navigate('post/' + encodeURIComponent(post.id));
        }
      });
      commentButton.setAttribute('aria-label', '查看评论，共 ' + (post.commentCount || 0) + ' 条');
      actions.appendChild(commentButton);

      if (context === 'list') {
        actions.appendChild(button('查看详情', 'oc-action-btn', function () {
          navigate('post/' + encodeURIComponent(post.id));
        }));
      }
      if (context === 'detail') {
        var interactionButton = button('让助手互动', 'oc-action-btn', function () {
          return startInteraction(post);
        });
        interactionButton.setAttribute('aria-label', '让本机助手真实互动（会调用模型）');
        actions.appendChild(interactionButton);
      }
      card.appendChild(actions);
      if (context === 'detail' && state.task.postId === post.id) {
        card.appendChild(el('p', 'oc-note', state.task.text));
      }
    }

    if (context !== 'detail' && post.previewComments && post.previewComments.length) {
      var previewBox = el('div', 'oc-comments');
      for (var c = 0; c < post.previewComments.length; c++) previewBox.appendChild(commentPreviewNode(post.previewComments[c]));
      card.appendChild(previewBox);
      if (post.commentCount > post.previewComments.length) {
        card.appendChild(button('查看全部 ' + post.commentCount + ' 条评论', 'oc-link-btn', function () {
          navigate('post/' + encodeURIComponent(post.id));
        }));
      }
    }

    return card;
  }

  function updateLikeButton(node, post) {
    node.textContent = (post.likedByMe ? '♥ ' : '♡ ') + (post.likeCount || 0);
    node.classList.toggle('is-liked', !!post.likedByMe);
    node.setAttribute('aria-pressed', post.likedByMe ? 'true' : 'false');
    node.setAttribute('aria-label', post.likedByMe ? '取消点赞，当前 ' + (post.likeCount || 0) + ' 个赞' : '点赞，当前 ' + (post.likeCount || 0) + ' 个赞');
  }

  function toggleLike(post, node) {
    if (!post || !C.safeRecordId(post.id)) throw C.apiError('invalid_request', '动态编号无效');
    if (node.disabled) return;
    node.disabled = true;
    B.call('moments.like.set', C.buildLikeParams(post.id, !post.likedByMe)).then(function (raw) {
      var data = raw || {};
      if (typeof data.liked !== 'boolean' || typeof data.likeCount !== 'number') {
        throw C.apiError('protocol', 'moments.like.set did not return liked/likeCount');
      }
      post.likedByMe = data.liked;
      post.likeCount = data.likeCount;
      updateLikeButton(node, post);
      node.disabled = false;
    }).catch(function (error) {
      node.disabled = false;
      showError(error);
    });
  }

  function copyText(value) {
    var text = String(value || '');
    if (!text) throw C.apiError('invalid_request', '没有可复制的正文');
    if (!navigator.clipboard || !navigator.clipboard.writeText) {
      throw C.apiError('clipboard', '当前宿主 WebView 不支持剪贴板写入');
    }
    return navigator.clipboard.writeText(text).then(function () { toast('正文已复制'); });
  }

  function deletePost(post) {
    if (!post || !C.safeRecordId(post.id)) throw C.apiError('invalid_request', '动态编号无效');
    if (!window.confirm('删除这条动态？删除后宿主会按 ACL 逻辑删除，不会伪造成功。')) return Promise.resolve();
    return B.call('moments.delete', C.buildDeleteParams(post.id)).then(function (raw) {
      var data = raw || {};
      if (data.deleted === false) throw C.apiError('conflict', 'Host reported deleted=false');
      if (data.deleted !== true) throw C.apiError('protocol', 'moments.delete did not confirm deleted=true');
      state.list.items = state.list.items.filter(function (item) { return item.id !== post.id; });
      state.author.posts = state.author.posts.filter(function (item) { return item.id !== post.id; });
      if (state.detail.post && state.detail.post.id === post.id) {
        navigate('list');
      } else if (state.route === 'author') {
        renderAuthor();
      } else {
        renderList();
      }
      toast('动态已删除');
    });
  }

  /* ------------------------------------------------------------------ */
  /* 详情                                                                */
  /* ------------------------------------------------------------------ */

  function loadDetail(id, reset) {
    if (state.detail.loading && state.detail.id === id) return;
    state.detail.requestId += 1;
    var requestId = state.detail.requestId;
    state.detail.loading = true;
    state.detail.error = null;
    if (reset) {
      state.detail.comments = [];
      state.detail.cursor = '';
      state.detail.hasMore = false;
      state.detail.commentCount = 0;
      state.detail.replyToId = '';
      state.detail.replyToName = '';
      state.detail.commentClientKey = '';
      var commentInput = $('commentBody');
      if (commentInput) commentInput.value = '';
      updateCommentCountLabel();
      renderReplyState();
    }
    state.detail.id = id;
    clear($('detailPost'));
    $('detailPost').appendChild(emptyState('正在读取动态…', '详情只查询宿主本地 store。'));

    B.call('moments.get', C.buildDetailParams(id, reset ? '' : state.detail.cursor)).then(function (raw) {
      if (requestId !== state.detail.requestId) return;
      var data = raw || {};
      var post = C.normalizePost(data.post);
      if (!post) throw C.apiError('protocol', 'moments.get did not return a valid post');
      var comments = data.comments || {};
      var page = C.normalizePage(comments, { normalize: C.normalizeComment });
      state.detail.post = post;
      state.detail.comments = page.items.slice(0, C.MAX_DETAIL_COMMENTS);
      state.detail.cursor = page.nextCursor;
      state.detail.hasMore = page.hasMore && !!page.nextCursor;
      state.detail.commentCount = typeof comments.commentCount === 'number' ? comments.commentCount : post.commentCount;
      state.detail.loading = false;
      state.detail.error = null;
      renderDetail();
    }).catch(function (error) {
      if (requestId !== state.detail.requestId) return;
      state.detail.loading = false;
      state.detail.error = error;
      renderDetail();
      showError(error);
    });
  }

  function renderDetail() {
    clear($('detailPost'));
    clear($('detailComments'));
    $('detailCommentCount').textContent = '';
    if (state.detail.loading) {
      $('detailPost').appendChild(emptyState('正在读取动态…', '详情只查询宿主本地 store。'));
      return;
    }
    if (state.detail.error || !state.detail.post) {
      $('detailPost').appendChild(emptyState(
        '动态不可读',
        state.detail.error ? C.humanError(state.detail.error) : '内容不存在或当前身份不可见。',
        '返回动态',
        function () { navigate('list'); }
      ));
      return;
    }
    $('detailPost').appendChild(renderPostCard(state.detail.post, {
      context: 'detail',
      maxMedia: C.MAX_MEDIA_PER_POST
    }));
    renderDetailComments();
  }

  function renderDetailComments() {
    var list = $('detailComments');
    clear(list);
    var comments = state.detail.comments;
    $('detailCommentCount').textContent = '(' + (state.detail.commentCount || comments.length) + ')';

    if (!comments.length) {
      list.appendChild(emptyState('还没有评论', '评论写入宿主本地 store，不会触发模型。'));
    } else {
      for (var i = 0; i < comments.length; i++) {
        list.appendChild(renderCommentRow(comments[i]));
      }
    }

    var atLimit = comments.length >= C.MAX_DETAIL_COMMENTS;
    var more = $('detailLoadComments');
    show(more, !atLimit && state.detail.hasMore && !state.detail.commentLoading);
    more.disabled = state.detail.commentLoading;
    more.textContent = state.detail.commentLoading ? '读取中…' : '加载更多评论';
    if (atLimit) {
      list.appendChild(el('p', 'oc-note', '评论节点已达到本次上限 ' + C.MAX_DETAIL_COMMENTS + ' 条；更早评论请返回后用搜索/作者页查看。'));
    }
  }

  function renderCommentRow(comment) {
    var row = el('div', 'oc-comment');
    var head = el('div', 'oc-comment-head');
    head.appendChild(el('span', 'oc-comment-name', comment.author.name || C.authorKindLabel(comment.author.kind)));
    head.appendChild(el('span', 'oc-comment-time', C.formatTime(comment.createdAt)));
    if (comment.replyToId) {
      var target = findComment(comment.replyToId);
      head.appendChild(el('span', 'oc-comment-reply', '回复 ' + (target && target.author.name ? target.author.name : '某条评论')));
    }
    row.appendChild(head);
    row.appendChild(el('p', 'oc-comment-body', comment.body));
    var actions = el('div', 'oc-comment-actions');
    actions.appendChild(button('回复', 'oc-link-btn', function () {
      state.detail.replyToId = comment.id;
      state.detail.replyToName = comment.author.name || C.authorKindLabel(comment.author.kind);
      renderReplyState();
      var body = $('commentBody');
      if (body) body.focus();
    }));
    row.appendChild(actions);
    return row;
  }

  function findComment(id) {
    for (var i = 0; i < state.detail.comments.length; i++) {
      if (state.detail.comments[i].id === id) return state.detail.comments[i];
    }
    if (state.detail.post && state.detail.post.previewComments) {
      for (var j = 0; j < state.detail.post.previewComments.length; j++) {
        if (state.detail.post.previewComments[j].id === id) return state.detail.post.previewComments[j];
      }
    }
    return null;
  }

  function renderReplyState() {
    var box = $('commentReply');
    if (!box) return;
    if (state.detail.replyToId) {
      $('commentReplyText').textContent = '回复 ' + (state.detail.replyToName || '某条评论');
      show(box, true);
    } else {
      show(box, false);
    }
  }

  function clearReply() {
    state.detail.replyToId = '';
    state.detail.replyToName = '';
    renderReplyState();
  }

  function loadMoreComments() {
    if (!state.detail.post || state.detail.commentLoading || !state.detail.hasMore || !state.detail.cursor) return;
    var requestId = state.detail.requestId;
    state.detail.commentLoading = true;
    renderDetailComments();
    B.call('moments.comments.list', C.buildCommentsParams(state.detail.post.id, state.detail.cursor, 3)).then(function (raw) {
      if (requestId !== state.detail.requestId) return;
      var page = C.normalizePage(raw, { normalize: C.normalizeComment });
      var appended = C.boundedAppend(state.detail.comments, page.items, C.MAX_DETAIL_COMMENTS);
      state.detail.comments = appended.items;
      state.detail.cursor = page.nextCursor;
      state.detail.hasMore = page.hasMore && !!page.nextCursor;
      state.detail.commentCount = Math.max(state.detail.commentCount, state.detail.comments.length);
      state.detail.commentLoading = false;
      renderDetailComments();
    }).catch(function (error) {
      if (requestId !== state.detail.requestId) return;
      state.detail.commentLoading = false;
      renderDetailComments();
      showError(error);
    });
  }

  function updateCommentCountLabel() {
    var body = $('commentBody');
    var length = body ? body.value.length : 0;
    $('commentCount').textContent = length + ' / ' + C.MAX_COMMENT_CHARS;
  }

  function submitComment() {
    if (!state.detail.post) throw C.apiError('invalid_request', '没有可评论的动态');
    var input = $('commentBody');
    var body = input.value.trim();
    if (!body) throw C.apiError('invalid_request', '评论不能为空');
    if (body.length > C.MAX_COMMENT_CHARS) throw C.apiError('invalid_request', '评论最长 2000 字');
    if (!state.detail.commentClientKey) state.detail.commentClientKey = C.makeClientKey('comment');
    var requestId = state.detail.requestId;
    var submit = $('commentSubmit');
    setBusy(submit, true, '提交中…');
    var params = C.buildCommentCreateParams(state.detail.post.id, body, state.detail.replyToId, state.detail.commentClientKey);

    B.call('moments.comment.create', params).then(function (raw) {
      if (requestId !== state.detail.requestId) {
        setBusy(submit, false);
        toast('评论已保存；页面已切换，请在详情中刷新查看');
        return;
      }
      var data = raw || {};
      var comment = C.normalizeComment(data.comment);
      if (!comment) throw C.apiError('protocol', 'moments.comment.create did not return a valid comment');
      var appended = C.boundedAppend(state.detail.comments, [comment], C.MAX_DETAIL_COMMENTS);
      state.detail.comments = appended.items;
      state.detail.commentCount = typeof data.commentCount === 'number' ? data.commentCount : state.detail.commentCount + 1;
      state.detail.post.commentCount = state.detail.commentCount;
      state.detail.post.previewComments = state.detail.comments.slice(0, 3);
      state.detail.commentClientKey = '';
      state.detail.replyToId = '';
      state.detail.replyToName = '';
      input.value = '';
      updateCommentCountLabel();
      renderReplyState();
      renderDetail();
      setBusy(submit, false);
      toast(appended.dropped ? '评论已保存；本次页面节点已达上限，刷新详情查看新评论' : '评论已写入宿主本地 store', appended.dropped ? 'warn' : '');
    }).catch(function (error) {
      setBusy(submit, false);
      showError(error);
    });
  }

  /* ------------------------------------------------------------------ */
  /* 编辑器                                                              */
  /* ------------------------------------------------------------------ */

  function openEditor(draft) {
    var editor = {
      draftId: draft ? draft.id : '',
      body: draft ? draft.body : '',
      media: draft ? draft.media.slice() : [],
      visibility: draft ? draft.visibility : 'all_assistants',
      selected: draft ? (draft.selectedAssistantIds || []).slice() : [],
      chatSnapshot: draft ? draft.chatSnapshot : null,
      clientKey: C.makeClientKey('post'),
      dirty: false,
      mode: draft ? 'draft' : 'new',
      picking: false,
      generated: { open: false, loading: false, loaded: false, items: [], error: '', truncated: false }
    };
    if (!editor.visibility) editor.visibility = 'all_assistants';
    state.editor = editor;
    state.detail.commentClientKey = '';
    go('editor');
  }

  function markEditorDirty(rotateKey) {
    if (!state.editor) return;
    state.editor.dirty = true;
    if (rotateKey) state.editor.clientKey = C.makeClientKey('post');
  }

  function renderEditor() {
    var editor = state.editor;
    if (!editor) return;
    $('editorTitle').textContent = editor.mode === 'draft' ? '编辑草稿' : '发布动态';
    $('editorMode').textContent = editor.mode === 'draft' ? '草稿 · ' + editor.draftId : '新动态';
    $('editorBody').value = editor.body;
    updateEditorCount();
    renderEditorMedia();
    renderVisibility();
    renderEditorChatSnapshot();
    renderEditorPreview();
  }

  function updateEditorCount() {
    $('editorBodyCount').textContent = (state.editor ? state.editor.body.length : 0) + ' / ' + C.MAX_BODY_CHARS;
  }

  function renderEditorMedia() {
    var grid = $('editorMedia');
    clear(grid);
    var editor = state.editor;
    var media = editor ? editor.media : [];
    for (var i = 0; i < media.length; i++) {
      (function (item, index) {
        var cell = el('div', 'oc-media-cell');
        var view = mediaItem(item, function (value) { openViewer(value, '待发布图片'); }, '查看待发布图片');
        cell.appendChild(view);
        var remove = button('×', 'oc-media-remove', function () {
          if (!state.editor) return;
          state.editor.media.splice(index, 1);
          markEditorDirty(true);
          renderEditorMedia();
          renderEditorPreview();
        });
        remove.setAttribute('aria-label', '从本次编辑移除第 ' + (index + 1) + ' 张图片');
        cell.appendChild(remove);
        grid.appendChild(cell);
      })(media[i], i);
    }
    if (media.length < C.MAX_MEDIA_PER_POST) {
      var add = button('＋ 添加图片', 'oc-media-add', function () { return pickEditorImages(); });
      grid.appendChild(add);
    }
    $('editorAddImages').disabled = media.length >= C.MAX_MEDIA_PER_POST || !editor || !!editor.picking;
    $('editorMediaNote').textContent = '已选 ' + media.length + ' / ' + C.MAX_MEDIA_PER_POST +
      ' 张。列表只加载缩略图；移除只影响本次编辑，不删除宿主原图。';
    var generated = editor ? editorGenerated() : null;
    $('editorImportGenerated').disabled = media.length >= C.MAX_MEDIA_PER_POST ||
      !editor || !!editor.picking || !!(generated && generated.loading);
    $('editorGeneratedClose').disabled = !!(generated && generated.loading);
    renderGeneratedPicker();
  }

  function editorGenerated() {
    if (!state.editor) return null;
    if (!state.editor.generated) {
      state.editor.generated = { open: false, loading: false, loaded: false, items: [], error: '', truncated: false };
    }
    return state.editor.generated;
  }

  function renderGeneratedPicker() {
    var generated = state.editor ? state.editor.generated : null;
    show($('editorGeneratedPicker'), !!(generated && generated.open));
    if (!generated || !generated.open) return;
    $('editorGeneratedStatus').textContent = generated.loading
      ? '正在读取宿主图库…'
      : generated.error
        ? generated.error
        : !generated.items.length
          ? '宿主图库里没有可导入的已生成图片；本页不会补任何占位图。'
          : '共 ' + generated.items.length + ' 张，最新优先' +
            (generated.truncated ? '；只显示最新一批' : '') +
            '。列表只显示宿主给出的元数据；选中并确认后才复制原图，不会重新生成。';
    var list = $('editorGeneratedList');
    clear(list);
    for (var i = 0; i < generated.items.length; i++) {
      (function (item) {
        var row = button('', 'oc-generated-row', function () { return importGeneratedImage(item); });
        row.appendChild(el('strong', '', C.formatFullTime(item.createdAt) || '时间未知'));
        var details = [];
        if (item.width && item.height) details.push(item.width + '×' + item.height);
        if (item.byteSize) details.push(C.formatByteSize(item.byteSize));
        details.push(C.generatedSourceLabel(item.source));
        row.appendChild(el('span', 'oc-note', details.join(' · ')));
        list.appendChild(row);
      })(generated.items[i]);
    }
  }

  function toggleGeneratedPicker(open) {
    var generated = editorGenerated();
    if (!generated) return;
    generated.open = !!open;
    if (generated.open && !generated.loaded && !generated.loading) loadGeneratedImages();
    renderEditorMedia();
  }

  function loadGeneratedImages() {
    var generated = editorGenerated();
    if (!generated || generated.loading) return;
    generated.loading = true;
    generated.error = '';
    renderEditorMedia();
    return B.listGeneratedImages().then(function (result) {
      if (!state.editor || state.editor.generated !== generated) return;
      generated.loading = false;
      generated.loaded = true;
      generated.items = result.items;
      generated.truncated = result.truncated;
      renderEditorMedia();
    }).catch(function (error) {
      if (!state.editor || state.editor.generated !== generated) return;
      generated.loading = false;
      generated.loaded = false;
      generated.items = [];
      generated.truncated = false;
      generated.error = C.humanError(error);
      renderEditorMedia();
    });
  }

  function importGeneratedImage(item) {
    var generated = editorGenerated();
    if (!generated || !state.editor || state.editor.picking) return Promise.resolve();
    if (state.editor.media.length >= C.MAX_MEDIA_PER_POST) {
      toast('最多 ' + C.MAX_MEDIA_PER_POST + ' 张图片', 'warn');
      return Promise.resolve();
    }
    var confirmed = window.confirm('把这张已生成的图片复制到本次朋友圈草稿？\n不会重新生成图片，也不会消耗宿主生图额度。');
    if (!confirmed) return Promise.resolve();
    state.editor.picking = true;
    renderEditorMedia();
    return B.importGeneratedImage(item.id).then(function (result) {
      if (!state.editor) return;
      state.editor.picking = false;
      state.editor.media = state.editor.media.concat([result.media]);
      markEditorDirty(true);
      generated.open = false;
      renderEditorMedia();
      renderEditorPreview();
      toast('已从宿主图库导入 1 张已生成图片');
    }).catch(function (error) {
      if (!state.editor) return;
      state.editor.picking = false;
      renderEditorMedia();
      showError(error);
    });
  }

  function pickEditorImages() {
    if (!state.editor || state.editor.picking) return;
    state.editor.picking = true;
    renderEditorMedia();
    setStatus('listStatus', '', '');
    B.pickImages().then(function (result) {
      if (!state.editor) return;
      state.editor.picking = false;
      if (!result.media.length) {
        renderEditorMedia();
        toast('未选择图片');
        return;
      }
      var room = C.MAX_MEDIA_PER_POST - state.editor.media.length;
      var added = result.media.slice(0, Math.max(0, room));
      state.editor.media = state.editor.media.concat(added);
      if (added.length) markEditorDirty(true);
      renderEditorMedia();
      renderEditorPreview();
      if (added.length < result.media.length) toast('最多 9 张，已忽略超出的图片', 'warn');
      else if (result.rejected) toast('部分图片没有受控 resourceId，已忽略', 'warn');
      else toast('已加入 ' + added.length + ' 张图片');
    }).catch(function (error) {
      if (!state.editor) return;
      state.editor.picking = false;
      renderEditorMedia();
      if (error && (error.code === 'cancelled' || error.code === 'user_cancelled')) return;
      showError(error);
    });
  }

  function renderVisibility() {
    var box = $('editorVisibility');
    clear(box);
    var editor = state.editor;
    if (!editor) return;
    var options = [
      { value: 'all_assistants', label: '全部助手可见', desc: '所有本机助手都能读取这条动态（仍受宿主 ACL 执行）。' },
      { value: 'selected', label: '指定助手可见', desc: '只允许选中的本机助手读取。' },
      { value: 'self', label: '仅自己可见', desc: '只有本机用户身份能读取。' }
    ];
    var hasAssistants = !!(state.boot.host && state.boot.host.assistants.length);
    for (var i = 0; i < options.length; i++) {
      var option = options[i];
      var label = el('label', '');
      var input = document.createElement('input');
      input.type = 'radio';
      input.name = 'oc-visibility';
      input.value = option.value;
      input.checked = editor.visibility === option.value;
      if (option.value === 'selected' && !hasAssistants) input.disabled = true;
      input.addEventListener('change', function () {
        if (!this.checked) return;
        state.editor.visibility = this.value;
        if (this.value !== 'selected') state.editor.selected = [];
        markEditorDirty(true);
        renderVisibility();
        renderEditorPreview();
      });
      label.appendChild(input);
      var textBox = el('span', '');
      textBox.appendChild(el('span', '', option.label));
      textBox.appendChild(el('small', '', option.value === 'selected' && !hasAssistants
        ? '宿主未提供助手列表，不能选择指定助手。'
        : option.desc));
      label.appendChild(textBox);
      box.appendChild(label);
    }

    var assistantsBox = $('editorAssistants');
    clear(assistantsBox);
    if (editor.visibility !== 'selected') {
      show(assistantsBox, false);
      return;
    }
    show(assistantsBox, true);
    if (!hasAssistants) {
      assistantsBox.appendChild(el('p', 'oc-note oc-note-warn', '宿主未提供助手列表；不会伪造助手身份。请先让宿主接线 localDataHostInfo。'));
      return;
    }
    for (var j = 0; j < state.boot.host.assistants.length; j++) {
      (function (assistant) {
        var label = el('label', '');
        var checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.value = assistant.id;
        checkbox.checked = editor.selected.indexOf(assistant.id) >= 0;
        checkbox.addEventListener('change', function () {
          var index = state.editor.selected.indexOf(assistant.id);
          if (this.checked && index < 0 && state.editor.selected.length >= C.MAX_SELECTED_ASSISTANTS) {
            this.checked = false;
            toast('最多选择 ' + C.MAX_SELECTED_ASSISTANTS + ' 个助手', 'warn');
            return;
          }
          if (this.checked && index < 0) state.editor.selected.push(assistant.id);
          if (!this.checked && index >= 0) state.editor.selected.splice(index, 1);
          markEditorDirty(true);
          renderEditorPreview();
        });
        label.appendChild(checkbox);
        label.appendChild(el('span', '', assistant.name || assistant.id));
        assistantsBox.appendChild(label);
      })(state.boot.host.assistants[j]);
    }
  }

  function renderEditorChatSnapshot() {
    var box = $('editorChatSnapshot');
    clear(box);
    var editor = state.editor;
    if (!editor) return;
    box.appendChild(el('h3', '', '聊天记录卡片'));
    if (editor.chatSnapshot) {
      var chat = renderChatCard(editor.chatSnapshot, C.MAX_CHAT_MESSAGES_LIST, C.MAX_CHAT_CHARS_LIST, function (media) {
        openViewer(media, '聊天记录图片');
      });
      if (chat) box.appendChild(chat);
      box.appendChild(button('从本次编辑移除聊天记录卡片', 'oc-btn oc-btn-soft', function () {
        state.editor.chatSnapshot = null;
        markEditorDirty(true);
        renderEditorChatSnapshot();
        renderEditorPreview();
      }));
    } else {
      box.appendChild(el('p', 'oc-note', '本页不提供自建聊天快照。聊天记录卡片应由宿主聊天页的“分享到朋友圈”入口创建后，以结构化快照写入；这里只显示草稿中已有的快照。'));
    }
  }

  function previewPost() {
    var editor = state.editor;
    if (!editor) return null;
    return {
      id: 'preview',
      author: { kind: 'user', id: 'preview', name: '发布预览', avatar: '' },
      body: editor.body,
      bodyTruncated: false,
      visibility: editor.visibility,
      selectedAssistantIds: editor.selected,
      media: editor.media,
      mediaOmitted: false,
      chatSnapshot: editor.chatSnapshot,
      chatSnapshotOmitted: false,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      commentCount: 0,
      likeCount: 0,
      likedByMe: false,
      previewComments: []
    };
  }

  function renderEditorPreview() {
    var box = $('editorPreview');
    if (!box) return;
    clear(box);
    var editor = state.editor;
    if (!editor) return;
    if (!editor.body.trim() && !editor.media.length && !editor.chatSnapshot) {
      box.appendChild(el('p', 'oc-note', '输入正文、添加图片或草稿中已有聊天卡片后，这里显示结构化预览。'));
      return;
    }
    box.appendChild(renderPostCard(previewPost(), { context: 'preview' }));
  }

  function schedulePreview() {
    clearTimeout(state.previewTimer);
    state.previewTimer = setTimeout(function () {
      if (state.route === 'editor') renderEditorPreview();
    }, 140);
  }

  function validateEditor() {
    var editor = state.editor;
    if (!editor) throw C.apiError('invalid_request', '编辑器未初始化');
    if (!editor.body.trim() && !editor.media.length && !editor.chatSnapshot) {
      throw C.apiError('invalid_request', '正文、图片或聊天卡片至少要有一样');
    }
    if (editor.body.length > C.MAX_BODY_CHARS) throw C.apiError('invalid_request', '正文最长 20000 字');
    if (editor.media.length > C.MAX_MEDIA_PER_POST) throw C.apiError('invalid_request', '最多 9 张图片');
    if (editor.visibility === 'selected' && !editor.selected.length) {
      throw C.apiError('invalid_request', '选择“指定助手可见”时至少选一个助手');
    }
    if (editor.visibility === 'selected' && !(state.boot.host && state.boot.host.assistants.length)) {
      throw C.apiError('invalid_request', '宿主未提供助手列表，不能发布“指定助手可见”');
    }
    return editor;
  }

  function saveDraft() {
    var editor = validateEditor();
    var node = $('editorSaveDraft');
    setBusy(node, true, '保存中…');
    B.call('moments.draft.save', C.buildDraftParams(editor)).then(function (raw) {
      if (!state.editor) {
        setBusy(node, false);
        return;
      }
      var data = raw || {};
      var draft = C.normalizeDraft(data.draft || data);
      if (!draft) throw C.apiError('protocol', 'moments.draft.save did not return a valid draft');
      state.editor.draftId = draft.id;
      state.editor.mode = 'draft';
      state.editor.dirty = false;
      if (!draft.mediaOmitted) state.editor.media = draft.media;
      if (!draft.chatSnapshotOmitted) state.editor.chatSnapshot = draft.chatSnapshot;
      state.editor.visibility = draft.visibility;
      state.editor.selected = draft.selectedAssistantIds;
      setBusy(node, false);
      renderEditor();
      toast('草稿已保存到宿主 store');
    }).catch(function (error) {
      setBusy(node, false);
      showError(error);
    });
  }

  function publishEditor() {
    var editor = validateEditor();
    var visibility = C.visibilityLabel(editor.visibility);
    if (!window.confirm('发布这条动态？可见范围：' + visibility + '。发布写入宿主本地 store，不会联网。')) return Promise.resolve();
    var node = $('editorPublish');
    setBusy(node, true, '发布中…');

    /*
     * 宿主对 moments.create({draftId}) 的语义是“发布已保存的草稿”，只读草稿行，
     * 不会应用本次请求里的 body/media。因此编辑已有草稿时必须先 draft.save 更新，
     * 再 create({draftId}) 发布；否则用户看到的修改不会进入动态。
     */
    var operation = editor.draftId
      ? B.call('moments.draft.save', C.buildDraftParams(editor)).then(function (raw) {
        if (!state.editor) {
          setBusy(node, false);
          return null;
        }
        var data = raw || {};
        var draft = C.normalizeDraft(data.draft || data);
        if (!draft) throw C.apiError('protocol', 'moments.draft.save did not return a valid draft');
        return B.call('moments.create', C.buildPublishDraftParams(draft.id));
      })
      : B.call('moments.create', C.buildCreateParams(editor, editor.clientKey));

    operation.then(function (raw) {
      if (!raw) return;
      var data = raw || {};
      var post = C.normalizePost(data.post);
      if (!post) throw C.apiError('protocol', 'moments.create did not return a valid post');
      state.editor = null;
      state.list.loaded = false;
      state.list.items = [];
      state.drafts.loaded = false;
      setBusy(node, false);
      toast('已发布到本机动态');
      navigate('post/' + encodeURIComponent(post.id));
    }).catch(function (error) {
      setBusy(node, false);
      showError(error);
    });
  }

  /* ------------------------------------------------------------------ */
  /* 草稿箱                                                              */
  /* ------------------------------------------------------------------ */

  function loadDrafts(reset) {
    if (!state.boot.localReady) return;
    if (!reset && state.drafts.loading) return;
    if (!reset && (!state.drafts.hasMore || !state.drafts.cursor)) return;
    if (!reset && state.drafts.items.length >= C.MAX_DRAFT_ROWS) { renderDrafts(); return; }
    if (reset) {
      state.drafts.requestId += 1;
      state.drafts.items = [];
      state.drafts.cursor = '';
      state.drafts.hasMore = true;
      state.drafts.loaded = false;
      state.drafts.error = null;
    }
    var requestId = state.drafts.requestId;
    state.drafts.loading = true;
    $('draftMeta').textContent = '读取中…';
    var params = { limit: state.prefs.pageSize };
    if (!reset && state.drafts.cursor) params.cursor = state.drafts.cursor;

    B.call('moments.draft.list', params).then(function (raw) {
      if (requestId !== state.drafts.requestId) return;
      var page = C.normalizeDraftPage(raw);
      var appended = C.boundedAppend(state.drafts.items, page.items, C.MAX_DRAFT_ROWS);
      state.drafts.items = appended.items;
      state.drafts.cursor = page.nextCursor;
      state.drafts.hasMore = page.hasMore && !!page.nextCursor;
      state.drafts.loading = false;
      state.drafts.loaded = true;
      renderDrafts();
    }).catch(function (error) {
      if (requestId !== state.drafts.requestId) return;
      state.drafts.loading = false;
      state.drafts.loaded = true;
      state.drafts.error = error;
      renderDrafts();
      showError(error);
    });
  }

  function renderDrafts() {
    var list = $('draftList');
    clear(list);
    var items = state.drafts.items;
    $('draftMeta').textContent = items.length + ' 条';
    if (state.drafts.loading && !items.length) {
      list.appendChild(emptyState('正在读取草稿…', '草稿只从宿主 store 读取。'));
    } else if (state.drafts.error && !items.length) {
      list.appendChild(emptyState('草稿读取失败', C.humanError(state.drafts.error), '重试', function () { loadDrafts(true); }));
    } else if (!items.length && state.drafts.loaded) {
      list.appendChild(emptyState('草稿箱是空的', '在发布页点“保存草稿”，草稿会写入宿主 store。', '去发布', function () { navigate('editor'); openEditor(null); }));
    }
    for (var i = 0; i < items.length; i++) {
      list.appendChild(renderDraftCard(items[i]));
    }
    var atLimit = items.length >= C.MAX_DRAFT_ROWS;
    var more = $('draftLoadMore');
    show(more, !atLimit && state.drafts.hasMore && !state.drafts.loading);
    more.disabled = state.drafts.loading;
    more.textContent = state.drafts.loading ? '读取中…' : '加载更多草稿';
    if (atLimit) list.appendChild(el('p', 'oc-note', '草稿节点已达到本次上限 ' + C.MAX_DRAFT_ROWS + ' 条。'));
  }

  function renderDraftCard(draft) {
    var card = el('article', 'oc-card oc-draft');
    var head = el('div', 'oc-post-head');
    head.appendChild(avatarNode(draft.author, ''));
    var box = el('div', 'oc-post-author');
    box.appendChild(el('p', 'oc-post-name', draft.author.name || C.authorKindLabel(draft.author.kind)));
    var bits = ['草稿', C.visibilityLabel(draft.visibility), '更新于 ' + C.formatTime(draft.updatedAt)];
    if (draft.media.length) bits.push(draft.media.length + ' 张图片');
    if (draft.chatSnapshot) bits.push('聊天卡片');
    box.appendChild(el('p', 'oc-post-sub', bits.join(' · ')));
    head.appendChild(box);
    card.appendChild(head);
    card.appendChild(el('p', 'oc-draft-body', draft.body || '（无正文）'));

    var actions = el('div', 'oc-draft-actions');
    actions.appendChild(button('继续编辑', 'oc-btn oc-btn-primary', function () { openEditor(draft); }));
    actions.appendChild(button('删除草稿', 'oc-btn oc-btn-soft is-danger', function () {
      if (!window.confirm('删除这份草稿？')) return;
      return B.call('moments.draft.delete', C.buildDraftDeleteParams(draft.id)).then(function (raw) {
        var data = raw || {};
        if (data.deleted === false) throw C.apiError('conflict', 'Host reported deleted=false');
        if (data.deleted !== true) throw C.apiError('protocol', 'moments.draft.delete did not confirm deleted=true');
        state.drafts.items = state.drafts.items.filter(function (item) { return item.id !== draft.id; });
        renderDrafts();
        toast('草稿已删除');
      });
    }));
    card.appendChild(actions);
    return card;
  }

  /* ------------------------------------------------------------------ */
  /* 作者主页                                                            */
  /* ------------------------------------------------------------------ */

  function loadAuthor(kind, id) {
    if (state.author.loading && state.author.kind === kind && state.author.id === id) return;
    state.author.requestId += 1;
    var requestId = state.author.requestId;
    state.author = {
      kind: kind,
      id: id,
      author: null,
      posts: [],
      cursor: '',
      hasMore: false,
      loading: true,
      loaded: false,
      error: null,
      authorError: null,
      requestId: requestId
    };
    clear($('authorPosts'));
    $('authorPosts').appendChild(emptyState('正在读取作者主页…', '只查询宿主本地 store。'));
    $('authorHeader').textContent = '读取中…';

    var authorError = null;
    var authorPromise = B.call('moments.author.get', C.buildAuthorParams(kind, id)).then(function (raw) {
      var data = raw || {};
      return C.normalizeAuthor(data.author || data);
    }).catch(function (error) {
      authorError = error;
      return null;
    });

    var params = C.buildListParams({ authorKind: kind, authorId: id, query: '' }, '', state.prefs.pageSize, false);
    var postsPromise = B.call('moments.list', params).then(function (raw) {
      return C.normalizePage(raw);
    });

    Promise.all([authorPromise, postsPromise]).then(function (values) {
      if (requestId !== state.author.requestId) return;
      var author = values[0];
      var page = values[1];
      state.author.author = author;
      state.author.authorError = authorError;
      state.author.posts = page.items.slice(0, C.MAX_LIST_POSTS);
      state.author.cursor = page.nextCursor;
      state.author.hasMore = page.hasMore && !!page.nextCursor;
      state.author.loaded = true;
      state.author.loading = false;
      renderAuthor();
    }).catch(function (error) {
      if (requestId !== state.author.requestId) return;
      state.author.loading = false;
      state.author.loaded = true;
      state.author.error = error;
      renderAuthor();
      showError(error);
    });
  }

  function renderAuthor() {
    var header = $('authorHeader');
    clear(header);
    var author = state.author.author;
    var fallback = state.author.posts.length ? state.author.posts[0].author : null;
    if (!author) author = fallback;

    if (author) {
      var head = el('div', 'oc-author-head');
      head.appendChild(avatarNode(author, ''));
      var box = el('div', '');
      box.appendChild(el('p', 'oc-author-name', author.name || '未命名作者'));
      var sub = [C.authorKindLabel(author.kind)];
      if (author.capturedAt) sub.push('作者快照 · ' + C.formatFullTime(author.capturedAt));
      else sub.push('作者资料来自当前可见动态');
      box.appendChild(el('p', 'oc-author-sub', sub.join(' · ')));
      head.appendChild(box);
      header.appendChild(head);
    } else {
      header.appendChild(el('p', 'oc-note', '宿主未返回作者快照；若没有可见动态，页面不会伪造作者资料。'));
    }

    var meta = [];
    if (state.author.loading) meta.push('读取中…');
    if (state.author.authorError) meta.push('作者快照不可用：' + C.humanError(state.author.authorError));
    meta.push('已显示 ' + state.author.posts.length + ' 条');
    $('authorMeta').textContent = meta.join(' · ');
    if (state.author.authorError) $('authorMeta').dataset.kind = 'warn'; else $('authorMeta').removeAttribute('data-kind');

    var list = $('authorPosts');
    clear(list);
    if (state.author.loading && !state.author.posts.length) {
      list.appendChild(emptyState('正在读取作者动态…', '只查询宿主本地 store。'));
    } else if (state.author.error && !state.author.posts.length) {
      list.appendChild(emptyState('作者动态读取失败', C.humanError(state.author.error), '重试', function () { loadAuthor(state.author.kind, state.author.id); }));
    } else if (!state.author.posts.length && state.author.loaded) {
      list.appendChild(emptyState('没有可见动态', '作者可能没有发布内容，或当前 ACL 不可见。'));
    }
    for (var i = 0; i < state.author.posts.length; i++) {
      list.appendChild(renderPostCard(state.author.posts[i], { context: 'list' }));
    }
    var atLimit = state.author.posts.length >= C.MAX_LIST_POSTS;
    var more = $('authorLoadMore');
    show(more, !atLimit && state.author.hasMore && !state.author.loading);
    more.disabled = state.author.loading;
    more.textContent = state.author.loading ? '读取中…' : '加载更多';
  }

  function loadMoreAuthor() {
    if (state.author.loading || !state.author.hasMore || !state.author.cursor) return;
    var requestId = state.author.requestId;
    state.author.loading = true;
    renderAuthor();
    var filters = { authorKind: state.author.kind, authorId: state.author.id, query: '' };
    var params = C.buildListParams(filters, state.author.cursor, state.prefs.pageSize, false);
    B.call('moments.list', params).then(function (raw) {
      if (requestId !== state.author.requestId) return;
      var page = C.normalizePage(raw);
      var appended = C.boundedAppend(state.author.posts, page.items, C.MAX_LIST_POSTS);
      state.author.posts = appended.items;
      state.author.cursor = page.nextCursor;
      state.author.hasMore = page.hasMore && !!page.nextCursor;
      state.author.loading = false;
      renderAuthor();
    }).catch(function (error) {
      if (requestId !== state.author.requestId) return;
      state.author.loading = false;
      renderAuthor();
      showError(error);
    });
  }

  /* ------------------------------------------------------------------ */
  /* 设置与宿主原生助手互动                                            */
  /* ------------------------------------------------------------------ */

  function renderSettings() {
    $('settingsMeta').textContent = 'v' + PLUGIN_VERSION + ' · localData v' + C.VERSION;
    renderSettingsIdentity();
    renderSettingsTheme();
    $('settingPageSize').value = String(state.prefs.pageSize);
    renderSettingsCaps();
    renderTaskText();
    renderTaskSettings();
    renderTaskReviews();
    renderTaskStatusList();
    clear($('settingsAbout'));
    var about = el('div', 'oc-settings-list');
    about.appendChild(settingsRow('插件版本', PLUGIN_VERSION));
    about.appendChild(settingsRow('插件 ID', PLUGIN_ID));
    about.appendChild(settingsRow('入口', 'ui/index.html'));
    about.appendChild(settingsRow('存储', '宿主 localData（独立 SQLite/媒体根）'));
    about.appendChild(settingsRow('浏览器存储', '不使用 localStorage / sessionStorage'));
    about.appendChild(settingsRow('模型', '浏览/搜索/详情不触发模型'));
    $('settingsAbout').appendChild(about);
  }

  var otherModalReturnFocus = null;

  function openOtherSettings() {
    // Populate the modal at open time as well as on route/host updates. A modal
    // must not depend on an earlier asynchronous settings render having finished.
    renderSettings();
    otherModalReturnFocus = document.activeElement || $('settingsOtherOpen');
    show($('settingsOtherDialog'), true);
    document.body.classList.add('oc-modal-open');
    $('settingsOtherClose').focus();
  }

  function closeOtherSettings() {
    if ($('settingsOtherDialog').hidden) return;
    show($('settingsOtherDialog'), false);
    document.body.classList.remove('oc-modal-open');
    if (otherModalReturnFocus && typeof otherModalReturnFocus.focus === 'function') otherModalReturnFocus.focus();
    otherModalReturnFocus = null;
  }

  function otherModalKeydown(event) {
    if ($('settingsOtherDialog').hidden) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      closeOtherSettings();
    } else if (event.key === 'Tab') {
      // This read-only modal has two focus stops, including its keyboard-scrollable
      // body. Keep focus inside it without depending on native <dialog> support.
      var target = event.shiftKey ? $('settingsOtherBody') : $('settingsOtherClose');
      if (document.activeElement === target) target = event.shiftKey ? $('settingsOtherClose') : $('settingsOtherBody');
      event.preventDefault();
      target.focus();
    }
  }

  function settingsRow(label, value) {
    var row = el('div', 'oc-settings-row');
    row.appendChild(el('span', '', label));
    row.appendChild(el('span', '', value));
    return row;
  }

  function renderSettingsIdentity() {
    var box = $('settingsIdentity');
    clear(box);
    var host = state.boot.host;
    if (!host) {
      box.appendChild(el('p', 'oc-note oc-note-warn', state.boot.hostError || '宿主未提供身份信息；页面不会伪造用户或助手身份。'));
      return;
    }
    if (host.user) {
      var user = el('div', 'oc-author-head');
      user.appendChild(avatarNode(host.user, ''));
      var userBox = el('div', '');
      userBox.appendChild(el('p', 'oc-post-name', host.user.name || '宿主未提供用户昵称'));
      userBox.appendChild(el('p', 'oc-post-sub', '本机用户身份 · 由宿主创建，页面不能更改'));
      user.appendChild(userBox);
      box.appendChild(user);
    } else {
      box.appendChild(el('p', 'oc-note oc-note-warn', '宿主未返回 user 对象；不会用“我”伪造身份。'));
    }
    if (host.assistants.length) {
      box.appendChild(el('h3', '', '本机助手'));
      for (var i = 0; i < host.assistants.length; i++) {
        var assistant = host.assistants[i];
        var row = el('div', 'oc-author-head');
        row.appendChild(avatarNode(assistant, ''));
        var info = el('div', '');
        info.appendChild(el('p', 'oc-post-name', assistant.name || assistant.id));
        info.appendChild(el('p', 'oc-post-sub', '助手 ID：' + assistant.id + ' · 仅宿主可创建其身份'));
        row.appendChild(info);
        box.appendChild(row);
      }
    } else {
      box.appendChild(el('p', 'oc-note', '宿主未返回助手列表；评论、指定可见和互评都不能由页面伪造助手。'));
    }
  }

  function renderSettingsTheme() {
    var box = $('settingsTheme');
    clear(box);
    var host = state.boot.host;
    var theme = host ? host.theme : [];
    box.appendChild(el('p', 'oc-note', theme.length
      ? '宿主提供了 ' + theme.length + ' 个主题变量，已生成 ' + state.themeApplied.length + ' 个 CSS 变量；页面背景保持透明，不覆盖宿主背景。'
      : '宿主未提供主题变量；使用系统色/中性卡片，背景保持透明。插件不内置三套猜测配色。'));
    if (theme.length) {
      var details = document.createElement('details');
      details.appendChild(el('summary', '', '查看宿主主题变量（' + theme.length + '）'));
      var list = el('div', 'oc-settings-list');
      for (var i = 0; i < theme.length && i < 24; i++) {
        var row = el('div', 'oc-settings-row');
        row.appendChild(el('code', '', theme[i].key));
        row.appendChild(el('span', '', theme[i].value));
        list.appendChild(row);
      }
      details.appendChild(list);
      if (theme.length > 24) details.appendChild(el('p', 'oc-note', '其余 ' + (theme.length - 24) + ' 个变量已应用，未在列表中展开。'));
      box.appendChild(details);
    }
  }

  function renderSettingsCaps() {
    var capsBox = $('settingsCaps');
    clear(capsBox);
    var host = state.boot.host;
    capsBox.appendChild(el('h3', '', '宿主报告的能力'));
    if (!host || !host.capabilities.length) {
      capsBox.appendChild(el('p', 'oc-note oc-note-warn', '宿主未报告 capabilities；不能据此推断已授权。缺少能力时接口会明确失败。'));
    } else {
      var chips = el('div', 'oc-settings-list');
      for (var i = 0; i < host.capabilities.length; i++) chips.appendChild(el('div', 'oc-settings-row', '· ' + host.capabilities[i]));
      capsBox.appendChild(chips);
    }
    capsBox.appendChild(el('h3', '', '本插件声明的能力（manifest 只是声明）'));
    var declared = el('div', 'oc-settings-list');
    for (var j = 0; j < DECLARED_PERMISSIONS.length; j++) {
      declared.appendChild(el('div', 'oc-settings-row', DECLARED_PERMISSIONS[j]));
    }
    capsBox.appendChild(declared);
    capsBox.appendChild(el('p', 'oc-note', '最终授权由宿主决定；页面请求不包含 actor/actorId，不能自证助手身份。'));

    var dataBox = $('settingsData');
    clear(dataBox);
    dataBox.appendChild(el('h3', '', 'localData 诊断'));
    var info = state.boot.info;
    var list = el('div', 'oc-settings-list');
    list.appendChild(settingsRow('API 版本', info ? String(info.apiVersion) : (state.boot.infoError || '不可用')));
    list.appendChild(settingsRow('Schema 版本', info ? String(info.schemaVersion) : '不可用'));
    list.appendChild(settingsRow('pluginId', info ? info.pluginId : '不可用'));
    list.appendChild(settingsRow('mediaOrigin', info ? info.mediaOrigin : '不可用'));
    list.appendChild(settingsRow('最近错误码', state.lastError || '无'));
    dataBox.appendChild(list);
  }

  function taskBridgeAvailable() {
    return !!(B && typeof B.available === 'function' && B.available().localDataTask);
  }

  function taskBridgeMissingText() {
    return '未就绪：当前宿主未提供 OrangeBridge.localDataTask。本页不会伪造任务、审核结果或助手回复。';
  }

  function setTaskText(message, kind) {
    state.task.text = message || '';
    state.task.kind = kind || '';
    renderTaskText();
  }

  function renderTaskText() {
    var node = $('taskStatusText');
    if (!node) return;
    node.textContent = state.task.text;
    if (state.task.kind) node.dataset.kind = state.task.kind; else node.removeAttribute('data-kind');
    var disabled = state.task.busy || state.task.settingsLoading || state.task.reviewsLoading;
    ['taskSettingsRefresh', 'taskStatusRefresh', 'taskCancelAll', 'taskReviewRefresh'].forEach(function (id) {
      var buttonNode = $(id);
      if (buttonNode) buttonNode.disabled = disabled;
    });
  }

  function taskFailure(error, prefix) {
    state.task.busy = false;
    var notReady = ['missing_method', 'not_ready', 'unavailable', 'unknown_method'].indexOf(error && error.code) >= 0;
    setTaskText((notReady ? '未就绪：' : (prefix || '请求失败：')) + C.humanError(error), notReady ? 'warn' : 'error');
    renderTaskSettings();
    renderTaskReviews();
    renderTaskStatusList();
    if (notReady) {
      showNotice((prefix || '助手互动') + '未就绪：' + C.humanError(error), 'warn');
      toast((prefix || '助手互动') + '未就绪', 'warn');
    } else {
      showError(error);
    }
  }

  function startInteraction(post) {
    if (!post || !C.safeRecordId(post.id)) throw C.apiError('invalid_request', '动态编号无效');
    if (state.task.busy) return;
    if (!taskBridgeAvailable()) {
      setTaskText(taskBridgeMissingText(), 'warn');
      showNotice(taskBridgeMissingText(), 'warn');
      toast('助手互动未就绪：宿主未提供 localDataTask', 'warn');
      return;
    }
    if (!window.confirm('让宿主原生任务调用真实模型来互动这条动态？\n\n参与助手、轮数、预算、审核和全局停止都由宿主原生“互动”设置决定；点击后会按宿主配置执行。')) return;
    state.task.busy = true;
    state.task.postId = post.id;
    state.task.taskId = '';
    setTaskText('正在请求宿主启动互动任务…', '');
    B.taskStart(post.id).then(function (raw) {
      var started = C.normalizeTaskStart(raw);
      state.task.taskId = started.taskId;
      state.task.busy = false;
      setTaskText('宿主已接受任务 ' + started.taskId + (started.existing ? '（复用已有任务）' : '') +
        '，当前状态：' + C.taskStateLabel(started.state) + '。真实评论由宿主任务落库，本页不会补写。', '');
      toast(started.existing ? '宿主复用了已有互动任务' : '宿主已启动互动任务');
      refreshTaskStatus(post.id);
      loadTaskSettings(true);
    }).catch(function (error) {
      taskFailure(error, '启动互动失败：');
    });
  }

  function refreshTaskStatus(postId) {
    if (state.task.busy) return;
    if (!taskBridgeAvailable()) {
      setTaskText(taskBridgeMissingText(), 'warn');
      return;
    }
    var payload = {};
    if (postId && C.safeRecordId(postId)) payload.postId = postId;
    else if (state.task.taskId) payload.taskId = state.task.taskId;
    state.task.busy = true;
    setTaskText('正在向宿主查询任务状态…', '');
    B.taskStatus(payload).then(function (raw) {
      var list = C.normalizeTaskStatusList(raw);
      state.task.busy = false;
      state.task.statuses = list;
      state.task.status = list.length ? list[0] : null;
      if (state.task.status && state.task.status.taskId) state.task.taskId = state.task.status.taskId;
      if (!list.length) {
        setTaskText('宿主返回：当前没有活跃任务。浏览/刷新不会触发模型。', '');
      } else {
        setTaskText('宿主返回 ' + list.length + ' 个任务；状态来自宿主，本页不会补写。', '');
      }
      renderTaskStatusList();
      renderTaskText();
    }).catch(function (error) {
      state.task.busy = false;
      taskFailure(error, '查询任务状态失败：');
    });
  }

  function cancelTask() {
    if (state.task.busy) return;
    if (!taskBridgeAvailable()) {
      setTaskText(taskBridgeMissingText(), 'warn');
      return;
    }
    var byId = !!state.task.taskId;
    if (!window.confirm(byId
      ? '取消当前记录的任务 ' + state.task.taskId + '？宿主会停止正在进行的 turn。'
      : '当前页没有记录具体任务。要取消本插件的全部活跃任务吗？')) return;
    state.task.busy = true;
    setTaskText('正在请求宿主取消任务…', '');
    B.taskCancel(byId ? { taskId: state.task.taskId } : { all: true }).then(function (raw) {
      var result = C.normalizeTaskCancel(raw);
      state.task.busy = false;
      state.task.taskId = '';
      setTaskText('宿主已取消 ' + result.cancelled + ' 个任务。已完成的写入不会被本页撤回。', '');
      toast(result.cancelled ? '宿主已取消 ' + result.cancelled + ' 个任务' : '宿主没有可取消的任务');
      refreshTaskStatus('');
      loadTaskReviews(true);
    }).catch(function (error) {
      taskFailure(error, '取消任务失败：');
    });
  }

  function loadTaskSettings(force) {
    if (!taskBridgeAvailable()) {
      state.task.settings = null;
      state.task.settingsError = C.apiError('missing_method', 'localDataTask is unavailable');
      state.task.settingsLoaded = true;
      renderTaskSettings();
      return;
    }
    if (state.task.settingsLoading) return;
    if (state.task.settingsLoaded && !force) {
      renderTaskSettings();
      return;
    }
    state.task.settingsLoading = true;
    state.task.settingsError = null;
    renderTaskText();
    renderTaskSettings();
    B.taskSettings().then(function (raw) {
      state.task.settings = C.normalizeTaskSettings(raw);
      state.task.settingsError = null;
      state.task.settingsLoaded = true;
      state.task.settingsLoading = false;
      renderTaskSettings();
      renderTaskText();
    }).catch(function (error) {
      state.task.settings = null;
      state.task.settingsError = error;
      state.task.settingsLoaded = true;
      state.task.settingsLoading = false;
      renderTaskSettings();
      renderTaskText();
      showNotice('读取宿主互动设置失败：' + C.humanError(error), 'warn');
    });
  }

  function loadTaskReviews(force) {
    if (!taskBridgeAvailable()) {
      state.task.reviews = [];
      state.task.reviewsError = C.apiError('missing_method', 'localDataTask is unavailable');
      state.task.reviewsLoaded = true;
      renderTaskReviews();
      return;
    }
    if (state.task.reviewsLoading) return;
    if (state.task.reviewsLoaded && !force) {
      renderTaskReviews();
      return;
    }
    state.task.reviewsLoading = true;
    state.task.reviewsError = null;
    renderTaskText();
    renderTaskReviews();
    B.taskReviewList().then(function (raw) {
      state.task.reviews = C.normalizeTaskReviews(raw);
      state.task.reviewsError = null;
      state.task.reviewsLoaded = true;
      state.task.reviewsLoading = false;
      renderTaskReviews();
      renderTaskText();
    }).catch(function (error) {
      state.task.reviews = [];
      state.task.reviewsError = error;
      state.task.reviewsLoaded = true;
      state.task.reviewsLoading = false;
      renderTaskReviews();
      renderTaskText();
      showNotice('读取待审核内容失败：' + C.humanError(error), 'warn');
    });
  }

  /*
   * Review is never auto-approved. A click only builds an explicit approve/reject
   * request; the host WebView then shows its own native confirmation dialog before
   * LocalTaskBridge applies anything.
   */
  function resolveReview(review, approve) {
    if (state.task.busy) return;
    if (!review || !C.safeRecordId(review.id) || state.task.reviewBusyId) return;
    if (!taskBridgeAvailable()) {
      setTaskText(taskBridgeMissingText(), 'warn');
      return;
    }
    state.task.reviewBusyId = review.id;
    setTaskText((approve ? '正在提交“通过”审核' : '正在提交“拒绝”审核') +
      '：宿主还会弹出原生确认框，页面不能自行批准。', '');
    renderTaskReviews();
    B.taskReviewResolve(review.id, approve).then(function (raw) {
      var result = C.normalizeTaskReviewResolve(raw);
      state.task.reviewBusyId = '';
      setTaskText('宿主审核结果：' + (result.approved ? '已通过' : '已拒绝') +
        '，应用=' + (result.applied ? '是' : '否') + '，任务状态=' + C.taskStateLabel(result.state) +
        (result.detail ? ' · ' + result.detail : '') + '。', '');
      toast(result.applied ? '宿主已应用审核结果' : '宿主已记录审核结果');
      loadTaskReviews(true);
      refreshTaskStatus(review.postId || '');
    }).catch(function (error) {
      state.task.reviewBusyId = '';
      renderTaskReviews();
      taskFailure(error, '审核失败：');
    });
  }

  function renderTaskSettings() {
    var box = $('taskSettingsSummary');
    if (!box) return;
    clear(box);
    if (!taskBridgeAvailable()) {
      box.appendChild(el('p', 'oc-note oc-note-warn', taskBridgeMissingText()));
      return;
    }
    if (state.task.settingsLoading) {
      box.appendChild(el('p', 'oc-note', '正在读取宿主原生互动设置（只读）…'));
      return;
    }
    if (state.task.settingsError) {
      box.appendChild(el('p', 'oc-note oc-note-warn', '宿主互动设置不可用：' + C.humanError(state.task.settingsError)));
      return;
    }
    var settings = state.task.settings;
    if (!settings) {
      box.appendChild(el('p', 'oc-note', '尚未读取宿主互动设置；点“刷新设置（只读）”。'));
      return;
    }
    var list = el('div', 'oc-settings-list');
    list.appendChild(settingsRow('启用互动', settings.enabled ? '是（宿主原生设置）' : '否'));
    list.appendChild(settingsRow('全局停止', settings.globalStop ? '是' : '否'));
    list.appendChild(settingsRow('审核模式', settings.requireReview ? '写入前必须由宿主原生确认' : '宿主按设置直接写入'));
    list.appendChild(settingsRow('参与助手', settings.participants.length ? settings.participants.length + ' 个' : '未配置'));
    list.appendChild(settingsRow('最多轮次', String(settings.maxRounds)));
    list.appendChild(settingsRow('每助手每条回复上限', String(settings.maxRepliesPerAssistantPerPost)));
    list.appendChild(settingsRow('每条模型调用上限', String(settings.maxModelCallsPerPost)));
    list.appendChild(settingsRow('每日模型调用预算', String(settings.dailyModelCallBudget)));
    list.appendChild(settingsRow('回复字数上限', String(settings.maxOutputChars)));
    list.appendChild(settingsRow('调用间隔', Math.round(settings.intervalMs / 1000) + ' 秒'));
    if (settings.allowImages) list.appendChild(settingsRow('允许图片', '是（仅宿主原生发布）'));
    box.appendChild(list);
    if (!settings.enabled) box.appendChild(el('p', 'oc-note oc-note-warn', '宿主未启用互动；此时 task.start 会被宿主拒绝。本页不会绕过设置。'));
    if (settings.globalStop) box.appendChild(el('p', 'oc-note oc-note-warn', '宿主处于全局停止状态；需要先在原生“互动”设置中恢复。'));
  }

  function renderTaskReviews() {
    var box = $('taskReviews');
    if (!box) return;
    clear(box);
    if (!taskBridgeAvailable()) {
      box.appendChild(el('p', 'oc-note oc-note-warn', taskBridgeMissingText()));
      return;
    }
    if (state.task.reviewsLoading) {
      box.appendChild(el('p', 'oc-note', '正在读取宿主待审核内容…'));
      return;
    }
    if (state.task.reviewsError) {
      box.appendChild(el('p', 'oc-note oc-note-warn', '待审核内容不可用：' + C.humanError(state.task.reviewsError)));
      return;
    }
    if (!state.task.reviews.length) {
      box.appendChild(el('p', 'oc-note', '宿主当前没有待审核内容。需要审核时，任务状态会变成“等待审核”。'));
      return;
    }
    for (var i = 0; i < state.task.reviews.length; i++) {
      var review = state.task.reviews[i];
      var item = el('div', 'oc-comment');
      var head = el('div', 'oc-comment-head');
      head.appendChild(el('span', 'oc-comment-name', review.assistantName || review.assistantId));
      head.appendChild(el('span', 'oc-comment-time', C.formatTime(review.createdAt)));
      head.appendChild(el('span', 'oc-comment-reply', review.kind));
      item.appendChild(head);
      item.appendChild(el('p', 'oc-comment-body', review.body || '（宿主未返回正文）'));
      var meta = [];
      if (review.postId) meta.push('动态 ' + review.postId);
      if (review.replyToId) meta.push('回复评论 ' + review.replyToId);
      if (review.like) meta.push('包含点赞');
      if (meta.length) item.appendChild(el('p', 'oc-note', meta.join(' · ')));
      var actions = el('div', 'oc-comment-actions');
      var approveButton = button('通过（宿主会再确认）', 'oc-link-btn', function () {
        resolveReview(review, true);
      });
      approveButton.disabled = state.task.busy || state.task.reviewBusyId === review.id;
      var rejectButton = button('拒绝（宿主会再确认）', 'oc-link-btn', function () {
        resolveReview(review, false);
      });
      rejectButton.disabled = state.task.busy || state.task.reviewBusyId === review.id;
      actions.appendChild(approveButton);
      actions.appendChild(rejectButton);
      item.appendChild(actions);
      box.appendChild(item);
    }
  }

  function renderTaskStatusList() {
    var box = $('taskStatusList');
    if (!box) return;
    clear(box);
    var list = state.task.statuses || [];
    if (!list.length) {
      box.appendChild(el('p', 'oc-note', '没有从宿主返回的活跃任务。查询只读状态，不会触发模型。'));
      return;
    }
    var rows = el('div', 'oc-settings-list');
    for (var i = 0; i < list.length; i++) {
      var status = list[i];
      var label = C.taskStateLabel(status.state) + ' · 任务 ' + status.taskId;
      var detail = [];
      if (status.postId) detail.push('动态 ' + status.postId);
      detail.push('轮次 ' + status.round);
      detail.push('发言 ' + status.turns);
      detail.push('评论 ' + status.comments);
      detail.push('点赞 ' + status.likes);
      detail.push('模型调用 ' + status.modelCalls);
      if (status.pendingReview.length) detail.push('待审核 ' + status.pendingReview.length);
      if (status.endReason) detail.push('结束原因 ' + status.endReason);
      if (status.error) detail.push('错误 ' + status.error.code + (status.error.message ? ' · ' + status.error.message : ''));
      var row = el('div', 'oc-settings-row');
      row.appendChild(el('span', '', label));
      row.appendChild(el('span', '', detail.join(' · ')));
      rows.appendChild(row);
    }
    box.appendChild(rows);
  }

  /* ------------------------------------------------------------------ */
  /* 静态事件与启动                                                      */
  /* ------------------------------------------------------------------ */

  function wireStatic() {
    if (state.wired) return;
    state.wired = true;

    $('btnRefresh').addEventListener('click', guard(function () {
      if (!state.boot.localReady) { connect(); return; }
      if (state.route === 'editor') { toast('编辑中：请先保存草稿或离开编辑页', 'warn'); return; }
      if (state.route === 'detail') { loadDetail(state.detail.id, true); return; }
      if (state.route === 'drafts') { loadDrafts(true); return; }
      if (state.route === 'author') { loadAuthor(state.author.kind, state.author.id); return; }
      if (state.route === 'settings') {
        loadInfoAndHost();
        loadTaskSettings(true);
        loadTaskReviews(true);
        renderSettings();
        return;
      }
      loadList(true);
    }));

    var skip = $('skipLink');
    if (skip) skip.addEventListener('click', function (event) {
      event.preventDefault();
      var main = $('main');
      if (main) main.focus();
    });

    $('btnCompose').addEventListener('click', guard(function () {
      if (!state.boot.localReady) throw C.apiError('missing_bridge', '宿主尚未接线 localData');
      if (state.route === 'editor' && state.editor && state.editor.dirty) {
        if (!window.confirm('重新开始会丢失当前未保存的正文或图片。继续？')) return;
      }
      openEditor(null);
    }));

    var tabs = document.querySelectorAll('[data-nav]');
    for (var i = 0; i < tabs.length; i++) {
      (function (tab) {
        tab.addEventListener('click', guard(function () {
          navigate(tab.getAttribute('data-nav'));
        }));
      })(tabs[i]);
    }

    $('applyFilters').addEventListener('click', guard(applyFilters));
    $('resetFilters').addEventListener('click', guard(resetFilters));
    $('loadMore').addEventListener('click', guard(function () { loadList(false); }));
    $('restartList').addEventListener('click', guard(function () {
      state.list.loaded = false;
      loadList(true);
    }));

    $('detailBack').addEventListener('click', guard(function () { navigate('list'); }));
    $('detailLoadComments').addEventListener('click', guard(loadMoreComments));
    $('commentBody').addEventListener('input', function () {
      updateCommentCountLabel();
      state.detail.commentClientKey = '';
    });
    $('commentSubmit').addEventListener('click', guard(submitComment));
    $('commentReplyClear').addEventListener('click', guard(clearReply));

    $('editorBack').addEventListener('click', guard(function () {
      if (state.editor && state.editor.dirty && !window.confirm('离开会丢失未保存的正文或图片。继续？')) return;
      navigate('list');
    }));
    $('editorBody').addEventListener('input', function () {
      if (!state.editor) return;
      state.editor.body = this.value;
      updateEditorCount();
      markEditorDirty(true);
      schedulePreview();
    });
    $('editorAddImages').addEventListener('click', guard(pickEditorImages));
    $('editorImportGenerated').addEventListener('click', guard(function () {
      return toggleGeneratedPicker(true);
    }));
    $('editorGeneratedClose').addEventListener('click', guard(function () {
      return toggleGeneratedPicker(false);
    }));
    $('editorSaveDraft').addEventListener('click', guard(saveDraft));
    $('editorPublish').addEventListener('click', guard(publishEditor));

    $('draftLoadMore').addEventListener('click', guard(function () { loadDrafts(false); }));
    $('authorBack').addEventListener('click', guard(function () { navigate('list'); }));
    $('authorLoadMore').addEventListener('click', guard(loadMoreAuthor));

    $('settingPageSize').addEventListener('change', function () {
      state.prefs.pageSize = C.clampPageSize(this.value);
      state.list.requestId += 1;
      state.list.loading = false;
      state.list.loaded = false;
      state.list.items = [];
      state.drafts.requestId += 1;
      state.drafts.loading = false;
      state.drafts.loaded = false;
      state.drafts.items = [];
      toast('每页条数已改为 ' + state.prefs.pageSize + '（仅本次页面）');
    });
    $('settingRefresh').addEventListener('click', guard(function () {
      state.list.requestId += 1;
      state.list.loading = false;
      state.list.loaded = false;
      state.list.items = [];
      navigate('list');
    }));

    var otherDialog = $('settingsOtherDialog');
    if ($('settingsOtherOpen') && otherDialog) {
      $('settingsOtherOpen').addEventListener('click', guard(function () {
        openOtherSettings();
      }));
    }
    if ($('settingsOtherClose') && otherDialog) {
      $('settingsOtherClose').addEventListener('click', guard(function () {
        closeOtherSettings();
      }));
    }

    otherDialog.addEventListener('click', function (event) {
      if (event.target === otherDialog) closeOtherSettings();
    });
    document.addEventListener('keydown', otherModalKeydown);

    $('taskSettingsRefresh').addEventListener('click', guard(function () { loadTaskSettings(true); }));
    $('taskStatusRefresh').addEventListener('click', guard(function () {
      refreshTaskStatus('');
    }));
    $('taskCancelAll').addEventListener('click', guard(cancelTask));
    $('taskReviewRefresh').addEventListener('click', guard(function () { loadTaskReviews(true); }));

    $('viewerClose').addEventListener('click', guard(closeViewer));
    $('viewer').addEventListener('click', function (event) {
      if (event.target === $('viewer')) closeViewer();
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && state.viewer) closeViewer();
    });
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) closeViewer();
    });
    window.addEventListener('pagehide', function () {
      closeOtherSettings();
      closeViewer();
      state.list.loaded = false;
      state.detail.post = null;
      state.detail.id = '';
      state.detail.comments = [];
      state.author.posts = [];
      state.author.loaded = false;
      state.author.kind = '';
      state.author.id = '';
      state.author.author = null;
      state.drafts.items = [];
      state.drafts.loaded = false;
      state.task.busy = false;
      state.task.reviewBusyId = '';
      state.task.statuses = [];
      state.task.status = null;
      state.task.settings = null;
      state.task.settingsLoaded = false;
      state.task.reviews = [];
      state.task.reviewsLoaded = false;
    });
    window.addEventListener('pageshow', function (event) {
      if (event.persisted && state.boot.localReady) route();
    });
    window.addEventListener('hashchange', route);
  }

  function boot() {
    if (!C || !B) {
      renderBridgeFatal('脚本未完整加载（core.js / bridge.js）。');
      return;
    }
    wireStatic();
    connect();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();

  /* Expose a small read-only surface for manual audit; no data mutation. */
  window.MomentsApp = {
    version: PLUGIN_VERSION,
    state: function () {
      return {
        route: state.route,
        localReady: state.boot.localReady,
        pageSize: state.prefs.pageSize,
        listCount: state.list.items.length,
        draftCount: state.drafts.items.length,
        themeKeys: state.themeApplied.length
      };
    }
  };
})();
