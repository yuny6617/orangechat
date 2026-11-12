# 本地朋友圈、主题与多助手开发记录

## 基线与交付边界

已通过GitHub API核对用户fork功能分支HEAD为0c74300c4e11001fff9c32cf046f6087f2d3d314（R8）。从该干净基线创建feature/local-moments-themes-export，不覆盖默认分支。现有优化共存包名/证书及unsigned正式候选流程沿用。

需求依据：用户开发任务书（501行，附件SHA256 61a10b0b506c67677aaf1b9a94184856fd5dd26ba5e0072ec5aa9dd98bc2565e）。朋友圈业务独立插件ZIP、主题为声明式ZIP，宿主只承载通用受控能力，不新增社交服务器。完整目标保留，按阶段编译验证。

## 阶段一已确认的源码事实

- ChatList.kt分享入口过滤selectedItems并取currentMessage，因此不能把该问题描述为‘导出强制渲染所有未选消息’。
- Export.kt将全部选中消息加入同一个Column，再用BitmapComposer一次drawToBitmap；BitmapComposer默认高度10000dp，并随设备density放大。长选中内容造成大位图分配和测量风险。
- 原capture使用Handler延时100ms等待图片，没有可靠图片加载完成条件；图片显示尺寸限制不等于显式解码尺寸。
- 原导出在Main coroutine中压缩PNG、写文件，再通过exportImage压缩保存第二次；按钮在任务完成前提示成功、关闭面板。
- 尚无用户手机崩溃日志，以上是可核实的风险和错误路径，不能声称已复现其实际OOM/ANR异常。

修复实现方向：单页固定有限像素，不创建巨图再切片；长文本按有限块排版并连续分段，图片采样到页内尺寸；仅选中内容，背景编码/IO，顺序多图分享，进度/取消/失败清理；Markdown源码和图表数据的分页清晰模式显式提示视觉差异，不静默截断。共享Compose capture保持图表调用兼容，并单独验证取消/清理。

## 生图接口初步事实

用户薄荷绘图看图版1.17.3使用suanbohe_draw，配置密钥由既有插件机制使用；返回PNG Base64并在插件dataStore分块暂存，在工具结果输出data:image/png;base64。跨插件长期图片引用不能直接依赖可回收槽位。新宿主媒体接口必须把受授权结果保存为受控持久资源ID，独立缩略图，密钥不传给朋友圈。

## 并行审查与职责

- 导出实现：Export.kt、BitmapComposer.kt及新增有界导出测试；不触碰插件/设置共享文件。
- 插件/朋友圈：先审查QuickJS/WebView权限、模型上下文、结构化存储和媒体契约。
- 多助手/主题：核对真实身份窗口选择与三套源码配色，再确定设置迁移和主题变量。
- 数据备份：核对Room/backup恢复事务及插件生命周期扩展，防止新数据仅在运行时可用却无法备份。

公共接口在审查报告合并后定版；禁止把UI演示或模拟模型回复当作完成。

## 多助手主动消息（task-6）

截至 2026-11-11，本项已完成代码接线和 JVM 验收。`ProactiveTaskExecutor` 复用既有服务生成、工具和上下文压缩路径；nullable 参与配置只迁移一次并冻结助手与窗口，显式空列表保持无人参与。任务绑定 assistantId、conversationId 和最后真实用户消息锚点；各助手使用自己的窗口配置、模型、角色和压缩结果，不随前台选择切换。设置页已接通多助手参与、固定窗口、独立冷却/每日任务上限、额外频率/活跃时段/提示词，以及全局活跃时段、频率、每日模型调用预算、暂停和停止。

执行默认串行，按助手公平轮转；初始请求、工具续接、工具循环收尾及额外 finalizer 均在网络发送前预留预算，已发送的失败请求不退账。失败不自动重试，仍遵守独立冷却与日限额。停用、移除助手、修改目标及全局暂停/停止会撤销待执行任务并取消运行 Job；窗口删除沿 ChatService 生命周期取消。每个 turn 和最终提交均重新核对授权、归属和精确用户锚点，流式及工具 checkpoint 不提前进入聊天；完成结果由 ChatService 在会话锁内通过任务 guard 一次发布。通知使用目标助手名称和对应窗口 PendingIntent。进程初始化中断旧 Running、取消旧 Pending 和过期预约，不补发旧任务。

持久提交收尾：最终消息先按 taskId 与顺序派生确定性消息 ID，将最终 UIMessage payload（UTF-8 上限 512,000 字节）与 ID 列表在任务账本同一次持久快照中写为 CommitPending。随后 ChatService 在会话锁内调用 ConversationRepository 的单次 Room 事务，核对窗口/助手/真实用户锚点和已有消息 ID 内容，再幂等追加缺失消息；Room 成功后才标 Sent 并清除 payload。CommitPending 不被普通重启中断或任务保留裁剪删除，未核对完成前不放行下一次生成。恢复先核对 Room 已有完整 ID 与内容，已写成功则仅补记 Sent；缺失时只恢复同一持久 payload，不重跑模型或工具，且重新核对当前授权、owner/window/anchor，用户已发新消息或窗口/授权失效则转 Interrupted，不擅自追加旧输出。取消在 durable intent 前可撤销；进入 CommitPending 后保留恢复凭据，不以清空正文的方式制造假 Sent。主动工具集合另排除 generate_image、text_to_speech 及不透明插件/MCP 工具，避免其内层付费调用绕过网络 turn 预算，普通聊天工具路径不受此主动消息限制。

验证：lead 统一执行的最新 `session-proactive-durable-regression.log` 为 BUILD SUCCESSFUL，包含 Proactive/video/VoiceAudioPayload/MomentsChatShare/PackageTheme 定向回归。最新 9 份 Proactive JUnit XML 合计 86 项、0 failures、0 errors，其中 ProactiveTaskStoreTest 为 16 项。新增覆盖 durable payload/ID 在重启中的保留、Room 回调失败不标 Sent、有界 payload 拒绝、模拟 Room 成功后 Sent preferences 写失败并重启恢复同一 payload。此前 integrated4 的 82 项通过是旧实现证据，不替代本次持久性收尾结果；最新批次通过也不等于全仓库所有测试通过。

边界：账本和 Room 仍是两个存储，但现在以 durable intent + 同一确定性 payload/IDs 恢复收敛，不再采用“先 Sent 后落库”的易丢失顺序。本轮验证是编译、纯 JVM 状态机与模拟写失败，未进行真实 Room 事务中途杀进程/断电、持久媒体清理竞态或跨存储故障注入，也未进行设备、锁屏、省电、真实双模型/通知跳转和端到端竞态验证；不能据此宣称已实测所有崩溃窗口或设备 exactly-once，不承诺 Android 后台准点执行。已执行的外部工具副作用不能回滚，只保留有界活动回执供核查。模型返回 WAIT 的分钟值目前仅记录决策，后续检查由全局调度间隔和独立冷却控制，未提供每助手 WAIT 精确延后。

## 宿主助手任务 localtasks（task-9）

截至 2026-11-11，本项服务、调度、真实执行器和审核路径已落地并完成一次只读审查；**本次未运行 Gradle**，编译与回测由 lead 执行，不能把旧构建产物当作修改后的通过证据。

可在 task-9 范围内完成并交付：

- `LocalTaskService.startInteraction/status/cancel/stopAll/settings/updateSettings/pendingReviews/resolveReview`；`startInteraction` 只接受 `postId/assistantId/clientKey`，助手只能收窄到原生 `participants`，同 post（同一 assistantId 收窄范围）/同 clientKey 幂等；all-participants 任务与收窄到某助手的任务不互相幂等。
- 设置由原生 SharedPreferences 持有，桥只读；JS 没有修改设置、预算、发布策略或图片权限的方法。
- `LocalTaskExecutor` 使用助手真实 `chatModelId`（回退全局）、真实 Provider 与 `UsageRecorder` 计费；单轮一次 `generateText`，无工具、无历史、无重试。
- 审核写入在 WebView 原生确认后才 dispatch；拒绝、页面失效、并发审核均失败；审核写入仍走 localdata ACL 与宿主 actor。

准确限制（不能标为已完成）：

- **已有图导入只有实现类，生产未接线**：`PluginImageToolResultSource` 真实读取已存在的 `_orange_image` 并复制进 localdata，但 `LocalDataWebBridge.tasks()` 未注入 `imageSourceFactory`，`allowImages` 在原生设置保存时固定为 `false`，生产代码也没有调用 `importExistingImageResult`/`startPublish`；因此运行时该能力返回 `Unsupported`，带图发布不可达。端到端接线需要改 localtasks 之外的 WebView/localdata，不在本项范围。
- **审核拦截在 WebView 层，不在桥层**：`PluginWebViewPage` 的原生确认框才是安全边界；直接调用 `LocalTaskBridge.dispatch` 或 `LocalDataWebBridge.taskCall` 的原生代码会绕过。没有桥级 approval token。
- 无 Service/Executor/图片导入/审核写入的单测；21:28:25 的 37 项纯测试覆盖 Scheduler/Ledger/ActionParser/Settings，21:35:32 的定向 BridgeParserTest 12 项通过（包含本次新增边界测试），但都早于最后两处代码修改，不能作为最终通过证据。审核与图片仍需人工验收。
- 本服务不启动前台服务，进程被杀后任务不续跑；`waiting_review` 会跨进程保留，没有审核超时。审核计数/review 移除/任务结束分三次持久化，评论写入有 clientKey 幂等，但崩溃窗口可能使 status 计数少记或多记一次。
- `startInteraction` 只校验 postId 格式，ACL 在 executor 内才检查；`post_unavailable`/`model_unavailable` 会消耗一次模型调用预留（只有 `NotAttempted` 退款），页面可用不存在的 post 或无模型助手消耗每日预算；有每日上限，无按页面/按 post 的启动限流。

本次审查修复（仅 `app/src/main/java/me/rerere/rikkahub/plugin/localtasks/`）：

- review 的 `approve` 必须为布尔，`body` 必须为非空、非超长字符串；`params` 必须是对象。避免原生确认框显示空白/截断正文而实际写入旧正文。
- 显式空白 `editedBody` 不再回退到原正文；审核返回真实任务状态而非硬编码 `completed`；审核写入后补记 `turns/comments/likes`；发布降级草稿时通过 `detail` 返回原因。
- 审核通过的回复补执行 `action.like`（受 `autoLike` 约束）。
- `LocalTaskExecutor.generate` 改用共享 `buildAssistantGenerationParams`，补齐 assistant 级 custom headers/body、reasoningLevel 等真实参数，保留 `DEFAULT_MAX_TOKENS` 兜底。
- `LocalTaskBridgeParserTest` 增加 review 参数边界测试。

详见 `app/src/main/java/me/rerere/rikkahub/plugin/localtasks/LOCAL_TASKS_API.md` 的 §6、§7、§10。

## 完成度记录

更新说明：以上阶段状态为早期审查快照，不代表本轮最终完成度。当前工作树已接入视频修复、多助手、插件桥、朋友圈、主题和数据备份；验收与未验证事项以 `docs/SESSION_ACCEPTANCE_CURRENT.md` 及本轮最新构建日志为准。设备/锁屏/真实模型端到端仍未验证。
