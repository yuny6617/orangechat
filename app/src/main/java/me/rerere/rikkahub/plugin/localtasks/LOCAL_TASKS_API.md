# localtasks 小契约（宿主助手任务）

本目录实现**宿主真实助手任务服务**，供朋友圈 UI / 工具 / 插件桥接接线。
它使用 `plugin.localdata` 的 session/ACL 读写动态，但**不修改** localdata、WebView、ChatService、主题或视频代码。

- 包名：`me.rerere.rikkahub.plugin.localtasks`
- API 版本：`LocalTasksApi.VERSION = 1`
- 设置/账本 schema：`SETTINGS_VERSION = 1`、`LEDGER_VERSION = 1`
- 持久化：`SharedPreferences`（键名前缀 `localtasks_v1_<sha256(pluginId)[0,16]>`），不是插件 JS 可写存储。
- 任务执行：串行、单轮有界、逐轮计费、可取消、重启不重放。

## 1. 信任边界（JS 不能自证）

| 事实 | 来源 |
|---|---|
| 参与助手、发布策略、预算、输出长度、间隔、审核、全局停止 | `LocalTaskSettingsStore`（原生 SharedPreferences，只有宿主设置页能写） |
| 模型 | 该助手真实 `chatModelId`（为空时用全局 `chatModelId`）→ `Settings.findModelById` → `Model.findProvider` |
| Provider / 计费 | `ProviderManager.getProviderByType` + `UsageRecorder.wrap(..., UsageTag(UsageSource.PLUGIN, assistantId))` |
| 身份 | 宿主用真实 `Assistant` 构造 `LocalDataActor.Assistant`，`params` 里不接受 actorId |
| ACL | `LocalDataSession.call(request, actor, hostDirectory, assistantAutoPublish)`，与页面同一套 localdata ACL |
| 权限 | manifest 只声明；`LocalDataGrant` 由宿主批准。任务不能扩大 grant |

`task.start` 的 JS 参数只允许 `postId`、`assistantId`、`clientKey`：
`assistantId` 只能**收窄**到原生 `participants` 中已有的助手，不能新增助手；
`mediaIds`、`publish`、`permissions`、`maxRounds` 等一律 `invalid_request`。
设置接口对 JS 只读（`task.settings`），没有 `task.settings.update`。

## 2. 真实类与构造

| 类 | 职责 |
|---|---|
| `LocalTaskService` | 宿主唯一入口：启动/查询/取消/设置/审核/图片导入 |
| `LocalTaskHosts` | 进程内每 plugin 一个 host，避免页面关闭取消任务或两个调度器竞争 |
| `LocalTaskBridge` | 把 `localDataTask(requestJson)` 转成 `LocalTaskService` 调用 |
| `LocalTaskScheduler` | 纯串行队列：轮数、回复上限、每 post/每日预算、间隔、取消、停止、重启中断 |
| `LocalTaskLedger` | 纯预算/幂等账本，持久化到 KV |
| `LocalTaskImageBudget` / `LedgerLocalTaskImageBudget` | 助手生图的预算 seam：与文本 turn 共用每任务/每日账本；无 host 时默认不可花费 |
| `LocalTaskExecutor` | 真实模型调用 + localdata 写入（唯一写入口） |
| `LocalTaskContextLoader` | 只构造当前 post + 最近评论的有界上下文 |
| `LocalTaskLocalData` | 带宿主 actor/directory 的 localdata 调用与 DTO 解码 |
| `LocalTaskPrompts` / `LocalTaskActionParser` | 严格 JSON 动作协议，无 eval |
| `LocalTaskSettingsStore` / `LocalTaskSettingsCodec` | 原生设置读写、版本化、越界收窄 |
| `LocalTaskImageSource` / `PluginImageToolResultSource` | 只导入**已存在**的 `_orange_image` 工具产物 |

### 2.1 直接构造

```kotlin
class LocalTaskService(
    context: Context,
    val pluginId: String,
    private val session: LocalDataSession,       // 必须是本 plugin 的 session
    private val settingsStore: SettingsStore,
    providerManager: ProviderManager,
    usageRecorder: UsageRecorder,
    parentScope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    imageSource: LocalTaskImageSource = LocalTaskImageSource.unsupported(),
    ownsSession: Boolean = false,
) : Closeable
```

推荐工厂（进程单例、自动建 session）：

```kotlin
LocalTaskHosts.obtain(
    context, pluginId, grant, settingsStore, providerManager, usageRecorder, parentScope,
    clock = System::currentTimeMillis,
    imageSourceFactory = { session ->
        PluginImageToolResultSource(context, filesManager, session)
    },
) // 同一 plugin 重复调用返回同一 host；grant 能力变化时自动重建
LocalTaskHosts.peek(pluginId)
LocalTaskHosts.release(pluginId) // 关闭并标记未完成任务为 interrupted
```

### 2.2 Service API

```kotlin
fun startInteraction(postId: String, assistantId: String? = null, clientKey: String? = null): LocalTaskSubmitResult
fun startPublish(assistantId: String, mediaIds: List<String> = emptyList()): LocalTaskSubmitResult // 仅原生
fun status(taskId: String): LocalTaskStatusDto?
fun statusesForPost(postId: String): List<LocalTaskStatusDto>
fun activeStatuses(): List<LocalTaskStatusDto>
fun cancel(taskId: String): Boolean
fun stopAll(): Int
fun settings(): LocalTaskSettings
fun updateSettings(settings: LocalTaskSettings): LocalTaskSettings // 仅原生设置页
fun pendingReviews(): List<LocalTaskPendingActionDto>
suspend fun resolveReview(reviewId: String, approve: Boolean, editedBody: String? = null): LocalTaskReviewResolveDto
suspend fun importExistingImageResult(producerPluginId: String, toolResultJson: String): LocalTaskImageImport
```

`LocalTaskSubmitResult` 为 `Accepted(taskId, existing)` 或 `Rejected(code, message)`。
`startInteraction` 对同一 post **且同一 assistantId 收窄范围**的活跃任务幂等（`assistantId=null`
表示全部参与者；它与一个收窄到 A 的任务不互相幂等）；相同 `clientKey` 也幂等。
`updateSettings` 在 `enabled=false` / `globalStop=true` / `maxRounds<=0` 时会 `stopAll()`。

## 3. 设置（原生持久，JS 只读）

`LocalTaskSettings` 默认值（全部会被 `normalized()` 收窄）：

| 字段 | 默认 | 范围 |
|---|---|---|
| `enabled` | `false` | 总开关 |
| `globalStop` | `false` | 持久全局停止 |
| `commentUserPosts` / `commentAssistantPosts` | `true` | 评论用户/助手动态 |
| `autoLike` | `true` | 自动点赞 |
| `maxRounds` | `2` | 0..10；**0 = 关闭自动互评** |
| `maxRepliesPerAssistantPerPost` | `1` | 0..5 |
| `maxModelCallsPerPost` | `8` | 1..50 |
| `dailyModelCallBudget` | `200` | 0..10000（UTC 日切） |
| `maxOutputChars` | `280` | 40..2000（受 localdata 评论上限约束） |
| `intervalMs` | `45000` | 0..3600000 |
| `requireReview` | `false` | 需要用户审核 |
| `allowImages` | `false` | 允许发图（仅原生设置） |
| `allowGeneratedImages` | `false` | 允许助手按需生成 **1 张**图（必须同时 `allowImages=true`、`localdata.media` 已授权且配置了生图模型；默认关闭，原生 UI 尚未接开关） |
| `participants` | `[]` | 最多 50 个真实 assistantId |
| `assistantPolicies` | `{}` | `disabled` / `draft_only` / `auto_publish` |
| `assistantReplyLimits` | `{}` | 每助手覆盖回复上限 |
| `contextCommentLimit` | `3` | 0..10（默认最新 3 条） |
| `contextBodyChars` | `800` | 0..4000 |
| `feedLimit` | `3` | 1..5（发布任务参考的最新动态数） |
| `commentScanPages` | `2` | 1..4（见 §7 评论读取限制） |

策略语义：`disabled` 不参与；`draft_only` 可互动但新动态只能存草稿；`auto_publish` 在
`localdata.publish` 已授权时可直接发布。未配置策略的参与者默认 `draft_only`。
**任何策略都不会绕过 localdata 的 grant**：缺少 `localdata.publish` 或
`assistantAutoPublish=false` 时 `moments.create` 会被拒，任务改为保存草稿并标记
`publish_not_granted`，不会假装发布成功。

## 4. 调度语义

- 串行：`LocalTaskScheduler.pump()` 一次只跑一个任务；Service 用单个 signal 协程持续 drain。
- 一轮 = 每个参与助手最多一次发言机会（可 `skip`）。
- 结束条件：达到轮数、`spoken==0`（全员不回复）、预算耗尽、用户 `cancel`/`stopAll`、
  动态不可见/被删、模型或写入失败、要求审核（转 `waiting_review`）。
- 失败不无限重试：一次 turn 失败即 `failed`，不重复调用模型。
- 预算：每轮在调用模型**之前** `tryChargeModelCall`，同时检查每 post + 每日上限；
  预算耗尽后任务以 `budget_exhausted` 结束，不会继续调用。若 runner 在调用模型前就因为
  策略（自己的动态、评论开关关闭）决定不调用，会 `refundModelCall` 退回该预留，
  `modelCalls` 只统计真正的模型调用。互动任务的 usage key 是 `postId`，所以不同助手、
  all-participants 任务与收窄任务共用同一份每 post 预算；发布任务没有 postId，统一用
  `LocalTasksApi.publishUsageKey(clock())`（按 UTC 日切、跨任务/跨助手共享，不是 taskId），
  新任务或换助手不会重置上限，隔日才重新计数。
- 间隔：同一 post 的相邻 turn 至少间隔 `intervalMs`（注入 `sleeper`，测试可立即推进）。
- 取消与写入的线性化：`LocalTaskCancellationToken.cancel()` 和
  `LocalTaskCancellationToken.commitIfActive { … }` 使用同一把 gate 锁。Executor 的每一次
  localdata 写入（评论、点赞、草稿、发帖、审核通过后的写入）都必须在该块内完成，且块内只能有
  **同步** 写入，不能包含模型/网络调用。于是只有两种结果：取消先拿到锁 → token 已标记、
  写入块根本不执行；写入先拿到锁 → 取消等待该块完成、已提交的结果保留（调度器用
  `committed` 区分；executor 还会把已提交结果记到 token 上，即使 job 取消恰好落在“写入完成、
  turn 还没返回”的窗口里，也不会漏记这次结果）。这是协作式取消，不是 socket 硬中断。
- 取消：`cancel()` 在 token 锁内先 `token.cancel()`（等待在途同步写入）再把任务置
  `cancelled` 并清空待审核动作；`stopAll()`/`updateSettings` 中与权限相关的变更（参与助手、
  发布策略、评论/点赞开关、审核、图片权限、回复上限）都会停止在跑任务并丢弃其待审核动作。
  `LocalTaskService.close()` 先 `cancellation.close()`（拒绝所有 token 的新写入并等待在途
  写入结束）再取消 Job、标 `interrupted`、关闭 session，因此新 host 不会与旧在飞写并发。
- 审核：审核通过前由 `LocalTaskScheduler.beginReview(taskId)` 在取消锁内租一个写 token；任务
  已取消/不再 `waiting_review` 时返回 `null`，不写库。执行审核写入前还会重新读取助手与设置
  （助手是否仍存在且仍是启用参与者、发布策略、`autoLike`、`allowImages`、`globalStop`），
  过期的审核动作不会按旧授权落地。
- 重启：`LocalTaskLedger.markInterruptedOnStartup()` 把 `queued`/`running` 改成
  `interrupted`，新进程队列为空，**不重放**。
- 幂等：动态/评论写入使用确定性 `clientKey`（`localdata` 对同作者同 key 返回原记录），
  所以“写入成功但账本未记”的重试不会产生第二条评论。

## 5. 上下文与动作协议（严格 JSON，无 eval）

上下文只包含：当前动态（`bodyChars` 截断）+ 最新窗口内的评论（默认展示 3 条）。
`includeMedia=true` 只取元数据（不读原图字节），`includeChatSnapshot=false`。
不读取对话历史、记忆、工具结果、其他动态。发布任务只读最新 `feedLimit` 条动态摘要。

模型必须输出**单个 JSON 对象**（允许一层 ``` 围栏）：

```json
{"action":"reply","body":"评论正文","like":true,"replyToId":"cmt_..."}
{"action":"skip","reason":"不回复的原因"}
```

发布任务：`{"action":"publish","body":"..."}` 或 `{"action":"draft","body":"..."}` 或 `skip`。
当本任务同时满足 `allowImages`、`allowGeneratedImages`、`localdata.media` 授权和已配置生图模型时，
publish/draft 还可带一个受控的 `image`：

```json
{"action":"publish","body":"...","image":{"source":"generate_image","prompt":"画面描述"}}
```

`image` 只允许 `source` 与 `prompt` 两个字段；`source` 只能是 `generate_image`，`prompt` 必须是
非空、≤ `MAX_IMAGE_PROMPT_CHARS` 的字符串。任何 `path`/`uri`/`url`/`mediaId`/`fileName`/`mediaIds`
等字段都会因未知字段被判 `invalid_action`；宿主不会把模型提供的字符串当路径/URI 使用。`skip`
不接受 `image`，互动任务也不接受。

解析规则（`LocalTaskActionParser`）：
- 未知字段 → `invalid_action`（严格 schema）；
- `body` 空 → `invalid_action`；超过 `maxOutputChars` → 截断并置 `truncated=true`；
- `replyToId` 不在本次上下文提供的评论 id 里 → 丢弃（退回评论动态本身）；
- 控制字符移除；不做任何表达式/脚本求值；
- 动作与任务模式不匹配（如在互动任务里 `publish`）→ `invalid_action`；
- 未开启生图的任务带 `image` → `invalid_action`，不会退回“悄悄只发文字”。

## 6. 桥接协议（`localDataTask(requestJson)`）

请求：`{"version":1,"method":"...","params":{...}}`
响应：`{"version":1,"ok":true,"data":...}` 或 `{"version":1,"ok":false,"error":{"code","message"}}`

| method | params | data |
|---|---|---|
| `task.start` | `postId` 必填；`assistantId?`、`clientKey?` | `{taskId,existing,state}` |
| `task.status` | `taskId?` / `postId?`；都为空返回活跃任务 | 单个或数组 `LocalTaskStatusDto` |
| `task.cancel` | `taskId` 或 `all=true` | `{cancelled:Int}` |
| `task.settings` | —（只读） | `LocalTaskSettings` |
| `task.review.list` | — | `{items:[LocalTaskPendingActionDto]}` |
| `task.review.resolve` | `reviewId`,`approve`,`body?` | `{reviewId,applied,approved,state,detail?}` |

根字段只允许 `version/method/params`；未知 method/参数 → `unknown_method`/`invalid_request`。
`task.settings` 带任何参数都会被拒，**没有**修改设置的方法。

`task.review.resolve` 的**原生确认不在本桥内**，而在 WebView 层：`PluginWebViewPage` 先弹出
Compose 确认框，只有用户确认后才调用 `LocalTaskBridge.dispatch`；拒绝、页面失效或并发审核
都会返回 `capability_denied`。直接调用 `LocalTaskBridge.dispatch` 或
`LocalDataWebBridge.taskCall` 的原生代码不经过该确认，**不要把它当成桥级安全边界**。
桥在分发前会拒绝空白/超长/非字符串的 `body` 与非布尔 `approve`，避免确认框显示的内容和
实际写入的内容不一致。

状态线值：`queued`、`running`、`waiting_review`、`completed`、`cancelled`、`failed`、`interrupted`。

## 7. 已知限制（不要当成已解决）

1. **最新评论**：宿主已增加 `order=newest` 的稳定降序游标；上下文直接从当前动态读取最新有界评论页（默认 3 条），再按时间升序呈现给模型。较早省略内容标记 `commentsTruncated=true`，不会前向扫描整串评论。`commentScanPages` 仅保留旧设置兼容。
2. **单轮、非流式**：一个 turn = 最多一次 `generateText`，没有工具调用循环，因此不会无限循环。
   若需工具调用，可后续复用 `me.rerere.ai.core.Tool` 或 `ToolSurfaceBuilder`；本目录只在
   动作 JSON 这一层做严格解析。
3. **预算单位是“turn 预留”**：调用模型前先预留；runner 在调用前因策略决定不调用时退回预留，
   所以 `modelCalls` 是真实调用数。但如果是在读取动态阶段失败（例如助手无权查看该动态），
   仍会消耗一个预留单位，防止异常路径被用来刷模型调用。
4. **审核**：`requireReview=true` 时只生成 `waiting_review` 和 pending action，不写库；
   必须由原生/用户调用 `task.review.resolve` 才写入。没有自动批准。
5. **图片**：本目录现在有两条路径，但都**未在生产设置页暴露**：
   - 用户选图：原生 `startPublish(mediaIds)` 仍只接受系统选择器导入的 media id；`task.start`
     不接受任何 mediaId。
   - 助手生图：publish 动作可带受控 `image` 字段；executor 用 `settings.imageGenerationModelId`
     对应的 provider 生成 **1 张**图，直接 `session.importImageBytes` 写入 localdata，再随草稿/发帖
     使用。该调用先经 `LocalTaskImageBudget` 占用发布任务的每任务/每日预算，失败只退未真正发起
     的预留；不读取任何来源文件名，不把 base64 当常规持久存储。
   - `PluginImageToolResultSource` 仍只读取已经存在的 `_orange_image` 产物，**不会触发生图**；
     它的生产调用仍为零（见 §10）。
   - **`allowGeneratedImages` 默认 false，且 `PluginWebViewPage` 还没有开关**：因此当前生产行为
     仍是“助手不会自动生图”，与本轮代码路径不矛盾。没有原生审核预览前不要打开该开关。
   - v1 **不把原图字节或聊天图片附进模型请求**：`LocalTaskContextLoader` 只报告 `mediaCount`，
     `includeChatSnapshot=false`；真实模型看不到动态配图像素。需要看图应由宿主另行授权并显式接图。
   - 生图由 provider 返回 base64，`UsageRecorder` 目前只记录文本请求，因此这次图片调用只有
     localtasks 账本预算，没有 token usage 行；不要把它写成“已计入 UsageRecorder 的 token 统计”。
6. **SQLite 会话**：`LocalTaskHosts` 会自己持有一个 `LocalDataSession`；如果页面桥接同时
   持有另一个 session，写操作由 SQLite 自身串行化。本目录自己的 host 在 `close()` 时用
   `LocalTaskCancellationGate` 先拒绝并等待在途写入，再关闭 session；页面桥自身的 session
   生命周期仍归 WebView 层，未做并发压力验证。若页面已有 session，可直接用
   `LocalTaskService(...)` 构造并传入同一个 session。
7. **编译/测试状态**：本轮只改 `plugin/localtasks/**`、对应 test 与本文件，**未运行 Gradle**
   （任务禁止；R8 由 lead 构建）。历史轮次曾用 `kotlin-compiler-embeddable` 单独编译本目录并
   跑通纯 JVM 测试；**本轮新增的 `LocalTaskImageBudget`、`allowGeneratedImages`、`image` 动作
   解析与 executor 生图分支尚未经过任何编译**，需要 lead 回测。不能替代 Gradle 的最终编译与
   `:app:testDebugUnitTest --tests '*LocalTask*'`；Service/Executor 的真实 Provider、ACL、
   图片字节与真机审核写入路径仍没有 Android/Robolectric 单测，需人工验收。
   未在真机验证通知/后台限制；本服务不启动前台服务，进程被杀后任务不会自动续跑。

## 8. 接线建议（lead / plugin-bridge）

```kotlin
// 1) 在插件页面可信上下文里拿 host（先确认 localData 已获授权）
val host = LocalTaskHosts.obtain(
    context = context.applicationContext,
    pluginId = pluginInfo.manifest.id,
    grant = LocalDataGrant.fromManifest(localDataBridge.grantedCapabilities()),
    settingsStore = settingsStore,
    providerManager = KoinJavaComponent.get(ProviderManager::class.java),
    usageRecorder = usageRecorder,
    parentScope = appScope,
    imageSourceFactory = { session ->
        PluginImageToolResultSource(context, filesManager, session)
    },
)
val bridge = LocalTaskBridge(host)

// 2) 在 WebView 的 bridge:// 分发里加一个方法（页面仍受 isTrustedLocalPluginPage 校验）
"localDataTask" -> scope.launch(Dispatchers.IO) {
    reply(bridge.dispatch(params["requestJson"].orEmpty()))
}

// 3) JS 侧
// Orange.localDataTask({version:1, method:'task.start', params:{postId}})
// Orange.localDataTask({version:1, method:'task.status', params:{postId}})
// Orange.localDataTask({version:1, method:'task.cancel', params:{taskId}})
```

设置页（原生 Compose）只需要：
`host.settings()` 读取，`host.updateSettings(newSettings)` 保存；
助手列表从 `SettingsStore.settingsFlow.value.assistants` 取，**不要让 JS 传**。
审核 UI：`host.pendingReviews()` + `host.resolveReview(id, approve, editedBody)`。
停用插件/退出：`host.stopAll()`；彻底释放：`LocalTaskHosts.release(pluginId)`。

带图发布的宿主流程（不触发生图；**当前生产 `LocalDataWebBridge.tasks()` 尚未注入 factory，
以下流程未接线，见 §10**）：

```kotlin
// toolResultJson 必须是已执行工具返回的 _orange_image 结果（宿主校验来源）
when (val imported = host.importExistingImageResult(producerPluginId, toolResultJson)) {
    is LocalTaskImageImport.Imported -> host.startPublish(assistantId, listOf(imported.resourceId))
    is LocalTaskImageImport.Unsupported -> // 明确告诉用户“本版本不支持”，不要重试或假装成功
    is LocalTaskImageImport.Failed -> // 报错
}
```

## 9. 纯测试覆盖

- `LocalTaskSchedulerTest`：幂等、0 轮关闭、非参与者、全局停止、每 post 预算（含跨助手、
  all+收窄任务共享、发布任务共用 usage key）、每日预算跨日、回复上限、全员不回复、串行、
  取消后不迟到写（取消先/写入先/写入后 job 取消三种受控顺序）、stopAll、重启不重放、间隔。
- `LocalTaskCancellationTest`：gate 的取消先/写入先顺序、`close()` 等待在途写后拒绝新写、
  `close()` 唤醒取消等待者、审核 lease 在取消后不能写入且待审核动作被清空。
- `LocalTaskLedgerTest`：预算原子性、跨日、回复/点赞计数、持久化、重启中断、裁剪、损坏回退。
- `LocalTaskImageBudgetTest`：生图调用占用发布任务每任务/每日预算、上限拒绝、失败退款、无任务不扣费。
- `LocalTaskActionParserTest`：严格 schema、围栏、截断、非法动作、脚本文本按字面量、空白拒绝；
  生图 `image` 仅接受 `source=generate_image`+受限 `prompt`，拒绝 path/URI/mediaId/fileName、未开启
  生图的任务和 skip/互动动作。
- `LocalTaskSettingsTest`：收窄、策略默认、每助手覆盖、编解码、损坏回退。
- `LocalTaskBridgeParserTest`：JS 不能传 media/权限/发布参数、设置只读、未知方法、版本、身份字段；
  review 的 `approve` 必须是布尔、`body` 必须是长度受限的非空字符串（防止确认框与实际写入不一致）。

## 10. 任务 9.9 审查状态（2026-11-11，只读）

结论：**9.9 的“服务入口、原生设置只读、真实模型执行、JS 审核不能直接 apply”在 localtasks
范围内成立；“导入已有图”只有真实实现类，生产接线不在本目录，运行时默认返回 Unsupported，
不能标注为端到端完成。** 本次未运行 Gradle，编译/回测由 lead 执行。

| 9.9 子项 | 状态 | 依据与准确限制 |
|---|---|---|
| `LocalTaskService.startInteraction` | 本目录完成 | 只接受 `postId/assistantId/clientKey`；`assistantId` 只能收窄到原生 `participants`；同 post/同 clientKey 幂等；无参与者、0 轮、全局停止、预算不足均拒绝（`LocalTaskScheduler.submit`）。 |
| `LocalTaskService.settings/updateSettings` | 本目录完成 | 设置由原生 SharedPreferences 持有；桥只有 `task.settings` 只读；没有 `settings.update` 方法；`updateSettings` 关闭/停止/0 轮时 `stopAll()`。原生 Compose 设置页在 `PluginWebViewPage`，仍属宿主代码而非 JS。 |
| `LocalTaskExecutor` 真实模型 | 本目录完成 | 使用 assistant 的真实 `chatModelId`（回退全局）→ `findModelById` → `findProvider` → `ProviderManager.getProviderByType`，经 `UsageRecorder.wrap(..., UsageTag(PLUGIN, assistantId, null))` 计费；参数经共享 `buildAssistantGenerationParams`（含 assistant `customHeaders`/`customBodies`、`reasoningLevel`），单轮一次 `generateText`，无工具、无历史、无重试。 |
| 任务导入已有图 | **部分实现，未接线** | `PluginImageToolResultSource` 真实读取已存在的 `_orange_image` 文件并复制进 localdata，不触发生图。但 `LocalDataWebBridge.tasks()` 创建 host 时**没有**传 `imageSourceFactory`，`allowImages` 在原生设置保存时又被固定为 `false`，且生产代码没有调用 `importExistingImageResult`/`startPublish`，所以运行时 `importExistingImageResult` 恒为 `Unsupported`，带图发布不可达。 |
| 助手按需生图 | **本目录实现，默认关闭且 UI 未接** | publish/draft 的受控 `image` 字段先经 `LedgerLocalTaskImageBudget` 占用 `publishUsageKey` 的每任务/每日预算，再用 `imageGenerationModelId` 的 provider 生成 1 张图并 `session.importImageBytes`。需要 `allowImages && allowGeneratedImages && localdata.media && 生图模型已配置`；`allowGeneratedImages` 默认 false，`PluginWebViewPage` 还没有开关，所以生产环境仍不会自动生图。审核结构带 `LocalTaskMediaSource`（`none`/`native_selection`/`generated`），但原生审核框没有图片预览，**不要在补预览前打开开关**。 |
| `LocalTaskBridge` 的 review 是否 JS 直接 apply | **Web 原生拦截成立；桥本身不拦截** | `PluginWebViewPage` 对 `task.review.resolve` 先弹 Compose 确认框，用户确认且页面仍 active 后才 dispatch；拒绝/导航离开/并发第二个审核都失败。直接调用 `LocalTaskBridge.dispatch` 或 `LocalDataWebBridge.taskCall` 的原生代码会绕过确认，这是分层约束，不是桥级保证。 |

### 10.1 可在 task-9 内完成并已标注

- 服务入口、设置只读、真实模型、预算/取消/审核状态语义与文档。
- 审核通过后的写入使用宿主 actor + localdata ACL，不扩大 grant；审核正文上限受
  `MAX_MAX_OUTPUT_CHARS` 约束。
- JS 审核必须经过 WebView 原生确认；桥在分发前拒绝非法/空白/超长 `body`。

### 10.2 不在 task-9（本目录）范围，不能在这里假装完成

- **图片端到端接线**：需要在 `LocalDataWebBridge.tasks()` 注入 `PluginImageToolResultSource`，
  并补一个原生“导入已有图 → startPublish”的入口；还要决定 `allowImages` 的原生开关。
  这些都在 localtasks 之外，本次不改。
- **助手生图的设置/审核 UI**：`allowGeneratedImages` 的 Compose 开关、`localdata.media` 授权
  状态的禁用逻辑、审核框的图片预览与 `LocalTaskMediaSource` 展示都在 `PluginWebViewPage`
  /插件页面；本目录只提供 DTO 与预算 seam，默认关闭，接线前不得宣称可用。
- **把审核确认下沉到桥层**：需要 `LocalTaskBridge` 接收原生签发的 approval token，或把
  `task.review.resolve` 从 JS bridge 移除；这会改 WebView/桥协议，不在本目录范围。
- **真机/后台/进程被杀行为**：未验证，且本服务不启动前台服务。

### 10.3 本次审查修复（仅 localtasks）

1. `LocalTaskBridgeRequestParser`：`params` 必须是对象；review 的 `approve` 必须是布尔，
   `body` 必须是非空、非超长字符串，避免原生确认框显示空白/截断内容而实际写入旧正文。
2. `LocalTaskService.resolveReview`：显式空白 `editedBody` 不再回退到原正文；返回真实任务
   状态而不是硬编码 `completed`；审核写入后补记 `turns/comments/likes`，并在发布降级为草稿
   时通过 `detail` 返回原因。
3. `LocalTaskExecutor.applyPending`：审核通过的回复现在会执行 `action.like`（受 `autoLike`
   约束），与正常 turn 的行为一致。
4. `LocalTaskExecutor.generate`：改用共享的 `buildAssistantGenerationParams`，不再手写只带
   model 级自定义 headers/body 的参数；现在包含 assistant 的 `customHeaders`/`customBodies`、
   `reasoningLevel` 等真实参数，同时保留 `DEFAULT_MAX_TOKENS` 兜底上限。
5. `LocalTaskBridgeParserTest`：补 review 参数边界测试（空白/超长/非字符串 body、非布尔
   approve、无 body 的拒绝）。这些测试仍需 lead 运行。
6. `LocalTaskCancellationToken` / `LocalTaskCancellationGate`：取消与写入共用一把 gate 锁，
   `commitIfActive { 同步 localdata 写 }` 是唯一的线性化点；取消先到则不写，写先到则保留
   结果并让 `cancel()` 等待写入块结束。块内不允许挂起/网络调用。
7. `LocalTaskExecutor`：评论、点赞、草稿、发布、审核通过的评论/点赞/草稿/发帖全部改为
   `token.commitIfActive`；同一次回复的点赞+评论在同一临界区，取消不会只留下点赞。
8. `LocalTaskScheduler.beginReview/endReview`：审核写入在取消锁内租 token；`cancel()` 清空
   pending review 并等待在途写。`stopAll()` / 审核停止后不能再 apply。
9. `LocalTaskExecutor.applyPending`：写入前重新读取设置与助手，拒绝已删除/被移出参与者、
   `globalStop`/`disabled` 的审核动作；发布策略降级时改为草稿；`autoLike`/`allowImages`
   也按当前设置重新判断。模型调用完成后还会复核助手仍存在。
10. `LocalTaskService.close()`：先关闭 gate（拒绝并等待在途写）再取消 Job、标 `interrupted`、
    关闭 session，保证 `LocalTaskHosts.release()`/重建 host 不会与旧在飞写并发。
    `updateSettings` 在参与助手/策略/评论/点赞/审核/图片/回复上限变化时 `stopAll()`。
11. 预算：发布任务统一使用按 UTC 日切、跨任务/跨助手共享的
    `LocalTasksApi.publishUsageKey(...)`，不再按 taskId 记账，避免换任务或换助手重置每 post
    上限；新增跨助手、all+收窄任务、发布任务及隔日重置的预算测试。

### 10.4 仍存在的限制（不要当成已解决）

- `LocalTaskBridge` 不是审核安全边界；安全依赖 `PluginWebViewPage` 的原生确认。任何未来
  新增的 `taskCall` 调用方都必须自己补确认。
- `task.review.list` 会把待审核正文返回给页面（页面本来就能读该动态），不是新增越权；但
  正文在原生确认框中完整展示，超长内容已在上限处拒绝，仍可能造成较大的 UI 文本。
- 审核恢复：`markInterruptedOnStartup()` 只把 `queued/running` 改成 `interrupted`，
  `waiting_review` 会跨进程保留；审核写入不重放，但没有“审核超时”机制。
- 审核计数、review 移除、任务结束是分开持久化的；评论/点赞写入本身有确定性 clientKey
  幂等，但进程在中间崩溃时 `status` 的 `turns/comments/likes` 可能少记或多记一次。
- 同 post 幂等按 `(postId, assistantId)` 匹配：先启动 all-participants 任务、再启动
  assistant A 任务不会复用（反之会复用）。本轮只补了这种不对称下每 post 预算仍共享的
  测试，没有改变幂等匹配规则。
- `startInteraction` 只校验 postId 格式，真正 ACL 在 executor 里；`post_unavailable` 或
  `model_unavailable` 仍会消耗一次预留（scheduler 只在 `NotAttempted` 退款）。页面因此可用
  不存在的 post 或无模型的助手消耗每日预算；有每日上限，但没有按页面/按 post 的启动限流。
- 取消是**协作式**的：gate 只保证本地 SQLite 写入不会在取消后迟到落库，不中断已经发出的
  HTTP/模型请求，也不保证取消后进程不再收到响应；不要在 UI/文档里宣称 hard socket abort。
  `close()` 等的是同步写入块，不是模型请求。
- 服务/执行器/图片导入/审核写入路径仍没有 Android/Robolectric 单测；本轮**未运行 Gradle**
  （任务禁止），新增的纯 JVM 测试（含取消/预算/生图动作）需 lead 用
  `.\gradlew.bat :app:testDebugUnitTest --tests '*LocalTask*'` 回测；真实 Provider、ACL、图片
  字节导入和审核写入仍需人工验收。
- 助手生图的图片调用没有 token usage 行（`UsageRecorder` 只记录文本），只有 localtasks 的
  `publishUsageKey` 预算计数；若 lead 要求统一 Usage 统计，需要扩展 `UsageRecorder`，不在
  localtasks 范围。生图后任务被取消/审核被拒会留下未被引用的 localdata media，当前只有显式
  `sweepUnreferencedMedia()` 能清理。
