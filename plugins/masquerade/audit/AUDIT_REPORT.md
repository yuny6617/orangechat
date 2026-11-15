# 假面舞会 · 三轮安全审计 / 代码审查报告

- 生成方式：`node audit/audit.mjs`，逐条实跑，非人工声明。
- 审查对象：`orangechat-masquerade` v1.0.0（插件包 + MCP server + 宿主补丁 + 预览页）。
- 审查方式：静态源码扫描 + `vm` 沙箱行为验证 + 子进程冒烟 + ZIP 独立解析器交叉校验。
- 结论：**三轮全部通过**。

> 覆盖声明：这是针对本仓库的**有范围审查**（scoped / quick 级），不是对橘瓣宿主全量代码或 Android 运行时的完整审计。未执行真机 WebView 运行、Gradle 编译、动态渗透或第三方模型接口联调，相关项列在文末「限制」。

## 第 1 轮 · 安全审计 · 信任边界与数据隔离（12/12）

| 编号 | 检查项 | 结果 | 实际核对内容 |
|---|---|---|---|
| R1-01 | 生产代码无硬编码密钥/凭据 | PASS | 4 个生产 JS 文件扫描通过 |
| R1-02 | 模型配置返回值不泄露 API Key | PASS | hasKey=true 且返回值不含密钥原文 |
| R1-03 | 模型请求强制 HTTPS 且拒绝非白名单域名 | PASS | 明文 HTTP → INSECURE_BASE_URL；非白名单 → HOST_NOT_ALLOWED；两者均未发出请求 |
| R1-04 | 网络白名单无通配符且与 main.js 内置白名单一致 | PASS | 14 个域名，两处一致，无 "*" |
| R1-05 | 权限声明最小化 | PASS | permissions = ai_chat,conversation_sync；未申请 provider_credentials |
| R1-06 | 剧中对话不污染长期记忆，落幕只写一条游戏声明 | PASS | 剧中 0 条记忆；落幕 1 条，含全部三项语义，且等于一次性 transientSystemPrompt |
| R1-07 | 系统提示词注入默认开启且明确游戏/真实关系 | PASS | PluginToolProvider 仅在 inject_as_prompt=true 时注入 promptTemplate |
| R1-08 | WebView 页面无外链资源、无 eval / new Function | PASS | 本地资源 5 个文件、4 个 JS 均无外部引用与动态执行 |
| R1-09 | UI 动态渲染均经转义或 textContent | PASS | innerHTML 动态插值全部为 esc(...) 或显式标注的 safe 片段；气泡与聊天流用 textContent |
| R1-10 | MCP server 不联网、不执行外部命令、日志只走 stderr | PASS | 沙箱 fetch 绑定 noNetworkFetch；config 不含 api_key；无子进程/网络模块；stdout 仅承载 JSON-RPC |
| R1-11 | MCP 存储路径拒绝相对路径与 .. | PASS | 源码有路径守卫；相对路径启动被拒绝（exit 1） |
| R1-12 | 第三方素材许可与来源已声明 | PASS | 4 个本地素材非空，许可覆盖 CC0 / MIT / OFL |

### 本轮发现与修复

- **R1-12 / 素材许可标识**：ATTRIBUTION.md 原先只写「SIL Open Font License 1.1」，未出现 OFL 缩写，许可校验判定失败且失败原因被硬编码的 detail 掩盖。已在表格补注「OFL-1.1」，并把该检查改为逐项报出缺失的许可标识与空文件，不再写死「全部通过」。
- **R1-10 / MCP 网络边界**：初版 MCP server 曾保留未使用的 fetch 常量与 STRING_ARG 占位函数，存在「看似有网络能力」的误导面。已删除死代码并注入 noNetworkFetch，使其只能显式失败。

## 第 2 轮 · 代码审查 · 契约、健壮性与工程规范（10/10）

| 编号 | 检查项 | 结果 | 实际核对内容 |
|---|---|---|---|
| R2-01 | main.js 严格限定 QuickJS ES5 子集 | PASS | 无 let/const/箭头/模板字符串/async/Promise/class/展开运算符 |
| R2-02 | manifest.tools 与 exports 一一对应 | PASS | 12 个工具，声明=导出 |
| R2-03 | 所有工具返回统一 {success, error} 结构 | PASS | 12 个工具空参调用，异常/结构不符：无 |
| R2-04 | 异常与恶意输入不导致未捕获异常 | PASS | 48 组异常输入，未捕获异常 0 次 |
| R2-05 | 生产代码无 TODO / 调试日志残留 | PASS | 4 个生产 JS 无 TODO/FIXME/console.log/debugger |
| R2-06 | 单文件规模在可审查范围内 | PASS | 8 个文件均 < 1000 行；最大 633 行 |
| R2-07 | 零第三方依赖，状态机只有一份实现 | PASS | package.json 无依赖；MCP 通过 vm 复用 plugin/main.js，未复制状态机 |
| R2-08 | 宿主补丁可自包含复现：锚点唯一、只改一个文件、按钮条件渲染 | PASS | 用仓库内原始快照重跑，输出与产物逐字一致；diff 仅含 FilesPicker.kt；入口由插件 ID + isEnabled 条件渲染；无中间文件残留 |
| R2-09 | 测试覆盖关键不变量（隔离/密钥/XSS/状态机） | PASS | 3 个测试文件，覆盖 5 类关键不变量 |
| R2-10 | 检测器自身无假阴性（正反样本自测） | PASS | 17 组正反样本全部符合预期 |

### 本轮发现与修复

- **R2-03 / 返回值结构**：roleplay_turn 失败分支曾同时出现重复 error 键，且在 invited 状态直接落幕会误写一条「游戏记忆」。已清理重复键，并把 invited 的收回改为 cancelled 分支（不写记忆、不发聊天卡）。
- **R2-01 / 状态机健壮性**：单测暴露两个真实缺陷：先建卡再列卡会丢三张内置卡（改为独立 seeded 哨兵）；进行中再次邀请先报「角色卡不存在」而非「已有进行中」（把状态检查提前）。两处均已修复并补回归用例。
- **R2-04 / 畸形输入健壮性**：首轮审计实跑发现 4 次未捕获异常：`String({ toString: null })` 会抛 TypeError，`get_role_card` / `update_role_card` / `delete_role_card` / `masquerade_invite` 读到畸形 card_id 时直接崩。已新增 `safeString()` 只接受标量（对象/数组/坏 toString 一律当空值），并让 `escapeHtml` 复用；48 组异常输入复测 0 次未捕获异常，核心单测 13/13 仍通过。
- **R2-08 / 补丁复现的隐藏依赖**：上传到宿主仓库时把补丁后文件覆盖到了本地参考副本，R2-08 随即 FAIL，暴露出真实问题：`generate.mjs` 默认源是仓库外的 `_ref/orangechat/...`，用户 clone 后 `npm run verify` 必然失败，审计结果不可复现。已把原始宿主文件快照 `FilesPicker.kt.orig` 随仓库提供作为默认输入，新增 `--out` 支持，并加入「输入已是补丁后版本」的显式报错；R2-08 改为在临时目录重生成后逐字比对产物，且失败时逐条报出问题而非硬编码「成功」。此外规则改为懒执行 `evaluate()`（`--round=1` 不再连带触发构建），生成脚本默认不留中间文件，`innerHTML` 检测器升级为括号/引号平衡抓取整条赋值语句。

## 第 3 轮 · 交付审查 · 可构建、可预览、可追溯（9/9）

| 编号 | 检查项 | 结果 | 实际核对内容 |
|---|---|---|---|
| R3-01 | 全量单元测试通过 | PASS | pass=23, fail=0 |
| R3-02 | 插件 ZIP 可构建并通过 CRC 回读 | PASS | dist/orangechat-masquerade-1.0.0.zip 135612 B |
| R3-03 | ZIP 内容与必需文件齐全（独立解析器交叉验证） | PASS | Python zipfile.testzip 通过，条目 11 |
| R3-04 | 所有 HTML/CSS 本地引用真实存在 | PASS | 无占位符 URL，引用全部命中本地文件 |
| R3-05 | 专属 UI 四个状态视图与三个功能区齐全 | PASS | 12 个关键节点齐全，缺：无 |
| R3-06 | 浏览器预览页可独立打开 | PASS | 复用同一套 CSS/JS/main.js，右侧演示聊天上下文里的专属卡片 |
| R3-07 | 交付文档齐全 | PASS | 7 份文档齐全，缺：无 |
| R3-08 | 端到端落幕语义：助手必须知道刚才是游戏 | PASS | 启幕卡 / 落幕卡 / transientSystemPrompt / 长期记忆四条通道一致 |
| R3-09 | 许可声明明确区分插件 MIT 与宿主 AGPL-3.0 | PASS | LICENSE / README 均声明宿主 AGPL-3.0，并指出 host-patch 衍生文件与组合作品的约束 |

### 本轮发现与修复

- **R3-09 / 许可合规**：远程回读宿主 `FilesPicker.kt` 时确认橘瓣基于 GNU AGPL v3，而子项目 LICENSE 初版只写「上游许可继续适用」，容易让人误以为补丁文件可按 MIT 任意使用。已明确区分：原创文件 MIT、`host-patch/` 衍生文件受 AGPL-3.0 约束、与宿主一起分发时组合作品遵循 AGPL-3.0，并把该声明纳入新增的 R3-09 自动检查。
- **R3-02 / 宿主可插拔性**：初版加号按钮是硬编码常驻，不满足「装了插件才显示」。已改为读 PluginManager.plugins 并按插件 ID + isEnabled 条件渲染；生成脚本同时适配 CRLF 换行，避免整文件 diff。

## 复验

- 三轮共 31 条检查，逐条重跑；任一 FAIL 即退出码 1。
- 检测器 `selfTest()` 用 17 组正反样本验证规则本身不产生假阴性（例如故意写入硬编码密钥、未转义 innerHTML、通配符白名单、map 回调内的裸插值时必须报错）。
- 修复后回归：`node --test tests/*.test.mjs` + `node scripts/build.mjs` + `python zipfile.testzip()` 全部通过。

## 限制（needs validation）

- 未在本地执行 Gradle 编译与 Android 真机运行：宿主补丁只使用 `FilesPicker.kt` 与 `ChatDrawer.kt` 已有的 import / API，编译与 UI 落地由 PR 的 CI 与真机验收兜底。
- `Bridge.sendChatMessage` 的 `transientSystemPrompt` 只对本轮请求生效这一行为来自宿主源码 `PluginWebViewPage.kt`，已在源码层核对，未做真机验证。
- 模型接口只做了本地 mock 行为验证，未对任何真实供应商发起请求（不消耗额度、不接触真实密钥）。
- WebView 在 `file://` 下的字体加载、iframe 跨源 postMessage 回退已在预览页做降级设计，真机表现需宿主侧验收。
