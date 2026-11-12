# localdata 小契约（task-5）

本目录是 OrangeChat 插件的**受控结构化存储与媒体仓库**，不包含朋友圈 UI、WebView 接线、助手模型调用或互评调度。
朋友圈插件（`plugins/local-moments`）通过本目录的 session/dispatcher 访问数据；宿主页面与后台任务负责建立**可信身份**。

- 包名：`me.rerere.rikkahub.plugin.localdata`
- API 版本：`LocalDataApi.VERSION = 1`
- SQLite schema 版本：`LocalDataApi.DB_SCHEMA_VERSION = 1`
- 每个 `pluginId` 独立数据库与独立媒体根，互不可见；数据库位于 `context.getDatabasePath(...)`，媒体位于
  `filesDir/plugin_localdata/<sha256(pluginId)>/media/`。
- 不依赖 WebView / ChatService / 主数据库；不改任何依赖。

## 1. 安全身份（JS 不能自证）

```kotlin
sealed interface LocalDataActor {
    data object User : LocalDataActor
    data class Assistant(val id: String, val name: String, val avatar: String? = null) : LocalDataActor
}
```

- `LocalDataActor.User` 只能由**宿主可信页面上下文**创建；`Assistant` 只能由宿主 chat/task 上下文创建。
- `LocalDataDispatcher.dispatch(request, context)` 的 `context.actor` 与 `context.assistantDirectory` 都是宿主参数，
  **不属于** `params`。`params` 里出现 `actorId/actor_id/viewerId/actingAssistantId/asAssistant` 等保留键会被
  直接拒绝（`invalid_request`），不会回退成“按参数里的助手身份执行”。
- 助手 actor 必须在宿主传入的 `LocalAssistantDirectory` 中存在，否则 `forbidden`。目录来自宿主真实的助手设置，
  不从插件 JSON 读取。
- 写方法（create/delete/comment/like/draft）额外拒绝 `authorId/authorName/authorKind/authorAvatar` 等字段；
  `authorId` 只允许作为 list/search 的过滤条件，不能用于伪造作者。用户显示名来自
  `LocalDataCallContext.userDisplayName`（宿主资料），同样不从页面参数读取。
- 能力（capability）由 manifest 声明、宿主授权；manifest 只是声明，不是授权。

能力字符串（manifest 的 `permissions` 数组）：

| capability | 用途 |
|---|---|
| `localdata.read` | list/get/search/comments list/author snapshot |
| `localdata.write` | 创建动态、保存/删除草稿、删除自己的动态 |
| `localdata.publish` | 助手把内容直接发布到动态流（未授予时助手只能存草稿） |
| `localdata.comment` | 发表评论 |
| `localdata.like` | 点赞/取消点赞 |
| `localdata.media` | 读取媒体元数据/缩略图/原图 URL；用户端媒体导入另走宿主 picker |
| `localdata.chat_snapshot` | 读取或写入聊天记录卡片快照 |
| `localdata.backup` | 宿主侧备份/恢复（不暴露给 JS 方法） |

## 2. 真实类

| 类 | 职责 |
|---|---|
| `LocalDataContract.kt` | 版本、错误码、DTO、actor/grant、request/envelope、cursor、预算常量 |
| `LocalDataDatabase.kt` | 独立 `SQLiteOpenHelper`、版本化 schema、按 pluginId 隔离的路径、事务 |
| `LocalDataStore.kt` | posts/comments/likes/drafts/授权快照/SQL ACL/游标分页/搜索/预算截断/逻辑导出 |
| `LocalDataMediaStore.kt` | 持久原图、按需缩略图、受控 resourceId、`https://localdata.invalid` URL、缓存清理 |
| `LocalDataDispatcher.kt` | `method + params -> ok/data/error` typed dispatcher、参数校验、能力与身份校验 |
| `LocalDataSession.kt` | 宿主接线的唯一入口：持有 db/store/media/dispatcher，连接 picker 导入和备份 |
| `LocalDataBackup.kt` | 版本化逻辑 ZIP 备份/恢复、SHA-256 完整性、ID 映射、不含密钥 |

### 2.1 Session（lead 首选接线点）

```kotlin
class LocalDataSession(
    context: Context,
    val pluginId: String,
    val grant: LocalDataGrant,
) : Closeable {
    fun call(
        requestJson: String,
        actor: LocalDataActor,
        assistantDirectory: LocalAssistantDirectory,
        assistantAutoPublish: Boolean = false,
    ): String // 返回 `LocalDataEnvelope` 的 JSON

    fun importImageFromUri(uri: Uri, displayName: String? = null): LocalImportedMedia
    fun importImageBytes(bytes: ByteArray, mimeHint: String? = null, displayName: String? = null): LocalImportedMedia
    fun openMedia(url: String): LocalMediaResponse? // WebView shouldInterceptRequest 用
    fun sweepUnreferencedMedia(): Int
    fun clearTransientCache(): LocalCacheClearReport
    fun exportBackup(destination: File, now: Long = System.currentTimeMillis()): LocalBackupManifest
    fun restoreBackup(source: File, options: LocalRestoreOptions): LocalRestoreReport
}
```

`LocalDataGrant.fromManifest(manifest.permissions)` 只提取声明，宿主可再收窄/授权。朋友圈 UI 的 JS 桥接对象应
**只**调用 `session.call(requestJson, LocalDataActor.User, hostDirectory)`；聊天/任务侧则传入宿主创建的
`LocalDataActor.Assistant(...)`，绝不要把 actor 放进请求 JSON。

### 2.2 Media URL

- 原图：`https://localdata.invalid/media/<resourceId>/original`
- 缩略图：`https://localdata.invalid/media/<resourceId>/thumb`
- `<resourceId>` 必须匹配 `^ldm_[A-Za-z0-9]{8,64}$`；`LocalDataMediaUrl.parse()` 拒绝 query/fragment、路径穿越、
  非本 origin 或非法 variant。数据库只存文件名，不存任意路径。
- `shouldInterceptRequest` 中调用 `session.openMedia(url)`，返回 `LocalMediaResponse(file, mime, length)`；
  其他 URL 返回 `null` 交给 WebView 正常处理。
- 缩略图是缓存，可随时删除并由 `openMedia(thumb)` 重建；**已发布动态引用的原图不会被
  `clearTransientCache()` 删除**。只有 `sweepUnreferencedMedia()` 显式清理无引用资源时才删除原图。

## 3. Dispatcher 方法（v1）

请求：`{"version":1,"method":"moments.list","params":{...}}`；未知根字段/保留身份字段直接失败。
响应：`{"version":1,"ok":true,"data":{...}}` 或
`{"version":1,"ok":false,"error":{"code":"forbidden","message":"...","retryable":false}}`。

| method | 必需能力 | params | data |
|---|---|---|---|
| `localdata.info` | — | — | `{apiVersion,schemaVersion,pluginId,mediaOrigin}` |
| `moments.list` | read | `cursor?,limit?=3,authorId?,authorKind?,type?,dateFrom?,dateTo?,commentPreview?=0,bodyChars?` | `LocalPostPageDto` |
| `moments.get` | read (+media/chat_snapshot 可选) | `postId,commentLimit?=3,commentCursor?,order?=oldest,includeChatSnapshot?=true,includeMedia?=true` | `{post,comments}` |
| `moments.search` | read | `query`（1..100 字）+ list 的过滤参数 | `LocalPostPageDto` |
| `moments.comments.list` | read | `postId,cursor?,limit?=3,order?=oldest` | `LocalCommentPageDto` |
| `moments.comment.create` | comment | `postId,body,replyToId?,clientKey?` | `{comment,commentCount}` |
| `moments.like.set` | like | `postId,liked` | `{postId,liked,likeCount}` |
| `moments.create` | write；助手发布另需 publish | `body?,visibility,selectedAssistantIds?,mediaIds?,chatSnapshot?,clientKey?,draftId?` | `{post}` |
| `moments.delete` | write | `postId` | `{postId,deleted:true}` |
| `moments.draft.save` | write | `draftId?,body?,visibility,selectedAssistantIds?,mediaIds?,chatSnapshot?` | `{draft}` |
| `moments.draft.list` | write | `cursor?,limit?=3` | `LocalDraftPageDto` |
| `moments.draft.delete` | write | `draftId` | `{draftId,deleted:true}` |
| `moments.author.get` | read | `authorKind,authorId` | `{author}` |
| `moments.media.describe` | media | `resourceId` | `{media}` |

媒体**导入/删除**不在 dispatcher 里：JS 不能传路径或 Base64。宿主 picker / 生图结果 / 聊天图片由
`LocalDataSession.importImageFromUri/Bytes` 写入后，把 `resourceId` 传给 `moments.create`。

### 3.1 可见范围（ACL）

`visibility`：`all_assistants` / `selected` / `self`。

- `User`：设备所有者，可读取所有未删除的已发布动态（用于 UI、审核）；草稿只能读自己的。
- `Assistant`：`all_assistants` 全部可见；`selected` 只有 `selectedAssistantIds` 中的助手可见；`self` 只有作者助手可见。
- ACL 同时作用于 list/get/search/comments list/comment create/like/media 元数据/聊天快照；`get` 不通过时返回
  `not_found`，不区分“不存在”和“无权”，避免枚举。
- 删除助手后，动态/评论中的作者昵称头像来自写入时快照，仍可显示；目录中已不存在的助手不能再调用接口。

### 3.2 分页、预算、截断

- 排序固定 `createdAt DESC, id DESC`（草稿为 `updatedAt DESC, id DESC`），cursor 是时间+ID 的 base64url 编码；
  相同时间戳不会重复/漏条。
- 默认每页 3 条，最大 20 条（`LocalDataApi.MAX_PAGE_SIZE`）；搜索/过滤不改变上限。
- list/search 的单条正文默认助手 800 字、用户 4000 字，单页总正文预算 8000 字；超出时截断并置
  `bodyTruncated=true`，页级 `truncated=true`，`budget` 字段给出实际预算。数据库中的完整正文不被改写。
- 助手 `moments.get` 正文最多 4000 字、评论默认 3 条（最大 10）；聊天卡片/媒体若没有对应能力则省略并置
  `chatSnapshotOmitted/mediaOmitted`，不静默伪装成空。
- 请求参数有长度上限；超过 `invalid_request`，不会存进数据库。

### 3.3 草稿与作者快照

- 草稿按作者隔离（用户/助手各自），只出现在 `moments.draft.*`，不会进入动态流或搜索。
- 作者快照写入 `author_snapshots`，每条动态/评论也保存当时的 `author_name/author_avatar`；助手删除不影响历史显示。
- `clientKey`（≤64 字符）用于创建动态/评论的幂等重试：同一作者同一 key 返回原记录，不重复写入。

## 4. 逻辑备份（不含密钥）

`LocalDataBackup` 生成 ZIP：

```
manifest.json   // format=orangechat.localdata.backup, formatVersion=1, schemaVersion, pluginId,
                // createdAt, counts, payloadSha256, payloadBytes, media[{resourceId,sha256,byteSize}]
payload.json    // 结构化动态/评论/点赞/草稿/作者快照/媒体元数据；无任何模型密钥字段
media/<resourceId>.<ext>
```

- 导出前校验所有被引用原图存在，避免“备份成功但恢复缺图”。
- 恢复校验：ZIP entry 名称/数量/解压大小上限、formatVersion/schemaVersion、payload SHA-256、counts、
  每个媒体 SHA-256 与 byteSize；任何一项失败都不写库、不移动已存在数据。
- 恢复默认要求目标库为空（`replaceExisting=false`），或显式 `replaceExisting=true`；`regenerateIds=true` 时
  重建 post/comment/draft/media ID 并同步 ACL、媒体引用、聊天快照内的媒体 ID；`assistantIdMap` 把旧助手 ID
  映射到新助手 ID。备份只包含业务数据，不包含模型 API Key（本模块也不保存 Key）。

## 5. 错误码

`invalid_request`、`unsupported_version`、`unknown_method`、`capability_denied`、`forbidden`、`not_found`、
`conflict`、`payload_too_large`、`unavailable`、`internal`。

错误消息只给稳定、非敏感的说明；不返回 SQL、路径、堆栈或插件目录。

## 6. 宿主接线建议（不属于 task-5 修改范围）

1. 在 `PluginWebViewPage` 的桥接对象中增加 `localData(requestJson)`：先 `isTrustedLocalPluginPage(webView.url,
   pluginDirectory)`，再 `session.call(requestJson, LocalDataActor.User, directory)`；不要接受页面传入的 actor。
2. 聊天/任务侧调用 `session.call(..., LocalDataActor.Assistant(id, name, avatar), directory, autoPublish)`；
   `autoPublish` 来自宿主的朋友圈设置，不从插件参数读取。
3. `WebViewClient.shouldInterceptRequest` 对 `https://localdata.invalid/...` 调用 `session.openMedia(url)`，
   返回 `WebResourceResponse(mime, "UTF-8", file.inputStream())`；找不到返回 404。
4. picker 结果交给 `session.importImageFromUri(uri)`，生图结果交给 `importImageBytes(...)`；JS 只拿
   `resourceId`，拿不到文件路径或 Base64。
5. 备份入口调用 `session.exportBackup(...)` / `restoreBackup(...)`；恢复时由宿主提供助手 ID 映射。
6. 清缓存按钮只调用 `session.clearTransientCache()`；不要删除 `media/originals`。

## 7. 明确不做

- 不做 WebView、ChatService、主数据库、依赖、插件 ZIP、朋友圈 UI、主题、助手模型调用、互评调度的修改。
- 不把整份朋友圈存成单个大 JSON，不把原图 Base64 持久化，不接受任意 SQL/路径。
- 不承诺跨用户/云端同步。
