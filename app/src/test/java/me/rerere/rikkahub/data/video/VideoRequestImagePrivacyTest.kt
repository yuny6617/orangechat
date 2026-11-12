package me.rerere.rikkahub.data.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class VideoRequestImagePrivacyTest {
    private val camera = "file:///data/user/0/orange/files/video_call_clips/clip.jpg"
    private val other = "file:///data/user/0/orange/files/uploads/normal.jpg"

    @Test fun historicalCameraImagesAreRemovedButOtherImagesAndSourceRemain() {
        val message = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Text("hello"), UIMessagePart.Image(camera), UIMessagePart.Image(other),
        ))
        val cleaned = listOf(message).withoutHistoricalCameraImages().single()
        assertEquals(listOf(other), cleaned.parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
        assertEquals(2, message.parts.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(isVideoCameraResource("file:///data/user/0/orange/files/video_call_snapshots/photo.jpg"))
        assertTrue(isVideoCameraResource("file:///data/user/0/orange/cache/video_call_frames/frame.jpg"))
        assertFalse(isVideoCameraResource(other))
        assertFalse(isVideoCameraResource("https://example.test/video_call_clips/clip.jpg"))
    }

    @Test fun metadataEncodedCameraAndNestedToolMediaAreRemovedBeforeTransforms() {
        val data = "data:image/jpeg;base64,Y2FtZXJh"
        val metadata = JsonObject(mapOf("provider" to JsonArray(listOf(JsonObject(mapOf(
            "source" to JsonPrimitive(camera), "data" to JsonPrimitive(data),
        ))))))
        val signature = JsonObject(mapOf("thought_signature" to JsonPrimitive("keep-signature")))
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("thinking", metadata = metadata),
            UIMessagePart.Tool("call", "camera", "{}", metadata = metadata, output = listOf(
                UIMessagePart.Image(data, metadata),
                UIMessagePart.Tool("nested", "read", "{}", output = listOf(
                    UIMessagePart.Image(camera), UIMessagePart.Image(other, signature),
                    UIMessagePart.Image(data), UIMessagePart.Document(other, "normal.jpg"),
                )),
            )),
        ))
        val result = listOf(source).withoutHistoricalCameraImages().single()
        assertNull(result.parts.first().metadata)
        val tool = result.parts[1] as UIMessagePart.Tool
        assertNull(tool.metadata)
        val nested = tool.output.single() as UIMessagePart.Tool
        assertEquals(listOf(other, data), nested.output.filterIsInstance<UIMessagePart.Image>().map { it.url })
        assertEquals(signature, nested.output.first().metadata)
        assertTrue(nested.output.last() is UIMessagePart.Document)
        // Sanitizing request metadata must never mutate the durable history snapshot.
        assertEquals(metadata, source.parts.first().metadata)
        assertEquals(metadata, (source.parts[1] as UIMessagePart.Tool).metadata)
    }

    @Test fun cameraIsRemovedBeforeEncodingCanEraseItsLocalUrl() {
        val source = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Text("look"), UIMessagePart.Image(camera), UIMessagePart.Image(other),
        ))
        val encodedUrls = mutableListOf<String>()
        val request = listOf(source).withoutHistoricalCameraImages().map { message ->
            message.copy(parts = message.parts.map { part ->
                if (part is UIMessagePart.Image) {
                    encodedUrls += part.url
                    part.copy(url = "data:image/jpeg;base64,b3JkaW5hcnk=")
                } else part
            })
        }.withoutHistoricalCameraImages()
        assertEquals(listOf(other), encodedUrls)
        assertEquals(1, request.single().parts.filterIsInstance<UIMessagePart.Image>().size)
        assertEquals(2, source.parts.filterIsInstance<UIMessagePart.Image>().size)
    }

    @Test fun wrongRoleMissingAndDuplicateAnchorsNeverBorrowAnotherUser() {
        val user = UIMessage.user("ordinary user")
        val assistant = UIMessage.assistant("not an anchor")
        val request = listOf(user, assistant)
        assertEquals(request, request.withAuthorizedVideoImages(listOf(camera), assistant.id.toString(), true))
        assertEquals(request, request.withAuthorizedVideoImages(listOf(camera), "missing", true))
        val duplicated = listOf(user, user.copy(parts = listOf(UIMessagePart.Text("duplicate"))))
        assertEquals(duplicated, duplicated.withAuthorizedVideoImages(listOf(camera), user.id.toString(), true))
    }

    @Test fun revocationAfterPreparationDropsAnonymousDataUriOnEveryToolRound() {
        val user = UIMessage.user("look")
        val ordinaryData = "data:image/png;base64,b3JkaW5hcnk="
        val clean = listOf(user.copy(parts = user.parts + UIMessagePart.Image(ordinaryData)))
        val cameraData = "data:image/jpeg;base64,Y2FtZXJh"
        var granted = true
        var checks = 0
        val authorization = VideoRequestImageAuthorization(listOf(cameraData), user.id.toString()) {
            checks++
            granted
        }
        val first = authorization.applyTo(clean)
        assertEquals(listOf(ordinaryData, cameraData), first.single().parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
        granted = false // Camera closes/hang-up happens while the prepared request waits to send.
        val second = authorization.applyTo(clean)
        assertEquals(clean, second)
        val nextToolRound = second + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("call", "lookup", "{}", output = listOf(UIMessagePart.Text("done"))),
        ))
        assertEquals(nextToolRound, authorization.applyTo(nextToolRound))
        assertEquals(3, checks)
        assertEquals(listOf(ordinaryData), clean.single().parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
    }

    @Test fun noCameraImageWithoutExactUserAnchorAndActiveGrant() {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))
        val request = listOf(user)
        assertEquals(request, request.withAuthorizedVideoImages(listOf(camera), user.id.toString(), false))
        assertEquals(request, request.withAuthorizedVideoImages(listOf(camera), null, true))
        assertEquals(request, request.withAuthorizedVideoImages(listOf(camera), "different", true))
        val granted = request.withAuthorizedVideoImages(listOf(camera, camera), user.id.toString(), true)
        assertEquals(listOf(camera), granted.single().parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
        assertTrue(user.parts.none { it is UIMessagePart.Image })
    }

    // ------------------------------------------------------------------
    // REVIEW_ROUND1_CODE #2：图片被隐私过滤、system prompt 却仍说"本轮附带了画面"
    //
    // 授权点必须在**同一次**活判断里同时决定"插不插图"和"提示词怎么说"：
    // 暂停恢复（token 被清空）、provider 调用前挂断、相机换代、文件校验失败，
    // 都会让本轮实际交付 0 张图，提示词因此必须改口。
    // ------------------------------------------------------------------

    private fun attachedContext(): String =
        VideoCallPrompts.turnContext(VideoCallContext(cameraAvailable = true, pictureAttached = true))

    private fun noPictureContext(): String =
        VideoCallPrompts.turnContext(VideoCallContext(cameraAvailable = true, pictureAttached = false))

    @Test fun pictureClaimBlockIsPinnedToTheRealVideoPrompt() {
        // 撤回用的锚点只能盯住真实 turnContext() 输出：措辞一改，这里立刻红。
        val attached = attachedContext()
        assertTrue(attached.startsWith(VIDEO_PICTURE_CLAIM_BLOCK))
        assertEquals(VIDEO_PICTURE_ABSENT_BLOCK, VideoCallPrompts.SECTION_TITLE + "\n- " + VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE)
        assertFalse(attached.contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))
        val absent = noPictureContext()
        assertFalse(absent.contains(VIDEO_PICTURE_CLAIM_BLOCK))
        assertFalse(absent.contains(VIDEO_PICTURE_ATTACHED_SENTENCE))
    }

    @Test fun deliveredImageKeepsTheClaimAndARevokedGrantRetractsIt() {
        val context = attachedContext()
        val user = UIMessage.user("look")
        val prepared = listOf(
            UIMessage.system(context),
            user,
        ).markVideoTurnSystemContext(context)
        var granted = true
        val authorization = VideoRequestImageAuthorization(listOf(camera), user.id.toString()) { granted }

        val delivered = authorization.applyTo(prepared)
        assertEquals(
            listOf(camera),
            delivered.single { it.role == MessageRole.USER }.parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
        )
        assertTrue(delivered.first().toText().contains(VIDEO_PICTURE_CLAIM_BLOCK))
        assertFalse(delivered.first().toText().contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))

        // Hang-up / camera epoch rotation lands between preparation and the provider call.
        granted = false
        val retracted = authorization.applyTo(prepared)
        assertTrue(retracted.none { message -> message.parts.any { it is UIMessagePart.Image } })
        val system = retracted.first().toText()
        assertFalse("不能再声称本轮有画面", system.contains(VIDEO_PICTURE_ATTACHED_SENTENCE))
        assertTrue(system.contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))
        // The already-prepared request is not mutated in place.
        assertTrue(prepared.first().toText().contains(VIDEO_PICTURE_CLAIM_BLOCK))
    }

    @Test fun zeroInsertedImagesRetractTheClaimEvenWhenTheGrantLooksAlive() {
        // Anchor missing / validAttachments rejected every file: grant says yes, 0 images delivered.
        val context = attachedContext()
        val user = UIMessage.user("look")
        val prepared = listOf(UIMessage.system(context), user).markVideoTurnSystemContext(context)
        val authorization = VideoRequestImageAuthorization(listOf(camera), "missing-anchor") { true }
        val sent = authorization.applyTo(prepared)
        assertTrue(sent.none { message -> message.parts.any { it is UIMessagePart.Image } })
        assertFalse(sent.first().toText().contains(VIDEO_PICTURE_ATTACHED_SENTENCE))
        assertTrue(sent.first().toText().contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))

        // No camera URLs at all (paused queue item resumed later cannot re-collect them).
        val emptyUrls = VideoRequestImageAuthorization(emptyList(), user.id.toString()) { false }
        assertTrue(
            emptyUrls.applyTo(prepared).first().toText().contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE),
        )
    }

    @Test fun claimSyncNeverRewritesUserAuthoredText() {
        val userText = "我自己的话：" + VIDEO_PICTURE_CLAIM_BLOCK
        val user = UIMessage.user(userText)
        val assistant = UIMessage.assistant(VIDEO_PICTURE_CLAIM_BLOCK)
        val system = UIMessage.system("我的助手设定：本轮附带了当前相机画面。")
        val request = listOf(system, user, assistant)
        val sent = request.synchronizeVideoPictureClaim(false)
        assertEquals(request, sent)
        // Even a system prompt quoting only the sentence (not our generated paragraph) stays intact.
        assertEquals(userText, sent[1].toText())
    }

    @Test fun resumedTokenlessRequestLosesTheClaimFoldedIntoTheSystemPrompt() {
        // Paused -> resumed: ChatService folds the queue prompt into conversationSystemPrompt and
        // the token is gone, so GenerationHandler has no request-scoped part to mark.
        val folded = UIMessage.system("assistant prompt\n\n" + noPictureContext() + "\n\n" + attachedContext())
        val queued = listOf(UIMessagePart.Text("hi"), UIMessagePart.Image(camera))
        val request = listOf(
            folded,
            UIMessage(role = MessageRole.USER, parts = queued),
        ).withoutHistoricalCameraImages()
        val authorization = VideoRequestImageAuthorization(emptyList(), "any-id") { false }
        val sent = authorization.applyTo(request)
        assertTrue(sent.none { message -> message.parts.any { it is UIMessagePart.Image } })
        val system = sent.first().toText()
        assertFalse(system.contains(VIDEO_PICTURE_ATTACHED_SENTENCE))
        assertTrue(system.contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))
    }

    @Test fun everyProviderBoundaryCallReevaluatesTheGrantForImagesAndPrompt() = runBlocking {
        val context = attachedContext()
        val user = UIMessage.user("look")
        val prepared = listOf(UIMessage.system(context), user).markVideoTurnSystemContext(context)
        var granted = false
        val provider = CapturingProvider()
        val wrapped = provider.withVideoRequestAuthorization(
            VideoRequestImageAuthorization(listOf(camera), user.id.toString()) { granted },
        )
        wrapped.streamText(ProviderSetting.OpenAI(), prepared, TextGenerationParams(Model())).collect { }
        // Hang-up before the streaming request is collected: no image, and no picture claim.
        assertEquals(1, provider.requests.size)
        val revoked = provider.requests.single()
        assertTrue(revoked.none { message -> message.parts.any { it is UIMessagePart.Image } })
        assertFalse(revoked.first().toText().contains(VIDEO_PICTURE_ATTACHED_SENTENCE))
        assertTrue(revoked.first().toText().contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))

        // A later call with a live grant delivers image *and* claim together.
        granted = true
        wrapped.generateText(ProviderSetting.OpenAI(), prepared, TextGenerationParams(Model()))
        val delivered = provider.requests.last()
        assertTrue(delivered.any { message -> message.parts.any { it is UIMessagePart.Image && it.url == camera } })
        assertTrue(delivered.first().toText().contains(VIDEO_PICTURE_CLAIM_BLOCK))
        assertFalse(delivered.first().toText().contains(VideoCallPrompts.NO_PICTURE_THIS_TURN_NOTE))
    }

    private class CapturingProvider : Provider<ProviderSetting> {
        val requests = mutableListOf<List<UIMessage>>()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = error("unused")

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk {
            requests += messages
            return MessageChunk("id", "model", emptyList())
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow { requests += messages }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("unused")
    }
}
