# 集成分支与 PR 说明

本分支 `feature/masquerade-integration` 已推送到 `yuny6617/orangechat`，base 为默认分支 `feature/video-call-gifts-ci`。

当前 GitHub 集成令牌是只读的（创建仓库 / 建分支 / 建 PR 均返回 403），因此 PR 需要你在网页上点一下创建：

**一键创建：** <https://github.com/yuny6617/orangechat/pull/new/feature/masquerade-integration>

打开后确认 base = `feature/video-call-gifts-ci`、head = `feature/masquerade-integration`，把下面的标题与正文粘贴进去即可。

---

## 标题

```
feat(masquerade): 可插拔「假面舞会」插件 / MCP 与加号入口
```

## 正文

```markdown
## 做了什么

新增 `plugins/masquerade/`：一个可插拔的「假面时刻」插件，同时提供 MCP 形态与浏览器预览页。

- **插件包**：`dist/orangechat-masquerade-1.4.0.zip` 可直接导入橘瓣；`customPageWebView` 提供专属华丽 UI（本地 CC0/MIT/OFL 素材，无外链 CDN）。
- **角色卡 / 模型接入**：参考 Mufy 的角色卡字段（身份、性格、小剧场、开场白、世界观、样例对话、边界），内置 3 张示例卡与增删改查、JSON 导入导出；OpenAI 兼容模型通道，API Key 由宿主密码字段保存。
- **MCP server**：stdio JSON-RPC 2.0，用 `vm` 复用同一份 `plugin/main.js` 状态机，由客户端 LLM 扮演舞伴，不联网、不读密钥。
- **落幕语义**：启幕/落幕专属卡片进入聊天上下文；`transientSystemPrompt` + 单条明确标注「游戏」的长期记忆 + 默认开启的 `promptTemplate`，保证助手知道刚才是角色扮演、平时仍是原本的真实情感关系。剧中台词只存插件 `dataStore`，不进主聊天历史与长期记忆。

### 1.4.0 本轮修复（真机反馈：弹窗塌陷 + 邀约始终没进主聊天）

按反馈**放弃消息内弹窗**，改走宿主原本就支持的主聊天工具通道。

- **走对通道**：回读宿主 `ToolSurfaceBuilder.kt:106`，`PluginToolProvider.getTools()` **本来就把 `manifest.tools` 注册给主聊天 LLM**。之前三轮一直让插件页面用 `sendChatMessage` 往聊天塞消息，这条路受 `bridge://` URL 长度与 `answer=true` 限制，真机不稳；宿主现成的「助手调工具」反而没用起来。
- **为什么助手之前不调**：`getPluginPromptInjections()` 只在用户**显式保存** `inject_as_prompt=true` 时注入 `promptTemplate`（`PluginManager` 用的是 `savedConfig`，`default: true` 不会自动落库）；且工具描述写得像说明书，没写触发条件与输出契约。助手于是只回自然语言「能看到」。
- **修正**：把「什么时候必须调用」（列出用户真实措辞）、「把 `replyToUser` 一字不改作为回复输出」、「虚构游戏语义」全部写进 `toolDef.description`（`buildDescription()` 必然发给模型）；三个流程工具返回值新增 `replyToUser` + `replyInstruction`。工具结果在 UI 里是折叠块，只有助手的回复才是用户看得见的对话——这一步决定邀约能否进对话。
- **三拍链路**：「我们玩一场假面舞会吧」→ 请柬卡；「我愿意」→ 启幕卡；「摘下面具」→ 只含压缩概要的落幕卡。`promptTemplate` 降级为辅助说明，修正 `plg_` 工具名前缀并去掉与 `replyToUser` 冲突的旧 `chatCardText` 契约；核心规则不依赖 `inject_as_prompt` 开关。
- **撤销弹窗**：真机截图显示全屏邀请函被压成一条裁切胶囊（`min()` / `dvh` / 背景模糊滤镜兼容问题）。DOM、CSS、JS 全部删除；「同意赴约 / 婉拒」平铺在页面上，并新增「复制请柬文本，贴进聊天」兜底。
- **送达失败不再阻断玩法**：1.3.0 把失败绑定为回滚 `idle`，用户连在插件里启幕都做不到。现在如实标注 `✗ 未送达` 但保留 `invited`，仍可同意/婉拒或复制请柬给助手。

（1.3.0 已交付：送达回执驱动、`current` / `latestActive` 会话拆分、落幕概要卡片与内容隔离；1.2.0 已交付：前端助手应邀、压缩概要入上下文、演绎档案、无模型硬拦截 `MODEL_NOT_CONFIGURED`、配置回归宿主单一来源。）

## 宿主改动

仅 `FilesPicker.kt` **+33 行**：加 3 个 import、读 `PluginManager.plugins`、在语音/视频/红包/转账之后条件渲染 `MasqueradeButton`、新增该 composable。入口按 `manifest.id == "com.orangechat.masquerade" && isEnabled` 判断，**未安装或卸载后不显示**；不打这个补丁，插件详情页也能打开完整功能。

## 验证

- `node --test tests/*.test.mjs`：**66/66 通过**；`delivery-card.test.mjs`(14) 模拟宿主 Bridge 执行 `app.js`，`chat-tool-invite.test.mjs`(11) 模拟 LLM 按工具名调用并核对 `replyToUser` 三拍输出
- `node scripts/build.mjs`：ZIP 11 条目，CRC 回读 + Python `zipfile.testzip()` 交叉校验通过
- `node audit/audit.mjs`：**三轮 51/51 全绿**，报告见 `plugins/masquerade/audit/AUDIT_REPORT.md`；含 20 组检测器正反样本自测，并如实记录 27 项发现与修复
- `node scripts/verify-package.mjs`：复刻宿主 `ZipInputStream` + `findManifest` + `validateManifest` 全导入链路，5 组正反样本自测；真机反馈的「找不到 manifest.json」已固化为自动回归
- 权限最小化 `conversation_sync`（请柬改走主聊天后移除 `ai_chat`）；HTTPS + 双重域名白名单；密钥不回显；WebView 动态渲染全经转义

## 限制（needs validation）

未在本地执行 Gradle 编译与 Android 真机 WebView 运行（补丁只使用宿主已有的 import / API，由 CI 与真机验收兜底）；模型接口只做本地 mock，未对真实供应商发起请求。详见 `SECURITY.md` 与审计报告文末。
```
