/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.data.interaction.withoutVideoCallTranscriptText

import me.rerere.rikkahub.data.interaction.withoutJumpControl

import me.rerere.rikkahub.utils.quotedBubbleSelection
import kotlinx.datetime.toJavaLocalDateTime
import java.time.format.DateTimeFormatter

 
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import android.content.Intent
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.PauseCircle
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import kotlin.math.max
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalMaterialMode
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplayMaterialMode
import me.rerere.rikkahub.data.interaction.MessageReaction
import me.rerere.rikkahub.data.interaction.avatarInteraction
import me.rerere.rikkahub.data.interaction.avatarInteractionText
import me.rerere.rikkahub.data.interaction.reactionSegments
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.Image
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.openUrl
import coil3.compose.AsyncImage
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

// ===== 普通气泡真实背景模糊（原型）=====
// 由 ChatPage 通过 CompositionLocal 下发的共享上下文：
// - 共享聊天背景 Painter（仅图片背景时非空）
// - 背景容器窗口原点与像素尺寸
// - 是否允许实时气泡模糊 + 模糊半径 px
@androidx.compose.runtime.Stable
internal class LiveBubbleBlurGeometry {
    var originInWindow by mutableStateOf(Offset.Unspecified)
    var sizePx by mutableStateOf(Size.Unspecified)
}

internal data class LiveBubbleBlurContext(
    val imageBitmap: ImageBitmap? = null,
    val geometry: LiveBubbleBlurGeometry = LiveBubbleBlurGeometry(),
    // 复现页面最终背景所需的视觉参数（与 AssistantBackground 共享同一套数值）
    val baseColor: Color = Color.Unspecified,
    val imageAlpha: Float = 1f,
    val gradientTopAlpha: Float = 0f,
    val gradientBottomAlpha: Float = 0f,
    val enabled: Boolean = false,
    val radiusPx: Float = 0f,
)

internal val LocalLiveBubbleBlur = staticCompositionLocalOf { LiveBubbleBlurContext() }

/**
 * 在气泡背景片段子层 DrawScope 中，按与 AssistantBackground 完全一致的合成顺序重绘背景片段：
 * 1. 页面基础底色；
 * 2. 按背景纸不透明度（imageAlpha）绘制的 Crop + Center 图片；
 * 3. 页面垂直渐变遮罩（以完整背景容器坐标为基准，气泡只裁取对应部分）。
 * 以上全部位于同一 graphicsLayer 内，统一接受 RenderEffect 模糊。
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLiveBackgroundFragment(
    context: LiveBubbleBlurContext,
    layerOriginInWindow: Offset,
) {
    // Geometry changes during navigation/scroll only invalidate drawing, never the message composition.
    val backgroundOriginInWindow = context.geometry.originInWindow
    val backgroundSizePx = context.geometry.sizePx
    val imageBitmap = context.imageBitmap
    val baseColor = context.baseColor
    val imageAlpha = context.imageAlpha
    val gradientTopAlpha = context.gradientTopAlpha
    val gradientBottomAlpha = context.gradientBottomAlpha
    if (layerOriginInWindow == Offset.Unspecified) return
    if (backgroundSizePx.width <= 0f || backgroundSizePx.height <= 0f) return

    val hasBaseColor = baseColor != Color.Unspecified

    // 1. 页面基础底色（页面最终背景的最底层；气泡在背景容器内，直接填满本层即可）
    if (hasBaseColor) {
        drawRect(color = baseColor)
    }

    // 2. 与页面一致的半透明背景图片（使用与 AssistantBackground 相同的 imageAlpha）
    if (imageBitmap != null && imageBitmap.width > 0 && imageBitmap.height > 0) {
        val safeAlpha = imageAlpha.coerceIn(0f, 1f)
        // Crop：源图覆盖完整背景容器所需的统一缩放
        val scale = max(
            backgroundSizePx.width / imageBitmap.width,
            backgroundSizePx.height / imageBitmap.height,
        )
        val drawW = imageBitmap.width * scale
        val drawH = imageBitmap.height * scale
        // Center 对齐后，源图左上角在背景容器中的偏移
        val imgOffInBgX = (backgroundSizePx.width - drawW) / 2f
        val imgOffInBgY = (backgroundSizePx.height - drawH) / 2f
        // 转换为气泡片段层局部坐标 = 背景容器坐标 - 本层窗口原点
        val offX = imgOffInBgX + backgroundOriginInWindow.x - layerOriginInWindow.x
        val offY = imgOffInBgY + backgroundOriginInWindow.y - layerOriginInWindow.y
        // drawImage 是 DrawScope 公开 API：src 取源图全图，dst 为 Crop 缩放后的
        // 目标矩形（尺寸=drawW×drawH，起点=背景容器坐标减去本层窗口原点），
        // 精确复现 ContentScale.Crop + Alignment.Center；alpha 与页面背景纸一致。
        drawImage(
            image = imageBitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(imageBitmap.width, imageBitmap.height),
            dstOffset = IntOffset(offX.toInt(), offY.toInt()),
            dstSize = IntSize(drawW.toInt(), drawH.toInt()),
            alpha = safeAlpha,
        )
    }

    // 3. 页面垂直渐变遮罩：startY/endY 以完整背景容器为基准换算到气泡局部坐标，
    // 气泡只裁取完整页面渐变在当前位置对应的部分，不把渐变重新缩放进每个气泡。
    if (hasBaseColor && (gradientTopAlpha > 0f || gradientBottomAlpha > 0f)) {
        val gradTopY = backgroundOriginInWindow.y - layerOriginInWindow.y
        val gradBottomY = gradTopY + backgroundSizePx.height
        val gradientBrush = Brush.verticalGradient(
            colors = listOf(
                baseColor.copy(alpha = gradientTopAlpha.coerceIn(0f, 1f)),
                baseColor.copy(alpha = gradientBottomAlpha.coerceIn(0f, 1f)),
            ),
            startY = gradTopY,
            endY = gradBottomY,
        )
        drawRect(brush = gradientBrush)
    }
}

@Composable
fun ChatMessage(
    node: MessageNode,
    inlineCards: Map<String, me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction> = emptyMap(),
    conversationId: kotlin.uuid.Uuid? = null,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    reactions: List<MessageReaction> = emptyList(),
    reactionPresets: List<String> = emptyList(),
    onReact: ((String, String) -> Unit)? = null,
    onAvatarInteraction: ((UIMessage) -> Unit)? = null,
    avatarPulse: String? = null,
    onQuote: ((String) -> Unit)? = null,
    hasAssistantResponse: Boolean? = null,
    hasUserRead: Boolean? = null,
    hideVideoCallTranscriptText: Boolean = false,
) {
    val sourceMessage = node.messages[node.selectIndex]
    val renderedMessage = me.rerere.rikkahub.data.ai.transformers.interactionTranscript(sourceMessage, streaming = loading).withoutJumpControl().let { original ->
        if (original.role == MessageRole.USER && original.parts.any {
                it is UIMessagePart.Text && it.text.startsWith("\u2063[orange-watch-auto]")
            }
        ) {
            original.copy(
                parts = original.parts.filterNot {
                    it is UIMessagePart.Text && it.text.startsWith("\u2063[orange-watch-auto]")
                }
            )
        } else {
            original
        }
    }
    val message = if (hideVideoCallTranscriptText) {
        renderedMessage.withoutVideoCallTranscriptText()
    } else renderedMessage
    if (message.parts.isEmptyUIMessage() && !loading &&
        message.annotations.none { it is me.rerere.ai.ui.UIMessageAnnotation.VideoCallGift }
    ) return
    if (message.avatarInteraction() != null) {
        Text(
            text = message.avatarInteractionText(),
            modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val avatarGesture = avatarInteractionModifier(
        avatarPulse,
        onAvatarInteraction?.let { callback -> { callback(message) } },
    )
    val settings = LocalDisplaySettings.current
    val everyday = settings.chatDisplayMode == ChatDisplayMode.EVERYDAY
    val timestamp = remember(message.createdAt, message.role, everyday, hasAssistantResponse, hasUserRead) {
        val time = message.createdAt.toJavaLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        val read = when (message.role) {
            MessageRole.USER -> hasAssistantResponse
            MessageRole.ASSISTANT -> hasUserRead
            else -> null
        }
        if (everyday && read != null) "$time ${if (read) "已读" else "未读"}" else time
    }
    message.annotations.filterIsInstance<me.rerere.ai.ui.UIMessageAnnotation.ChatInteraction>().firstOrNull()?.let { card ->
        Column(modifier.fillMaxWidth()) {
            if (!everyday) {
                if (message.role == MessageRole.ASSISTANT) {
                    ChatMessageAssistantAvatar(
                        message = message, model = model, assistant = assistant, loading = loading,
                        modifier = Modifier.fillMaxWidth(), interactionModifier = avatarGesture,
                    )
                } else if (message.role == MessageRole.USER) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ChatMessageUserAvatar(
                            message = message, avatar = settings.userAvatar, nickname = settings.userNickname,
                            interactionModifier = avatarGesture,
                        )
                    }
                }
            }
            if (everyday) ChatBubbleRow(
                enabled = true,
                isUser = message.role == MessageRole.USER,
                avatar = { ChatBubbleAvatar(message.role == MessageRole.USER, model, assistant, avatarGesture) },
                timestamp = timestamp,
            ) {
                RichChatCard(card, conversationId, readOnly,
                    senderName = if (card.actor == "user") settings.userNickname.ifBlank { "我" }
                        else assistant?.name?.takeIf { it.isNotBlank() } ?: model?.displayName ?: "AI")
            } else {
                RichChatCard(card, conversationId, readOnly,
                    senderName = if (card.actor == "user") settings.userNickname.ifBlank { "我" }
                        else assistant?.name?.takeIf { it.isNotBlank() } ?: model?.displayName ?: "AI")
                val headerShowsTime = settings.showDateBelowName && when (message.role) {
                    MessageRole.USER -> settings.showUserAvatar
                    MessageRole.ASSISTANT -> settings.showModelName && (model != null || assistant?.useAssistantAvatar == true)
                    else -> false
                }
                if (!headerShowsTime) {
                    Text(
                        text = timestamp,
                        modifier = Modifier.align(if (message.role == MessageRole.USER) Alignment.End else Alignment.Start)
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            if (message.role == MessageRole.USER && !everyday) {
                Column(Modifier.align(Alignment.End).padding(top = 4.dp)) {
                    InteractionMessageActions(sourceMessage, node, readOnly, model, onUpdate, onRegenerate,
                        onEdit, onDelete, onShare, onFork, isFavorite, onToggleFavorite)
                }
            }
        }
        return
    }
    val quoteAuthor = if (message.role == MessageRole.USER) settings.userNickname.ifBlank { "我" }
        else assistant?.name?.takeIf { it.isNotBlank() } ?: model?.displayName ?: "AI"
    val quoteSelection = onQuote?.let { callback ->
        { text: String -> callback(quotedBubbleSelection(quoteAuthor, text)) }
    }
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        color = settings.chatTextColor?.let { it.toComposeColor() } ?: Color.Unspecified,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = when (settings.chatFontFamily) {
            ChatFontFamily.DEFAULT -> FontFamily.Default
            ChatFontFamily.SERIF -> FontFamily.Serif
            ChatFontFamily.MONOSPACE -> FontFamily.Monospace
            ChatFontFamily.CUSTOM -> {
                val fontPath = settings.customFontPath
                if (fontPath.isNotBlank() && java.io.File(fontPath).exists()) {
                    FontFamily(Font(java.io.File(fontPath)))
                } else {
                    FontFamily.Default
                }
            }
        }
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showProcessSheet by remember(message.id) { mutableStateOf(false) }
    var showReactionPicker by remember(message.id) { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!everyday && !message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f),
                    interactionModifier = avatarGesture,
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f),
                    interactionModifier = avatarGesture,
                )
            }
        }
        ProvideTextStyle(textStyle) {
            if (everyday && message.role == MessageRole.ASSISTANT &&
                (!hideVideoCallTranscriptText || message.parts.any { it is UIMessagePart.Reasoning || it is UIMessagePart.Tool }) && message.parts.none {
                (it is UIMessagePart.Text && it.text.isNotBlank())
                    || it is UIMessagePart.Image || it is UIMessagePart.Audio
                    || it is UIMessagePart.VoiceMessage || it is UIMessagePart.Video || it is UIMessagePart.Document
            }) {
                ChatBubbleRow(
                    enabled = true,
                    isUser = false,
                    avatar = { ChatBubbleAvatar(false, model, assistant, avatarGesture) },
                    onDoubleClick = { showActionsSheet = true },
                    timestamp = timestamp,
                ) {
                    Text(if (loading) "正在回复…" else "查看思考和工具记录", Modifier.padding(8.dp), color = colorScheme.onSurfaceVariant)
                }
            }
            MessagePartsBlock(
                afterTools = { tools ->
                    tools.mapNotNull { me.rerere.rikkahub.data.interaction.interactionToolId(it) }.distinct().forEach { id ->
                        inlineCards[id]?.let { card ->
                            ChatBubbleRow(everyday, false, avatar = { ChatBubbleAvatar(false, model, assistant, avatarGesture) }, timestamp = timestamp) {
                                RichChatCard(card, conversationId, readOnly, senderName = assistant?.name?.takeIf { it.isNotBlank() } ?: "AI")
                            }
                        }
                    }
                },
                assistant = assistant,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onUserMessageClick = if (!readOnly && message.role == MessageRole.USER) onEdit else null,
                reactions = reactions,
                reactionPresets = reactionPresets,
                onReact = onReact,
                bubbleAvatar = { ChatBubbleAvatar(message.role == MessageRole.USER, model, assistant, avatarGesture) },
                onBubbleLongClick = quoteSelection?.let { quote -> { quote(message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.ifBlank { "[附件消息]" }) } },
                onBubbleDoubleClick = { showActionsSheet = true },
                onBubbleQuote = quoteSelection,
                timestamp = timestamp,
            )
 
            message.annotations.filterIsInstance<me.rerere.ai.ui.UIMessageAnnotation.VideoCallGift>()
                .lastOrNull()?.let { gift ->
                    ChatBubbleRow(
                        enabled = true,
                        isUser = false,
                        avatar = { ChatBubbleAvatar(false, model, assistant, avatarGesture) },
                        timestamp = timestamp,
                    ) {
                        VideoCallGiftCard(gift)
                    }
                }
            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }
 
        if (message.parts.none { it is UIMessagePart.Text }) {
            MessageReactionBar("", reactions, reactionPresets, onReact)
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }
 
        AnimatedVisibility(
            visible = showActions && !everyday,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    readOnly = readOnly,
                    message = sourceMessage,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }
 
        if (everyday && !readOnly && node.messages.size > 1) {
            ChatMessageBranchSelector(node = node, onUpdate = onUpdate)
        }
        if (!everyday) {
            ProvideTextStyle(textStyle) { ChatMessageNerdLine(message = message) }
        }
    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            readOnly = readOnly,
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onReact = onReact?.let { { showReactionPicker = true } },
            onMessageActions = {
                if (everyday) {
                    Column {
                        ChatMessageActionButtons(
                            readOnly = readOnly,
                            message = sourceMessage,
                            node = node,
                            onUpdate = onUpdate,
                            onRegenerate = { showActionsSheet = false; onRegenerate() },
                            onOpenActionSheet = { showActionsSheet = false },
                            onTranslate = onTranslate,
                            onClearTranslation = onClearTranslation,
                            showMoreButton = false,
                        )
                    }
                }
            },
            onShowProcess = if (everyday && message.parts.any {
                it is UIMessagePart.Reasoning || it is UIMessagePart.Tool
            }) ({ showProcessSheet = true }) else null,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showReactionPicker && onReact != null) {
        MessageReactionPicker(
            segments = reactionSegments(message, (assistant ?: Assistant()).let {
                if (everyday) it.copy(splitBubbleByLine = true, splitUserBubbleByLine = true) else it
            }),
            presets = reactionPresets,
            onDismiss = { showReactionPicker = false },
            onSelect = { emoji, quote ->
                onReact(emoji, quote)
                showReactionPicker = false
            },
        )
    }
 
    if (showProcessSheet) {
        ModalBottomSheet(onDismissRequest = { showProcessSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CompositionLocalProvider(LocalDisplaySettings provides settings.copy(
                chatDisplayMode = ChatDisplayMode.STANDARD,
                showThinkingContent = true,
            )) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("思考和工具记录", style = MaterialTheme.typography.titleMedium)
                    MessagePartsBlock(
                        assistant = assistant,
                        role = message.role,
                        model = model,
                        parts = message.parts.filter { it is UIMessagePart.Reasoning || it is UIMessagePart.Tool },
                        annotations = emptyList(),
                        loading = loading,
                        onToolApproval = onToolApproval,
                        onToolAnswer = onToolAnswer,
                    )
                }
            }
        }
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}
 
@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    reactions: List<MessageReaction> = emptyList(),
    reactionPresets: List<String> = emptyList(),
    onReact: ((String, String) -> Unit)? = null,
    bubbleAvatar: @Composable () -> Unit = {},
    onBubbleLongClick: (() -> Unit)? = null,
    onBubbleDoubleClick: (() -> Unit)? = null,
    onBubbleQuote: ((String) -> Unit)? = null,
    timestamp: String? = null,
    afterTools: @Composable (List<UIMessagePart.Tool>) -> Unit = {},
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
 
    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val displaySettings = LocalDisplaySettings.current
    val everyday = displaySettings.chatDisplayMode == ChatDisplayMode.EVERYDAY
    val partsState by rememberUpdatedState(parts)
 
    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(displaySettings) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && displaySettings.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }
 
    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                val visibleSteps = block.steps.forChatDisplay(displaySettings.chatDisplayMode)
                if (visibleSteps.isNotEmpty()) {
                    val isReasoningOnlyBlock = visibleSteps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = visibleSteps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }
 
                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        allParts = parts,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                    )
                                }
                            }
                        }
                    }
                }
                afterTools(block.steps.filterIsInstance<ThinkingStep.ToolStep>().map { it.tool })
            }
 
            is MessagePartBlock.ChartBlock -> key("chart", block.index) {
                me.rerere.rikkahub.ui.components.charts.ChatChartCard(block.spec)
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                ChatBubbleRow(
                    enabled = everyday && block.part !is UIMessagePart.Text,
                    isUser = role == MessageRole.USER,
                    avatar = bubbleAvatar,
                    onLongClick = onBubbleLongClick, onDoubleClick = onBubbleDoubleClick,
                    timestamp = timestamp,
                ) {
                    when (val part = block.part) {
                        is UIMessagePart.Text -> {
                            // 从显示文本中移除[zip:...]标记
                            val displayText = remember(part.text) {
                                part.text.replace(Regex("\\[zip:[^\\]]+\\]", RegexOption.IGNORE_CASE), "")
                            }

                            ChatMessageTextBubbles(
                                text = displayText, role = role, assistant = assistant,
                                reactions = reactions, reactionPresets = reactionPresets, onReact = onReact,
                                onUserMessageClick = onUserMessageClick, onClickCitation = handleClickCitation,
                                avatar = bubbleAvatar, onLongClick = onBubbleLongClick, onDoubleClick = onBubbleDoubleClick, onQuote = onBubbleQuote, timestamp = timestamp,
                            )
                        }

                        is UIMessagePart.Video -> {
                            Surface(
                                tonalElevation = 2.dp,
                                modifier = Modifier.combinedClickable(
                                    onLongClick = if (everyday) onBubbleLongClick else null,
                                    onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW)
                                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        intent.data = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            part.url.toUri().toFile()
                                        )
                                        val chooserIndent = Intent.createChooser(intent, null)
                                        context.startActivity(chooserIndent)
                                    },
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                    Icon(HugeIcons.Video01, null)
                                }
                            }
                        }

                        is UIMessagePart.Audio -> {
                            AudioPlayerBubble(url = part.url, onLongClick = if (everyday) onBubbleLongClick else null, onDoubleClick = if (everyday) onBubbleDoubleClick else null)
                        }

                        is UIMessagePart.VoiceMessage -> {
                            VoiceMessageBubble(
                                voiceMessage = part,
                                isUser = role == MessageRole.USER,
                                onLongClick = if (everyday) onBubbleLongClick else null,
                                    onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                            )
                        }

                        is UIMessagePart.Image -> {
                            val isImageLoading =
                                part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                            if (isImageLoading) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .shimmer(isLoading = true)
                                )
                            } else {
                                ZoomableAsyncImage(
                                    model = part.url,
                                    contentDescription = null,
                                    onLongClick = if (everyday) onBubbleLongClick else null,
                                    onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .height(72.dp)
                                )
                            }
                        }

                        is UIMessagePart.Document -> {
                            Surface(
                                tonalElevation = 2.dp,
                                modifier = Modifier.combinedClickable(
                                    onLongClick = if (everyday) onBubbleLongClick else null,
                                    onDoubleClick = if (everyday) onBubbleDoubleClick else null,
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW)
                                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        intent.data = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            part.url.toUri().toFile()
                                        )
                                        val chooserIndent = Intent.createChooser(intent, null)
                                        context.startActivity(chooserIndent)
                                    },
                                ),
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        when (part.mime) {
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                                Icon(
                                                    painter = painterResource(R.drawable.docx),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            "application/pdf" -> {
                                                Icon(
                                                    painter = painterResource(R.drawable.pdf),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            else -> {
                                                Icon(
                                                    imageVector = HugeIcons.File02,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = part.fileName,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 200.dp)
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                        }
                    }
                }
            }
        }
    }
 
    // Annotations (always rendered at the end)
    val citations = annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>()
    if (citations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        citations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, citations.size))
            }
        }
    }
 
    // 工作区文件 chip: assistant 消息下方展示被 workspace_write_file/
    // workspace_edit_file 写入/编辑的文件, 点击可导出/分享。
    // 仅在归属工作区的 assistant 消息中渲染, 不影响用户消息和其它布局。
    if (role == MessageRole.ASSISTANT) {
        EditedFilesList(parts = parts, assistant = assistant)
    }
}
 
@Composable
internal fun BubbleSurface(
    imagePath: String,
    cornerRadius: Dp,
    color: Color,
    overlayEnabled: Boolean,
    bubbleAlpha: Float,
    onClick: (() -> Unit)? = null,
    // 本轮原型：用户与助手的普通文本气泡传 true（最终由 LiveBubbleBlurContext 与 final 条件决定）
    enableLiveBubbleBlur: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val materialMode = LocalMaterialMode.current
    val liveContext = LocalLiveBubbleBlur.current
    val liveEnabled =
        enableLiveBubbleBlur &&
            liveContext.enabled &&
            liveContext.imageBitmap != null &&
            materialMode == DisplayMaterialMode.GLASS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            liveContext.radiusPx > 0f
    val cachedBlurEffect = remember(liveEnabled, liveContext.radiusPx) {
        if (liveEnabled) {
            AndroidRenderEffect.createBlurEffect(
                liveContext.radiusPx,
                liveContext.radiusPx,
                Shader.TileMode.CLAMP,
            ).asComposeRenderEffect()
        } else {
            null
        }
    }
    var liveLayerOriginInWindow by remember { mutableStateOf(Offset.Unspecified) }
    // 最终条件：仅此条件为 true 时才追加背景片段层（当前已验证 FINAL=1）
    val finalLiveBubbleBlurEnabled = liveEnabled && cachedBlurEffect != null
    // 注意：本体不包含 fillMaxSize/fillMaxWidth 等参与父级测量的尺寸 modifier，
    // 尺寸由调用点的 matchParentSize() 决定（只覆盖父 Box 已有尺寸，不参与父级测量）。
    val liveFragmentModifier = if (finalLiveBubbleBlurEnabled) {
        Modifier
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                if (origin != liveLayerOriginInWindow) {
                    liveLayerOriginInWindow = origin
                }
            }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                renderEffect = cachedBlurEffect
            }
            .drawBehind {
                // 页面背景完整合成作为该 graphicsLayer 节点的直接内容：
                // 底色 → 半透明图片 → 页面渐变遮罩，统一接受 RenderEffect 模糊
                drawLiveBackgroundFragment(
                    context = liveContext,
                    layerOriginInWindow = liveLayerOriginInWindow,
                )
            }
    } else {
        Modifier
    }
    val effectiveAlpha = when (materialMode) {
        DisplayMaterialMode.TRANSLUCENT -> TRANSLUCENT_BUBBLE_BASE_ALPHA * bubbleAlpha
        DisplayMaterialMode.GLASS -> bubbleAlpha
        DisplayMaterialMode.FOLLOW_THEME,
        DisplayMaterialMode.FLAT -> bubbleAlpha
    }
    val glassBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = GLASS_BUBBLE_BORDER_ALPHA)
    val translucentBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRANSLUCENT_BUBBLE_BORDER_ALPHA)
    val glassFillModifier = Modifier.drawWithCache {
        val gradientCenter = Offset(size.width / 2f, size.height / 2f)
        val glassFillBrush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = 0.82f * bubbleAlpha),
                0.55f to color.copy(alpha = 0.80f * bubbleAlpha),
                0.82f to color.copy(alpha = 0.56f * bubbleAlpha),
                1f to color.copy(alpha = 0.40f * bubbleAlpha),
            ),
            center = gradientCenter,
            radius = gradientCenter.getDistance(),
        )
        onDrawBehind {
            drawRect(glassFillBrush)
        }
    }
    val glassHighlightModifier = Modifier.drawWithCache {
        val topHighlightDepth = 6.dp.toPx()
        val bottomHighlightDepth = 4.dp.toPx()
        val specularHighlightTop = 1.dp.toPx()
        val specularHighlightHeight = 1.dp.toPx()
        val glassTopHighlightBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.22f),
                0.5f to Color.White.copy(alpha = 0.08f),
                1f to Color.Transparent,
            ),
            endY = topHighlightDepth,
        )
        val glassBottomHighlightBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.05f),
                1f to Color.White.copy(alpha = 0.12f),
            ),
            startY = size.height - bottomHighlightDepth,
            endY = size.height,
        )
        val glassSpecularHighlightBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.10f to Color.White.copy(alpha = 0.14f),
                0.28f to Color.White.copy(alpha = 0.42f),
                0.52f to Color.White.copy(alpha = 0.24f),
                0.78f to Color.White.copy(alpha = 0.08f),
                1f to Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(size.width, 0f),
        )
        onDrawBehind {
            drawRect(
                brush = glassTopHighlightBrush,
                size = Size(size.width, minOf(size.height, topHighlightDepth)),
            )
            drawRect(
                brush = glassBottomHighlightBrush,
                topLeft = Offset(0f, maxOf(0f, size.height - bottomHighlightDepth)),
                size = Size(size.width, minOf(size.height, bottomHighlightDepth)),
            )
            drawRect(
                brush = glassSpecularHighlightBrush,
                topLeft = Offset(0f, specularHighlightTop),
                size = Size(
                    width = size.width,
                    height = minOf(specularHighlightHeight, size.height - specularHighlightTop),
                ),
            )
        }
    }
    // 昼夜状态：与应用实际 colorScheme 一致（SYSTEM→系统；LIGHT/DARK 手动覆盖）
    val isDarkTheme = LocalDarkMode.current
    // 实时模糊气泡专用：白色径向渐变高光遮罩（左上亮、向右下衰减；昼夜参数不同）
    val liveBubbleRadialHighlightModifier = Modifier.drawWithCache {
        val highlightCenter = if (isDarkTheme) {
            Offset(size.width * 0.20f, size.height * 0.15f)
        } else {
            Offset(size.width * 0.18f, size.height * 0.12f)
        }
        val highlightRadius = maxOf(size.width, size.height) * 0.95f
        val highlightBrush = Brush.radialGradient(
            colorStops = if (isDarkTheme) {
                arrayOf(
                    0f to Color.White.copy(alpha = 0.06f),
                    0.45f to Color.White.copy(alpha = 0.02f),
                    1f to Color.Transparent,
                )
            } else {
                arrayOf(
                    0f to Color.White.copy(alpha = 0.18f),
                    0.42f to Color.White.copy(alpha = 0.07f),
                    1f to Color.Transparent,
                )
            },
            center = highlightCenter,
            radius = highlightRadius,
        )
        onDrawBehind {
            drawRect(brush = highlightBrush)
        }
    }
    // 实时模糊气泡专用（仅夜间）：深色方向性识读遮罩，防止亮色背景导致气泡泛白、白字不可读
    val liveBubbleNightReadabilityModifier = Modifier.drawWithCache {
        val readabilityBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Black.copy(alpha = 0.06f),
                0.55f to Color.Black.copy(alpha = 0.09f),
                1f to Color.Black.copy(alpha = 0.12f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawBehind {
            drawRect(brush = readabilityBrush)
        }
    }
    // 实时模糊气泡专用：沿真实圆角轮廓贴边的方向性硬高光（左上亮、向右下透明；昼夜强弱不同）
    val liveBubbleEdgeHighlightModifier = Modifier.drawWithCache {
        val strokeWidthPx = 1.dp.toPx()
        val halfStroke = strokeWidthPx / 2f
        val adjustedRadius = maxOf(0f, cornerRadius.toPx() - halfStroke)
        val edgeStartAlpha = if (isDarkTheme) 0.38f else 0.78f
        val edgeMidAlpha = if (isDarkTheme) 0.133f else 0.273f
        val edgeBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = edgeStartAlpha),
                0.45f to Color.White.copy(alpha = edgeMidAlpha),
                0.75f to Color.Transparent,
                1f to Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawBehind {
            if (size.width > 0f && size.height > 0f) {
                drawRoundRect(
                    brush = edgeBrush,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = Size(
                        width = size.width - strokeWidthPx,
                        height = size.height - strokeWidthPx,
                    ),
                    cornerRadius = CornerRadius(adjustedRadius, adjustedRadius),
                    style = Stroke(width = strokeWidthPx),
                )
            }
        }
    }
    val shape = RoundedCornerShape(cornerRadius)
    val clickModifier = when {
        onLongClick != null || onDoubleClick != null -> Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    val hasImage = imagePath.isNotBlank() && java.io.File(imagePath).exists()
    if (materialMode == DisplayMaterialMode.GLASS) {
        Box(
            modifier = Modifier
                .animateContentSize()
                .clip(shape)
                .then(clickModifier)
                .border(1.dp, glassBorderColor, shape)
        ) {
            if (finalLiveBubbleBlurEnabled) {
                // 背景图片片段层：仅模糊此子层，文字等前景内容保持清晰。
                // matchParentSize 只覆盖父 Box 已确定尺寸，不参与父 Box 测量，
                // 因此气泡宽度仍由文字内容 + padding + 宽度上限决定。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(liveFragmentModifier)
                )
            }
            if (hasImage) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            if (finalLiveBubbleBlurEnabled && isDarkTheme) {
                // 夜间深色识读遮罩：位于模糊背景之上、glass fill 之下，压暗亮背景保证白字可读
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(liveBubbleNightReadabilityModifier)
                )
            }
            if (!hasImage || overlayEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(glassFillModifier)
                )
            }
            if (finalLiveBubbleBlurEnabled) {
                // 白色径向渐变高光遮罩：位于 glass fill 上方、玻璃高光下方，受气泡圆角裁剪
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(liveBubbleRadialHighlightModifier)
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(glassHighlightModifier)
            )
            if (finalLiveBubbleBlurEnabled) {
                // 沿真实圆角贴边的方向性硬高光：位于渲染层外、content 之下；仅左上/顶部可见，向右下透明
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(liveBubbleEdgeHighlightModifier)
                )
            }
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    } else if (hasImage) {
        Box(
            modifier = Modifier
                .animateContentSize()
                .clip(shape)
                .then(clickModifier)
                .then(
                    if (materialMode == DisplayMaterialMode.TRANSLUCENT) {
                        Modifier.border(1.dp, translucentBorderColor, shape)
                    } else {
                        Modifier
                    }
                )
        ) {
            AsyncImage(
                model = imagePath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            if (overlayEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(color.copy(alpha = effectiveAlpha))
                )
            }
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    } else {
        Surface(
            modifier = Modifier.animateContentSize().then(clickModifier),
            shape = shape,
            color = color.copy(alpha = effectiveAlpha),
            border = if (materialMode == DisplayMaterialMode.TRANSLUCENT) {
                BorderStroke(1.dp, translucentBorderColor)
            } else {
                null
            },
        ) {
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    }
}

private const val TRANSLUCENT_BUBBLE_BASE_ALPHA = 0.72f
private const val TRANSLUCENT_BUBBLE_BORDER_ALPHA = 0.18f
private const val GLASS_BUBBLE_BORDER_ALPHA = 0.24f
 
@Composable
@Suppress("UnusedCrossTarget")
internal fun AudioPlayerBubble(url: String, onLongClick: (() -> Unit)? = null, onDoubleClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentMs by remember { mutableIntStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
 
    // Generate pseudo-random waveform bar heights (deterministic per url)
    val waveformBars = remember(url) {
        val rnd = java.util.Random(url.hashCode().toLong())
        List(40) { 0.15f + rnd.nextFloat() * 0.85f }
    }
 
    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs else 0f
 
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
 
    // Progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    currentMs = it.currentPosition
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }
 
    // Animate waveform bars when playing
    val animatedBars = remember { mutableStateOf(waveformBars) }
    LaunchedEffect(isPlaying, progress) {
        if (isPlaying) {
            val rnd = java.util.Random()
            val newBars = waveformBars.mapIndexed { index, base ->
                val playedRatio = if (progress > 0f) index.toFloat() / waveformBars.size else 0f
                if (playedRatio <= progress) {
                    // Already played bars stay at original height
                    base
                } else {
                    // Upcoming bars get slight animation
                    base * (0.85f + rnd.nextFloat() * 0.3f)
                }
            }
            animatedBars.value = newBars
        } else {
            animatedBars.value = waveformBars
        }
    }
 
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
 
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Play / Pause button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .combinedClickable(onLongClick = onLongClick, onDoubleClick = onDoubleClick, onClick = {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        if (mediaPlayer == null || !isPrepared) {
                            val mp = MediaPlayer()
                            try {
                                val uri = android.net.Uri.parse(url)
                                mp.setDataSource(context, uri)
                                mp.prepare()
                                durationMs = mp.duration
                                mp.setOnCompletionListener {
                                    isPlaying = false
                                    currentMs = 0
                                }
                                mp.start()
                                isPlaying = true
                                isPrepared = true
                                mediaPlayer = mp
                            } catch (e: Exception) {
                                mp.release()
                            }
                        } else {
                            mediaPlayer?.start()
                            isPlaying = true
                        }
                    }
                }),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
 
        Spacer(modifier = Modifier.width(8.dp))
 
        // Waveform bars
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
        ) {
            val barCount = animatedBars.value.size
            val totalWidth = size.width
            val barWidth = 2.5f
            val gap = (totalWidth - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
            val playedBarCount = (progress * barCount).toInt()
 
            animatedBars.value.forEachIndexed { index, barRatio ->
                val barHeight = size.height * barRatio.coerceIn(0.15f, 1f)
                val x = index * (barWidth + gap)
                val y = (size.height - barHeight) / 2f
                drawRoundRect(
                    color = if (index < playedBarCount) activeColor else inactiveColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f)
                )
            }
        }
 
        Spacer(modifier = Modifier.width(6.dp))
 
        // Duration text
        val displaySec = if (isPlaying || currentMs > 0) {
            val remaining = (durationMs - currentMs) / 1000
            remaining.coerceAtLeast(0)
        } else {
            durationMs / 1000
        }
        Text(
            text = String.format("%d:%02d", displaySec / 60, displaySec % 60),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 13.sp,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}
 
@Composable
internal fun VoiceMessageBubble(
    voiceMessage: UIMessagePart.VoiceMessage,
    isUser: Boolean,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
 
    val voiceColors = interactionColors(if (isUser) "sakura" else "ice")
    val durationSec = (voiceMessage.duration / 1000).coerceAtLeast(1)
 
    DisposableEffect(voiceMessage.url) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
 
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    isPlaying = false
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }
 
    Surface(
        modifier = Modifier.combinedClickable(onLongClick = onLongClick, onDoubleClick = onDoubleClick, onClick = {
            if (isPlaying) {
                mediaPlayer?.let {
                    it.stop()
                    it.reset()
                }
                isPlaying = false
            } else {
                try {
                    val mp = MediaPlayer()
                    mp.setDataSource(voiceMessage.url)
                    mp.prepare()
                    mp.setOnCompletionListener {
                        isPlaying = false
                    }
                    mp.start()
                    isPlaying = true
                    mediaPlayer?.release()
                    mediaPlayer = mp
                } catch (e: Exception) {
                    // File might not exist
                }
            }
        }),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .85f)),
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.background(Brush.linearGradient(listOf(voiceColors.top.copy(alpha = .88f), voiceColors.bottom.copy(alpha = .70f)))).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) HugeIcons.PauseCircle else HugeIcons.PlayCircle,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = voiceColors.ink,
                    modifier = Modifier.size(24.dp)
                )
                // Waveform bars
                val waveformBars = remember(voiceMessage.url) {
                    val rnd = java.util.Random(voiceMessage.url.hashCode().toLong())
                    List(24) { 0.2f + rnd.nextFloat() * 0.8f }
                }
                val waveformColor = voiceColors.accent
                Canvas(modifier = Modifier.width(112.dp).height(24.dp)) {
                    val barCount = waveformBars.size
                    val barWidth = 2.5f
                    val gap = (size.width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
                    waveformBars.forEachIndexed { index, barRatio ->
                        val barHeight = size.height * barRatio.coerceIn(0.2f, 1f)
                        val x = index * (barWidth + gap)
                        val y = (size.height - barHeight) / 2f
                        drawRoundRect(
                            color = waveformColor,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f)
                        )
                    }
                }
                Text(
                    text = "${durationSec}″",
                    style = MaterialTheme.typography.labelMedium,
                    color = voiceColors.ink,
                )
            }
            // Show transcript text below the voice bubble (like WeChat)
            if (voiceMessage.transcript.isNotBlank()) {
                Text(
                    text = voiceMessage.transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = voiceColors.ink.copy(alpha = .8f),
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
