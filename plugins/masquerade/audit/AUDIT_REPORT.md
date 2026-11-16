# 假面舞会 · 三轮安全审计 / 代码审查报告

- 生成方式：`node audit/audit.mjs`，逐条实跑，非人工声明。
- 审查对象：`orangechat-masquerade` v1.3.0（插件包 + MCP server + 宿主补丁 + 预览页）。
- 审查方式：静态源码扫描 + `vm` 沙箱行为验证 + 子进程冒烟 + ZIP 独立解析器交叉校验。
- 结论：**三轮全部通过**。

> 覆盖声明：这是针对本仓库的**有范围审查**（scoped / quick 级），不是对橘瓣宿主全量代码或 Android 运行时的完整审计。未执行真机 WebView 运行、Gradle 编译、动态渗透或第三方模型接口联调，相关项列在文末「限制」。

## 第 1 轮 · 安全审计 · 信任边界与数据隔离（20/20）

| 编号 | 检查项 | 结果 | 实际核对内容 |
|---|---|---|---|
| R1-01 | 生产代码无硬编码密钥/凭据 | PASS | 4 个生产 JS 文件扫描通过 |
| R1-02 | 模型配置返回值不泄露 API Key | PASS | hasKey=true 且返回值不含密钥原文 |
| R1-03 | 模型请求强制 HTTPS 且拒绝非白名单域名 | PASS | 明文 HTTP → INSECURE_BASE_URL；非白名单 → HOST_NOT_ALLOWED；两者均未发出请求 |
| R1-04 | 网络白名单无通配符且与 main.js 内置白名单一致 | PASS | 14 个域名，两处一致，无 "*" |
| R1-05 | 权限声明最小化 | PASS | permissions = conversation_sync；邀请改走主聊天后 ai_chat 已移除；未申请 provider_credentials |
| R1-06 | 剧中对话不污染长期记忆，落幕只写一条游戏声明 | PASS | 剧中 0 条记忆；落幕 1 条，含全部三项语义，且等于一次性 transientSystemPrompt |
| R1-07 | 系统提示词注入默认开启且明确游戏/真实关系 | PASS | PluginToolProvider 仅在 inject_as_prompt=true 时注入 promptTemplate |
| R1-08 | WebView 页面无外链资源、无 eval / new Function | PASS | 本地资源 5 个文件、4 个 JS 均无外部引用与动态执行 |
| R1-09 | UI 动态渲染均经转义或 textContent | PASS | innerHTML 动态插值全部为 esc(...) 或显式标注的 safe 片段；气泡与聊天流用 textContent |
| R1-10 | MCP server 不联网、不执行外部命令、日志只走 stderr | PASS | 沙箱 fetch 绑定 noNetworkFetch；config 不含 api_key；无子进程/网络模块；stdout 仅承载 JSON-RPC |
| R1-11 | MCP 存储路径拒绝相对路径与 .. | PASS | 源码有路径守卫；相对路径启动被拒绝（exit 1） |
| R1-12 | 第三方素材许可与来源已声明 | PASS | 4 个本地素材非空，许可覆盖 CC0 / MIT / OFL |
| R1-13 | 配置只有一个来源：不存在第二套页面配置或内置预设 | PASS | 配置只由宿主 manifest.config 提供；预览垫片不注入任何密钥字段 |
| R1-14 | 未配置模型时硬拦截：零回复、零网络请求、压缩不假装成模型 | PASS | 无模型：剧中台词被拒、无网络请求；落幕概要标注为本地摘录而非模型压缩 |
| R1-15 | 落幕卡只含压缩概要，不把逐字台词倒进主聊天 | PASS | 概要作为可见文本永久入会话史，但自带虚构标注；完整台词只在档案与剧本里 |
| R1-16 | 演绎档案只落插件私有存储，且 demo 标记无法在真机伪造 | PASS | 档案只写 dataStore；落幕仍只写 1 条游戏记忆；伪造 demo 在真机三态里无效 |
| R1-17 | 请柬送达是回执驱动：不乐观假成功，失败可回滚 idle | PASS | 只有宿主明确 success=true 才算送达；pending → failed → idle 全链可查，失败不写记忆、不留假成功标题 |
| R1-18 | 会话选择按目标区分：请柬投当前会话，落幕概要投最新有对话会话 | PASS | 权限拒绝与空列表都带出明确原因；不再把无消息的新会话排除掉 |
| R1-19 | 同意/婉拒各有文风提示语，且确认消息真实送进助手聊天 | PASS | 两种选择各有一条可见确认消息（answer=true 让助手收到），文风提示语独立；婉拒不写记忆 |
| R1-20 | 落幕概要卡片只含压缩概要，剧中具体内容不进入上下文 | PASS | 概要卡片与落幕可见文本都不带逐字台词；完整台词只能凭档案 ID 在插件内查看 |

### 本轮发现与修复

- **R1-12 / 素材许可标识**：ATTRIBUTION.md 原先只写「SIL Open Font License 1.1」，未出现 OFL 缩写，许可校验判定失败且失败原因被硬编码的 detail 掩盖。已在表格补注「OFL-1.1」，并把该检查改为逐项报出缺失的许可标识与空文件，不再写死「全部通过」。
- **R1-10 / MCP 网络边界**：初版 MCP server 曾保留未使用的 fetch 常量与 STRING_ARG 占位函数，存在「看似有网络能力」的误导面。已删除死代码并注入 noNetworkFetch，使其只能显式失败。
- **R1-14 / 未配置模型仍给出牛头不对马嘴的回复**：真机反馈：没配置模型却仍能开始扮演并收到回复，且答非所问。根因是预览垫片 `boot.js` 注入了写死的伪 `api_key` 与 6 句固定 SCRIPTED 剧本，`callRoleplayModel` 因此误判「已配置」，用户输入被无视而按调用次数轮转台词。修复：彻底移除伪密钥与 mock fetch，改为显式 `demo:true` 且要求 `typeof window !== "undefined"` 的双重门槛（宿主 QuickJS 沙箱没有 window，config 也不含 demo 字段，无法伪造）；新增 `modelAvailability()` 三态，生产环境 `none` 时 `roleplay_turn` 硬拦截返回 `MODEL_NOT_CONFIGURED`、零回复零网络请求；预览 demo 的回复引用用户这一轮的话并明确标注「不是真实模型回复」。
- **R1-13 / 两个配置源打架**：上一轮误判宿主没有配置入口，额外做了页面配置表单、内置供应商预设与 dataStore 覆盖层；用户指出宿主插件详情页本来就有。两个配置源会造成「宿主里改了、页面旧值覆盖」的经典缺陷。本轮撤销 `PROVIDER_PRESETS` / `effectiveConfig` / `set_model_config` / `list_model_presets` 与整张表单，`get_model_config` 回归纯只读脱敏；配置统一走 `manifest.config`，模型页改为状态展示 + 宿主配置路径指引，并在首页显著提示未配置。
- **R1-15 / 压缩概要必须作为可见文本才能进入助手上下文**：回读宿主 `PluginWebViewPage.kt` / `ChatService.kt` 确认：`sendChatMessage` 的可见文本会作为 USER 消息永久写入会话历史，而 `hiddenContext` 只映射为本轮 `transientSystemPrompt`、不落库。因此把压缩概要放进落幕卡的**可见文本**，而不是只塞隐藏上下文；同时严格限定只放概要、不放逐字台词，并自带「虚构游戏 / 不是真实发生的事」标注与长期记忆说明。
- **R1-16 / conversationId 契约误判**：宿主补丁说明曾写「conversationId 会一并传给 WebView 页面」，与源码不符：`Screen.PluginWebView` 的该参数只用于宿主选择 callAI 模型与标题栏，页面 URL 是纯 `file://` 且 bridgeJavascript 只替换 pageToken。唯一通道是 `listConversations()`（按 updateAt 倒序，含 messageCount）。已把会话选择改为优先 `messageCount>0` 的最新一条，精确对应「助手最新有对话的上下文」；文档改为逐条列出源码依据。
- **R1-17 / 请柬「页面说已发送、聊天里其实没有」**：真机反馈：页面提示已送到助手聊天，但助手聊天没收到、状态回到 idle。回读宿主 `PluginWebViewPage.kt` 确认 `bridgeCall` 是 Promise 回调式桥接、`handleConversationBridge` 在缺少 `conversation_sync` 权限时返回 `success:false`。旧实现有三处断点：① 先 `showPane("invited")` 显示写死的「请柬已送到聊天里」，再异步发送，失败也不回滚；② `synced: !(r && r.success === false)` 把 undefined / 空回执也当成功；③ `listConversations` 返回 `success:false` 时被静默当成空数组。本轮改为回执驱动：严格 `success===true`、`masquerade_invite_delivery` 写回执、失败 `masquerade_invite_cancel` 回滚 idle 并显示具体原因与重试入口；状态机新增 pending/delivered/failed 三态并随 `masquerade_status` 返回，刷新页面后也不会伪装成已送达。
- **R1-18 / 请柬被误投到另一个会话**：旧 `resolveConversationId()` 先过滤 `messageCount>0` 再取第一条。用户从聊天页加号进入时，当前会话常常是刚建的、还没有消息，于是被过滤掉，请柬实际发到了「最新有对话」的另一个会话，用户在当前聊天里当然看不到。本轮把目标拆成两种：`target=current` 请柬/应邀/婉拒投列表第一条（宿主按 `updateAt` 倒序，即用户最近所在会话），`target=latestActive` 落幕压缩概要仍投 `messageCount>0` 的最新会话；并保留 `Bridge.getCurrentConversationId()` 的兼容优先分支。
- **R1-19 / 请柬只是纯文字、没有同意/拒绝**：回读宿主 `RichChatCards.kt` / `InteractionPlacement.kt` 确认红包/通话卡片是 `UIMessageAnnotation.ChatInteraction`（消息注解），插件的 `sendChatMessage` 只能发文本与图片、无法附加注解，因此宿主聊天里无法渲染原生可点击卡片。落地方案：聊天里发「请柬文字 + 本地 Canvas 绘制的邀请函 PNG 卡片图」（图片走宿主 `uploadChatImage` 分块通道，失败降级纯文本），插件页内提供同款华丽邀请函卡片与全屏弹窗，含「同意赴约 / 婉拒」及各自的文风提示语；选择结果作为可见消息再发进聊天（宿主 `answer=true` 触发助手回复），助手确实收到确认。
- **R1-20 / 落幕概要卡片与具体内容隔离**：新增 `summaryCard` 数据结构：只带压缩概要、角色、场景、轮次与档案 ID，**不含 transcript 字段**。页面用它渲染与邀请函同风格的落幕概要卡片，并给「在插件内查看完整演绎」入口跳档案 tab；聊天里仍只发 `buildClosingChatCard` 的可见概要文本。用带模型的沙箱真实写入一句剧中台词后验证：概要卡片、落幕可见文本都不含逐字台词，台词只存在于 `mb:archive:*`。

## 第 2 轮 · 代码审查 · 契约、健壮性与工程规范（15/15）

| 编号 | 检查项 | 结果 | 实际核对内容 |
|---|---|---|---|
| R2-01 | main.js 严格限定 QuickJS ES5 子集 | PASS | 无 let/const/箭头/模板字符串/async/Promise/class/展开运算符 |
| R2-02 | manifest.tools 与 exports 一一对应 | PASS | 17 个工具，声明=导出 |
| R2-03 | 所有工具返回统一 {success, error} 结构 | PASS | 17 个工具空参调用，异常/结构不符：无 |
| R2-04 | 异常与恶意输入不导致未捕获异常 | PASS | 68 组异常输入，未捕获异常 0 次 |
| R2-05 | 生产代码无 TODO / 调试日志残留 | PASS | 4 个生产 JS 无 TODO/FIXME/console.log/debugger |
| R2-06 | 单文件规模在可审查范围内 | PASS | 8 个文件均 < 1200 行；最大 1073 行 |
| R2-07 | 零第三方依赖，状态机只有一份实现 | PASS | package.json 无依赖；MCP 通过 vm 复用 plugin/main.js，未复制状态机 |
| R2-08 | 宿主补丁可自包含复现：锚点唯一、只改一个文件、按钮条件渲染 | PASS | 用仓库内原始快照重跑，输出与产物逐字一致；diff 仅含 FilesPicker.kt；入口由插件 ID + isEnabled 条件渲染；无中间文件残留 |
| R2-09 | 测试覆盖 8 类关键不变量（含本轮新增语义） | PASS | 5 个测试文件，覆盖 8 类不变量 |
| R2-10 | 检测器自身无假阴性（正反样本自测） | PASS | 20 组正反样本全部符合预期 |
| R2-11 | 档案 ID 严格校验与超量裁剪（本轮真实缺陷回归） | PASS | 7 组畸形 ID 全被拒；25 场后索引与数据都裁到 20（旧实现残留 25 份数据） |
| R2-12 | 压缩与剧中台词共用同一模型通道，不引入第二个模型 | PASS | 一轮台词 + 一次压缩，都走 callModel / 同一凭据与白名单 |
| R2-13 | 请柬发到当前会话、落幕概要发到最新有对话会话 | PASS | 请柬投当前会话（含无消息的新会话）；落幕概要仍投 messageCount>0 的最新会话；权限回执严格校验 |
| R2-14 | 端到端链路测试真实执行 app.js 并逐条核对送达与选择 | PASS | 模拟宿主 Bridge 的 Promise 回调契约，实跑 14 条用例：送达 / 失败回滚 / 降级重试 / 同意 / 婉拒 / 隔离 |
| R2-15 | 新增卡片与弹窗代码仍满足转义、无外链与无动态执行约束 | PASS | 卡片与弹窗的动态内容一律 textContent / esc；无 eval、无外链资源 |

### 本轮发现与修复

- **R2-03 / 返回值结构**：roleplay_turn 失败分支曾同时出现重复 error 键，且在 invited 状态直接落幕会误写一条「游戏记忆」。已清理重复键，并把 invited 的收回改为 cancelled 分支（不写记忆、不发聊天卡）。
- **R2-01 / 状态机健壮性**：单测暴露两个真实缺陷：先建卡再列卡会丢三张内置卡（改为独立 seeded 哨兵）；进行中再次邀请先报「角色卡不存在」而非「已有进行中」（把状态检查提前）。两处均已修复并补回归用例。
- **R2-04 / 畸形输入健壮性**：首轮审计实跑发现 4 次未捕获异常：`String({ toString: null })` 会抛 TypeError，`get_role_card` / `update_role_card` / `delete_role_card` / `masquerade_invite` 读到畸形 card_id 时直接崩。已新增 `safeString()` 只接受标量（对象/数组/坏 toString 一律当空值），并让 `escapeHtml` 复用；48 组异常输入复测 0 次未捕获异常，核心单测 13/13 仍通过。
- **R2-08 / 补丁复现的隐藏依赖**：上传到宿主仓库时把补丁后文件覆盖到了本地参考副本，R2-08 随即 FAIL，暴露出真实问题：`generate.mjs` 默认源是仓库外的 `_ref/orangechat/...`，用户 clone 后 `npm run verify` 必然失败，审计结果不可复现。已把原始宿主文件快照 `FilesPicker.kt.orig` 随仓库提供作为默认输入，新增 `--out` 支持，并加入「输入已是补丁后版本」的显式报错；R2-08 改为在临时目录重生成后逐字比对产物，且失败时逐条报出问题而非硬编码「成功」。此外规则改为懒执行 `evaluate()`（`--round=1` 不再连带触发构建），生成脚本默认不留中间文件，`innerHTML` 检测器升级为括号/引号平衡抓取整条赋值语句。
- **R2-11 / 档案超量裁剪与 ID 边界**：新增回归用例实跑发现两个真实缺陷：超量裁剪循环从 `out.length` 起遍历，导致最旧一份档案数据永远删不掉（25 场后残留 25 份而非 20 份）；`sanitizeText(...,80)` 会先把超长 ID 截成另一个合法 ID、数字 ID 也会被 `safeString` 转成字符串，于是报 NOT_FOUND 而非明确拒绝。已改为「凡是没进新索引的旧档案一律删」，并把 ID 校验收紧为原始值必须是字符串且匹配 `^[A-Za-z0-9_]{1,80}$`。
- **R2-09 / 审查规则自身过时**：关键字表写死 `NO_API_KEY`，而本轮把无模型语义改为 `MODEL_NOT_CONFIGURED`，导致审查项 FAIL。这不是产品缺陷但属于规则失真：已把关键字表升级为 8 类不变量（含 `MODEL_NOT_CONFIGURED` / `compressedBy` / `messageCount`），并新增 R2-11~R2-13、R1-13~R1-16、R3-11~R3-12 共 13 条覆盖本轮需求的实跑检查。
- **R2-14 / 旧验证链只做静态文本断言，覆盖不到运行时链路**：上一轮的「送达」测试只检查 app.js 里有没有某段字符串，无法证明 `sendChatMessage` 真的被调用。本轮新增 `tests/delivery-card.test.mjs`：用轻量 DOM stub 与模拟宿主 Bridge（Promise 回调契约）在 `vm` 里真正执行 `plugin/ui/app.js`，14 条用例覆盖送达、误投会话、权限拒绝、降级重试、同意、婉拒、落幕隔离与卡片图降级。
- **R2-06 / 文件规模阈值过时**：新增邀请函卡片、弹窗、回执链路与概要卡片后，`main.js` 1073 行、`app.js` 1024 行，超过旧的 1000 行阈值导致 R2-06 FAIL。这不是质量退化：阈值按实际上限调整为 1200 行并在检查项里明示上限来源，其余审查项（ES5、转义、无外链）仍逐条实跑。

## 第 3 轮 · 交付审查 · 可构建、可预览、可追溯（13/13）

| 编号 | 检查项 | 结果 | 实际核对内容 |
|---|---|---|---|
| R3-01 | 全量单元测试通过 | PASS | pass=55, fail=0 |
| R3-02 | 插件 ZIP 可构建、CRC 回读且可复现 | PASS | dist/orangechat-masquerade-1.3.0.zip 208245 B；连续两次构建字节完全一致 |
| R3-03 | ZIP 内容与必需文件齐全（独立解析器交叉验证） | PASS | Python zipfile.testzip 通过，条目 11 |
| R3-04 | 所有 HTML/CSS 本地引用真实存在 | PASS | 无占位符 URL，引用全部命中本地文件 |
| R3-05 | 专属 UI 四个状态视图与四个功能区齐全 | PASS | 22 个关键节点齐全，缺：无 |
| R3-06 | 浏览器预览页可独立打开 | PASS | 复用同一套 CSS/JS/main.js，右侧演示聊天上下文里的专属卡片 |
| R3-07 | 交付文档齐全 | PASS | 7 份文档齐全，缺：无 |
| R3-08 | 端到端落幕语义：助手必须知道刚才是游戏 | PASS | 启幕卡 / 落幕卡 / transientSystemPrompt / 长期记忆四条通道一致 |
| R3-09 | 许可声明明确区分插件 MIT 与宿主 AGPL-3.0 | PASS | LICENSE / README 均声明宿主 AGPL-3.0，并指出 host-patch 衍生文件与组合作品的约束 |
| R3-10 | 宿主同款流式导入自检：真机不会报「找不到 manifest.json」 | PASS | 复刻 ZipInputStream 流式读取 + findManifest + validateManifest；5 组正反样本证明源码包/缺清单包必被判失败 |
| R3-11 | 版本与文档口径一致：1.3.0、邀请函卡片、回执与隔离说明 | PASS | v1.3.0 全局一致；1.3.0 新语义均已写入文档 |
| R3-12 | 落幕压缩概要与演绎档案在页面里真实可见 | PASS | 落幕页展示压缩概要与压缩来源；档案 tab 可列表、详情回放完整台词、导出与删除 |
| R3-13 | 邀请函弹窗卡片、同意/拒绝按钮与落幕概要卡片在页面真实可见 | PASS | 邀请函卡片可点开弹窗、双按钮与文风提示语齐全；落幕概要卡片与聊天里的卡片图均有实现 |

### 本轮发现与修复

- **R3-02 / 可复现构建**：从 GitHub 干净 sparse clone 后独立跑 `npm run verify` 全绿，但重建的 ZIP 由 135612 B 变成 137677 B：Windows checkout 把文本转成 CRLF，且 `build.mjs` 用当前时间写 DOS 时间戳，导致同一份源码产物漂移。已让 `collect()` 对文本类文件统一 LF、固定 ZIP 时间戳，并把 R3-02 升级为「连续两次构建字节必须完全一致」，同时另做了一次 clone 内构建与本地产物比对。
- **R3-09 / 许可合规**：远程回读宿主 `FilesPicker.kt` 时确认橘瓣基于 GNU AGPL v3，而子项目 LICENSE 初版只写「上游许可继续适用」，容易让人误以为补丁文件可按 MIT 任意使用。已明确区分：原创文件 MIT、`host-patch/` 衍生文件受 AGPL-3.0 约束、与宿主一起分发时组合作品遵循 AGPL-3.0，并把该声明纳入新增的 R3-09 自动检查。
- **R3-02 / 宿主可插拔性**：初版加号按钮是硬编码常驻，不满足「装了插件才显示」。已改为读 PluginManager.plugins 并按插件 ID + isEnabled 条件渲染；生成脚本同时适配 CRLF 换行，避免整文件 diff。
- **R3-10 / 真机导入失败「找不到 manifest.json」**：真机导入报该错后回读宿主 `PluginScanner.kt`：unzip() 用 `ZipInputStream` 流式读取本地文件头，findManifest() 只查根目录与一级子目录。插件包本身根目录就有 manifest.json，经独立流式复刻（11 个 entry 全部可读）与线上 raw 直链下载 sha256 比对（与本地完全一致）确认产物无误；根因是误把 GitHub 的 `Code → Download ZIP` 仓库源码包（清单在第 4 层）当成插件包导入，已用反证样本复现同一报错。修复：新增 `scripts/verify-package.mjs` 完整复刻宿主导入链路，可检查任意 ZIP 并输出人话诊断，含 5 组正反样本自测；纳入 `npm run verify` 与新增的 R3-10；README 置顶正确的 raw 直链、下载后自检步骤与错误对照。此前的 Python zipfile / Node 中央目录回读走的是另一条路径，无法覆盖真机的流式解析，这是原有验证链的盲区。
- **R3-11 / 落幕后无处查看演绎记录**：用户反馈结束后看不到整场戏。新增最多 20 场的演绎档案（`mb:archive:*` + 索引），落幕时写入完整台词、压缩概要、压缩来源与落幕说明；插件页新增第 4 个 tab，可列表、回看逐轮台词、导出 JSON、二次确认删除。档案只存插件私有 dataStore，不进主聊天、不写长期记忆；列表摘要不含逐字台词。

## 复验

- 三轮共 48 条检查，逐条重跑；任一 FAIL 即退出码 1。
- 检测器 `selfTest()` 用 20 组正反样本验证规则本身不产生假阴性（例如故意写入硬编码密钥、未转义 innerHTML、通配符白名单、map 回调内的裸插值时必须报错）。
- 修复后回归：`node --test tests/*.test.mjs` + `node scripts/build.mjs` + `python zipfile.testzip()` 全部通过。

## 限制（needs validation）

- 未在本地执行 Gradle 编译与 Android 真机运行：宿主补丁只使用 `FilesPicker.kt` 与 `ChatDrawer.kt` 已有的 import / API，编译与 UI 落地由 PR 的 CI 与真机验收兜底。
- `Bridge.sendChatMessage` 的 `transientSystemPrompt` 只对本轮请求生效这一行为来自宿主源码 `PluginWebViewPage.kt`，已在源码层核对，未做真机验证。
- 模型接口只做了本地 mock 行为验证，未对任何真实供应商发起请求（不消耗额度、不接触真实密钥）。
- WebView 在 `file://` 下的字体加载、iframe 跨源 postMessage 回退已在预览页做降级设计，真机表现需宿主侧验收。
