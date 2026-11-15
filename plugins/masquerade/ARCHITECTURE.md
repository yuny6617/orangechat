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
| 启幕专属卡片 | 主聊天（用户可见） | 是 | 否 |
| 启幕说明 | `transientSystemPrompt`（一次性） | 否 | 否 |
| 落幕专属卡片 | 主聊天（用户可见） | 是 | 否 |
| 落幕说明 | transientSystemPrompt + `mb:lastClosing` | 否 | **是，整场仅 1 条** |
| 长期规则 | `manifest.promptTemplate` | 否 | 每次请求注入 |

**剧中台词完全隔离**是「平时仍是真实情感关系」的技术保证：模型在主聊天里看不到剧中的告白、承诺、冲突，就不会把它们当作真实记忆。

## 4. 为什么角色扮演不走 `Bridge.callAI`

读宿主 `PluginWebViewPage.kt` / `PluginAiCall.kt` 后可以确认：`callAI` 的系统提示词由宿主写死为「你是一个阅读助手…」，插件无法替换成舞台设定。因此：

- `Bridge.callAI` **只**用来生成一句应邀台词（不承载角色扮演主循环），并带 fallback，失败也不阻塞流程；
- 真正的剧中回复走 `plugin/main.js` 自带的 OpenAI 兼容通道（`api_base` / `api_key` / `model_id`），系统提示词由 `buildStageSystemPrompt()` 完全掌控；
- MCP 形态下连通道都不需要：`roleplay_turn` 直接把 `stagePrompt` 交给客户端 LLM，由它自己演，再用 `record_stage_line` 记回剧本。

## 5. 让助手知道「刚才是游戏」的四条冗余通道

任何一条单独失效都不足以误导模型，四条同时保证：

1. `promptTemplate`（`inject_as_prompt` 默认 `true`）—— 宿主 `PluginToolProvider.getPluginPromptInjections()` 只在配置为 `true` 时注入，所以默认开启是硬要求。
2. 落幕 `closingNote` 作为 `Bridge.sendChatMessage` 的 `hiddenContext` → 宿主映射为 `transientSystemPrompt`，只对落幕那轮请求生效，不永久污染系统提示词。
3. `memoryBank.save(closingNote)` —— 整场只写一条，明确标注「游戏 / 不是真实发生的事 / 平时依旧是原本的真实情感关系」。
4. 用户可见的落幕卡 `【假面时刻 · 落幕】` —— 与 `promptTemplate` 里的识别标记一致，模型看到即知道收场。

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
| `permissions = [ai_chat, conversation_sync]` | 不申请 `provider_credentials`，不碰宿主凭据库 |
| 双重域名白名单（manifest + main.js） | 宿主对空 `allowedHosts` 是「默认放行」，不能依赖宿主兜底 |
| 零第三方依赖 | QuickJS 插件包与 MCP 都要能在最小环境跑起来，ZIP 自己写 |
| 素材本地化 | WebView 在 `file://` 下不依赖 CDN；离线可用；许可可追溯 |
| `main.js` 限定 ES5 子集 | QuickJS 沙箱不支持 `let/const/箭头/模板字符串/async/Promise/class` |

## 8. 未覆盖的验证

见 `SECURITY.md` 第 6 节与 `audit/AUDIT_REPORT.md` 文末「限制」。主要包括：Gradle 编译、Android 真机 WebView、真实模型供应商联调、宿主运行时权限弹窗行为。
