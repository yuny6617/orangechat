# 安全说明 · 假面舞会

> 适用范围：本仓库的插件包、MCP server、宿主补丁与预览页。这是一份**有范围的安全说明**，
> 不覆盖橘瓣宿主全量代码、Android 运行时或第三方模型服务。逐条自动检测结果见
> [`audit/AUDIT_REPORT.md`](audit/AUDIT_REPORT.md)。

## 1. 威胁模型

| 威胁 | 处置 |
|---|---|
| 插件越权访问宿主凭据 | 只申请 `ai_chat` + `conversation_sync`，不申请 `provider_credentials` |
| 模型请求被劫持到任意服务器 | HTTPS 强制 + `manifest.allowedHosts` 与 `main.js` 内置白名单双重校验；禁止 `"*"` |
| API Key 泄露到页面 / 日志 / 角色卡 | `api_key` 为 `password` 配置；`get_model_config()` 只返回 `hasKey`，不返回原文 |
| 剧中内容污染真实关系记忆 | 剧中台词只写 `dataStore: mb:transcript:*`，不入主聊天历史、不入 `memoryBank` |
| 助手把角色扮演当真 | 四条冗余通道（promptTemplate / transientSystemPrompt / 落幕记忆 / 落幕卡） |
| WebView XSS | 所有动态渲染用 `textContent` 或 `esc()`；无 `eval` / `new Function`；无外链资源 |
| MCP server 偷偷联网或读密钥 | 沙箱注入 `noNetworkFetch`；`config` 不含 `api_key`；无子进程 / `node:https` |
| 落盘路径逃逸 | `MASQUERADE_STORE_FILE` 必须是绝对路径且不含 `..`；文件 `mode 0600`；写入用临时文件 + rename |
| 畸形 / 恶意参数导致崩溃 | `safeString()` 只接受标量，带坏 `toString` 的对象一律当空值；48 组异常输入零未捕获异常 |
| 供应链 | `package.json` 零依赖；ZIP 由本仓库脚本生成并做 CRC 回读 + Python 独立解析校验 |

## 2. 权限

```json
"permissions": ["ai_chat", "conversation_sync"]
```

- `ai_chat`：仅用于 `Bridge.callAI` 生成一句应邀台词。
- `conversation_sync`：仅用于 `Bridge.listConversations` / `sendChatMessage` 把启幕、落幕卡片送进当前聊天。
- 未申请 `provider_credentials`，插件不读取宿主已配置的模型凭据库；`chat_model` 只是宿主模型选择器的展示项。

## 3. 网络边界

`manifest.allowedHosts` 与 `main.js` 的 `ALLOWED_HOSTS` 必须逐项一致（审计 R1-04 强校验），共 14 个 HTTPS 模型域名。请求发起前依次校验：

1. `api_base` 必须以 `https://` 开头，否则 `INSECURE_BASE_URL`；
2. 解析出的 host 必须在白名单内，否则 `HOST_NOT_ALLOWED`；
3. 上述任一失败都**不发起请求**（审计 R1-03 在沙箱里核对 `requests.length === 0`）。

白名单为空时宿主默认是放行的，所以本项目显式列全域名并在插件内二次校验，不依赖宿主兜底。

## 4. 数据处理

- 角色卡、剧本、会话状态全部存在宿主 `dataStore` 的 `mb:*` 命名空间下，不上传、不落到聊天记录。
- `memoryBank` 整场只写 **1 条**落幕说明；`invited` 状态收回请柬时写 **0 条**。
- MCP 形态落盘到 `~/.orangechat-masquerade/store.json`（可用 `MASQUERADE_STORE_FILE` 覆盖），`0600` 权限，原子写。
- 预览页 `studio/index.html` 只用 localStorage + 本地演示剧本，不发任何网络请求。

## 5. 自动审计

```bash
node audit/audit.mjs             # 三轮 30 项，逐条实跑
node audit/audit.mjs --round=1   # 只跑某一轮（懒执行，不会连带触发构建副作用）
```

- 第 1 轮：信任边界与数据隔离（12 项）
- 第 2 轮：契约、健壮性与工程规范（10 项）
- 第 3 轮：可构建、可预览、可追溯（8 项）

检测器自带 17 组正反样本自测（`selfTest()`），故意构造硬编码密钥、通配符白名单、未转义 `innerHTML`、`map` 回调内的裸插值等坏样本，确保规则本身不会假阴性放行。任一检查 FAIL 即退出码 1。

## 6. 已知限制（needs validation）

- 未在本地执行 **Gradle 编译**与 **Android 真机 WebView 运行**。宿主补丁只使用 `FilesPicker.kt` / `ChatDrawer.kt` 中已存在的 import 与 API，编译与 UI 落地由 PR 的 CI 与真机验收兜底。
- `Bridge.sendChatMessage` 的 `transientSystemPrompt` 「只对本次请求生效」来自宿主源码核对，未做真机验证。
- 模型接口只做本地 mock 行为验证，**未对任何真实供应商发起请求**，不消耗额度、不接触真实密钥。
- WebView 在 `file://` 下的字体加载、iframe 跨源 `postMessage` 已做降级设计，真机表现需宿主侧验收。
- 本审查是 scoped / quick 级静态与沙箱验证，不含动态渗透测试、不含对宿主全量代码的审计。

## 7. 报告问题

如发现越权、密钥泄露、落幕语义失效或 XSS，请在本仓库提 issue，附上复现步骤与 `node audit/audit.mjs` 的输出；涉及密钥请**不要**粘贴真实凭据。
