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

    private fun videoCallCoveredMessageIds(messages: List<UIMessage>) =
        me.rerere.rikkahub.data.interaction.videoCallCoveredMessageIds(messages, messages, conversationId)

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
    fun `空snapshot仅在精确原文详情可达时隐藏`() {
        val userMessage = UIMessage.user("你好")
        val replyMessage = UIMessage.assistant("我很好")
        val noTranscript = cardNode(
            callCard(listOf(userMessage.id.toString(), replyMessage.id.toString()), transcript = null),
        )
        val originals = messages(listOf(noTranscript, userMessage.toMessageNode(), replyMessage.toMessageNode()))
        assertEquals(setOf(userMessage.id, replyMessage.id), videoCallCoveredMessageIds(originals))
        assertEquals(listOf("你好", "我很好"), noTranscript.currentMessage.annotations
            .filterIsInstance<ChatInteraction>().single().transcriptFrom(originals, "我", "对方").map { it.text })
        assertTrue("无可达详情时不隐藏", me.rerere.rikkahub.data.interaction
            .videoCallCoveredMessageIds(originals).isEmpty())
    }

    @Test
    fun `dynamic exact originals replace empty partial and wrong role snapshots without losing text`() {
        val user = UIMessage.user("用户原文")
        val reply = UIMessage.assistant("完整回复不能少任何一个字")
        val ids = listOf(user.id.toString(), reply.id.toString())
        val empty = cardNode(callCard(ids, transcript = emptyList()))
        assertEquals(setOf(user.id, reply.id), videoCallCoveredMessageIds(listOf(empty.currentMessage, user, reply)))
        val partial = cardNode(callCard(ids, transcript = listOf(
            CallTranscriptEntry("user", "我", "用户原文", 1),
            CallTranscriptEntry("assistant", "对方", "完整回复", 2),
        )))
        assertEquals(setOf(user.id, reply.id), videoCallCoveredMessageIds(listOf(partial.currentMessage, user, reply)))
        val wrongRole = cardNode(callCard(ids, transcript = listOf(
            CallTranscriptEntry("user", "我", reply.toText(), 2),
        )))
        val all = listOf(wrongRole.currentMessage, user, reply)
        assertEquals(setOf(user.id, reply.id), videoCallCoveredMessageIds(all))
        assertEquals(listOf("user" to user.toText(), "assistant" to reply.toText()),
            wrongRole.currentMessage.annotations.filterIsInstance<ChatInteraction>().single()
                .transcriptFrom(all, "我", "对方").map { it.role to it.text })
        assertTrue(me.rerere.rikkahub.data.interaction.videoCallCoveredMessageIds(all, all, Uuid.random()).isEmpty())
        assertTrue(me.rerere.rikkahub.data.interaction.videoCallCoveredMessageIds(all, emptyList(), conversationId).isEmpty())
    }

    @Test
    fun `late assistant revision is fully accessible only in card and deleting card restores bubbles`() {
        val user = UIMessage.user("你好")
        val original = UIMessage.assistant("前半")
        val linked = callCard(listOf(user.id.toString(), original.id.toString()), transcript = listOf(
            CallTranscriptEntry("user", "我", "你好", 1),
            CallTranscriptEntry("assistant", "对方", "前半", 2),
        ))
        val late = original.copy(parts = listOf(UIMessagePart.Text("前半，后半是取消最终保存的文字")))
        val messages = listOf(cardNode(linked).currentMessage, user, late)
        assertEquals(listOf("你好", "前半，后半是取消最终保存的文字"), linked.transcriptFrom(messages, "我", "对方").map { it.text })
        assertEquals(setOf(user.id, late.id), videoCallCoveredMessageIds(messages))
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
    fun `branch switch and unlinked late IDs fail closed even with identical text`() {
        val original = UIMessage.assistant("同一句完整文字")
        val alternate = UIMessage.assistant("同一句完整文字")
        val card = cardNode(callCard(listOf(original.id.toString()), transcript = emptyList())).currentMessage
        val full = listOf(card, original)
        assertEquals(setOf(original.id), videoCallCoveredMessageIds(full))
        assertTrue(videoCallCoveredMessageIds(listOf(card, alternate)).isEmpty())
        assertTrue(me.rerere.rikkahub.data.interaction.videoCallCoveredMessageIds(
            full, listOf(card, alternate), conversationId).isEmpty())
        assertTrue(me.rerere.rikkahub.data.interaction.videoCallCoveredMessageIds(
            listOf(original), full, conversationId).isEmpty())
        val late = UIMessage.assistant("新ID迟到文字必须保留")
        assertEquals(setOf(original.id), videoCallCoveredMessageIds(full + late))
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
    fun `mixed assistant hides only covered text and never exports reasoning into transcript`() {
        val reasoning = UIMessagePart.Reasoning("private reasoning")
        val tool = UIMessagePart.Tool("call", "lookup", "{}", emptyList())
        val image = UIMessagePart.Image("file:///photo.jpg")
        val gift = me.rerere.ai.ui.UIMessageAnnotation.VideoCallGift("gift", "heart")
        val reply = UIMessage.assistant("spoken body").copy(
            parts = listOf(reasoning, UIMessagePart.Text("spoken body"), tool, image), annotations = listOf(gift))
        val card = callCard(listOf(reply.id.toString()), transcript = emptyList())
        val all = listOf(cardNode(card).currentMessage, reply)
        assertEquals(setOf(reply.id), videoCallCoveredMessageIds(all))
        assertEquals(listOf("spoken body"), card.transcriptFrom(all, "我", "对方").map { it.text })
        val display = reply.withoutVideoCallTranscriptText()
        assertEquals(listOf(reasoning, tool, image), display.parts)
        assertEquals(listOf(gift), display.annotations)
        assertEquals("spoken body", reply.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertFalse(reply.absorbedIntoVideoCallCard())
        assertTrue(videoCallCoveredMessageIds(listOf(reply)).isEmpty())
        val generation = Conversation(assistantId = Uuid.random(), messageNodes = all.map { it.toMessageNode() })
            .messagesForGeneration()
        assertEquals(1, generation.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().count { it.text == "spoken body" })
        assertEquals(1, generation.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>().size)
    }

    @Test
    fun `late tool rounds use explicit request ownership after card snapshot and survive serialization`() {
        val user = UIMessage.user("question")
        val history = UIMessage.assistant("old assistant")
        val requestIds = setOf(history.id, user.id)
        val card = callCard(listOf(user.id.toString()), transcript = emptyList())
        val tool = UIMessage.assistant("tool preface").copy(parts = listOf(
            UIMessagePart.Text("tool preface"), UIMessagePart.Tool("t", "lookup", "{}", emptyList())))
        val late = UIMessage.assistant("late final body")
        val outputs = associateVideoCallReplies(listOf(history, user, tool, late), requestIds, conversationId, user.id)
        assertEquals(history, outputs[0])
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString<List<UIMessage>>(json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(UIMessage.serializer()), outputs))
        val all = listOf(cardNode(card).currentMessage) + restored
        assertEquals(setOf(user.id, tool.id, late.id), videoCallCoveredMessageIds(all))
        assertEquals(listOf("question", "tool preface", "late final body"), card.transcriptFrom(all, "我", "对方").map { it.text })
        assertTrue(restored.last().absorbedIntoVideoCallCard())
        val unrelated = UIMessage.assistant("late final body")
        assertFalse(unrelated.id in videoCallCoveredMessageIds(all + unrelated))
        val alternate = user.copy(id = Uuid.random())
        val switched = listOf(cardNode(card).currentMessage, alternate) + restored.filter { it.role != me.rerere.ai.core.MessageRole.USER }
        assertTrue(videoCallCoveredMessageIds(switched).isEmpty())
        assertEquals(listOf(late), associateVideoCallReplies(listOf(late), requestIds, conversationId, null))
        val otherConversation = associateVideoCallReplies(listOf(late), requestIds, Uuid.random(), user.id)
        assertEquals(setOf(user.id), videoCallCoveredMessageIds(listOf(cardNode(card).currentMessage, user) + otherConversation))
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
