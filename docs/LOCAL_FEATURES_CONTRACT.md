# 本地功能接入约束

此文记录本阶段统一决定，不表示以下入口均已接通。

## 文件责任与验证

- 导出：Export/PagedChatImageExport/BitmapComposer与回归由独立实现者负责；父代理统一Gradle。固定1080×1920分页，单页约8.3MB，不为长会话创建巨图；仅选中消息，图片明确采样，隐藏工具原始参数不导出。兼容Markdown原功能，不用安全修复名义移除用户功能。
- 多助手：复用ProactiveTaskStore有界持久账本，nullable配置区分旧版与显式无人。执行目标固定assistant/conversation/真实用户anchor，后台任务和页面当前选择无关。每网络turn先预留预算；暂停/取消/删除后迟到结果拒绝提交。ChatService必要原子提交入口由主动消息实现者修改，其他功能不同时改此文件。
- 主题：CustomTheme增加可选完整token字段沿现有设置备份；ZIP第一版只接受声明式JSON，不接受可执行代码或资源文件。读取不解压到目标目录；entry名称/重复/计数/压缩和展开上限校验。三配色来自RichChatCards实际palette，不能套用名称相似的旧preset。用户动态色、主色、文字颜色和背景仍按显式偏好优先。预览不写入。
- 朋友圈：独立插件目录plugins/local-moments；宿主服务plugin/localdata提供通用受控命名空间结构化存储和资源ID。使用独立有版本SQLite store避免与主库迁移交叉，必须提供版本化逻辑备份恢复。父代理接WebView/系统图片选择器/主备份入口。

## 受控身份和存储

WebView页面只能作为本机用户操作；模型请求的assistant身份由宿主chat/task上下文创建，不能从JS参数接受actorId自证。权限需要宿主授权，manifest仅声明能力。任何列表、详情、搜索、评论、媒体读取都走相同ACL。插件只拿受控mediaId/缩略图地址，不能取得任意文件路径或私聊数据库。

插件localData请求采用版本化method+params，统一响应ok/data/error。模型密钥永不传给页面。独立SQLite按pluginId隔离，不接受任意SQL；游标固定时间+ID排序、默认3条并有限最大page，长正文读取也有预算。正常浏览不调用模型。

媒体从系统picker或已授权选中聊天/生成结果导入；原图存私有持久根，缩略图可重建，post引用不得由清缓存删除。生图插件当前_ orange_image（实际键以PluginImageResults为准）专用结果必须经过宿主校验复制，不依赖可轮转的插件Base64槽位。

## 分阶段完成状态

截至首次集成：生产Kotlin已通过一次编译；新增导出测试存在旧Sequence接口适配错误正在修复；主动配置与store仅基础，实际多助手/UI仍在接线；主题loader未接入；朋友圈业务实现已派发。不得将这些状态写为已交付完整功能。

最终需：阶段导出APK、完整集成APK、独立朋友圈ZIP、三主题ZIP、迁移和备份说明、实际测试结果与真机未验证边界。现有R8文件不覆盖，原签名和共存包身份不变；正式更新候选仍unsigned。
