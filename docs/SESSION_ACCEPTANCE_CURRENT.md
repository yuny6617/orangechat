# 当前实机反馈修复与验收

## 范围

本轮仅处理朋友圈标题/其他弹窗、相机校准角重复、主动消息无法触发。不上传 GitHub，不开发假面舞会。原有实现及本地历史保留。

## 修复

### 主动消息

对照本地 `0c74300` 的原主动消息 Service，保留已有请求构造、模型参数、生成/工具循环和消息入库链。修复新增调度层造成的阻断：

- dispatcher 锁只覆盖入队和 claim，不再覆盖 provider 的完整网络执行。
- Running/CommitPending 只占用自身聊天窗口，不再挡住所有助手。
- 手动触发同样尝试恢复旧提交；确切拒绝会终止旧提交，未知数据库结果保留原载荷和确定性 IDs，避免重复生成。
- 手动触发绕过自动 STOP/追问上限、冷却和自动预算；自动运行保留相应限制。
- host、Service 与发布前复核统一使用已加载内存状态或完整数据库快照，不把未初始化的占位会话当真实历史。
- 每任务/网络 turn 有 180 秒超时；诊断写入失败不会击穿其他助手；取消按任务隔离。
- Room 明确返回 null 时收敛 CommitPending；Room 已成功时日志账本回执失败不再隐藏已保存消息。
- 发送记录使用确定性提交 IDs，不使用临时 streaming IDs。

### 视频

- 删除根据图像宽高再次追加 90° 的 `portraitRotationAdjustment`。它曾把四档校准折叠为两组，例如基准90°加校准0/90/180/270时错误成为90/270/270/90。
- 现在预览与 JPEG 使用同一 sensor/display/calibration 角度，镜像最后施加。
- 保留竖屏3:4卡片及整图等比fit，不通过旋转猜测或非等比拉伸消除黑边。非3:4源画面仍可能留边；OEM真实方向需要实机复核。
- 系统返回/侧滑只退出面板，明确挂断才结束通话（原有已实现行为保留）。

### 朋友圈

- 宿主标题改为单行小字号并省略溢出；插件内标题改为18px单行。
- “其他”改为固定遮罩、有界高度弹窗，固定标题、独立滚动正文；打开时更新实际内容。
- 支持关闭按钮、遮罩、Escape、焦点返回和Tab循环，离页时清理。
- 搜索默认收起，折叠卡片收窄；原有分享入口按插件安装/启用状态显示的行为保留。

## 本轮实际验证

`build/feedback-final-tests.log`：15个测试类，151项，0失败、0错误、0跳过。包括：

- 4项真实Robolectric Service集成：实际 `onStartCommand`、生产Koin/SettingsStore/ChatService、Room；仅网络provider为捕获请求的fake。证明非前台指定助手、双助手分模型分窗口、manual绕STOP、受阻CP恢复不挡peer请求及落库。
- 6项生产Executor `drain + networkTurn`集成：并发、挂起超时、跨触发锁释放、旧提交占用隔离、诊断失败隔离。
- 44项相机几何测试：四档角度各异、1024种旋转/校准/镜像组合下预览/JPEG逐点对应。
- 主动消息状态/存储/历史/记录UI、摄像头请求隐私等其余定向回归。

插件core（27）、bridge（20）、syntax（12）、modal-layout、ui-smoke五组Node检查全部通过。弹窗测试实际打开并检查内容、焦点及关闭；仍不等于真实Android WebView像素验收。

第二轮由独立审查者读取最终Executor/Store/Service/ChatService发布/Room事务及相机几何代码，未发现新增高置信P1；未把静态审查当运行测试。

本次没有重跑整仓全量测试；历史全量仍存在时间戳、Windows链接、ASR及UI fixture相关失败，不能宣称整仓全绿。

## 产物

- `build/latest-install-apk.log`：优化共存APK构建成功。
- APK：`app/build/outputs/apk/videoCoexist/app-arm64-v8a-videoCoexist.apk`，62,769,957字节。
- APK SHA-256：`30D758EF294566E3CABE56F760E937B1ABDCBDA8E3FFF1A0E542E042A22FD6EE`。
- 包名 `me.rerere.orangechat.videogifts`，versionCode165，2.2.9，arm64-v8a。
- 既有共存测试证书SHA-256：`ff516315e577235b64ff356dbc856c053b7e589ae636dd8bc4301e528b46d116`，未替换正式私钥。签名验证及内置OCR核验通过。
- 插件：`build/local-moments-plugin/local-moments-1.0.0.zip`，55,839字节。
- 插件SHA-256：`059137ED781EAAA61FF7370937F222AACF6CE8D1332BBAF3B740BCB11EEB006B`，确定性打包检查通过。
- 三套主题ZIP未改，构建检查/对比度审计通过。

## 必须保留的边界

- 没有在用户设备上复测：相机最终朝向、黑边、WebView弹窗像素、真实账号网络和锁屏/OEM后台行为仍待安装确认。
- fake provider回归证明真实应用发送与入库链，不证明用户实际服务商连通性或响应速度；“立即触发”不表示模型立即完成回复。
- 已发送给服务端的字节不能撤回；未知提交结果保留载荷而非盲目重发。
- 对话中直接要求助手发布朋友圈的完整工具尚未接通；本轮没有把此前需求缺口声称为已完成。
- 按用户指令暂停所有GitHub上传和授权刷新，不创建Release。
