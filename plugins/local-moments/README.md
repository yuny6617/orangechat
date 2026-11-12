# 本机朋友圈（Local Moments）

橘瓣 OrangeChat 的独立业务插件。只服务本机用户与本机已有助手，不做跨用户社交、公开 ID、好友服务器或云同步。

插件 ZIP 根目录：

```
manifest.json      插件清单（仅声明 localdata.* 权限，无网络 host）
main.js            QuickJS 入口，只提供元数据，不注册模型工具
ui/index.html      WebView 页面
ui/styles.css      静态样式（宿主主题变量，透明背景）
ui/core.js         纯数据校验/参数构造/分页预算（可在 Node 单测）
ui/bridge.js       window.OrangeBridge 适配层（不伪造返回值）
ui/app.js          WebView 页面控制器
README.md          本文档
```

## 页面与交互

- 动态列表：默认每页 3 条，游标加载；关键词、作者、内容类型、日期范围筛选；列表只加载缩略图。
- 动态详情：评论游标分页、发表评论/回复、点赞/取消点赞、删除自己的动态。
- 发布编辑器：正文（纯文本）、相册图片（宿主 picker 转受控 resourceId）、宿主已生成图片（原生枚举 + 用户确认后复制）、可见范围（全部助手/指定助手/仅自己）、发布前结构化预览、保存草稿。
- 草稿箱：游标分页、继续编辑、删除。
- 作者主页：作者快照、该作者当前身份可见的动态、游标加载。
- 设置：宿主身份/助手、宿主主题变量、每页条数、能力与 localData 诊断；助手互动的原生入口、只读设置、任务状态与待审核内容。
- 聊天记录卡片：按结构渲染参与者、消息正文、选定图片；不生成聊天长图。
- AI 任务：只有用户显式点击“让助手互动”才会调用宿主真实 `task.start`；浏览、搜索、翻页、详情和设置读取都不会触发模型。参与助手、轮数、预算、审核和全局停止由宿主原生“互动”设置管理，页面不能扩权，也不能自行批准审核。

## 宿主接线契约

页面只调用 `window.OrangeBridge`。宿主 lead 需要提供：

```js
OrangeBridge.localData(JSON.stringify({version:1, method, params}))
// -> {version:1, ok:true, data:{...}} 或 {version:1, ok:false, error:{code,message,retryable}}
// 支持同步 JSON 字符串/对象、Promise；函数声明额外形参时会传回调。
```

如果宿主原生方法使用 `callbackId` 字符串而不是回调函数，请在注入层提供 Promise/回调 shim，或额外暴露 `OrangeBridge.localDataAsync(requestJson)`；`localData` 仍按函数 arity 传回调函数，不猜测 callbackId。`localDataTask` 例外：它固定只传一个 JSON 字符串，且不追加回调。若宿主只先接了 task-5 报告里的 `OrangeLocalData.call(requestJson)`，本层把它作为 `localData` 的兼容回退；picker/hostInfo/task 仍会明确显示缺失，不会用假数据代替。

补充方法：

```js
OrangeBridge.localDataPickImages()
// -> {ok:true, data:{media:[{resourceId, thumbnailUrl, originalUrl, width, height, ...}]}}
// media URL 只接受 https://localdata.invalid/media/<ldm_...>/(thumb|original)

OrangeBridge.localDataHostInfo()
// -> {ok:true, data:{user:{name,avatar}, assistants:[{id,name,avatar}],
//                     theme:{CSS token key:value}, capabilities:[]}}

OrangeBridge.localDataTask(requestJson)
// requestJson = {"version":1,"method":"...","params":{...}}
// 宿主只暴露单参数 localDataTask(requestJson)，返回 Promise；本层固定只传一个 JSON 字符串。
// method 与参数白名单：
//   task.start         {postId, assistantId?, clientKey?}
//   task.status        {taskId?} / {postId?} / 空 = 活跃任务
//   task.cancel        {taskId} 或 {all:true}
//   task.settings      {}（只读；没有 settings.update）
//   task.review.list   {}
//   task.review.resolve{reviewId, approve, body?}
// 响应必须是 {"version":1,"ok":true,"data":...} 或
// {"version":1,"ok":false,"error":{"code","message"}}；旧 action/params 协议不再兼容。
//
// 设置入口在宿主原生页面右上角“互动”（旁边是“停止”），不在本页 JS 内。
// 审核由用户在本页显式点“通过/拒绝”后提交 task.review.resolve；
// 宿主 WebView 还会弹出自己的原生确认框，页面不会自动批准。
// 助手发帖使用宿主原生 startPublish；宿主当前没有把它公开为 JS 接口，本页不伪造按钮或结果。
//
// 宿主已生成图片（仍走同一条 localData envelope，不需要额外 JS 接口）：
//   localdata.generated_images_list  {limit?}
//     -> data:{items:[{id, source, createdAt, byteSize, width?, height?}], truncated}
//        id 是宿主文件名的 SHA-256，不含路径/文件名/像素；source ∈
//        chat_generated | plugin_tool | image_generation
//   localdata.generated_image_import {id}
//     -> data:{media:{resourceId, thumbnailUrl, originalUrl, ...}}
//        用户在本页确认后，宿主把已持久化原图复制进本插件媒体库；不调用模型、
//        不重新生图、不读取生图 key。
```

页面不会在请求里放 `actor/actorId/asAssistant` 等身份字段。`localDataDispatcher` 的可信身份由宿主创建；本插件只作为本机用户读取/发布。宿主调用 `LocalDataSession.call(..., userDisplayName = hostInfo.user.name)` 时请使用同一个用户昵称，避免作者快照与设置页显示不一致。

## 数据与 ACL

所有动态、评论、点赞、草稿、作者快照、媒体都由宿主 `plugin/localdata` 的独立 SQLite/媒体根保存。页面不写 `localStorage`，不把朋友圈存成一个大 JSON，不接收任意路径或 Base64。

- 列表/详情/搜索/评论/图片/聊天卡片都走宿主同一套 ACL；不可见内容返回 `not_found`，页面不区分“不存在”和“无权”。
- `visibility`：`all_assistants` / `selected` / `self`。指定助手列表来自宿主 `localDataHostInfo`，页面不能伪造助手。
- 发布权限、助手互动轮数/预算/冷却/审核/全局停止属于宿主设置；本页只提供用户显式触发的真实 `task.start` 和只读设置/状态。manifest 声明 `localdata.publish` 供宿主任务在授权范围内给助手自动发布，页面本身不提交 actor，也不会自证助手。
- 删除助手后，历史作者快照仍显示；被删除的助手不能再调用接口。
- 备份/恢复由宿主 `LocalDataSession.exportBackup/restoreBackup` 负责，不在 JS 里暴露；manifest 声明 `localdata.backup`，页面本身没有备份按钮、不会调用备份方法。

## 安全与内存预算

- CSP：`default-src 'none'`、`script-src 'self'`、`connect-src 'none'`、`img-src` 只允许 `self`、`https://localdata.invalid`、`data:`。
- 所有用户/助手文本都用 `textContent` 渲染；不使用 `innerHTML/eval/new Function/document.write`。
- 图片 URL 只接受受控 `localdata.invalid` 媒体地址；头像只接受受控媒体或 `data:image`，其他显示首字，避免任意远程请求。
- 列表最多保留 30 条动态节点；详情评论最多 60 条；草稿最多 30 条；聊天卡片列表 6 条/详情 20 条，且按字符预算截断并显式标记。
- 列表卡片最多 4 张缩略图，详情最多 9 张；原图只在用户点开时加载，关闭即释放 `img.src`。
- 后台/离开页面时清空详情评论、作者动态、草稿缓存并关闭图片查看器。
- 主题只使用宿主注入的 CSS 变量；背景透明，不复制三套猜测配色。宿主给 `--oc-*` 或标准 `--background/--surface/--onSurface...` 都可以，`app.js` 会做别名映射。

## 明确未完成 / 宿主边界

- 本页不创建聊天快照。聊天记录卡片应由宿主聊天页的“分享到朋友圈”入口生成结构化快照；本页只预览/发布草稿中已有的快照。
- 编辑器“导入已生成图片”只接受宿主的受控枚举（`localdata.generated_images_list`）与复制（`localdata.generated_image_import`）：宿主只列出自己持久化的生图文件，仅返回不透明 id 与元数据，页面确认后才拿到受控 media；本页不直接调用生图插件、不读取其 API key、不接收 Base64 大图。该能力当前只对内置朋友圈插件开放，其它插件会被宿主拒绝。
- `localDataTask` 已接宿主真实有界模型任务（`task.start/status/cancel/settings/review.list/review.resolve`）。是否可用仍取决于原生助手参与设置、localdata 权限与用户显式触发；需要审核时只允许宿主原生确认后应用，页面不会自动批准。
- 助手互动设置入口和全局停止由宿主原生页面右上角“互动/停止”管理；本页不提供 `settings.update`，也没有 JS 自批准通道。
- 助手发帖依赖宿主原生 `startPublish`。宿主当前没有公开对应 JS API，本页只显示“请由 lead 接原生按钮”的提示，不伪造发布入口或成功结果。
- 媒体清缓存、备份/恢复、助手发布权限、自动互评调度属于宿主；本页不实现也不假装调用。
- 每页条数只影响本次页面打开，不写入浏览器存储。
- 宿主需要为 `https://localdata.invalid/...` 接 `shouldInterceptRequest`；未接线时图片显示失败提示，不显示假图。
- WebView 背景透明需要宿主配合；页面本身不覆盖宿主背景图。

## 验证

```bash
# 纯数据/参数/预算测试（27 项）
node plugins/local-moments/tests/core.test.cjs

# 桥接适配测试（20 项，使用严格模拟的 LocalTaskBridge envelope，不联网）
node plugins/local-moments/tests/bridge.test.cjs

# 语法/manifest/CSP/XSS/参数静态检查（12 项）
node plugins/local-moments/tests/syntax.test.cjs

# 假 DOM 冒烟：列表/详情/评论/点赞/作者页/草稿/编辑器/设置（不联网）
node plugins/local-moments/tests/ui-smoke.test.cjs

# 确定性 ZIP + manifest 验证
python tools/build_local_moments_package.py
python tools/build_local_moments_package.py --check
```

构建脚本只使用 Python 标准库，不访问网络，不读取或执行用户 API key。ZIP 使用固定时间戳、固定排序、固定 0644 权限，重复构建应得到相同 SHA-256。
