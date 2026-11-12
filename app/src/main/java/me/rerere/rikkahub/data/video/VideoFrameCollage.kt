package me.rerere.rikkahub.data.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 把最近几帧拼成一张固定尺寸的 JPEG（最多九宫格）。
 *
 * - 统一 [SIDE] × [SIDE]，模型/服务端拿到的图大小稳定；
 * - 每帧在**自己的格子**里等比缩放，不拉伸：预览是什么方向，拼图里就是什么方向
 *   （调用方传进来的已经是按 rotation/mirror 校正过的 JPEG）；
 * - 剩余空间用深色背景填（letterbox），不是裁掉用户画面；
 * - 任何一帧解不出来就整张失败，绝不悄悄少拼一格 / 上传半张图。
 */
internal object VideoFrameCollage {
    const val SIDE = 1200

    fun compose(frames: List<ByteArray>): ByteArray? {
        if (frames.isEmpty() || frames.size > 9) return null
        val columns = ceil(sqrt(frames.size.toDouble())).toInt().coerceIn(1, 3)
        val rows = (frames.size + columns - 1) / columns
        val cellWidth = SIDE / columns
        val cellHeight = SIDE / rows
        val sheet = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(16, 22, 28))
            // 缩小到格子里时开双线性过滤：否则大图缩到 400px 会明显锯齿。
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            frames.forEachIndexed { index, bytes ->
                val bitmap = decodeCell(bytes, cellWidth, cellHeight) ?: return null
                try {
                    val cellLeft = (index % columns) * cellWidth
                    val cellTop = (index / columns) * cellHeight
                    val scale = minOf(
                        cellWidth.toFloat() / bitmap.width,
                        cellHeight.toFloat() / bitmap.height,
                    )
                    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    // 在格子内居中：横图上下留边，竖图左右留边，比例始终不变。
                    val left = cellLeft + (cellWidth - width) / 2
                    val top = cellTop + (cellHeight - height) / 2
                    canvas.drawBitmap(bitmap, null, Rect(left, top, left + width, top + height), paint)
                } finally {
                    bitmap.recycle()
                }
            }
            val output = ByteArrayOutputStream()
            return if (sheet.compress(Bitmap.CompressFormat.JPEG, VideoFramePolicy.JPEG_QUALITY, output)) {
                output.toByteArray()
            } else {
                null
            }
        } finally {
            sheet.recycle()
        }
    }

    /** 先读尺寸，再按格子大小采样解码，避免为 400px 的格子把整张 1280px 原图读进内存。 */
    private fun decodeCell(bytes: ByteArray, cellWidth: Int, cellHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(
                1,
                maxOf(bounds.outWidth / cellWidth, bounds.outHeight / cellHeight),
            )
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
