# 视频通话与虚拟礼物

本文件记录 OrangeChat 视频通话（原生相机面板 + 留影 + 文字弹幕 + 独立虚拟礼物协议）的实现、使用方式、
提示词、隐私与数据生命周期、测试情况、构建环境现状、真机验收清单与已知限制。

- 基线：`4c614d667dae41697a886a68f05d49b792a42209`（来源分支 `build/workspace-official-20261108`）
- 工作分支：`feature/video-call-gifts`（工作树变更，未提交）
- 原则：**保留全部原有语音通话行为**；礼物是**独立的纯虚拟事件**，与红包/转账/钱包/支付完全无关。
- 当前状态（R7 收口）：功能与实现按本文件描述的状态；**真机 / 端到端测试没有执行**（§9.1）。
  验证只有"编译 + JVM 单测 + 服务集成回归（Robolectric）"，构建与 lint 的最终统计以
  `orangechat-admin/RESPONSE_R7.md` 里主模型的验收报告为准（本文件不复制多轮数字）。

---

## 2026-11-11 视频修复更新（优先于下文 R7 历史描述）

- 当前开发分支为 `feature/local-moments-themes-export`，保留原有未提交工作；尚未声明云端发布或真机验收。
- 预览外卡固定宽:高 4:3，完整相机源图在卡内等比留边；输入和动作栏位于有界滚动区外并消费键盘 inset。镜像和用户校准同时用于预览与发送 JPEG；任一变换使旧代帧、待发送帧和许可失效。预览/JPEG 必须精确匹配有向比例，没有匹配时明确停止相机会话。
- 每轮模式以单调时钟约 1 Hz 采样，保留设置的最新 1..9 帧（另有 12 MiB 原始字节上限），随文字或语音等比拼为一张最多九宫格的临时图。30/60 秒模式保持定时待发送帧语义；手动夹帧优先。关摄像头、挂断、换会话拒绝迟到帧；拼图写入取消有所有权清理。
- 请求在转换前后过滤历史摄像头媒体，仅本轮唯一 USER 锚点与首次登记的相机代次授权可注入画面。关摄像头再重开不会恢复旧 token 图片授权。保留历史可见照片，不自动重放给模型。授权检查位于 provider 调用边界；已经进入 provider 内部处理的网络撤销边界尚需传输层进一步验证。
- 自动音频按当前会话实际模型和 provider 切换。Gemini 与 OpenAI ChatCompletions 音频家族受支持；普通 GPT 与 Responses 不冒充音频能力。录音中切到不支持的模型会拒发并说明；GPT 音频模型不会默认继承普通 GPT-4o 的视觉标记。
- 本轮 integrated4 主源码编译通过，data.video 264 项与 VoiceAudioPayload 16 项测试零失败。最终新增变更仍需统一构建；无连接设备，OEM 取景方向/键盘/真实服务与后台行为均未实测。

## 1. 使用方式

### 1.1 入口

1. 打开任意会话 → 输入框右侧 `+` → **视频通话**（和原来的 **语音通话** 入口并列）。
2. 面板上方左侧：助手头像 + 名字 + 计时玻璃徽章（**点一下 = 打断当前生成 / 停止朗读**）。
3. 上方右侧：`🎁 数量`（本次通话收到的礼物数，没送过时不显示）· `⇄`（前后摄像头切换）·
   `📸`（夹住当前帧，随下一句发送）· `✕`（挂断）。
4. 底部输入框：打字发给模型，走的是**语音通话同一条对话链**（同一个消息队列、同一个 TTS、同一套挂断清理）。
5. 返回键 = **挂断**（`BackHandler { hangUp() }`，和点 `✕` 完全一样：结束通话 → 释放相机 → 退出面板）。
   **不是**"离开面板、通话继续"——代码里没有那条路径，以代码为准。

### 1.2 设置（设置 → 语音）

- **视频通话礼物**：默认**关闭**。开启后模型才允许送一件纯虚拟礼物（详见 §3）。
  设置页里直接给出可审查的完整送礼提示词原文。
- **自动抓帧**：关闭 / 每轮一帧（默认）/ 每 30 秒 / 每 60 秒。
  - 每轮一帧：发送前现抓一帧，作为**本次请求的临时图片输入**（不写聊天历史）。
  - 30s / 60s：只刷新"待发送帧"，**不会**自己发请求、**不会**后台自动发送。
- **抓帧画质**：最长边 384 / 512（默认）/ 768，固定 JPEG 质量。
- **显示我打的字**：关闭后只显示模型逐条上浮的气泡。

### 1.3 权限与前台服务类型

| 权限情况 | 服务形态 | 前台服务类型 | 能力 |
| --- | --- | --- | --- |
| 相机 + 麦克风 | 前台服务 | `CAMERA \| MICROPHONE` | 相机预览 + 语音 |
| 只有相机 | 前台服务 | `CAMERA` | 相机预览 + 纯文字（**不启动 ASR**） |
| 只有麦克风 | 前台服务 | `MICROPHONE` | 语音 + 纯文字（画面黑底） |
| 两个都拒绝 | **可见 Activity 绑定运行**（不占任何前台类型，离开页面即结束） | – | 纯文字黑底模式：打字、弹幕、礼物、打断、挂断都可用 |

- 权限请求按**顺序**进行：先请求相机，等它的结果回来再请求麦克风，不会同时 launch 两个请求。
- 之前"两个权限都拒绝就不能通话"的说法是**错的**，已删除：没有权限时不再走前台服务，
  而是退化成可见范围内的会话（这是 Android 允许的做法，不需要滥用其它 FGS 类型）。
- `audioUnavailable`（没麦克风 / ASR 初始化失败）现在真的参与判断：`shouldListen` 直接返回 false，
  麦克风异常不会再把这通电话打成 `Error` 从而破坏纯文字链路（纯语音通话行为不变）。
- **通话进行中的权限变化（R5 修）**：`VideoCallPolicy.startAction` 在"通话已经在前台"时恒为
  `None`，所以它永远处理不了"camera-only 通话后来补到麦克风"这件事（旧实现就是在这里断的：
  再次授权之后不会执行 `service.reconcile`，前台类型不加 `MICROPHONE`，ASR/TTS 也不补建）。
  现在页面观察权限组合变化，调用**只对本会话生效**的
  `VoiceCallService.refreshSessionPermissions(conversationId)`：
  - 补到麦克风 → 前台类型刷新 + 补建语音链路**一次**（幂等，能力组合没变就不做事）；
  - 权限被撤回 → 前台类型跟着减；
  - 两个权限都撤回 → **不再挂着错误的 FGS**：有页面绑着就降级成绑定模式（纯文字继续），
    否则结束这通通话。
- **R6 修：撤麦克风必须真的停录音**。旧实现只把前台类型减回 `CAMERA`，`shouldListen` / `syncMicrophone`
  完全不读 `hasMicrophonePermission()`：麦克风继续录、继续报错，重新授权后也因为语音链路一直算
  "已经建好"而永远是静音。现在：
  - 权限组合里没有 `MICROPHONE` → 立刻 `stop` 麦克风、取消 VAD/ASR/打断/策略协程、释放旧 ASR，
    并把语音链路标成 **not ready**（同时清掉 UI 上的半截转写与振幅）；
  - 重新授权 → `reconcile` 按新能力**补建一次**输入链路（TTS 与当前一轮的计时/转写不动）。
- **R6 修：挂断当场停前台服务与通知**。旧 `endCall` 先 `capabilityState.reset()`、
  再 `if (isForegroundRunning()) stopForeground(...)`，条件恒为 false —— 页面还绑着服务时
  前台与通知会一直留到 `onDestroy`。现在进入收尾时先记住"这通电话确实在前台跑过"，
  在挂断路径上**当场** `stopForeground(STOP_FOREGROUND_REMOVE)`，并 `reset` 一次收尾。
- **R6 修：语音链路按"会话 + 当前权限 + 这一次成功初始化"判定**。`bound service` 会被系统复用
  承载下一通电话：旧代码用 `lateinit` 变量"存在 = 就绪"（`::asr.isInitialized`），
  没有麦克风权限的下一通会把上一通留下的 ASR 当成这次建好的链路，旧转写回调也会串台。
  现在 ASR/TTS/麦克风各带一个**会话号**（`asrToken/ttsToken/microphoneToken == sessionToken` 才算就绪），
  并且：
  - 挂断时**释放**（不只是 stop）ASR/TTS/麦克风，会话号 +1；
  - 转写回调闭包里固定创建时的会话号，旧链路晚到的转写一律丢弃；
  - 初始化就绪只看**这次初始化是否真的建出了 ASR**，不看变量有没有被赋值过。
- **R6 修：初始化在飞期间的重复刷新不再取消/重启**。`CallCapabilityState` 记录"有没有一次针对
  **这组能力**的初始化在飞"：同一组能力的重复刷新直接落 `None`（服务还会用
  `isInitializationInFlightFor(target)` 一层兜底），能力组合变了（撤权/重新授权）才取消旧初始化、
  按新能力重来。补建**不会**再调 `startCall`：它只重建输入链路，计时/转写/正在生成状态都不动。
- **R7 修：初始化**挂起**期间撤麦克风（相机保留）不再留下"假就绪"**。ASR 工厂是 `suspend` 的，
  旧实现只在提交前复查初始化代次：如果撤权发生在工厂挂起期间，`refreshSessionPermissions`
  看到还没有 ASR/麦克风（`hasAsr || hasMicrophone` 为 false）就不取消初始化，于是旧结果照样
  提交成 `audioPipelineReady = true` 并装上麦克风——权限与能力状态已经不符。现在两道闸：
  - 撤权时的对账：**已经提交过输入链路**或**这通电话已经开场**（只可能是"只补建输入"的初始化）
    → 立刻取消并释放；而"全新会话的第一套初始化"**不整条取消**——它还要建 TTS、还要开场，
    取消它会让新会话停在"没开始"，纯文字也接不上（这也就是"不误取消 text-only 新会话的 TTS 启动"）；
  - **提交前**再次检查"当前麦克风权限 + 当前能力组合仍然包含麦克风"：不满足就释放这次建出来的
    ASR，绝不置 ready、绝不装进通话；TTS 与文字通话照常提交，新会话仍然可以打字。
- **R7 修：释放是幂等的**。`releaseAudioPipeline` / `stopMicrophoneAndAsr` 改用
  `hasAsr` / `hasTts` / `hasMicrophone`（会话号仍然匹配）而不是 `::x.isInitialized` 判断，
  挂断、服务复用、`onDestroy` 反复收尾只 cleanup 一次（旧注释说"不重复"，代码其实会重复）。
- **R7 修：旧 provider 回调按"会话 + 实例"双重确认**。转写回调除了会话号，还要求
  `asrState === asr`；ASR 状态流监听同样要求"就是这个实例"。同一通电话里撤权再补建之后，
  旧 provider 迟到的事件不会再驱动 UI。

---

## 2. 相机与生命周期

相机绑定由四个条件**共同**门禁（`VideoCallPolicy.shouldBindCamera`）：

```
Surface 就绪  &&  有相机权限  &&  生命周期 STARTED  &&  通话进行中
```

任一条件不成立 → 立即解绑。因此"授权前 Surface 已经创建、授权后要重新绑定"这件事是自动成立的。

| 事件 | 行为 |
| --- | --- |
| 进入视频面 / 授权变化 / 回前台 | 四个条件都满足才绑定（同一路会话重复 bind 不会重建） |
| 退到后台（`ON_STOP`） | 立即解绑相机 + 停止定时采样 + **清空待发送帧**（自动与手动都作废，不留用旧图） |
| 按返回键 / 点 `✕` | `hangUp()`：结束通话 → 解绑相机 → 清空待发送帧 → 退出面板（返回键**不会**让通话继续） |
| 挂断（面板或通知栏） | 页面观察通话状态 → 立即解绑相机、停计时、清特效队列、清待发送帧 |
| 切换前后摄 | 关闭当前一代相机会话后重新开一代 |

- 相机按"**一代**"（generation）管理：`bind`/`unbind`/`switchCamera`/屏幕方向变化都会换一代；
  旧一代的 `onOpened` / `onConfigured` / `onImageAvailable` / `onDisconnected` / `onError` 回调
  在**同一把锁内**先确认自己仍是当前代才动手，过期时**只释放自己的资源**，
  绝不会重开相机、也绝不会关掉新一代的会话。
- **线程模型（R5 修）**：camera2 的回调在 HandlerThread 上，停止/切镜头在主线程。
  "检查 → 接管资源 → 下发请求 → 发布 status/矩阵"整段都在 `CameraGenerationGate` 的**同一把锁**里
  完成（`exclusive { }` / `ifCurrent { }`），所以不存在"检查时还是当前代、动手时已经被换掉"的窗口。
  R4 的写法是 `@Synchronized isCurrent()` 只包住一次 read，check 与 act 之间 stop/switch 可以插进去，
  旧回调仍会把 status 写回 `Previewing`、或拿已释放的设备下发请求 ——
  **"`@Volatile` + `@Synchronized` 就不会让旧回调复活"这句话不再作为结论**；
  现在由这把锁本身 + 真实多线程交错测试（`VideoCallCameraLifecycleTest`）证明。
  资源释放（`CameraDevice.close()` / `Session.close()` / `ImageReader.close()`）一律在**锁外**：
  先在锁内摘除、再用返回值释放，避免"持控制器锁等 generation 锁"的反向锁序。
- **相机代次与运行时（R5 修）**：控制器每次**实际换代/结束**（切镜头、退后台 `ON_STOP`、打开失败、
  方向变化）都会**同步**调用 `VideoCallRuntime.notifyCameraGenerationChanged(this)`；
  运行时按"本通话 + 就是这个 gateway 对象"的作用域让相机代次失效，并在同一个临界区里清掉
  待发送帧（不存在"失效了但帧还在"的窗口），**不依赖晚到的 Compose effect**。
  `VideoCallRuntime.captureFrame()` 在拿到抓帧互斥锁**之后**与抓帧返回**之后**都会重新确认
  gateway 身份 + 可用 + 代次，排队期间被换掉的旧 gateway 不会被用来抓画面。
  （R4 只在 `register/unregister` 换对象时才 rotate epoch，而 UI 切镜头用的是同一个对象、
  `ON_STOP` 只 `unbind` 不 `unregister`，于是切镜头/退后台之后留影许可仍可消费、旧图仍可能落盘。）
- **R6 修：换代时"运行时失效"必须在释放资源之前、且与 `gate.end` 同一临界区**。
  旧实现是"门闩内结束一代 → 锁外释放相机 → 才通知运行时"，中间那段窗口里运行时仍认为旧代有效，
  排队中的抓帧、已经发出的留影许可、`holdPendingFrame` 都还能消费旧画面。
  现在所有"结束/换代"都经过 production 组件 `CameraGenerationLifecycle`：
  **门闩内**摘除当前代并让运行时失效（epoch +1、清待发送帧、撤销留影许可），**锁外**才释放
  `CameraDevice`/`Session`/`ImageReader`。
- **R6 修：锁序固定为 `gate → runtime`**。控制器在持有相机门闩时调用
  `VideoCallRuntime.notifyCameraGenerationChanged`，所以 runtime 侧任何读取 gateway 的地方
  （`cameraAvailable`、`captureFrame`、`requestSnapshot`）都**不持**自己的监视器去调 gateway
  （`requestSnapshot` 把相机状态读取挪到 `synchronized` 之外）。
  `enter/exit/register/unregister/notify/hold/take/clear` 共用同一把 runtime 监视器保证作用域一致。
  配套回归：`VideoCallRuntimeTest` 用真实线程把"持门闩让 runtime 失效"和"读相机门闩发留影许可"
  卡在一起 —— 如果锁序反过来，两边会互相等锁，测试直接超时失败。
- **R6 修：start 必须重验 surface 归属**。`startInternal` 在门闩内先确认
  `previewAllowed && ownedSurface === surface && ownedTexture === texture`
  （production 纯函数 `cameraStartAllowed`），UI 拆掉 Surface / 关掉预览门禁之后，
  之前排队的 start 不会再把相机开在已经销毁的 texture 上。
- **R6 修：清单权限**。服务声明了 `microphone|camera` 两种前台类型，Android 14+ 每个类型都要有
  对应权限：补上缺失的 `FOREGROUND_SERVICE_CAMERA`（`FOREGROUND_SERVICE_MICROPHONE`、
  `CAMERA` 原本就有）。这正是 `android-lint-main-r5.log` 里唯一的 lint error
  （`ForegroundServicePermission`，`app/src/main/AndroidManifest.xml:339`）。
- **镜头朝向按实际选中的那颗读**：请求前摄但设备只有后摄时，旋转/镜像按**实际** `LENS_FACING`
  计算（旧实现沿用"请求值"，画面方向会错）。
- **抓帧对齐（R5 修）**：每次抓帧一张票据（`CameraCaptureTickets`），**只有 sensor 时间戳严格相等**
  的 JPEG 会被交付。`onCaptureStarted` 还没到（时间戳未知）时，早到的图只进**有界暂存**（默认 2 张），
  等时间戳到了再按精确相等认领；已知时间戳之后的旧图按"作废水位 + 严格递增"丢弃。
  超时/取消/失败一律在 `finally` 里作废票据、清空暂存，迟到的旧图永远喂不给下一次抓帧。
  R4 的漏洞是 `expected == null` 时接受**任意** JPEG："旧请求超时发生在 `onCaptureStarted` 之前 →
  旧图晚到 → 新票据时间戳未知"这条路径会把旧画面当成本次结果。
  `CancellationException` 原样透传（不再被 `catch (Exception)` 吞掉）。
- **抓帧提交与 close 同锁**：开票据 + 提交 `capture()` 与 close/换代互斥；`await` **不持锁**；
  交付前在锁内复核 generation 与票据身份，换过票据/换过代的完成一律丢弃。
- **显示方向（R5 修）**：`DisplayManager.DisplayListener` 随控制器生命周期注册/注销；
  方向真的变了就更新当前代的快照、重新发布预览矩阵、作废在飞的抓帧票据，并同步让运行时相机代次失效。
  R4 的 `displayRotationDegrees` 是"打开那一刻"的 `val`、`updateViewSize` 只改宽高，
  旋转 180°（view 尺寸不变、`onSurfaceTextureSizeChanged` 根本不触发）时预览与照片方向都停在旧角度。

- **方向只转一次（R4 修）**：抓帧请求固定 `JPEG_ORIENTATION = 0`（HAL 不转像素），
  落盘时由 `JpegOrientationPolicy` + `VideoPreviewGeometry.uprightImageAffine` 施加**唯一一次**
  旋转（前摄再镜像）。JPEG 里的 EXIF 只用于诊断，**绝不**叠加成第二次旋转；重新编码后
  文件不含 EXIF，方向只有一种解释。旧实现"请求 90 + 再转 90"在 HAL 真转像素的机型上会双转。
- **预览不拉伸（R4 修）**：`setDefaultBufferSize` 把 SurfaceTexture 的 buffer 设成相机真实输出尺寸；
  再交出 `textureViewAffine` 算出的矩阵 —— 它先**抵消 TextureView 把 buffer 拉伸到视图的那层默认变换**，
  然后做旋转 + 等比 centerCrop + 前摄镜像。旧实现直接在默认拉伸之上 `postRotate` + 等比 `scale`，
  消不掉拉伸，还会留下黑边。
- 与既有 `CameraService`/`CameraTool` 的关系：视频通话预览期间，`camera_capture` 工具会**明确返回**
  "视频通话正在使用相机，请改用通话里的留影"，而不是靠抢占失败来碰运气。
  视频通话语境之外的行为完全不变。

---

## 3. 协议（模型 → 应用）

两个指令只在**进行中的视频通话**里、由**这通通话自己发起的那一轮对话**携带时才执行；
它们永远会从正文里被剥离，**不会**出现在聊天气泡、弹幕、TTS 或下一次模型请求里。

### 3.0 执行时机与作用域（REVIEW_R3 §1 修）

真实生成管线是：

```
onUpdateMessages → transforms(...)        // Streaming：只记录，**正文原样返回**
                 → visualTransforms(...)  // Display：剥离（末尾半截先藏起来）
（生成结束）      → onGenerationFinish(...) // Finish：剥离 + 执行一次
```

**Streaming 绝不改正文**（职责表见 `VideoDirectiveStage.plan()`）。读代码核实过：
`generateModelTurn` 的分片合并基准是 `initialMessages` + `handleMessageChunk`（即**原始**消息），
`transforms(...)` 的输出不会回流成合并基准——所以旧实现"在流式阶段剥离"是**违反契约但侥幸没坏**。
它仍然是错误的方向：`transforms` 的输出正是上层继续 `visualTransforms`/`onGenerationFinish`
并持久化的那一份，在流式阶段就删会让"当前状态"变成被删过的正文；
一旦有人（或未来重构）把这份输出当作下一片 delta 的基准，跨分片标签就会被永久拆散。
`VideoCallDirectiveStageTest` 把这条契约（含那个反例形态）钉住了。

因此协议原文不可能"留到结束再从正文里读"。现在的做法是一层**请求作用域的待执行指令状态**
（`VideoCallDirectiveSession`）：

1. `Streaming` 阶段：只把这一条消息的**累积原文**按消息 ID 记下来并冻结解析结果；不改正文、**不执行**；
2. `Display` 阶段：剥离协议（半截标签先隐藏），给界面/TTS/弹幕；
3. `Finish` 阶段：剥离协议（此时不会再有半截）+ 用第 1 步冻结的结果执行**一次**；
4. 这一轮的生成任务无论在正常结束、异常、取消还是打断时，都会在 `finally` 里清空这份状态。

作用域是**失败关闭**的：

- 必须明确给出 `conversationId`，且等于当前视频通话的会话（`null` 不再被当成"当前通话"）；
- 必须带这通通话自己生成、随队列项不可变传下来的 `requestToken`
  （`QueuedMessage.requestToken → TransformerContext.requestToken`）。同会话里的其它生成
  （后台主动消息、用户在聊天页手打一轮、工具重放）拿不到 token，因此**不能**偷用通话状态；
- 每轮通话最多一枚礼物（同一轮的第二次回退请求也不会再送）；
- 每条回复的礼物还会落在消息注解 `video_call_gift` 上，重放/重入/重新生成时以注解为准去重，
  不依赖内存台账的容量上限。

### 3.1 礼物

执行只认**规范完整标签**（严格白名单写在正则里）：

```
<ws_gift id="heart"/>     或者   <ws_gift>heart</ws_gift>
```

- 接受双引号或单引号、`id` 与 `=` 之间可有空格、`/>` 前可有空格；
- **不接受**（只剥离、不执行）：`<ws_gift id="heart-bad"/>`（不会被截成 `heart`）、
  `<ws_gift data-id="heart"/>`、`<ws_gift id="heart" extra="1"/>`、未自闭合的 `<ws_gift id="heart">`；
- 多个标签按**文本顺序**取第一枚（成对形式不再被单独提前遍历）。

| id | 名称 | 档位 | 全屏特效 |
| --- | --- | --- | --- |
| `heart` | 小心心 | 1 | 否（只出气泡） |
| `bouquet` | 花束 | 2 | 否（只出气泡） |
| `fireworks` | 夏日烟火 | 3 | 是（烟火） |
| `meteor` | 流星雨 | 4 | 是（流星） |
| `galaxy_train` | 银河铁道之夜 | 5 | 是（银河 + 发光列车 + 流星） |

- 三档高档礼物播放 **8.2 秒**原创 Canvas 全屏特效；特效队列上限 3，
  退出通话/离开面板即取消，不影响上方与底部控件触摸。
- 每次播放都带自己的**事件身份**（礼物事件 id = 消息 id + 礼物 id，再加单调序号）：
  连续两枚同档位礼物是两次独立动画，不会因为枚举相同而第二次空屏（回归见
  `VideoGiftEffectQueueTest`）。
- 统计用**独立累计值**：右上角数量与结束通话卡的汇总来自累计计数，
  不受 UI 历史列表上限（100 条）影响；UI 列表本身有上限，防止内存持续增长。

### 3.2 留影

```
<ws_vsnap/>
```

- 只在视频通话里、并且相机确实可用时才生效；**同一回复最多一次**。
- 生效时把当前帧压缩落盘，以一条用户侧图片消息写进聊天（"📷 留影（视频通话）"）。
- 相机不可用 / 模型不识图 / 保存失败时**不会假装成功**：不插图片，并把准确原因注入下一轮请求；
  这些写入协程都挂在通话自己的子作用域上，挂断即取消，写入前还会再校验一次"调用是否仍然有效"。
- 模型不识图时：不抓帧、不附图，注入"当前模型不支持图片输入"的说明。

---

## 4. 注入给模型的提示词

每轮请求的临时 system prompt = 「朗读风格」+「视频上下文」。视频上下文**基于本轮实际结果**构造：

- `pictureAttached`（本轮是否真的附上了画面，抓帧/压缩失败时为 false）、
- 相机是否可用、模型是否识图、抓帧频率说明、留影说明、礼物开关与已送礼汇总。

`<ws_vsnap/>` 说明在"相机可用 + 模型识图"时注入（**与本轮有没有附图无关**）：
关闭自动附帧、定时模式没到点、本轮抓帧失败时，模型仍可主动请求留影；
但"本轮没有附带任何画面，不要描述对方的样子或环境"的约束同时保留，不许声称已经看见。

### 4.1 手工夹帧 vs 自动抓帧

| 来源 | 保存位置 | 是否进聊天历史 |
| --- | --- | --- |
| `📸` 手动夹住的一帧 | `filesDir/video_call_clips/*.jpg` | 是（显式动作，永久图片） |
| 定时 / 每轮一帧的自动帧 | `cacheDir/video_call_frames/*.jpg` | **否**：只作为本次请求（含工具多步）的临时图片输入，请求结束/取消即删除 |
| `<ws_vsnap/>` 留影 | `filesDir/video_call_snapshots/*.jpg` | 是（模型留影，永久图片） |

自动帧因此不会无限占磁盘，也不会被反复送进之后的上下文；后续历史里只有文字。

**所有权（R3 建立，R4 改成保守交接）**：临时图/永久帧都不再由"扫目录"清理，而是**显式的所有权集合**。

- `QueuedMessage.transientImageUrls`：本次请求专用的临时帧，只有这一个持有者；
- `QueuedMessage.ownedImageUrls`：**本次新建、还没有被聊天历史引用**的永久帧（手动夹帧）；
- 队列只终结 **pending**（从未提交到历史）的项：`MessageQueue.clear()` 不再 `withdraw`
  in-flight 的项，所以会话删除/清理不会删掉一张**已经写进历史**的手动照片
  （旧实现按目录删 `parts` 里所有 `Image`，用户重发一张历史截图时会把那张照片删掉）；
- in-flight 的永久帧所有权在**请求本身**，而且不是一个布尔：
  `FrameFileOwnership` 用三态机 `OWNED → PUBLISHING → PUBLISHED` 做**保守交接**。
  `saveConversationLocked` 是"先 `updateConversation()` 发布进内存历史 → 再 Room 挂起提交"，
  取消可能落在中间，此时调用方**无法知道**事务提交没有；所以：
  - 进数据库**之前**先 `markPublishing()`：从这一刻起文件"可能已被引用"，两条兜底路径
    （job 的 `finally` 与 `invokeOnCompletion`）都**不允许**删；
  - 正常返回 → `markPublished()`，永久保留；
  - 普通（非取消）异常 → 事务一定回滚，但仍要查会话状态里**是否真的发布过**这条消息：
    发布过就按已引用处理，确实没发布才退回可删除（`markSaveFailed()`），正常失败照样能清干净；
  - `claimDeletion()` 是幂等的 CAS，两条兜底路径同时跑也只有一条真正删。
  旧 Boolean 实现"取消就删"会在这种中间态把历史照片删掉，留下永远修不回来的断图。
- 临时帧永远由请求的 `finally` 删除，**不会**比 job 停止更早被队列清掉；
- 写文件：`persistFrame` 用 `NonCancellable` 写盘，**清理放在外层 `try/finally`**——
  `withContext(NonCancellable)` 切回调用方 dispatcher 时会做一次 prompt cancellation 检查并直接抛异常，
  旧实现"`val url = withContext(...)` + 事后 `ensureActive()`"在那次取消里根本拿不到 url，
  文件就漏在磁盘上没人知道；
- 文件名从"毫秒时间戳"改成 **UUID**，并且先写 `.part` 临时文件再原子 `rename`
  （并发同 prefix 落盘不会再互相覆盖，写失败会删掉残片）；
- 挂断/开始通话只按**安全龄期**（30 分钟）清扫孤儿临时帧，不再整目录删（旧实现会删掉
  别的请求正在使用的图）。

**暂停（REVIEW_R3 §4 修）**：暂停只影响 pending 项，且必须同时做三件事——
摘掉临时图与其文件、把 `transientSystemPrompt` 换成**无图版本**
（`transientSystemPromptWithoutImages`；永久手动夹帧仍在 `parts` 里时保持原说明）、
**撤销并清空 requestToken**（恢复队列是一次全新请求，不能复用暂停前的送礼/留影授权）。

### 4.1.1 一轮请求的授权（REVIEW_R2 §3 修）

送礼/留影不再靠"最后一条 USER 消息 ID"这种可复用伪身份：

```
VoiceCallService 生成 requestToken
  → ChatService.submitMessage(requestToken=...)
  → QueuedMessage.requestToken（不可变）
  → GenerationHandler.streamText(requestToken=...)
  → TransformerContext.requestToken
  → VideoCallRuntime.grantGift/requestSnapshot(conversationId, requestToken, ...)
```

`VideoCallTurnLedger` 只认"有效 call epoch + 这个 token"，并且区分**三种结束**：

| 结束方式 | 方法 | token | 已发出的留影许可 |
| --- | --- | --- | --- |
| 正常完成 | `completeTurn` | 关闭 | **保留**（合法的异步留影落在请求结束之后） |
| 取消/打断/失败 | `cancelTurn` | 关闭 | **全部撤销**（被打断的回复不该还能写照片） |
| 挂断/换通话 | `endCall` | 全部关闭 | 全部撤销 |

`beginTurn` 是**幂等**的：同一个 token 重复登记返回既有凭证，绝不重置已经消耗掉的
"每轮一枚礼物"名额（旧实现每次登记都新建 Grant，一次重复登记就能让同一条回复再送一枚）。
`<ws_vsnap/>` 另发一次性 `VideoSnapshotPermit`（受 call epoch 与 camera epoch 约束，
并记住申请它的 `requestToken`），相机换代/挂断之后不会再插入一张旧照片。

**留影许可的校验/消费在保存锁之内（R4 修）**：`consumeSnapshotPermit` 不再在 `appendVideoCallMessage`
之前调用，而是作为 `validate` 回调传进去，在**拿到 `saveMutex` 之后、第一次发布消息之前**执行
（`appendVideoCallMessage` 返回"是否真的发布"）。旧顺序是"先消费许可 → 再等保存锁"：
等锁期间挂断/离开面板/相机换代都不会取消这个协程（通话本身没挂断），一张已作废的照片照样落库。

token 的生命周期只由**持有它的那个请求**决定：`VoiceCallService.dispatchTurn` 的 `finally`
只关闭它自己的局部 token（全局 `activeRequestToken` 只在相等时才清），所以上一轮抓帧协程
晚到的 `finally` 不会把新一轮的授权关掉；提交成功之后所有权移交给 `ChatService` 的 job
（正常完成 → `completeTurn`，失败/取消 → `cancelTurn`）。

### 4.2 送礼提示词（人工审查原文，开启礼物后才注入）

```
虚拟礼物（可选，纯屏幕动画）：
- 如果想表达心情，可以在回复里送一件虚拟礼物，格式为 <ws_gift id="礼物id"/>。
- 可用礼物：heart（小心心）、bouquet（花束）、fireworks（夏日烟火）、meteor（流星雨）、galaxy_train（银河铁道之夜）。
- 每条回复最多送一件；同一条回复里重复写标签也只算一次。
- 送礼之后请用一句自然的话说明为什么想送，不要重复标签，也不要罗列参数。
- 用户如果提出轻松的虚拟互动（例如“你陪我聊完这段我就送你”“你答应我一个小心愿才收”），可以顺着演，这只是角色扮演。
- 礼物只是通话里的动画，没有真实金额、不能兑换，也不需要付费、充值或打赏。
- 严禁把礼物和真实金钱、真实价值、充值、打赏、支付或任何形式的现实交易绑在一起，也不要暗示送礼能换来现实回报。
- 不要催促、施压或反复索要礼物；对方不送也照常聊。
- 只使用上面列出的 id；其它 id 会被忽略，也不要发明新的礼物格式。
```

未开启礼物时注入："本次通话没有开放礼物功能；不要输出 `<ws_gift ...>` 之类的标签，即使输出也会被忽略。"

> 礼物默认关闭，**在被人类审定之前不会主动启用**。本轮按审查意见调整了措辞：
> 允许用户提出的轻松虚拟谈条件，同时**仍然严禁**真实支付/真实价值诱导。

---

## 5. 隐私与数据生命周期

| 数据 | 位置 | 生命周期 |
| --- | --- | --- |
| 相机预览帧 | 不落盘 | 只用于 `TextureView` 预览（旋转/镜像由应用矩阵完成） |
| 待发送帧（手动/定时） | 进程内存（只保留 1 帧） | 发送即消费；退后台 / 离开面板 / 挂断立即丢弃 |
| 自动抓帧（本轮请求用） | `cacheDir/video_call_frames/*.jpg` | 请求结束（含失败/取消）删除；通话开始/结束再兜底清空 |
| 手动夹帧 | `filesDir/video_call_clips/*.jpg` | 随消息持久化，不删除（避免历史引用不存在的文件） |
| `<ws_vsnap/>` 留影 | `filesDir/video_call_snapshots/*.jpg` | 持久图像消息 |
| 礼物 | 消息注解 `video_call_gift` | 仅礼物 id/名称/时间；无金额、无钱包、无支付 |
| 通话礼物汇总 | 结束通话卡的 `giftSummary` 一行 | 与通话卡一起持久化 |

- 抓帧永远由**用户可见的面板**驱动：面板不在前台时 `cameraAvailable == false`，服务端不会再抓帧。
- 全程没有任何钱包/余额/支付调用；`ChatInteraction` 的 kind 也未扩展。

---

## 6. 实现位置

新增（纯逻辑，可 JVM 单测）：

- `data/video/VideoGift.kt` — 五档白名单、严格协议解析/剥离、按消息 ID 去的台账、计数与汇总
- `data/video/VideoGiftAnnotation.kt` — 礼物注解的持久化投影（需要 ai 模块的 `UIMessageAnnotation`，
  单独拆出来，好让 `VideoGift.kt` 保持纯 Kotlin、能被 JVM 单测真正编译执行）
- `data/video/VideoCallRuntime.kt` — 进程内桥接：失败关闭的作用域、请求 token 授权、礼物/留影、累计统计、单帧缓存、按通话/相机代次注册与校验
- `data/video/VideoCallTurnLedger.kt` — **轮次授权台账**（幂等 request token + call epoch + camera epoch + 一次性留影许可 + `completeTurn`/`cancelTurn` 区分）
- `data/video/VideoCallPolicy.kt` — 权限组合/前台服务类型/是否监听/相机门禁/每轮令牌/前台能力对账/页面启动意图/通话中的权限刷新动作（纯函数）
- `data/video/CameraGenerationGate.kt` — **相机代次串行化门闩**（同一把锁内 check+act、换代返回值在锁外释放；纯 Kotlin，可做多线程确定性交错测试）
- `data/video/CameraCaptureTickets.kt` — **抓帧票据 + JPEG 时间戳对齐**（严格相等才交付、早到帧有界暂存、超时作废水位与暂存清理）+ **显示方向变化检测**（纯 Kotlin）
- `data/video/VideoPreviewGeometry.kt` — 预览旋转矩阵、JPEG 方向、显示方向常量映射的**同一套纯计算**
- `data/video/VideoFramePolicy.kt` — 抓帧频率、画质、缩放、定时器、单帧缓存（`VideoFrame` 带旋转/镜像信息；手动帧优先）
- `data/video/VideoCallPrompts.kt` — 注入提示词（含送礼原文、"本轮无画面"、留影可用性）
- `data/video/VideoCallSession.kt` — 只保留相机状态枚举；**纯测试用的状态机已删除**
- `data/video/VideoCallSetting.kt`、`data/video/VideoDanmaku.kt` — 设置数据 / 逐句气泡切分
- `data/ai/transformers/VideoCallDirectiveSession.kt` — **三阶段职责表（`VideoDirectiveStage.plan`）+ 请求作用域的累积原文/静态解析结果**（纯 Kotlin，可 JVM 单测）
- `data/ai/transformers/VideoCallDirectives.kt` — 按职责表改写消息的纯处理逻辑（Streaming 原样返回）
- `service/VoiceCallListening.kt` — "这一轮要不要继续听麦克风"的纯判断（从 `VoiceCallMicrophone` 抽出，便于 `VideoCallPolicyTest` 真跑）

新增/重写（Android）：

- `data/ai/transformers/VideoCallOutputTransformer.kt` — 三阶段薄壳（Streaming/Display/Finish）
- `data/video/VideoFrameStore.kt` — 旋转/镜像压缩、临时/夹帧/留影三种落盘策略、临时文件清理
- `service/VideoCallCoordinator.kt` — 视频侧状态（气泡/计数/留影/抓帧），自己的子作用域 + 关闭即取消
- `service/VideoCallMessageStore.kt` — 追加留影消息
- `ui/pages/video/VideoCallCameraController.kt` — camera2 预览 + 抓帧（按代管理、sensor/display 旋转、前摄镜像）
- `ui/pages/video/VideoCallPage.kt` — 全屏面板（四条件相机门禁、顺序权限请求、真正的上浮弹幕、冲突提示）
- `ui/pages/video/VideoGiftEffects.kt`、`ui/components/message/VideoCallGiftCard.kt`、`ui/pages/setting/VideoCallSettingsSection.kt`

修改（保持原行为，只做增量）：

- `ai/.../ui/Message.kt`：新增 `UIMessageAnnotation.VideoCallGift`；`ChatInteraction` 增加默认字段 `giftSummary`
- `data/ai/transformers/Transformer.kt`：`TransformerContext` 增加 `conversationId`、请求作用域的 `videoDirectives` 与 `requestToken`
- `data/ai/GenerationHandler.kt`：每轮生成创建/清空指令状态（try/finally）；`transientImageUrls` + `transientImageAnchorMessageId`（锚定触发消息，不是"最后一条 USER"）+ `requestToken`
- `service/ChatService.kt`：注册输出转换器；`submitMessage` 支持临时图片、**显式所有权**（`ownedImageUrls`）、
  `transientSystemPromptWithoutImages` 与 `requestToken`（去掉可失败的 `onEnqueued` 副作用）；
  队列终结回调只删**显式所有权**里的文件；请求的 `finally` 按"用户消息是否真的落库"决定删帧与 token 结局；
  `finishInteractionCalls` 增加 `giftSummary`
- `service/MessageQueue.kt`：`QueuedMessage.transientImageUrls` / `ownedImageUrls` / `transientSystemPromptWithoutImages`
  / `requestToken`、`onDiscardTransientImages` / `onMessageDiscarded` / `onRequestTokenRevoked`；
  `clear()` **只终结 pending**（in-flight 归请求）；暂停时摘除本轮附图、改写无图提示、撤销 token
- `service/VoiceCallService.kt`：`reconcileServiceCapabilities`（幂等前台对账，消除 5 秒崩溃路径）、初始化代次/Job、稳定 panelState、绑定模式 ON_STOP 结束、每轮 token/job（打断/挂断先失效再取消再撤回）、`audioUnavailable` 真正生效、按能力组合的前台服务类型、无权限时的绑定运行模式、`requestInterrupt()`、视频协调器
- `data/ai/tools/CameraTool.kt`：视频通话占用相机时明确返回"请用留影"
- `data/datastore/PreferencesStore.kt`、`ui/.../SettingSpeechPage.kt`、`ChatMessage.kt`、`RichChatCards.kt`、
  `FilesPicker.kt`、`RouteActivity.kt`、`ChatCallOverlay.kt`、`AndroidManifest.xml`（`microphone|camera`）

---

## 7. 测试

### 7.1 JVM 单测（真实编译 + 真实执行）

生产文件与测试文件都不经过任何 stub：`orangechat-admin/RUN_CORE_TESTS.ps1` 用 Kotlin 2.3.21
编译器编译仓库里**真实的**纯 Kotlin 生产文件，再用 JUnit 4.13.2 执行它们的真实测试；
Android 侧（含 Robolectric 服务集成回归）由 `orangechat-admin/RUN_ANDROID_BUILD.ps1` 驱动 Gradle 单测。
用例数量会随轮次增长，**本文件不记具体数字**：每一轮的统计、日志和验收结论见
`orangechat-admin/RESPONSE_R7.md` 与主模型的验收报告。

| 文件 | 覆盖点 |
| --- | --- |
| `data/video/VideoGiftProtocolTest.kt` | 严格/非法标签、`id="heart-bad"` 不被截断、`data-id`、文本顺序、分片半截（含 `<`、`<w`）、去重台账、内存上限、档位汇总 |
| `data/video/VideoFramePolicyTest.kt` | 四种抓帧频率、间隔、缩放、画质、定时器、单帧缓存、手动帧优先 |
| `data/video/VideoCallPromptsTest.kt` | 礼物默认关闭、审查原文、允许虚拟谈条件但仍禁真实支付、"本轮无画面"、识图能力、**留影与`本轮无图`分离**、已送礼汇总 |
| `data/video/VideoCallSerializationTest.kt` | 设置默认值/序列名、旧 JSON 兼容、`giftSummary` 与礼物注解往返（需要 app 模块的 `JsonInstant`，只能在 Gradle 单测里跑） |
| `data/video/VideoDanmakuTest.kt` | 句末切分、多句、长句折行、复位 |
| `data/video/VideoCallTurnLedgerTest.kt` | **请求 token 授权**（无 token / 伪造 token / 跨会话 / 取消/挂断/换通话后失效）、**重复登记不刷新礼物名额**、**完成保留许可 / 取消撤销许可**、留影许可一次性、相机换代失效、容量上限 |
| `data/video/VideoPreviewGeometryTest.kt` | **逐点几何**：TextureView 默认拉伸被抵消后的 `M ∘ S` 必须等于想要的变换、1280x720→1080x1920 四角精确落位、4:3 塞进竖屏是 centerCrop（角点越界、上下贴合）、任意旋转/镜像都铺满且等比、交给 TextureView 的矩阵**必须非等比**（否则就是那个伪正确公式）、抓帧归一化的像素级方向（左上→右上）与宽高互换 |
| `data/video/JpegOrientationPolicyTest.kt` | **只旋转一次**：HAL 被请求的角度恒为 0；对 EXIF 0..8 × 四种转正角，`HAL角度 + 我们施加的角度` 恒等于转正角且 `pixelRotations ≤ 1`；EXIF 声明的旋转只诊断、绝不叠加；置乱 EXIF 表符合规范 |
| `data/video/FrameFileOwnershipTest.kt` | **文件所有权交接**：取消落在发布中间态时**不许删**、确认提交后永不删、明确失败可退回删除、礼物轮次初始即持久、重复删除只成功一次、8 线程并发认领恰好一个赢家、200 轮"发布 vs 认领"竞态 |
| `data/video/VideoCallPolicyTest.kt` | 权限组合与前台服务类型、无权限绑定模式、`audioUnavailable`、相机四条件门禁、每轮令牌失效（含并发）、前台能力对账、一次启动意图、授权弹窗/页面重建/挂断的真实门禁走向、**纯语音无麦克风不可前台（"前台 ⇒ 能力集非空"不变量）**、**通话中补到麦克风必须刷新（`startAction` 恒 None 的反例）/权限撤回降级/刷新只对本会话生效** |
| `data/video/VideoCallCameraLifecycleTest.kt` | **真实多线程交错**：回调"检查通过之后、动手之前"被挂起时 stop 必须被挡在锁外（顺序绝不可能是 stop 插进中间）、过期回调的 block 一次都不执行、换代返回值用于锁外释放；**抓帧票据**：未知时间戳不接受任意 JPEG、早到帧只在时间戳严格相等时被认领、暂存有界、超时作废水位、`onCaptureStarted` 之前超时 + 旧图晚到的具体顺序不会被新票据吞掉、帧时间戳严格递增；**方向**：同一角度连发不重复处理、180° 尺寸不变时矩阵仍然必须变、`ROTATION_* → 角度` 映射；**R6**：结束一代时运行时失效与 `gate.end` 同临界区（失效回调里另一个线程拿不到门闩）且发生在释放之前（释放时留影许可已不可用）、`cameraStartAllowed` 拒绝已 detach 的 surface/已关的门禁 |
| `data/video/VideoCallRuntimeTest.kt` | **作用域/所有权**：异会话不许接管或挂断、相机注册与换代、**晚到的帧（换代/异会话）被拒**、异会话不许清待发送帧、手动帧优先、token 才是送礼唯一凭据、每轮一枚、**正常完成保留/取消撤销留影许可**、抓帧 fail closed、取消原样透传、**控制器换代同步失效（同作用域清待发送帧、别的 gateway/挂断后是空操作）**、**排队期间/抓帧途中换代或换 gateway 的帧被丢弃**、**R6：换代让已发出的留影许可与待发送帧同时失效**、**R6：真实线程锁序回归（持门闩失效 vs 读门闩发许可不得互等）** |
| `service/VoiceCallServiceLifecycleTest.kt` | **真实 Robolectric 服务集成**（`Robolectric.buildService`，前台/通知断言来自真实 `ShadowService`，权限来自真实 `ShadowApplication`）：**挂断当场 stopForeground**、撤麦克风停录音+释放 ASR+降级绑定、重新授权只补建一次且不重置计时、初始化未完成时重复刷新不重启、**复用的服务不把上一通 ASR 当成就绪且旧转写不串台**；**R7**：ASR 工厂挂起期间撤麦克风（相机保留）→ 旧结果不提交、不录音、不复用，TTS 与开场照常，再授予只建一个**新**实例；补建期间撤权同样拒绝旧结果（不置 ready、不登记当前链路）；挂断/复用/`onDestroy` 反复收尾只 cleanup 一次 |
| `data/ai/transformers/VideoCallDirectiveStageTest.kt` | **三阶段职责表**（Streaming 不剥离）、**真实累积 delta 下流式剥离会把标签拆散的反例**、解析结果冻结/幂等/有界 |
| `data/ai/transformers/VideoCallDirectivesTest.kt` | **真实 Transformer 包装**（`runStage`，三个 override 共用）按真实顺序驱动、Streaming 原样返回、Display 剥离、多分片累积、普通 HTML 不执行、非通话请求复用同会话+用户 ID 仍失败、重复 finish 不重发、跨会话/无 token 失败关闭、留影许可、**挂断/换代后 hold 被拒**、**异会话不能清当前通话**（需要 ai 模块的 `UIMessage`，在 Gradle 单测里跑） |
| `data/video/VideoGiftAnnotationTest.kt` | 礼物注解 id/giftId/caption 稳定（需要 ai 模块，在 Gradle 单测里跑） |
| `service/MessageQueueTest.kt` | FIFO/编辑/暂停等原有行为 + 请求 token 随队列项传递、**clear 只终结 pending（in-flight 归请求）**、**暂停改写无图提示并撤销 token**、终结只触发一次（在 Gradle 单测里跑） |

`data/video/VideoCallSessionTest.kt` **已删除**（REVIEW_R2 §6）：它测的
`VideoCallSessionStateMachine` 生产代码从不使用，属于"测一个不用的状态机"。

### 7.2 主模型最终复核

- 功能过滤集：**21 类 / 224 个测试，0 失败、0 错误、0 跳过**，Gradle 实际通过。
- 独立纯 Kotlin 核心编译与 JUnit：**154 个测试通过**；与上面的 Gradle 测试有重叠，不应相加当作独立覆盖数。
- 最终 `:app:assembleDebug` **成功**，产出 ARM64 调试 APK；没有安装或真机验证。
- 扩大检查的 app 全套（最后一项动画身份修复之前）：**1691 个测试，2 失败、1 跳过**。
  失败分别是未修改的 `GenerationFlowCompatibilityTest` 时间戳往返比较，以及
  `WorkspaceRoutingTest` 在 Windows 创建 junction 的环境步骤；没有通过删测试/改断言使其全绿。
- 全模块 `testDebugUnitTest` 在未修改的 `highlight` 模块测试编译处失败（缺 JUnit 依赖）；不能声称全仓测试通过。
- Lint 检查发现的相机前台服务权限遗漏已经补齐；仍有基线 `InteractionGiftDesign.kt:305` 的
  `NonObservableLocale` 错误。最终报告与完整证据以交付目录 `ACCEPTANCE.md` / `validation-evidence.zip` 为准。

### 7.3 命令

```powershell
# 1) 纯 Kotlin 核心编译 + JUnit（本环境可跑，不需要 Android SDK）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\DSH-Work\orangechat-admin\RUN_CORE_TESTS.ps1

# 2) Android 编译 / 单测 / lint（便携 JDK 17 + Android SDK 37 + Gradle 依赖，脚本自己设环境变量）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\DSH-Work\orangechat-admin\RUN_ANDROID_BUILD.ps1 -Stage test
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\DSH-Work\orangechat-admin\RUN_ANDROID_BUILD.ps1 -Stage assemble
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\DSH-Work\orangechat-admin\RUN_ANDROID_BUILD.ps1 -Stage lint
```

- **JDK 说明（如实）**：Android 构建脚本用的是便携 **JDK 17**（`android-build-tools\jdk17\zulu17.70.113-...`，
  Gradle daemon 跑在 17 上，不是 21）；核心 JVM 测试脚本用的是便携 **JDK 21**
  （`android-build-tools\jdk\zulu21.54.109-...`）。两者都在 `D:\DSH-Work\android-build-tools` 下，不改系统环境。
- `app/google-services.json` 不存在，但本仓库**没有**应用 `com.google.gms.google-services` 插件
  （`build.gradle.kts` 里没有该插件，也没有 Firebase 依赖），所以它**不是**当前构建的阻塞项。
  仓库根目录 `AGENTS.md` 里"构建需要 `app/google-services.json`"是过时说明，以构建脚本的实测结果为准。

### 7.4 验证边界（如实）

| 检查 | 结果 |
| --- | --- |
| 生产 + 回归文件的真实类型检查（真实 `android.jar 37` + Compose/Koin/Robolectric classpath，K2JVMCompiler 2.3.21） | 通过（只有既有 warning） |
| `VoiceCallServiceLifecycleTest`（真实 Robolectric 服务集成，含 R7 新增 3 例） | OK（8 tests） |
| 变异验证（把服务复制一份、只改回 R7 之前的撤权/cleanup 行为） | 3 条 R7 用例按预期失败，5 条既有用例仍通过 |
| 纯 Kotlin 核心测试（`RUN_CORE_TESTS.ps1`） | 由主模型执行，统计见验收报告 |
| `:app:testDebugUnitTest` / `:app:assembleDebug` / `:app:lintDebug` | 由主模型执行，统计见验收报告（本轮按要求不并行 Gradle） |

两层限制保持不变：

1. **只有编译 + JVM/Robolectric 验证**：前台服务在真实系统上的行为、Android 14+ 的 FGS 权限、
   厂商 ROM 的权限撤回广播、camera2/HAL 的真实回调顺序，都要真机（§8）才能确认。
2. **Robolectric 不是设备**：`ShadowService` / `ShadowApplication` 能证明"权限被撤回、前台被停之后
   代码做了什么"，证明不了系统什么时候发这些事件。

临时验证工具（`orangechat-admin/validation/` 下的 args、classes、变异副本）**不在仓库里**，只是本机自查；
正式执行始终是 `RUN_ANDROID_BUILD.ps1`（过滤表里的 `--tests *VoiceCall*` 会命中
`VoiceCallServiceLifecycleTest`，不需要改过滤表）。

`data/video/VideoCallDirectivesTest.kt`、`service/MessageQueueTest.kt`、`VideoGiftAnnotationTest.kt`、
`VideoCallSerializationTest.kt` 需要 ai 模块/Android 依赖，不在核心脚本里，由 Gradle 单测执行。
`JpegOrientationPolicyTest` / `FrameFileOwnershipTest` / `VideoCallCameraLifecycleTest` 由核心脚本执行，
同时已在 `compileDebugUnitTestKotlin` 下编译通过。

> 源码写入约定：本仓库的 `.kt`/`.md` 只按 UTF-8（无 BOM、LF）写入，不用 PowerShell 默认编码改文本。

---

## 8. 真机验收清单（**尚未执行**，需要真机逐条过）

> 下面每一条都**没有**在本环境执行过：目前只有编译 + JVM 单测 + Robolectric 服务集成，
> 没有真机、没有模拟器、没有安装 APK。

1. **入口**：`+` 菜单里同时看到"语音通话"和"视频通话"；语音入口行为与改动前一致。
2. **面板**：进入后镜头画面铺底、方向正立、前摄镜像正确、**无拉伸无黑边**（R4 改了预览矩阵，
   这一条是重点回归项）；左上角显示助手头像+名字+计时；点击徽章能停止当前朗读/撤回正在生成的一轮。
3. **相机**：`⇄` 能切换前后摄；`📸` 提示"已夹住一帧"，下一条消息带上这张图（聊天里能看到图片）。
4. **定时抓帧**：设为 30/60 秒后，只在下一轮发送时带最新一帧；不发送时不产生任何请求。
   发送后聊天历史里**只有文字**，`cacheDir/video_call_frames` 不残留（可用文件管理器核对）。
5. **拒相机权限**：面板黑底，打字/弹幕/礼物/挂断可用；模型回答不会声称看到画面。
6. **拒麦克风权限**：仍能纯文字聊天（前台类型只有 CAMERA，不应启动 ASR）。
7. **两个权限都拒绝**：面板仍可用（绑定模式）；退出页面后服务自动结束（通知栏无残留）。
8. **不识图模型**：不附图、不抓帧，模型不会假称看到画面。
9. **留影**：让模型输出 `<ws_vsnap/>`，聊天里出现"📷 留影（视频通话）"+图片；相机不可用时不出现图片。
   **新增回归**：抓到留影的同时立刻按返回键/挂断 → 不允许出现一张"作废但仍写进聊天"的照片。
10. **礼物（先在设置里开启）**：模型输出 `<ws_gift id="fireworks"/>` 时正文里看不到标签，
    面板出现气泡 + 8.2 秒全屏烟火，右上角计数 +1，聊天里出现持久礼物卡；重复写只出一次。
11. **礼物关闭**：同样输出标签，不出现气泡/特效/卡片，正文与 TTS 里也没有标签。
12. **非法标签**：让模型输出 `<ws_gift id="heart-bad"/>`，不应出现礼物（也不应出现标签）。
13. **打断**：生成中打字发送/点徽章 → 旧一轮不再上传画面、不再发请求，新轮不混入旧内容。
14. **结束通话卡**：挂断后通话卡显示"🎁 小心心 ×1 · 花束 ×2"汇总行（无礼物时不显示）。
15. **冲突**：会话 A 通话中，从会话 B 打开视频面 → 明确提示另一通电话进行中，不能发送/挂断它。
16. **生命周期**：退后台后镜头立即关闭（系统相机占用指示消失）；重入不会"相机已被占用"；
    从通知栏挂断时，若页面还在，镜头也会立刻关闭。**返回键 = 挂断**（不是离开面板继续）。
17. **内存**：连续多轮对话、连续送礼后，气泡数量有上限、特效队列有上限、弹幕按生命周期消失。
18. **照片方向（R4 重点）**：竖屏/横屏各拍一张手动夹帧和一张 `<ws_vsnap/>` 留影，
    照片必须是正立的（不能躺倒、不能倒 180°、前摄不能是没镜像的反字）。
    这一条专门验证"只转一次"——旧实现在 HAL 真转像素的机型上会双转。
19. **迟到帧（R4）**：反复快速连拍/连发，确认不会出现"这一轮附的却是上一轮那张更旧的画面"。
20. **相机切换竞态（R4）**：快速连点 `⇄` 多次，不应出现黑屏、崩溃或"相机已被占用"。
21. **相机回调竞态（R5 重点）**：切镜头的**同一瞬间**退后台/挂断（或反复快切），
    面板不应出现"已经挂断但仍显示预览中"、黑屏、或"相机被占用"。
22. **屏幕旋转（R5 重点）**：竖屏 ↔ 横屏 ↔ **反向竖屏（180°）**，预览画面必须始终正立；
    旋转之后立刻 `📸` 夹帧，照片方向必须与当时看到的一致（不能是旋转前那一帧的方向）。
23. **权限补授（R5 重点）**：camera-only 进入视频通话（拒绝麦克风）→ 在系统设置里补授权 →
    回到面板应变成"相机 + 语音"，并且**只补建一次**（不重复初始化、不崩）；
    再把权限撤掉，面板不应因为一个错误类型的前台服务通知而残留。

---

## 9. 已知限制

### 9.1 **硬件 / 端到端测试没有执行**（最重要的一条）

本仓库到目前为止的所有验证都只到"编译 + JVM 单测 + Robolectric 服务集成"为止：

- **没有**真机、**没有**模拟器、**没有**安装 APK，`connectedDebugAndroidTest` 未运行；
- 因此相机预览是否真的正立/无拉伸、照片是否真的只转一次、迟到帧过滤是否生效、
  前台服务与权限弹窗的真实行为、通知栏挂断、TTS/ASR 实际链路、DisplayManager 回调的
  真实时序与厂商 ROM 差异——**全部未经真实设备验证**；
- 几何与策略靠 `VideoPreviewGeometryTest` / `JpegOrientationPolicyTest` /
  `FrameFileOwnershipTest` / `VideoCallCameraLifecycleTest` 在 JVM 上逐点验证，但
  `Bitmap.createBitmap`、`TextureView`、Camera2 HAL、`DisplayManager` 的真实行为
  只能由 §8 的真机清单确认。

### 9.2 其他限制

1. **测试范围**：核心纯 Kotlin 部分由 `RUN_CORE_TESTS.ps1` 用真实 Kotlin 编译器 + JUnit 执行；
   Android 侧（含 `VoiceCallServiceLifecycleTest` 的 Robolectric 服务集成）由
   `RUN_ANDROID_BUILD.ps1 -Stage test` 跑视频/队列/语音相关类。两者都**不是**真机验证，
   具体用例数见验收报告。
2. **相机线程安全能证明到哪一步**：`CameraGenerationGate` 的"check+act 原子"与
   `CameraCaptureTickets` 的时间戳对齐都有真实多线程/交错测试；但
   `VideoCallCameraController` 里那些**调用 camera2 API 的回调体本身**只做到
   "编译通过 + 结构上把整段放进同一把锁"，没有模拟 camera2/HAL 的设备可以真的验证。
3. **预览方向**：`TextureView` + 显式矩阵（先抵消默认拉伸，再旋转 + 等比 centerCrop + 前摄镜像），
   与抓帧归一化共用同一套纯几何（`VideoPreviewGeometry`）。落地效果仍需真机确认
   （尤其是分屏/横屏与少数厂商 ROM）。
4. **抓帧方向**：请求 `JPEG_ORIENTATION = 0` + 落盘只归一化一次；若某机型**无视**该请求仍然自己
   转像素，本实现只会在日志里留一条诊断（`VideoFrameStore` 的 `W/相机返回的 JPEG 带非正常 EXIF 方向`），
   不会去猜第三种解释。这类机型需要真机排查。
5. **绑定模式（无任何权限）**：通话只在该页面可见期间存在，退到后台（ON_STOP）即结束（这是刻意的，
   避免在没有权限的情况下长期持有后台会话）。正常的前台语音/视频通话不受影响。
   纯语音（`videoMode = false`）**只有相机权限**时不会以前台服务运行（stopSelf / 绑定模式），
   否则前台类型掩码为 0，`startForeground` 5 秒后必崩。
6. **礼物标签来源**：礼物完全由模型按协议输出，面板没有"用户送礼"入口（刻意避免付费暗示）。
7. **单帧抓取**：JPEG 抓帧有 2 秒超时；超时作废后该帧会被时间戳规则丢弃（不会喂给下一次抓帧），
   极慢设备可能偶发抓不到，此时不会附图（不会假成功）。
   时间戳未知时早到的图会短暂暂存（最多 2 张），超时/换代一定清空。
8. **孤儿文件**：取消落在"可能已发布"的中间态时**故意保留**文件（宁可留一张没人引用的孤儿，
   也不能让历史断图）；永久目录目前没有定期清理，只有临时目录按 30 分钟龄期清扫。
9. **未覆盖**：多人通话、录制、美颜、横屏下的布局微调。
