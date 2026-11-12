/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.service.buildToolInvocationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-4: the LocalMoments read budget belongs to the user turn, so rebuilding the tool surface
 * after a tool approval must reuse the same counters. R2-3: the turn's actual model (conversation
 * override included) is the only source of image capability.
 */
class LocalMomentsTurnBudgetTest {
    private val assistantId = "11111111-1111-1111-1111-111111111111"

    // --------------------------------------------------------- R2-4 budget scope

    @Test
    fun `approval rebuild of the same user turn reuses the exhausted budget`() {
        val registry = LocalMomentsBudgetRegistry()
        val beforeApproval = registry.budgetFor(conversationId = "conv", anchorUserId = "user-1")

        // Exhaust the page allowance (4 pages) and then the call allowance (6 calls).
        repeat(LocalMomentsLimits.MAX_PAGES_PER_TURN) {
            assertNull(beforeApproval.beginCall(LocalMomentsCallKind.LIST))
        }
        assertEquals(
            "page_budget_exhausted",
            beforeApproval.beginCall(LocalMomentsCallKind.LIST)?.code,
        )
        repeat(LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN - LocalMomentsLimits.MAX_PAGES_PER_TURN) {
            assertNull(beforeApproval.beginCall(LocalMomentsCallKind.GET))
        }

        // handleMessageComplete() after the approval rebuilds every tool. The newest USER message
        // is unchanged, so ChatService asks the registry for the same budget object.
        val afterApproval = registry.budgetFor(conversationId = "conv", anchorUserId = "user-1")

        assertSame(beforeApproval, afterApproval)
        assertEquals(
            "tool_call_budget_exhausted",
            afterApproval.beginCall(LocalMomentsCallKind.GET)?.code,
        )
        assertEquals(LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN, afterApproval.calls)
        assertEquals(LocalMomentsLimits.MAX_PAGES_PER_TURN, afterApproval.pages)
    }

    @Test
    fun `build helper budget resolution reuses the registry budget for the same conversation turn`() {
        val registry = LocalMomentsBudgetRegistry()
        val invocationContext = ToolInvocationContext(
            callerConversationId = "conv",
            callerAssistantId = assistantId,
        )
        val firstBuildBudget = localMomentsTurnBudget(registry, invocationContext, "user-1")
        assertNotNull(firstBuildBudget)
        assertNull(firstBuildBudget!!.beginCall(LocalMomentsCallKind.LIST))

        // This is the exact resolver used inside buildLocalMomentsTools on approval rebuild.
        val approvalRebuildBudget = localMomentsTurnBudget(registry, invocationContext, "user-1")
        assertSame(firstBuildBudget, approvalRebuildBudget)
        assertEquals(1, approvalRebuildBudget!!.calls)

        val nextTurnBudget = localMomentsTurnBudget(registry, invocationContext, "user-2")
        assertNotSame(firstBuildBudget, nextTurnBudget)
        assertEquals(0, nextTurnBudget!!.calls)
    }

    @Test
    fun `build helper does not consult registry without host conversation and turn anchor`() {
        val registry = LocalMomentsBudgetRegistry()
        assertNull(localMomentsTurnBudget(
            registry,
            ToolInvocationContext(callerConversationId = "conv"),
            anchorUserId = null,
        ))
        assertNull(localMomentsTurnBudget(
            registry,
            ToolInvocationContext.EMPTY,
            anchorUserId = "user-1",
        ))
        assertEquals(0, registry.trackedConversations())
    }

    @Test
    fun `a new user message starts a fresh budget and drops the old one`() {
        val registry = LocalMomentsBudgetRegistry()
        val firstTurn = registry.budgetFor(conversationId = "conv", anchorUserId = "user-1")
        repeat(LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN) {
            assertNull(firstTurn.beginCall(LocalMomentsCallKind.GET))
        }

        val secondTurn = registry.budgetFor(conversationId = "conv", anchorUserId = "user-2")

        assertNotSame(firstTurn, secondTurn)
        assertEquals(0, secondTurn.calls)
        assertNull(secondTurn.beginCall(LocalMomentsCallKind.GET))
        assertEquals(1, registry.trackedConversations())
    }

    @Test
    fun `conversations keep independent budgets for their own user turns`() {
        val registry = LocalMomentsBudgetRegistry()
        val first = registry.budgetFor(conversationId = "a", anchorUserId = "user-1")
        val second = registry.budgetFor(conversationId = "b", anchorUserId = "user-1")

        repeat(LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN) {
            assertNull(first.beginCall(LocalMomentsCallKind.GET))
        }

        assertEquals("tool_call_budget_exhausted", first.beginCall(LocalMomentsCallKind.GET)?.code)
        assertNull(second.beginCall(LocalMomentsCallKind.GET))
    }

    @Test
    fun `registry is bounded and evicts the least recently used conversation`() {
        val registry = LocalMomentsBudgetRegistry(maxConversations = 2)
        registry.budgetFor(conversationId = "a", anchorUserId = "user-1")
        registry.budgetFor(conversationId = "b", anchorUserId = "user-1")
        registry.budgetFor(conversationId = "c", anchorUserId = "user-1")

        assertEquals(2, registry.trackedConversations())
        // "a" was the eldest and is gone; asking again must not resurrect its old counters.
        val fresh = registry.budgetFor(conversationId = "a", anchorUserId = "user-1")
        assertEquals(0, fresh.calls)
        assertEquals(2, registry.trackedConversations())
    }

    @Test
    fun `deleted conversation drops its budget`() {
        val registry = LocalMomentsBudgetRegistry()
        val before = registry.budgetFor(conversationId = "conv", anchorUserId = "user-1")
        repeat(LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN) {
            assertNull(before.beginCall(LocalMomentsCallKind.GET))
        }

        registry.remove("conv")

        val after = registry.budgetFor(conversationId = "conv", anchorUserId = "user-1")
        assertNotSame(before, after)
        assertNull(after.beginCall(LocalMomentsCallKind.GET))
    }

    // --------------------------------------------- R2-3 conversation model override

    @Test
    fun `tool context takes image vision from the conversation override not the assistant default`() {
        val textOnly = Model(modelId = "text-only", inputModalities = listOf(Modality.TEXT))
        val vision = Model(
            modelId = "vision",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        val assistant = Assistant(chatModelId = textOnly.id, name = "assistant")
        val settings = Settings.dummy().copy(
            assistantId = assistant.id,
            assistants = listOf(assistant),
            chatModelId = textOnly.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(textOnly, vision))),
        )
        val overridden = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = vision.id),
        )

        val context = buildToolInvocationContext(
            settings = settings,
            assistant = assistant,
            conversationId = overridden.id,
            conversation = overridden,
            headless = false,
        )

        assertTrue(context.modelCanSeeImages)
        assertTrue(evaluateAttach(context.modelCanSeeImages, assistant).canAttachImages)

        // The reverse override (assistant default can see images, conversation switched to text)
        // must not grant image bytes either.
        val textConversation = overridden.copy(config = ConversationConfig(chatModelId = textOnly.id))
        val textContext = buildToolInvocationContext(
            settings = settings,
            assistant = assistant.copy(chatModelId = vision.id),
            conversationId = textConversation.id,
            conversation = textConversation,
            headless = false,
        )

        assertFalse(textContext.modelCanSeeImages)
        assertFalse(evaluateAttach(textContext.modelCanSeeImages, assistant).canAttachImages)
    }

    @Test
    fun `snapshot image capability comes from the turn context only`() {
        // The assistant default is deliberately absent from localMomentsAccessSnapshot: the turn
        // context already carries the model ChatService will actually call. A context that says
        // "vision" must therefore survive the snapshot even when the assistant default is text.
        val context = ToolInvocationContext(
            callerAssistantId = assistantId,
            callerConversationId = "conv",
            modelCanSeeImages = true,
        )
        val snapshot = localMomentsAccessSnapshot(
            invocationContext = context,
            pluginInstalled = true,
            pluginEnabled = true,
            pluginLoadError = null,
            grantedCapabilities = setOf("localdata.read", "localdata.media"),
            taskSettings = LocalTaskSettings(
                enabled = true,
                participants = listOf(assistantId),
                allowImages = true,
            ).normalized(),
            assistantId = assistantId,
            assistantKnown = true,
        )

        assertTrue(snapshot.modelCanSeeImages)
        assertTrue(LocalMomentsAccessPolicy.evaluate(snapshot).canAttachImages)
    }

    @Test
    fun `an unknown caller keeps the conservative no-vision default`() {
        assertFalse(ToolInvocationContext().modelCanSeeImages)
        assertFalse(ToolInvocationContext.EMPTY.modelCanSeeImages)
        assertFalse(
            evaluateAttach(
                ToolInvocationContext.EMPTY.modelCanSeeImages,
                Assistant(name = "assistant"),
            ).canAttachImages,
        )
    }

    private fun evaluateAttach(
        modelCanSeeImages: Boolean,
        assistant: Assistant,
    ): LocalMomentsAccess = LocalMomentsAccessPolicy.evaluate(
        LocalMomentsAccessSnapshot(
            pluginInstalled = true,
            pluginEnabled = true,
            grantedCapabilities = setOf("localdata.read", "localdata.media"),
            taskSettings = LocalTaskSettings(
                enabled = true,
                participants = listOf(assistant.id.toString()),
                allowImages = true,
            ).normalized(),
            assistantId = assistant.id.toString(),
            assistantKnown = true,
            headless = false,
            modelCanSeeImages = modelCanSeeImages,
        ),
    )
}
