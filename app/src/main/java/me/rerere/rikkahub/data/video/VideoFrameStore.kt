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

    /**
     * 一次解码允许读入的 raw 字节上限。
     *
     * 抓帧链路自己已经把 JPEG 限在 4MB / [VideoPreviewGeometry.CAPTURE_MAX_PIXELS] 以内
     * （见 `VideoCallCameraController`），这里是"绝不因为一张畸形输入去申请内存"的兜底：
     * 超过就直接判坏图，连尺寸都不读。
     */
    const val MAX_RAW_BYTES: Int = 16 * 1024 * 1024

    /** 源图单边上限：`bounds` 读出来超过它就直接判坏图，连解码都不试。 */
    const val MAX_SOURCE_EDGE: Int = 12_000

    /** 源图总像素上限（约 40MP）：远超任何相机输出，只用来挡畸形/恶意输入。 */
    const val MAX_SOURCE_PIXELS: Long = 40_000_000L

    /**
     * 采样之后一张位图允许占用的像素上限（4MP ≈ 16MB ARGB_8888）。
     *
     * 真正的目标边只有 384/512/768，正常路径解出来的图远小于这个数；这个上限是
     * "解码器没有完全照办 `inSampleSize`" 时的最后一道硬闸门。
     */
    const val MAX_DECODED_PIXELS: Long = 4_194_304L

    /** [decode] 不指定目标边时的默认预算：和预览的最长边上限一致。 */
    const val DEFAULT_DECODE_MAX_EDGE: Int = VideoPreviewGeometry.PREVIEW_MAX_EDGE

    /** 目标边的硬上限：传进来再大也只按这个预算解码，内存必须可预期。 */
    const val MAX_DECODE_MAX_EDGE: Int = 2048

    /** 采样因子的硬上限（2 的幂）。正常输入到不了这里，只是防止病态输入把循环拖长。 */
    private const val MAX_SAMPLE_SIZE: Int = 64

    /**
     * 有界解码：先只读尺寸（`inJustDecodeBounds`），再按 [maxEdgePx] 用 `inSampleSize`
     * **采样解码**，永远不分配源图尺寸的位图。
     *
     * 为什么必须这样（相机代理确认的旧行为）：旧实现是 `BitmapFactory.decodeByteArray`
     * **整张解码**、再 `createScaledBitmap` 缩到 384/512/768 —— 一张 12MP 的 JPEG 会先要
     * 48MB 的 ARGB_8888，低端机上直接 OOM，而这张图最后只会被缩到几百像素。
     *
     * 采样只取 2 的幂、宽高用**同一个**因子，所以是等比缩小：不裁切、不拉伸，
     * "所见即所发"的取景范围不变。旋转不改变最长边，因此"按最长边采样"在旋转前后是
     * 同一个预算，[compressToJpeg] 可以先用最终目标边解出一张略大的图，再等比缩一次。
     *
     * 下列情况一律返回 null（坏图/超限），绝不抛给调用方、绝不留未回收的位图：
     * raw 为空或超过 [MAX_RAW_BYTES]；读不出尺寸；源图单边超过 [MAX_SOURCE_EDGE] 或
     * 总像素超过 [MAX_SOURCE_PIXELS]；解码结果仍超过 [MAX_DECODED_PIXELS]。
     *
     * @return 解码后的位图（最长边不小于 [maxEdgePx]，源图本身更小时不放大）；
     *         调用方负责 `recycle()`。
     */
    fun decode(raw: ByteArray, maxEdgePx: Int = DEFAULT_DECODE_MAX_EDGE): Bitmap? {
        val source = readSourceBounds(raw) ?: return null
        val bound = maxEdgePx.coerceIn(1, MAX_DECODE_MAX_EDGE)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(source, bound)
            // 定量解码：颜色不能被悄悄降成 RGB_565；密度缩放也不能掺进最终比例里。
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
        } catch (outOfMemory: OutOfMemoryError) {
            // 采样已经算过了，正常路径不会走到这里；真撞上就放弃这一帧，不让进程崩掉。
            Log.w(TAG, "解码这一帧时 OOM，已放弃（${outOfMemory.message}）")
            null
        } catch (error: Exception) {
            null
        }
        val bitmap = decoded ?: return null
        // 采样只是"请求"：解码后再验一次像素预算，超了就回收 + 判坏图，绝不把大图交出去。
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (bitmap.width <= 0 || bitmap.height <= 0 || pixels > MAX_DECODED_PIXELS) {
            runCatching { bitmap.recycle() }
            return null
        }
        return bitmap
    }

    /**
     * 只读尺寸（`inJustDecodeBounds`），不做任何像素分配。
     *
     * 坏文件、非图片、只有半截头都会让这里返回 null —— 坏图就是 null，
     * 而不是一个"能返回但没法用"的半条命位图。
     */
    private fun readSourceBounds(raw: ByteArray): SourceBounds? {
        if (raw.size <= 0 || raw.size > MAX_RAW_BYTES) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size, options) }
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        if (maxOf(width, height) > MAX_SOURCE_EDGE) return null
        if (width.toLong() * height.toLong() > MAX_SOURCE_PIXELS) return null
        return SourceBounds(width, height)
    }

    /**
     * 2 的幂采样因子：两个方向共用**同一个**因子，所以比例不变（不会拉扁 / 拉长）。
     *
     * 两条约束，按优先级：
     * 1. 像素预算：解码结果不许超过 [MAX_DECODED_PIXELS]（硬上限，先满足）；
     * 2. 目标边：在预算内尽量让解码后的最长边不小于 [maxEdgePx]，这样后面那次缩放只会
     *    缩小、不会"先采小再放大"把画面糊掉 —— 也绝不上采样。
     */
    private fun sampleSizeFor(source: SourceBounds, maxEdgePx: Int): Int {
        var sample = 1
        // 1) 像素预算：当前预测尺寸还超预算，就再采一格。
        while (sample <= MAX_SAMPLE_SIZE / 2 && source.pixelsAt(sample) > MAX_DECODED_PIXELS) {
            sample *= 2
        }
        // 2) 目标边：再采一格的最长边仍不小于目标边，就继续采（取"最大的安全因子"）。
        while (sample <= MAX_SAMPLE_SIZE / 2 && source.longest / (sample * 2) >= maxEdgePx) {
            sample *= 2
        }
        return sample
    }

    /** `bounds` 读到的源图尺寸（还没解码，不占像素内存）。 */
    private class SourceBounds(val width: Int, val height: Int) {
        val longest: Int
            get() = maxOf(width, height)

        /** 按 [sample] 采样之后解码器大约会分配多少像素（宽高各除同一个因子）。 */
        fun pixelsAt(sample: Int): Long {
            val sampledWidth = (width / sample).coerceAtLeast(1)
            val sampledHeight = (height / sample).coerceAtLeast(1)
            return sampledWidth.toLong() * sampledHeight.toLong()
        }
    }

    /**
     * 把抓到的 JPEG 归一化成正立图像，再等比缩到最长边 + 固定质量重压。
     *
     * 方向只**转一次**（REVIEW_R4 §4）：
     * - 抓帧请求给 HAL 的 `JPEG_ORIENTATION` 固定是 0（[VideoPreviewGeometry.REQUESTED_JPEG_ORIENTATION]），
     *   所以像素是 sensor 原始朝向，这里按 [rotationDegrees] 施加唯一一次旋转；
     * - JPEG 自带的 EXIF 只读来诊断（[JpegOrientationPolicy]），绝不叠加成第二次旋转；
     * - 重新编码写出的字节不含 EXIF，落盘方向因此只有一种解释。
     *
     * 解码是**有界**的（见 [decode]）：按最终目标最长边先采样再解，绝不整张解码；
     * 旋转不改变最长边，所以"目标边 / 解码预算"在转正前后是同一个数 —— 采样既不会
     * 把图裁掉，也不会"先缩太小再放大"。
     *
     * 失败返回 null，绝不上传坏图。
     */
    fun compressToJpeg(
        raw: ByteArray,
        quality: VideoFrameQuality,
        rotationDegrees: Int = 0,
        mirror: Boolean = false,
    ): ByteArray? {
        // 最终目标最长边同时也是解码预算：解码出来的图一定不小于它（源图更小时不放大）。
        val targetEdge = minOf(quality.maxEdgePx, VideoFramePolicy.MAX_CAPTURE_EDGE)
        val bitmap = decode(raw, targetEdge) ?: return null
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
            // 等比：forQuality 只按比例算宽高，createScaledBitmap 用它缩放 —— 只缩不拉伸。
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
        } catch (outOfMemory: OutOfMemoryError) {
            // 旋转/缩放这一步也可能 OOM（上一帧还没回收、堆正好很紧）：
            // 这一帧不要了，但绝不让进程崩在压缩上。
            Log.w(TAG, "压缩这一帧时 OOM，已放弃（${outOfMemory.message}）")
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
