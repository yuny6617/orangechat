'use strict';

const assert = require('node:assert/strict');
const C = require('../ui/core.js');

let checks = 0;

function test(name, fn) {
  try {
    fn();
    checks += 1;
    console.log('ok ' + name);
  } catch (error) {
    console.error('FAIL ' + name);
    throw error;
  }
}

function envelope(data) {
  return { version: 1, ok: true, data: data };
}

function errorOf(fn) {
  try {
    fn();
  } catch (error) {
    return error;
  }
  throw new Error('expected function to throw');
}

test('constants keep the default page at 3 and bounded node budgets', () => {
  assert.equal(C.DEFAULT_PAGE_SIZE, 3);
  assert.equal(C.MAX_PAGE_SIZE, 20);
  assert.equal(C.MAX_LIST_POSTS, 30);
  assert.equal(C.MAX_DETAIL_COMMENTS, 60);
  assert.equal(C.MAX_CHAT_MESSAGES_LIST, 6);
  assert.equal(C.MAX_CHAT_MESSAGES_DETAIL, 20);
  assert.equal(C.MAX_MEDIA_PER_POST, 9);
  assert.equal(C.MAX_SELECTED_ASSISTANTS, 50);
});

test('media URLs accept only the controlled localdata origin and variant', () => {
  assert.equal(C.safeMediaUrl('https://localdata.invalid/media/ldm_abcd1234/thumb'), true);
  assert.equal(C.safeMediaUrl('https://localdata.invalid/media/ldm_abcd1234/original'), true);
  assert.equal(C.safeMediaUrl('http://localdata.invalid/media/ldm_abcd1234/thumb'), false);
  assert.equal(C.safeMediaUrl('https://localdata.invalid/media/ldm_abcd1234/thumb?x=1'), false);
  assert.equal(C.safeMediaUrl('https://localdata.invalid/media/../ldm_abcd1234/thumb'), false);
  assert.equal(C.safeMediaUrl('https://evil.example/media/ldm_abcd1234/thumb'), false);
  assert.equal(C.safeMediaUrl('javascript:alert(1)'), false);
  assert.equal(C.safeMediaUrl('https://localdata.invalid/media/ldm_short/thumb'), false);
});

test('avatars only allow controlled media or data:image', () => {
  assert.equal(C.safeAvatarUrl(''), '');
  assert.equal(C.safeAvatarUrl('https://localdata.invalid/media/ldm_abcd1234/thumb'), 'https://localdata.invalid/media/ldm_abcd1234/thumb');
  assert.equal(C.safeAvatarUrl('data:image/png;base64,AAAA'), 'data:image/png;base64,AAAA');
  assert.equal(C.safeAvatarUrl('javascript:alert(1)'), '');
  assert.equal(C.safeAvatarUrl('https://evil.example/a.png'), '');
  assert.equal(C.safeAvatarUrl('file:///data/user/0/x/a.png'), '');
});

test('media normalization accepts thumbUrl/originalUrl and picker aliases', () => {
  const media = C.normalizeMedia({
    resourceId: 'ldm_abcd1234',
    thumbnailUrl: 'https://localdata.invalid/media/ldm_abcd1234/thumb',
    originalUrl: 'https://localdata.invalid/media/ldm_abcd1234/original',
    width: 1200,
    height: 900
  });
  assert.equal(media.resourceId, 'ldm_abcd1234');
  assert.equal(media.available, true);
  assert.equal(media.width, 1200);
  const alias = C.normalizeMedia({
    resourceId: 'ldm_abcd1234',
    thumbUrl: 'https://localdata.invalid/media/ldm_abcd1234/thumb'
  });
  assert.equal(alias.originalUrl, 'https://localdata.invalid/media/ldm_abcd1234/original');
  assert.equal(C.normalizeMedia({ resourceId: 'nope' }), null);
});

test('post normalization keeps text inert and caps media/preview comments', () => {
  const post = C.normalizePost({
    id: 'post_1',
    author: { kind: 'user', id: 'user_1', name: '<img src=x onerror=alert(1)>' },
    body: '<script>alert(1)</script>',
    visibility: 'all_assistants',
    media: Array.from({ length: 12 }, () => ({
      resourceId: 'ldm_abcd1234',
      thumbUrl: 'https://localdata.invalid/media/ldm_abcd1234/thumb'
    })),
    previewComments: Array.from({ length: 5 }, (_, index) => ({
      id: 'c_' + index,
      postId: 'post_1',
      author: { kind: 'assistant', id: 'a_1', name: 'A' },
      body: 'comment',
      createdAt: 1
    })),
    createdAt: 10,
    commentCount: 5,
    likeCount: 2
  });
  assert.equal(post.body, '<script>alert(1)</script>');
  assert.equal(post.media.length, 9);
  assert.equal(post.previewComments.length, 3);
  assert.equal(post.author.name, '<img src=x onerror=alert(1)>');
  assert.equal(C.normalizePost({ id: '../bad', author: { kind: 'user', id: 'u' } }), null);
  assert.equal(C.normalizePost({ id: 'p', author: { kind: 'robot', id: 'u' } }), null);
});

test('page normalization reports invalid/missing cursors instead of pretending more', () => {
  const normal = C.normalizePage({
    items: [{ id: 'p', author: { kind: 'user', id: 'u' } }],
    nextCursor: 'abcDEF12',
    hasMore: true
  });
  assert.equal(normal.hasMore, true);
  assert.equal(normal.cursorInvalid, false);
  assert.equal(normal.items.length, 1);

  const missing = C.normalizePage({ items: [], hasMore: true });
  assert.equal(missing.hasMore, false);
  assert.equal(missing.cursorInvalid, true);

  const bad = C.normalizePage({ items: [], nextCursor: '../x', hasMore: true });
  assert.equal(bad.hasMore, false);
  assert.equal(bad.cursorInvalid, true);
});

test('draft page normalization works with draft DTOs', () => {
  const page = C.normalizeDraftPage(envelope({
    items: [{
      id: 'draft_1',
      author: { kind: 'user', id: 'u' },
      body: 'draft',
      visibility: 'selected',
      selectedAssistantIds: ['a1', 'bad/id'],
      media: [],
      updatedAt: 5
    }],
    hasMore: false
  }));
  assert.equal(page.items.length, 1);
  assert.deepEqual(page.items[0].selectedAssistantIds, ['a1']);
  assert.equal(page.items[0].visibility, 'selected');
});

test('host info normalizes user/assistants/theme/capabilities without inventing values', () => {
  const host = C.normalizeHostInfo(envelope({
    user: { name: '机主', avatar: 'data:image/png;base64,AAAA' },
    assistants: [
      { id: 'a1', name: '小橘', avatar: 'https://evil.example/a.png' },
      { id: 'bad/id', name: 'bad' }
    ],
    theme: {
      '--oc-bg': '#101010',
      '--oc-text': 'rgba(255,255,255,.9)',
      '--bad': 'url(https://evil.example/x)',
      '--bad2': 'red; } body { color: red',
      'onSurface': 'CanvasText'
    },
    capabilities: ['localdata.read', 'evil cap']
  }));
  assert.equal(host.user.name, '机主');
  assert.equal(host.assistants.length, 1);
  assert.equal(host.assistants[0].name, '小橘');
  assert.equal(host.assistants[0].avatar, '');
  assert.equal(host.theme.length, 3);
  assert.equal(host.theme[0].key, '--oc-bg');
  assert.equal(host.theme[2].key, '--onSurface');
  assert.deepEqual(host.capabilities, ['localdata.read']);
});

test('theme values reject CSS injection and unsafe URLs', () => {
  const theme = C.themeCssProperties({
    '--ok': '#fff',
    '--ok2': 'linear-gradient(155deg, #fff, #000)',
    '--ok3': 'color-mix(in srgb, #fff 80%, #000)',
    '--ok4': 'var(--background)',
    '--ok5': '#D5ECFEF2',
    '--radius-xl': '28px',
    '--radius-lg': '12.5px',
    '--bad': 'url(https://evil.example/x)',
    '--bad2': 'red; } body {',
    '--bad3': 'expression(alert(1))',
    '--bad4': 'javascript:alert(1)',
    '--bad5': 'red !important',
    '--bad6': 'a'.repeat(300),
    '--bad7': '28px; }',
    '--bad8': '1e9px',
    '--bad9': '28 px'
  });
  assert.deepEqual(theme.map(item => item.key), ['--ok', '--ok2', '--ok3', '--ok4', '--ok5', '--radius-xl', '--radius-lg']);
});

test('labels and post type are stable', () => {
  assert.equal(C.visibilityLabel('all_assistants'), '全部助手可见');
  assert.equal(C.visibilityLabel('selected'), '指定助手可见');
  assert.equal(C.visibilityLabel('self'), '仅自己可见');
  assert.equal(C.visibilityLabel('other'), '可见范围未知');
  assert.equal(C.authorKindLabel('user'), '本机用户');
  assert.equal(C.authorKindLabel('assistant'), '本机助手');
  assert.equal(C.postType({ media: [], chatSnapshot: { messages: [] } }), 'text');
  assert.equal(C.postType({ media: [{}] }), 'image');
  assert.equal(C.postType({ media: [], chatSnapshot: { messages: [{ role: 'user' }] } }), 'chat_snapshot');
});

test('list/search params only carry contract fields and never actor identity', () => {
  const filters = {
    query: '咖啡',
    authorKind: 'assistant',
    authorId: 'a1',
    type: 'image',
    dateFrom: 1000,
    dateTo: 2000
  };
  const search = C.buildListParams(filters, 'abcDEF12', 3, true);
  assert.equal(search.query, '咖啡');
  assert.equal(search.limit, 3);
  assert.equal(search.authorKind, 'assistant');
  assert.equal(search.authorId, 'a1');
  assert.equal(search.type, 'image');
  assert.equal(search.dateFrom, 1000);
  assert.equal(search.dateTo, 2000);
  assert.equal(search.commentPreview, 3);
  assert.equal(C.hasActorKey(search), false);

  const list = C.buildListParams(filters, '', 3, false);
  assert.equal(Object.prototype.hasOwnProperty.call(list, 'query'), false);
  assert.equal(C.hasActorKey(list), false);
});

test('create/draft/comment/like/task params never self-assert an actor', () => {
  const create = C.buildCreateParams({
    body: 'hello',
    visibility: 'selected',
    selected: ['a1', 'bad/id'],
    media: [{ resourceId: 'ldm_abcd1234' }, 'ldm_efgh5678'],
    chatSnapshot: { title: 't', messages: [{ role: 'user', text: 'hi' }] },
    draftId: 'draft_1'
  }, 'client_key_1');
  assert.equal(create.visibility, 'selected');
  assert.deepEqual(create.selectedAssistantIds, ['a1']);
  assert.deepEqual(create.mediaIds, ['ldm_abcd1234', 'ldm_efgh5678']);
  assert.equal(create.draftId, 'draft_1');
  assert.equal(create.clientKey, 'client_key_1');
  assert.equal(C.hasActorKey(create), false);

  const all = C.buildCreateParams({ body: 'x', visibility: 'all_assistants', selected: ['a1'] }, 'k');
  assert.deepEqual(all.selectedAssistantIds, []);

  const draft = C.buildDraftParams({ body: 'x', visibility: 'self', selected: ['a1'] });
  assert.equal(C.hasActorKey(draft), false);
  assert.deepEqual(draft.selectedAssistantIds, []);

  const publishDraft = C.buildPublishDraftParams('draft_1');
  assert.deepEqual(publishDraft, { draftId: 'draft_1' });
  assert.equal(C.hasActorKey(publishDraft), false);

  const comment = C.buildCommentCreateParams('post_1', 'hi', 'c1', 'comment_key');
  assert.equal(C.hasActorKey(comment), false);
  assert.equal(C.safeClientKey(comment.clientKey), true);

  const like = C.buildLikeParams('post_1', true);
  assert.equal(C.hasActorKey(like), false);

  const task = C.buildTaskRequest('task.start', {
    postId: 'post_1',
    assistantId: 'a1',
    clientKey: 'task_key_1',
    actorId: 'evil',
    actor: { kind: 'assistant', id: 'a1' }
  });
  assert.equal(task.version, 1);
  assert.equal(task.method, 'task.start');
  assert.equal(Object.prototype.hasOwnProperty.call(task, 'action'), false);
  assert.deepEqual(task.params, { postId: 'post_1', assistantId: 'a1', clientKey: 'task_key_1' });
  assert.equal(Object.prototype.hasOwnProperty.call(task.params, 'actor'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(task.params, 'actorId'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(task.params, 'viewerId'), false);
});

test('task request builder speaks only the real LocalTaskBridge method envelope', () => {
  assert.deepEqual(C.buildTaskRequest('task.settings', {}).params, {});
  assert.deepEqual(C.buildTaskRequest('task.review.list', {}).params, {});

  assert.deepEqual(C.buildTaskRequest('task.status', { taskId: 'lt_1', postId: 'post_1', actorId: 'evil' }).params, {
    taskId: 'lt_1',
    postId: 'post_1'
  });
  assert.deepEqual(C.buildTaskRequest('task.cancel', { taskId: 'lt_1' }).params, { taskId: 'lt_1' });
  assert.deepEqual(C.buildTaskRequest('task.cancel', { all: true }).params, { all: true });
  assert.deepEqual(C.buildTaskRequest('task.review.resolve', {
    reviewId: 'ltr_1',
    approve: true
  }).params, { reviewId: 'ltr_1', approve: true });
  assert.deepEqual(C.buildTaskRequest('task.review.resolve', {
    reviewId: 'ltr_1',
    approve: false,
    body: '  修改后的正文  '
  }).params, { reviewId: 'ltr_1', approve: false, body: '  修改后的正文  ' });

  assert.throws(() => C.buildTaskRequest('start', { postId: 'post_1' }), /Unsupported task method/);
  assert.throws(() => C.buildTaskRequest('task.start', {}), /postId/);
  assert.throws(() => C.buildTaskRequest('task.cancel', {}), /taskId or all=true/);
  assert.throws(() => C.buildTaskRequest('task.review.resolve', { reviewId: 'ltr_1' }), /boolean approve/);
  assert.throws(() => C.buildTaskRequest('task.review.resolve', {
    reviewId: 'ltr_1',
    approve: true,
    body: 'x'.repeat(C.MAX_COMMENT_CHARS + 1)
  }), /too long/);
  assert.throws(() => C.buildTaskRequest('task.review.resolve', {
    reviewId: 'ltr_1',
    approve: true,
    body: 7
  }), /must be a string/);

  const blankBody = C.buildTaskRequest('task.review.resolve', {
    reviewId: 'ltr_1',
    approve: true,
    body: '   '
  });
  assert.equal(Object.prototype.hasOwnProperty.call(blankBody.params, 'body'), false);
});

test('envelope parsing preserves host error codes and rejects mismatched versions', () => {
  assert.deepEqual(C.parseEnvelope(envelope({ x: 1 })), { x: 1 });
  const error = errorOf(() => C.parseEnvelope({
    version: 1,
    ok: false,
    error: { code: 'capability_denied', message: 'no', retryable: false }
  }));
  assert.equal(error.code, 'capability_denied');
  assert.equal(error.retryable, false);

  const version = errorOf(() => C.parseEnvelope({ version: 2, ok: true, data: {} }));
  assert.equal(version.code, 'unsupported_version');

  const protocol = errorOf(() => C.parseEnvelope('not json'));
  assert.equal(protocol.code, 'protocol');

  const missing = errorOf(() => C.parseEnvelope({ version: 1 }));
  assert.equal(missing.code, 'protocol');
});

test('pick images parses the supplemental host contract and rejects unsafe media', () => {
  const result = C.parsePickImages({
    ok: true,
    data: {
      media: [{
        resourceId: 'ldm_abcd1234',
        thumbnailUrl: 'https://localdata.invalid/media/ldm_abcd1234/thumb',
        originalUrl: 'https://localdata.invalid/media/ldm_abcd1234/original'
      }, {
        resourceId: 'bad',
        thumbnailUrl: 'javascript:alert(1)'
      }]
    }
  });
  assert.equal(result.media.length, 1);
  assert.equal(result.rejected, 1);

  const cancelled = errorOf(() => C.parsePickImages({
    ok: false,
    error: { code: 'cancelled', message: 'user cancelled' }
  }));
  assert.equal(cancelled.code, 'cancelled');
});

test('human error messages are stable and never echo arbitrary host messages', () => {
  assert.match(C.humanError({ code: 'capability_denied' }), /未授予/);
  assert.match(C.humanError({ code: 'forbidden' }), /不能自证/);
  assert.match(C.humanError({ code: 'missing_bridge' }), /OrangeBridge/);
  assert.match(C.humanError({ code: 'missing_method', method: 'localDataTask' }), /localDataTask/);
  assert.equal(C.humanError({ code: 'unknown_code', message: '<script>x</script>' }), '操作未完成；没有显示示例数据或模拟回复。');
});

test('bounded append stops at the node budget and reports dropped items', () => {
  const result = C.boundedAppend([1, 2], [3, 4, 5], 4);
  assert.deepEqual(result.items, [1, 2, 3, 4]);
  assert.equal(result.accepted, 2);
  assert.equal(result.dropped, 1);
  assert.equal(result.atLimit, true);

  const full = C.boundedAppend([1, 2, 3, 4], [5], 4);
  assert.deepEqual(full.items, [1, 2, 3, 4]);
  assert.equal(full.dropped, 1);
  assert.equal(full.atLimit, true);
});

test('client keys are bounded and URL-safe; page sizes clamp', () => {
  const key = C.makeClientKey('post');
  assert.equal(C.safeClientKey(key), true);
  assert.ok(key.length <= 64);
  assert.notEqual(C.makeClientKey('post'), key);
  assert.equal(C.clampPageSize(0), 1);
  assert.equal(C.clampPageSize(3), 3);
  assert.equal(C.clampPageSize(99), 20);
  assert.equal(C.clampPageSize('x'), 3);
});

test('date inputs convert local day boundaries and reject invalid dates', () => {
  const start = C.dateInputToMs('2026-02-03', false);
  const end = C.dateInputToMs('2026-02-03', true);
  assert.ok(start > 0);
  assert.ok(end > start);
  assert.equal(new Date(start).getHours(), 0);
  assert.equal(new Date(end).getMinutes(), 59);
  assert.equal(C.dateInputToMs('2026-02-31', false), 0);
  assert.equal(C.dateInputToMs('', false), 0);
});

test('time formatting is deterministic and bounded', () => {
  const now = new Date(2026, 4, 20, 12, 0, 0).getTime();
  assert.equal(C.formatTime(now - 1000, now), '刚刚');
  assert.equal(C.formatTime(now - 5 * 60000, now), '5 分钟前');
  assert.match(C.formatTime(new Date(2026, 4, 20, 9, 5).getTime(), now), /^今天 09:05$/);
  assert.equal(C.formatFullTime(0), '');
  assert.match(C.formatFullTime(now), /^2026-05-20 12:00$/);
});

test('initials and truncation notes are safe and explicit', () => {
  assert.equal(C.initials('小橘'), '小');
  assert.equal(C.initials('Orange'), 'OR');
  assert.equal(C.initials(''), '·');
  const notes = C.truncationNotes({ bodyTruncated: true, mediaOmitted: true, chatSnapshotOmitted: true }, { truncated: true });
  assert.equal(notes.length, 4);
  assert.ok(notes.join(' ').includes('截断'));
});

test('task DTO normalizers accept only real LocalTaskBridge data', () => {
  assert.deepEqual(C.normalizeTaskStart({ taskId: 'lt_1', existing: false, state: 'queued' }), {
    taskId: 'lt_1',
    existing: false,
    state: 'queued'
  });
  assert.throws(() => C.normalizeTaskStart({ state: 'queued' }), /taskId/);

  const status = C.normalizeTaskStatus({
    taskId: 'lt_1',
    mode: 'interact',
    state: 'waiting_review',
    postId: 'post_1',
    assistantId: 'a1',
    round: 1,
    turns: 2,
    comments: 1,
    likes: 1,
    modelCalls: 3,
    pendingReview: [{
      id: 'ltr_1',
      kind: 'reply',
      assistantId: 'a1',
      assistantName: '小橘',
      postId: 'post_1',
      body: '待审核正文',
      like: true,
      createdAt: 10
    }],
    endReason: 'review',
    error: { code: 'invalid_action', message: 'bad' },
    createdAt: 1,
    updatedAt: 2
  });
  assert.equal(status.state, 'waiting_review');
  assert.equal(status.modelCalls, 3);
  assert.equal(status.pendingReview.length, 1);
  assert.equal(status.pendingReview[0].assistantName, '小橘');
  assert.equal(status.error.code, 'invalid_action');

  assert.equal(C.normalizeTaskStatusList({ taskId: 'lt_1', state: 'running' }).length, 1);
  assert.equal(C.normalizeTaskStatusList([{ taskId: 'lt_1' }, { taskId: 'bad/id' }]).length, 1);
  assert.equal(C.normalizeTaskStatusList({ items: [{ taskId: 'lt_2' }] })[0].taskId, 'lt_2');

  const settings = C.normalizeTaskSettings({
    enabled: true,
    globalStop: false,
    maxRounds: 2,
    participants: ['a1', 'bad/id'],
    requireReview: true,
    intervalMs: 45000
  });
  assert.equal(settings.enabled, true);
  assert.equal(settings.requireReview, true);
  assert.deepEqual(settings.participants, ['a1']);

  const zero = C.normalizeTaskSettings({
    enabled: false,
    commentUserPosts: false,
    commentAssistantPosts: false,
    autoLike: false,
    maxRounds: 0,
    maxRepliesPerAssistantPerPost: 0,
    dailyModelCallBudget: 0,
    intervalMs: 0
  });
  assert.equal(zero.commentUserPosts, false);
  assert.equal(zero.commentAssistantPosts, false);
  assert.equal(zero.autoLike, false);
  assert.equal(zero.maxRounds, 0);
  assert.equal(zero.maxRepliesPerAssistantPerPost, 0);
  assert.equal(zero.dailyModelCallBudget, 0);
  assert.equal(zero.intervalMs, 0);

  const reviews = C.normalizeTaskReviews({
    items: [{
      id: 'ltr_1',
      kind: 'reply',
      assistantId: 'a1',
      assistantName: '小橘',
      body: '正文'
    }, {
      id: 'bad/id',
      assistantId: 'a1'
    }]
  });
  assert.equal(reviews.length, 1);
  assert.equal(reviews[0].id, 'ltr_1');

  assert.deepEqual(C.normalizeTaskReviewResolve({
    reviewId: 'ltr_1',
    applied: true,
    approved: false,
    state: 'completed',
    detail: 'ok'
  }), {
    reviewId: 'ltr_1',
    applied: true,
    approved: false,
    state: 'completed',
    detail: 'ok'
  });
  assert.throws(() => C.normalizeTaskReviewResolve({ applied: true }), /reviewId/);
  assert.equal(C.taskStateLabel('waiting_review'), '等待审核');
  assert.equal(C.taskStateLabel('bogus'), '状态未知');

  assert.deepEqual(C.normalizeInfo({ apiVersion: 1, schemaVersion: 1, pluginId: 'p', mediaOrigin: 'o' }), {
    apiVersion: 1,
    schemaVersion: 1,
    pluginId: 'p',
    mediaOrigin: 'o'
  });
});

test('actor keys are detected across known aliases', () => {
  assert.equal(C.hasActorKey({ actorId: 'x' }), true);
  assert.equal(C.hasActorKey({ asAssistant: true }), true);
  assert.equal(C.hasActorKey({ selectedAssistantIds: ['a1'] }), false);
  assert.equal(C.hasActorKey({ authorId: 'u1' }), false);
});

test('chat avatars stay controlled and never carry remote or data URLs', () => {
  const snapshot = C.normalizeChatSnapshot({
    messages: [
      { role: 'user', text: 'hi', avatarMediaId: 'ldm_abcd1234', avatarEmoji: '🙂' },
      { role: 'assistant', text: 'yo', avatarEmoji: '🤖' },
      { role: 'assistant', text: 'no', avatarMediaId: 'https://evil.example/a.png', avatarEmoji: 'x' }
    ]
  });
  assert.equal(snapshot.messages[0].avatarMediaId, 'ldm_abcd1234');
  assert.equal(snapshot.messages[0].avatarEmoji, '');
  assert.equal(snapshot.messages[1].avatarMediaId, '');
  assert.equal(snapshot.messages[1].avatarEmoji, '🤖');
  assert.equal(snapshot.messages[2].avatarMediaId, '');
  assert.equal(snapshot.messages[2].avatarEmoji, 'x');
});

test('generated image candidates keep opaque ids, real metadata and bounded size', () => {
  const id = 'a'.repeat(64);
  assert.deepEqual(C.normalizeGeneratedImage({
    id,
    source: 'chat_generated',
    createdAt: 1700000000000,
    byteSize: 2048,
    width: 1024,
    height: 768
  }), {
    id,
    source: 'chat_generated',
    createdAt: 1700000000000,
    byteSize: 2048,
    width: 1024,
    height: 768
  });

  // Path-shaped, uppercase or short ids never reach the renderer.
  assert.equal(C.normalizeGeneratedImage({ id: '../images/chat_1.png' }), null);
  assert.equal(C.normalizeGeneratedImage({ id: 'A'.repeat(64) }), null);
  assert.equal(C.normalizeGeneratedImage({ id: 'abc' }), null);
  const minimal = C.normalizeGeneratedImage({ id });
  assert.equal(minimal.source, '');
  assert.equal(minimal.createdAt, 0);
  assert.equal(minimal.byteSize, 0);
});

test('generated image list is capped and reports host truncation', () => {
  const id = 'b'.repeat(64);
  const items = [];
  for (let index = 0; index < C.MAX_GENERATED_IMAGES + 5; index += 1) {
    items.push({ id, createdAt: index, byteSize: 10, width: 1, height: 1 });
  }
  const page = C.normalizeGeneratedImages({ items, truncated: false });
  assert.equal(page.items.length, C.MAX_GENERATED_IMAGES);
  assert.equal(page.truncated, true);
  assert.equal(C.normalizeGeneratedImages({ items: [], truncated: true }).truncated, true);
  assert.deepEqual(C.normalizeGeneratedImages(null), { items: [], truncated: false });
});

test('generated image display labels never invent a source', () => {
  assert.equal(C.generatedSourceLabel('chat_generated'), '聊天生图');
  assert.equal(C.generatedSourceLabel('plugin_tool'), '插件工具产物');
  assert.equal(C.generatedSourceLabel('image_generation'), '生图页面');
  assert.equal(C.generatedSourceLabel(''), '宿主图库');
  assert.equal(C.normalizeGeneratedImage({ id: 'c'.repeat(64), source: 'unknown-origin' }).source, '');
  assert.equal(C.formatByteSize(0), '');
  assert.equal(C.formatByteSize(2048), '2 KB');
  assert.equal(C.formatByteSize(2 * 1024 * 1024), '2.0 MB');
});

console.log('core.test: ' + checks + ' checks passed');
