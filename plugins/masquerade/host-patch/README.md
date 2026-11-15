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

`conversationId` 会一并传给 WebView 页面，插件用它把启幕卡 / 落幕卡通过 `Bridge.sendChatMessage` 送回当前聊天，并附上一次性 `transientSystemPrompt`。

## 应用方式

```bash
# 在 orangechat 仓库根目录
git apply /path/to/orangechat-masquerade/host-patch/FilesPicker.kt.patch
# 或者直接覆盖
cp /path/to/orangechat-masquerade/host-patch/FilesPicker.kt \
   app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt
```

回滚：`git apply -R host-patch/FilesPicker.kt.patch`

重新生成补丁（需要一份橘瓣源码副本）：

```bash
node host-patch/generate.mjs --src=/path/to/orangechat/app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt
```

脚本对四个锚点做「必须恰好出现一次」的校验，源码漂移时直接报错，不会悄悄打错位置；同时自动适配 CRLF / LF 换行，避免整文件 diff。默认只输出补丁后的 `FilesPicker.kt`，**不留任何中间产物**。

需要重算 unified diff 时才写原始副本：

```bash
node host-patch/generate.mjs --src=... --keep-before
diff -u host-patch/FilesPicker.kt.before host-patch/FilesPicker.kt > host-patch/FilesPicker.kt.patch
rm host-patch/FilesPicker.kt.before
```

## 完整接入顺序

1. 构建插件包：`npm run build` → `dist/orangechat-masquerade-1.0.0.zip`
2. 橘瓣里：设置 → 插件管理 → 导入 ZIP → 确认 `ai_chat` / `conversation_sync` 权限与网络白名单 → 安装并启用
3. 应用本补丁并编译橘瓣
4. 回聊天页点加号，四个按钮旁边会出现「🎭 假面舞会」

插件详情页本身也能打开管理页面（`customPageWebView`），所以**不打这个补丁**也能用全部功能，只是没有加号快捷入口。

## 验证口径

- 补丁对 `feature/video-call-gifts-ci` 分支 HEAD `46748a55b80412dab5785f60cc884ba4dfca21ed` 生成并逐行核对。
- 生成脚本断言：`MasqueradeButton` 定义 1 处 / 调用 1 处、`masqueradeInstalled` 2 处、`Screen.PluginWebView` 1 处、插件 ID 2 处。
- 未在本地执行 Gradle 编译（缺少 Android SDK 与完整依赖缓存）；补丁只使用该文件与 ChatDrawer 已有的 import 与 API，编译风险由 PR 的 CI 兜底。
