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
import me.rerere.rikkahub.data.video.PixelSize
import me.rerere.rikkahub.data.video.PreviewCalibration
import me.rerere.rikkahub.data.video.PreviewTransform
import me.rerere.rikkahub.data.video.VideoCallCameraGateway
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoCameraState
import me.rerere.rikkahub.data.video.VideoCameraStatus
import me.rerere.rikkahub.data.video.VideoFrame
import me.rerere.rikkahub.data.video.VideoPreviewGeometry

private const val TAG = "VideoCallCamera"

/** 一次抓帧等 JPEG 的上限；超时就把这次请求作废（不能让旧图喂给下一次抓帧）。 */
private const val CAPTURE_TIMEOUT_MILLIS = 2_000L

/**
 * 单帧 JPEG 的**原始字节**上限。
 *
 * 选中的输出尺寸已经被 [VideoPreviewGeometry.CAPTURE_MAX_PIXELS] 限制在 1080p 级，
 * 正常 JPEG 通常远小于 1.5MB。4MB 是兜底：再大的帧直接作废，绝不把 `ByteArray`
 * 交给下游整张解码/压缩。超限帧只丢弃、不动票据，避免迟到的旧大图误伤新一代抓帧
 * （当前请求由抓帧超时兜底）。
 */
private const val MAX_CAPTURE_JPEG_BYTES = 4 * 1024 * 1024

/** ImageReader 创建失败时退回"只预览"的提示（与几何层的预算原因区分开）。 */
private const val CAPTURE_DISABLED_READER_FAILED = "创建照片输出失败，已保留预览并停用抓帧"

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
 * ## 方向与取景范围（REVIEW_R5 §4）
 * - 预览矩阵与抓帧方向来自同一个纯函数 [VideoPreviewGeometry]；抓帧角 = sensor/display
 *   基准角 + 用户预览校准角，镜像 = 用户预览镜像开关（"所见即所发"）。
 * - 预览卡按 [PreviewTransform.contentSize] 设置宽高比，所以预览是整张源图的等比贴合，
 *   不会出现"预览为了铺满视图裁掉一块、JPEG 却是整张图"的取景范围差异。
 * - 预览输出与 JPEG 输出必须同宽高比（[VideoPreviewGeometry.chooseCameraStreamPlan]）：
 *   以前两者各自挑"最大面积"，可能一个是 16:9、一个是 4:3，模型收到的画面比用户看到的多。
 *   同比例是**精确**的（有向宽高交叉相乘），绝不接受近似比例。
 * - 兼容性（Round1）：预览本身限制在 1280 以内；如果设备只有更大的同比例 JPEG，
 *   允许选**最小的那张**更大输出，但受 [VideoPreviewGeometry.CAPTURE_MAX_PIXELS]
 *   / [VideoPreviewGeometry.CAPTURE_FALLBACK_MAX_EDGE] 的严格预算约束（下游是整张解码）。
 *   如果连一张预算内的同比例 JPEG 都没有，**不**退回拉伸/近似比例，而是保留预览、
 *   不建 ImageReader、`capture()` 返回 null，并用
 *   [VideoPreviewGeometry.CAPTURE_DISABLED_NO_MATCHING_ASPECT] /
 *   [VideoPreviewGeometry.CAPTURE_DISABLED_OVERSIZED_CAPTURE] 作为
 *   `unavailableReason` 提示：用户看到的画面与可能发送的画面仍然一致，只是这一代不抓帧
 *   （自动/手动/留影共用同一条抓帧路径，都会拿到 null）。
 * - 显示方向靠 `DisplayManager` 监听刷新（180° 时 view 尺寸不变，只靠
 *   `onSurfaceTextureSizeChanged` 永远不会更新），方向真的变了就重新发布矩阵、
 *   作废在飞的抓帧票据，并同步让运行时相机代次失效；
 * - 用户改校准角/镜像时同样作废在飞的抓帧票据：旧几何下提交的请求不能当成新几何的结果。
 * - 抓帧请求固定 `JPEG_ORIENTATION = 0`，旋转/镜像只由落盘归一化做一次。
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

    /** 预览内容需要做的变换（旋转 + 等比填满 + 前摄镜像 + 抵消默认拉伸 + 用户校准）。 */
    private val _previewTransform = MutableStateFlow(PreviewTransform())
    val previewTransform: StateFlow<PreviewTransform> = _previewTransform.asStateFlow()

    /**
     * 用户校准角度同时作用于预览和发送给模型的抓帧 JPEG。
     *
     * 故意放在控制器上、而不是放进 [Generation]：换镜头、退后台再回来、页面重建都会重建
     * generation，如果角度跟着 generation 走，用户每切一次前后摄就得重新校一次。
     * 这里它是控制器级状态，只有设置被改才变（来源是 `VideoCallSetting.previewCalibrationDegrees`）。
     */
    private var previewCalibrationDegrees: Int = PreviewCalibration.DEFAULT_DEGREES
    private var previewMirrored = false
    // Monotonic under gate: A → B → A must still reject a capture submitted before B.
    private var geometryRevision = 0L

    /**
     * 这一代"预览能开、但抓帧停用"的原因；正常可抓帧时是空串。
     *
     * 只在门闩内写（换代/会话建立/停止），读取不加锁是为了让 [unavailableReason]
     * 保持无锁（运行时可能在任意协程里读它）。volatile 保证门闩内写入对读侧可见。
     * 它不是 UI 文案的唯一来源：`available == false` 时运行时会用它回复模型，
     * 手动抓帧则直接拿到 `capture() == null`。
     */
    @Volatile
    private var captureDisabledReason: String = ""

    fun setPreviewMirrored(mirrored: Boolean) {
        gate.exclusive {
            if (previewMirrored == mirrored) return@exclusive
            previewMirrored = mirrored
            invalidateGeometryLocked()
            gate.currentOrNull()?.let { publishTransformLocked(it) }
        }
    }

    override fun closeCamera() = unbind()

    /**
     * 写入预览校准角度（面板按钮 / 设置页调用）。
     *
     * 角度同时进入预览矩阵和抓帧 JPEG 的归一化旋转。重算预览矩阵、作废在飞的抓帧票据；
     * 下一个抓帧请求读取新的校准角度。角度会先归一化到 0/90/180/270，非法值不会进矩阵。
     */
    fun setPreviewCalibrationDegrees(degrees: Int) {
        val normalized = PreviewCalibration.normalize(degrees)
        gate.exclusive {
            if (normalized == previewCalibrationDegrees) return@exclusive
            previewCalibrationDegrees = normalized
            invalidateGeometryLocked()
            gate.currentOrNull()?.let { publishTransformLocked(it) }
        }
    }

    /** 当前预览校准角度（面板显示用）。 */
    fun previewCalibration(): Int = previewCalibrationDegrees

    /** 面板是否已经把一个可用的 SurfaceTexture 交了过来（Surface 就绪）。 */
    private val _surfaceReady = MutableStateFlow(false)
    val surfaceReady: StateFlow<Boolean> = _surfaceReady.asStateFlow()

    /**
     * 一次抓帧的票据 + 等待句柄 + **提交那一刻的几何快照**。
     *
     * 几何快照必须在提交时取：如果等 JPEG 回来再读校准/镜像，期间用户改了角度，
     * 这一帧就会用新几何解释旧请求。配合各 setter 里的作废逻辑，旧几何的请求根本不会交付。
     */
    private class PendingCapture(
        val ticket: CameraCaptureTickets.Ticket,
        val rotationDegrees: Int,
        val mirror: Boolean,
        val revision: Long,
    ) {
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
         * 抓帧 JPEG 的输出尺寸；必须与 [previewWidth]/[previewHeight] 同宽高比。
         * 两者都为 0 表示这一代是**只预览**（没有 ImageReader，抓帧停用）。
         */
        @Volatile
        var captureWidth: Int = 0

        @Volatile
        var captureHeight: Int = 0

        /** 这一代抓帧停用的原因；`reader != null` 时必须为空串。 */
        @Volatile
        var captureDisabledReason: String = ""

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

    /**
     * 相机能不能**交付一帧**（运行时据此决定是否抓帧、以及告诉模型"相机可用"）。
     *
     * 预览-only 的会话（没有 ImageReader）必须返回 false：`capture()` 在那种会话里
     * 一定返回 null，这里若还说可用，模型/服务端就会以为能收到画面。
     */
    override val available: Boolean
        get() = gate.exclusive {
            val current = gate.currentOrNull()
            _status.value.isPreviewing && current?.session != null && current.reader != null
        }

    override val unavailableReason: String
        get() = when (_status.value.state) {
            VideoCameraState.PermissionDenied -> "没有相机权限"
            VideoCameraState.Unavailable -> _status.value.message.ifBlank { "没有可用的相机" }
            VideoCameraState.Failed -> _status.value.message.ifBlank { "相机打开失败" }
            VideoCameraState.Requesting -> "相机正在启动"
            VideoCameraState.Idle -> "相机未启动（离开视频面会立即释放）"
            // 正常抓帧时是空串；预览-only 时把"为什么没有照片"如实交给提示词。
            VideoCameraState.Previewing -> captureDisabledReason
        }

    val isFrontCamera: Boolean
        get() = _status.value.facing == CameraFacing.Front

    /** 面板/服务用来确认"这一代相机还在预览"（预览-only 也算在预览）。 */
    fun isPreviewingGeneration(): Boolean = gate.exclusive {
        _status.value.isPreviewing && gate.currentOrNull()?.session != null
    }

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
            previewAllowed = allowed && VideoCallRuntime.isCameraEnabled()
            if (!previewAllowed) {
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
        // 尺寸没到位时**绝对不能**发布单位阵：单位阵的语义是"不旋转 + 按 buffer 拉伸到视图"，
        // 在竖屏 + 后摄(sensor 90) 的真机上，画面正好就侧躺 90°。
        // 旧实现会在这条路径上静默写出 [PreviewTransform]，等于把"还没算"显示成"不用转"。
        if (generation.viewWidth <= 0 || generation.viewHeight <= 0 ||
            generation.previewWidth <= 0 || generation.previewHeight <= 0
        ) {
            Log.i(
                TAG,
                "预览几何：尺寸未就绪，暂不发布变换 view=${generation.viewWidth}x${generation.viewHeight} " +
                    "buffer=${generation.previewWidth}x${generation.previewHeight}",
            )
            return
        }
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = generation.sensorOrientation,
            displayRotationDegrees = generation.displayRotationDegrees,
            frontFacing = generation.frontFacing,
            bufferWidth = generation.previewWidth,
            bufferHeight = generation.previewHeight,
            viewWidth = generation.viewWidth,
            viewHeight = generation.viewHeight,
            // 预览矩阵带上校准；抓帧方向用同一个 captureRotationDegrees（基准角 + 同一校准角），
            // 所以两边方向一致，且 JPEG 只旋转一次（HAL 的 JPEG_ORIENTATION 固定 0）。
            calibrationDegrees = previewCalibrationDegrees,
            previewMirrored = previewMirrored,
            // Video calls always fit the complete frame into the fixed 4:3 card. A crop or a
            // non-uniform scale here is what users perceive as "compressed/deformed".
            fit = true,
        )
        _previewTransform.value = transform
        logPreviewGeometry(generation, transform)
    }

    /**
     * 预览方向的几何日志（**不含任何画面内容**，只有角度/尺寸/矩阵）。
     *
     * 真机核对专用：一行里同时有 sensor / display / 前摄 / buffer / view / 用户校准 /
     * 最终矩阵，任何一处取值不对都能直接看出来。只在数值变化时打一次，不刷屏。
     *
     * 这里**不再打印 `SurfaceTexture.getTransformMatrix()`**：那份矩阵表示采样坐标变换，
     * 常规就带 Y-flip 与 crop/scale，"非单位阵 = 厂商多转了一次"是错误因果
     * （见 RESPONSE_DEVICE_FIX_R4 §1），据此推断方向只会误导排障。
     */
    private fun logPreviewGeometry(generation: Generation, transform: PreviewTransform) {
        val matrix = transform.matrix
        val line = buildString {
            append("预览几何 camera=").append(generation.cameraId)
            append(" facing=").append(if (generation.frontFacing) "front" else "back")
            append(" sensor=").append(generation.sensorOrientation)
            append(" display=").append(generation.displayRotationDegrees)
            append(" buffer=").append(generation.previewWidth).append('x').append(generation.previewHeight)
            append(" jpeg=").append(generation.captureWidth).append('x').append(generation.captureHeight)
            append(" content=").append(transform.contentSize.width).append('x').append(transform.contentSize.height)
            append(" view=").append(generation.viewWidth).append('x').append(generation.viewHeight)
            append(" rotate=").append(transform.rotationDegrees)
            append(" calibration=").append(transform.calibrationDegrees)
            append(" mirror=").append(transform.mirrorHorizontally)
            append(" matrix=[")
            append(listOf(matrix.a, matrix.b, matrix.c, matrix.d, matrix.e, matrix.f).joinToString(","))
            append(']')
        }
        if (line == lastGeometryLog) return
        lastGeometryLog = line
        Log.i(TAG, line)
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // 显示方向（REVIEW_R5 §4）
    // ------------------------------------------------------------------

    private var displayListenerRegistered = false

    /** 上一次打过的一行几何日志（只用于去重，避免每次尺寸变化都刷屏）。 */
    private var lastGeometryLog: String? = null

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
            invalidateGeometryLocked()
            publishTransformLocked(generation)
        }
    }

    /**
     * 让**正在飞的抓帧**作废。必须在门闩内调用。
     *
     * 校验角、镜像、显示方向变化后，旧请求虽然拿到的还是 sensor 原始 JPEG（像素没变），
     * 但它是在旧几何下提交的；继续交付会让调用方以为这是新几何的帧。这里结束票据并
     * 立即完成等待方（返回 null），下一次抓帧重新开票据。
     *
     * 与 [captureJpeg] 的 `finally` 重复结束同一张票据是幂等的。
     */
    private fun invalidateGeometryLocked() {
        geometryRevision++
        gate.currentOrNull()?.let { invalidateInflightCaptureLocked(it) }
        // Clear pending frames and revoke old permits atomically with the geometry change.
        invalidateRuntimeGeneration()
    }

    private fun invalidateInflightCaptureLocked(generation: Generation) {
        generation.tickets.openTicketOrNull()?.let { ticket ->
            generation.tickets.end(ticket)
        }
        generation.capture?.let { pending ->
            generation.capture = null
            pending.deferred.complete(null)
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
            if (!VideoCallRuntime.isCameraEnabled() ||
                !cameraStartAllowed(previewAllowed, ownedSurface, surface, ownedTexture, texture)
            ) {
                return@exclusive
            }

            // 新一代：先把上一代从门闩里摘掉（它的回调从此只会看到 stale），
            // 并在**同一个临界区**里让运行时失效，锁外再释放资源。
            dropped = generationLifecycle.endLocked()
            // 新会话还没确定抓帧能力：先清掉上一代的提示，避免它被误当成这一代的原因。
            captureDisabledReason = ""

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
                captureDisabledReason = ""
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
        val streamSizes = pickStreamSizes(configMap) ?: return SessionStart.Failed
        val previewSize = streamSizes.preview
        val captureSize = streamSizes.capture
        generation.previewWidth = previewSize.width
        generation.previewHeight = previewSize.height
        generation.captureWidth = captureSize?.width ?: 0
        generation.captureHeight = captureSize?.height ?: 0
        generation.captureDisabledReason = streamSizes.captureDisabledReason
        // 控制器级提示：`unavailableReason` 不加锁读取，靠这个 volatile 与门闩内写入同步。
        captureDisabledReason = streamSizes.captureDisabledReason

        // 关键：让 SurfaceTexture 的 buffer 尺寸等于相机真实输出尺寸。
        // 默认 buffer 尺寸是"视图大小"，那样 TextureView 的默认拉伸比就和我们算矩阵时
        // 假设的不一样，等比 centerCrop 也就无从下手（REVIEW_R4 §5）。
        // 注意公开 API 在 SurfaceTexture 上：android.view.Surface#setDefaultBufferSize 是 @hide。
        val bufferSizeApplied = runCatching {
            generation.texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        }.isSuccess
        if (!bufferSizeApplied) {
            // 不能静默假装几何成立：buffer 不是我们算的尺寸时，预览矩阵的"抵消默认拉伸"
            // 前提就失效了。真机上会表现为方向/比例异常，日志里必须留下证据。
            Log.w(
                TAG,
                "setDefaultBufferSize(${previewSize.width}x${previewSize.height}) 失败，" +
                    "预览矩阵对 buffer 尺寸的假设可能不成立",
            )
        }

        // 没有可用的同比例 JPEG 时**不创建 ImageReader**：会话只挂预览 surface。
        // 这样既不会为了"有照片"去拉伸/近似比例，也不会让整台相机因为配不出对而打不开。
        val imageReader = captureSize?.let { size ->
            runCatching {
                ImageReader.newInstance(
                    size.width,
                    size.height,
                    ImageFormat.JPEG,
                    2,
                ).also { created ->
                    created.setOnImageAvailableListener({ reader ->
                        val image = runCatching { reader.acquireLatestImage() }
                            .getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            deliverImage(generation, image)
                        } finally {
                            runCatching { image.close() }
                        }
                    }, handler)
                }
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "创建抓帧 ImageReader(${size.width}x${size.height}) 失败，退回只预览",
                    error,
                )
            }.getOrNull()
        }
        if (captureSize != null && imageReader == null) {
            // 尺寸来自 StreamConfigurationMap，理论上一定支持；真建不出来也只丢抓帧，不丢预览。
            generation.captureWidth = 0
            generation.captureHeight = 0
            generation.captureDisabledReason = CAPTURE_DISABLED_READER_FAILED
            captureDisabledReason = CAPTURE_DISABLED_READER_FAILED
        }
        generation.reader = imageReader
        if (imageReader == null) {
            Log.w(
                TAG,
                "这一代只开预览：${generation.captureDisabledReason.ifBlank { "没有可用的照片输出" }}",
            )
        }

        return try {
            // 预览-only 时 surfaces 只有预览；抓帧 surface 只在真的能解码时才加入。
            val surfaces = mutableListOf(generation.surface)
            imageReader?.let { surfaces += it.surface }
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    val outcome = gate.ifCurrent(generation) { current ->
                        // 接管会话、下发重复请求、发布状态：全部在同一把锁内。
                        current.session = configured
                        try {
                            // 先发布矩阵再开始出帧：否则第一批 buffer 会先按旧矩阵
                            // （甚至单位阵）渲染一帧，真机上表现为闪一下拉伸/侧躺。
                            publishTransformLocked(current)
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
        captureDisabledReason = ""
        rotationTracker.reset()
        lastGeometryLog = null
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
            captureDisabledReason = ""
            rotationTracker.reset()
            lastGeometryLog = null
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
            val size = buffer.remaining()
            if (size <= 0 || size > MAX_CAPTURE_JPEG_BYTES) {
                // 先看字节数再复制：异常大的 JPEG 连 ByteArray 都不分配。
                // **不在这里动票据**：迟到的旧大图不能误伤新一代抓帧；当前请求由
                // CAPTURE_TIMEOUT_MILLIS 超时兜底返回 null（异常设备上最多多等一次超时）。
                Log.w(TAG, "相机帧字节数 $size 超出上限(${MAX_CAPTURE_JPEG_BYTES})，丢弃这一帧")
                return
            }
            ByteArray(size).also { buffer.get(it) }
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

    /** 抓到的一帧 + 提交时的几何快照（旋转/镜像只算一次、只解释一次）。 */
    private class CapturedJpeg(
        val bytes: ByteArray,
        val rotationDegrees: Int,
        val mirror: Boolean,
        val revision: Long,
    )

    /**
     * 抓一帧 JPEG；相机不在预览状态时返回 null，绝不排空等。
     *
     * **只预览**的这一代（没有同比例且预算内的 JPEG，`reader == null`）直接返回 null：
     * 不拉伸、不近似比例、不截预览图，宁可这一轮没有照片，也不发一张取景范围不同的图。
     */
    override suspend fun capture(): VideoFrame? {
        val generation = gate.currentOrNull() ?: return null
        if (generation.session == null) return null
        if (generation.reader == null) {
            Log.i(
                TAG,
                "抓帧已停用：${generation.captureDisabledReason.ifBlank { "这一代没有照片输出" }}",
            )
            return null
        }
        val captured = captureJpeg(generation) ?: return null
        // 字节上限在 deliverImage 已经先看过一次；这里再验一遍，保证交给下游的帧一定合规。
        if (captured.bytes.size > MAX_CAPTURE_JPEG_BYTES) {
            Log.w(TAG, "抓到的 JPEG ${captured.bytes.size}B 超过上限(${MAX_CAPTURE_JPEG_BYTES})，丢弃")
            return null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(captured.bytes, 0, captured.bytes.size, options) }
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "抓到的 JPEG 解不出尺寸，丢弃")
            return null
        }
        // 最后一道"所见即所发"闸门：即使 HAL 没按请求尺寸出图，JPEG 也必须与预览
        // **精确同比例**（有向交叉相乘，横竖互换不算）。近似比例会改变取景范围，直接丢弃。
        val previewWidth = generation.previewWidth
        val previewHeight = generation.previewHeight
        val sameAspectAsPreview =
            width.toLong() * previewHeight == previewWidth.toLong() * height
        if (!sameAspectAsPreview) {
            Log.w(
                TAG,
                "抓到的 JPEG ${width}x$height 与预览 ${previewWidth}x$previewHeight 比例不一致，丢弃",
            )
            return null
        }
        // 严格像素上限：下游 `VideoFrameStore` 是整张解码后再缩放，这里必须保证它一定解得动。
        // 不在控制器里做 inSampleSize 二次解码：选尺寸时已经把 JPEG 限制在预算内；
        // HAL 万一返回超预算图，直接丢弃比再解一次大图更安全（也避免二次压缩）。
        val pixels = width.toLong() * height.toLong()
        if (pixels > VideoPreviewGeometry.CAPTURE_MAX_PIXELS.toLong()) {
            Log.w(
                TAG,
                "抓到的 JPEG ${width}x$height 超出解码预算" +
                    "(${VideoPreviewGeometry.CAPTURE_MAX_PIXELS}px)，丢弃",
            )
            return null
        }
        // Check after decoding too; equal angle/mirror values cannot detect A → B → A.
        val stillCurrent = gate.exclusive {
            gate.currentOrNull() === generation && geometryRevision == captured.revision
        }
        if (!stillCurrent) return null
        return VideoFrame(
            bytes = captured.bytes,
            width = width,
            height = height,
            capturedAtMillis = System.currentTimeMillis(),
            rotationDegrees = captured.rotationDegrees,
            // 与预览同一个镜像开关：用户看到的方向/镜像就是发给模型的。
            mirror = captured.mirror,
        )
    }

    /**
     * 真正的抓帧请求。
     *
     * - **提交**（开票据 + capture + 记录几何快照）与 close/换代在同一把锁里，
     *   不会提交到已释放的会话上；
     * - `await` **不持锁**（否则一个慢抓帧会把整个 UI 的相机操作卡住）；
     * - `CancellationException` 原样透传：取消不是"这一帧没抓到"，吞掉它会让已取消的
     *   协程继续把照片写进聊天；
     * - `finally` 一定结束票据：清暂存、记作废水位，迟到的旧图喂不给下一次抓帧。
     */
    private suspend fun captureJpeg(generation: Generation): CapturedJpeg? {
        // **提交**与 close/换代在同一把锁里：不会提交到已经释放的会话上；
        // 几何快照也在这一把锁里取，之后用户改校准/镜像会让这张票据直接作废。
        val pending = gate.exclusive { submitCaptureLocked(generation) } ?: return null
        // await 不持锁：一个慢抓帧不能把整个 UI 的相机操作卡住。
        return try {
            val bytes = withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) { pending.deferred.await() } ?: return null
            CapturedJpeg(
                bytes = bytes,
                rotationDegrees = pending.rotationDegrees,
                mirror = pending.mirror,
                revision = pending.revision,
            )
        } catch (cancelled: CancellationException) {
            // 取消必须透传（不是"抓帧失败"）：吞掉它会让已取消的协程继续写出照片。
            throw cancelled
        } finally {
            // 无论是超时、取消还是正常返回：作废票据、清暂存、记作废水位。
            gate.exclusive {
                generation.tickets.end(pending.ticket)
                if (generation.capture === pending) generation.capture = null
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
        // 几何快照：与预览矩阵同一套计算（基准角 + 校准角、镜像开关）。
        val capture = PendingCapture(
            ticket = ticket,
            rotationDegrees = captureRotation(generation),
            mirror = previewMirrored,
            revision = geometryRevision,
        )
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
     * 抓帧需要的旋转角度：sensor 方向 + **当前**显示方向 + 用户校准角。
     *
     * 与预览矩阵**同一个纯函数**（[VideoPreviewGeometry.captureRotationDegrees] /
     * [VideoPreviewGeometry.uprightRotation]），因此预览正着看到的画面方向就是照片存下来的方向。
     * 预览使用 fit 留边显示整张源图；卡片比例只决定缩放，不再额外推断旋转方向。
     * 因此 JPEG 与预览使用同一个最终角度、相同完整取景范围。
     */
    private fun captureRotation(generation: Generation): Int = VideoPreviewGeometry.captureRotationDegrees(
        sensorOrientation = generation.sensorOrientation,
        displayRotationDegrees = generation.displayRotationDegrees,
        frontFacing = generation.frontFacing,
        calibrationDegrees = previewCalibrationDegrees,
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

    /**
     * 选中的预览尺寸 + 可选的抓帧尺寸。
     *
     * [capture] 为 null = 只预览：不建 ImageReader、`capture()` 返回 null，
     * [captureDisabledReason] 会作为 `unavailableReason` 交给提示词。
     */
    private class StreamSizes(
        val preview: Size,
        val capture: Size?,
        val captureDisabledReason: String = "",
    )

    /**
     * 同时选预览 / 抓帧尺寸，并保证两者**宽高比相同**（精确比例，不是近似）。
     *
     * 旧实现分别取"最长边 <= 1280 的最大面积"：预览常选中 1280x720(16:9)、
     * JPEG 常选中 1280x960(4:3)，模型收到的取景范围就比用户看到的多一块。
     * 现在由 [VideoPreviewGeometry.chooseCameraStreamPlan] 统一配对：
     * 优先同比例；只有更大的同比例 JPEG 时，选最小的那张且受严格解码预算限制；
     * 仍然没有时**保留预览、停用抓帧**，绝不近似比例误发。
     */
    private fun pickStreamSizes(configMap: android.hardware.camera2.params.StreamConfigurationMap?): StreamSizes? {
        val previewSizes = runCatching { configMap?.getOutputSizes(SurfaceTexture::class.java) }
            .getOrNull()?.map { PixelSize(it.width, it.height) }.orEmpty()
        val captureSizes = runCatching { configMap?.getOutputSizes(ImageFormat.JPEG) }
            .getOrNull()?.map { PixelSize(it.width, it.height) }.orEmpty()
        val plan = VideoPreviewGeometry.chooseCameraStreamPlan(
            previewSizes = previewSizes,
            captureSizes = captureSizes,
            // 预览仍严格限制在 1280 内；JPEG 的大图兜底预算显式传一次，便于审查。
            previewMaxEdge = VideoPreviewGeometry.PREVIEW_MAX_EDGE,
            captureMaxPixels = VideoPreviewGeometry.CAPTURE_MAX_PIXELS,
            captureFallbackMaxEdge = VideoPreviewGeometry.CAPTURE_FALLBACK_MAX_EDGE,
        )
        if (plan == null) {
            // 连预览都配不出来：这时相机确实开不了。
            Log.w(
                TAG,
                "相机没有最长边 <=${VideoPreviewGeometry.PREVIEW_MAX_EDGE} 的预览输出尺寸，停止相机会话",
            )
            return null
        }
        val capture = plan.capture
        if (capture != null) {
            Log.i(
                TAG,
                "相机尺寸预览=${plan.preview.width}x${plan.preview.height} " +
                    "抓帧=${capture.width}x${capture.height}（同宽高比）",
            )
            if (capture.maxEdge > VideoPreviewGeometry.PREVIEW_MAX_EDGE) {
                Log.i(
                    TAG,
                    "抓帧改用更大的同比例输出（预算内）：" +
                        "${capture.width}x${capture.height} > ${VideoPreviewGeometry.PREVIEW_MAX_EDGE}",
                )
            }
        } else {
            Log.w(TAG, "相机只开预览：${plan.captureDisabledReason}")
        }
        return StreamSizes(
            preview = Size(plan.preview.width, plan.preview.height),
            capture = capture?.let { Size(it.width, it.height) },
            captureDisabledReason = plan.captureDisabledReason,
        )
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
