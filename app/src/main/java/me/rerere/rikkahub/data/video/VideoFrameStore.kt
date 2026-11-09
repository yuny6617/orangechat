/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** 一帧画面最终存在哪里：决定它是"本次请求的临时输入"还是"永久照片"。 */
enum class FramePersistence {
    /** 自动抓帧（定时 / 每轮一帧）：只作为本次请求的临时图片输入，请求结束就删。 */
    Temp,

    /** 用户手动夹住的一帧：随下一句发送并永久保存（显式动作）。 */
    Clip,

    /** 模型 `<ws_vsnap/>` 留影：永久保存进聊天。 */
    Snapshot,
}

/**
 * 帧的压缩与落盘。
 *
 * 生命周期约定（和 brief / 审查意见一致）：
 * - 自动抓帧只写 `cacheDir/video_call_frames`，作为**本次请求作用域**的临时图片输入，
 *   请求结束/取消后被删除；聊天历史里永远只有文字；
 * - 用户手动夹帧、模型留影才写 `filesDir/...`，并且**永不删除**，
 *   这样历史消息不会引用已经不存在的图片文件；
 * - 文件名带时间戳，重复留影不会互相覆盖。
 */
object VideoFrameStore {
    private const val TAG = "VideoFrameStore"
    private const val TRANSIENT_DIR = "video_call_frames"
    private const val CLIP_DIR = "video_call_clips"
    private const val SNAPSHOT_DIR = "video_call_snapshots"

    fun decode(raw: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()

    /**
     * 把抓到的 JPEG 归一化成正立图像，再等比缩到最长边 + 固定质量重压。
     *
     * 方向只**转一次**（REVIEW_R4 §4）：
     * - 抓帧请求给 HAL 的 `JPEG_ORIENTATION` 固定是 0（[VideoPreviewGeometry.REQUESTED_JPEG_ORIENTATION]），
     *   所以像素是 sensor 原始朝向，这里按 [rotationDegrees] 施加唯一一次旋转；
     * - JPEG 自带的 EXIF 只读来诊断（[JpegOrientationPolicy]），绝不叠加成第二次旋转；
     * - 重新编码写出的字节不含 EXIF，落盘方向因此只有一种解释。
     *
     * 失败返回 null，绝不上传坏图。
     */
    fun compressToJpeg(
        raw: ByteArray,
        quality: VideoFrameQuality,
        rotationDegrees: Int = 0,
        mirror: Boolean = false,
    ): ByteArray? {
        val bitmap = decode(raw) ?: return null
        var rotated: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val exifOrientation = readExifOrientation(raw)
            val decision = JpegOrientationPolicy.decide(
                geometryRotationDegrees = rotationDegrees,
                mirror = mirror,
                exifOrientation = exifOrientation,
            )
            if (decision.exifContradictsRequest) {
                // 设备没有遵守"请求 0 度"：只记一次诊断，不改变像素方向。
                Log.w(
                    TAG,
                    "相机返回的 JPEG 带非正常 EXIF 方向($exifOrientation)，已忽略以保证只旋转一次",
                )
            }
            val affine = VideoPreviewGeometry.uprightImageAffine(
                rotationDegrees = decision.rotationDegrees,
                mirror = decision.mirror,
                width = bitmap.width,
                height = bitmap.height,
            )
            val upright = if (decision.rotationDegrees == 0 && !decision.mirror) {
                bitmap
            } else {
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    affine.toAndroidMatrix(),
                    true,
                ).also { rotated = it }
            }
            val target = VideoFramePolicy.forQuality(upright.width, upright.height, quality)
            val source = if (target.width != upright.width || target.height != upright.height) {
                Bitmap.createScaledBitmap(upright, target.width, target.height, true).also { scaled = it }
            } else {
                upright
            }
            val output = ByteArrayOutputStream()
            if (!source.compress(Bitmap.CompressFormat.JPEG, VideoFramePolicy.JPEG_QUALITY, output)) {
                null
            } else {
                output.toByteArray()
            }
        } catch (error: Exception) {
            null
        } finally {
            runCatching { scaled?.recycle() }
            if (rotated !== scaled) runCatching { rotated?.recycle() }
            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    /** 读 JPEG 的 EXIF Orientation（1..8）；读不到或格式不对返回 0。 */
    private fun readExifOrientation(raw: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        )
    }.getOrDefault(0)

    /**
     * 落盘一个 JPEG。
     *
     * 文件名用 UUID 而不是毫秒时间戳：并发写同一 prefix（两路抓帧/留影同时落盘）时，
     * 毫秒时间戳会互相覆盖 —— 一条历史消息的图片被另一张照片顶掉。
     *
     * 写入走"临时文件 + 原子 rename"：中途失败不会留下半张图，也不会让读者看到不完整的文件；
     * 写失败时把残片删掉。
     */
    fun saveJpeg(bytes: ByteArray, directory: File, prefix: String): File? = runCatching {
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = File(directory, "${prefix}_${UUID.randomUUID()}.jpg")
        val temporary = File(directory, "${target.name}.part")
        try {
            temporary.outputStream().use { it.write(bytes) }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return null
            }
            target
        } catch (error: Exception) {
            runCatching { temporary.delete() }
            runCatching { target.delete() }
            null
        }
    }.getOrNull()

    /** 本次请求作用域的临时帧目录（cacheDir，系统也可自行回收）。 */
    fun transientDirectory(context: Context): File = File(context.cacheDir, TRANSIENT_DIR)

    fun clipsDirectory(context: Context): File = File(context.filesDir, CLIP_DIR)

    fun snapshotsDirectory(context: Context): File = File(context.filesDir, SNAPSHOT_DIR)

    fun fileUrl(file: File): String = "file://${file.absolutePath}"

    /** 按持久化策略选目录；Temp 一定是 cacheDir。 */
    fun directoryFor(context: Context, persistence: FramePersistence): File = when (persistence) {
        FramePersistence.Temp -> transientDirectory(context)
        FramePersistence.Clip -> clipsDirectory(context)
        FramePersistence.Snapshot -> snapshotsDirectory(context)
    }

    fun prefixFor(persistence: FramePersistence): String = when (persistence) {
        FramePersistence.Temp -> "frame"
        FramePersistence.Clip -> "clip"
        FramePersistence.Snapshot -> "snapshot"
    }

    /** 删除本次请求用过的临时帧（只删 cacheDir 里的，绝不碰永久照片）。 */
    fun deleteTransient(context: Context?, urls: Collection<String>) {
        if (urls.isEmpty()) return
        val root = context?.let { transientDirectory(it) }?.absolutePath ?: File(TRANSIENT_DIR).absolutePath
        urls.forEach { url ->
            val path = url.removePrefix("file://")
            if (path.isBlank() || !path.startsWith(root)) return@forEach
            runCatching { File(path).delete() }
        }
    }

    /** 删除一个已经落盘的永久帧（写入聊天失败时用来避免留下无人引用的孤儿文件）。 */
    fun deleteSaved(context: Context, url: String) {
        val path = url.removePrefix("file://")
        if (path.isBlank()) return
        val roots = listOf(
            clipsDirectory(context).absolutePath,
            snapshotsDirectory(context).absolutePath,
        )
        if (roots.none { path.startsWith(it) }) return
        runCatching { File(path).delete() }
    }

    /**
     * 删除本模块**自己拥有**的任意一帧（临时 / 手动夹帧 / 留影）。
     *
     * 只认这三个目录里的路径，别的一律不碰——调用方拿到 url 之后被取消时用它兜底，
     * 既不会留孤儿文件，也不会误删别处的东西。
     */
    fun deleteOwned(context: Context, url: String) {
        val path = url.removePrefix("file://")
        if (path.isBlank()) return
        val roots = listOf(
            transientDirectory(context).absolutePath,
            clipsDirectory(context).absolutePath,
            snapshotsDirectory(context).absolutePath,
        )
        if (roots.none { path.startsWith(it) }) return
        runCatching { File(path).delete() }
    }

    /**
     * 孤儿临时帧的安全清理。
     *
     * **不能**在通话开始/结束时整目录删：同一时刻可能还有别的请求正在使用其中的图
     * （取消中的请求、另一路调用还没结束的写文件）。只删"足够旧"的文件：
     * 正常请求的生命周期只有几秒到几分钟，超过 [minAgeMillis] 的必然是孤儿。
     */
    fun sweepStaleTransients(context: Context, minAgeMillis: Long = ORPHAN_MIN_AGE_MILLIS) {
        val directory = transientDirectory(context)
        val now = System.currentTimeMillis()
        runCatching {
            if (!directory.exists()) return@runCatching
            directory.listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() >= minAgeMillis) file.delete()
            }
        }
    }

    /** 孤儿判定龄期：远大于一次请求从抓帧到删除的正常耗时。 */
    const val ORPHAN_MIN_AGE_MILLIS: Long = 30 * 60 * 1000L
}

/**
 * 纯几何 → `android.graphics.Matrix`。
 *
 * Android 的 Matrix 值顺序是 `[MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, 0,0,1]`，
 * 对应 `x' = a*x + c*y + e`、`y' = b*x + d*y + f`，与 [Affine] 的字段一一对应。
 * 预览矩阵也走同一个转换，所以"预览怎么转"和"照片怎么转"用的是同一份数学。
 */
internal fun Affine.toAndroidMatrix(): Matrix = Matrix().apply {
    setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f))
}
