# 宿主接入补丁：聊天页加号里的「假面舞会」

只改橘瓣一个文件：`app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`，共 +33 行，不动数据模型、不动红包/通话/转账任何既有逻辑，不新增 Android 权限。

## 改了什么

| 位置 | 改动 |
|---|---|
| import 区 | 加 `collectAsStateWithLifecycle` / `PluginManager` / `koinInject`（项目里 ChatDrawer 已在使用同样的依赖，无需加 Gradle 依赖） |
| `FilesPicker` 开头 | 从 `PluginManager.plugins` 读取已安装插件，算出 `masqueradeInstalled` |
| `PickerActionStrip` 第 226 行后 | 四个既有按钮（语音通话 / 视频通话 / 红包 / 转账）后面，条件渲染 `MasqueradeButton` |
| `GongduButton` 之后 | 新增 `MasqueradeButton` 组合项（🎭 + 「假面舞会」），点击导航到 `Screen.PluginWebView(pluginId, "ui/index.html", conversationId)` |

**可插拔的关键**：按钮由 `installedPlugins.any { id == "com.orangechat.masquerade" && isEnabled }` 决定。没装插件、装了没启用、或卸载插件，加号里都不会多出这个按钮；宿主里没有任何剧情逻辑，只有一句 ID 判断和一次导航。

**宿主契约（已核对 `PluginWebViewPage.kt`）**：`Screen.PluginWebView` 的 `conversationId` 只用于宿主自己选择 `callAI` 的模型与标题栏文案，**不会传进 WebView**（页面 URL 是纯 `file://` 绝对路径，无 query，`bridgeJavascript` 只替换 pageToken）。插件页拿会话 ID 的唯一通道是 `await Bridge.listConversations()`（需 `conversation_sync` 权限），宿主按 `updateAt` 倒序返回 `{id, title, messageCount, updatedAt}`；插件优先选 `messageCount > 0` 的最新一条，即「助手最新有对话的上下文」。

`sendChatMessage` 的可见文本会作为一条 USER 消息**永久写入该会话历史**（`ChatService` 的 `createQueuedMessageJob` → `saveConversationWithOwnership`），后续对话能看见；`hiddenContext` 映射为 `transientSystemPrompt`，只作用本轮生成、不落库。`answer=true` 会触发一次助手回复。因此落幕卡的压缩概要必须放在可见文本里，才能真正进入助手上下文。

## 应用方式

```bash
# 在 orangechat 仓库根目录
git apply /path/to/orangechat-masquerade/host-patch/FilesPicker.kt.patch
# 或者直接覆盖
cp /path/to/orangechat-masquerade/host-patch/FilesPicker.kt \
   app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt
```

回滚：`git apply -R host-patch/FilesPicker.kt.patch`

## 三件套与可复现性

| 文件 | 内容 |
|---|---|
| `FilesPicker.kt.orig` | 宿主 `46748a5` 版本的**原始** `FilesPicker.kt` 快照（CRLF，496 行） |
| `FilesPicker.kt` | 打补丁后的完整文件（529 行，+33） |
| `FilesPicker.kt.patch` | 两者之间的 unified diff |

原始快照随仓库提供，因此生成脚本**不依赖任何仓库外的参考副本**，clone 下来即可复跑：

```bash
node host-patch/generate.mjs
# 也可以指定别的宿主版本与输出位置：
node host-patch/generate.mjs --src=/path/to/orangechat/.../FilesPicker.kt --out=/tmp/FilesPicker.kt
```

脚本对四个锚点做「必须恰好出现一次」的校验，源码漂移时直接报错，不会悄悄打错位置；若误把补丁后文件当作输入，会显式拒绝，避免二次插入；同时自动适配 CRLF / LF 换行，避免整文件 diff。默认只输出补丁后的文件，**不留任何中间产物**。审计 R2-08 会用这份快照在临时目录重生成并逐字比对产物，保证补丁确实可复现。

需要重算 unified diff 时才写原始副本：

```bash
node host-patch/generate.mjs --src=... --keep-before
diff -u host-patch/FilesPicker.kt.before host-patch/FilesPicker.kt > host-patch/FilesPicker.kt.patch
rm host-patch/FilesPicker.kt.before
```

## 完整接入顺序

1. 构建插件包：`npm run build` → `dist/orangechat-masquerade-1.2.0.zip`
2. 橘瓣里：设置 → 插件管理 → 导入 ZIP → 确认 `conversation_sync` 权限与网络白名单 → 安装并启用
3. 应用本补丁并编译橘瓣
4. 回聊天页点加号，四个按钮旁边会出现「🎭 假面舞会」

插件详情页本身也能打开管理页面（`customPageWebView`），所以**不打这个补丁**也能用全部功能，只是没有加号快捷入口。

## 验证口径

- 补丁对 `feature/video-call-gifts-ci` 分支 HEAD `46748a55b80412dab5785f60cc884ba4dfca21ed` 生成并逐行核对。
- 生成脚本断言：`MasqueradeButton` 定义 1 处 / 调用 1 处、`masqueradeInstalled` 2 处、`Screen.PluginWebView` 1 处、插件 ID 2 处。
- 未在本地执行 Gradle 编译（缺少 Android SDK 与完整依赖缓存）；补丁只使用该文件与 ChatDrawer 已有的 import 与 API，编译风险由 PR 的 CI 兜底。
