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

- **插件包**：`dist/orangechat-masquerade-1.2.0.zip` 可直接导入橘瓣；`customPageWebView` 提供专属华丽 UI（本地 CC0/MIT/OFL 素材，无外链 CDN）。
- **角色卡 / 模型接入**：参考 Mufy 的角色卡字段（身份、性格、小剧场、开场白、世界观、样例对话、边界），内置 3 张示例卡与增删改查、JSON 导入导出；OpenAI 兼容模型通道，API Key 由宿主密码字段保存。
- **MCP server**：stdio JSON-RPC 2.0，用 `vm` 复用同一份 `plugin/main.js` 状态机，由客户端 LLM 扮演舞伴，不联网、不读密钥。
- **落幕语义**：启幕/落幕专属卡片进入聊天上下文；`transientSystemPrompt` + 单条明确标注「游戏」的长期记忆 + 默认开启的 `promptTemplate`，保证助手知道刚才是角色扮演、平时仍是原本的真实情感关系。剧中台词只存插件 `dataStore`，不进主聊天历史与长期记忆。

### 1.2.0 修复（来自真机反馈）

- **同一个助手**：请柬不再用 `Bridge.callAI` 另开一次请求，改走 `sendChatMessage` 进入主聊天，前端对话框里的助手本人应邀；`conversationId` 通过 `listConversations()` 取 `messageCount>0` 的最新会话（宿主不会把它传进 WebView）。
- **落幕压缩概要**：整场戏由插件内置的那个模型压成概要，作为落幕卡的可见文本永久进入「最新有对话」的会话；只放概要、不放逐字台词，并自带「虚构游戏 / 不是真实发生的事 / 完整台词未进入本对话」标注。
- **演绎档案**：新增最多 20 场的档案（`mb:archive:*`），插件页第 4 个 tab 可回看逐轮台词、导出 JSON、二次确认删除；档案不进主聊天、不写长期记忆。
- **没配置模型就硬拦截**：移除预览垫片里的伪 API Key，新增 `modelAvailability()` 三态，`none` 时 `roleplay_turn` 返回 `MODEL_NOT_CONFIGURED`，零台词零网络请求；压缩降级为本地摘录并明确标注。
- **配置只有一个来源**：撤销上一轮误加的页面配置表单与内置预设，配置统一走宿主 `manifest.config`。

## 宿主改动

仅 `FilesPicker.kt` **+33 行**：加 3 个 import、读 `PluginManager.plugins`、在语音/视频/红包/转账之后条件渲染 `MasqueradeButton`、新增该 composable。入口按 `manifest.id == "com.orangechat.masquerade" && isEnabled` 判断，**未安装或卸载后不显示**；不打这个补丁，插件详情页也能打开完整功能。

## 验证

- `node --test tests/*.test.mjs`：**41/41 通过**
- `node scripts/build.mjs`：ZIP 11 条目，CRC 回读 + Python `zipfile.testzip()` 交叉校验通过
- `node audit/audit.mjs`：**三轮 41/41 全绿**，报告见 `plugins/masquerade/audit/AUDIT_REPORT.md`；含 20 组检测器正反样本自测，并如实记录 17 项发现与修复
- `node scripts/verify-package.mjs`：复刻宿主 `ZipInputStream` + `findManifest` + `validateManifest` 全导入链路，5 组正反样本自测；真机反馈的「找不到 manifest.json」已固化为自动回归
- 权限最小化 `conversation_sync`（请柬改走主聊天后移除 `ai_chat`）；HTTPS + 双重域名白名单；密钥不回显；WebView 动态渲染全经转义

## 限制（needs validation）

未在本地执行 Gradle 编译与 Android 真机 WebView 运行（补丁只使用宿主已有的 import / API，由 CI 与真机验收兜底）；模型接口只做本地 mock，未对真实供应商发起请求。详见 `SECURITY.md` 与审计报告文末。
```
