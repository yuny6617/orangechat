package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import me.rerere.rikkahub.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.interaction.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.createChatInteraction
import me.rerere.rikkahub.service.respondChatInteraction
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal data class InteractionColors(val top: Color, val bottom: Color, val ink: Color, val accent: Color)
internal fun interactionColors(theme: String) = when (theme) {
    "sakura" -> InteractionColors(Color(0xFFFFE8F1).copy(alpha = .82f), Color(0xFFFFC8DE).copy(alpha = .58f), Color(0xFF833252), Color(0xFFCF85A4))
    "lavender" -> InteractionColors(Color(0xFFF4EAFF).copy(alpha = .84f), Color(0xFFD6C4FF).copy(alpha = .58f), Color(0xFF4A3074), Color(0xFFA28AC8))
    else -> InteractionColors(Color(0xFFECF8FF).copy(alpha = .84f), Color(0xFFBEE0FC).copy(alpha = .58f), Color(0xFF27476E), Color(0xFF8AAACB))
}

@Composable
internal fun InteractionGlass(theme: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = interactionColors(theme)
    Column(modifier.background(Brush.linearGradient(listOf(colors.top, colors.bottom)), RoundedCornerShape(24.dp))
        .border(1.5.dp, Color.White.copy(alpha = .88f), RoundedCornerShape(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompositionLocalProvider(LocalContentColor provides colors.ink) { content() }
    }
}

@Composable
fun RichChatCard(card: ChatInteraction, conversationId: Uuid?, readOnly: Boolean, modifier: Modifier = Modifier,
    senderName: String = if (card.actor == "user") "你" else "对方") {
    val interactive = !readOnly && card.canAccessCallHistory(conversationId)
    val historySource = me.rerere.rikkahub.ui.pages.voice.LocalCallHistorySource.current
    val loadHistory = interactive || (historySource != null &&
        historySource.conversationId == conversationId && card.canAccessCallHistory(historySource.conversationId))
    val service: ChatService? = if (interactive) koinInject() else null
    val scope = rememberCoroutineScope()
    val nav = if (interactive) LocalNavController.current else null
    val permission = rememberPermissionState(PermissionRecordAudio)
    var expanded by rememberSaveable(card.id) { mutableStateOf(false) }
    var busy by remember(card.id) { mutableStateOf(false) }
    var error by remember(card.id) { mutableStateOf<String?>(null) }
    val recipient = card.actor == "assistant"
    val title = when (card.kind) { "red_packet" -> "专属红包"; "transfer" -> "转账"; else -> "语音通话" }
    val illustration = when {
        card.kind == "call" && card.theme == "lavender" -> R.drawable.interaction_moon
        card.theme == "sakura" -> R.drawable.interaction_bear
        card.kind == "call" -> R.drawable.interaction_phone
        else -> R.drawable.interaction_fish
    }
    val state = when (card.status) {
        "syncing", "unconfirmed" -> "结果待核实"
        "failed" -> "未发出"
        "connecting" -> "正在连接"
        "accepted" -> if (card.kind == "call") "通话中" else "已领取"
        "returned" -> "已退回"
        "declined" -> "已拒绝"
        "ended" -> "通话已结束"
        else -> if (card.kind == "call") "等待接听" else "待领取"
    }
    val callState = when (card.status) {
        "connecting" -> "正在连接"
        "accepted" -> "通话中"
        "declined" -> "未接听"
        "ended" -> "已结束"
        else -> "等待接听"
    }
    fun openCard() {
        if (card.kind == "call" && interactive) {
            when (card.callDestination(me.rerere.rikkahub.service.VoiceCallService.activeConversationId.value,
                me.rerere.rikkahub.service.VoiceCallService.startedAtMillis.value)) {
                CallCardDestination.Incoming -> { nav?.navigate(Screen.IncomingCall(card.conversationId, card.id)); return }
                CallCardDestination.Active -> { nav?.navigate(Screen.VoiceCall(card.conversationId)); return }
                CallCardDestination.History -> Unit
            }
        }
        expanded = true
    }
    fun respond(action: String) {
        if (!interactive || busy) return
        if (card.kind == "call" && action == "accept" && !permission.allRequiredPermissionsGranted) {
            permission.requestPermissions(); error = "请允许录音权限后，再点接听"; return
        }
        busy = true
        scope.launch {
            try {
                val result = requireNotNull(service).respondChatInteraction(conversationId!!, card.id, "user", action)
                error = result.error
                if (card.kind == "call" && result.status == "connecting" && action == "accept") {
                    expanded = false
                    nav?.navigate(Screen.VoiceCall(conversationId.toString()))
                }
            } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (card.actor == "user") Alignment.End else Alignment.Start) {
        if (card.kind == "call") {
            val colors = interactionColors(card.theme)
            val seconds = card.connectedAtMillis?.let {
                ((card.endedAtMillis ?: System.currentTimeMillis()) - it).coerceAtLeast(0) / 1000
            }
            Row(
                Modifier.widthIn(max = 280.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(colors.top, colors.bottom)))
                    .border(1.5.dp, Color.White.copy(alpha = .88f), RoundedCornerShape(20.dp))
                    .clickable { openCard() }.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(36.dp).background(Color.White.copy(alpha = .75f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Image(painterResource(illustration), null, Modifier.size(24.dp), colorFilter = interactionIconColorFilter(illustration, colors.accent))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val callLabel = if (card.videoCall) "视频通话" else "语音通话"
                    Text(when (card.status) {
                        "pending" -> if (card.videoCall) "视频通话邀请" else "语音通话邀请"
                        "declined" -> "$callLabel · 未接听"
                        "ended" -> if (seconds == null) "$callLabel · 已结束" else "通话结束 · %02d:%02d".format(seconds / 60, seconds % 60)
                        else -> if (seconds == null) callLabel else "$callLabel · %02d:%02d".format(seconds / 60, seconds % 60)
                    },
                        color = colors.ink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("✦ $callState · 点击查看", color = colors.ink.copy(alpha = .75f), fontSize = 10.sp, lineHeight = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (card.giftSummary.isNotBlank()) {
                        Text("🎁 ${card.giftSummary}", color = colors.ink.copy(alpha = .85f), fontSize = 10.sp,
                            lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else GiftChatBubble(card, state, senderName) { expanded = true }
    }

    if (expanded && card.kind == "call") {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            me.rerere.rikkahub.ui.pages.voice.CallHistoryRoute(card, conversationId, loadHistory) { expanded = false }
        }
    } else if (expanded) InteractionDetailDialog(onDismiss = { if (!busy) expanded = false }, enabled = !busy) { close ->
        Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            GiftDetailCard(card, state, interactive, busy, error, senderName, ::respond)
            Text(if (card.walletLinked) "关联小钱包账本" else "虚拟金额 · 不关联钱包", Modifier.padding(top = 10.dp), fontSize = 11.sp)
            GiftDetailCloseButton(card.theme, enabled = !busy, onClick = close)
        }
    }
}

@Composable
fun SendChatGiftDialog(conversationId: Uuid, assistant: Assistant, kind: String, onDismiss: () -> Unit) {
    val service: ChatService = koinInject()
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var virtualAmount by rememberSaveable { mutableStateOf(assistant.pretendRich) }
    val requestId = rememberSaveable { Uuid.random().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        InteractionGlass(assistant.chatInteractionTheme, Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            GiftComposerContent(assistant.chatInteractionTheme, kind, virtualAmount,
                amount, { amount = it }, note, { note = it.take(200) }, busy, error,
                onModeChange = { virtualAmount = it }) {
                scope.launch {
                    busy = true
                    try {
                        parseChatGiftAmount(kind, amount)
                        val card = service.createChatInteraction(conversationId, assistant.id, "user", kind, amount, note, requestId,
                            walletLinked = !virtualAmount)
                        if (card.status == "pending") service.answerChatGift(conversationId, card.id)
                        onDismiss()
                    } catch (e: Exception) { error = e.message } finally { busy = false }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.GiftComposerContent(theme: String, kind: String, pretendRich: Boolean,
    amount: String, onAmount: (String) -> Unit, note: String, onNote: (String) -> Unit,
    busy: Boolean, error: String?, onModeChange: ((Boolean) -> Unit)? = null, onSend: () -> Unit) {
    val colors = interactionColors(theme)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.ink, unfocusedTextColor = colors.ink,
        cursorColor = colors.ink, focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.accent.copy(alpha = .65f),
        focusedLabelColor = colors.ink, unfocusedLabelColor = colors.ink.copy(alpha = .75f),
        focusedContainerColor = Color.White.copy(alpha = .28f), unfocusedContainerColor = Color.White.copy(alpha = .2f))
    Text(if (kind == "red_packet") "发红包" else "转账", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(amount, onAmount, modifier = Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("金额（元）") }, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    if (kind == "red_packet") Text("单个红包最高 200 元", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(note, onNote, modifier = Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("想说的话") }, maxLines = 3, colors = fieldColors, shape = RoundedCornerShape(16.dp))
    onModeChange?.let { change ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("使用虚拟金额", Modifier.weight(1f), color = colors.ink)
            Switch(checked = pretendRich, onCheckedChange = change, enabled = !busy,
                modifier = Modifier.testTag("gift-virtual-mode"), colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.ink, uncheckedTrackColor = colors.top))
        }
    }
    Text(if (pretendRich) "本次为虚拟金额，不关联钱包、不记账" else "本次关联小钱包：对方领取后记入收入", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ReferenceGlass(theme, Modifier.width(96.dp).height(44.dp).testTag("gift-send").semantics {
            role = androidx.compose.ui.semantics.Role.Button
            if (busy) disabled()
        }, radius = 22.dp, onClick = if (busy) null else onSend) {
            Text(if (busy) "发送中" else "发送", Modifier.align(Alignment.Center), color = colors.ink,
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun GiftDetailCloseButton(theme: String, enabled: Boolean, onClick: () -> Unit) {
    ReferenceGlass(theme, Modifier.padding(top = 10.dp, bottom = 8.dp).width(124.dp).height(44.dp)
        .testTag("gift-detail-close").semantics {
            role = androidx.compose.ui.semantics.Role.Button
            if (!enabled) disabled()
        }.graphicsLayer { alpha = if (enabled) 1f else .55f }, radius = 22.dp,
        onClick = if (enabled) onClick else null) {
        Text("关闭", Modifier.align(Alignment.Center), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = interactionColors(theme).ink)
    }
}

@Composable
internal fun InteractionDetailDialog(onDismiss: () -> Unit, enabled: Boolean, content: @Composable (() -> Unit) -> Unit) {
    val backdrop = androidx.compose.ui.platform.LocalView.current.rootView
    val density = androidx.compose.ui.platform.LocalDensity.current
    DisposableEffect(backdrop) {
        // The activity and dialog have separate windows: blur the conversation, never the card.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val radius = with(density) { 16.dp.toPx() }
            backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
        }
        onDispose { if (android.os.Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(null) }
    }
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(if (visible) 1f else .82f,
        animationSpec = if (closing) tween(120, easing = FastOutLinearInEasing)
            else tween(420, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)), label = "giftDetailScale")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (closing) 120 else 420), label = "giftDetailAlpha")
    LaunchedEffect(Unit) { visible = true }
    val close: () -> Unit = {
        if (enabled && !closing) {
            closing = true
            scope.launch { visible = false; kotlinx.coroutines.delay(120); onDismiss() }
        }
        Unit
    }
    Dialog(onDismissRequest = close) {
        Box(Modifier.testTag("gift-detail-dialog").semantics { this[GiftRevealProgress] = alpha }
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha; translationY = (1f - alpha) * 10.dp.toPx() }) { content(close) }
    }
}
