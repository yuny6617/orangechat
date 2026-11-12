/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.localdata.AuthorKind
import me.rerere.rikkahub.plugin.localdata.LocalAuthorDto
import me.rerere.rikkahub.plugin.localdata.LocalDataMethods
import me.rerere.rikkahub.plugin.localdata.LocalDraftDto
import me.rerere.rikkahub.plugin.localdata.PostVisibility
import me.rerere.rikkahub.plugin.localtasks.LocalAssistantPolicy
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMomentsChatDraftToolTest {
    private val conversationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val otherConversationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val assistantId = "11111111-1111-1111-1111-111111111111"
    private val otherAssistantId = "22222222-2222-2222-2222-222222222222"

    private val user1 = "00000000-0000-4000-8000-000000000001"

    // user1 is digits-only, so uppercasing it would be a no-op. This fixture contains hex
    // letters, so it really exercises the lowercase-only canonical wire form.
    private val uppercaseUuid = "00000000-0000-4000-8000-00000000000A"
    private val assistant1 = "00000000-0000-4000-8000-000000000002"
    private val user2 = "00000000-0000-4000-8000-000000000003"
    private val system1 = "00000000-0000-4000-8000-000000000004"
    private val tool1 = "00000000-0000-4000-8000-000000000005"

    // ------------------------------------------------------------ selection scope

    @Test
    fun `requested ids resolve in conversation order not request order`() {
        val messages = listOf(
            message(user1, MessageRole.USER, UIMessagePart.Text("第一条")),
            message(assistant1, MessageRole.ASSISTANT, UIMessagePart.Text("第二条")),
            message(user2, MessageRole.USER, UIMessagePart.Text("第三条")),
        )
        val result = plan(turn(messages), listOf(user2, user1))

        val ready = result as LocalMomentsChatDraftPlanResult.Ready
        assertEquals(listOf(user1, user2), ready.plan.selectedMessages.map { it.id.toString() })
        assertEquals(listOf("第一条", "第三条"), ready.plan.snapshot.messages.map { it.text })
    }

    @Test
    fun `id outside the user selection is rejected even when it exists in the conversation`() {
        val messages = listOf(
            message(user1, MessageRole.USER, UIMessagePart.Text("已选中")),
            message(user2, MessageRole.USER, UIMessagePart.Text("未选中")),
        )
        val result = plan(turn(messages, selected = listOf(user1)), listOf(user2))

        val denied = result as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("message_not_selected", denied.code)
    }

    @Test
    fun `id from another conversation cannot cross the window`() {
        val current = listOf(message(user1, MessageRole.USER, UIMessagePart.Text("当前会话")))
        // A stale selection might still mention an id from another conversation. The planner only
        // looks at the trusted current message list, so that id must be not_found.
        val result = plan(turn(current, selected = listOf(user1, user2)), listOf(user2))

        val denied = result as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("message_not_found", denied.code)
    }

    @Test
    fun `empty selection or empty request is rejected`() {
        val messages = listOf(message(user1, MessageRole.USER, UIMessagePart.Text("x")))
        val noSelection = plan(turn(messages, selected = emptyList()), listOf(user1))
        assertEquals(
            "no_user_selection",
            (noSelection as LocalMomentsChatDraftPlanResult.Denied).code,
        )
        val emptyRequest = plan(turn(messages), emptyList())
        assertEquals(
            "no_messages",
            (emptyRequest as LocalMomentsChatDraftPlanResult.Denied).code,
        )
    }

    // ------------------------------------------------------------- model allowlist

    @Test
    fun `selection prompt is the model facing allowlist`() {
        val messages = listOf(
            message(system1, MessageRole.SYSTEM, UIMessagePart.Text("系统提示词")),
            message(user1, MessageRole.USER, UIMessagePart.Text("用户正文")),
            message(tool1, MessageRole.TOOL, UIMessagePart.Text("工具结果")),
            message(assistant1, MessageRole.ASSISTANT, UIMessagePart.Text("助手正文")),
            message(user2, MessageRole.USER, UIMessagePart.Text("未选中")),
        )
        val prompt = LocalMomentsChatDraftPrompt.selectionContext(
            turn(messages, selected = listOf(system1, user1, tool1, assistant1)),
        )

        assertTrue(prompt.contains("id=$user1"))
        assertTrue(prompt.contains("id=$assistant1"))
        assertFalse(prompt.contains("id=$system1"))
        assertFalse(prompt.contains("id=$tool1"))
        assertFalse(prompt.contains("id=$user2"))
        assertTrue(prompt.contains("role=user"))
        assertTrue(prompt.contains("role=assistant"))
    }

    @Test
    fun `selection prompt excludes reasoning and documents but keeps image count`() {
        val messages = listOf(
            message(
                user1,
                MessageRole.USER,
                UIMessagePart.Text("正文"),
                UIMessagePart.Reasoning("思考内容"),
                UIMessagePart.Document("file:///secret.pdf", "secret.pdf"),
                UIMessagePart.Image("file:///a.png"),
                UIMessagePart.Image("file:///b.png"),
            ),
        )
        val prompt = LocalMomentsChatDraftPrompt.selectionContext(turn(messages))

        assertTrue(prompt.contains("images=2"))
        assertTrue(prompt.contains("text=\"正文"))
        assertFalse(prompt.contains("思考内容"))
        assertFalse(prompt.contains("secret.pdf"))
    }

    @Test
    fun `selection prompt is bounded and says when it omitted rows`() {
        val messages = (0 until 30).map { index ->
            message(messageId(index), MessageRole.USER, UIMessagePart.Text("z".repeat(2_000)))
        }
        val prompt = LocalMomentsChatDraftPrompt.selectionContext(turn(messages))

        assertTrue(prompt.length <= LocalMomentsChatDraftPrompt.MAX_CONTEXT_CHARS + 500)
        assertTrue(prompt.contains("省略"))
    }

    @Test
    fun `selection prompt handles a selection with no visible content`() {
        val messages = listOf(
            message(user1, MessageRole.USER, UIMessagePart.Reasoning("只有思考")),
        )
        val prompt = LocalMomentsChatDraftPrompt.selectionContext(turn(messages))

        assertTrue(prompt.contains("没有可写入"))
    }

    // ------------------------------------------------------------------ filtering

    @Test
    fun `system and tool messages are excluded from the card`() {
        val messages = listOf(
            message(system1, MessageRole.SYSTEM, UIMessagePart.Text("系统提示词")),
            message(user1, MessageRole.USER, UIMessagePart.Text("用户可见")),
            message(tool1, MessageRole.TOOL, UIMessagePart.Text("工具原始结果")),
            message(assistant1, MessageRole.ASSISTANT, UIMessagePart.Text("助手可见")),
        )
        val result = plan(
            turn(messages, selected = listOf(system1, user1, tool1, assistant1)),
            listOf(system1, user1, tool1, assistant1),
        )

        val ready = result as LocalMomentsChatDraftPlanResult.Ready
        assertEquals(listOf("user", "assistant"), ready.plan.snapshot.messages.map { it.role })
        assertEquals(listOf("用户可见", "助手可见"), ready.plan.snapshot.messages.map { it.text })
        assertEquals(2, ready.plan.snapshotResult.excludedMessages)
    }

    @Test
    fun `only user and assistant visible text survives`() {
        val messages = listOf(
            message(
                user1,
                MessageRole.USER,
                UIMessagePart.Text("正文"),
                UIMessagePart.Reasoning("思考过程"),
                UIMessagePart.Tool("call-1", "search", "{}"),
                UIMessagePart.Document("file:///secret.pdf", "secret.pdf"),
            ),
            message(assistant1, MessageRole.ASSISTANT, UIMessagePart.Text("回复")),
        )
        val result = plan(turn(messages), listOf(user1, assistant1))

        val ready = result as LocalMomentsChatDraftPlanResult.Ready
        assertEquals(listOf("正文", "回复"), ready.plan.snapshot.messages.map { it.text })
        assertTrue(ready.plan.snapshotResult.excludedParts >= 3)
    }

    @Test
    fun `a selection with no visible text or image is rejected`() {
        val messages = listOf(
            message(system1, MessageRole.SYSTEM, UIMessagePart.Text("系统")),
            message(user1, MessageRole.USER, UIMessagePart.Reasoning("只有思考")),
        )
        val result = plan(
            turn(messages, selected = listOf(system1, user1)),
            listOf(system1, user1),
        )

        val denied = result as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("snapshot_limit", denied.code)
        assertTrue(denied.message.contains("没有可分享"))
    }

    // --------------------------------------------------------------------- images

    @Test
    fun `images are opt in and bound to their message`() {
        val messages = listOf(
            message(
                user1,
                MessageRole.USER,
                UIMessagePart.Text("看图"),
                UIMessagePart.Image("file:///a.png"),
                UIMessagePart.Image("data:image/png;base64,AA=="),
            ),
            message(assistant1, MessageRole.ASSISTANT, UIMessagePart.Text("收到")),
        )

        val without = plan(turn(messages), listOf(user1, assistant1), includeImages = false)
            as LocalMomentsChatDraftPlanResult.Ready
        assertEquals(0, without.plan.images.size)
        assertEquals(2, without.plan.snapshotResult.omittedImages)
        assertTrue(without.plan.snapshot.messages.all { it.media.isEmpty() })

        val with = plan(turn(messages), listOf(user1, assistant1), includeImages = true)
            as LocalMomentsChatDraftPlanResult.Ready
        assertEquals(2, with.plan.images.size)
        assertEquals(0, with.plan.snapshotResult.omittedImages)
        assertEquals(2, with.plan.snapshot.messages.first().media.size)
        assertEquals(0, with.plan.snapshot.messages.last().media.size)
    }

    @Test
    fun `more than nine images is rejected instead of truncated`() {
        val parts = buildList<UIMessagePart> {
            add(UIMessagePart.Text("十张图"))
            repeat(10) { add(UIMessagePart.Image("file:///image-$it.png")) }
        }
        val result = plan(
            turn(listOf(message(user1, MessageRole.USER, *parts.toTypedArray()))),
            listOf(user1),
            includeImages = true,
        )

        val denied = result as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("snapshot_limit", denied.code)
        assertTrue(denied.message.contains("9"))
    }

    // --------------------------------------------------------------------- limits

    @Test
    fun `more than fifty messages is rejected by the planner`() {
        val messages = (0 until 51).map { index ->
            message(messageId(index), MessageRole.USER, UIMessagePart.Text("m$index"))
        }
        val result = plan(turn(messages), messages.map { it.id.toString() })

        val denied = result as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("too_many_messages", denied.code)
    }

    @Test
    fun `over long single message is rejected without truncation`() {
        val messages = listOf(
            message(user1, MessageRole.USER, UIMessagePart.Text("x".repeat(2_001))),
        )
        val result = plan(turn(messages), listOf(user1))

        val denied = result as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("snapshot_limit", denied.code)
        assertTrue(denied.message.contains("2000"))
    }

    @Test
    fun `over total character budget is rejected`() {
        // 11 * 1,900 = 20,900 > 20,000, while each message stays under the per-message cap.
        val messages = (0 until 11).map { index ->
            message(
                messageId(index),
                MessageRole.USER,
                UIMessagePart.Text("y".repeat(1_900) + index),
            )
        }
        val result = plan(turn(messages), messages.map { it.id.toString() })

        val denied = result as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("snapshot_limit", denied.code)
        assertTrue(denied.message.contains("20000"))
    }

    @Test
    fun `oversized snapshot json is rejected before writing`() {
        // 10 * 2,000 Chinese characters is exactly 20,000 characters (within the character cap)
        // but about 60 KB of UTF-8 JSON, over the 32 KB snapshot limit.
        val messages = (0 until 10).map { index ->
            message(
                messageId(index),
                MessageRole.USER,
                UIMessagePart.Text("汉".repeat(2_000)),
            )
        }
        val result = plan(turn(messages), messages.map { it.id.toString() })

        val denied = result as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("snapshot_limit", denied.code)
        assertTrue(denied.message.contains("32"))
    }

    // ------------------------------------------------------------------- arguments

    @Test
    fun `argument parser rejects duplicates malformed ids and identity keys`() {
        assertCode("duplicate_message_id") {
            LocalMomentsChatDraftArgs.parse(obj("messageIds" to listOf(user1, user1)))
        }
        assertCode("invalid_message_id") {
            LocalMomentsChatDraftArgs.parse(obj("messageIds" to listOf("not-a-uuid")))
        }
        assertCode("invalid_message_id") {
            // Uppercase UUIDs are canonicalized by Uuid.parse but are not the canonical wire form.
            LocalMomentsChatDraftArgs.parse(obj("messageIds" to listOf(uppercaseUuid)))
        }
        assertCode("identity_not_allowed") {
            LocalMomentsChatDraftArgs.parse(
                buildJsonObject {
                    put("messageIds", JsonArray(listOf(JsonPrimitive(user1))))
                    put("conversationId", otherConversationId)
                },
            )
        }
        assertCode("identity_not_allowed") {
            LocalMomentsChatDraftArgs.parse(
                buildJsonObject {
                    put("messageIds", JsonArray(listOf(JsonPrimitive(user1))))
                    put("assistantId", otherAssistantId)
                },
            )
        }
        assertCode("unknown_parameter") {
            LocalMomentsChatDraftArgs.parse(
                buildJsonObject {
                    put("messageIds", JsonArray(listOf(JsonPrimitive(user1))))
                    put("cursor", "x")
                },
            )
        }
        assertCode("no_messages") {
            LocalMomentsChatDraftArgs.parse(obj("messageIds" to emptyList<String>()))
        }
        assertCode("invalid_argument") {
            LocalMomentsChatDraftArgs.parse(obj("messageIds" to "not-an-array"))
        }
        assertCode("too_many_messages") {
            LocalMomentsChatDraftArgs.parse(
                obj("messageIds" to (0 until 51).map { messageId(it) }),
            )
        }
    }

    @Test
    fun `argument parser accepts a canonical id list`() {
        val parsed = LocalMomentsChatDraftArgs.parse(obj("messageIds" to listOf(user1, user2)))

        assertEquals(listOf(user1, user2), parsed)
    }

    // ---------------------------------------------------------------------- ACL

    @Test
    fun `draft needs install enable write chat snapshot and task opt in`() {
        assertTrue(access().allowed)

        assertEquals("plugin_not_installed", access(installed = false).denial?.code)
        assertEquals("plugin_disabled", access(enabled = false).denial?.code)
        assertEquals("plugin_disabled", access(loadError = "bad").denial?.code)
        assertEquals(
            "write_not_granted",
            access(grants = setOf("localdata.chat_snapshot")).denial?.code,
        )
        assertEquals(
            "chat_snapshot_not_granted",
            access(grants = setOf("localdata.write")).denial?.code,
        )
        assertEquals("headless_call", access(headless = true).denial?.code)
        assertEquals("assistant_identity_missing", access(assistant = null).denial?.code)
        assertEquals("assistant_identity_missing", access(assistant = "bad/id").denial?.code)
        assertEquals("assistant_mismatch", access(turnAssistant = otherAssistantId).denial?.code)
        assertEquals("assistant_unknown", access(assistantKnown = false).denial?.code)
        assertEquals(
            "assistant_tasks_disabled",
            access(tasks = taskSettings(enabled = false)).denial?.code,
        )
        assertEquals(
            "assistant_tasks_stopped",
            access(tasks = taskSettings(globalStop = true)).denial?.code,
        )
        assertEquals(
            "assistant_not_participant",
            access(tasks = taskSettings(participant = otherAssistantId)).denial?.code,
        )
        assertEquals(
            "assistant_policy_disabled",
            access(tasks = taskSettings(policy = LocalAssistantPolicy.DISABLED)).denial?.code,
        )
    }

    @Test
    fun `draft is bound to the current conversation and user selection`() {
        assertEquals("conversation_identity_missing", access(conversation = null).denial?.code)
        assertEquals(
            "conversation_identity_missing",
            access(conversation = "not-a-uuid").denial?.code,
        )
        assertEquals(
            "conversation_mismatch",
            access(turnConversation = otherConversationId).denial?.code,
        )
        assertEquals("no_user_selection", access(selection = false).denial?.code)
    }

    @Test
    fun `images need the media grant and the image switch`() {
        val grants = setOf("localdata.write", "localdata.chat_snapshot", "localdata.media")

        val allowed = access(grants = grants, tasks = taskSettings(allowImages = true), images = true)
        assertTrue(allowed.allowed)
        assertTrue(allowed.canUseImages)
        assertNull(allowed.imageOmissionReason)

        val switchOff = access(
            grants = grants,
            tasks = taskSettings(allowImages = false),
            images = true,
        )
        assertTrue(switchOff.allowed)
        assertFalse(switchOff.canUseImages)
        assertEquals("image_draft_not_allowed", switchOff.imageOmissionReason)

        val noGrant = access(
            grants = setOf("localdata.write", "localdata.chat_snapshot"),
            tasks = taskSettings(allowImages = true),
            images = true,
        )
        assertTrue(noGrant.allowed)
        assertFalse(noGrant.canUseImages)
        assertEquals("media_not_granted", noGrant.imageOmissionReason)

        val notRequested = access(
            grants = grants,
            tasks = taskSettings(allowImages = true),
            images = false,
        )
        assertTrue(notRequested.allowed)
        assertFalse(notRequested.canUseImages)
        assertNull(notRequested.imageOmissionReason)
    }

    // ------------------------------------------------------------------ visibility

    @Test
    fun `selected visibility requires a non empty unique safe assistant list`() {
        val messages = listOf(message(user1, MessageRole.USER, UIMessagePart.Text("x")))

        val empty = plan(
            turn(messages, visibility = PostVisibility.SELECTED),
            listOf(user1),
        ) as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("visibility_selection_required", empty.code)

        val duplicate = plan(
            turn(
                messages,
                visibility = PostVisibility.SELECTED,
                selectedAssistantIds = listOf(otherAssistantId, otherAssistantId),
            ),
            listOf(user1),
        ) as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("duplicate_selected_assistant", duplicate.code)

        val invalid = plan(
            turn(
                messages,
                visibility = PostVisibility.SELECTED,
                selectedAssistantIds = listOf("bad/id"),
            ),
            listOf(user1),
        ) as LocalMomentsChatDraftPlanResult.Denied
        assertEquals("invalid_selected_assistant", invalid.code)

        val valid = plan(
            turn(
                messages,
                visibility = PostVisibility.SELECTED,
                selectedAssistantIds = listOf(otherAssistantId),
            ),
            listOf(user1),
        )
        assertTrue(valid is LocalMomentsChatDraftPlanResult.Ready)
    }

    // --------------------------------------------------------------------- output

    @Test
    fun `draft output never claims publication`() {
        assertEquals(LocalDataMethods.DRAFT_SAVE, LocalMomentsChatDraftContract.DRAFT_METHOD)
        assertNotEquals(LocalDataMethods.CREATE, LocalMomentsChatDraftContract.DRAFT_METHOD)

        val messages = listOf(message(user1, MessageRole.USER, UIMessagePart.Text("x")))
        val ready = plan(turn(messages), listOf(user1)) as LocalMomentsChatDraftPlanResult.Ready
        val drafted = LocalMomentsChatDraftOutput.success(
            draft = LocalDraftDto(
                id = "d_test",
                author = LocalAuthorDto(AuthorKind.USER, "local-user", "我"),
                body = "",
                visibility = PostVisibility.SELF,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            plan = ready.plan,
            visibility = PostVisibility.SELF,
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(drafted).jsonObject

        assertEquals(false, json["published"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("d_test", json["draft"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("user", json["draft"]!!.jsonObject["author"]!!.jsonPrimitive.content)
        assertEquals(
            "pending_user_review",
            json["draft"]!!.jsonObject["reviewState"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "moments_drafts",
            json["review"]!!.jsonObject["where"]!!.jsonPrimitive.content,
        )

        val error = kotlinx.serialization.json.Json
            .parseToJsonElement(LocalMomentsChatDraftOutput.error("no_messages", "空"))
            .jsonObject
        assertEquals(false, error["published"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `canonical id validation rejects non canonical forms`() {
        assertTrue(isCanonicalUuid(user1))
        assertFalse(isCanonicalUuid(uppercaseUuid))
        assertFalse(isCanonicalUuid("not-a-uuid"))
        assertFalse(isCanonicalUuid(user1.replace("-", "")))
        assertFalse(isCanonicalUuid(""))
    }

    // --------------------------------------------------------------------- helpers

    private fun message(
        id: String,
        role: MessageRole,
        vararg parts: UIMessagePart,
    ): UIMessage = UIMessage(id = Uuid.parse(id), role = role, parts = parts.toList())

    private fun messageId(index: Int): String =
        "cccccccc-cccc-4ccc-8ccc-" + index.toString().padStart(12, '0')

    private fun turn(
        messages: List<UIMessage>,
        selected: List<String> = messages.map { it.id.toString() },
        includeImages: Boolean = false,
        visibility: PostVisibility = PostVisibility.SELF,
        selectedAssistantIds: Collection<String> = emptyList(),
    ): LocalMomentsChatTurn = LocalMomentsChatTurn(
        conversationId = conversationId,
        assistantId = assistantId,
        title = "测试会话",
        messages = messages,
        selectedMessageIds = selected,
        includeImages = includeImages,
        visibility = visibility,
        selectedAssistantIds = selectedAssistantIds,
    )

    private fun plan(
        turn: LocalMomentsChatTurn,
        ids: List<String>,
        includeImages: Boolean = false,
    ): LocalMomentsChatDraftPlanResult = LocalMomentsChatDraftPlanner.plan(
        turn = turn,
        requestedIds = ids,
        identity = LocalMomentsChatDraftIdentity("小明", "小助手"),
        includeImages = includeImages,
        now = 1L,
    )

    private fun taskSettings(
        enabled: Boolean = true,
        globalStop: Boolean = false,
        allowImages: Boolean = false,
        participant: String = assistantId,
        policy: LocalAssistantPolicy = LocalAssistantPolicy.DRAFT_ONLY,
    ): LocalTaskSettings = LocalTaskSettings(
        enabled = enabled,
        globalStop = globalStop,
        allowImages = allowImages,
        participants = listOf(participant),
        assistantPolicies = mapOf(participant to policy),
    )

    private fun access(
        installed: Boolean = true,
        enabled: Boolean = true,
        loadError: String? = null,
        grants: Set<String> = setOf("localdata.write", "localdata.chat_snapshot"),
        tasks: LocalTaskSettings = taskSettings(),
        assistant: String? = assistantId,
        turnAssistant: String? = assistant,
        assistantKnown: Boolean = true,
        headless: Boolean = false,
        conversation: String? = conversationId,
        turnConversation: String? = conversation,
        selection: Boolean = true,
        images: Boolean = false,
    ): LocalMomentsChatDraftAccess = LocalMomentsChatDraftPolicy.evaluate(
        LocalMomentsChatDraftAccessSnapshot(
            pluginInstalled = installed,
            pluginEnabled = enabled,
            pluginLoadError = loadError,
            grantedCapabilities = grants,
            taskSettings = tasks,
            assistantId = assistant,
            turnAssistantId = turnAssistant,
            assistantKnown = assistantKnown,
            headless = headless,
            conversationId = conversation,
            turnConversationId = turnConversation,
            userSelectionAvailable = selection,
            imagesRequested = images,
        ),
    )

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is List<*> -> put(
                    key,
                    JsonArray(value.map { JsonPrimitive(it as String) }),
                )
            }
        }
    }

    private fun assertCode(expected: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(
            "expected LocalMomentsChatDraftException, got $error",
            error is LocalMomentsChatDraftException,
        )
        assertEquals(expected, (error as LocalMomentsChatDraftException).code)
    }
}
