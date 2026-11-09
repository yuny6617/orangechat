/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.video.caption

/**
 * 聊天里的持久礼物卡。
 *
 * 它只是通话里发生过的虚拟礼物的记录：没有金额、没有钱包、没有支付入口，
 * 也永远不可点击交易。
 */
@Composable
internal fun VideoCallGiftCard(
    gift: UIMessageAnnotation.VideoCallGift,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1B1B24), Color(0xFF2B2440))), shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .testTag("video-gift-card")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("视频通话礼物 · 纯虚拟", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(gift.caption(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("×1", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        Text(
            "只存在于这次通话的画面里，没有真实价值",
            modifier = Modifier.fillMaxWidth(),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}
