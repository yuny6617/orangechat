package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.interaction.ChatInteraction
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.service.respondChatInteraction
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import kotlin.uuid.Uuid

@Composable
internal fun ChatCallOverlay(conversation: Conversation) {
    val service: ChatService = org.koin.compose.koinInject()
    val settings = LocalSettings.current
    val nav = LocalNavController.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val tts = LocalTTSState.current
    val permission = rememberPermissionState(PermissionRecordAudio)
    val scope = rememberCoroutineScope()
    val activeId by VoiceCallService.activeConversationId.collectAsStateWithLifecycle()
    val rememberedInvite by service.pendingIncomingCall.collectAsStateWithLifecycle()
    val localInvite = conversation.currentMessages.flatMap { it.annotations }.filterIsInstance<ChatInteraction>()
        .lastOrNull { it.kind == "call" && it.actor == "assistant" && it.status == "pending" && it.conversationId == conversation.id.toString() }
    // Persisted card state is authoritative when this conversation is visible.
    val pending = if (rememberedInvite?.conversationId == conversation.id.toString()) localInvite else rememberedInvite ?: localInvite
    val targetId = activeId ?: pending?.conversationId ?: return
    val targetUuid = runCatching { Uuid.parse(targetId) }.getOrNull() ?: return
    val target by remember(targetUuid) { service.getConversationFlow(targetUuid) }.collectAsStateWithLifecycle()
    LaunchedEffect(targetUuid) { service.initializeConversation(targetUuid, updateSelectedAssistant = false) }
    val assistant = settings.getAssistantById(target.assistantId)
    var busy by remember(targetId, pending?.id) { mutableStateOf(false) }
    fun respond(action: String) {
        val card = pending ?: return
        if (busy) return
        if (action == "accept" && !permission.allRequiredPermissionsGranted) {
            permission.requestPermissions(); return
        }
        busy = true
        scope.launch {
            try {
                val result = service.respondChatInteraction(targetUuid, card.id, "user", action)
                if (action == "accept" && result.status == "connecting") {
                    tts.stop()
                    VoiceCallService.start(context, targetId)
                    nav.navigate(Screen.VoiceCall(targetId)) { launchSingleTop = true }
                } else if (action == "decline" && result.status == "declined") service.notifyVoiceCallDeclined(targetUuid)
            } catch (e: Exception) { toaster.show(e.message ?: "通话操作失败") } finally { busy = false }
        }
    }
    FloatingCallPill(theme = assistant?.chatInteractionTheme ?: pending?.theme ?: "ice",
        name = assistant?.name?.ifBlank { "对方" } ?: "对方", incoming = activeId == null, busy = busy,
        onAccept = { respond("accept") },
        onOpen = {
            val target = when {
                activeId != null && VoiceCallService.isVideoCall() -> Screen.VideoCall(targetId)
                activeId != null -> Screen.VoiceCall(targetId)
                else -> Screen.IncomingCall(targetId, pending?.id)
            }
            nav.navigate(target) { launchSingleTop = true }
        },
        onEnd = { if (activeId != null) VoiceCallService.stop(context) else respond("decline") })
}
