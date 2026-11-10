/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.interaction

import me.rerere.ai.ui.CallTranscriptEntry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 用户点 #5：挂断后只留一张 P4 通话记录卡，不再把这次通话的每条文字铺成 P5 气泡。
 *
 * 这一组钉住的是**数据结构 + 显示边界**，而不是"看起来像隐藏了"：
 * 1. 关联只来自卡上的持久字段（不许按开始/结束时间猜、老数据不许隐藏）；
 * 2. 隐藏只发生在显示层：`Conversation.messagesForGeneration()` 里的文字逐字仍在、且只出现一次；
 * 3. 卡被删掉之后原文必须重新可见；
 * 4. 带图片/工具的消息不被吸收（用户特意留的影不能被藏进一张只有文字的卡里）；
 * 5. 一条消息只属于一通电话。
 *
 * 服务侧的"提交 → 记录 id → 挂断写卡"由 `VoiceCallServiceLifecycleTest` 里的
 * 服务集成用例覆盖（真实的 `VoiceCallService.endCall` 链）。
 */
class VideoCallCardLinkTest {
    private val conversationId = Uuid.random()

    private fun callCard(
        ids: List<String>,
        transcript: List<CallTranscriptEntry>? = listOf(
            CallTranscriptEntry("user", "我", "你好", 1_000L),
            CallTranscriptEntry("assistant", "对方", "我很好", 2_000L),
        ),
        videoCall: Boolean = true,
        kind: String = "call",
    ) = ChatInteraction(
        id = "card-1",
        conversationId = conversationId.toString(),
        assistantId = "assistant-1",
        actor = "user",
        kind = kind,
        status = "ended",
        connectedAtMillis = 1_000L,
        endedAtMillis = 3_000L,
        callTranscript = transcript,
        videoCall = videoCall,
        videoCallMessageIds = ids,
    )

    private fun cardNode(card: ChatInteraction = callCard(emptyList())): MessageNode =
        UIMessage.user(card.readableText()).copy(annotations = listOf(card)).toMessageNode()

    private fun messages(nodes: List<MessageNode>) = nodes.map { it.currentMessage }

    @Test
    fun `关联只来自卡上的持久字段 老数据一律不隐藏`() {
        // 关联里存的是**消息 id**（不是 MessageNode id）：落库的用户消息 id 才是队列项 id。
        val userMessage = UIMessage.user("你好")
        val replyMessage = UIMessage.assistant("我很好")
        val user = userMessage.toMessageNode()
        val reply = replyMessage.toMessageNode()
        // 老数据：卡在、但没有任何关联 → 不猜、不隐藏。
        assertTrue(videoCallCoveredMessageIds(messages(listOf(cardNode(), user, reply))).isEmpty())
        // 写入关联之后才生效。
        val linked = cardNode(callCard(listOf(userMessage.id.toString(), replyMessage.id.toString())))
        assertEquals(
            setOf(userMessage.id, replyMessage.id),
            videoCallCoveredMessageIds(messages(listOf(linked, user, reply))),
        )
    }

    @Test
    fun `卡里没有 transcript 时宁可继续铺消息也不许把文字藏没`() {
        val userMessage = UIMessage.user("你好")
        val replyMessage = UIMessage.assistant("我很好")
        val noTranscript = cardNode(
            callCard(listOf(userMessage.id.toString(), replyMessage.id.toString()), transcript = null),
        )
        assertTrue(
            "卡内看不到文字时不许隐藏原文（否则用户再也打不开这段对话）",
            videoCallCoveredMessageIds(
                messages(listOf(noTranscript, userMessage.toMessageNode(), replyMessage.toMessageNode())),
            ).isEmpty(),
        )
    }

    @Test
    fun `empty and incomplete snapshots never hide unrepresented original text`() {
        val user = UIMessage.user("用户原文")
        val reply = UIMessage.assistant("完整回复不能少任何一个字")
        val ids = listOf(user.id.toString(), reply.id.toString())
        val empty = cardNode(callCard(ids, transcript = emptyList()))
        assertTrue(videoCallCoveredMessageIds(listOf(empty.currentMessage, user, reply)).isEmpty())
        val partial = cardNode(callCard(ids, transcript = listOf(
            CallTranscriptEntry("user", "我", "用户原文", 1),
            CallTranscriptEntry("assistant", "对方", "完整回复", 2),
        )))
        assertEquals(setOf(user.id), videoCallCoveredMessageIds(listOf(partial.currentMessage, user, reply)))
        val wrongRole = cardNode(callCard(ids, transcript = listOf(
            CallTranscriptEntry("user", "我", reply.toText(), 2),
        )))
        assertTrue(videoCallCoveredMessageIds(listOf(wrongRole.currentMessage, user, reply)).isEmpty())
    }

    @Test
    fun `late assistant revision is accessible in history but remains visible until snapshot is complete`() {
        val user = UIMessage.user("你好")
        val original = UIMessage.assistant("前半")
        val linked = callCard(listOf(user.id.toString(), original.id.toString()), transcript = listOf(
            CallTranscriptEntry("user", "我", "你好", 1),
            CallTranscriptEntry("assistant", "对方", "前半", 2),
        ))
        val late = original.copy(parts = listOf(UIMessagePart.Text("前半，后半是取消最终保存的文字")))
        val messages = listOf(cardNode(linked).currentMessage, user, late)
        assertEquals(listOf("你好", "前半，后半是取消最终保存的文字"), linked.transcriptFrom(messages, "我", "对方").map { it.text })
        assertEquals(setOf(user.id), videoCallCoveredMessageIds(messages))
        assertTrue("删除卡后所有原文重新显示", videoCallCoveredMessageIds(listOf(user, late)).isEmpty())
    }

    @Test
    fun `multiple calls recover only their own linked messages without time guessing`() {
        val firstUser = UIMessage.user("第一通")
        val firstReply = UIMessage.assistant("回答一")
        val secondUser = UIMessage.user("第二通")
        val secondReply = UIMessage.assistant("回答二")
        val first = callCard(listOf(firstUser.id.toString(), firstReply.id.toString()), transcript = emptyList())
        val second = callCard(listOf(secondUser.id.toString(), secondReply.id.toString()), transcript = emptyList()).copy(id = "card-2")
        val all = listOf(firstUser, firstReply, secondUser, secondReply)
        assertEquals(listOf("第一通", "回答一"), first.transcriptFrom(all, "我", "对方").map { it.text })
        assertEquals(listOf("第二通", "回答二"), second.transcriptFrom(all, "我", "对方").map { it.text })
    }

    @Test
    fun `语音通话卡与红包卡永远不吸收消息`() {
        val userMessage = UIMessage.user("你好")
        val user = userMessage.toMessageNode()
        val voice = cardNode(callCard(listOf(userMessage.id.toString()), videoCall = false))
        assertTrue(videoCallCoveredMessageIds(messages(listOf(voice, user))).isEmpty())
        val packet = cardNode(callCard(listOf(userMessage.id.toString()), kind = "red_packet"))
        assertTrue(videoCallCoveredMessageIds(messages(listOf(packet, user))).isEmpty())
    }

    @Test
    fun `卡被删除之后原文重新可见`() {
        val userMessage = UIMessage.user("你好")
        val replyMessage = UIMessage.assistant("我很好")
        val user = userMessage.toMessageNode()
        val reply = replyMessage.toMessageNode()
        val card = cardNode(callCard(listOf(userMessage.id.toString(), replyMessage.id.toString())))
        val stored = listOf(card, user, reply)

        val covered = videoCallCoveredMessageIds(messages(stored))
        assertEquals(listOf(card), stored.withoutVideoCallCardMessages(covered))

        // 卡所在的那条消息被删除（关联就存在卡自己身上）→ 集合为空 → 原文可见。
        val afterDelete = stored.filterNot { it.id == card.id }
        assertTrue(videoCallCoveredMessageIds(messages(afterDelete)).isEmpty())
        assertEquals(afterDelete, afterDelete.withoutVideoCallCardMessages(videoCallCoveredMessageIds(messages(afterDelete))))
    }

    @Test
    fun `带媒体或工具的消息不被吸收`() {
        val image = UIMessage.user("📷 留影（视频通话）").copy(
            parts = listOf(
                UIMessagePart.Text("📷 留影（视频通话）"),
                UIMessagePart.Image(url = "file:///tmp/snapshot.jpg"),
            ),
        )
        assertFalse("特意留下的照片不许被藏进纯文字卡里", image.absorbedIntoVideoCallCard())
        val textOnly = UIMessage.user("就一句文字")
        assertTrue(textOnly.absorbedIntoVideoCallCard())
        val tool = UIMessage.assistant("").copy(
            parts = listOf(UIMessagePart.Tool("call-1", "tool_name", "{}", emptyList())),
        )
        assertFalse("工具过程也不吸收", tool.absorbedIntoVideoCallCard())
        // 卡自己的消息永远不吸收（否则卡会把自己藏掉）。
        assertFalse(cardNode().currentMessage.absorbedIntoVideoCallCard())
    }

    @Test
    fun `显示被吸收 但生成上下文逐字仍在且只出现一次`() {
        val userMessage = UIMessage.user("你好")
        val replyMessage = UIMessage.assistant("我很好")
        val user = userMessage.toMessageNode()
        val reply = replyMessage.toMessageNode()
        // 普通聊天（同一会话里、通话之外的）必须照常显示。
        val normal = UIMessage.user("通话之后我又聊了一句").toMessageNode()
        val card = cardNode(callCard(listOf(userMessage.id.toString(), replyMessage.id.toString())))
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(card, user, reply, normal),
        )
        val covered = videoCallCoveredMessageIds(conversation.currentMessages)

        // 显示层：只剩通话卡 + 普通聊天。
        assertEquals(
            listOf(card.id, normal.id),
            conversation.messageNodes.withoutVideoCallCardMessages(covered).map { it.id },
        )

        // 上下文：模型的输入完全没变，而且每句只出现一次（卡里的 transcript 不会再注入一份）。
        val generation = conversation.messagesForGeneration()
        assertEquals(4, generation.size)
        assertEquals(1, generation.count { it.toText().trim() == "你好" })
        assertEquals(1, generation.count { it.toText().trim() == "我很好" })
        assertEquals(1, generation.count { it.toText().trim() == "通话之后我又聊了一句" })
        assertEquals(1, generation.count { it.toText() == card.currentMessage.toText() })
    }

    @Test
    fun `助手回复按队列同一套边界关联 不会把下一条用户消息算进来`() {
        val raw = listOf(
            UIMessage.user("第一句"),
            UIMessage.assistant("前半"),
            UIMessage.assistant("后半"),
            UIMessage.user("第二句"),
            UIMessage.assistant("回答二"),
            UIMessage.user("普通聊天"),
            UIMessage.assistant("普通回答"),
        )
        val plain = raw
        assertEquals(
            "用户消息本身 + 到下一条用户消息之前的全部助手片段",
            listOf(raw[0].id, raw[1].id, raw[2].id),
            videoCallReplyMessageIds(plain, raw[0].id),
        )
        assertEquals(listOf(raw[3].id, raw[4].id), videoCallReplyMessageIds(plain, raw[3].id))
        assertEquals(listOf(raw[5].id, raw[6].id), videoCallReplyMessageIds(plain, raw[5].id))
        // 不存在的消息 id 不许凭空造出关联。
        assertTrue(videoCallReplyMessageIds(plain, Uuid.random()).isEmpty())

        // 只关联这通电话里真实提交过的两条用户消息。
        assertEquals(
            listOf(raw[0].id, raw[1].id, raw[2].id, raw[3].id, raw[4].id).map { it.toString() },
            videoCallLinkedMessageIds(plain, listOf(raw[0].id, raw[3].id)),
        )
    }

    @Test
    fun `一笔消息只属于一通电话 合并幂等`() {
        val ids = listOf("m1", "m2", "m2")
        val first = callCard(listOf("m0")).withVideoCallMessages(ids)
        assertEquals(listOf("m0", "m1", "m2"), first.videoCallMessageIds)
        // 幂等：重复写入不会重复累积。
        assertEquals(first.videoCallMessageIds, first.withVideoCallMessages(ids).videoCallMessageIds)

        val older = callCard(listOf("m1", "m2", "m3"))
        assertEquals(listOf("m3"), older.withoutVideoCallMessages(setOf("m1", "m2")).videoCallMessageIds)
        // 不含这些 id 时返回同一个对象（避免无意义的落库）。
        assertTrue(older.withoutVideoCallMessages(setOf("zzz")) === older)
    }

    @Test
    fun `关联字段有上限 防止异常数据撑爆单条注解`() {
        val many = (0 until MAX_VIDEO_CALL_MESSAGE_IDS + 50).map { "m$it" }
        val card = callCard(emptyList()).withVideoCallMessages(many)
        assertEquals(MAX_VIDEO_CALL_MESSAGE_IDS, card.videoCallMessageIds.size)
        // 保留最新的那一批（挂断前刚提交的那几条不能被老数据挤掉）。
        assertEquals("m${MAX_VIDEO_CALL_MESSAGE_IDS + 49}", card.videoCallMessageIds.last())
    }
}
