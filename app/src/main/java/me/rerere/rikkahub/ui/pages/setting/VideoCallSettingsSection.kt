/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.video.VideoCallPrompts
import me.rerere.rikkahub.data.video.VideoCallSetting
import me.rerere.rikkahub.data.video.VideoFrameCaptureMode
import me.rerere.rikkahub.data.video.VideoFrameQuality

/**
 * 视频通话设置（默认折叠，避免把语音页挤爆）。
 *
 * 礼物默认关闭，并在展开后直接给出完整送礼提示词原文，方便人工审查后再开启。
 * 抓帧只影响"下一轮请求要不要带画面"，不会在后台自动发消息。
 */
@Composable
internal fun VideoCallSettingsSection(
    setting: VideoCallSetting,
    onUpdate: (VideoCallSetting) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showGiftPrompt by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("视频通话", style = MaterialTheme.typography.titleSmall)
            Text(
                "礼物：${if (setting.giftsEnabled) "已开启" else "关闭"} · " +
                    "抓帧：${setting.frameCaptureMode.displayName} · ${setting.frameQuality.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (!expanded) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "独立入口：聊天输入框右侧的 + → 视频通话。保留原来的语音通话入口，两者共用同一通电话。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("视频通话礼物", style = MaterialTheme.typography.titleSmall)
            Text(
                "默认关闭。开启后模型才可以用 <ws_gift id=\"...\"/> 送一件纯虚拟礼物，" +
                    "触发通话内气泡与全屏动画；礼物没有真实金额、不扣余额、不调用钱包。" +
                    "送礼提示词需要人工审定，未审定前建议保持关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = setting.giftsEnabled,
            onCheckedChange = { onUpdate(setting.copy(giftsEnabled = it)) },
        )
    }
    Text(
        text = if (showGiftPrompt) "收起送礼提示词" else "查看送礼提示词（人工审查用）",
        modifier = Modifier.padding(horizontal = 16.dp).clickable { showGiftPrompt = !showGiftPrompt }
            .padding(vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    if (showGiftPrompt) {
        Text(
            text = VideoCallPrompts.GIFT_INSTRUCTION,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
        )
    }

    SettingGroup(title = "自动抓帧") {
        VideoFrameCaptureMode.entries.forEach { mode ->
            SettingChoiceRow(
                title = mode.displayName,
                subtitle = when (mode) {
                    VideoFrameCaptureMode.OFF -> "不自动附带任何画面"
                    VideoFrameCaptureMode.EVERY_REPLY -> "每次发送前现抓一帧，随这条消息发出去"
                    else -> "每 ${mode.intervalMillis?.div(1000) ?: 30} 秒刷新一次待发送帧，发送时带最新一帧"
                },
                selected = setting.frameCaptureMode == mode,
                onSelect = { onUpdate(setting.copy(frameCaptureMode = mode)) },
            )
        }
    }

    SettingGroup(title = "抓帧画质") {
        VideoFrameQuality.entries.forEach { quality ->
            SettingChoiceRow(
                title = quality.displayName,
                subtitle = "JPEG 压缩质量固定，按最长边等比缩放",
                selected = setting.frameQuality == quality,
                onSelect = { onUpdate(setting.copy(frameQuality = quality)) },
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("显示我打的字", style = MaterialTheme.typography.titleSmall)
            Text(
                "关闭后只在面板里显示模型逐条上浮的气泡，不显示你自己的弹幕内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = setting.showUserDanmaku,
            onCheckedChange = { onUpdate(setting.copy(showUserDanmaku = it)) },
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title,
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        content()
    }
}

@Composable
private fun SettingChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() }.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
