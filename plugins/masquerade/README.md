# 假面舞会 · Masquerade for OrangeChat

> 一个可插拔的「假面时刻」：从聊天页加号进入，邀请助手戴上面具，在专属舞台上角色扮演；
> 摘下面具时，助手清楚地知道刚才那是一场游戏，你们平时依旧是原本的真实情感关系。

本项目为 [yuny6617/orangechat](https://github.com/yuny6617/orangechat)（橘瓣 / RikkaHub）提供三种可独立使用的形态。在宿主仓库中它位于 `plugins/masquerade/`，是一个自成一体的子项目；本目录下的 `dist/*.zip` 已随仓库提供，可直接下载导入，无需自行构建。



| 形态 | 产物 | 用途 |
|---|---|---|
| 橘瓣插件 | `dist/orangechat-masquerade-1.2.0.zip` | 装进 App，出现在聊天页加号面板，有专属 WebView UI |
| MCP server | `mcp/server.mjs` | stdio JSON-RPC，让任意 MCP 客户端的 LLM 直接当舞伴 |
| 宿主补丁 | `host-patch/FilesPicker.kt.patch` | 只改 1 个文件 +33 行，把入口放到「语音通话 / 视频通话 / 红包 / 转账」旁边 |

三种形态**共用同一份状态机与落幕语义**（`plugin/main.js`），MCP 通过 `vm` 加载它，不复制逻辑。

---

## 📱 装到手机：请认准这一个文件

> **真机踩过的坑**：在 GitHub 点 `Code → Download ZIP` 下载到的是**整个仓库源码包**，
> 其中 `manifest.json` 位于 `plugins/masquerade/plugin/manifest.json`（第 4 层）；
> 而宿主 `PluginScanner.findManifest()` 只查**根目录和一级子目录**，
> 导入必然报 **「导入失败: 找不到 manifest.json」**。

**唯一可导入的文件**：`dist/orangechat-masquerade-1.2.0.zip`

- 一键下载（本地产物 169109 字节，`Content-Type: application/zip`）：
  <https://raw.githubusercontent.com/yuny6617/orangechat/feature/masquerade-integration/plugins/masquerade/dist/orangechat-masquerade-1.2.0.zip>
- 或在仓库文件列表进入 `plugins/masquerade/dist/`，点该 `.zip` → `View raw` / `Download`；**不要**点页面顶部的 `Code → Download ZIP`
- 或直接使用对话中提供的附件卡片保存

**下载后自检**（任选其一）：

- 用解压软件打开：**第一层**就能看到 `manifest.json` 和 `main.js`；若第一层是 `orangechat-xxx/` 文件夹，就是拿错包了
- 电脑上跑 `node scripts/verify-package.mjs <你下载的文件>`，输出 `✓ 导入可成功` 才对

然后在橘瓣里：`插件页 → + 导入插件 → 选择该 zip → 确认权限 → 启用`。
安装后插件详情页本身就能打开 WebView 页面；**不打宿主补丁也能用**。

---

## 快速开始

### 1. 拿到插件包（推荐直接下载，本地构建可选）

按上一节下载现成的 ZIP 即可；若要在本地重建：

```bash
node scripts/build.mjs
# → dist/orangechat-masquerade-1.2.0.zip
```

零第三方依赖，只需要 Node ≥ 18。构建脚本自带 CRC 回读校验，也可用 Python 交叉验证：

```bash
python -c "import zipfile; z=zipfile.ZipFile('dist/orangechat-masquerade-1.2.0.zip'); print(z.testzip(), len(z.namelist()))"
```

### 2. 让入口出现在聊天页加号里（可选补丁）

```bash
node host-patch/generate.mjs \
  --src=<你的 orangechat>/app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt
# 或直接应用现成的 unified diff：
git apply host-patch/FilesPicker.kt.patch
```

补丁是**可插拔**的：按钮由 `PluginManager.plugins` 判断，只有装好且启用 `com.orangechat.masquerade` 时才渲染；卸载插件按钮自动消失，不会留一个点了没反应的空壳。详见 [host-patch/README.md](host-patch/README.md)。

### 3. 用 MCP 形态（可选）

```bash
node mcp/server.mjs
```

客户端配置见 [mcp/README.md](mcp/README.md)。MCP 形态里 **LLM 本身就是舞伴**：server 只负责状态、角色卡、剧中提示词与落幕说明，不联网、不读取任何 API Key。

### 4. 不装 App，先看效果

直接用浏览器打开 [`studio/index.html`](studio/index.html)。左侧是插件真实界面，右侧演示启幕/落幕卡片如何出现在聊天上下文里。演示模式使用本地剧本，**不联网、不调用真实模型**，数据只存浏览器 localStorage。

---

## 先配置模型（唯一入口在宿主）

到橘瓣 **设置 → 插件管理 → 假面舞会** 填写 `模型 API Key`、`接口地址`、`模型 ID`，或选好 `备用模型`，保存后重新打开插件页。插件页「模型接入」只**只读展示状态**，不提供第二套配置，避免改了这边、那边还是旧值。

剧中台词与落幕压缩**共用这一个模型通道**（不额外引入第二个模型），仅 HTTPS + 域名白名单，密钥由宿主密码字段保存、永不回显。

> **没配置模型会怎样**：请柬仍能送进聊天、前端助手仍能应邀（这一步不依赖插件模型），但 `roleplay_turn` 会硬拦截并返回 `MODEL_NOT_CONFIGURED`，**不会生成任何剧中台词**，也不会编造「牛头不对马嘴」的回复。落幕压缩会明确降级标注为「本地摘录」，而不是假装是模型压的。

---

## 一场假面时刻的流程

```
idle ──masquerade_invite──▶ invited ──masquerade_accept──▶ active ──masquerade_end──▶ ended
                              │                                                        │
                              └── 未应邀就收回：cancelled（不写记忆、不发聊天卡）      └── 可再开一场
```

1. **递请柬**：选一张角色卡、写开场场景和自己戴的面具 → `invited`。请柬作为可见消息经 `Bridge.sendChatMessage` 发进主聊天（宿主内部以 `answer=true` 提交，会触发一次助手回复），**前端对话框里的助手就在那里应邀**；也可以在聊天里直接对助手说「我们玩一场假面舞会吧」，它会调用 `masquerade_invite` 工具应邀（由 `promptTemplate` 引导）。
2. **启幕**：`masquerade_accept` → `active`，把「【假面时刻 · 启幕】」卡片送进主聊天，并附一次性说明。
3. **剧中**：`roleplay_turn` 的每句台词只写插件内部 `dataStore` 的 `mb:transcript:*`，**不进主聊天历史、不写长期记忆**。
4. **落幕**：`masquerade_end` 用内置模型把整场戏压成一段**压缩概要**，作为落幕卡的**可见文本**送进助手最新有对话的会话；同时写档案、写一条游戏记忆，四条通道同时生效（见下）。

### 落幕后助手为什么一定知道「刚才是游戏」

| 通道 | 内容 | 生效范围 |
|---|---|---|
| 主聊天落幕卡（可见文本） | `【假面时刻 · 落幕】` + **压缩概要** + 「这是虚构游戏」标注 | **永久写入会话历史**，后续对话可见 |
| `transientSystemPrompt` | 完整落幕说明，经 `Bridge.sendChatMessage(id, text, hiddenContext)` 注入 | 本轮请求 |
| `memoryBank.save` | 同一份落幕说明，整场只写 **1 条** | 长期 |
| `promptTemplate` | 解释落幕卡里的概要只是虚构游戏剧情，不要当真实事实 | 每次对话（需 `inject_as_prompt=true`，默认开启） |

宿主 `sendChatMessage` 的可见文本会永久落进该会话历史，而 `hiddenContext` 只作用本轮，所以**压缩概要必须放在可见文本里**才能真正进入助手上下文。落幕卡只放概要、绝不放逐字台词，并自带 **「不是真实发生的事」「完整台词不进入本对话」** 标注。会话 ID 由 `listConversations()` 取 `messageCount > 0` 且 `updateAt` 最新的一条（宿主不把 `conversationId` 传进 WebView）。

落幕说明明确包含三句关键语义：**「角色扮演游戏」**、**「不是真实发生的事」**、**「平时依旧是原本的真实情感关系」**，并要求之后不沿用剧中人设、不把剧情当真实经历。

### 演绎档案

每场落幕都会把完整逐轮台词、压缩概要、压缩来源与落幕说明存进插件私有 `dataStore`（最多 20 场）。插件页第 4 个 tab「演绎档案」可列表回看、导出 JSON、二次确认删除。档案**不进主聊天、不写长期记忆**，列表摘要也不含逐字台词。

---

## 内置能力

- **角色卡**（参考 Mufy）：身份、性格、小剧场 / 场景、开场白、世界观、样例对话、边界、标签、可见性。内置 3 张示例卡，支持增删改查与 JSON 导入导出。
- **模型接入**：`api_base`（OpenAI 兼容，到 `/v1`）、`api_key`、`model_id`、`chat_model`、`max_turns`，全部在宿主插件详情页配置。仅 HTTPS + 域名白名单；`get_model_config` 只读脱敏。
- **落幕压缩 + 演绎档案**：压缩用插件内置的那个模型；模型不可用时降级本地摘录并明确标注。
- **少女感专属 UI**：玫瑰粉 / 香槟金 / 象牙白 / 深莓色，本地打包的 CC0 / MIT / OFL 素材与自绘金色假面，运行时不请求外部 CDN。来源见 [plugin/ui/assets/ATTRIBUTION.md](plugin/ui/assets/ATTRIBUTION.md)。

---

## 验证

```bash
node --test tests/*.test.mjs     # 41 个用例：状态机、隔离、密钥、XSS、契约、MCP、压缩、档案
node scripts/build.mjs           # ZIP 构建 + CRC 回读（可复现）
node audit/audit.mjs             # 三轮安全审计 / 代码审查，生成 audit/AUDIT_REPORT.md
node scripts/verify-package.mjs  # 宿主同款流式导入自检；也可传入任意 ZIP 路径排障
npm run verify                   # 以上全部
```

审计报告与已知限制见 [audit/AUDIT_REPORT.md](audit/AUDIT_REPORT.md) 与 [SECURITY.md](SECURITY.md)。

---

## 目录

```
plugin/        manifest.json + main.js（QuickJS ES5 子集）+ ui/（专属 WebView 页面）
mcp/           stdio MCP server，vm 复用 main.js
studio/        浏览器交互预览页
host-patch/    宿主加号按钮补丁生成脚本、完整文件、unified diff
scripts/       零依赖 ZIP 构建（CRC 回读校验）+ 宿主同款导入自检
tests/         核心 / 契约 / MCP 冒烟测试
audit/         三轮审计脚本与报告
dist/          可导入的插件 ZIP
```

设计取舍与数据通道详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 许可

本子项目原创代码：MIT（见 [LICENSE](LICENSE)）。第三方素材各自许可见 `plugin/ui/assets/ATTRIBUTION.md`。

**注意**：宿主橘瓣基于 **GNU AGPL v3**。`host-patch/` 下的 `FilesPicker.kt` 与 `.patch` 是宿主 AGPL 源文件的衍生，仍受 AGPL-3.0 约束，不适用 MIT；把插件与宿主一起分发或编译时，组合作品需遵循 AGPL-3.0。
