'use strict';

/*
 * Minimal DOM smoke test for app.js.
 *
 * This is not a browser: it is a deliberately small fake DOM that exercises the
 * real controller, bridge and core modules together. It catches missing element
 * ids, broken listeners, accidental fake data paths and the main list/detail/
 * editor/settings render paths without a Gradle build.
 */

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');

class FakeStyle {
  constructor() { this.props = new Map(); }
  setProperty(key, value) { this.props.set(key, String(value)); }
  removeProperty(key) { this.props.delete(key); }
  getPropertyValue(key) { return this.props.get(key) || ''; }
}

class FakeClassList {
  constructor(element) { this.element = element; this.values = new Set(); }
  sync() { this.element._className = [...this.values].join(' '); }
  add(...names) { names.forEach(name => this.values.add(name)); this.sync(); }
  remove(...names) { names.forEach(name => this.values.delete(name)); this.sync(); }
  toggle(name, force) {
    const enabled = force === undefined ? !this.values.has(name) : !!force;
    if (enabled) this.values.add(name); else this.values.delete(name);
    this.sync();
    return enabled;
  }
  contains(name) { return this.values.has(name); }
}

class FakeElement {
  constructor(tag) {
    this.tagName = String(tag || 'div').toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.attributes = {};
    this.dataset = {};
    this.style = new FakeStyle();
    this.classList = new FakeClassList(this);
    this._className = '';
    this._text = '';
    this.hidden = false;
    this.disabled = false;
    this.value = '';
    this.checked = false;
    this.id = '';
    this.type = '';
    this.name = '';
    this.title = '';
    this.alt = '';
    this.loading = '';
    this.decoding = '';
    this.src = '';
    this.listeners = {};
  }
  get className() { return this._className; }
  set className(value) {
    this._className = String(value);
    this.classList.values = new Set(this._className.split(/\s+/).filter(Boolean));
  }
  get firstChild() { return this.children[0] || null; }
  get textContent() {
    let output = this._text;
    for (const child of this.children) output += child.textContent;
    return output;
  }
  set textContent(value) {
    this._text = String(value);
    this.children = [];
  }
  get options() { return this.children.filter(child => child.tagName === 'OPTION'); }
  appendChild(child) {
    if (!child) return child;
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
  removeChild(child) {
    const index = this.children.indexOf(child);
    if (index >= 0) {
      this.children.splice(index, 1);
      child.parentNode = null;
    }
    return child;
  }
  replaceChildren(...nodes) {
    this.children = [];
    this._text = '';
    nodes.forEach(node => this.appendChild(node));
  }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'id') this.id = String(value);
    if (name === 'class') this.className = String(value);
    if (name === 'hidden') this.hidden = true;
    if (name === 'disabled') this.disabled = true;
    if (name === 'src') this.src = String(value);
    if (name.startsWith('data-')) {
      const key = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      this.dataset[key] = String(value);
    }
  }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }
  removeAttribute(name) {
    delete this.attributes[name];
    if (name === 'hidden') this.hidden = false;
    if (name === 'disabled') this.disabled = false;
    if (name === 'src') this.src = '';
    if (name.startsWith('data-')) {
      const key = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      delete this.dataset[key];
    }
  }
  addEventListener(type, listener) {
    (this.listeners[type] || (this.listeners[type] = [])).push(listener);
  }
  removeEventListener(type, listener) {
    const list = this.listeners[type] || [];
    const index = list.indexOf(listener);
    if (index >= 0) list.splice(index, 1);
  }
  focus() {}
  querySelectorAll() { return []; }
}

const html = fs.readFileSync(path.join(ROOT, 'ui/index.html'), 'utf8');
const elements = new Map();

for (const match of html.matchAll(/<([a-z0-9]+)([^>]*)>/gi)) {
  const tag = match[1];
  const attributes = match[2];
  const idMatch = /id="([^"]+)"/.exec(attributes);
  if (!idMatch) continue;
  const element = new FakeElement(tag);
  element.id = idMatch[1];
  if (/\bhidden\b/.test(attributes)) element.hidden = true;
  elements.set(element.id, element);
}

function requireElement(id) {
  const element = elements.get(id);
  if (!element) throw new Error('missing fake element #' + id);
  return element;
}

const tabs = ['list', 'drafts', 'settings'].map(nav => {
  const tab = new FakeElement('button');
  tab.setAttribute('data-nav', nav);
  return tab;
});

const documentListeners = {};
const documentObject = {
  readyState: 'complete',
  hidden: false,
  documentElement: new FakeElement('html'),
  body: new FakeElement('body'),
  getElementById(id) { return elements.get(id) || null; },
  createElement(tag) { return new FakeElement(tag); },
  querySelectorAll(selector) { return selector === '[data-nav]' ? tabs : []; },
  addEventListener(type, listener) { (documentListeners[type] || (documentListeners[type] = [])).push(listener); }
};

const windowListeners = {};
const location = { hash: '' };
const navigator = { clipboard: { writeText: async () => {} } };
const errorLog = [];
const windowObject = {
  document: documentObject,
  location,
  navigator,
  console: {
    error(...args) { errorLog.push(args.map(String).join(' ')); },
    log() {},
    warn() {}
  },
  getComputedStyle() {
    return { getPropertyValue: key => documentObject.documentElement.style.getPropertyValue(key) };
  },
  scrollTo() {},
  confirm() { return true; },
  addEventListener(type, listener) {
    (windowListeners[type] || (windowListeners[type] = [])).push(listener);
  }
};

global.window = windowObject;
global.document = documentObject;
global.location = location;
Object.defineProperty(global, 'navigator', { value: navigator, configurable: true, writable: true });
global.getComputedStyle = windowObject.getComputedStyle;
global.scrollTo = windowObject.scrollTo;
global.confirm = windowObject.confirm;

const calls = [];
const taskCalls = [];
let pickCalls = 0;
let publishedPost = null;
let savedDraftBody = '';

function envelope(data) {
  return JSON.stringify({ version: 1, ok: true, data });
}

function taskError(code, message) {
  return JSON.stringify({ version: 1, ok: false, error: { code, message } });
}

const author = { kind: 'user', id: 'user_1', name: '机主', avatar: '' };
const comment = {
  id: 'comment_1',
  postId: 'post_1',
  author: { kind: 'assistant', id: 'a1', name: '小橘' },
  body: '真不错',
  createdAt: 1700000000000,
  replyToId: ''
};
const basePost = {
  id: 'post_1',
  author,
  body: '第一条真实动态',
  bodyTruncated: false,
  visibility: 'all_assistants',
  selectedAssistantIds: [],
  media: [{
    resourceId: 'ldm_abcd1234',
    mime: 'image/jpeg',
    width: 1200,
    height: 900,
    byteSize: 1000,
    thumbUrl: 'https://localdata.invalid/media/ldm_abcd1234/thumb',
    originalUrl: 'https://localdata.invalid/media/ldm_abcd1234/original',
    available: true
  }],
  mediaOmitted: false,
  chatSnapshot: {
    title: '聊天片段',
    messages: [{ role: 'user', name: '机主', text: '你看这个', media: [] }],
    capturedAt: 1700000000000
  },
  chatSnapshotOmitted: false,
  createdAt: 1700000000000,
  updatedAt: 1700000000000,
  commentCount: 1,
  likeCount: 2,
  likedByMe: false,
  previewComments: [comment]
};

windowObject.OrangeBridge = {
  localData(requestJson) {
    const request = JSON.parse(requestJson);
    calls.push(request);
    switch (request.method) {
      case 'localdata.info':
        return envelope({ apiVersion: 1, schemaVersion: 1, pluginId: 'com.orangechat.localmoments', mediaOrigin: 'https://localdata.invalid' });
      case 'moments.list':
        return envelope({ items: [basePost], nextCursor: 'cursorABCD', hasMore: true, truncated: false, budget: { pageSize: 3, bodyCharsPerItem: 1200, bodyCharsUsed: 20, truncated: false } });
      case 'moments.search':
        return envelope({ items: [basePost], nextCursor: null, hasMore: false, truncated: false, budget: { pageSize: 3, bodyCharsPerItem: 1200, bodyCharsUsed: 20, truncated: false } });
      case 'moments.get':
        if (request.params.postId === 'post_2' && publishedPost) {
          return envelope({ post: publishedPost, comments: { items: [], nextCursor: null, hasMore: false, commentCount: 0 } });
        }
        return envelope({ post: basePost, comments: { items: [comment], nextCursor: null, hasMore: false, commentCount: 1 } });
      case 'moments.like.set':
        return envelope({ postId: request.params.postId, liked: request.params.liked, likeCount: request.params.liked ? 3 : 2 });
      case 'moments.comment.create':
        return envelope({
          comment: {
            id: 'comment_2',
            postId: request.params.postId,
            author,
            body: request.params.body,
            createdAt: 1700000001000,
            replyToId: ''
          },
          commentCount: 2
        });
      case 'moments.draft.save':
        savedDraftBody = request.params.body;
        return envelope({
          id: 'draft_1',
          author,
          body: request.params.body,
          visibility: request.params.visibility,
          selectedAssistantIds: request.params.selectedAssistantIds || [],
          media: [],
          chatSnapshot: null,
          createdAt: 1700000000000,
          updatedAt: 1700000000000
        });
      case 'moments.create':
        publishedPost = Object.assign({}, basePost, {
          id: 'post_2',
          body: request.params.draftId ? savedDraftBody : request.params.body,
          commentCount: 0,
          likeCount: 0,
          previewComments: []
        });
        return envelope({ post: publishedPost });
      case 'moments.draft.list':
        return envelope({ items: [], nextCursor: null, hasMore: false });
      case 'moments.author.get':
        return envelope({ author });
      case 'localdata.generated_images_list':
        return envelope({
          items: [{
            id: 'e'.repeat(64),
            source: 'image_generation',
            createdAt: 1700000002000,
            byteSize: 2048,
            width: 512,
            height: 512
          }],
          truncated: false
        });
      case 'localdata.generated_image_import':
        return envelope({
          media: {
            resourceId: 'ldm_ijkl9012',
            thumbnailUrl: 'https://localdata.invalid/media/ldm_ijkl9012/thumb',
            originalUrl: 'https://localdata.invalid/media/ldm_ijkl9012/original',
            width: 512,
            height: 512
          }
        });
      default:
        return envelope({});
    }
  },
  localDataHostInfo() {
    return {
      ok: true,
      data: {
        user: { name: '机主', avatar: '' },
        assistants: [{ id: 'a1', name: '小橘', avatar: '' }],
        theme: { '--background': '#f6f1ec', '--on-surface': '#222222' },
        capabilities: ['localdata.read']
      }
    };
  },
  localDataTask(requestJson) {
    const request = JSON.parse(requestJson);
    taskCalls.push(request);
    if (Object.keys(request).some(key => !['version', 'method', 'params'].includes(key))) {
      return taskError('invalid_request', 'Unknown root field');
    }
    if (request.version !== 1) return taskError('unsupported_version', 'Unsupported task API version');
    if (typeof request.method !== 'string' || !request.method) {
      return taskError('invalid_request', 'Missing method');
    }
    if (!request.params || typeof request.params !== 'object' || Array.isArray(request.params)) {
      return taskError('invalid_request', 'params must be an object');
    }
    switch (request.method) {
      case 'task.start':
        if (typeof request.params.postId !== 'string') {
          return taskError('invalid_request', 'postId is required');
        }
        return envelope({ taskId: 'lt_1', existing: false, state: 'queued' });
      case 'task.status':
        return envelope([{
          taskId: 'lt_1',
          mode: 'interact',
          state: 'running',
          postId: 'post_1',
          round: 1,
          turns: 1,
          comments: 0,
          likes: 0,
          modelCalls: 1,
          pendingReview: [],
          createdAt: 1700000000000,
          updatedAt: 1700000001000
        }]);
      case 'task.cancel':
        return envelope({ cancelled: 1 });
      case 'task.settings':
        return envelope({
          version: 1,
          enabled: true,
          globalStop: false,
          maxRounds: 2,
          maxRepliesPerAssistantPerPost: 1,
          maxModelCallsPerPost: 8,
          dailyModelCallBudget: 200,
          maxOutputChars: 280,
          intervalMs: 45000,
          requireReview: true,
          allowImages: false,
          participants: ['a1']
        });
      case 'task.review.list':
        return envelope({
          items: [{
            id: 'ltr_1',
            kind: 'reply',
            assistantId: 'a1',
            assistantName: '小橘',
            postId: 'post_1',
            body: '待审核正文',
            like: false,
            mediaIds: [],
            createdAt: 1700000001000
          }]
        });
      case 'task.review.resolve':
        if (typeof request.params.approve !== 'boolean') {
          return taskError('invalid_request', 'approve must be a boolean');
        }
        return envelope({
          reviewId: request.params.reviewId,
          applied: request.params.approve,
          approved: request.params.approve,
          state: request.params.approve ? 'completed' : 'cancelled',
          detail: 'native-confirmed'
        });
      default:
        return taskError('unknown_method', 'Unknown task method');
    }
  },
  localDataPickImages() {
    pickCalls += 1;
    return {
      ok: true,
      data: {
        media: [{
          resourceId: 'ldm_efgh5678',
          thumbnailUrl: 'https://localdata.invalid/media/ldm_efgh5678/thumb',
          originalUrl: 'https://localdata.invalid/media/ldm_efgh5678/original'
        }]
      }
    };
  }
};

function flush() {
  return new Promise(resolve => setTimeout(resolve, 0));
}

async function settle() {
  for (let i = 0; i < 12; i++) await flush();
}

function walk(element, output = []) {
  output.push(element);
  for (const child of element.children) walk(child, output);
  return output;
}

function find(predicate) {
  for (const element of elements.values()) {
    const match = walk(element).find(predicate);
    if (match) return match;
  }
  return undefined;
}

function click(element) {
  const listeners = element.listeners.click || [];
  for (const listener of listeners) {
    listener({ preventDefault() {}, currentTarget: element, target: element });
  }
}

function fireWindow(type) {
  for (const listener of windowListeners[type] || []) listener({ persisted: true });
}

(async function main() {
  require(path.join(ROOT, 'ui/core.js'));
  require(path.join(ROOT, 'ui/bridge.js'));
  require(path.join(ROOT, 'ui/app.js'));
  await settle();

  assert.equal(calls[0].method, 'localdata.info');
  assert.equal(calls.some(request => request.method === 'moments.list'), true);
  assert.equal(taskCalls.length, 0, 'normal browse must not call localDataTask at all');
  assert.equal(documentObject.getElementById('listMeta').textContent.includes('已显示 1 条'), true);
  assert.equal(documentObject.getElementById('postList').textContent.includes('第一条真实动态'), true);
  assert.equal(documentObject.getElementById('hostNotice').hidden, true);
  assert.equal(documentObject.documentElement.style.getPropertyValue('--oc-bg'), '#f6f1ec');
  assert.equal(documentObject.documentElement.style.getPropertyValue('--oc-text'), '#222222');

  assert.equal(documentObject.getElementById('loadMore').hidden, false);
  click(documentObject.getElementById('loadMore'));
  await settle();
  assert.equal(calls.some(request => request.method === 'moments.list' && request.params.cursor === 'cursorABCD'), true);

  const like = find(element => (element.getAttribute('aria-label') || '').startsWith('点赞'));
  assert.ok(like, 'like button should render');
  click(like);
  await settle();
  assert.match(like.textContent, /♥ 3/);
  assert.equal(calls.some(request => request.method === 'moments.like.set' && request.params.liked === true), true);

  location.hash = '#/author/user/user_1';
  fireWindow('hashchange');
  await settle();
  assert.match(documentObject.getElementById('authorHeader').textContent, /机主/);
  assert.match(documentObject.getElementById('authorPosts').textContent, /第一条真实动态/);

  location.hash = '#/list';
  fireWindow('hashchange');
  await settle();
  location.hash = '#/author/user/user_1';
  fireWindow('hashchange');
  await settle();
  assert.match(documentObject.getElementById('authorPosts').textContent, /第一条真实动态/);

  location.hash = '#/drafts';
  fireWindow('hashchange');
  await settle();
  assert.match(documentObject.getElementById('draftList').textContent, /草稿箱是空的/);

  location.hash = '#/post/post_1';
  fireWindow('hashchange');
  await settle();
  assert.match(documentObject.getElementById('detailPost').textContent, /第一条真实动态/);
  const interactionButton = find(element => (element.getAttribute('aria-label') || '').startsWith('让本机助手真实互动'));
  assert.ok(interactionButton, 'explicit assistant interaction button should render in detail');
  assert.equal(taskCalls.some(request => request.method === 'task.start'), false);
  click(interactionButton);
  await settle();
  const startCall = taskCalls.find(request => request.method === 'task.start');
  assert.ok(startCall, 'explicit click should start a real host task');
  assert.deepEqual(startCall.params, { postId: 'post_1' });
  assert.equal(Object.prototype.hasOwnProperty.call(startCall, 'action'), false);
  assert.equal(taskCalls.some(request => request.method === 'task.status'), true);
  assert.match(documentObject.getElementById('taskStatusText').textContent, /宿主已接受任务|宿主返回 1 个任务/);

  documentObject.getElementById('commentBody').value = '补充评论';
  click(documentObject.getElementById('commentSubmit'));
  await settle();
  assert.equal(calls.some(request => request.method === 'moments.comment.create' && request.params.body === '补充评论'), true);
  assert.match(documentObject.getElementById('detailComments').textContent, /补充评论/);

  location.hash = '#/settings';
  fireWindow('hashchange');
  await settle();
  assert.match(documentObject.getElementById('settingsIdentity').textContent, /机主/);
  assert.match(documentObject.getElementById('settingsTheme').textContent, /CSS 变量/);
  assert.match(documentObject.getElementById('taskSettingsSummary').textContent, /启用互动/);
  assert.match(documentObject.getElementById('taskSettingsSummary').textContent, /审核模式/);
  assert.match(documentObject.getElementById('taskSettingsSummary').textContent, /参与助手/);
  assert.match(documentObject.getElementById('taskReviews').textContent, /待审核正文/);
  assert.equal(taskCalls.some(request => request.method === 'task.review.resolve'), false, 'settings view must not auto-approve');
  assert.equal(taskCalls.some(request => request.method === 'task.start'), true);
  const approveButton = find(element => element.tagName === 'BUTTON' && element.textContent.includes('通过（宿主会再确认）'));
  assert.ok(approveButton, 'review approve button should render');
  click(approveButton);
  await settle();
  const resolveCall = taskCalls.find(request => request.method === 'task.review.resolve');
  assert.ok(resolveCall, 'explicit approve click should call task.review.resolve');
  assert.deepEqual(resolveCall.params, { reviewId: 'ltr_1', approve: true });
  assert.match(documentObject.getElementById('taskStatusText').textContent, /宿主审核结果|已通过|宿主返回/);
  assert.equal(taskCalls.some(request => request.method === 'task.settings'), true);
  assert.equal(taskCalls.some(request => request.method === 'task.review.list'), true);

  location.hash = '#/list';
  fireWindow('hashchange');
  await settle();
  click(documentObject.getElementById('btnCompose'));
  await settle();
  fireWindow('hashchange');
  await settle();
  assert.equal(documentObject.getElementById('editorTitle').textContent, '发布动态');

  documentObject.getElementById('editorBody').value = '新动态正文';
  for (const listener of documentObject.getElementById('editorBody').listeners.input || []) listener.call(documentObject.getElementById('editorBody'));
  assert.match(documentObject.getElementById('editorBodyCount').textContent, /5 \//);

  click(documentObject.getElementById('editorAddImages'));
  await settle();
  assert.equal(pickCalls, 1);
  assert.equal(documentObject.getElementById('editorMedia').children.some(child => child.className.includes('oc-media-cell')), true);
  assert.match(documentObject.getElementById('editorMediaNote').textContent, /已选 1 \/ 9/);

  click(documentObject.getElementById('editorImportGenerated'));
  await settle();
  assert.equal(calls.some(request => request.method === 'localdata.generated_images_list'), true);
  assert.equal(documentObject.getElementById('editorGeneratedPicker').hidden, false);
  const generatedRow = find(element => element.className.includes('oc-generated-row'));
  assert.ok(generatedRow, 'a real host generated image candidate should render');
  click(generatedRow);
  await settle();
  assert.equal(calls.some(request => request.method === 'localdata.generated_image_import'), true);
  assert.match(documentObject.getElementById('editorMediaNote').textContent, /已选 2 \/ 9/);
  assert.equal(documentObject.getElementById('editorGeneratedPicker').hidden, true);
  assert.equal(documentObject.getElementById('editorImportGenerated').disabled, false);

  click(documentObject.getElementById('editorSaveDraft'));
  await settle();
  assert.equal(documentObject.getElementById('editorTitle').textContent, '编辑草稿');
  assert.equal(calls.some(request => request.method === 'moments.draft.save' && request.params.body === '新动态正文'), true);

  click(documentObject.getElementById('editorPublish'));
  await settle();
  fireWindow('hashchange');
  await settle();
  assert.equal(calls.some(request => request.method === 'moments.create' && request.params.draftId === 'draft_1'), true);
  assert.match(documentObject.getElementById('detailPost').textContent, /新动态正文/);

  location.hash = '#/list';
  fireWindow('hashchange');
  await settle();
  documentObject.getElementById('filterQuery').value = '真实';
  click(documentObject.getElementById('applyFilters'));
  await settle();
  assert.equal(calls.some(request => request.method === 'moments.search' && request.params.query === '真实'), true);
  assert.equal(documentObject.getElementById('listStatus').textContent.includes('正在读取'), false);
  assert.equal(taskCalls.filter(request => request.method === 'task.start').length, 1, 'browse/search must not start another task');

  const oldActionResponse = JSON.parse(windowObject.OrangeBridge.localDataTask(JSON.stringify({
    version: 1,
    action: 'start',
    params: { postId: 'post_1' }
  })));
  assert.equal(oldActionResponse.ok, false);
  assert.equal(oldActionResponse.error.code, 'invalid_request');

  assert.deepEqual(errorLog, []);
  console.log('ui-smoke.test: fake-DOM list/detail/editor/settings flows passed');
})().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
