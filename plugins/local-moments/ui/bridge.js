/*
 * 本机朋友圈 · 宿主桥接适配层
 *
 * 契约（宿主 lead 接线）：
 *   window.OrangeBridge.localData(JSON.stringify({version:1,method,params}))
 *       -> v1 envelope JSON 字符串 / 对象 / Promise
 *   window.OrangeBridge.localDataPickImages()
 *       -> {ok:true,data:{media:[{resourceId,thumbnailUrl,originalUrl,...}]}}
 *   window.OrangeBridge.localDataHostInfo()
 *       -> {ok:true,data:{user,assistants,theme,capabilities}}
 *   window.OrangeBridge.localDataTask(requestJson)
 *       -> Promise<string>，请求是 v1 envelope：{version:1,method,params}
 *          method 只允许 task.start/task.status/task.cancel/task.settings/
 *          task.review.list/task.review.resolve；参数只允许宿主白名单字段。
 *
 * 本层不伪造任何返回值。找不到方法时抛出 code=missing_method，
 * 由 UI 显示“当前宿主未提供 X 接口”，绝不显示示例动态或模拟回复。
 *
 * 宿主实际只暴露单参数 `localDataTask(requestJson)`，返回 Promise；本层固定
 * 只传一个 JSON 字符串，不再根据函数 arity 猜测 action/params 旧协议。
 * 其余 localData 调用仍兼容同步字符串/对象、Promise 和回调函数三种接线。
 */
(function (global) {
  'use strict';

  var Core = global.MomentsCore;
  if (!Core) throw new Error('MomentsCore must load before MomentsBridge');

  var CALL_TIMEOUT_MS = 20000;
  var HOST_INFO_TIMEOUT_MS = 10000;
  var PICK_TIMEOUT_MS = 300000;
  var TASK_TIMEOUT_MS = 15000;

  function orangeBridge() {
    return global.OrangeBridge && typeof global.OrangeBridge === 'object'
      ? global.OrangeBridge
      : null;
  }

  /*
   * Compatibility fallback only: the host task-5 report also describes a
   * dedicated `window.OrangeLocalData.call(requestJson)` JS interface.
   * The primary contract remains OrangeBridge.localData; if the umbrella
   * object is absent, a user-side read/write session can still work through
   * this one method. Picker/host info/task stay explicitly unavailable.
   */
  function legacyLocalData() {
    var legacy = global.OrangeLocalData;
    return legacy && typeof legacy.call === 'function' ? legacy : null;
  }

  function method(name) {
    var bridge = orangeBridge();
    return bridge && typeof bridge[name] === 'function' ? bridge[name] : null;
  }

  function available() {
    return {
      localData: !!method('localData') || !!method('localDataAsync') || !!legacyLocalData(),
      pickImages: !!method('localDataPickImages'),
      hostInfo: !!method('localDataHostInfo'),
      localDataTask: !!method('localDataTask')
    };
  }

  function ready() {
    return available().localData;
  }

  function missingBridge() {
    return Core.apiError(
      'missing_bridge',
      'window.OrangeBridge is not available'
    );
  }

  function missingMethod(name) {
    var error = Core.apiError('missing_method', 'Missing host method: ' + name);
    error.method = name;
    return error;
  }

  function parseMaybeJson(value) {
    if (typeof value !== 'string') return value;
    var text = value.trim();
    if (!text) return value;
    if (text.charAt(0) === '{' || text.charAt(0) === '[' || text.charAt(0) === '"') {
      try {
        return JSON.parse(text);
      } catch (error) {
        return value;
      }
    }
    return value;
  }

  function timeoutError(name, timeoutMs) {
    var error = Core.apiError(
      'timeout',
      'Host method timed out: ' + name + ' after ' + timeoutMs + 'ms',
      true
    );
    error.method = name;
    return error;
  }

  /*
   * 调用一个宿主方法。args 是业务参数；默认在函数声明了更多形参时追加回调。
   * 传 callback:false 时绝不追加回调（localDataTask 固定单参数 Promise）。
   * 对于同步返回 undefined 且没有回调的宿主，会等待到超时再明确失败，而不是假装成功。
   */
  function invokeOn(target, name, args, options) {
    options = options || {};
    if (!target) return Promise.reject(missingBridge());
    var fn = target[name];
    if (typeof fn !== 'function') return Promise.reject(missingMethod(name));

    var timeoutMs = typeof options.timeoutMs === 'number' ? options.timeoutMs : CALL_TIMEOUT_MS;
    var callArgs = Array.isArray(args) ? args.slice() : [];

    return new Promise(function (resolve, reject) {
      var settled = false;
      var timer = null;

      function finish(value, failure) {
        if (settled) return;
        settled = true;
        if (timer !== null) clearTimeout(timer);
        if (failure) {
          if (value && value.code) {
            reject(value);
          } else {
            var error = Core.apiError(
              'bridge_exception',
              'Host bridge call failed: ' + name
            );
            error.method = name;
            error.cause = value;
            reject(error);
          }
          return;
        }
        resolve(parseMaybeJson(value));
      }

      if (timeoutMs > 0) {
        timer = setTimeout(function () {
          finish(timeoutError(name, timeoutMs), true);
        }, timeoutMs);
      }

      try {
        var wantsCallback = options.callback !== false && fn.length > callArgs.length;
        if (wantsCallback) {
          callArgs.push(function (result) {
            finish(result, false);
          });
        }
        var returned = fn.apply(target, callArgs);
        if (returned && typeof returned.then === 'function') {
          returned.then(function (value) {
            finish(value, false);
          }, function (error) {
            finish(error, true);
          });
        } else if (returned !== undefined && returned !== null) {
          finish(returned, false);
        }
        /* returned === undefined: host may call the callback later. */
      } catch (error) {
        finish(error, true);
      }
    });
  }

  function invoke(name, args, options) {
    return invokeOn(orangeBridge(), name, args, options);
  }

  function callRaw(methodName, params, timeoutMs) {
    var request = {
      version: Core.VERSION,
      method: methodName,
      params: params || {}
    };
    var bridge = orangeBridge();
    var asyncMethod = bridge && typeof bridge.localDataAsync === 'function'
      ? 'localDataAsync'
      : 'localData';
    if (bridge && typeof bridge[asyncMethod] === 'function') {
      return invoke(asyncMethod, [JSON.stringify(request)], { timeoutMs: timeoutMs }).then(function (raw) {
        return Core.parseEnvelope(raw);
      });
    }
    var legacy = legacyLocalData();
    if (legacy) {
      return invokeOn(legacy, 'call', [JSON.stringify(request)], { timeoutMs: timeoutMs }).then(function (raw) {
        return Core.parseEnvelope(raw);
      });
    }
    if (!bridge) return Promise.reject(missingBridge());
    return Promise.reject(missingMethod('localData'));
  }

  function call(methodName, params) {
    if (typeof methodName !== 'string' || !/^[a-z][a-z0-9_]{1,31}(\.[a-z][a-z0-9_]{1,31})+$/.test(methodName)) {
      return Promise.reject(Core.apiError('invalid_request', 'Invalid localData method'));
    }
    return callRaw(methodName, params, CALL_TIMEOUT_MS);
  }

  function info() {
    return call('localdata.info', {});
  }

  function pickImages() {
    if (!method('localDataPickImages')) return Promise.reject(missingMethod('localDataPickImages'));
    return invoke('localDataPickImages', [], { timeoutMs: PICK_TIMEOUT_MS }).then(function (raw) {
      if (raw && typeof raw === 'object' && raw.ok === false) Core.parseEnvelope(raw);
      return Core.parsePickImages(raw);
    });
  }

  function hostInfo() {
    if (!method('localDataHostInfo')) return Promise.reject(missingMethod('localDataHostInfo'));
    return invoke('localDataHostInfo', [], { timeoutMs: HOST_INFO_TIMEOUT_MS }).then(function (raw) {
      if (raw && typeof raw === 'object' && raw.ok === false) Core.parseEnvelope(raw);
      return Core.normalizeHostInfo(raw);
    });
  }

  /*
   * Real host task bridge. `OrangeBridge.localDataTask` is a one-argument Promise
   * (`requestJson`). The request must be the strict v1 envelope built by
   * Core.buildTaskRequest; the response must be the LocalTaskBridge envelope.
   * There is deliberately no action/params fallback and no "accepted" guess.
   */
  function task(methodName, payload) {
    var request;
    try {
      request = Core.buildTaskRequest(methodName, payload || {});
    } catch (error) {
      return Promise.reject(error);
    }
    var bridge = orangeBridge();
    if (!bridge) return Promise.reject(missingBridge());
    if (typeof bridge.localDataTask !== 'function') {
      return Promise.reject(missingMethod('localDataTask'));
    }
    return invoke('localDataTask', [JSON.stringify(request)], { timeoutMs: TASK_TIMEOUT_MS, callback: false })
      .then(function (raw) {
        if (!raw || typeof raw !== 'object' || raw.version !== Core.VERSION) {
          throw Core.apiError('protocol', 'Task response is not a v1 LocalTaskBridge envelope');
        }
        return Core.parseEnvelope(raw);
      });
  }

  function taskStart(postId, assistantId, clientKey) {
    return task('task.start', {
      postId: postId,
      assistantId: assistantId,
      clientKey: clientKey
    });
  }

  function taskStatus(payload) {
    return task('task.status', payload || {});
  }

  function taskCancel(payload) {
    return task('task.cancel', payload || {});
  }

  function taskSettings() {
    return task('task.settings', {});
  }

  function taskReviewList() {
    return task('task.review.list', {});
  }

  function taskReviewResolve(reviewId, approve, body) {
    return task('task.review.resolve', {
      reviewId: reviewId,
      approve: approve,
      body: body
    });
  }

  /*
   * Host-generated image library. The host enumerates its own persisted images
   * (chat generate_image / plugin tool results / image generation page); the page
   * only receives opaque ids and must get the user's confirmation before asking
   * for a copy. There is deliberately no generate/callTool fallback here.
   */
  function listGeneratedImages() {
    return call('localdata.generated_images_list', {}).then(function (data) {
      return Core.normalizeGeneratedImages(data);
    });
  }

  function importGeneratedImage(id) {
    if (typeof id !== 'string' || !/^[a-f0-9]{64}$/.test(id)) {
      return Promise.reject(Core.apiError('invalid_request', 'Invalid generated image id'));
    }
    return call('localdata.generated_image_import', { id: id }).then(function (data) {
      var media = Core.normalizeMedia(data && data.media);
      if (!media) throw Core.apiError('protocol', 'Host did not return a usable media resource');
      return { media: media };
    });
  }

  var api = {
    available: available,
    ready: ready,
    call: call,
    bridgeCall: call,
    info: info,
    pickImages: pickImages,
    listGeneratedImages: listGeneratedImages,
    importGeneratedImage: importGeneratedImage,
    hostInfo: hostInfo,
    task: task,
    taskStart: taskStart,
    taskStatus: taskStatus,
    taskCancel: taskCancel,
    taskSettings: taskSettings,
    taskReviewList: taskReviewList,
    taskReviewResolve: taskReviewResolve,
    parseMaybeJson: parseMaybeJson
  };

  global.MomentsBridge = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
