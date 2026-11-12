/*
 * 本机朋友圈 · 纯数据与校验层
 *
 * 这个文件不碰 DOM、不发网络请求、不读写 localStorage。
 * 它只做三件事：
 *   1. 把宿主 v1 localData 返回的结构收敛成 UI 可安全渲染的普通对象；
 *   2. 构造请求参数时只允许契约里的字段，永远不加入 actor 身份；
 *   3. 提供分页上限、URL 白名单、主题变量白名单、错误文案等可测试规则。
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.MomentsCore = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this), function () {
  'use strict';

  var VERSION = 1;
  var DEFAULT_PAGE_SIZE = 3;
  var MAX_PAGE_SIZE = 20;
  var MAX_LIST_POSTS = 30;
  var MAX_DETAIL_COMMENTS = 60;
  var MAX_DRAFT_ROWS = 30;
  var MAX_CHAT_MESSAGES_LIST = 6;
  var MAX_CHAT_MESSAGES_DETAIL = 20;
  var MAX_CHAT_CHARS_LIST = 6000;
  var MAX_CHAT_CHARS_DETAIL = 12000;
  var MAX_MEDIA_PER_POST = 9;
  var MAX_SELECTED_ASSISTANTS = 50;
  var MAX_LIST_PREVIEW_MEDIA = 4;
  var MAX_BODY_CHARS = 20000;
  var MAX_COMMENT_CHARS = 2000;
  var MAX_SEARCH_CHARS = 100;
  var MAX_THEME_KEYS = 64;
  var MAX_AVATAR_DATA_URL = 200000;
  var MAX_GENERATED_IMAGES = 24;
  var MAX_GENERATED_IMAGE_BYTES = 12 * 1024 * 1024;

  var VISIBILITIES = ['all_assistants', 'selected', 'self'];
  var AUTHOR_KINDS = ['user', 'assistant'];
  var POST_TYPES = ['text', 'image', 'chat_snapshot'];
  var GENERATED_IMAGE_SOURCES = ['chat_generated', 'plugin_tool', 'image_generation'];
  var TASK_METHODS = [
    'task.start', 'task.status', 'task.cancel',
    'task.settings', 'task.review.list', 'task.review.resolve'
  ];
  var TASK_STATES = ['queued', 'running', 'waiting_review', 'completed', 'cancelled', 'failed', 'interrupted'];

  var MEDIA_URL_RE = /^https:\/\/localdata\.invalid\/media\/(ldm_[A-Za-z0-9]{8,64})\/(thumb|original)$/;
  var MEDIA_ID_RE = /^ldm_[A-Za-z0-9]{8,64}$/;
  var GENERATED_IMAGE_ID_RE = /^[a-f0-9]{64}$/;
  var RECORD_ID_RE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/;
  var CURSOR_RE = /^[A-Za-z0-9_-]{4,160}$/;
  var CLIENT_KEY_RE = /^[A-Za-z0-9_-]{1,64}$/;
  var PLUGIN_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
  var DATA_IMAGE_RE = /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=\s]+$/i;
  var SAFE_CSS_VALUE_RE = /^(?:#[0-9A-Fa-f]{3,8}|(?:color-mix|light-dark|oklch|oklab|lch|lab|hwb|rgb|rgba|hsl|hsla|linear-gradient|radial-gradient)\([^;{}]*\)|var\(--[A-Za-z0-9_-]+\)|transparent|currentColor|[A-Za-z][A-Za-z0-9-]{0,31}|[0-9]{1,4}(?:\.[0-9]{1,3})?(?:px|rem|em|%))$/;
  var BAD_CSS_VALUE_RE = /(?:[;{}]|url\s*\(|expression\s*\(|@import|javascript:|\!important)/i;

  var ACTOR_KEYS = [
    'actor', 'actorId', 'actor_id', 'actorKind', 'actor_kind',
    'viewer', 'viewerId', 'viewer_id', 'trustedActor', 'trusted_actor',
    'assistantId', 'assistant_id', 'assistantActorId', 'assistant_actor_id',
    'actingAssistantId', 'acting_assistant_id', 'asAssistant', 'as_assistant',
    'isAssistant', 'is_assistant', 'userId', 'user_id', 'roleOverride'
  ];

  function apiError(code, message, retryable) {
    var error = new Error(message || code || 'error');
    error.code = code || 'unknown';
    error.retryable = retryable === true;
    return error;
  }

  function str(value, max) {
    if (value === null || value === undefined) return '';
    var text = String(value).replace(/\u0000/g, '');
    if (max && text.length > max) return text.slice(0, max);
    return text;
  }

  function int(value, fallback, min, max) {
    var number = typeof value === 'number' ? value : Number(value);
    if (!isFinite(number)) return fallback;
    number = Math.floor(number);
    if (typeof min === 'number' && number < min) number = min;
    if (typeof max === 'number' && number > max) number = max;
    return number;
  }

  function bool(value, fallback) {
    if (value === true || value === false) return value;
    return fallback === true;
  }

  function fieldValue(object, camel, snake) {
    if (!object || typeof object !== 'object') return undefined;
    if (Object.prototype.hasOwnProperty.call(object, camel) &&
      object[camel] !== undefined && object[camel] !== null) return object[camel];
    return object[snake];
  }

  function boolField(object, camel, snake, fallback) {
    var value = fieldValue(object, camel, snake);
    return typeof value === 'boolean' ? value : fallback === true;
  }

  function oneOf(value, allowed, fallback) {
    return allowed.indexOf(value) >= 0 ? value : fallback;
  }

  function safeRecordId(value) {
    return typeof value === 'string' && RECORD_ID_RE.test(value);
  }

  function safeMediaId(value) {
    return typeof value === 'string' && MEDIA_ID_RE.test(value);
  }

  function safeCursor(value) {
    return typeof value === 'string' && CURSOR_RE.test(value);
  }

  function safeClientKey(value) {
    return typeof value === 'string' && CLIENT_KEY_RE.test(value);
  }

  function safeMediaUrl(value) {
    if (typeof value !== 'string' || value.length > 320) return false;
    if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0 || value.indexOf('\\') >= 0) return false;
    return MEDIA_URL_RE.test(value);
  }

  function mediaThumbForId(resourceId) {
    return safeMediaId(resourceId)
      ? 'https://localdata.invalid/media/' + resourceId + '/thumb'
      : '';
  }

  function mediaOriginalForId(resourceId) {
    return safeMediaId(resourceId)
      ? 'https://localdata.invalid/media/' + resourceId + '/original'
      : '';
  }

  function safeAvatarUrl(value) {
    if (typeof value !== 'string') return '';
    if (value.length > MAX_AVATAR_DATA_URL) return '';
    if (safeMediaUrl(value)) return value;
    if (DATA_IMAGE_RE.test(value)) return value;
    return '';
  }

  function firstSafeUrl() {
    for (var i = 0; i < arguments.length; i++) {
      if (safeMediaUrl(arguments[i])) return arguments[i];
    }
    return '';
  }

  function normalizeMedia(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var resourceId = str(raw.resourceId || raw.resource_id, 80);
    if (!safeMediaId(resourceId)) return null;
    var thumb = firstSafeUrl(raw.thumbnailUrl, raw.thumbUrl, raw.thumbnail_url, raw.thumb_url);
    var original = firstSafeUrl(raw.originalUrl, raw.original_url);
    if (!thumb && original) thumb = original.replace(/\/original$/, '/thumb');
    if (!original && thumb) original = thumb.replace(/\/thumb$/, '/original');
    var width = int(raw.width, 0, 0, 20000);
    var height = int(raw.height, 0, 0, 20000);
    return {
      resourceId: resourceId,
      mime: str(raw.mime || raw.mimeType, 80),
      width: width,
      height: height,
      byteSize: int(raw.byteSize || raw.byte_size, 0, 0, 64 * 1024 * 1024),
      thumbUrl: thumb,
      originalUrl: original,
      available: !!(thumb || original),
      displayName: str(raw.displayName || raw.name, 120)
    };
  }

  function normalizeMediaList(rawList) {
    var list = Array.isArray(rawList) ? rawList : [];
    var output = [];
    for (var i = 0; i < list.length && output.length < MAX_MEDIA_PER_POST; i++) {
      var media = normalizeMedia(list[i]);
      if (media) output.push(media);
    }
    return output;
  }

  /*
   * Host-generated images: the host enumerates its own persisted image files and
   * returns only an opaque id plus real metadata. This layer keeps that shape
   * bounded and rejects anything that looks like a path or a made-up id.
   */
  function normalizeGeneratedImage(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var id = str(raw.id, 64);
    if (!GENERATED_IMAGE_ID_RE.test(id)) return null;
    return {
      id: id,
      source: oneOf(raw.source, GENERATED_IMAGE_SOURCES, ''),
      createdAt: int(raw.createdAt || raw.created_at, 0, 0, Number.MAX_SAFE_INTEGER),
      byteSize: int(raw.byteSize || raw.byte_size, 0, 0, MAX_GENERATED_IMAGE_BYTES),
      width: int(raw.width, 0, 0, 20000),
      height: int(raw.height, 0, 0, 20000)
    };
  }

  function normalizeGeneratedImages(data) {
    var body = data && typeof data === 'object' ? data : {};
    var list = Array.isArray(body.items) ? body.items : [];
    var items = [];
    for (var i = 0; i < list.length && items.length < MAX_GENERATED_IMAGES; i++) {
      var item = normalizeGeneratedImage(list[i]);
      if (item) items.push(item);
    }
    return {
      items: items,
      truncated: bool(body.truncated) || list.length > MAX_GENERATED_IMAGES
    };
  }

  function normalizeAuthor(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var kind = oneOf(raw.kind, AUTHOR_KINDS, '');
    var id = str(raw.id, 80);
    if (!kind || !safeRecordId(id)) return null;
    return {
      kind: kind,
      id: id,
      name: str(raw.name, 80),
      avatar: safeAvatarUrl(raw.avatar),
      capturedAt: int(raw.capturedAt || raw.captured_at, 0, 0, Number.MAX_SAFE_INTEGER)
    };
  }

  function normalizeChatMessage(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var role = oneOf(raw.role, ['user', 'assistant'], '');
    if (!role) return null;
    var media = [];
    var ids = Array.isArray(raw.media) ? raw.media : [];
    for (var i = 0; i < ids.length && media.length < MAX_MEDIA_PER_POST; i++) {
      if (safeMediaId(ids[i])) media.push(ids[i]);
    }
    var rawAvatarMediaId = str(fieldValue(raw, 'avatarMediaId', 'avatar_media_id'), 80);
    var hasAvatarMedia = safeMediaId(rawAvatarMediaId);
    var avatarEmoji = str(fieldValue(raw, 'avatarEmoji', 'avatar_emoji'), 32)
      .replace(/[\u0000-\u001F\u007F]/g, '');
    return {
      role: role,
      name: str(raw.name, 100),
      text: str(raw.text, 2000),
      media: media,
      avatarMediaId: hasAvatarMedia ? rawAvatarMediaId : '',
      avatarEmoji: hasAvatarMedia ? '' : avatarEmoji
    };
  }

  function normalizeChatSnapshot(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var messages = [];
    var list = Array.isArray(raw.messages) ? raw.messages : [];
    for (var i = 0; i < list.length && messages.length < 50; i++) {
      var message = normalizeChatMessage(list[i]);
      if (message) messages.push(message);
    }
    if (!messages.length) return null;
    return {
      title: str(raw.title, 200),
      messages: messages,
      capturedAt: int(raw.capturedAt || raw.captured_at, 0, 0, Number.MAX_SAFE_INTEGER)
    };
  }

  function normalizeComment(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var id = str(raw.id, 80);
    var postId = str(raw.postId || raw.post_id, 80);
    var author = normalizeAuthor(raw.author);
    if (!safeRecordId(id) || !safeRecordId(postId) || !author) return null;
    var replyToId = str(raw.replyToId || raw.reply_to_id, 80);
    return {
      id: id,
      postId: postId,
      author: author,
      body: str(raw.body, MAX_COMMENT_CHARS),
      createdAt: int(raw.createdAt || raw.created_at, 0, 0, Number.MAX_SAFE_INTEGER),
      replyToId: safeRecordId(replyToId) ? replyToId : ''
    };
  }

  function normalizePost(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var id = str(raw.id, 80);
    var author = normalizeAuthor(raw.author);
    if (!safeRecordId(id) || !author) return null;
    var visibility = oneOf(raw.visibility, VISIBILITIES, '');
    var preview = [];
    var previewRaw = Array.isArray(raw.previewComments) ? raw.previewComments : [];
    for (var i = 0; i < previewRaw.length && preview.length < 3; i++) {
      var comment = normalizeComment(previewRaw[i]);
      if (comment) preview.push(comment);
    }
    var selected = [];
    var selectedRaw = Array.isArray(raw.selectedAssistantIds) ? raw.selectedAssistantIds : [];
    for (var j = 0; j < selectedRaw.length && selected.length < 50; j++) {
      if (safeRecordId(selectedRaw[j])) selected.push(selectedRaw[j]);
    }
    return {
      id: id,
      author: author,
      body: str(raw.body, MAX_BODY_CHARS),
      bodyTruncated: bool(raw.bodyTruncated),
      visibility: visibility,
      selectedAssistantIds: selected,
      media: normalizeMediaList(raw.media),
      mediaOmitted: bool(raw.mediaOmitted),
      chatSnapshot: normalizeChatSnapshot(raw.chatSnapshot),
      chatSnapshotOmitted: bool(raw.chatSnapshotOmitted),
      createdAt: int(raw.createdAt || raw.created_at, 0, 0, Number.MAX_SAFE_INTEGER),
      updatedAt: int(raw.updatedAt || raw.updated_at, 0, 0, Number.MAX_SAFE_INTEGER),
      commentCount: int(raw.commentCount || raw.comment_count, 0, 0, 1000000),
      likeCount: int(raw.likeCount || raw.like_count, 0, 0, 1000000),
      likedByMe: bool(raw.likedByMe || raw.liked_by_me),
      previewComments: preview
    };
  }

  function normalizePage(raw, options) {
    options = options || {};
    var data = unwrap(raw);
    data = data && typeof data === 'object' ? data : {};
    var items = [];
    var list = Array.isArray(data.items) ? data.items : [];
    var normalize = options.normalize || normalizePost;
    for (var i = 0; i < list.length; i++) {
      var item = normalize(list[i]);
      if (item) items.push(item);
    }
    var cursor = str(data.nextCursor || data.next_cursor, 200);
    var cursorInvalid = false;
    if (cursor && !safeCursor(cursor)) {
      cursor = '';
      cursorInvalid = true;
    }
    var rawHasMore = bool(data.hasMore || data.has_more);
    if (rawHasMore && !cursor) cursorInvalid = true;
    var hasMore = rawHasMore && !!cursor && !cursorInvalid;
    var budget = data.budget && typeof data.budget === 'object' ? {
      pageSize: int(data.budget.pageSize, 0, 0, MAX_PAGE_SIZE),
      bodyCharsPerItem: int(data.budget.bodyCharsPerItem, 0, 0, 20000),
      bodyCharsUsed: int(data.budget.bodyCharsUsed, 0, 0, 200000),
      truncated: bool(data.budget.truncated)
    } : null;
    return {
      items: items,
      nextCursor: cursor,
      hasMore: hasMore,
      truncated: bool(data.truncated) || !!(budget && budget.truncated),
      budget: budget,
      cursorInvalid: cursorInvalid,
      rejected: list.length - items.length
    };
  }

  function normalizeDraft(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var id = str(raw.id, 80);
    var author = normalizeAuthor(raw.author);
    if (!safeRecordId(id) || !author) return null;
    var selected = [];
    var selectedRaw = Array.isArray(raw.selectedAssistantIds) ? raw.selectedAssistantIds : [];
    for (var i = 0; i < selectedRaw.length && selected.length < 50; i++) {
      if (safeRecordId(selectedRaw[i])) selected.push(selectedRaw[i]);
    }
    return {
      id: id,
      author: author,
      body: str(raw.body, MAX_BODY_CHARS),
      visibility: oneOf(raw.visibility, VISIBILITIES, 'all_assistants'),
      selectedAssistantIds: selected,
      media: normalizeMediaList(raw.media),
      mediaOmitted: bool(raw.mediaOmitted),
      chatSnapshot: normalizeChatSnapshot(raw.chatSnapshot),
      chatSnapshotOmitted: bool(raw.chatSnapshotOmitted),
      createdAt: int(raw.createdAt || raw.created_at, 0, 0, Number.MAX_SAFE_INTEGER),
      updatedAt: int(raw.updatedAt || raw.updated_at, 0, 0, Number.MAX_SAFE_INTEGER)
    };
  }

  function normalizeDraftPage(raw) {
    return normalizePage(raw, { normalize: normalizeDraft });
  }

  function normalizeInfo(raw) {
    var data = unwrap(raw);
    if (!data || typeof data !== 'object') {
      throw apiError('protocol', 'localdata.info did not return an object');
    }
    return {
      apiVersion: int(data.apiVersion || data.api_version, VERSION, 0, 1000),
      schemaVersion: int(data.schemaVersion || data.schema_version, 0, 0, 100000),
      pluginId: str(data.pluginId || data.plugin_id, 128),
      mediaOrigin: str(data.mediaOrigin || data.media_origin, 200)
    };
  }

  function normalizeTheme(theme) {
    if (!theme || typeof theme !== 'object' || Array.isArray(theme)) return [];
    var output = [];
    var keys = Object.keys(theme);
    for (var i = 0; i < keys.length && output.length < MAX_THEME_KEYS; i++) {
      var rawKey = keys[i];
      var key = normalizeThemeKey(rawKey);
      if (!key) continue;
      var value = str(theme[rawKey], 240).trim();
      if (!value || BAD_CSS_VALUE_RE.test(value)) continue;
      if (!SAFE_CSS_VALUE_RE.test(value)) continue;
      output.push({ key: key, value: value });
    }
    return output;
  }

  function normalizeThemeKey(rawKey) {
    if (typeof rawKey !== 'string') return '';
    if (rawKey.indexOf('--') === 0) {
      return /^--[A-Za-z0-9_-]{1,64}$/.test(rawKey) ? rawKey : '';
    }
    return /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(rawKey) ? '--' + rawKey : '';
  }

  function normalizeHostInfo(raw) {
    var data = unwrap(raw);
    if (!data || typeof data !== 'object') {
      throw apiError('protocol', 'localDataHostInfo did not return an object');
    }
    var user = data.user && typeof data.user === 'object' ? {
      name: str(data.user.name, 80),
      avatar: safeAvatarUrl(data.user.avatar)
    } : null;
    var assistants = [];
    var list = Array.isArray(data.assistants) ? data.assistants : [];
    for (var i = 0; i < list.length && assistants.length < 100; i++) {
      var item = list[i];
      if (!item || typeof item !== 'object') continue;
      var id = str(item.id, 80);
      if (!safeRecordId(id)) continue;
      assistants.push({
        id: id,
        name: str(item.name, 80),
        avatar: safeAvatarUrl(item.avatar)
      });
    }
    var capabilities = [];
    var caps = Array.isArray(data.capabilities) ? data.capabilities : [];
    for (var j = 0; j < caps.length && capabilities.length < 64; j++) {
      var cap = str(caps[j], 80);
      if (/^[A-Za-z][A-Za-z0-9._-]{0,79}$/.test(cap)) capabilities.push(cap);
    }
    return {
      user: user,
      assistants: assistants,
      theme: normalizeTheme(data.theme),
      capabilities: capabilities
    };
  }

  function unwrap(raw) {
    if (raw && typeof raw === 'object' && raw.ok === true && raw.data !== undefined) return raw.data;
    return raw;
  }

  function parseEnvelope(raw) {
    var object = raw;
    if (typeof raw === 'string') {
      var trimmed = raw.trim();
      if (!trimmed) throw apiError('protocol', 'Host returned an empty response');
      try {
        object = JSON.parse(trimmed);
      } catch (error) {
        throw apiError('protocol', 'Host response is not valid JSON');
      }
    }
    if (!object || typeof object !== 'object' || Array.isArray(object)) {
      throw apiError('protocol', 'Host response is not an envelope object');
    }
    if (object.version !== undefined && object.version !== VERSION) {
      throw apiError('unsupported_version', 'Unsupported localData API version');
    }
    if (object.ok === false) {
      var detail = object.error && typeof object.error === 'object' ? object.error : {};
      throw apiError(
        str(detail.code, 60) || 'internal',
        str(detail.message, 200),
        detail.retryable === true
      );
    }
    if (object.ok !== true) {
      throw apiError('protocol', 'Host response is missing ok=true');
    }
    return object.data === undefined ? null : object.data;
  }

  function parsePickImages(raw) {
    if (raw && typeof raw === 'object' && raw.ok === false) {
      parseEnvelope(raw);
    }
    var data = unwrap(raw);
    if (!data || typeof data !== 'object' || !Array.isArray(data.media)) {
      throw apiError('protocol', 'localDataPickImages did not return data.media[]');
    }
    var media = [];
    for (var i = 0; i < data.media.length && media.length < MAX_MEDIA_PER_POST; i++) {
      var item = normalizeMedia(data.media[i]);
      if (item) media.push(item);
    }
    if (data.media.length && !media.length) {
      throw apiError('protocol', 'localDataPickImages returned no usable media resource');
    }
    return { media: media, rejected: data.media.length - media.length };
  }

  /*
   * The host LocalTaskBridge speaks one JSON envelope:
   *   {"version":1,"method":"task.start|task.status|task.cancel|task.settings|
   *    task.review.list|task.review.resolve","params":{...}}
   * The old reserved `{action,params}` shape is not accepted. These normalizers only
   * accept the documented DTOs and never invent a task state or an approval result.
   */
  function normalizeTaskError(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var code = str(raw.code, 60);
    if (!code) return null;
    return { code: code, message: str(raw.message, 200) };
  }

  function normalizePendingReview(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var id = str(raw.id, 80);
    var assistantId = str(raw.assistantId || raw.assistant_id, 80);
    if (!safeRecordId(id) || !safeRecordId(assistantId)) return null;
    var mediaIds = [];
    var rawMedia = Array.isArray(raw.mediaIds) ? raw.mediaIds : [];
    for (var i = 0; i < rawMedia.length && mediaIds.length < MAX_MEDIA_PER_POST; i++) {
      if (safeMediaId(rawMedia[i])) mediaIds.push(rawMedia[i]);
    }
    var postId = str(raw.postId || raw.post_id, 80);
    var replyToId = str(raw.replyToId || raw.reply_to_id, 80);
    return {
      id: id,
      kind: str(raw.kind, 40) || 'action',
      assistantId: assistantId,
      assistantName: str(raw.assistantName || raw.assistant_name, 80),
      postId: safeRecordId(postId) ? postId : '',
      body: str(raw.body, MAX_COMMENT_CHARS),
      replyToId: safeRecordId(replyToId) ? replyToId : '',
      like: bool(raw.like),
      mediaIds: mediaIds,
      createdAt: int(raw.createdAt || raw.created_at, 0, 0, Number.MAX_SAFE_INTEGER)
    };
  }

  function normalizeTaskStatus(raw) {
    var data = unwrap(raw);
    if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
    var taskId = str(data.taskId || data.task_id, 80);
    if (!safeRecordId(taskId)) return null;
    var pendingReview = [];
    var pendingRaw = Array.isArray(data.pendingReview) ? data.pendingReview : [];
    for (var i = 0; i < pendingRaw.length && pendingReview.length < 50; i++) {
      var review = normalizePendingReview(pendingRaw[i]);
      if (review) pendingReview.push(review);
    }
    var postId = str(data.postId || data.post_id, 80);
    var assistantId = str(data.assistantId || data.assistant_id, 80);
    var state = oneOf(str(data.state, 40), TASK_STATES, '');
    return {
      taskId: taskId,
      mode: oneOf(str(data.mode, 20), ['interact', 'publish'], 'interact'),
      state: state,
      postId: safeRecordId(postId) ? postId : '',
      assistantId: safeRecordId(assistantId) ? assistantId : '',
      round: int(data.round, 0, 0, 1000),
      turns: int(data.turns, 0, 0, 100000),
      comments: int(data.comments, 0, 0, 100000),
      likes: int(data.likes, 0, 0, 100000),
      modelCalls: int(data.modelCalls || data.model_calls, 0, 0, 100000),
      commentsTruncated: bool(data.commentsTruncated || data.comments_truncated),
      endReason: str(data.endReason || data.end_reason, 120),
      error: normalizeTaskError(data.error),
      pendingReview: pendingReview,
      createdAt: int(data.createdAt || data.created_at, 0, 0, Number.MAX_SAFE_INTEGER),
      updatedAt: int(data.updatedAt || data.updated_at, 0, 0, Number.MAX_SAFE_INTEGER),
      finishedAt: int(data.finishedAt || data.finished_at, 0, 0, Number.MAX_SAFE_INTEGER)
    };
  }

  function normalizeTaskStatusList(raw) {
    var data = unwrap(raw);
    var list = [];
    if (Array.isArray(data)) list = data;
    else if (data && typeof data === 'object' && Array.isArray(data.items)) list = data.items;
    else if (data && typeof data === 'object') list = [data];
    var output = [];
    for (var i = 0; i < list.length && output.length < 100; i++) {
      var status = normalizeTaskStatus(list[i]);
      if (status) output.push(status);
    }
    return output;
  }

  function normalizeTaskStart(raw) {
    var data = unwrap(raw);
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      throw apiError('protocol', 'task.start did not return an object');
    }
    var taskId = str(data.taskId || data.task_id, 80);
    if (!safeRecordId(taskId)) throw apiError('protocol', 'task.start did not return a valid taskId');
    return {
      taskId: taskId,
      existing: bool(data.existing),
      state: oneOf(str(data.state, 40), TASK_STATES, '')
    };
  }

  function normalizeTaskCancel(raw) {
    var data = unwrap(raw);
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      throw apiError('protocol', 'task.cancel did not return an object');
    }
    return { cancelled: int(data.cancelled, 0, 0, 100000) };
  }

  function normalizeTaskSettings(raw) {
    var data = unwrap(raw);
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      throw apiError('protocol', 'task.settings did not return an object');
    }
    var participants = [];
    var rawParticipants = Array.isArray(data.participants) ? data.participants : [];
    for (var i = 0; i < rawParticipants.length && participants.length < 50; i++) {
      if (safeRecordId(rawParticipants[i])) participants.push(rawParticipants[i]);
    }
    return {
      enabled: bool(data.enabled),
      globalStop: boolField(data, 'globalStop', 'global_stop', false),
      commentUserPosts: boolField(data, 'commentUserPosts', 'comment_user_posts', true),
      commentAssistantPosts: boolField(data, 'commentAssistantPosts', 'comment_assistant_posts', true),
      autoLike: boolField(data, 'autoLike', 'auto_like', true),
      maxRounds: int(fieldValue(data, 'maxRounds', 'max_rounds'), 2, 0, 10),
      maxRepliesPerAssistantPerPost: int(fieldValue(data, 'maxRepliesPerAssistantPerPost', 'max_replies_per_assistant_per_post'), 1, 0, 5),
      maxModelCallsPerPost: int(fieldValue(data, 'maxModelCallsPerPost', 'max_model_calls_per_post'), 8, 1, 50),
      dailyModelCallBudget: int(fieldValue(data, 'dailyModelCallBudget', 'daily_model_call_budget'), 200, 0, 10000),
      maxOutputChars: int(fieldValue(data, 'maxOutputChars', 'max_output_chars'), 280, 40, MAX_COMMENT_CHARS),
      intervalMs: int(fieldValue(data, 'intervalMs', 'interval_ms'), 45000, 0, 3600000),
      requireReview: boolField(data, 'requireReview', 'require_review', false),
      allowImages: boolField(data, 'allowImages', 'allow_images', false),
      participants: participants
    };
  }

  function normalizeTaskReviews(raw) {
    var data = unwrap(raw);
    var list = [];
    if (Array.isArray(data)) list = data;
    else if (data && typeof data === 'object' && Array.isArray(data.items)) list = data.items;
    var output = [];
    for (var i = 0; i < list.length && output.length < 50; i++) {
      var review = normalizePendingReview(list[i]);
      if (review) output.push(review);
    }
    return output;
  }

  function normalizeTaskReviewResolve(raw) {
    var data = unwrap(raw);
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      throw apiError('protocol', 'task.review.resolve did not return an object');
    }
    var reviewId = str(data.reviewId || data.review_id, 80);
    if (!safeRecordId(reviewId)) throw apiError('protocol', 'task.review.resolve did not return a valid reviewId');
    return {
      reviewId: reviewId,
      applied: bool(data.applied),
      approved: bool(data.approved),
      state: oneOf(str(data.state, 40), TASK_STATES, ''),
      detail: str(data.detail, 200)
    };
  }

  function taskStateLabel(value) {
    if (value === 'queued') return '排队中';
    if (value === 'running') return '进行中';
    if (value === 'waiting_review') return '等待审核';
    if (value === 'completed') return '已完成';
    if (value === 'cancelled') return '已取消';
    if (value === 'failed') return '失败';
    if (value === 'interrupted') return '已中断';
    return '状态未知';
  }

  function humanError(error) {
    var code = error && error.code ? String(error.code) : 'unknown';
    var messages = {
      invalid_request: '宿主拒绝了请求参数；没有写入任何数据。',
      unsupported_version: '宿主 localData API 版本不匹配（本插件需要 v1）。',
      unknown_method: '宿主尚未实现这个朋友圈接口。',
      capability_denied: '宿主未授予这项朋友圈能力。请在宿主插件权限中授权后重试。',
      forbidden: '当前页面身份无权执行该操作。身份只由宿主创建，页面不能自证。',
      not_found: '内容不存在，或当前身份不可见。',
      conflict: '数据已被其他操作改变，请刷新后再试。',
      payload_too_large: '内容、图片或请求超出宿主限制。',
      unavailable: '宿主本地数据服务暂不可用。',
      internal: '宿主内部错误；没有自动重试或伪造结果。',
      protocol: '宿主返回格式不符合 localData v1 契约。',
      missing_bridge: '未检测到 window.OrangeBridge。请使用已接线 localData 的橘瓣宿主打开插件页。',
      missing_method: '当前宿主未提供 ' + (error && error.method ? error.method : '所需') + ' 接口；不会用示例数据代替。',
      timeout: '宿主响应超时；为避免重复写入，没有自动重试。',
      bridge_exception: '调用宿主桥接时发生异常；没有自动重试。',
      cancelled: '已取消。',
      not_ready: '宿主接口尚未就绪；本页不会模拟助手回复。',
      clipboard: '当前 WebView 不支持剪贴板写入。',
      network: '本插件不发起业务网络请求。',
      disabled: '宿主未启用助手互动；请先在宿主原生“互动”设置中启用。',
      stopped: '助手互动已被宿主全局停止。',
      interaction_disabled: '宿主关闭了这类动态的助手互动。',
      no_participants: '宿主原生设置里没有参与互动的助手。',
      assistant_not_participant: '该助手不在宿主原生设置的参与名单中。',
      assistant_missing: '该助手已不存在或不可用。',
      model_unavailable: '该助手没有可用的聊天模型。',
      budget_exhausted: '助手互动预算已用完；任务没有继续调用模型。',
      publish_denied: '宿主未授予发布权限；任务不会假装发布成功。',
      image_unsupported: '宿主当前版本不支持导入这张图片。',
      post_unavailable: '动态不存在，或助手无权读取。',
      invalid_action: '助手返回的动作不符合宿主严格 JSON 协议。'
    };
    return messages[code] || '操作未完成；没有显示示例数据或模拟回复。';
  }

  function clampPageSize(value) {
    return int(value, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
  }

  function visibilityLabel(value) {
    if (value === 'all_assistants') return '全部助手可见';
    if (value === 'selected') return '指定助手可见';
    if (value === 'self') return '仅自己可见';
    return '可见范围未知';
  }

  function authorKindLabel(value) {
    if (value === 'user') return '本机用户';
    if (value === 'assistant') return '本机助手';
    return '未知身份';
  }

  function typeLabel(value) {
    if (value === 'text') return '文字';
    if (value === 'image') return '图片';
    if (value === 'chat_snapshot') return '聊天记录';
    return '全部';
  }

  function postType(post) {
    if (!post) return 'text';
    if (post.chatSnapshot && post.chatSnapshot.messages && post.chatSnapshot.messages.length) return 'chat_snapshot';
    if (post.media && post.media.length) return 'image';
    return 'text';
  }

  function pad2(value) {
    return value < 10 ? '0' + value : String(value);
  }

  function sameDay(a, b) {
    return a.getFullYear() === b.getFullYear() &&
      a.getMonth() === b.getMonth() &&
      a.getDate() === b.getDate();
  }

  function formatTime(timestamp, now) {
    var value = int(timestamp, 0, 0, Number.MAX_SAFE_INTEGER);
    if (!value) return '';
    var date = new Date(value);
    if (isNaN(date.getTime())) return '';
    var current = typeof now === 'number' ? new Date(now) : new Date();
    var diff = current.getTime() - value;
    if (diff >= 0 && diff < 60000) return '刚刚';
    if (diff >= 0 && diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    var hhmm = pad2(date.getHours()) + ':' + pad2(date.getMinutes());
    if (sameDay(date, current)) return '今天 ' + hhmm;
    var yesterday = new Date(current.getTime());
    yesterday.setDate(yesterday.getDate() - 1);
    if (sameDay(date, yesterday)) return '昨天 ' + hhmm;
    if (date.getFullYear() === current.getFullYear()) {
      return pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) + ' ' + hhmm;
    }
    return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) + ' ' + hhmm;
  }

  function formatFullTime(timestamp) {
    var value = int(timestamp, 0, 0, Number.MAX_SAFE_INTEGER);
    if (!value) return '';
    var date = new Date(value);
    if (isNaN(date.getTime())) return '';
    return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) +
      ' ' + pad2(date.getHours()) + ':' + pad2(date.getMinutes());
  }

  function dateInputToMs(value, endOfDay) {
    if (typeof value !== 'string') return 0;
    var match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
    if (!match) return 0;
    var year = Number(match[1]);
    var month = Number(match[2]) - 1;
    var day = Number(match[3]);
    var date = new Date(year, month, day, endOfDay ? 23 : 0, endOfDay ? 59 : 0, endOfDay ? 59 : 0, endOfDay ? 999 : 0);
    if (date.getFullYear() !== year || date.getMonth() !== month || date.getDate() !== day) return 0;
    return date.getTime();
  }

  function cleanSelectedIds(values) {
    var output = [];
    var list = Array.isArray(values) ? values : [];
    for (var i = 0; i < list.length && output.length < MAX_SELECTED_ASSISTANTS; i++) {
      var id = str(list[i], 80);
      if (safeRecordId(id) && output.indexOf(id) < 0) output.push(id);
    }
    return output;
  }

  function cleanMediaIds(values) {
    var output = [];
    var list = Array.isArray(values) ? values : [];
    for (var i = 0; i < list.length && output.length < MAX_MEDIA_PER_POST; i++) {
      var id = typeof list[i] === 'string' ? list[i] : (list[i] && list[i].resourceId);
      if (safeMediaId(id) && output.indexOf(id) < 0) output.push(id);
    }
    return output;
  }

  function buildListParams(filters, cursor, limit, search) {
    filters = filters || {};
    var params = { limit: clampPageSize(limit) };
    if (cursor && safeCursor(cursor)) params.cursor = cursor;
    if (search) {
      var query = str(filters.query, MAX_SEARCH_CHARS).trim();
      if (query) params.query = query;
    }
    if (filters.authorKind === 'user' || filters.authorKind === 'assistant') {
      params.authorKind = filters.authorKind;
    }
    if (filters.authorId && safeRecordId(filters.authorId)) {
      params.authorId = filters.authorId;
    }
    if (POST_TYPES.indexOf(filters.type) >= 0) params.type = filters.type;
    if (filters.dateFrom) params.dateFrom = int(filters.dateFrom, 0, 0, Number.MAX_SAFE_INTEGER);
    if (filters.dateTo) params.dateTo = int(filters.dateTo, 0, 0, Number.MAX_SAFE_INTEGER);
    params.commentPreview = 3;
    params.bodyChars = 1200;
    return params;
  }

  function buildDetailParams(postId, commentCursor) {
    var params = {
      postId: str(postId, 80),
      commentLimit: 3,
      includeChatSnapshot: true,
      includeMedia: true,
      bodyChars: 4000
    };
    if (commentCursor && safeCursor(commentCursor)) params.commentCursor = commentCursor;
    return params;
  }

  function buildCommentsParams(postId, cursor, limit) {
    var params = { postId: str(postId, 80), limit: clampPageSize(limit) };
    if (cursor && safeCursor(cursor)) params.cursor = cursor;
    return params;
  }

  function buildLikeParams(postId, liked) {
    return { postId: str(postId, 80), liked: liked === true };
  }

  function buildCommentCreateParams(postId, body, replyToId, clientKey) {
    var params = {
      postId: str(postId, 80),
      body: str(body, MAX_COMMENT_CHARS)
    };
    if (replyToId && safeRecordId(replyToId)) params.replyToId = replyToId;
    if (safeClientKey(clientKey)) params.clientKey = clientKey;
    return params;
  }

  function normalizeVisibility(value) {
    return oneOf(value, VISIBILITIES, 'all_assistants');
  }

  function buildCreateParams(editor, clientKey) {
    editor = editor || {};
    var params = {
      body: str(editor.body, MAX_BODY_CHARS),
      visibility: normalizeVisibility(editor.visibility),
      selectedAssistantIds: normalizeVisibility(editor.visibility) === 'selected'
        ? cleanSelectedIds(editor.selectedAssistantIds || editor.selected)
        : [],
      mediaIds: cleanMediaIds(editor.media || editor.mediaIds)
    };
    if (editor.chatSnapshot) {
      var snapshot = normalizeChatSnapshot(editor.chatSnapshot);
      if (snapshot) params.chatSnapshot = snapshot;
    }
    if (editor.draftId && safeRecordId(editor.draftId)) params.draftId = editor.draftId;
    if (safeClientKey(clientKey)) params.clientKey = clientKey;
    return params;
  }

  function buildDraftParams(editor) {
    editor = editor || {};
    var params = {
      body: str(editor.body, MAX_BODY_CHARS),
      visibility: normalizeVisibility(editor.visibility),
      selectedAssistantIds: normalizeVisibility(editor.visibility) === 'selected'
        ? cleanSelectedIds(editor.selectedAssistantIds || editor.selected)
        : [],
      mediaIds: cleanMediaIds(editor.media || editor.mediaIds)
    };
    if (editor.chatSnapshot) {
      var snapshot = normalizeChatSnapshot(editor.chatSnapshot);
      if (snapshot) params.chatSnapshot = snapshot;
    }
    if (editor.draftId && safeRecordId(editor.draftId)) params.draftId = editor.draftId;
    return params;
  }

  function buildPublishDraftParams(draftId) {
    if (!safeRecordId(draftId)) throw apiError('invalid_request', 'Invalid draft id');
    return { draftId: draftId };
  }

  function buildDeleteParams(postId) {
    return { postId: str(postId, 80) };
  }

  function buildDraftDeleteParams(draftId) {
    return { draftId: str(draftId, 80) };
  }

  function buildAuthorParams(kind, id) {
    return {
      authorKind: oneOf(kind, AUTHOR_KINDS, 'user'),
      authorId: str(id, 80)
    };
  }

  function buildTaskRequest(method, payload) {
    if (TASK_METHODS.indexOf(method) < 0) {
      throw apiError('invalid_request', 'Unsupported task method');
    }
    payload = payload && typeof payload === 'object' ? payload : {};
    var params = {};
    if (method === 'task.start') {
      if (!safeRecordId(payload.postId)) throw apiError('invalid_request', 'task.start requires a valid postId');
      params.postId = payload.postId;
      if (payload.assistantId && safeRecordId(payload.assistantId)) params.assistantId = payload.assistantId;
      if (payload.clientKey && safeClientKey(payload.clientKey)) params.clientKey = payload.clientKey;
    } else if (method === 'task.status') {
      if (payload.taskId && safeRecordId(payload.taskId)) params.taskId = payload.taskId;
      if (payload.postId && safeRecordId(payload.postId)) params.postId = payload.postId;
    } else if (method === 'task.cancel') {
      if (payload.all === true) {
        params.all = true;
      } else if (payload.taskId && safeRecordId(payload.taskId)) {
        params.taskId = payload.taskId;
      } else {
        throw apiError('invalid_request', 'task.cancel requires taskId or all=true');
      }
    } else if (method === 'task.review.resolve') {
      if (!safeRecordId(payload.reviewId)) throw apiError('invalid_request', 'task.review.resolve requires reviewId');
      if (typeof payload.approve !== 'boolean') throw apiError('invalid_request', 'task.review.resolve requires a boolean approve');
      params.reviewId = payload.reviewId;
      params.approve = payload.approve;
      if (payload.body !== undefined && payload.body !== null) {
        if (typeof payload.body !== 'string') throw apiError('invalid_request', 'Review body must be a string');
        var body = payload.body;
        if (body.trim()) {
          if (body.length > MAX_COMMENT_CHARS) throw apiError('invalid_request', 'Review body is too long');
          params.body = body;
        }
      }
    }
    return { version: VERSION, method: method, params: params };
  }

  function makeClientKey(prefix) {
    var alphabet = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
    var random = '';
    var cryptoObject = typeof crypto !== 'undefined' ? crypto : null;
    if (cryptoObject && cryptoObject.getRandomValues) {
      var bytes = new Uint8Array(12);
      cryptoObject.getRandomValues(bytes);
      for (var i = 0; i < bytes.length; i++) random += alphabet.charAt(bytes[i] % alphabet.length);
    } else {
      for (var j = 0; j < 12; j++) random += alphabet.charAt(Math.floor(Math.random() * alphabet.length));
    }
    var cleanPrefix = String(prefix || 'c').replace(/[^A-Za-z0-9]/g, '').slice(0, 10) || 'c';
    return (cleanPrefix + '_' + Date.now().toString(36) + '_' + random).slice(0, 64);
  }

  function boundedAppend(current, incoming, max) {
    var existing = Array.isArray(current) ? current.slice() : [];
    var next = Array.isArray(incoming) ? incoming : [];
    var capacity = Math.max(0, int(max, 0, 0, 100000) - existing.length);
    var accepted = capacity > 0 ? next.slice(0, capacity) : [];
    return {
      items: existing.concat(accepted),
      accepted: accepted.length,
      dropped: next.length - accepted.length,
      atLimit: existing.length + accepted.length >= max
    };
  }

  function truncationNotes(post, page) {
    var notes = [];
    if (!post) return notes;
    if (post.bodyTruncated) notes.push('正文按宿主读取预算截断；详情也是同一预算，不会伪装成全文。');
    if (post.mediaOmitted) notes.push('图片未随本次列表返回（可能未授权 media 能力）。');
    if (post.chatSnapshotOmitted) notes.push('聊天记录卡片未随本次返回（可能未授权 chat_snapshot 能力）。');
    if (page && page.truncated) notes.push('本页正文/评论预算已截断，截断标记来自宿主。');
    return notes;
  }

  function initials(name) {
    var text = str(name, 80).trim();
    if (!text) return '·';
    var characters = Array.from ? Array.from(text) : text.split('');
    if (/^[A-Za-z]/.test(text)) {
      return characters.slice(0, 2).join('').toUpperCase();
    }
    return characters.slice(0, 1).join('');
  }

  function hasActorKey(params) {
    if (!params || typeof params !== 'object') return false;
    for (var key in params) {
      if (Object.prototype.hasOwnProperty.call(params, key) && ACTOR_KEYS.indexOf(key) >= 0) return true;
    }
    return false;
  }

  function generatedSourceLabel(source) {
    if (source === 'chat_generated') return '聊天生图';
    if (source === 'plugin_tool') return '插件工具产物';
    if (source === 'image_generation') return '生图页面';
    return '宿主图库';
  }

  function formatByteSize(bytes) {
    var value = int(bytes, 0, 0, MAX_GENERATED_IMAGE_BYTES);
    if (!value) return '';
    if (value < 1024) return value + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(0) + ' KB';
    return (value / (1024 * 1024)).toFixed(1) + ' MB';
  }

  return {
    VERSION: VERSION,
    DEFAULT_PAGE_SIZE: DEFAULT_PAGE_SIZE,
    MAX_PAGE_SIZE: MAX_PAGE_SIZE,
    MAX_LIST_POSTS: MAX_LIST_POSTS,
    MAX_DETAIL_COMMENTS: MAX_DETAIL_COMMENTS,
    MAX_DRAFT_ROWS: MAX_DRAFT_ROWS,
    MAX_CHAT_MESSAGES_LIST: MAX_CHAT_MESSAGES_LIST,
    MAX_CHAT_MESSAGES_DETAIL: MAX_CHAT_MESSAGES_DETAIL,
    MAX_CHAT_CHARS_LIST: MAX_CHAT_CHARS_LIST,
    MAX_CHAT_CHARS_DETAIL: MAX_CHAT_CHARS_DETAIL,
    MAX_MEDIA_PER_POST: MAX_MEDIA_PER_POST,
    MAX_SELECTED_ASSISTANTS: MAX_SELECTED_ASSISTANTS,
    MAX_LIST_PREVIEW_MEDIA: MAX_LIST_PREVIEW_MEDIA,
    MAX_BODY_CHARS: MAX_BODY_CHARS,
    MAX_COMMENT_CHARS: MAX_COMMENT_CHARS,
    MAX_SEARCH_CHARS: MAX_SEARCH_CHARS,
    MAX_GENERATED_IMAGES: MAX_GENERATED_IMAGES,
    VISIBILITIES: VISIBILITIES,
    POST_TYPES: POST_TYPES,
    TASK_METHODS: TASK_METHODS,
    TASK_STATES: TASK_STATES,
    mediaThumbForId: mediaThumbForId,
    mediaOriginalForId: mediaOriginalForId,
    safeMediaId: safeMediaId,
    safeMediaUrl: safeMediaUrl,
    safeAvatarUrl: safeAvatarUrl,
    safeRecordId: safeRecordId,
    safeCursor: safeCursor,
    safeClientKey: safeClientKey,
    apiError: apiError,
    normalizeMedia: normalizeMedia,
    normalizeGeneratedImage: normalizeGeneratedImage,
    normalizeGeneratedImages: normalizeGeneratedImages,
    generatedSourceLabel: generatedSourceLabel,
    formatByteSize: formatByteSize,
    normalizeAuthor: normalizeAuthor,
    normalizeChatSnapshot: normalizeChatSnapshot,
    normalizeComment: normalizeComment,
    normalizePost: normalizePost,
    normalizePage: normalizePage,
    normalizeDraft: normalizeDraft,
    normalizeDraftPage: normalizeDraftPage,
    normalizeInfo: normalizeInfo,
    normalizeHostInfo: normalizeHostInfo,
    normalizeTheme: normalizeTheme,
    themeCssProperties: normalizeTheme,
    normalizeTaskError: normalizeTaskError,
    normalizePendingReview: normalizePendingReview,
    normalizeTaskStatus: normalizeTaskStatus,
    normalizeTaskStatusList: normalizeTaskStatusList,
    normalizeTaskStart: normalizeTaskStart,
    normalizeTaskCancel: normalizeTaskCancel,
    normalizeTaskSettings: normalizeTaskSettings,
    normalizeTaskReviews: normalizeTaskReviews,
    normalizeTaskReviewResolve: normalizeTaskReviewResolve,
    taskStateLabel: taskStateLabel,
    parseEnvelope: parseEnvelope,
    parsePickImages: parsePickImages,
    humanError: humanError,
    clampPageSize: clampPageSize,
    visibilityLabel: visibilityLabel,
    authorKindLabel: authorKindLabel,
    typeLabel: typeLabel,
    postType: postType,
    formatTime: formatTime,
    formatFullTime: formatFullTime,
    dateInputToMs: dateInputToMs,
    buildListParams: buildListParams,
    buildDetailParams: buildDetailParams,
    buildCommentsParams: buildCommentsParams,
    buildLikeParams: buildLikeParams,
    buildCommentCreateParams: buildCommentCreateParams,
    buildCreateParams: buildCreateParams,
    buildDraftParams: buildDraftParams,
    buildPublishDraftParams: buildPublishDraftParams,
    buildDeleteParams: buildDeleteParams,
    buildDraftDeleteParams: buildDraftDeleteParams,
    buildAuthorParams: buildAuthorParams,
    buildTaskRequest: buildTaskRequest,
    makeClientKey: makeClientKey,
    boundedAppend: boundedAppend,
    truncationNotes: truncationNotes,
    initials: initials,
    hasActorKey: hasActorKey
  };
});
