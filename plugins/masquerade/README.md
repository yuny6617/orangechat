# 假面舞会 · Masquerade for OrangeChat

> 一个可插拔的「假面时刻」：从聊天页加号进入，邀请助手戴上面具，在专属舞台上角色扮演；
> 摘下面具时，助手清楚地知道刚才那是一场游戏，你们平时依旧是原本的真实情感关系。

本项目为 [yuny6617/orangechat](https://github.com/yuny6617/orangechat)（橘瓣 / RikkaHub）提供三种可独立使用的形态。在宿主仓库中它位于 `plugins/masquerade/`，是一个自成一体的子项目；本目录下的 `dist/*.zip` 已随仓库提供，可直接下载导入，无需自行构建。



| 形态 | 产物 | 用途 |
|---|---|---|
| 橘瓣插件 | `dist/orangechat-masquerade-1.4.0.zip` | 装进 App，出现在聊天页加号面板，有专属 WebView UI |
| MCP server | `mcp/server.mjs` | stdio JSON-RPC，让任意 MCP 客户端的 LLM 直接当舞伴 |
| 宿主补丁 | `host-patch/FilesPicker.kt.patch` | 只改 1 个文件 +33 行，把入口放到「语音通话 / 视频通话 / 红包 / 转账」旁边 |

三种形态**共用同一份状态机与落幕语义**（`plugin/main.js`），MCP 通过 `vm` 加载它，不复制逻辑。

---

## 📱 装到手机：请认准这一个文件

> **真机踩过的坑**：在 GitHub 点 `Code → Download ZIP` 下载到的是**整个仓库源码包**，
> 其中 `manifest.json` 位于 `plugins/masquerade/plugin/manifest.json`（第 4 层）；
> 而宿主 `PluginScanner.findManifest()` 只查**根目录和一级子目录**，
> 导入必然报 **「导入失败: 找不到 manifest.json」**。

**唯一可导入的文件**：`dist/orangechat-masquerade-1.4.0.zip`

- 一键下载（本地产物 211851 字节，`Content-Type: application/zip`）：
  <https://raw.githubusercontent.com/yuny6617/orangechat/feature/masquerade-integration/plugins/masquerade/dist/orangechat-masquerade-1.4.0.zip>
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
# → dist/orangechat-masquerade-1.4.0.zip
```

零第三方依赖，只需要 Node ≥ 18。构建脚本自带 CRC 回读校验，也可用 Python 交叉验证：

```bash
python -c "import zipfile; z=zipfile.ZipFile('dist/orangechat-masquerade-1.4.0.zip'); print(z.testzip(), len(z.namelist()))"
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

## 一场假面时刻的流程（1.4.0：直接在和助手聊天时玩）

```
聊天里说一句 ──▶ 助手调用 masquerade_invite ──▶ 请柬卡原样输出进对话（invited）
                                                        │
                         「我愿意」→ masquerade_accept → 启幕卡进对话（active）
                         「摘下面具」→ masquerade_end → 落幕卡（只含压缩概要）进对话（ended）
```

### 主路径：让助手自己调工具应邀

宿主 `ToolSurfaceBuilder.kt` 会把插件 `manifest.tools` 注册给主聊天 LLM——助手**本来就能调用假面舞会工具**。1.4.0 把「什么时候必须调用」和「调用后必须把哪段文本原样输出」写进了**工具描述**（`PluginToolProvider.buildDescription()` 会把 `toolDef.description` 原样发给模型，这是最可靠、不依赖任何开关的通道）：

| 你在聊天里说 | 助手必须做的事 |
|---|---|
| 「我们玩一场假面舞会吧 / 想邀请你进入假面时刻 / 戴上面具陪我演一场」 | 调用 `masquerade_invite`，把返回的 `replyToUser`（【假面时刻 · 请柬】）**一字不改**作为回复输出 |
| 「我愿意 / 同意赴约 / 开始吧 / 启幕」 | 调用 `masquerade_accept`，把 `replyToUser`（【假面时刻 · 启幕】）**一字不改**输出 |
| 「摘下面具 / 落幕 / 结束假面舞会」 | 调用 `masquerade_end`，把 `replyToUser`（落幕卡，只含压缩概要）**一字不改**输出 |
| 「不要了 / 婉拒」 | 调用 `masquerade_end`，输出婉拒卡并明确「这一场没有开始」 |

工具结果在橘瓣里是折叠的「调用工具 xxx」块，**用户看得见的是助手的回复文本**，所以只有让助手把 `replyToUser` 原样说出来，邀约才真正写进对话。工具描述同时禁止输出 JSON、复述工具名或改成「好呀 / 能看到」这类自然语言。三个工具的返回值都带 `replyToUser` 与 `replyInstruction`，双重约束。

> **两个宿主细节**：① `ToolNaming.buildPluginToolName()` 会给工具名加 `plg_<8位hash>_` 前缀，助手按 tools 定义里的实际名称调用，`promptTemplate` 已写明；② 插件工具 `needsApproval = true`，首次调用会出现批准框，点允许即执行。
>
> **关于 `inject_as_prompt`**：宿主 `getPluginPromptInjections()` 只读 `plugin.config` 里**用户显式保存**的值（`PluginManager` 用的是 `savedConfig`，`manifest.config` 的 `default: true` 不会自动落库）。若想让长期语义规则也注入系统提示词，到宿主「设置 → 插件管理 → 假面舞会」把「把假面舞会规则注入系统提示词」打开**并保存一次**。**核心触发条件与输出契约全部放在工具描述里，不打开这个开关也能玩。**

### 插件页面（可选，1.4.0 起不再有弹窗）

真机截图显示全屏邀请函弹窗被压成一条裁切胶囊（`min()` / `dvh` / 背景模糊滤镜在 WebView 上兼容不一致），按反馈**彻底撤销**：弹窗 DOM、CSS、JS 全部删除，「同意赴约」「婉拒」两个按钮直接平铺在页面上，各有文风提示语。页面仍会尝试把请柬文字 + 邀请函 PNG 发进聊天（保留送达回执），但**同步失败不再回滚 `idle`**，只如实标注 `✗ 未送达` 并保留 `invited`——你仍可在页面里同意/婉拒，也可以点「复制请柬文本，贴进聊天」把请柬贴给助手，它会按上表调工具应邀。

剧中台词仍只在插件专属舞台里进行（不进主聊天、不写长期记忆）；落幕时生成与 UI 同风格的**概要卡片**、写档案、写一条游戏记忆。

### 送达回执与会话选择

| 项目 | 行为 |
|---|---|
| 判定标准 | `sendChatMessage` 返回 `success === true` 才算送达；`undefined` / 空回执 / `success:false` 一律算失败 |
| 会话来源 | 宿主不把 `conversationId` 传进 WebView，唯一通道是 `listConversations()`（按 `updateAt` 倒序） |
| 请柬 / 应邀 / 婉拒 | `target=current` → 取列表**第一条**，即用户最近所在会话（**包括还没有消息的新会话**） |
| 落幕压缩概要 | `target=latestActive` → 取 `messageCount > 0` 的最新一条，即「助手最新有对话的上下文」 |
| 失败可见 | 权限被拒、列表为空、发送被拒都带出原始原因；页面显示 `✗ 未送达：…` 与重试按钮 |

1.2.0 的断点在于先过滤 `messageCount>0` 再取第一条：从聊天页加号进入的是一个刚建的空会话，请柬被投到了另一个会话，用户在当前聊天里自然看不到。1.3.0 把两种目标拆开修掉了这一条；1.4.0 进一步让「同步失败」不再阻断玩法，并补了 25 条端到端链路测试（模拟宿主 Bridge 回调与 LLM 按工具名调用，真正执行 `app.js` 与工具返回值）。

### 落幕后助手为什么一定知道「刚才是游戏」

| 通道 | 内容 | 生效范围 |
|---|---|---|
| 主聊天落幕卡（可见文本） | `【假面时刻 · 落幕】` + **压缩概要** + 「这是虚构游戏」标注 | **永久写入会话历史**，后续对话可见 |
| `transientSystemPrompt` | 完整落幕说明，经 `Bridge.sendChatMessage(id, text, hiddenContext)` 注入 | 本轮请求 |
| `memoryBank.save` | 同一份落幕说明，整场只写 **1 条** | 长期 |
| `promptTemplate` | 解释落幕卡里的概要只是虚构游戏剧情，不要当真实事实 | 每次对话（需 `inject_as_prompt=true`，默认开启） |

宿主 `sendChatMessage` 的可见文本会永久落进该会话历史，而 `hiddenContext` 只作用本轮，所以**压缩概要必须放在可见文本里**才能真正进入助手上下文。落幕卡只放概要、绝不放逐字台词，并自带 **「不是真实发生的事」「完整台词不进入本对话」** 标注。落幕用 `target=latestActive` 取 `messageCount > 0` 且 `updateAt` 最新的一条；请柬则用 `target=current` 取列表第一条（宿主不把 `conversationId` 传进 WebView）。

### 落幕概要卡片与内容隔离

落幕后生成一张与邀请函同风格的**概要卡片**：只有压缩概要、角色、场景、轮次与压缩来源，卡片数据 `summaryCard` 里**没有 `transcript` 字段**。剧中具体内容不进入上下文——完整逐轮台词只能凭 `archiveId` 在插件「演绎档案」里查看，卡片上的「在插件内查看完整演绎」会直接跳过去。`chatCardText`（进聊天的可见文本）与概要卡片都不含逐字台词，审计 R1-20 用带模型的沙箱真实写一句台词后逐项核对。

落幕说明明确包含三句关键语义：**「角色扮演游戏」**、**「不是真实发生的事」**、**「平时依旧是原本的真实情感关系」**，并要求之后不沿用剧中人设、不把剧情当真实经历。

### 演绎档案

每场落幕都会把完整逐轮台词、压缩概要、压缩来源与落幕说明存进插件私有 `dataStore`（最多 20 场）。插件页第 4 个 tab「演绎档案」可列表回看、导出 JSON、二次确认删除。档案**不进主聊天、不写长期记忆**，列表摘要也不含逐字台词。

---

## 内置能力

- **角色卡**（参考 Mufy）：身份、性格、小剧场 / 场景、开场白、世界观、样例对话、边界、标签、可见性。内置 3 张示例卡，支持增删改查与 JSON 导入导出。
- **模型接入**：`api_base`（OpenAI 兼容，到 `/v1`）、`api_key`、`model_id`、`chat_model`、`max_turns`，全部在宿主插件详情页配置。仅 HTTPS + 域名白名单；`get_model_config` 只读脱敏。
- **主聊天工具应邀**：三个流程工具的描述自带触发条件与 `replyToUser` 原样输出契约，助手能自己把请柬 / 启幕 / 落幕卡写进对话；不依赖 `inject_as_prompt` 注入。
- **邀请函卡片（无弹窗）**：聊天里发「文字 + 本地 Canvas 绘制的邀请函 PNG」，插件页内是同款华丽邀请函（金色双框、角饰、丝带、封蜡印章、柔光动效），同意 / 婉拒按钮平铺，并支持一键复制请柬文本贴进聊天。Canvas 不可用时自动降级纯文本。
- **落幕压缩 + 概要卡片 + 演绎档案**：压缩用插件内置的那个模型；模型不可用时降级本地摘录并明确标注。概要卡片只放概要，具体内容仅在插件内查看。
- **少女感专属 UI**：玫瑰粉 / 香槟金 / 象牙白 / 深莓色，本地打包的 CC0 / MIT / OFL 素材与自绘金色假面，金线角饰、按钮流光、卡片层次与微光动效；1.4.0 撤掉了真机上会塌陷的全屏弹窗，运行时不请求外部 CDN。来源见 [plugin/ui/assets/ATTRIBUTION.md](plugin/ui/assets/ATTRIBUTION.md)。

---

## 验证

```bash
node --test tests/*.test.mjs     # 66 个用例：状态机、隔离、密钥、XSS、契约、MCP、压缩、档案、端到端送达、工具输出契约
node scripts/build.mjs           # ZIP 构建 + CRC 回读（可复现）
node audit/audit.mjs             # 三轮 51 项安全审计 / 代码审查，生成 audit/AUDIT_REPORT.md
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
