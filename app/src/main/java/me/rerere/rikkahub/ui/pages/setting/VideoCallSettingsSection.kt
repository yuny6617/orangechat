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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.video.VIDEO_GIFT_EFFECT_DURATION_MS
import me.rerere.rikkahub.data.video.VideoCallPrompts
import me.rerere.rikkahub.data.video.VideoCallSetting
import me.rerere.rikkahub.data.video.VideoFrameCaptureMode
import me.rerere.rikkahub.data.video.VideoFrameQuality
import me.rerere.rikkahub.data.video.VideoGift
import me.rerere.rikkahub.data.video.VideoGiftCatalog
import me.rerere.rikkahub.data.video.VideoGiftCustomization
import me.rerere.rikkahub.data.video.VideoGiftEffect
import me.rerere.rikkahub.data.video.VideoGiftEffectRequest
import me.rerere.rikkahub.data.video.VideoVoiceInputMode
import me.rerere.rikkahub.data.video.isFullScreen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.video.VIDEO_GIFT_BUBBLE_PREVIEW_MS
import me.rerere.rikkahub.ui.pages.video.VideoGiftBubblePreview
import me.rerere.rikkahub.ui.pages.video.VideoGiftBubblePreviewState
import me.rerere.rikkahub.ui.pages.video.VideoGiftEffectOverlay
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LargeFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults

/**
 * 语音页里的视频通话入口。
 *
 * 这里**只留一行摘要**，真正的设置项全部搬进 [VideoCallSettingsScreen]。
 * 原因（真机反馈）：原来是把整段视频设置内联展开在语音页的非滚动 `Column` 顶部，
 * 它越长、下面的 TTS/ASR 列表就被 `weight(1f)` 挤得越扁，最后整页既划不动也点不到
 * 最底下的「自动抓帧/抓帧画质/显示我打的字」。把折叠内容放进自己有滚动的页面，
 * 展开多少都不会再影响语音页原来的两个列表。
 */
@Composable
internal fun VideoCallSettingsSection(
    setting: VideoCallSetting,
    onOpen: () -> Unit,
) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
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
            "设置 ›",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/**
 * 视频通话设置的独立页面（自己有滚动）。
 *
 * 验收要求：**展开后所有项都可达**，底栏/键盘不挡。所以这里是
 * `Scaffold + verticalScroll + imePadding`，而不是把内容塞进 parent 的固定高度里。
 */
@Composable
internal fun VideoCallSettingsScreen(
    setting: VideoCallSetting,
    onUpdate: (VideoCallSetting) -> Unit,
    onBack: () -> Unit,
) {
    var showGiftPrompt by remember { mutableStateOf(false) }
    // 本地预览：只在本页播一次动画。不伪造模型送过礼、不进历史、不计数、不请求模型。
    var previewEffect by remember { mutableStateOf<VideoGiftEffectRequest?>(null) }
    // 心心/花束这类"只出气泡"的礼物也要有可见预览（全屏 overlay 对它们是空操作）。
    var bubblePreview by remember { mutableStateOf<VideoGiftBubblePreviewState?>(null) }
    // 礼物名字/文案的**草稿**：只有点"保存"才写回设置，点"取消"丢弃。
    var draftNames by remember(setting.giftDisplayNames) {
        mutableStateOf(VideoGiftCustomization.sanitizedNames(setting))
    }
    var draftMessage by remember(setting.giftMessage) {
        mutableStateOf(VideoGiftCustomization.sanitizedMessage(setting))
    }
    var draftPrompt by remember(setting.giftPrompt) {
        mutableStateOf(VideoCallPrompts.effectiveGiftPrompt(setting))
    }
    var savedNotice by remember { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(previewEffect) {
        val current = previewEffect ?: return@LaunchedEffect
        delay(VIDEO_GIFT_EFFECT_DURATION_MS + 600L)
        if (previewEffect?.key == current.key) previewEffect = null
    }
    LaunchedEffect(bubblePreview) {
        val current = bubblePreview ?: return@LaunchedEffect
        delay(VIDEO_GIFT_BUBBLE_PREVIEW_MS + 400L)
        if (bubblePreview?.key == current.key) bubblePreview = null
    }
    LaunchedEffect(savedNotice) {
        val current = savedNotice ?: return@LaunchedEffect
        delay(2_000L)
        if (savedNotice == current) savedNotice = null
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("视频通话") },
                    navigationIcon = { BackButton() },
                    colors = CustomColors.topBarColors,
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = settingsScaffoldContainerColor(CustomColors.topBarColors.containerColor),
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                Text(
                    "独立入口：聊天输入框右侧的 + → 视频通话。保留原来的语音通话入口，两者共用同一通电话。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SettingGroup(title = "基本视频设置 · 我说一句") {
                    VideoVoiceInputMode.entries.forEach { mode ->
                        SettingChoiceRow(mode.displayName(), mode.subtitle(), setting.voiceInputMode == mode) {
                            onUpdate(setting.copy(voiceInputMode = mode))
                        }
                    }
                }
                SettingGroup(title = "自动抓帧") {
                    VideoFrameCaptureMode.entries.forEach { mode ->
                        SettingChoiceRow(
                            mode.displayName,
                            if (mode == VideoFrameCaptureMode.OFF) "不自动附带画面" else "仅随下一轮请求发送画面",
                            setting.frameCaptureMode == mode,
                        ) { onUpdate(setting.copy(frameCaptureMode = mode)) }
                    }
                }
                SettingGroup(title = "抓帧画质") {
                    VideoFrameQuality.entries.forEach { quality ->
                        SettingChoiceRow(quality.displayName, "JPEG 按最长边等比缩放", setting.frameQuality == quality) {
                            onUpdate(setting.copy(frameQuality = quality))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("显示我打的字", Modifier.weight(1f))
                    Switch(setting.showUserDanmaku, { onUpdate(setting.copy(showUserDanmaku = it)) })
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("虚拟礼物", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("视频通话礼物", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "默认关闭。开启后模型可以直接在回复正文里写 <ws_gift id=\"...\"/> 送出纯虚拟礼物，" +
                                "触发通话内气泡与全屏动画；礼物没有真实金额、不扣余额、不调用钱包。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "这个标签是回复正文的一部分，不是工具调用：不需要 tool schema，也不需要任何人工审定。" +
                                "开关只决定这一通电话里允不允许送。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = setting.giftsEnabled,
                        onCheckedChange = { onUpdate(setting.copy(giftsEnabled = it)) },
                    )
                }

                GiftPreviewGroup(
                    nameOf = { gift -> VideoGiftCustomization.nameOf(draftNames, gift) },
                    onPreview = { gift ->
                        // 高档礼物：全屏原创 Canvas 动画。
                        previewEffect = VideoGiftEffectRequest(
                            key = "preview-${gift.id}-${System.nanoTime()}",
                            eventId = "preview",
                            gift = gift,
                        )
                        // 低档礼物：只有 Emoji 气泡，单独给一个可见的气泡预览。
                        bubblePreview = if (gift.isFullScreen) null else VideoGiftBubblePreviewState(
                            key = "preview-bubble-${gift.id}-${System.nanoTime()}",
                            gift = gift,
                            label = VideoGiftCustomization.nameOf(draftNames, gift),
                        )
                    },
                )

                SettingGroup(title = "礼物名字与赠礼文案") {
                    Text(
                        "这里可改 5 件礼物的显示名和可选文案偏好；下方另可编辑完整送礼提示词。礼物 id、特效和「每条最多一件」" +
                            "这些规则由程序固定，改不了。名字最长 ${VideoGiftCustomization.MAX_DISPLAY_NAME_LENGTH} 字，" +
                            "文案最长 ${VideoGiftCustomization.MAX_GIFT_MESSAGE_LENGTH} 字。",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VideoGiftCatalog.all.forEach { gift ->
                        GiftNameField(
                            gift = gift,
                            value = draftNames[gift.id].orEmpty(),
                            placeholder = gift.displayName,
                            onValueChange = { raw ->
                                val clean = VideoGiftCustomization.sanitizeDisplayName(raw)
                                draftNames = if (clean.isEmpty()) draftNames - gift.id
                                else draftNames + (gift.id to clean)
                            },
                        )
                    }
                    GiftMessageField(
                        value = draftMessage,
                        onValueChange = { draftMessage = VideoGiftCustomization.sanitizeGiftMessage(it) },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                onUpdate(
                                    VideoGiftCustomization.withDisplayNames(setting, draftNames, draftMessage),
                                )
                                savedNotice = "已保存。之后的通话与提示词按新名字生效，历史记录不会回改。"
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text("保存") }
                        OutlinedButton(
                            onClick = {
                                draftNames = VideoGiftCustomization.sanitizedNames(setting)
                                draftMessage = VideoGiftCustomization.sanitizedMessage(setting)
                                savedNotice = "已取消，恢复成上次保存的内容。"
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text("取消") }
                        OutlinedButton(
                            onClick = {
                                onUpdate(VideoGiftCustomization.withDefaults(setting))
                                draftNames = emptyMap()
                                draftMessage = ""
                                savedNotice = "已恢复默认名字与空文案。"
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text("恢复默认") }
                    }
                    if (savedNotice != null) {
                        Text(
                            savedNotice!!,
                            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                SettingGroup(title = "完整送礼提示词") {
                    Text(
                        "下面直接显示完整默认文本，可任意编辑并保存；留空使用默认全文。" +
                            "固定 id、标签格式与每轮上限仍由程序验证，保存后下一轮请求生效。",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = draftPrompt,
                        onValueChange = { draftPrompt = it },
                        minLines = 8,
                        maxLines = 16,
                        label = { Text("完整提示词（不是单句措辞偏好）") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = {
                            onUpdate(setting.copy(giftPrompt = draftPrompt.takeIf { it.isNotBlank() }.orEmpty()))
                            savedNotice = "完整提示词已保存，下一轮请求生效。"
                        }) { Text("保存提示词") }
                        OutlinedButton(onClick = {
                            draftPrompt = VideoCallPrompts.effectiveGiftPrompt(setting)
                        }) { Text("取消") }
                        OutlinedButton(onClick = {
                            onUpdate(setting.copy(giftPrompt = ""))
                            draftPrompt = VideoCallPrompts.effectiveGiftPrompt(setting.copy(giftPrompt = ""))
                            savedNotice = "已恢复默认完整提示词。"
                        }) { Text("恢复默认") }
                    }
                    Text(
                        VideoCallPrompts.giftProtocolBoundary(VideoGiftCustomization.sanitizedNames(setting)),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text(
                    text = if (showGiftPrompt) "收起送礼提示词" else "查看送礼提示词（模型实际收到的原文）",
                    modifier = Modifier.padding(horizontal = 16.dp).clickable { showGiftPrompt = !showGiftPrompt }
                        .padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (showGiftPrompt) {
                    Text(
                        // 这里显示的是**已保存**配置生成的真实提示词（草稿要点保存才会生效）。
                        text = if (setting.giftsEnabled) {
                            VideoCallPrompts.effectiveGiftPrompt(setting) + "\n\n" +
                                VideoCallPrompts.giftProtocolBoundary(VideoGiftCustomization.sanitizedNames(setting))
                        } else {
                            VideoCallPrompts.GIFT_DISABLED_NOTE
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp,
                    )
                }

                // 底部留白：最后一项不会被系统手势条/键盘吃掉。
                Spacer(Modifier.height(32.dp))
            }
        }
        // 预览只提供显式跳过按钮，其余区域不拦截设置页操作；旧 key 不能跳过新的预览。
        VideoGiftEffectOverlay(previewEffect, Modifier.fillMaxSize(), onSkip = { key ->
            if (previewEffect?.key == key) previewEffect = null
        })
        VideoGiftBubblePreview(bubblePreview, Modifier.fillMaxSize())
    }
}

/** 单个礼物的展示名字段（只改显示名，id 与特效固定）。 */
@Composable
private fun GiftNameField(
    gift: VideoGift,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(gift.emoji, fontSize = 18.sp)
        Column(Modifier.weight(1f)) {
            Text("${gift.displayName} · ${gift.id}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                placeholder = { Text(placeholder) },
                supportingText = {
                    Text("${value.length}/${VideoGiftCustomization.MAX_DISPLAY_NAME_LENGTH}")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 赠礼文案偏好输入框（只是措辞，不改变协议规则）。 */
@Composable
private fun GiftMessageField(value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("赠礼文案偏好（可选）", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            minLines = 2,
            maxLines = 4,
            placeholder = { Text("例如：送礼的时候说一句甜甜的话，不要长篇大论") },
            supportingText = {
                Text("只是措辞偏好，不能改变规则（每条最多一件、只用合法 id、没有真实金额）。" +
                    "${value.length}/${VideoGiftCustomization.MAX_GIFT_MESSAGE_LENGTH}")
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 礼物清单 + 预览。
 *
 * 事实核对（写进 UI，免得再被当成"外部素材"）：`heart`/`bouquet` 是 Emoji 气泡，
 * `fireworks`/`meteor`/`galaxy_train` 是本项目原创的 Compose Canvas 动画，没有外部贴图。
 */
@Composable
private fun GiftPreviewGroup(nameOf: (VideoGift) -> String, onPreview: (VideoGift) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            "礼物与特效预览",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        VideoGiftCatalog.all.forEach { gift ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(gift.emoji, fontSize = 20.sp)
                Column(Modifier.weight(1f)) {
                    Text("${nameOf(gift)} · ${gift.id}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        effectDescription(gift.effect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { onPreview(gift) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "预览",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text(
            "素材说明：小心心、花束是 Emoji 气泡；夏日烟火、流星雨、银河铁道之夜是本项目**原创 Compose Canvas 动画**。" +
                "整套礼物不依赖任何外部素材包/贴图（也没有从别处复制别人的特效代码）。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "预览只是在本页播一次动画：不会让模型以为送过礼，不进聊天记录、不计入礼物次数，也不会请求模型。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun effectDescription(effect: VideoGiftEffect): String = when (effect) {
    VideoGiftEffect.Bubble -> "Emoji 气泡（小尺寸，不挡画面）"
    VideoGiftEffect.Fireworks -> "全屏原创 Canvas 动画：夏日烟火"
    VideoGiftEffect.MeteorShower -> "全屏原创 Canvas 动画：流星雨"
    VideoGiftEffect.GalaxyTrain -> "全屏原创 Canvas 动画：银河铁道之夜"
}

private fun VideoVoiceInputMode.displayName(): String = when (this) {
    VideoVoiceInputMode.AUTO -> "自动"
    VideoVoiceInputMode.DIRECT -> "直接发音频给模型"
    VideoVoiceInputMode.ASR -> "语音转文字"
}

private fun VideoVoiceInputMode.subtitle(): String = when (this) {
    VideoVoiceInputMode.AUTO ->
        "能直接听音频的模型/通道就直传，否则用已配置的语音识别；两者都没有时按钮会明确禁用"

    VideoVoiceInputMode.DIRECT ->
        "录音作为一段 WAV 随这一次请求发出（只发这一轮，之后不保留）；模型不支持音频时会禁用并说明原因"

    VideoVoiceInputMode.ASR ->
        "只把转写文字发给模型，不传音频；没有配置 ASR 时按钮会禁用并说明原因，打字仍然可用"
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
