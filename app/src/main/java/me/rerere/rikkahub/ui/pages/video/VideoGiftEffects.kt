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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.rerere.rikkahub.data.video.VIDEO_GIFT_METEOR_COUNT
import me.rerere.rikkahub.data.video.VIDEO_GIFT_GALAXY_METEOR_COUNT
import me.rerere.rikkahub.data.video.VIDEO_GIFT_METEOR_LIFETIME
import me.rerere.rikkahub.data.video.VIDEO_GIFT_METEOR_START_SPAN
import kotlin.math.atan2
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
 * 原创全屏礼物特效（Compose Canvas；仅可选的跳过按钮接收触摸，其余绘制层透传）。
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
    onSkip: ((String) -> Unit)? = null,
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
        Box(modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                // Read in the draw phase: animation frames do not recompose the button or page.
                val value = progress.value
                when (gift.effect) {
                    VideoGiftEffect.Fireworks -> drawFireworks(value)
                    VideoGiftEffect.MeteorShower -> drawMeteorShower(value, fullScreen = true)
                    VideoGiftEffect.GalaxyTrain -> drawGalaxyTrain(value)
                    VideoGiftEffect.Bubble -> Unit
                }
            }
            if (onSkip != null) {
                Button(
                    onClick = { onSkip(effect.key) },
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(top = 72.dp, end = 16.dp)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = "跳过当前礼物特效，继续播放队列" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF242640),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("跳过特效")
                }
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
    val envelope = minOf(progress / 0.08f, (1f - progress) / 0.12f, 1f).coerceIn(0f, 1f)
    if (fullScreen) {
        // A translucent night veil preserves the call beneath, even on bright glass themes.
        drawRect(Color(0xFF101B39).copy(alpha = 0.58f * envelope))
    }
    val count = if (fullScreen) VIDEO_GIFT_METEOR_COUNT else VIDEO_GIFT_GALAXY_METEOR_COUNT
    repeat(count) { index ->
        // Stratified starts avoid synchronized clumps; <=29 full-screen meteors alive at once.
        val start = (index + noise(index * 19) * 0.65f) / count * VIDEO_GIFT_METEOR_START_SPAN
        val local = (progress - start) / VIDEO_GIFT_METEOR_LIFETIME
        if (local <= 0f || local >= 1f) return@repeat
        val depth = 0.55f + 0.45f * noise(index * 23)
        val startX = size.width * (-0.48f + 1.42f * noise(index * 13))
        val startY = size.height * (-0.10f + 0.30f * noise(index * 31))
        val travel = size.height * 1.18f * local
        val head = Offset(startX + travel * 0.42f, startY + travel)
        val length = size.height * (0.04f + 0.07f * depth)
        val tail = Offset(head.x - length * 0.42f, head.y - length)
        if (head.x < 0f || tail.x > size.width || tail.y > size.height) return@repeat
        val fade = minOf(local / 0.12f, (1f - local) / 0.24f, 1f).coerceIn(0f, 1f) * envelope
        // Cheap indigo under-stroke, not a per-particle blur/layer, gives bright-background contrast.
        drawLine(
            color = Color(0xFF344F89).copy(alpha = fade * 0.6f),
            start = tail, end = head,
            strokeWidth = size.minDimension * 0.010f * depth,
            cap = StrokeCap.Round,
        )
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

    // One shared sloping coordinate system: wheels actually sit on the twin rails.
    val railStart = Offset(0f, size.height * 0.73f)
    val railEnd = Offset(size.width, size.height * 0.53f)
    val angle = atan2(railEnd.y - railStart.y, size.width) * 180f / kotlin.math.PI.toFloat()
    val unit = size.minDimension
    val railGap = unit * 0.026f
    repeat(38) { index ->
        val x = size.width * index / 37f
        val y = railStart.y + (railEnd.y - railStart.y) * index / 37f
        drawLine(Color(0xFF7284CB).copy(alpha = 0.48f),
            Offset(x - unit * 0.009f, y - railGap), Offset(x + unit * 0.009f, y + railGap),
            strokeWidth = unit * 0.004f)
    }
    repeat(2) { rail ->
        val shift = Offset(0f, if (rail == 0) -railGap * 0.55f else railGap * 0.55f)
        drawLine(Color(0xFF6C66DC).copy(alpha = 0.28f), railStart + shift, railEnd + shift,
            strokeWidth = unit * 0.018f)
        drawLine(Color(0xFFADBFFF), railStart + shift, railEnd + shift,
            strokeWidth = unit * 0.003f)
    }
    val carWidth = unit * 0.23f
    val carHeight = unit * 0.085f
    val trainX = -carWidth * 3.5f + (size.width + carWidth * 4.2f) * progress
    val trainY = railStart.y + (railEnd.y - railStart.y) * trainX / size.width
    translate(trainX, trainY) {
        rotate(angle, Offset.Zero) {
            // Three connected carriages, a dark chassis, rim-lit roof and warm recessed windows.
            repeat(3) { car ->
                val x = car * carWidth * 1.08f
                val top = -carHeight - railGap * 0.3f
                drawLine(Color(0xFFB3C6FF), Offset(x - carWidth * 0.1f, -carHeight * 0.2f),
                    Offset(x, -carHeight * 0.2f), strokeWidth = unit * 0.009f)
                drawRoundRect(Color(0xFF111831), Offset(x, top + carHeight * 0.12f),
                    Size(carWidth, carHeight), CornerRadius(carHeight * 0.22f))
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFFC1C9FF), Color(0xFF606DAB), Color(0xFF283556)),
                        startY = top, endY = top + carHeight,
                    ),
                    topLeft = Offset(x, top), size = Size(carWidth, carHeight * 0.86f),
                    cornerRadius = CornerRadius(carHeight * 0.22f),
                )
                drawLine(Color(0xFFFFDFAA), Offset(x + carWidth * 0.05f, top + carHeight * 0.76f),
                    Offset(x + carWidth * 0.95f, top + carHeight * 0.76f), strokeWidth = unit * 0.003f)
                repeat(4) { window ->
                    val windowTop = Offset(x + carWidth * (0.09f + window * 0.22f), top + carHeight * 0.22f)
                    drawRoundRect(Color(0xFF252D55), windowTop - Offset(unit * 0.003f, unit * 0.003f),
                        Size(carWidth * 0.16f, carHeight * 0.42f), CornerRadius(unit * 0.004f))
                    drawRoundRect(Color(0xFFFFE9B2), windowTop,
                        Size(carWidth * 0.13f, carHeight * 0.34f), CornerRadius(unit * 0.003f))
                }
                repeat(2) { wheel ->
                    val center = Offset(x + carWidth * (0.2f + wheel * 0.6f), -railGap * 0.25f)
                    drawCircle(Color(0xFF10172E), unit * 0.012f, center)
                    drawCircle(Color(0xFFB5C5F5), unit * 0.005f, center)
                }
            }
            val lamp = Offset(carWidth * 3.16f, -carHeight * 0.6f)
            val beam = Path().apply {
                moveTo(lamp.x, lamp.y)
                lineTo(lamp.x + carWidth * 1.3f, lamp.y - carHeight * 0.7f)
                lineTo(lamp.x + carWidth * 1.3f, lamp.y + carHeight * 0.7f)
                close()
            }
            drawPath(beam, Brush.horizontalGradient(
                listOf(Color(0xFFFFE9AD).copy(alpha = 0.32f), Color.Transparent),
                startX = lamp.x, endX = lamp.x + carWidth * 1.3f,
            ))
            drawCircle(Color(0xFFFFF3CC), unit * 0.012f, lamp)
        }
    }
}
