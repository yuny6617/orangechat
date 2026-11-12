# task-5 完成报告：localdata 独立存储与媒体仓库

日期：2026-11-11  
范围：`app/src/main/java/me/rerere/rikkahub/plugin/localdata/**` 与
`app/src/test/java/me/rerere/rikkahub/plugin/localdata/**`。未修改 WebView、ChatService、主数据库、
`app/build.gradle.kts`、依赖、插件 ZIP、主题或 UI，也未执行 Gradle、git commit/reset。

## 1. 完成度

| 项 | 状态 |
|---|---|
| 独立 SQLite store（每插件 namespace、schema v1、版本化迁移入口） | 代码已写 |
| 媒体仓库（持久原图、按需缩略图、受控 resourceId） | 代码已写 |
| 版本化 DTO / `method+params -> ok/data/error` dispatcher | 代码已写 |
| ALL_ASSISTANTS / SELECTED / SELF ACL（list/get/search/comment/like/media/chat snapshot） | 代码已写 |
| 时间+ID 游标分页（默认 3，最大 20）、正文预算与截断标识 | 代码已写 |
| 草稿、作者历史快照、clientKey 幂等 | 代码已写 |
| 版本化逻辑备份/恢复、SHA-256 完整性、ID 映射、不含 key | 代码已写 |
| 单元测试 | **40 项通过（Robolectric，JVM）** |
| 宿主 WebView/ChatService/助手指令接线 | **未接，留给 lead** |
| 真机、APK、朋友圈 ZIP | **未做** |

“代码已写 + 编译通过 + 40 项单元测试通过”不等于“已接线”或“真机已验证”。本报告不把未接线的能力写成已交付。

## 2. 文件

生产代码（包 `me.rerere.rikkahub.plugin.localdata`）：

| 文件 | 作用 |
|---|---|
| `LOCALDATA_API.md` | 小契约：真实类、dispatcher 方法、ACL、媒体 URL、备份格式、接线建议 |
| `LocalDataContract.kt` | 版本、错误码、actor/grant、请求/响应、DTO、cursor、聊天快照限制、媒体 URL |
| `LocalDataDatabase.kt` | 独立 `SQLiteOpenHelper`、schema v1、每插件哈希路径、事务、迁移入口 |
| `LocalDataStore.kt` | posts/comments/likes/drafts 写入、草稿发布、删除、导出、恢复写入 |
| `LocalDataReadStore.kt` | 统一 ACL 的 list/get/search/comments、游标分页、预算、作者快照、引用统计 |
| `LocalDataMediaStore.kt` | 原图/缩略图、SHA-256 去重、受控 URL、缓存清理、无引用 sweep |
| `LocalDataDispatcher.kt` | typed dispatcher、能力检查、可信 actor、参数上限、错误映射 |
| `LocalDataSession.kt` | 宿主入口：call、picker 导入、媒体 URL、缓存、备份恢复 |
| `LocalDataBackup.kt` | 逻辑 ZIP、manifest、完整性、恢复校验与 ID 重映射 |

测试：

| 文件 | 覆盖 |
|---|---|
| `LocalDataAclTest.kt` | 三种可见范围、全链路 ACL、删除助手快照、能力缺省省略媒体/聊天卡片 |
| `LocalDataPaginationTest.kt` | 默认 3、游标不重不漏、同时间戳 ID tie-break、正文/总预算截断、非法游标/limit |
| `LocalDataDraftTest.kt` | 草稿作者隔离、发布、删除、幂等、草稿媒体引用 |
| `LocalDataIdentityTest.kt` | JS 不能传身份、助手目录校验、能力门、发布门、版本/方法/参数错误 |
| `LocalDataMediaTest.kt` | 原图持久、缩略图重建、去重、缓存清理、sweep、路径与 URL 拒绝、类型/大小限制 |
| `LocalDataBackupTest.kt` | 导出结构、无 key、ID 重生成、助手 ID 映射、损坏/版本/路径穿越拒绝、插件归属 |

## 3. 实际存储

- 数据库：`context.getDatabasePath("localdata_v1_<sha256(pluginId).take(24)>.db")`，每个 pluginId 独立文件。
- 媒体：`filesDir/plugin_localdata/<sha256(pluginId).take(32)>/media/{originals,thumbnails,tmp}`。
- schema v1 表：`posts / post_acl / post_media / comments / likes / drafts / draft_acl / draft_media /
  author_snapshots / media_assets / meta`。
- 不使用主数据库、不共享 Room、不执行任意 SQL；所有 SQL 在本包内固定。
- `posts.body` 上限 20,000 字；评论 2,000；聊天快照 JSON 32 KB；列表响应按预算截断。没有 Base64 入库。

## 4. 身份与 ACL

- `LocalDataActor` 由宿主构造；`LocalDataCallContext` 携带 actor、capability、助手目录、自动发布开关、用户显示名。
- parser/dispatcher 拒绝参数中的 `actorId/actor_id/viewerId/assistantId/...`；写方法额外拒绝
  `authorId/authorName/authorKind/authorAvatar`。用户显示名来自宿主 context，不来自 JS。
- 助手 actor 必须存在于宿主 `LocalAssistantDirectory`；否则 `forbidden`。
- `ALL_ASSISTANTS`：所有助手可见/可评论/可点赞/可读媒体与聊天卡片。
- `SELECTED`：仅 `post_acl` 中助手；`SELF`：仅作者助手。
- 本地用户是设备所有者：可读所有未删除已发布动态；助手 `SELF` 对用户可见（审核/UI 需要），
  助手侧仍严格受限。若产品要求“助手 SELF 对用户也不可见”，改 `LocalDataReadStore.viewerPredicate` 即可。
- 删除助手不删除历史：动态/评论保存写时作者昵称/头像，`author_snapshots` 另存。
- 媒体没有独立 ACL 表：`canAccessMedia` 要求至少一个当前 viewer 可见的动态/草稿仍引用该 resourceId，
  防止拿到/猜到 ID 后越权读取。

## 5. 分页、预算、截断

- 排序固定 `created_at DESC, id DESC`；评论/草稿分别为 `created_at ASC, id ASC` /
  `updated_at DESC, id DESC`。
- cursor 是 `timestamp:id` 的 URL-safe Base64；非法 cursor 返回 `invalid_request`。
- 默认每页 3，最大 20；超过返回 `invalid_request`，不静默截 limit。
- 列表单条正文：助手默认 800 字、用户默认 4000 字；单页总正文预算 8000 字。
- 超预算：item `bodyTruncated=true`，page `truncated=true`，`budget.bodyCharsUsed` 给出实际用量；
  数据库完整正文不变。`get` 最多 4000 字并同样标记。
- 列表不返回聊天卡片全文；`get` 才返回，且受 `localdata.chat_snapshot` 能力与 ACL 约束。

## 6. 媒体生命周期

- 导入：宿主 picker/聊天图片/生图结果 → `LocalDataSession.importImageFromUri/Bytes` → 校验类型/尺寸/字节数 →
  SHA-256 去重 → 原子写原图 → 按需生成 512px JPEG 缩略图。
- 插件只拿 `resourceId` / `https://localdata.invalid/media/<id>/thumb|original`；不拿路径。
- `clearTransientCache()` 只删 `thumbnails/` 与 `tmp/`，**不删 `originals/`**；缩略图可重建。
- `sweepUnreferencedMedia()` 才删除无引用原图；引用来自动态、草稿、聊天卡片中的媒体 ID。
- 备份只打包已存在原图；缺失的被引用原图会让导出失败，避免“备份成功但缺图”。

## 7. 备份/恢复

ZIP 内容：

```
manifest.json
payload.json
media/<ldm_xxx>.<ext>
```

- manifest：format、formatVersion、schemaVersion、pluginId、createdAt、counts、payload SHA-256/bytes、
  每个媒体的 SHA-256/bytes。
- payload：authors/posts/comments/likes/drafts/media 的纯逻辑记录；schema 中没有任何 API Key 字段。
- 恢复校验：entry 数/重名/大小上限、format/version/schema、pluginId、payload hash/size、counts、
  每条媒体 hash/size、ID/引用/ACL/聊天快照媒体一致性。
- 默认要求目标库为空；`replaceExisting=true` 才覆盖。`regenerateIds=true` 重生成 post/comment/draft/media ID，
  同步 ACL、媒体引用、聊天快照媒体 ID；`assistantIdMap` 处理助手 ID 映射。
- 失败不写库；已移动的新媒体文件会删除。替换恢复会留下旧媒体孤儿文件，之后由 sweep 清理。

## 8. 验证证据

按用户要求未运行 Gradle；用独立 `kotlinc` 2.3.21 + JUnit 4.13.2 + Robolectric 4.16.1 在 JVM 上验证。

- 主源码编译：本包 8 个生产 Kotlin 文件，`K2JVMCompiler -jvm-target 17`，exit 0。
- 测试源码编译：同 classpath（JUnit/Robolectric/androidx.test core）exit 0。
- 运行：`LocalDataMediaTest` 7、`LocalDataAclTest` 7、`LocalDataPaginationTest` 5、
  `LocalDataDraftTest` 4、`LocalDataIdentityTest` 10、`LocalDataBackupTest` 7，共 **40 项，0 失败**。
- 说明：手工 JUnit 运行时 Windows 临时目录过长会让 Robolectric 的 SQLite 打不开，使用短
  `-Djava.io.tmpdir` 后通过；这是测试启动方式问题，不是应用路径问题。
  **2026-11-11 更正：Gradle 下同样需要短 `-Djava.io.tmpdir`**；统一 Gradle 的临时目录同样会让
  localdata 的长测试方法名 + 48 字符数据库名超过 native SQLite 的 Windows 路径上限（27 fail 的根因）。
  证据和 init script 修复见 `LOCALDATA_TEST_HOST_DIAGNOSIS.md`。不要用 `robolectric.sqliteMode=LEGACY`。
- 未做：真机、Android 10+ scoped storage、真实图片解码/EXIF/HEIC、进程重启、并发发布、WebView 真链路。

建议 lead 统一跑：

```
.\gradlew.bat :app:testDebugUnitTest --tests 'me.rerere.rikkahub.plugin.localdata.*' --no-daemon --console=plain
```

## 9. 接线代码建议（未修改这些文件）

### 9.1 能力声明

`plugins/local-moments/manifest.json` 增加声明（manifest 只是声明，宿主仍需授权）：

```json
"permissions": [
  "localdata.read",
  "localdata.write",
  "localdata.publish",
  "localdata.comment",
  "localdata.like",
  "localdata.media",
  "localdata.chat_snapshot",
  "localdata.backup"
]
```

宿主构造 session 时用 `LocalDataGrant.fromManifest(manifest.permissions).restrictedTo(hostApproved)`；
不要只信 manifest。

### 9.2 WebView 用户侧（`PluginWebViewPage.kt`）

每个插件页面创建一个 session，并在插件的 `WebView` 上挂一个**独立** JS 接口。现有 `bridge://` 是 URL query
传参，不适合大 JSON；建议新接口：

```kotlin
private class OrangeLocalDataBridge(
    private val session: LocalDataSession,
    private val pluginDirectory: File,
    private val currentPageUrl: () -> String?,
) {
    @JavascriptInterface
    fun call(requestJson: String): String {
        // 只允许插件自己的本地页面；不要从 requestJson 读 actor。
        if (!isTrustedLocalPluginPage(currentPageUrl(), pluginDirectory)) {
            return """{"version":1,"ok":false,"error":{"code":"forbidden","message":"Untrusted page"}}"""
        }
        return session.call(
            requestJson = requestJson,
            actor = LocalDataActor.User,
            assistantDirectory = hostAssistantDirectory,
            userDisplayName = hostUserDisplayName,
        )
    }
}
```

在 `WebView` 的 `factory`/`update` 中：

```kotlin
webView.addJavascriptInterface(
    OrangeLocalDataBridge(session, pluginInfo.directory) { trustedPageUrl },
    "OrangeLocalData",
)
```

`trustedPageUrl` 在 `onPageStarted/onPageFinished` 记录；或把 `WebView` 的 `url` 通过 `webView.post` 同步到
`@Volatile`。JS 只拿 JSON 字符串，不拿数据库、路径或 key。页面侧：

```js
const result = JSON.parse(await OrangeLocalData.call(JSON.stringify({
  version: 1,
  method: "moments.list",
  params: { limit: 3, cursor: null }
})));
```

### 9.3 媒体显示（`shouldInterceptRequest`）

```kotlin
override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
    val pageUrl = view.url ?: return null
    if (!isTrustedLocalPluginPage(pageUrl, pluginInfo.directory)) return null
    val media = session.openMedia(request.url?.toString() ?: return null) ?: return null
    return WebResourceResponse(media.mime, null, media.length.toInt(), "OK", emptyMap(), media.file.inputStream())
}
```

只接受 `https://localdata.invalid/media/<resourceId>/thumb|original`；其他 URL 返回 null。

### 9.4 Picker / 生图结果

- 系统图片选择器：现有 `pickImage` 回调拿到 `Uri` 后调用
  `session.importImageFromUri(uri)`，把返回的 `LocalImportedMedia.media.resourceId` 回给 JS。
- 生图插件结果：现有 `PluginImageResults` 已把 `_orange_image` 校验并落成 `UIMessagePart.Image` 文件；
  在发布前用 `session.importImageBytes(file.readBytes(), mime)` 复制为持久资源，禁止把插件 Base64 槽位当永久存储。
- JS 发布时只传 `mediaIds`；`moments.create` 校验资源存在。

### 9.5 助手侧（ChatService/task，不在本 task 修改）

在宿主任务上下文中构造：

```kotlin
val actor = LocalDataActor.Assistant(
    id = assistant.id.toString(),
    name = assistant.name,
    avatar = assistantAvatarUrl(assistant),
)
val response = session.call(
    requestJson = buildMomentToolRequestJson(method, params),
    actor = actor,
    assistantDirectory = directoryFromSettings,
    assistantAutoPublish = momentsSettings.autoPublishFor(assistant.id),
)
```

- 模型 tool 参数只能填 `moments.*` 的 params；actor 不能由模型/页面提供。
- 助手没有 `localdata.publish` 或宿主 `assistantAutoPublish=false` 时，`moments.create` 返回
  `capability_denied`；只创建草稿请走 `moments.draft.save`。
- 互评调度、模型调用、预算/轮数由 lead 另接；本包只提供存储边界。

### 9.6 备份/清缓存

```kotlin
val file = File(backupDir, "moments-${System.currentTimeMillis()}.zip")
session.exportBackup(file)
// 恢复时先确认 pluginId 一致；需要把旧助手 ID 映射到新助手：
session.restoreBackup(file, LocalRestoreOptions(assistantIdMap = idMap))
session.clearTransientCache() // 清缓存按钮只能调这个
```

## 10. 已知限制/风险

1. **未接线**：WebView bridge、picker、ChatService、tools、备份入口、UI、ZIP 都未改；朋友圈仍不可用。
2. **用户可见性**：用户作为设备所有者能看助手 SELF 动态；如产品要求严格私密，需改 `viewerPredicate` 并补测试。
3. **搜索范围**：第一版搜索动态正文与作者名，不搜评论；FTS 未接。
4. **备份完整性**：SHA-256 只检测损坏/篡改，不是签名或加密；恶意构造者可以同时改 manifest 和文件。
5. **恢复替换**：`replaceExisting=true` 不删除旧媒体文件，可能留下孤儿；恢复后调用 `sweepUnreferencedMedia()`。
6. **sweep 并发**：`sweepUnreferencedMedia` 需要宿主在空闲时调用；与发布/草稿保存并发时可能误删刚导入但尚未引用的媒体。
7. **真实图片**：测试用注入的 inspector/thumbnailer，真实 `BitmapFactory` 路径尚未在设备验证；HEIC/SVG 被拒。
8. **性能**：`referencedMediaIds` 会解析所有聊天卡片 JSON；数据量很大时需要改为引用表/索引。
9. **未做 Android 进程重启/并发/多线程压力测试**。

## 11. 回退

本 task 只新增文件，不改既有文件；如集成失败，删除本包和测试目录即可，不影响主数据库、WebView、ChatService
或已有插件。已发布数据只存在于本包命名空间，不与主库交叉。
