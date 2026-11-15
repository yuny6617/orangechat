# 假面舞会 · Masquerade for OrangeChat

> 一个可插拔的「假面时刻」：从聊天页加号进入，邀请助手戴上面具，在专属舞台上角色扮演；
> 摘下面具时，助手清楚地知道刚才那是一场游戏，你们平时依旧是原本的真实情感关系。

本项目为 [yuny6617/orangechat](https://github.com/yuny6617/orangechat)（橘瓣 / RikkaHub）提供三种可独立使用的形态。在宿主仓库中它位于 `plugins/masquerade/`，是一个自成一体的子项目；本目录下的 `dist/*.zip` 已随仓库提供，可直接下载导入，无需自行构建。



| 形态 | 产物 | 用途 |
|---|---|---|
| 橘瓣插件 | `dist/orangechat-masquerade-1.0.0.zip` | 装进 App，出现在聊天页加号面板，有专属 WebView UI |
| MCP server | `mcp/server.mjs` | stdio JSON-RPC，让任意 MCP 客户端的 LLM 直接当舞伴 |
| 宿主补丁 | `host-patch/FilesPicker.kt.patch` | 只改 1 个文件 +33 行，把入口放到「语音通话 / 视频通话 / 红包 / 转账」旁边 |

三种形态**共用同一份状态机与落幕语义**（`plugin/main.js`），MCP 通过 `vm` 加载它，不复制逻辑。

---

## 快速开始

### 1. 构建可导入的插件包

```bash
node scripts/build.mjs
# → dist/orangechat-masquerade-1.0.0.zip
```

零第三方依赖，只需要 Node ≥ 18。构建脚本自带 CRC 回读校验，也可用 Python 交叉验证：

```bash
python -c "import zipfile; z=zipfile.ZipFile('dist/orangechat-masquerade-1.0.0.zip'); print(z.testzip(), len(z.namelist()))"
```

在橘瓣里按 `PLUGINS_GUIDE.md` 导入该 ZIP。安装并启用后，插件详情页本身就能打开 WebView 页面；**不需要打宿主补丁也能用**。

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

## 一场假面时刻的流程

```
idle ──masquerade_invite──▶ invited ──masquerade_accept──▶ active ──masquerade_end──▶ ended
                              │                                                        │
                              └── 未应邀就收回：cancelled（不写记忆、不发聊天卡）      └── 可再开一场
```

1. **递请柬**：选一张角色卡、写开场场景和自己戴的面具 → `invited`。
2. **等应邀**：页面用 `Bridge.callAI` 只生成一句应邀台词（不承载角色扮演主循环）。
3. **启幕**：`masquerade_accept` → `active`。此时把一张「【假面时刻 · 启幕】」专属卡片送进主聊天上下文，并附一次性说明。
4. **剧中**：`roleplay_turn` 的每句台词只写插件内部 `dataStore` 的 `mb:transcript:*`，**不进主聊天历史、不写长期记忆**。
5. **落幕**：`masquerade_end` 生成落幕说明，四条通道同时生效（见下）。

### 落幕后助手为什么一定知道「刚才是游戏」

| 通道 | 内容 | 生效范围 |
|---|---|---|
| 主聊天落幕卡 | `【假面时刻 · 落幕】…` | 用户可见 |
| `transientSystemPrompt` | 完整落幕说明，经 `Bridge.sendChatMessage(id, text, hiddenContext)` 注入 | 本轮请求 |
| `memoryBank.save` | 同一份落幕说明，整场只写 **1 条** | 长期 |
| `promptTemplate` | 「假面舞会是游戏；落幕后不要把剧中设定当真实事实；平时仍是真实关系」 | 每次对话（需 `inject_as_prompt=true`，默认开启） |

落幕说明明确包含三句关键语义：**「角色扮演游戏」**、**「不是真实发生的事」**、**「平时依旧是原本的真实情感关系」**，并要求之后不沿用剧中人设、不把剧情当真实经历。

---

## 内置能力

- **角色卡**（参考 Mufy）：身份、性格、小剧场 / 场景、开场白、世界观、样例对话、边界、标签、可见性。内置 3 张示例卡，支持增删改查与 JSON 导入导出。
- **模型接入**：`api_base`（OpenAI 兼容，到 `/v1`）、`api_key`（宿主密码字段保存，页面不回显）、`model_id`、`chat_model`、`max_turns`。仅 HTTPS + 域名白名单。
- **少女感专属 UI**：玫瑰粉 / 香槟金 / 象牙白 / 深莓色，本地打包的 CC0 / MIT / OFL 素材与自绘金色假面，运行时不请求外部 CDN。来源见 [plugin/ui/assets/ATTRIBUTION.md](plugin/ui/assets/ATTRIBUTION.md)。

---

## 验证

```bash
node --test tests/*.test.mjs     # 23 个用例：状态机、隔离、密钥、XSS、契约、MCP 冒烟
node scripts/build.mjs           # ZIP 构建 + CRC 回读
node audit/audit.mjs             # 三轮安全审计 / 代码审查，生成 audit/AUDIT_REPORT.md
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
scripts/       零依赖 ZIP 构建（CRC 回读校验）
tests/         核心 / 契约 / MCP 冒烟测试
audit/         三轮审计脚本与报告
dist/          可导入的插件 ZIP
```

设计取舍与数据通道详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 许可

代码：MIT（见 [LICENSE](LICENSE)）。第三方素材各自许可见 `plugin/ui/assets/ATTRIBUTION.md`。
