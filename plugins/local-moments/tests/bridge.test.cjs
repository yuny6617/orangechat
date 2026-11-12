'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');

const CORE_PATH = path.join(__dirname, '..', 'ui', 'core.js');
const BRIDGE_PATH = path.join(__dirname, '..', 'ui', 'bridge.js');

globalThis.MomentsCore = require(CORE_PATH);

function freshBridge(host) {
  delete require.cache[require.resolve(BRIDGE_PATH)];
  globalThis.OrangeBridge = host;
  delete globalThis.OrangeLocalData;
  return require(BRIDGE_PATH);
}

function envelope(data) {
  return JSON.stringify({ version: 1, ok: true, data });
}

let checks = 0;
function ok(name) { checks += 1; console.log('ok ' + name); }

/*
 * Strict stand-in for the real host `LocalTaskBridge` + `LocalTaskBridgeRequestParser`.
 * It mirrors the documented contract instead of the client helper: only
 * version/method/params, only the six methods, only the documented params, and
 * only `{version,ok,data|error}` envelopes. Old `{action,params}` requests fail.
 */
const TASK_METHODS = {
  'task.start': new Set(['postId', 'assistantId', 'clientKey']),
  'task.status': new Set(['taskId', 'postId']),
  'task.cancel': new Set(['taskId', 'all']),
  'task.settings': new Set(),
  'task.review.list': new Set(),
  'task.review.resolve': new Set(['reviewId', 'approve', 'body'])
};

function hostFailure(code, message) {
  return { version: 1, ok: false, error: { code, message } };
}

function parseHostTaskRequest(raw) {
  if (typeof raw !== 'string' || !raw.trim()) {
    return { error: hostFailure('invalid_request', 'Request is empty') };
  }
  let root;
  try {
    root = JSON.parse(raw);
  } catch (error) {
    return { error: hostFailure('invalid_request', 'Request must be a JSON object') };
  }
  if (!root || typeof root !== 'object' || Array.isArray(root)) {
    return { error: hostFailure('invalid_request', 'Request must be a JSON object') };
  }
  if (Object.keys(root).some(key => !['version', 'method', 'params'].includes(key))) {
    return { error: hostFailure('invalid_request', 'Unknown root field') };
  }
  if (root.version !== 1) {
    return { error: hostFailure('unsupported_version', 'Unsupported task API version') };
  }
  if (typeof root.method !== 'string' || !root.method) {
    return { error: hostFailure('invalid_request', 'Missing method') };
  }
  const allowed = TASK_METHODS[root.method];
  if (!allowed) {
    return { error: hostFailure('unknown_method', 'Unknown task method') };
  }
  if (root.params !== undefined && (!root.params || typeof root.params !== 'object' || Array.isArray(root.params))) {
    return { error: hostFailure('invalid_request', 'params must be a JSON object') };
  }
  const params = root.params || {};
  if (Object.keys(params).some(key => !allowed.has(key))) {
    return { error: hostFailure('invalid_request', 'Unsupported parameter for this method') };
  }
  if (root.method === 'task.review.resolve') {
    if (typeof params.approve !== 'boolean') {
      return { error: hostFailure('invalid_request', 'approve must be a boolean') };
    }
    if (typeof params.reviewId !== 'string' || !params.reviewId || params.reviewId.length > 128) {
      return { error: hostFailure('invalid_request', 'reviewId is required') };
    }
    if (Object.prototype.hasOwnProperty.call(params, 'body')) {
      if (typeof params.body !== 'string' || !params.body.trim() || params.body.length > 2000) {
        return { error: hostFailure('invalid_request', 'body must be a non-blank bounded string') };
      }
    }
  }
  return { method: root.method, params };
}

function createHostTaskBridge(overrides) {
  const calls = [];
  const dispatch = function (requestJson) {
    calls.push(requestJson);
    const parsed = parseHostTaskRequest(requestJson);
    if (parsed.error) return JSON.stringify(parsed.error);
    const params = parsed.params;
    let data;
    switch (parsed.method) {
      case 'task.start':
        if (typeof params.postId !== 'string' || !params.postId) {
          return JSON.stringify(hostFailure('invalid_request', 'postId is required'));
        }
        data = { taskId: 'lt_1', existing: false, state: 'queued' };
        break;
      case 'task.status':
        data = params.taskId
          ? {
            taskId: params.taskId,
            mode: 'interact',
            state: 'running',
            postId: 'post_1',
            round: 1,
            turns: 1,
            comments: 0,
            likes: 0,
            modelCalls: 1,
            pendingReview: [],
            createdAt: 1,
            updatedAt: 2
          }
          : [{
            taskId: 'lt_1',
            mode: 'interact',
            state: 'queued',
            postId: params.postId || 'post_1',
            round: 0,
            turns: 0,
            comments: 0,
            likes: 0,
            modelCalls: 0,
            pendingReview: [],
            createdAt: 1,
            updatedAt: 1
          }];
        break;
      case 'task.cancel':
        data = { cancelled: params.all === true ? 2 : 1 };
        break;
      case 'task.settings':
        data = {
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
        };
        break;
      case 'task.review.list':
        data = {
          items: [{
            id: 'ltr_1',
            kind: 'reply',
            assistantId: 'a1',
            assistantName: '小橘',
            postId: 'post_1',
            body: '待审核正文',
            like: false,
            mediaIds: [],
            createdAt: 10
          }]
        };
        break;
      case 'task.review.resolve':
        data = {
          reviewId: params.reviewId,
          applied: params.approve,
          approved: params.approve,
          state: params.approve ? 'completed' : 'cancelled',
          detail: 'host-applied'
        };
        break;
      default:
        return JSON.stringify(hostFailure('unknown_method', 'Unknown task method'));
    }
    return JSON.stringify({ version: 1, ok: true, data });
  };
  const host = { calls, dispatch, localDataTask: dispatch };
  if (overrides) Object.assign(host, overrides);
  return host;
}

(async function main() {
  {
    const bridge = freshBridge({
      localData: function (requestJson) {
        const request = JSON.parse(requestJson);
        assert.equal(request.version, 1);
        assert.equal(request.method, 'moments.list');
        assert.deepEqual(request.params, { limit: 3 });
        return envelope({ items: [], nextCursor: null, hasMore: false });
      }
    });
    const data = await bridge.call('moments.list', { limit: 3 });
    assert.deepEqual(data, { items: [], nextCursor: null, hasMore: false });
    assert.equal(bridge.available().localData, true);
    ok('synchronous localData envelope');
  }

  {
    const bridge = freshBridge({
      localDataAsync: async function (requestJson) {
        return { version: 1, ok: true, data: { request: JSON.parse(requestJson).method } };
      }
    });
    const data = await bridge.call('localdata.info', {});
    assert.equal(data.request, 'localdata.info');
    ok('promise localDataAsync envelope');
  }

  {
    let seen = null;
    const bridge = freshBridge({
      localData: function (requestJson, callback) {
        seen = JSON.parse(requestJson);
        setTimeout(function () {
          callback(envelope({ items: [], hasMore: false }));
        }, 1);
      }
    });
    const data = await bridge.call('moments.search', { query: 'x', limit: 3 });
    assert.equal(seen.params.query, 'x');
    assert.deepEqual(data.items, []);
    ok('callback-style localData');
  }

  {
    const bridge = freshBridge({
      localDataPickImages: function () {
        return {
          ok: true,
          data: {
            media: [{
              resourceId: 'ldm_abcd1234',
              thumbnailUrl: 'https://localdata.invalid/media/ldm_abcd1234/thumb',
              originalUrl: 'https://localdata.invalid/media/ldm_abcd1234/original'
            }]
          }
        };
      }
    });
    const result = await bridge.pickImages();
    assert.equal(result.media.length, 1);
    assert.equal(result.media[0].resourceId, 'ldm_abcd1234');
    ok('pick images supplemental contract');
  }

  {
    const bridge = freshBridge({
      localDataHostInfo: function () {
        return {
          ok: true,
          data: {
            user: { name: '机主', avatar: '' },
            assistants: [{ id: 'a1', name: '小橘', avatar: '' }],
            theme: { '--oc-bg': '#101010' },
            capabilities: ['localdata.read']
          }
        };
      }
    });
    const host = await bridge.hostInfo();
    assert.equal(host.user.name, '机主');
    assert.equal(host.assistants.length, 1);
    assert.equal(host.theme[0].key, '--oc-bg');
    ok('host info supplemental contract');
  }

  {
    const bridge = freshBridge({});
    await assert.rejects(
      bridge.call('moments.list', { limit: 3 }),
      error => error.code === 'missing_method' && error.method === 'localData'
    );
    await assert.rejects(
      bridge.taskStart('post_1'),
      error => error.code === 'missing_method' && error.method === 'localDataTask'
    );
    ok('missing methods fail explicitly');
  }

  {
    const host = createHostTaskBridge();
    const bridge = freshBridge(host);
    const started = await bridge.taskStart('post_1', 'a1', 'client_key_1');
    assert.deepEqual(started, { taskId: 'lt_1', existing: false, state: 'queued' });
    assert.equal(host.calls.length, 1);
    const request = JSON.parse(host.calls[0]);
    assert.deepEqual(request, {
      version: 1,
      method: 'task.start',
      params: { postId: 'post_1', assistantId: 'a1', clientKey: 'client_key_1' }
    });
    assert.equal(Object.prototype.hasOwnProperty.call(request, 'action'), false);
    assert.equal(Object.prototype.hasOwnProperty.call(request.params, 'actorId'), false);
    ok('real task.start envelope through one-argument localDataTask');
  }

  {
    const host = createHostTaskBridge();
    const bridge = freshBridge(host);
    const status = await bridge.taskStatus({ postId: 'post_1' });
    assert.equal(Array.isArray(status), true);
    assert.equal(status[0].taskId, 'lt_1');
    assert.equal(status[0].state, 'queued');

    const single = await bridge.taskStatus({ taskId: 'lt_1' });
    assert.equal(single.taskId, 'lt_1');
    assert.equal(single.state, 'running');

    const settings = await bridge.taskSettings();
    assert.equal(settings.enabled, true);
    assert.equal(settings.requireReview, true);

    const reviews = await bridge.taskReviewList();
    assert.equal(reviews.items.length, 1);
    assert.equal(reviews.items[0].id, 'ltr_1');

    const resolved = await bridge.taskReviewResolve('ltr_1', true);
    assert.equal(resolved.reviewId, 'ltr_1');
    assert.equal(resolved.applied, true);
    assert.equal(resolved.approved, true);

    const cancelled = await bridge.taskCancel({ all: true });
    assert.deepEqual(cancelled, { cancelled: 2 });

    assert.deepEqual(host.calls.map(raw => JSON.parse(raw).method), [
      'task.status', 'task.status', 'task.settings', 'task.review.list',
      'task.review.resolve', 'task.cancel'
    ]);
    const resolveCall = JSON.parse(host.calls[4]);
    assert.deepEqual(resolveCall.params, { reviewId: 'ltr_1', approve: true });
    ok('real task.status/settings/review/cancel envelopes');
  }

  {
    const host = createHostTaskBridge();
    const bridge = freshBridge(host);
    await assert.rejects(bridge.task('start', { postId: 'post_1' }), error => error.code === 'invalid_request');
    await assert.rejects(
      bridge.taskReviewResolve('ltr_1'),
      error => error.code === 'invalid_request'
    );
    assert.equal(host.calls.length, 0);
    ok('old short action names and missing approve are rejected before the host is called');
  }

  {
    const host = createHostTaskBridge();
    const bridge = freshBridge(host);
    const oldStyle = JSON.stringify({ version: 1, action: 'start', params: { postId: 'post_1' } });
    const response = JSON.parse(host.dispatch(oldStyle));
    assert.equal(response.ok, false);
    assert.equal(response.error.code, 'invalid_request');
    assert.match(response.error.message, /root field|Missing method/i);
    assert.equal(host.calls.length, 1);
    assert.equal(JSON.parse(host.calls[0]).method, undefined);
    ok('host parser regression: old action envelope fails with invalid_request');

    const missingMethod = JSON.parse(host.dispatch(JSON.stringify({ version: 1, params: { postId: 'post_1' } })));
    assert.equal(missingMethod.ok, false);
    assert.equal(missingMethod.error.code, 'invalid_request');
    assert.match(missingMethod.error.message, /Missing method/);

    const unknownMethod = JSON.parse(host.dispatch(JSON.stringify({
      version: 1,
      method: 'task.reserved',
      params: {}
    })));
    assert.equal(unknownMethod.ok, false);
    assert.equal(unknownMethod.error.code, 'unknown_method');
    ok('host parser regression: missing and unknown methods fail explicitly');
  }

  {
    let argumentCount = -1;
    let secondArgument = 'unset';
    const bridge = freshBridge({
      localDataTask: function (requestJson, extra) {
        argumentCount = arguments.length;
        secondArgument = extra;
        const request = JSON.parse(requestJson);
        assert.equal(request.method, 'task.start');
        return envelope({ taskId: 'lt_2', existing: false, state: 'queued' });
      }
    });
    const started = await bridge.taskStart('post_1');
    assert.equal(started.taskId, 'lt_2');
    assert.equal(argumentCount, 1);
    assert.equal(secondArgument, undefined);
    ok('localDataTask is always called with one argument, even when declared with two');
  }

  {
    const bridge = freshBridge({
      localDataTask: function () {
        return { status: 'not_ready', message: 'reserved' };
      }
    });
    await assert.rejects(
      bridge.taskStart('post_1'),
      error => error.code === 'protocol'
    );
    ok('old reserved task response is rejected as a protocol error');
  }

  {
    const bridge = freshBridge({
      localDataTask: function () {
        return { ok: true, data: { taskId: 'lt_1', existing: false, state: 'queued' } };
      }
    });
    await assert.rejects(
      bridge.taskStart('post_1'),
      error => error.code === 'protocol' && /v1/.test(error.message)
    );
    ok('task envelope without version is rejected');
  }

  {
    const bridge = freshBridge({
      localDataTask: function () {
        return { version: 1, ok: false, error: { code: 'disabled', message: 'Interaction is disabled' } };
      }
    });
    await assert.rejects(
      bridge.taskStart('post_1'),
      error => error.code === 'disabled'
    );
    ok('host task error envelope is preserved');
  }

  {
    const bridge = freshBridge({
      localData: function () {
        return { version: 1, ok: false, error: { code: 'capability_denied', message: 'no', retryable: false } };
      }
    });
    await assert.rejects(
      bridge.call('moments.list', {}),
      error => error.code === 'capability_denied'
    );
    ok('host error envelope is preserved');
  }

  {
    const bridge = freshBridge(null);
    globalThis.OrangeLocalData = {
      call: function (requestJson) {
        const request = JSON.parse(requestJson);
        return envelope({ fallback: request.method });
      }
    };
    assert.equal(bridge.available().localData, true);
    assert.equal(bridge.available().pickImages, false);
    const data = await bridge.call('moments.list', { limit: 3 });
    assert.equal(data.fallback, 'moments.list');
    ok('legacy OrangeLocalData.call fallback remains explicit');
  }

  {
    const generatedId = 'd'.repeat(64);
    const requests = [];
    const bridge = freshBridge({
      localData: function (requestJson) {
        const request = JSON.parse(requestJson);
        requests.push(request);
        if (request.method === 'localdata.generated_images_list') {
          return envelope({
            items: [{
              id: generatedId,
              source: 'plugin_tool',
              createdAt: 1700000000000,
              byteSize: 4096,
              width: 512,
              height: 512
            }],
            truncated: false
          });
        }
        if (request.method === 'localdata.generated_image_import') {
          return envelope({
            media: {
              resourceId: 'ldm_abcd1234',
              thumbnailUrl: 'https://localdata.invalid/media/ldm_abcd1234/thumb',
              originalUrl: 'https://localdata.invalid/media/ldm_abcd1234/original',
              width: 512,
              height: 512
            }
          });
        }
        return { version: 1, ok: false, error: { code: 'unknown_method', message: 'no', retryable: false } };
      }
    });

    const listed = await bridge.listGeneratedImages();
    assert.equal(listed.items.length, 1);
    assert.equal(listed.items[0].id, generatedId);
    assert.equal(listed.items[0].source, 'plugin_tool');
    assert.equal(listed.truncated, false);

    const imported = await bridge.importGeneratedImage(generatedId);
    assert.equal(imported.media.resourceId, 'ldm_abcd1234');
    assert.equal(imported.media.available, true);
    assert.deepEqual(requests.map(request => request.method), [
      'localdata.generated_images_list',
      'localdata.generated_image_import'
    ]);
    assert.deepEqual(requests[1].params, { id: generatedId });
    ok('generated images enumerate and import through the existing localData envelope');
  }

  {
    const bridge = freshBridge({ localData: function () { return envelope({}); } });
    await assert.rejects(
      bridge.importGeneratedImage('../images/chat_1.png'),
      error => error.code === 'invalid_request'
    );
    await assert.rejects(
      bridge.importGeneratedImage('E'.repeat(64)),
      error => error.code === 'invalid_request'
    );
    ok('non-digest generated image ids are rejected before the host is called');
  }

  {
    const bridge = freshBridge({
      localData: function () {
        return { version: 1, ok: false, error: { code: 'capability_denied', message: 'limited', retryable: false } };
      }
    });
    await assert.rejects(
      bridge.listGeneratedImages(),
      error => error.code === 'capability_denied'
    );
    ok('generated image host denial is surfaced explicitly');
  }

  console.log('bridge.test: ' + checks + ' checks passed');
})().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
