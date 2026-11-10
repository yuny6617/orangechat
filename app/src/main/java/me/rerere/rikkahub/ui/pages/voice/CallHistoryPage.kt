package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.ui.CallTranscriptEntry
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.interaction.ChatInteraction
import me.rerere.rikkahub.data.interaction.transcriptFrom
import me.rerere.rikkahub.data.interaction.canAccessCallHistory
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.message.*
import me.rerere.rikkahub.ui.context.LocalSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

internal data class CallHistorySource(val conversationId: Uuid, val messages: List<me.rerere.ai.ui.UIMessage>)
internal val LocalCallHistorySource = compositionLocalOf<CallHistorySource?> { null }

@Composable
internal fun CallHistoryRoute(card: ChatInteraction, conversationId: Uuid?, loadHistory: Boolean, onClose: () -> Unit) {
    val settings = LocalSettings.current
    val assistant = settings.assistants.firstOrNull { it.id.toString() == card.assistantId }
    var rows = card.callTranscript.orEmpty()
    val source = LocalCallHistorySource.current
    if (loadHistory && source != null && card.canAccessCallHistory(source.conversationId) &&
        source.conversationId == conversationId) {
        rows = card.transcriptFrom(source.messages,
            settings.displaySetting.userNickname.ifBlank { "我" }, assistant?.name?.ifBlank { "对方" } ?: "对方")
    } else if (loadHistory && conversationId != null && card.canAccessCallHistory(conversationId)) {
        val service: ChatService = org.koin.compose.koinInject()
        val conversation by remember(conversationId) { service.getConversationFlow(conversationId) }.collectAsStateWithLifecycle()
        rows = card.transcriptFrom(conversation.currentMessages,
            settings.displaySetting.userNickname.ifBlank { "我" }, assistant?.name?.ifBlank { "对方" } ?: "对方")
    }
    CallHistoryPage(card, assistant?.name?.ifBlank { "对方" } ?: "对方", rows, onClose)
}

@Composable
internal fun CallHistoryPage(card: ChatInteraction, assistantName: String, rows: List<CallTranscriptEntry>, onClose: () -> Unit) {
    val colors = interactionColors(card.theme)
    val date = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val startedAt = card.connectedAtMillis
    val endedAt = card.endedAtMillis
    val duration = if (startedAt != null && endedAt != null) ((endedAt - startedAt).coerceAtLeast(0) / 1000) else null
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.top, colors.bottom)))
        .systemBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
        ReferenceGlass(card.theme, Modifier.widthIn(max = 380.dp).fillMaxWidth().heightIn(max = 620.dp)
            .testTag("call-history"), radius = 38.dp) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(themeMascot(card.theme)), null, Modifier.size(40.dp),
                        colorFilter = interactionIconColorFilter(themeMascot(card.theme), colors.accent))
                    Column(Modifier.weight(1f)) {
                        Text(assistantName + " · 通话记录", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(date.format(Date(card.connectedAtMillis ?: card.createdAtMillis)), fontSize = 11.sp,
                            color = colors.ink.copy(alpha = .7f))
                    }
                }
                Text(if (duration != null) "通话结束 · %02d:%02d".format(duration / 60, duration % 60)
                    else if (card.status == "declined") "未接通" else "通话已结束", fontSize = 12.sp)
                HorizontalDivider(color = Color.White.copy(alpha = .8f))
                if (card.callTranscript == null && rows.isNotEmpty()) Text("历史记录按通话时段恢复", fontSize = 10.sp,
                    color = colors.ink.copy(alpha = .65f))
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 250.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (rows.isEmpty()) Text(if (card.connectedAtMillis == null) "这次通话未接通，没有文字记录。"
                        else "这次通话没有可显示的文字记录。", fontSize = 13.sp, color = colors.ink.copy(alpha = .7f))
                    rows.forEach { row ->
                        Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = if (row.role == "user") .48f else .68f), RoundedCornerShape(18.dp))
                            .border(1.dp, Color.White.copy(alpha = .85f), RoundedCornerShape(18.dp)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(row.speaker, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(clock.format(Date(row.timestampMillis)), fontSize = 10.sp, color = colors.ink.copy(alpha = .65f))
                            }
                            Text(row.text, fontSize = 14.sp, lineHeight = 22.sp)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GiftDetailCloseButton(card.theme, true, onClose) }
            }
        }
    }
}
