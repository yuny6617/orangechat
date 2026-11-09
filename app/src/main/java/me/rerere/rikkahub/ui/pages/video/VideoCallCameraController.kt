/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.video.CameraCaptureTickets
import me.rerere.rikkahub.data.video.CameraDisplayRotationTracker
import me.rerere.rikkahub.data.video.CameraFacing
import me.rerere.rikkahub.data.video.CameraGenerationGate
import me.rerere.rikkahub.data.video.CameraGenerationLifecycle
import me.rerere.rikkahub.data.video.cameraStartAllowed
import me.rerere.rikkahub.data.video.PreviewTransform
import me.rerere.rikkahub.data.video.VideoCallCameraGateway
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoCameraState
import me.rerere.rikkahub.data.video.VideoCameraStatus
import me.rerere.rikkahub.data.video.VideoFrame
import me.rerere.rikkahub.data.video.VideoFramePolicy
import me.rerere.rikkahub.data.video.VideoPreviewGeometry

private const val TAG = "VideoCallCamera"

/** 一次抓帧等 JPEG 的上限；超时就把这次请求作废（不能让旧图喂给下一次抓帧）。 */
private const val CAPTURE_TIMEOUT_MILLIS = 2_000L

/**
 * 视频面板专用的相机控制器。
 *
 * 与 [me.rerere.rikkahub.data.service.CameraService]（AI 后台单张拍照）互不干扰：
 * 这里长期持有一路预览会话，只在本面板可见时存活，离开/挂断立即释放。
 *
 * ## 生命周期按"一代"（generation）管理
 * 每次 bind/切换镜头/unbind/方向变化都换一代。旧一代的异步回调（onOpened / onConfigured /
 * onImageAvailable）必须在**同一把锁内**先确认自己仍是当前代才动手；发现自己过期就只
 * 释放自己的资源，既不改当前状态、也不会关掉新一代的会话。
 *
 * ## 线程模型（REVIEW_R5 §1）
 * 所有状态迁移都走 [CameraGenerationGate]：
 * - 公开方法（attachSurface / setPreviewAllowed / unbind / updateViewSize / switchCamera）
 *   与相机回调（onOpened / onConfigured / onDisconnected / onError / onImageAvailable /
 *   onCaptureStarted）都在 `gate.exclusive { }` 里完成 **check → 资源接管 → 操作 → 状态发布**；
 * - 上一版的 `@Synchronized isCurrent()` 只包住了一次 read，check 与 act 之间 stop/switch
 *   可以插进来，旧回调仍会把 status 写回 Previewing —— 这里由 [CameraGenerationGate.ifCurrent]
 *   原子化，过期就不再执行；
 * - 资源**释放**（`CameraDevice.close()` 等）一律在锁外：先摘除、再用返回值释放，
 *   不存在"持控制器锁等 generation 锁"的反向锁序。
 *
 * ## 换代的顺序（REVIEW_R6 §3）
 * 所有"结束/换代"都经过 [CameraGenerationLifecycle]：**门闩内**摘除当前代并让
 * `VideoCallRuntime` 的相机代次失效（epoch +1、清待发送帧、撤销留影许可），
 * **锁外**才释放相机资源。旧实现把失效放在释放之后，那段窗口里运行时尚认为旧代有效，
 * 排队中的抓帧/已发出的留影许可还能消费旧画面。
 * 锁序因此固定为 **gate → runtime**：运行时侧读本 gateway（`available` / `capture`）
 * 时绝不持自己的监视器（见 `VideoCallRuntime.requestSnapshot`），不会反向要门闩。
 * `startInternal` 还会重验"仍然允许预览 + 仍是同一个 surface/texture"，
 * UI 拆掉 Surface 之后之前排队的 start 不会再把相机开在已销毁的 texture 上。
 *
 * ## 抓帧（REVIEW_R5 §3）
 * 一次抓帧 = 一张票据（[CameraCaptureTickets]）。提交与 close 同锁，`await` 不持锁，
 * 交付前必须在锁内复核 generation 与票据；只有**时间戳严格相等**的 JPEG 会被接受，
 * 早到的图有界暂存等 `onCaptureStarted` 来认领，超时/取消一定作废票据（`CancellationException`
 * 原样透传，绝不吞）。
 *
 * ## 方向（REVIEW_R5 §4）
 * - 预览矩阵与抓帧方向来自同一个纯函数 [VideoPreviewGeometry]；
 * - 显示方向靠 `DisplayManager` 监听刷新（180° 时 view 尺寸不变，只靠
 *   `onSurfaceTextureSizeChanged` 永远不会更新），方向真的变了就重新发布矩阵、
 *   作废在飞的抓帧票据，并同步让运行时相机代次失效；
 * - 抓帧请求固定 `JPEG_ORIENTATION = 0`，旋转只由落盘归一化做一次。
 */
class VideoCallCameraController(
    private val context: Context,
) : VideoCallCameraGateway {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    /**
     * 相机代次的串行化门闩：这个类里所有跨线程状态都只在它里面读写。
     * 纯 Kotlin，配套 JVM 交错测试（[me.rerere.rikkahub.data.video.CameraGenerationGate]）。
     */
    private val gate = CameraGenerationGate<Generation>()

    /**
     * 结束/换代一代相机资源的**唯一顺序**（REVIEW_R6 §3）：
     * 门闩内摘除 + 同临界区让运行时失效 → 锁外才释放相机。
     *
     * 这样"stop / 切镜头 / 退后台"从结束那一代开始，运行时就已经不认旧代的帧与留影许可，
     * 不存在"资源还没释放完、旧画面还能被塞进下一句"的窗口。
     */
    private val generationLifecycle = CameraGenerationLifecycle(
        gate = gate,
        invalidateRuntime = { VideoCallRuntime.notifyCameraGenerationChanged(this) },
        release = { generation -> generation.release() },
    )

    /** 显示方向的变化检测：同一角度连发不重复发布，180° 也能发现。 */
    private val rotationTracker = CameraDisplayRotationTracker()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val _status = MutableStateFlow(VideoCameraStatus())
    val status: StateFlow<VideoCameraStatus> = _status.asStateFlow()

    /** 预览内容需要做的变换（旋转 + 等比填满 + 前摄镜像 + 抵消默认拉伸）。 */
    private val _previewTransform = MutableStateFlow(PreviewTransform())
    val previewTransform: StateFlow<PreviewTransform> = _previewTransform.asStateFlow()

    /** 面板是否已经把一个可用的 SurfaceTexture 交了过来（Surface 就绪）。 */
    private val _surfaceReady = MutableStateFlow(false)
    val surfaceReady: StateFlow<Boolean> = _surfaceReady.asStateFlow()

    /** 一次抓帧的票据 + 等待句柄。 */
    private class PendingCapture(val ticket: CameraCaptureTickets.Ticket) {
        val deferred = CompletableDeferred<ByteArray?>()
    }

    /** 当前这一代相机资源；所有回调都闭包持有自己的 [Generation]。 */
    private class Generation(
        val surface: Surface,
        /** 预览用的 SurfaceTexture：`setDefaultBufferSize` 只在这个公开 API 上（`Surface` 上是 @hide）。 */
        val texture: SurfaceTexture,
        /** **实际选中**那颗镜头的朝向（不是用户请求的兜底朝向）。 */
        val frontFacing: Boolean,
        val sensorOrientation: Int,
        val cameraId: String,
    ) {
        /** 这一代自己的抓帧票据（旧代的票绝不会被新代认领）。 */
        val tickets = CameraCaptureTickets()

        /** 开这一代时的显示方向；方向变化时在锁内更新（不再是打开时的 val）。 */
        @Volatile
        var displayRotationDegrees: Int = 0

        @Volatile
        var thread: HandlerThread? = null

        @Volatile
        var handler: Handler? = null

        @Volatile
        var device: CameraDevice? = null

        @Volatile
        var session: CameraCaptureSession? = null

        @Volatile
        var reader: ImageReader? = null

        @Volatile
        var capture: PendingCapture? = null

        @Volatile
        var viewWidth: Int = 0

        @Volatile
        var viewHeight: Int = 0

        /** `setDefaultBufferSize` 用的 buffer 尺寸 = 相机真实输出尺寸。 */
        @Volatile
        var previewWidth: Int = 0

        @Volatile
        var previewHeight: Int = 0

        /**
         * 释放自己的全部资源。
         *
         * 幂等，而且**永远在控制器锁外**被调用（见类注释的锁序约定）；
         * 这里的 `@Synchronized` 只保护它自己的字段，与控制器锁没有嵌套关系。
         */
        @Synchronized
        fun release() {
            val closingSession = session
            val closingDevice = device
            val closingReader = reader
            val closingCapture = capture
            session = null
            device = null
            reader = null
            capture = null
            runCatching { closingSession?.close() }
            runCatching { closingDevice?.close() }
            runCatching { closingReader?.close() }
            closingCapture?.let { pending ->
                tickets.end(pending.ticket)
                pending.deferred.complete(null)
            }
            val closingThread = thread
            thread = null
            handler = null
            runCatching { closingThread?.quitSafely() }
        }
    }

    private enum class SessionStart { Started, Failed }

    // ------------------------------------------------------------------
    // 只读状态
    // ------------------------------------------------------------------

    override val available: Boolean
        get() = gate.exclusive {
            _status.value.isPreviewing && gate.currentOrNull()?.session != null
        }

    override val unavailableReason: String
        get() = when (_status.value.state) {
            VideoCameraState.PermissionDenied -> "没有相机权限"
            VideoCameraState.Unavailable -> _status.value.message.ifBlank { "没有可用的相机" }
            VideoCameraState.Failed -> _status.value.message.ifBlank { "相机打开失败" }
            VideoCameraState.Requesting -> "相机正在启动"
            VideoCameraState.Idle -> "相机未启动（离开视频面会立即释放）"
            VideoCameraState.Previewing -> ""
        }

    val isFrontCamera: Boolean
        get() = _status.value.facing == CameraFacing.Front

    /** 面板/服务用来确认"这一代相机还在预览"。 */
    fun isPreviewingGeneration(): Boolean = available

    /** 当前这一代是否就是面板期望的朝向（切换镜头后旧帧不会串台）。 */
    fun currentFacing(): CameraFacing = _status.value.facing

    // ------------------------------------------------------------------
    // 面板驱动：Surface / 门禁 / 尺寸 / 切镜头 / 解绑
    // ------------------------------------------------------------------

    /**
     * TextureView 的 SurfaceTexture 就绪。
     *
     * 是否立刻开相机由 [setPreviewAllowed] 的门禁结果决定——授权弹窗/退后台期间
     * surface 可能在、但不该开相机。
     */
    fun attachSurface(texture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        var toRelease: Surface? = null
        var startSurface: Surface? = null
        var startTexture: SurfaceTexture? = null
        var startWidth = 0
        var startHeight = 0
        gate.exclusive {
            val existing = ownedSurface
            // 同一个 SurfaceTexture 重新 attach（页面重建）时复用已有的 Surface，
            // 只有真的换成另一个 texture 才新建并释放旧的。
            val surface = if (ownedTexture === texture && existing != null) existing else Surface(texture)
            if (existing !== surface) {
                ownedSurface = surface
                toRelease = existing
            }
            ownedTexture = texture
            if (viewWidth > 0 && viewHeight > 0) {
                pendingViewWidth = viewWidth
                pendingViewHeight = viewHeight
            }
            _surfaceReady.value = true
            registerDisplayListenerLocked()
            val current = gate.currentOrNull()
            when {
                !previewAllowed -> current?.let { updateViewSizeLocked(it, viewWidth, viewHeight) }
                current != null && current.surface === surface -> {
                    updateViewSizeLocked(current, viewWidth, viewHeight)
                }
                else -> {
                    startSurface = surface
                    startTexture = texture
                    startWidth = pendingViewWidth
                    startHeight = pendingViewHeight
                }
            }
        }
        toRelease?.let { runCatching { it.release() } }
        val surface = startSurface ?: return
        val textureToUse = startTexture ?: return
        startInternal(surface, textureToUse, startWidth, startHeight)
    }

    /** TextureView 的 SurfaceTexture 被回收：必须先停相机，再释放我们自己的 Surface。 */
    fun detachSurface() {
        var ended: Generation? = null
        var toRelease: Surface? = null
        gate.exclusive {
            _surfaceReady.value = false
            ownedTexture = null
            unregisterDisplayListenerLocked()
            // 摘除 + 让运行时失效在同一个临界区，之后才（锁外）释放资源。
            ended = stopInternalLocked()
            toRelease = ownedSurface
            ownedSurface = null
        }
        ended?.release()
        toRelease?.let { runCatching { it.release() } }
    }

    /**
     * 相机门禁结果变化：允许就（重新）开预览，不允许就立刻释放相机。
     *
     * 这条路径同时覆盖：授权弹窗期间的 ON_STOP/ON_START、页面重建、挂断，
     * 因此不会出现"背景里还留着 Surface 却在反复开相机"。
     */
    fun setPreviewAllowed(allowed: Boolean) {
        var ended: Generation? = null
        var startSurface: Surface? = null
        var startTexture: SurfaceTexture? = null
        var startWidth = 0
        var startHeight = 0
        gate.exclusive {
            previewAllowed = allowed
            if (!allowed) {
                unregisterDisplayListenerLocked()
                ended = stopInternalLocked()
                return@exclusive
            }
            registerDisplayListenerLocked()
            if (gate.currentOrNull() != null) return@exclusive
            val surface = ownedSurface ?: return@exclusive
            val texture = ownedTexture ?: return@exclusive
            startSurface = surface
            startTexture = texture
            startWidth = pendingViewWidth
            startHeight = pendingViewHeight
        }
        ended?.release()
        val surface = startSurface ?: return
        val texture = startTexture ?: return
        // startInternal 自己会重验门禁：关掉预览之后这里也不会再开旧相机。
        startInternal(surface, texture, startWidth, startHeight)
    }

    /** 纯文字模式 / 退后台的兜底停止：只停相机，surface 留给回前台后重开。 */
    fun unbind() {
        setPreviewAllowed(false)
    }

    /** TextureView 尺寸变化（旋转屏幕 / 键盘弹出 / 分屏）。 */
    fun updateViewSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            gate.exclusive {
                pendingViewWidth = width
                pendingViewHeight = height
            }
        }
        gate.exclusive {
            val generation = gate.currentOrNull() ?: return@exclusive
            updateViewSizeLocked(generation, width, height)
        }
    }

    /**
     * 切换前后摄像头。
     *
     * 换代是一等动作：旧一代立刻从门闩里摘除（它的回调之后只会看到 stale），
     * 资源在锁外释放，再开新一代。
     */
    fun switchCamera() {
        var ended: Generation? = null
        gate.exclusive {
            requestedFacing = requestedFacing.toggled()
            ended = stopInternalLocked()
            // 只有在真的重开一代时才进入 Requesting：没有会话时保持 Idle，
            // 绝不把面板永久留在"相机正在启动…"。
            if (ended != null) {
                _status.value = VideoCameraStatus(
                    state = VideoCameraState.Requesting,
                    facing = requestedFacing,
                    message = "",
                )
            }
        }
        val previous = ended
        previous?.release()
        // 没有在跑的会话时什么都不做：等下次 bind 再开，绝不空转重开。
        if (previous != null) {
            startInternal(previous.surface, previous.texture, previous.viewWidth, previous.viewHeight)
        }
    }

    private fun updateViewSizeLocked(generation: Generation, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        generation.viewWidth = width
        generation.viewHeight = height
        publishTransformLocked(generation)
    }

    /** 必须在门闩内调用。 */
    private fun publishTransformLocked(generation: Generation) {
        if (gate.currentOrNull() !== generation) return
        _previewTransform.value = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = generation.sensorOrientation,
            displayRotationDegrees = generation.displayRotationDegrees,
            frontFacing = generation.frontFacing,
            bufferWidth = generation.previewWidth,
            bufferHeight = generation.previewHeight,
            viewWidth = generation.viewWidth,
            viewHeight = generation.viewHeight,
        )
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // 显示方向（REVIEW_R5 §4）
    // ------------------------------------------------------------------

    private var displayListenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            onDisplayRotationMaybeChanged()
        }
    }

    /** 必须在门闩内调用。 */
    private fun registerDisplayListenerLocked() {
        val manager = displayManager ?: return
        if (displayListenerRegistered) return
        runCatching { manager.registerDisplayListener(displayListener, mainHandler) }
            .onSuccess { displayListenerRegistered = true }
    }

    /** 必须在门闩内调用。 */
    private fun unregisterDisplayListenerLocked() {
        if (!displayListenerRegistered) return
        displayListenerRegistered = false
        val manager = displayManager ?: return
        runCatching { manager.unregisterDisplayListener(displayListener) }
    }

    /**
     * 显示方向可能变了。
     *
     * 180° 旋转时 view 尺寸不变，`onSurfaceTextureSizeChanged` 不会触发，所以这条
     * 监听是唯一入口。方向真的变了：更新当前代的快照、重新发布矩阵、作废在飞的抓帧
     * 票据（按旧方向算出来的那一帧不能当这一代的结果），并同步让运行时相机代次失效。
     */
    private fun onDisplayRotationMaybeChanged() {
        val degrees = currentDisplayRotationDegrees()
        if (!rotationTracker.observe(degrees)) return
        gate.exclusive {
            val generation = gate.currentOrNull() ?: return@exclusive
            if (generation.displayRotationDegrees == degrees) return@exclusive
            generation.displayRotationDegrees = degrees
            generation.tickets.openTicketOrNull()?.let { ticket ->
                generation.tickets.end(ticket)
            }
            generation.capture?.let { pending ->
                generation.capture = null
                pending.deferred.complete(null)
            }
            publishTransformLocked(generation)
            // 方向变了 = 这一代的画面作废：与上面同一个临界区里让运行时失效，
            // 锁序仍然是 gate → runtime。
            invalidateRuntimeGeneration()
        }
    }

    private fun currentDisplayRotationDegrees(): Int = runCatching {
        @Suppress("DEPRECATION")
        val surfaceRotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.rotation
        VideoPreviewGeometry.displayRotationDegrees(surfaceRotation)
    }.getOrDefault(0)

    // ------------------------------------------------------------------
    // 开 / 停
    // ------------------------------------------------------------------

    /** 一颗实际存在的镜头：id + 特性 + **它自己的**朝向。 */
    private class CameraCandidate(
        val id: String,
        val characteristics: CameraCharacteristics,
        val facing: CameraFacing,
    )

    private fun startInternal(surface: Surface, texture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        var dropped: Generation? = null
        gate.exclusive {
            // 排队期间 UI 可能已经拆掉 Surface / 关掉预览门禁（REVIEW_R6 §3）：
            // 这时绝不能再拿旧 surface 开一次相机，否则新相机会挂在已销毁的 texture 上。
            if (!cameraStartAllowed(previewAllowed, ownedSurface, surface, ownedTexture, texture)) {
                return@exclusive
            }

            // 新一代：先把上一代从门闩里摘掉（它的回调从此只会看到 stale），
            // 并在**同一个临界区**里让运行时失效，锁外再释放资源。
            dropped = generationLifecycle.endLocked()

            if (!hasPermission()) {
                // surface 留着：用户随后授权时 setPreviewAllowed(true) 要能直接开相机。
                _status.value = VideoCameraStatus(
                    VideoCameraState.PermissionDenied,
                    requestedFacing,
                    "没有相机权限",
                )
                return@exclusive
            }
            val candidate = pickCamera(requestedFacing)
            if (candidate == null) {
                _status.value = VideoCameraStatus(VideoCameraState.Unavailable, requestedFacing, "没有可用的相机")
                return@exclusive
            }

            val generation = Generation(
                surface = surface,
                texture = texture,
                // 用**实际选中**那颗镜头的朝向：请求前摄但设备只有后摄时，
                // 旧实现仍然按"前摄"算法算旋转/镜像，画面方向和预览就会错。
                frontFacing = candidate.facing == CameraFacing.Front,
                sensorOrientation = candidate.characteristics
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                cameraId = candidate.id,
            )
            generation.viewWidth = viewWidth
            generation.viewHeight = viewHeight
            generation.displayRotationDegrees = currentDisplayRotationDegrees()
            rotationTracker.observe(generation.displayRotationDegrees)
            val backgroundThread = HandlerThread("VideoCallCamera").apply { start() }
            generation.thread = backgroundThread
            generation.handler = Handler(backgroundThread.looper)

            // 新一代上线同样在门闩内让旧相机代次失效（旧帧/旧许可一律作废）。
            generationLifecycle.adoptLocked(generation)
            _status.value = VideoCameraStatus(
                state = VideoCameraState.Requesting,
                facing = candidate.facing,
                message = "",
            )

            val failure = try {
                cameraManager.openCamera(
                    candidate.id,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            val opened = gate.ifCurrent(generation) { current ->
                                // 资源接管 + 建会话都在锁内：与 stop/switch 互斥。
                                current.device = camera
                                startSessionLocked(current, camera)
                            }
                            if (opened == null) {
                                // 过期的一代：只关掉自己这台设备，绝不碰当前会话与状态。
                                runCatching { camera.close() }
                                generation.release()
                                return
                            }
                            if (opened == SessionStart.Failed) {
                                failGeneration(
                                    generation,
                                    VideoCameraStatus(
                                        VideoCameraState.Failed,
                                        facingOf(generation),
                                        "启动相机会话失败",
                                    ),
                                )
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            runCatching { camera.close() }
                            failGeneration(
                                generation,
                                VideoCameraStatus(
                                    VideoCameraState.Unavailable,
                                    candidate.facing,
                                    "相机被其它功能占用",
                                ),
                            )
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            runCatching { camera.close() }
                            failGeneration(
                                generation,
                                VideoCameraStatus(
                                    VideoCameraState.Failed,
                                    candidate.facing,
                                    "相机错误($error)",
                                ),
                            )
                        }
                    },
                    generation.handler,
                )
                null
            } catch (error: CameraAccessException) {
                VideoCameraStatus(
                    VideoCameraState.Failed,
                    candidate.facing,
                    "无法打开相机: ${error.message}",
                )
            } catch (error: SecurityException) {
                VideoCameraStatus(
                    VideoCameraState.PermissionDenied,
                    candidate.facing,
                    "没有相机权限",
                )
            } catch (error: Exception) {
                VideoCameraStatus(
                    VideoCameraState.Failed,
                    candidate.facing,
                    "无法打开相机: ${error.message}",
                )
            }

            if (failure != null) {
                // 这一代没能起来：同样在门闩内摘除 + 失效，锁外释放。
                dropped = generationLifecycle.endLocked()
                _status.value = failure
                _previewTransform.value = PreviewTransform()
            }
        }
        dropped?.release()
    }

    /**
     * 建立预览会话（**必须在门闩内**，因为 onConfigured 的 check+act 也要在这把锁里）。
     *
     * @return [SessionStart.Failed] 表示配置/请求失败（调用方负责 failGeneration）。
     */
    private fun startSessionLocked(generation: Generation, camera: CameraDevice): SessionStart {
        val handler = generation.handler ?: return SessionStart.Failed
        val configMap = runCatching {
            cameraManager.getCameraCharacteristics(generation.cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull()
        val previewSize = pickPreviewSize(configMap)
        val captureSize = pickCaptureSize(configMap)
        generation.previewWidth = previewSize.width
        generation.previewHeight = previewSize.height

        // 关键：让 SurfaceTexture 的 buffer 尺寸等于相机真实输出尺寸。
        // 默认 buffer 尺寸是"视图大小"，那样 TextureView 的默认拉伸比就和我们算矩阵时
        // 假设的不一样，等比 centerCrop 也就无从下手（REVIEW_R4 §5）。
        // 注意公开 API 在 SurfaceTexture 上：android.view.Surface#setDefaultBufferSize 是 @hide。
        runCatching {
            generation.texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        }

        val imageReader = ImageReader.newInstance(
            captureSize.width,
            captureSize.height,
            ImageFormat.JPEG,
            2,
        )
        imageReader.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireLatestImage() }
                .getOrNull() ?: return@setOnImageAvailableListener
            try {
                deliverImage(generation, image)
            } finally {
                runCatching { image.close() }
            }
        }, handler)
        generation.reader = imageReader

        return try {
            val surfaces = listOf(generation.surface, imageReader.surface)
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    val outcome = gate.ifCurrent(generation) { current ->
                        // 接管会话、下发重复请求、发布状态：全部在同一把锁内。
                        current.session = configured
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(current.surface)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                )
                            }
                            configured.setRepeatingRequest(request.build(), null, handler)
                            _status.value = VideoCameraStatus(
                                VideoCameraState.Previewing,
                                facingOf(current),
                            )
                            publishTransformLocked(current)
                            SessionStart.Started
                        } catch (error: Exception) {
                            Log.w(TAG, "启动预览失败", error)
                            SessionStart.Failed
                        }
                    }
                    when (outcome) {
                        SessionStart.Started -> Unit
                        SessionStart.Failed -> failGeneration(
                            generation,
                            VideoCameraStatus(
                                VideoCameraState.Failed,
                                facingOf(generation),
                                "启动预览失败",
                            ),
                        )
                        // 过期的一代：只关自己的会话，绝不让"关闭新会话"发生。
                        null -> {
                            runCatching { configured.close() }
                            generation.release()
                        }
                    }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    runCatching { failed.close() }
                    failGeneration(
                        generation,
                        VideoCameraStatus(
                            VideoCameraState.Failed,
                            facingOf(generation),
                            "相机预览配置失败",
                        ),
                    )
                }
            }, handler)
            SessionStart.Started
        } catch (error: Exception) {
            Log.w(TAG, "创建相机会话失败", error)
            SessionStart.Failed
        }
    }

    /**
     * 结束当前这一代并发布 Idle。**必须在门闩内调用**，返回被结束的那一代，
     * 由调用方在锁外释放它。
     *
     * 结束时立刻（同一临界区）让运行时相机代次失效：这样即便释放动作还没做完，
     * 旧代的帧与留影许可也已经不可能被消费（REVIEW_R6 §3）。
     */
    private fun stopInternalLocked(): Generation? {
        val ended = generationLifecycle.endLocked()
        _status.value = VideoCameraStatus(VideoCameraState.Idle, requestedFacing)
        _previewTransform.value = PreviewTransform()
        rotationTracker.reset()
        return ended
    }

    /**
     * 这一代失败/断开：只在它仍是当前代时才改状态；过期回调是**幂等**的，
     * 不会把新一代的 [gate] 清掉。资源释放放在锁外。
     */
    private fun failGeneration(generation: Generation, status: VideoCameraStatus) {
        gate.exclusive {
            if (gate.currentOrNull() !== generation) return@exclusive
            // 摘除 + 运行时失效在同一个临界区，释放放在锁外。
            generationLifecycle.endLocked()
            _status.value = status
            _previewTransform.value = PreviewTransform()
            rotationTracker.reset()
        }
        generation.release()
    }

    /**
     * 同步告诉运行时"这一代相机结束/变了"（REVIEW_R5 §2 / REVIEW_R6 §3）。
     *
     * 只允许在门闩内调用（[generationLifecycle] 与方向变化都走这里），因此锁序固定为
     * **gate → runtime**；运行时侧读 gateway 一律不持监视器，不会反向要门闩。
     * 不依赖 Compose 的 `LaunchedEffect`：退后台（ON_STOP）、切镜头、打开失败、方向变化
     * 都在这里**当场**让 runtime 的相机代次失效并清掉本会话的待发送帧。
     */
    private fun invalidateRuntimeGeneration() {
        VideoCallRuntime.notifyCameraGenerationChanged(this)
    }

    // ------------------------------------------------------------------
    // 抓帧
    // ------------------------------------------------------------------

    /**
     * 把 ImageReader 里的 JPEG 交给**对得上**的那次抓帧。
     *
     * 对齐规则全在 [CameraCaptureTickets] 里（纯 Kotlin、可单测）：
     * 没有打开的票据 → 丢弃；时间戳 <= 作废水位或非单调 → 丢弃；
     * 票据还不知道时间戳 → 有界暂存；时间戳严格相等才交付。
     */
    private fun deliverImage(generation: Generation, image: Image) {
        val timestampNs = image.timestamp
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (error: Exception) {
            Log.w(TAG, "读取相机帧失败", error)
            return
        }
        // 锁内复核 generation + 票据身份，锁外再完成 await（完成动作不持锁）。
        val delivery = gate.exclusive {
            if (gate.currentOrNull() !== generation) return@exclusive null
            generation.tickets.offer(timestampNs, bytes)
        } ?: return
        completeDelivery(generation, delivery)
    }

    /** 只完成**票据对得上**的那次等待；换过票据/换过代就谁也不给。 */
    private fun completeDelivery(generation: Generation, delivery: CameraCaptureTickets.Delivery) {
        val target = generation.capture ?: return
        if (target.ticket !== delivery.ticket) return
        target.deferred.complete(delivery.bytes)
    }

    /** 抓帧回调：只把 sensor 时间戳记到票据上；早到帧按时间戳精确匹配。 */
    private fun onCaptureTimestamp(
        generation: Generation,
        ticket: CameraCaptureTickets.Ticket,
        timestampNs: Long,
    ) {
        val delivery = gate.exclusive {
            if (gate.currentOrNull() !== generation) return@exclusive null
            generation.tickets.noteCaptureStarted(ticket, timestampNs)
        } ?: return
        completeDelivery(generation, delivery)
    }

    /** 抓一帧 JPEG；相机不在预览状态时返回 null，绝不排空等。 */
    override suspend fun capture(): VideoFrame? {
        val generation = gate.currentOrNull() ?: return null
        if (generation.session == null) return null
        val bytes = captureJpeg(generation) ?: return null
        // 抓帧是挂起操作：回来之后必须还是同一代，否则这一帧不属于当前画面。
        var rotationDegrees = 0
        val stillCurrent = gate.exclusive {
            if (gate.currentOrNull() !== generation) return@exclusive false
            rotationDegrees = captureRotation(generation)
            true
        }
        if (!stillCurrent) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        return VideoFrame(
            bytes = bytes,
            width = options.outWidth.coerceAtLeast(1),
            height = options.outHeight.coerceAtLeast(1),
            capturedAtMillis = System.currentTimeMillis(),
            rotationDegrees = rotationDegrees,
            mirror = generation.frontFacing,
        )
    }

    /**
     * 真正的抓帧请求。
     *
     * - **提交**（开票据 + capture）与 close/换代在同一把锁里，不会提交到已释放的会话上；
     * - `await` **不持锁**（否则一个慢抓帧会把整个 UI 的相机操作卡住）；
     * - `CancellationException` 原样透传：取消不是"这一帧没抓到"，吞掉它会让已取消的
     *   协程继续把照片写进聊天；
     * - `finally` 一定结束票据：清暂存、记作废水位，迟到的旧图喂不给下一次抓帧。
     */
    private suspend fun captureJpeg(generation: Generation): ByteArray? {
        // **提交**与 close/换代在同一把锁里：不会提交到已经释放的会话上。
        val ticket = gate.exclusive { submitCaptureLocked(generation) } ?: return null
        // await 不持锁：一个慢抓帧不能把整个 UI 的相机操作卡住。
        return try {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) { ticket.deferred.await() }
        } catch (cancelled: CancellationException) {
            // 取消必须透传（不是"抓帧失败"）：吞掉它会让已取消的协程继续写出照片。
            throw cancelled
        } finally {
            // 无论是超时、取消还是正常返回：作废票据、清暂存、记作废水位。
            gate.exclusive {
                generation.tickets.end(ticket.ticket)
                if (generation.capture === ticket) generation.capture = null
            }
        }
    }

    /** 开票据 + 提交 capture。**必须在门闩内调用**；失败返回 null 并已清干净。 */
    private fun submitCaptureLocked(generation: Generation): PendingCapture? {
        if (gate.currentOrNull() !== generation) return null
        val camera = generation.device ?: return null
        val configured = generation.session ?: return null
        val imageReader = generation.reader ?: return null
        val backgroundHandler = generation.handler ?: return null

        val ticket = generation.tickets.begin()
        val capture = PendingCapture(ticket)
        generation.capture = capture

        val request = try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // 只转一次：HAL 不转像素，方向由落盘的归一化统一处理。
                set(CaptureRequest.JPEG_ORIENTATION, VideoPreviewGeometry.REQUESTED_JPEG_ORIENTATION)
            }
        } catch (error: Exception) {
            Log.w(TAG, "创建抓帧请求失败", error)
            generation.tickets.end(ticket)
            generation.capture = null
            return null
        }
        try {
            configured.capture(
                request.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long,
                    ) {
                        onCaptureTimestamp(generation, ticket, timestamp)
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        onCaptureTimestamp(generation, ticket, timestamp)
                    }
                },
                backgroundHandler,
            )
        } catch (error: Exception) {
            Log.w(TAG, "提交抓帧请求失败", error)
            generation.tickets.end(ticket)
            generation.capture = null
            return null
        }
        return capture
    }

    /**
     * 抓帧需要的旋转角度：sensor 方向 + **当前**显示方向的组合。
     *
     * 与预览矩阵**同一个纯函数**（[VideoPreviewGeometry.uprightRotation]），
     * 因此预览正着看到的画面就是照片存下来的方向。
     */
    private fun captureRotation(generation: Generation): Int = VideoPreviewGeometry.uprightRotation(
        sensorOrientation = generation.sensorOrientation,
        displayRotationDegrees = generation.displayRotationDegrees,
        frontFacing = generation.frontFacing,
    )

    /**
     * 选镜头：优先请求的朝向，没有就退回第一颗。
     * 但**朝向必须读回实际那颗镜头**的特性，不能沿用请求值。
     */
    private fun pickCamera(requested: CameraFacing): CameraCandidate? {
        val ids = runCatching { cameraManager.cameraIdList }.getOrNull().orEmpty()
        if (ids.isEmpty()) return null
        val wanted = if (requested == CameraFacing.Front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val candidates = ids.mapNotNull { id ->
            runCatching {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facing = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    CameraFacing.Front
                } else {
                    CameraFacing.Back
                }
                CameraCandidate(id, characteristics, facing) to (lensFacing == wanted)
            }.getOrNull()
        }
        if (candidates.isEmpty()) return null
        return (candidates.firstOrNull { it.second } ?: candidates.first()).first
    }

    private fun pickPreviewSize(configMap: android.hardware.camera2.params.StreamConfigurationMap?): Size {
        val candidates = runCatching { configMap?.getOutputSizes(SurfaceTexture::class.java) }
            .getOrNull()?.toList().orEmpty()
        if (candidates.isEmpty()) return Size(720, 1280)
        return candidates
            .filter { maxOf(it.width, it.height) <= VideoFramePolicy.MAX_CAPTURE_EDGE }
            .maxByOrNull { it.width * it.height }
            ?: candidates.minByOrNull { it.width * it.height }
            ?: Size(720, 1280)
    }

    private fun pickCaptureSize(configMap: android.hardware.camera2.params.StreamConfigurationMap?): Size {
        val candidates = runCatching { configMap?.getOutputSizes(ImageFormat.JPEG) }
            .getOrNull()?.toList().orEmpty()
        if (candidates.isEmpty()) return Size(720, 1280)
        return candidates
            .filter { maxOf(it.width, it.height) <= VideoFramePolicy.MAX_CAPTURE_EDGE }
            .maxByOrNull { it.width * it.height }
            ?: candidates.minByOrNull { maxOf(it.width, it.height) }
            ?: Size(720, 1280)
    }

    private fun facingOf(generation: Generation): CameraFacing =
        if (generation.frontFacing) CameraFacing.Front else CameraFacing.Back

    // 面板当前允许开预览吗（Surface + 权限 + STARTED + 通话中 四条门禁的结果）。
    // 只在门闩内读写。
    private var previewAllowed: Boolean = false

    // 最近一次知道的 TextureView 尺寸（补开相机时用它算预览矩阵）。只在门闩内读写。
    private var pendingViewWidth: Int = 0
    private var pendingViewHeight: Int = 0

    /** 用户最近一次请求的朝向（切镜头用）；实际生效的朝向在 [Generation.frontFacing]。 */
    private var requestedFacing: CameraFacing = CameraFacing.Front

    /**
     * 本控制器包装出来的 [Surface]（TextureView 的 SurfaceTexture 外面那层）。
     *
     * 只有它自己的所有者（面板 detach / 换成另一个 texture）才能释放它：
     * 切换镜头、退后台再回前台都会重建相机代次，但 surface 仍然是同一个，
     * 绝不能顺手 release 掉。
     */
    private var ownedSurface: Surface? = null

    /** 与 [ownedSurface] 配套的 SurfaceTexture（`setDefaultBufferSize` 只能从这里调）。 */
    private var ownedTexture: SurfaceTexture? = null
}
