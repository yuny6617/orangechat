/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.video

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.video.VIDEO_GIFT_EFFECT_DURATION_MS
import me.rerere.rikkahub.data.video.VideoGift
import me.rerere.rikkahub.data.video.VideoGiftEffect
import me.rerere.rikkahub.data.video.VideoGiftEffectRequest
import kotlin.math.cos
import kotlin.math.sin

/**
 * 原创全屏礼物特效（Compose Canvas，纯绘制，不拦截触摸）。
 *
 * 三档高礼物各有一段 8.2 秒的完整动画：
 * - 夏日烟火：五轮不同高度的烟火爆发 + 重力下坠的粒子；
 * - 流星雨：倾斜落下的流星带 + 拖尾余晖；
 * - 银河铁道之夜：银河背景 + 星点 + 发光列车 + 流星。
 *
 * 整段 overlay 按请求的**事件身份**（[VideoGiftEffectRequest.key]）分组：连续两次送同档位
 * 礼物时枚举相等，但 key 不同，`Animatable` 与 `LaunchedEffect` 都会重建，
 * 第二次一定从 0 重新播，不会停在上一轮已经跑完的 1f 上（空屏）。
 */
@Composable
internal fun VideoGiftEffectOverlay(
    effect: VideoGiftEffectRequest?,
    modifier: Modifier = Modifier,
) {
    if (effect == null) return
    val gift = effect.gift
    if (!gift.isFullScreenEffect()) return
    key(effect.key) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = VIDEO_GIFT_EFFECT_DURATION_MS.toInt(), easing = LinearEasing),
            )
        }
        val value = progress.value
        Canvas(modifier.fillMaxSize()) {
            when (gift.effect) {
                VideoGiftEffect.Fireworks -> drawFireworks(value)
                VideoGiftEffect.MeteorShower -> drawMeteorShower(value, fullScreen = true)
                VideoGiftEffect.GalaxyTrain -> drawGalaxyTrain(value)
                VideoGiftEffect.Bubble -> Unit
            }
        }
    }
}

private fun VideoGift.isFullScreenEffect(): Boolean = effect != VideoGiftEffect.Bubble

/** 低档礼物（心心/花束）的预览：它们本来就不是全屏动画，只挂全屏 overlay 会什么都看不见。 */
internal data class VideoGiftBubblePreviewState(
    val key: String,
    val gift: VideoGift,
    val label: String,
)

/** 气泡预览的展示时长；比 8.2 秒的全屏特效短，不长时间压住页面。 */
internal const val VIDEO_GIFT_BUBBLE_PREVIEW_MS: Long = 2_600L

/**
 * 心心 / 花束这类"只出气泡"的礼物的可见预览。
 *
 * 关键约束：
 * - **纯绘制，不拦截触摸**（没有 clickable/pointerInput）：预览期间返回、关闭、底部按钮照常可点；
 * - 只在页面上浮一颗气泡，不是全屏遮罩，所以也不会挡住顶栏；
 * - 和通话里一样显示 `emoji + 当前展示名`，用户改完名字就能立刻看到效果。
 */
@Composable
internal fun VideoGiftBubblePreview(
    preview: VideoGiftBubblePreviewState?,
    modifier: Modifier = Modifier,
) {
    if (preview == null) return
    key(preview.key) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = VIDEO_GIFT_BUBBLE_PREVIEW_MS.toInt(),
                    easing = LinearEasing,
                ),
            )
        }
        val value = progress.value
        // 0.0-0.15 淡入上浮，0.75-1.0 淡出，中段稳定可读。
        val alpha = when {
            value < 0.15f -> value / 0.15f
            value > 0.75f -> ((1f - value) / 0.25f).coerceIn(0f, 1f)
            else -> 1f
        }
        val rise = (1f - value) * 24f
        Box(
            modifier
                .fillMaxSize()
                .padding(bottom = 96.dp)
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = -rise
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.82f))
                    .border(1.dp, Color.White, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(preview.gift.emoji, fontSize = 18.sp)
                Text(preview.label, fontSize = 13.sp, color = Color(0xFF2B2F44))
            }
        }
    }
}

/** 稳定的伪随机：每帧都相同，避免粒子抖动。 */
private fun noise(seed: Int): Float {
    var value = seed * 1103515245 + 12345
    value = value xor (value shr 16)
    value *= 2246822519.toInt()
    value = value xor (value shr 13)
    return ((value and 0x7fffffff).toFloat()) / 2147483647f
}

private fun DrawScope.drawFireworks(progress: Float) {
    val palette = listOf(
        Color(0xFFFFD98A),
        Color(0xFFFF9EC4),
        Color(0xFF9CE7FF),
        Color(0xFFC9A9FF),
        Color(0xFFFFB07A),
    )
    repeat(5) { burst ->
        val start = burst * 0.13f
        val local = ((progress - start) / 0.6f).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) return@repeat
        val color = palette[burst % palette.size]
        val center = Offset(
            x = size.width * (0.18f + 0.16f * burst + 0.05f * noise(burst * 7)),
            y = size.height * (0.16f + 0.11f * (burst % 3) + 0.04f * noise(burst * 11)),
        )
        val radius = size.minDimension * (0.06f + 0.34f * local)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = (1f - local) * 0.5f), Color.Transparent),
                center = center,
                radius = radius * 1.5f,
            ),
            radius = radius * 1.5f,
            center = center,
        )
        repeat(20) { index ->
            val angle = (index / 20f) * 6.2832f + noise(burst * 31 + index) * 0.35f
            val speed = 0.65f + noise(burst * 17 + index * 7) * 0.55f
            val distance = radius * speed
            val gravity = radius * 0.5f * local * local
            drawCircle(
                color = color.copy(alpha = ((1f - local) * 1.4f).coerceIn(0f, 1f)),
                radius = size.minDimension * 0.006f,
                center = Offset(
                    x = center.x + cos(angle) * distance,
                    y = center.y + sin(angle) * distance + gravity,
                ),
            )
        }
    }
}

private fun DrawScope.drawMeteorShower(progress: Float, fullScreen: Boolean) {
    val count = if (fullScreen) 44 else 18
    repeat(count) { index ->
        val offset = (index % 11) * 0.06f
        val local = ((progress - offset) / 0.72f).coerceIn(0f, 1f)
        if (local <= 0f) return@repeat
        val startX = size.width * (0.02f + 1.02f * noise(index * 13))
        val startY = -size.height * 0.15f
        val travel = size.height * 1.45f * local
        val head = Offset(startX + travel * 0.42f, startY + travel)
        val length = size.height * (0.05f + 0.10f * noise(index * 7))
        val tail = Offset(head.x - length * 0.45f, head.y - length)
        val fade = (1f - (local - 0.75f).coerceAtLeast(0f) / 0.25f).coerceIn(0f, 1f)
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color(0xFFBFE9FF).copy(alpha = fade)),
                start = tail,
                end = head,
            ),
            start = tail,
            end = head,
            strokeWidth = size.minDimension * 0.008f,
        )
        drawCircle(
            color = Color.White.copy(alpha = fade),
            radius = size.minDimension * 0.006f,
            center = head,
        )
    }
}

private fun DrawScope.drawGalaxyTrain(progress: Float) {
    // 银河背景：深空渐变 + 斜向星河带
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF0B0A1F), Color(0xFF1B1440), Color(0xFF2A1B55), Color(0xFF0B0A1F)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        size = size,
    )
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color(0xFF6F5BD6).copy(alpha = 0.28f), Color.Transparent),
            start = Offset(0f, size.height * 0.75f),
            end = Offset(size.width, size.height * 0.15f),
        ),
        size = size,
    )
    // 星点
    repeat(140) { index ->
        val twinkle = 0.35f + 0.65f * kotlin.math.abs(sin(progress * 9f + index * 1.7f))
        drawCircle(
            color = Color.White.copy(alpha = 0.25f + 0.5f * twinkle),
            radius = size.minDimension * (0.0016f + 0.0022f * noise(index * 3)),
            center = Offset(
                x = size.width * noise(index * 29),
                y = size.height * noise(index * 41),
            ),
        )
    }
    // 流星
    drawMeteorShower(progress, fullScreen = false)

    // 发光列车：从左下驶向右上，车身带窗与车头灯
    val travel = progress
    val trainX = -size.width * 0.35f + size.width * 1.55f * travel
    val trainY = size.height * 0.66f - size.height * 0.10f * travel
    val bodyWidth = size.width * 0.34f
    val bodyHeight = size.height * 0.055f
    val body = Offset(trainX, trainY)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFE9A8).copy(alpha = 0.45f), Color.Transparent),
            center = Offset(body.x + bodyWidth, body.y + bodyHeight / 2f),
            radius = bodyWidth * 0.9f,
        ),
        radius = bodyWidth * 0.9f,
        center = Offset(body.x + bodyWidth, body.y + bodyHeight / 2f),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF2B2F6B), Color(0xFF6F7BD6), Color(0xFFFFE9A8)),
            start = body,
            end = Offset(body.x + bodyWidth, body.y + bodyHeight),
        ),
        topLeft = body,
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyHeight * 0.45f),
    )
    // 车窗
    repeat(4) { index ->
        val windowSize = Size(bodyWidth * 0.08f, bodyHeight * 0.42f)
        drawRoundRect(
            color = Color(0xFFFFF6C8).copy(alpha = 0.9f),
            topLeft = Offset(
                body.x + bodyWidth * (0.12f + 0.19f * index),
                body.y + bodyHeight * 0.28f,
            ),
            size = windowSize,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(windowSize.height * 0.4f),
        )
    }
    // 车头灯
    drawCircle(
        color = Color(0xFFFFF3C4),
        radius = bodyHeight * 0.28f,
        center = Offset(body.x + bodyWidth * 0.97f, body.y + bodyHeight * 0.5f),
    )
    // 铁轨光带
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color(0xFF7F9CFF).copy(alpha = 0.55f), Color.Transparent),
            start = Offset(0f, size.height * 0.86f),
            end = Offset(size.width, size.height * 0.86f),
        ),
        start = Offset(0f, size.height * 0.86f),
        end = Offset(size.width, size.height * 0.86f),
        strokeWidth = size.minDimension * 0.004f,
    )
}
