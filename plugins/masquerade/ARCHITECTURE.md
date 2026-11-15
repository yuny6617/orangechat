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
- **受邀这一步也不再调用 `callAI`**（1.1.0 曾用它生成一句应邀台词，导致「前端对话框里的助手其实没被邀请」）。1.2.0 起请柬作为可见消息经 `Bridge.sendChatMessage(conversationId, card, hiddenContext, true)` 发进主聊天，`answer=true` 让**主聊天里的那个助手自己回应**——它用的是宿主模型、宿主的上下文与人格，这才是用户真正在聊的那个「他」；
- MCP 形态下连通道都不需要：`roleplay_turn` 直接把 `stagePrompt` 交给客户端 LLM，由它自己演，再用 `record_stage_line` 记回剧本。

**会话 ID 的取得**：宿主 `Screen.PluginWebView` 的 `conversationId` 不会传进 WebView（页面是纯 `file://` 路径，无 query），唯一通道是 `listConversations()`。`resolveConversationId()` 取 `messageCount > 0` 且 `updateAt` 最新的一条，即「助手最新有对话的上下文」。

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

## 5. 让助手知道「刚才是游戏」的四条冗余通道

任何一条单独失效都不足以误导模型，四条同时保证：

1. `promptTemplate`（`inject_as_prompt` 默认 `true`）—— 宿主 `PluginToolProvider.getPluginPromptInjections()` 只在配置为 `true` 时注入，所以默认开启是硬要求。
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
