# 安全说明 · 假面舞会

> 适用范围：本仓库的插件包、MCP server、宿主补丁与预览页。这是一份**有范围的安全说明**，
> 不覆盖橘瓣宿主全量代码、Android 运行时或第三方模型服务。逐条自动检测结果见
> [`audit/AUDIT_REPORT.md`](audit/AUDIT_REPORT.md)。

## 1. 威胁模型

| 威胁 | 处置 |
|---|---|
| 插件越权访问宿主凭据 | 只申请 `conversation_sync`，不申请 `provider_credentials`；请柬改走主聊天后不再需要 `ai_chat` |
| 模型请求被劫持到任意服务器 | HTTPS 强制 + `manifest.allowedHosts` 与 `main.js` 内置白名单双重校验；禁止 `"*"`；剧中台词与落幕压缩共用同一条通道、同一套校验 |
| API Key 泄露到页面 / 日志 / 角色卡 | `api_key` 为宿主 `password` 配置项；插件页**没有**第二套配置表单，也没有独立 KV，只读展示「已配置 / 未配置」；`get_model_config` 永远不返回原文 |
| 剧中内容污染真实关系记忆 | 剧中台词只写 `dataStore: mb:transcript:*`，不入主聊天历史、不入 `memoryBank`；进入会话的只有压缩概要，且自带虚构标注 |
| 助手把角色扮演当真 | 四条冗余通道（promptTemplate / transientSystemPrompt / 落幕记忆 / 落幕卡含概要） |
| 未配置模型却「假装在演」 | 三态 `modelAvailability()`：无模型时 `roleplay_turn` 返回 `MODEL_NOT_CONFIGURED`，零台词、零网络请求；压缩降级为本地摘录并标注 |
| 请柬「假成功」：页面说已发送、聊天里没有 | 回执驱动：`sendChatMessage` 只认 `success===true`；`delivery` 三态随状态持久化；失败回滚 `idle` 并显示原始错误与重试入口；页面无写死的「已送达」标题 |
| 请柬被投到错误的会话 | 目标拆分：`current`（含无消息的新会话）用于请柬/应邀/婉拒，`latestActive` 用于落幕概要；`listConversations` 返回 `success:false` 时明确报因 |
| 同意 / 婉拒只在插件内自嗨，助手收不到 | 两种选择都生成可见确认消息送进聊天（宿主 `answer=true` 触发助手回复）；婉拒不写记忆、不产生剧情 |
| 剧中具体内容泄漏进上下文 | `summaryCard` 无 `transcript` 字段；落幕可见文本只含压缩概要；完整台词只能凭档案 ID 在插件内查看 |
| 档案越权读 / 路径式 ID | `parseArchiveId()` 要求原始值为字符串且匹配 ID 正则，拒绝数字与带 `toString` 的对象；档案只读插件私有 `dataStore` |
| WebView XSS | 所有动态渲染用 `textContent` 或 `esc()`；无 `eval` / `new Function`；无外链资源 |
| MCP server 偷偷联网或读密钥 | 沙箱注入 `noNetworkFetch`；`config` 不含 `api_key`；无子进程 / `node:https` |
| 落盘路径逃逸 | `MASQUERADE_STORE_FILE` 必须是绝对路径且不含 `..`；文件 `mode 0600`；写入用临时文件 + rename |
| 畸形 / 恶意参数导致崩溃 | `safeString()` 只接受标量，带坏 `toString` 的对象一律当空值；60 组异常输入零未捕获异常 |
| 供应链 | `package.json` 零依赖；ZIP 由本仓库脚本生成并做 CRC 回读 + Python 独立解析校验 |

## 2. 权限

```json
"permissions": ["conversation_sync"]
```

- `conversation_sync`：`Bridge.listConversations` / `sendChatMessage`，把请柬、启幕、落幕卡送进当前聊天；`sendChatMessage` 会触发一次助手回复，这是「前端对话框里的助手本人应邀」的唯一通道。
- `ai_chat` 已移除：旧版用 `Bridge.callAI` 在独立请求里生成应邀台词，主聊天助手全程没参与；1.2.0 起不再调用它。
- 未申请 `provider_credentials`，插件不读取宿主已配置的模型凭据库；`chat_model` 只是宿主模型选择器的展示项。

## 3. 网络边界

`manifest.allowedHosts` 与 `main.js` 的 `ALLOWED_HOSTS` 必须逐项一致（审计 R1-04 强校验），共 14 个 HTTPS 模型域名。请求发起前依次校验：

1. `api_base` 必须以 `https://` 开头，否则 `INSECURE_BASE_URL`；
2. 解析出的 host 必须在白名单内，否则 `HOST_NOT_ALLOWED`；
3. 上述任一失败都**不发起请求**（审计 R1-03 在沙箱里核对 `requests.length === 0`）。

白名单为空时宿主默认是放行的，所以本项目显式列全域名并在插件内二次校验，不依赖宿主兜底。

## 4. 数据处理

- 角色卡、剧本、会话状态、演绎档案全部存在宿主 `dataStore` 的 `mb:*` 命名空间下，不上传、不落到聊天记录。
- `memoryBank` 整场只写 **1 条**落幕说明；`invited` 状态收回请柬时写 **0 条**。
- 档案最多保留 20 场，索引与数据同步裁剪，避免无主数据长期滞留；档案列表摘要不含逐字台词。
- MCP 形态落盘到 `~/.orangechat-masquerade/store.json`（可用 `MASQUERADE_STORE_FILE` 覆盖），`0600` 权限，原子写。
- 预览页 `studio/index.html` 只用 localStorage + 本地演示剧本，不发任何网络请求；预览垫片显式注入 `demo: true`，**不注入任何伪造密钥**，因此「预览能演」不会被误认为「真机已配置」。

## 5. 自动审计

```bash
node --test tests/*.test.mjs     # 55 个用例；其中 14 条端到端链路测试真正执行 app.js
node audit/audit.mjs             # 三轮 48 项，逐条实跑
node scripts/verify-package.mjs  # 宿主同款导入自检（也可传入任意 ZIP 路径排障）
node audit/audit.mjs --round=1   # 只跑某一轮（懒执行，不会连带触发构建副作用）
```

- 第 1 轮：信任边界与数据隔离（20 项，新增送达回执、会话选择、同意/婉拒确认、概要卡片隔离）
- 第 2 轮：契约、健壮性与工程规范（15 项，新增端到端链路实跑、卡片/弹窗转义与无外链约束）
- 第 3 轮：可构建、可预览、可追溯（13 项，新增邀请函弹窗与落幕概要卡片可见性）

检测器自带 20 组正反样本自测（`selfTest()`），故意构造硬编码密钥、通配符白名单、未转义 `innerHTML`、`map` 回调内的裸插值等坏样本，确保规则本身不会假阴性放行。任一检查 FAIL 即退出码 1。

## 6. 已知限制（needs validation）

- 未在本地执行 **Gradle 编译**与 **Android 真机 WebView 运行**。宿主补丁只使用 `FilesPicker.kt` / `ChatDrawer.kt` 中已存在的 import 与 API，编译与 UI 落地由 PR 的 CI 与真机验收兜底。
- 「压缩概要进入最新会话」依赖 `listConversations()` 的 `messageCount` 与 `updateAt` 排序（已按宿主源码核对），未做真机验证；若列表为空或全部 `messageCount === 0`，落幕卡会退回插件页面展示，不强行新建会话。
- 聊天里的邀请函是「可见文字 + 本地生成的 PNG 卡片图」，**不是**宿主原生可点击互动卡片：宿主红包/通话卡片依赖 `UIMessageAnnotation.ChatInteraction`，插件桥接无法附加注解。带「同意 / 婉拒」按钮的邀请函弹窗在插件页面内；这一取舍保住了「宿主只改一个文件」的可插拔约束。
- 邀请函 PNG 走宿主 `uploadChatImage` 分块通道（`bridge://` URL 传 base64 片段），真机上若分块失败会自动降级为纯文本请柬；该降级路径只在本地模拟过，未在真机 WebView 验证。
- `Bridge.sendChatMessage` 的 `transientSystemPrompt` 「只对本次请求生效」来自宿主源码核对，未做真机验证。
- 模型接口只做本地 mock 行为验证，**未对任何真实供应商发起请求**，不消耗额度、不接触真实密钥。
- WebView 在 `file://` 下的字体加载、iframe 跨源 `postMessage` 已做降级设计，真机表现需宿主侧验收。
- 本审查是 scoped / quick 级静态与沙箱验证，不含动态渗透测试、不含对宿主全量代码的审计。

## 7. 报告问题

如发现越权、密钥泄露、落幕语义失效或 XSS，请在本仓库提 issue，附上复现步骤与 `node audit/audit.mjs` 的输出；涉及密钥请**不要**粘贴真实凭据。
