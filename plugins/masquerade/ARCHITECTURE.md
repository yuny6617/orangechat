# 架构说明 · 假面舞会

## 1. 分层

```
┌─────────────────────────────────────────────────────────────┐
│ 橘瓣宿主（Kotlin / Compose）                                 │
│  FilesPicker.kt 加号面板 ──条件渲染──▶ Screen.PluginWebView   │
│  PluginManager.plugins: StateFlow<List<PluginInfo>>           │
└───────────────────────────┬─────────────────────────────────┘
                            │ window.Bridge
┌───────────────────────────▼─────────────────────────────────┐
│ 插件专属 WebView（ui/index.html + app.js + boot.js）          │
│  仅负责交互与渲染；所有数据经 Bridge.callTool 进出              │
└───────────────────────────┬─────────────────────────────────┘
                            │ callTool(name, JSON)
┌───────────────────────────▼─────────────────────────────────┐
│ QuickJS 沙箱：plugin/main.js（唯一状态机）                    │
│  dataStore / config / memoryBank / fetch                      │
│  ├─ 模型通道：HTTPS + 白名单 → OpenAI 兼容 /chat/completions   │
│  └─ 主聊天通道：sendChatMessage(id, text, hiddenContext)       │
└───────────────────────────┬─────────────────────────────────┘
                            │ vm.runInContext（复用同一份源码）
┌───────────────────────────▼─────────────────────────────────┐
│ mcp/server.mjs：stdio JSON-RPC 2.0                           │
│  fetch = noNetworkFetch；由客户端 LLM 扮演舞伴                 │
└─────────────────────────────────────────────────────────────┘
```

**唯一状态机原则**：`plugin/main.js` 是状态、`buildStageSystemPrompt()`、`buildClosingNote()` 的唯一实现。MCP 用 `vm` 加载同一文件后只覆盖 `roleplay_turn` 并新增 `record_stage_line`，不复制任何剧情逻辑。这样「落幕语义」在两种形态里不可能漂移。

## 2. 状态机

`nextStatus(status, action)` 是唯一合法转换入口：

| 当前 | invite | accept | turn | end |
|---|---|---|---|---|
| `idle` | `invited` | — | — | `idle`（空场） |
| `invited` | `BAD_STATE` | `active` | — | `idle`（`cancelled`，不写记忆） |
| `active` | `BAD_STATE` | — | `active` | `ended` |
| `ended` | `invited` | — | — | `idle` |

`invited` 时的 `end` 是「收回请柬」，不是落幕：助手还没同意，不能让它以为刚玩过一场游戏。这个分支不写 `memoryBank`、不发主聊天落幕卡。

## 3. 数据通道（本项目的核心设计）

| 数据 | 存放 | 是否进入主聊天历史 | 是否进入长期记忆 |
|---|---|---|---|
| 角色卡 | `dataStore: mb:card:*` | 否 | 否 |
| 剧中每一句台词 | `dataStore: mb:transcript:*` | **否** | **否** |
| 会话状态 / 轮次 | `dataStore: mb:session` | 否 | 否 |
| 请柬（含面具与场景） | 主聊天（用户可见，`answer=true`） | 是 | 否 |
| 启幕专属卡片 | 主聊天（用户可见） | 是 | 否 |
| 启幕说明 | `transientSystemPrompt`（一次性） | 否 | 否 |
| **压缩概要** | 落幕卡可见文本 + `mb:archive:*` | **是（概要，非逐字）** | 否 |
| 落幕说明 | transientSystemPrompt + `mb:lastClosing` | 否 | **是，整场仅 1 条** |
| 完整台词档案 | `dataStore: mb:archive:*`（最多 20 场） | **否** | **否** |
| 长期规则 | `manifest.promptTemplate` | 否 | 每次请求注入 |

**剧中台词完全隔离**是「平时仍是真实情感关系」的技术保证：模型在主聊天里看不到剧中的告白、承诺、冲突，就不会把它们当作真实记忆。

**压缩概要是唯一的破例通道，且被刻意做窄**：宿主 `sendChatMessage` 的 `content` 会永久写入会话历史，`hiddenContext` 只作用本轮，所以要让助手在后续对话里「记得玩过这一场」，概要必须放进可见文本。为保证它不被当成真实经历，概要有三条约束：只由模型压成第三人称剧情摘要、由 `compressTranscript()` 强制截断长度、末尾固定附带「虚构游戏 / 不是真实发生的事 / 完整台词未进入本对话」标注。

## 4. 为什么两条路都不走 `Bridge.callAI`

读宿主 `PluginWebViewPage.kt` / `PluginAiCall.kt` 后可以确认：`callAI` 的系统提示词由宿主写死为「你是一个阅读助手…」，插件无法替换成舞台设定。因此：

- **角色扮演主循环**走 `plugin/main.js` 自带的 OpenAI 兼容通道（`api_base` / `api_key` / `model_id`），系统提示词由 `buildStageSystemPrompt()` 完全掌控；
- **受邀这一步也不再调用 `callAI`**（1.1.0 曾用它生成一句应邀台词，导致「前端对话框里的助手其实没被邀请」）。1.2.0 起请柬作为可见消息经 `Bridge.sendChatMessage` 发进主聊天；1.3.0 进一步改为回执驱动 + 邀请函卡片图，`answer=true` 让**主聊天里的那个助手自己回应**——它用的是宿主模型、宿主的上下文与人格，这才是用户真正在聊的那个「他」；
- MCP 形态下连通道都不需要：`roleplay_turn` 直接把 `stagePrompt` 交给客户端 LLM，由它自己演，再用 `record_stage_line` 记回剧本。

**会话 ID 的取得**：宿主 `Screen.PluginWebView` 的 `conversationId` 不会传进 WebView（页面是纯 `file://` 路径，无 query，`SafeFileResolver.resolveInside` 还会把 query 当成文件名，所以不能塞进 `entryPath`），唯一通道是 `listConversations()`。1.3.0 把目标拆成两种：
- `target=current`（请柬 / 应邀 / 婉拒）→ `list[0]`，宿主按 `updateAt` 倒序返回，**包含还没有消息的新会话**；
- `target=latestActive`（落幕压缩概要）→ `messageCount > 0` 的最新一条，即「助手最新有对话的上下文」；
- 若宿主将来暴露 `Bridge.getCurrentConversationId()`，优先精确命中，否则自动降级到列表。

1.2.0 把两者混用同一个「先过滤 `messageCount>0`」的选择器，用户从聊天页加号进入一个刚建的空会话时，请柬被投到另一个会话，聊天里自然看不到——这是本轮真机反馈的直接根因。

## 4.1 模型三态与硬拦截

| 状态 | 判定 | `roleplay_turn` 行为 | 落幕压缩 |
|---|---|---|---|
| `configured` | `resolveCredentials()` 拿到 key | 正常出戏 | 模型压缩 |
| `none` | 无 key 且非预览 | **返回 `MODEL_NOT_CONFIGURED`，零台词、零网络请求** | 降级本地摘录，明确标注 |
| `demo` | 仅浏览器预览垫片注入 `demo:true` | 本地剧本 | 本地摘录 |

三态存在的直接原因：旧版 `boot.js` 会注入一个伪 API Key，真机上请求永远 401，界面上却仍在「出戏」，用户看到的就是牛头不对马嘴的回复。现在无模型时前端不产生任何气泡，只显示配置指引；`demo` 标记在真机无效（宿主不会注入），无法用来伪造「已配置」。

## 4.2 落幕压缩与演绎档案

- `callModel(systemPrompt, history, line)` 是**唯一**的模型出口：剧中台词与落幕压缩共用同一份凭据、同一套白名单校验，不引入第二个模型，也不额外申请权限。
- `compressTranscript()` 把整场逐轮台词交给模型压成概要；失败或无模型时走 `localDigest()`，返回值带 `compressedBy: "local"` 与 `compressedDegraded: true`，**不假装是模型压的**。
- `mb:archive:<sessionId>` 保存完整台词 + 概要 + 压缩来源 + 落幕说明，`mb:archive:index` 保存索引，两者都只在你插件私有 `dataStore` 里；超过 20 场时索引与数据**同时**裁剪（旧实现只裁索引，会残留无主数据，审计 R2-11 是这条的回归测试）。
- 档案 ID 必须是字符串且匹配白名单正则，`parseArchiveId()` 不做静默转换——`Number` / `{toString}` 之类的输入一律拒绝，避免「传 1 读到第 1 场」这种越权读。

## 4.3 送达回执、邀请函卡片与同意 / 婉拒（1.3.0）

`PluginWebViewPage.kt` 的 `bridgeCall` 是 Promise 回调式桥接，`handleConversationBridge` 在缺少 `conversation_sync` 权限时返回 `success:false`；`sendChatMessage` 内部 `answer=true`，会话正忙时也可能拒绝。因此「页面显示已发送」不等于「聊天里真的有消息」。

| 环节 | 实现 |
|---|---|
| 回执判定 | `sendOnce()` 只认 `r.success === true`；`undefined` / 空回执 / `success:false` 一律失败 |
| 状态机 | `masquerade_invite` 写 `delivery=pending`；`masquerade_invite_delivery` 写 `delivered/failed`。**1.4.0 起失败不再回滚 `idle`**：邀请在插件内已成立，同步只是尽力而为的通知；`masquerade_invite_cancel` 仅用于「重试送达」前清理旧状态 |
| 页面顺序 | **先发送、再切界面**；标题与「✓/✗ 送达状态」按真实回执渲染，没有写死的「已送到聊天里」 |
| 降级重试 | 首次失败后去掉 `hiddenContext` 与卡片图、把文本压到 180 字内再发一次；成功标注「降级重试后送达」 |
| 卡片载体 | 聊天里 = 请柬可见文字 + `buildInviteImage()` 用 Canvas 画的邀请函 PNG（走宿主 `uploadChatImage` 分块通道）；Canvas 不可用则纯文本；宿主 `UIMessagePart.Image` 可点击放大 |
| 原生卡片为何不做 | 宿主红包/通话卡片是 `UIMessageAnnotation.ChatInteraction`，插件的 `sendChatMessage` 无法附加消息注解；为此改宿主消息渲染层会破坏「只改一个文件」的可插拔约束 |
| 同意 / 婉拒 | 1.4.0 起弹窗撤销，两个按钮**平铺在页面**（真机 WebView 对 `min()` / `dvh` / 背景模糊滤镜支持不一致，曾把弹窗压成裁切胶囊）；选择后把 `【假面时刻 · 应邀】`（合并启幕卡）或 `【假面时刻 · 婉拒】` 作为可见消息送进聊天 |
| 概要卡片 | `summaryCard` 只含压缩概要、角色、场景、轮次与 `archiveId`，**无 `transcript` 字段**；剧中具体内容不进入上下文，只能在插件档案里查看 |

`tests/delivery-card.test.mjs` 用轻量 DOM stub + 模拟宿主 Bridge 在 `vm` 里真正执行 `app.js`，逐条核对 `sendChatMessage` 的调用参数与状态机回执，不是静态文本断言。

## 4.4 主聊天助手调用工具应邀（1.4.0，主路径）

**这才是「和助手玩」的正确通道，之前三轮都绕开了它。**

| 宿主依据 | 结论 |
|---|---|
| `ToolSurfaceBuilder.kt:106` | `addAll(pluginToolProvider.getTools(...))` —— `manifest.tools` **本来就会注册进发给 LLM 的 tools 数组** |
| `PluginToolProvider.createTool()` | `buildDescription()` 把 `toolDef.description` 原样发给模型（追加一行 `Provided by plugin: …`）；`needsApproval = true`，首次调用会出现批准框 |
| `ToolNaming.buildPluginToolName()` | 工具名变成 `plg_<8位hash>_<原名>`，模型按 tools 定义里的实际名称调用，裸名对不上 |
| `PluginToolProvider.getPluginPromptInjections()` | 只有 `plugin.config["inject_as_prompt"] == "true"` 才注入 `promptTemplate` |
| `PluginManager.refreshPluginsInternal()` | `plugin.config` 来自 `savedSettings.configs[id]`（用户显式保存的覆盖值）；`manifest.config` 的 `default: true` **不会自动落库** |

也就是说：宿主会自动注入一条「插件能力总览」，但**具体的假面舞会规则默认不进系统提示词**。旧版把规则只写在 `promptTemplate` 里，工具描述又写成说明书（「递出假面请柬」），助手于是只回自然语言「能看到」，从不调工具，邀约自然进不了对话。

1.4.0 的修正：

1. **把规则放进必然送达的通道**——`masquerade_invite` / `masquerade_accept` / `masquerade_end` 的 `description` 各写三段：【必须调用的场景】（列出用户真实措辞）、【调用后必须做什么】、【语义边界】。
2. **给工具返回值加输出契约**——`replyToUser`（要原样输出的卡片文本）与 `replyInstruction`（「一字不改、不要输出 JSON、不要复述工具名」）。工具结果在橘瓣里是折叠的「调用工具」块，用户看得见的是助手的**回复文本**，所以这一步决定邀约是否真正进对话。
3. **三拍链路**：用户说「我们玩一场假面舞会吧」→ 助手调 `masquerade_invite` 输出请柬卡 → 用户「我愿意」→ 调 `masquerade_accept` 输出启幕卡 → 用户「摘下面具」→ 调 `masquerade_end` 输出只含压缩概要的落幕卡。
4. **`promptTemplate` 降级为辅助说明**：修正工具名前缀说明，去掉与 `replyToUser` 冲突的旧 `chatCardText` 契约；并明确「核心规则在工具描述里，即使本模板未注入也要遵守」。
5. **不依赖开关**：用户若不打开 `inject_as_prompt`，主路径照常可用；打开并保存一次只是额外获得长期语义规则。
6. **兜底**：插件页提供「复制请柬文本，贴进聊天」，用户手动粘贴后助手同样按工具描述应邀；页面自动同步失败也不回滚状态。

`tests/chat-tool-invite.test.mjs` 模拟宿主按工具名调用，逐条核对触发措辞、`replyToUser` 与三拍输出，不依赖 `sendChatMessage`。

## 5. 让助手知道「刚才是游戏」的四条冗余通道

任何一条单独失效都不足以误导模型，四条同时保证：

1. `promptTemplate` —— 宿主 `getPluginPromptInjections()` 只在用户**显式保存** `inject_as_prompt=true` 时注入，`default: true` 不会自动落库；因此 1.4.0 起核心触发条件与语义边界同时写进工具描述，**这一条不再是不可缺的硬要求**。
2. 落幕 `closingNote` 作为 `Bridge.sendChatMessage` 的 `hiddenContext` → 宿主映射为 `transientSystemPrompt`，只对落幕那轮请求生效，不永久污染系统提示词。
3. `memoryBank.save(closingNote)` —— 整场只写一条，明确标注「游戏 / 不是真实发生的事 / 平时依旧是原本的真实情感关系」。
4. 用户可见的落幕卡 `【假面时刻 · 落幕】` —— 卡里带**压缩概要**和「不是真实发生的事 / 完整台词不进入本对话」标注，与 `promptTemplate` 里的识别标记一致，模型看到即知道收场。

`masquerade_end` 里 `chatHiddenContext === closingNote === memoryBank 内容`，审计 R1-06 / R3-08 逐字校验这一点。

## 6. 宿主集成点（源码依据）

| 依据 | 结论 |
|---|---|
| `PluginManifest.kt` | 支持 `customPageWebView`、`promptTemplate`、`allowedHosts`、`permissions` |
| `PluginInfo.hasPageShortcut()` | `customPageWebView != null` 即插件详情页可打开页面 |
| `PluginWebViewPage.kt` | `Bridge.callTool` / `sendChatMessage` / `listConversations`；`hiddenContext` → `transientSystemPrompt`；`conversation_sync` 权限校验 |
| `PluginToolProvider.kt` | `inject_as_prompt == true` 才注入 `promptTemplate` |
| `PluginLoader.kt` | hook 返回值被忽略，因此不依赖 hook 传递状态 |
| `FilesPicker.kt` | 加号面板 `PickerActionStrip` 里语音 / 视频 / 红包 / 转账同排 |
| `ChatDrawer.kt` | 宿主已有 `koinInject<PluginManager>()` + `collectAsStateWithLifecycle()` 用法，补丁沿用 |

补丁只改 `FilesPicker.kt`：加 3 个 import、读 `installedPlugins`、条件渲染 `MasqueradeButton`、新增该 composable。宿主不持有任何剧情状态。

## 7. 关键取舍

| 取舍 | 理由 |
|---|---|
| 按钮条件渲染，而非硬编码常驻 | 满足「可插拔」；卸载后不留死入口 |
| 补丁只动 1 个文件 | 降低与宿主后续提交的冲突面，便于 PR 与回滚 |
| `permissions = [conversation_sync]` | 请柬改走主聊天后不再用 `Bridge.callAI`，`ai_chat` 已移除；`conversation_sync` 只用于 `sendChatMessage` / `listConversations`；不申请 `provider_credentials`，不碰宿主凭据库 |
| 双重域名白名单（manifest + main.js） | 宿主对空 `allowedHosts` 是「默认放行」，不能依赖宿主兜底 |
| 零第三方依赖 | QuickJS 插件包与 MCP 都要能在最小环境跑起来，ZIP 自己写 |
| 素材本地化 | WebView 在 `file://` 下不依赖 CDN；离线可用；许可可追溯 |
| `main.js` 限定 ES5 子集 | QuickJS 沙箱不支持 `let/const/箭头/模板字符串/async/Promise/class` |

## 8. 未覆盖的验证

见 `SECURITY.md` 第 6 节与 `audit/AUDIT_REPORT.md` 文末「限制」。主要包括：Gradle 编译、Android 真机 WebView、真实模型供应商联调、宿主运行时权限弹窗行为。
