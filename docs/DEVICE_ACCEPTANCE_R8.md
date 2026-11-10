# R8 礼物卡与共存包瘦身

## 变更

- 持久礼物卡改为明亮白紫渐变、深色文字、浅色边框；长名称换行，数量保留空间，合并读屏语义。
- 礼物属于同一回复，不再添加第二个助手头像行。仅礼物且无思考/工具的完成消息不显示假的“查看思考和工具记录”；真实思考/工具及加载入口保留。
- videoCoexist继承既有release裁剪优化和资源压缩，使用release库fallback，关闭debuggable，但保持.videogifts包名和原共享debug证书。release/videoUpdate签名决策不变。
- 未增加字体/动画素材或删功能资源，未新增全局keep；既有serialization、QuickJS/JNI、JavascriptInterface和OCR规则沿用。既有dontobfuscate保留，准确说是裁剪优化，不是名称混淆。
- 云流水线单独保存共存variant的R8 mapping以供崩溃回溯。

## 本地验证

主模型独立完整回归40类394项，0失败/错误/跳过；发布工具91项通过。
新增5项真实Compose测试覆盖礼物卡两主题背景/文字、200dp与1.6字体长名称、真实ChatMessage两模式重复头像、gift-only对齐和加载状态。
首轮两个测试harness问题（PixelCopy超时、UIAvatar缺Koin）经诊断修正，未放宽头像数或替换生产卡/头像。最终全集通过。

本地优化共存APK62389597字节（62.39MB），原R7云共存106602862字节（106.60MB），减少44213265字节，约41.5%。最终云包实际字节数以下载核验为准。
包名me.rerere.orangechat.videogifts，versionCode165，ARM64，证书SHA256 ff516315e577235b64ff356dbc856c053b7e589ae636dd8bc4301e528b46d116 保持不变；身份/OCR检查通过，aapt未报告application-debuggable。

## 用户验收

不要先卸载旧共存版，尝试直接覆盖升级并确认数据保留。检查带思考链的礼物回复只出现一处发送者，纯礼物无假思考入口；窄屏大字体名称不被截断。检查优化包插件工具、WebView、OCR、语音与记录读取。

尚未真机安装或执行优化包运行时冒烟测试，自动测试不是这类验证的替代。正式包候选仍需原私钥签名，不可用共存debug证书冒充正式更新；无Release发布。
