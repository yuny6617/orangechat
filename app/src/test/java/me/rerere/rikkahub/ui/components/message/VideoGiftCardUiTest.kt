package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.FilesRepository
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.video.caption
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real card and ChatMessage compositions; no call service, persistence or generation integration. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w420dp-h1100dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoGiftCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val gift = UIMessageAnnotation.VideoCallGift(
        id = "reply:custom", giftId = "custom", giftName = "送给你的星河",
        conversationId = "conversation", turnMessageId = "reply", createdAtMillis = 123L,
    )
    private val assistant = Assistant(name = "礼物助手", avatar = Avatar.Emoji("🦊"), useAssistantAvatar = true)

    @Test fun cardStaysBrightWithDarkTextInBothThemesAndHasReadOnlyGroupedSemantics() {
        var dark by mutableStateOf(false)
        var cardInWindow = Rect.Zero
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                VideoCallGiftCard(gift, Modifier.onGloballyPositioned { cardInWindow = it.boundsInWindow() })
            }
        }
        for (darkTheme in listOf(false, true)) {
            compose.runOnIdle { dark = darkTheme }
            val card = compose.onNodeWithTag("video-gift-card")
            card.assertIsDisplayed().assertHasNoClickAction()
                .assertTextContains("视频通话礼物 · 纯虚拟")
                .assertTextContains(gift.caption())
                .assertTextContains("×1")
                .assertTextContains("只存在于这次通话的画面里，没有真实价值")
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(gift.caption(), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(Color(0xFF302A43), layouts.single().layoutInput.style.color)
            // Robolectric cannot satisfy PixelCopy's forced window redraw. Draw the actual
            // activity decor into a native bitmap instead; no replacement card/brush is used.
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val location = IntArray(2).also { view.getLocationInWindow(it) }
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    assertTrue(cardInWindow.width > 0f && cardInWindow.height > 0f)
                    val x = (cardInWindow.left - location[0] + 5f).toInt()
                    val y = (cardInWindow.center.y - location[1]).toInt()
                    val background = Color(bitmap.getPixel(x, y))
                    assertTrue("Gift background must remain bright in either theme", background.red > 0.85f &&
                        background.green > 0.85f && background.blue > 0.90f)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun narrowCardAtLargeFontWrapsEntireCustomNameWithoutHidingQuantity() {
        val longGift = gift.copy(giftName = "送给你独一无二的漫长星河与温柔晚风礼物")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                    Box(Modifier.width(200.dp).testTag("lane")) { VideoCallGiftCard(longGift) }
                }
            }
        }
        val name = compose.onNodeWithText(longGift.caption(), useUnmergedTree = true)
        name.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 1)
        assertFalse(layout.hasVisualOverflow)
        assertEquals(longGift.caption().length, layout.getLineEnd(layout.lineCount - 1))
        val quantity = compose.onNodeWithText("×1", useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("video-gift-card").fetchSemanticsNode().boundsInRoot
        assertTrue(name.fetchSemanticsNode().boundsInRoot.right <= quantity.left)
        assertTrue(quantity.right <= card.right)
        assertTrue(card.width <= compose.onNodeWithTag("lane").fetchSemanticsNode().boundsInRoot.width)
    }

    @Test fun realChatMessageReasoningAndGiftHaveOnlyOneSenderInBothModes() {
        var mode by mutableStateOf(ChatDisplayMode.STANDARD)
        val now = kotlin.time.Clock.System.now()
        val reasoning = UIMessagePart.Reasoning("这段思考仍然保留", createdAt = now, finishedAt = now)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning), annotations = listOf(gift))
        compose.setContent { MessageHarness(message, mode) }
        for (displayMode in listOf(ChatDisplayMode.STANDARD, ChatDisplayMode.EVERYDAY)) {
            compose.runOnIdle { mode = displayMode }
            compose.onNodeWithTag("video-gift-card").assertIsDisplayed()
            compose.onAllNodesWithText("🦊", useUnmergedTree = true).assertCountEquals(1)
        }
        compose.onNodeWithText("查看思考和工具记录").assertIsDisplayed()
        assertEquals(listOf(reasoning), message.parts)
        assertEquals(listOf(gift), message.annotations)
    }

    @Test fun realGiftOnlyMessageStartsAtTopWithoutPhantomProcessOrAvatar() {
        var mode by mutableStateOf(ChatDisplayMode.EVERYDAY)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), annotations = listOf(gift))
        compose.setContent { MessageHarness(message, mode) }
        for (displayMode in listOf(ChatDisplayMode.EVERYDAY, ChatDisplayMode.STANDARD)) {
            compose.runOnIdle { mode = displayMode }
            compose.onNodeWithText("查看思考和工具记录").assertDoesNotExist()
            compose.onAllNodesWithText("🦊", useUnmergedTree = true).assertCountEquals(0)
            val card = compose.onNodeWithTag("video-gift-card").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val messageBounds = compose.onNodeWithTag("message").fetchSemanticsNode().boundsInRoot
            assertEquals(messageBounds.top, card.top, 0.5f)
            assertEquals(messageBounds.left, card.left, 0.5f)
        }
        assertTrue(message.parts.isEmpty())
        assertEquals(listOf(gift), message.annotations)
    }

    @Test fun giftOnlyLoadingStillShowsReplyProgress() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), annotations = listOf(gift))
        compose.setContent { MessageHarness(message, ChatDisplayMode.EVERYDAY, loading = true) }
        compose.onNodeWithText("正在回复…").assertIsDisplayed()
        compose.onNodeWithTag("video-gift-card").assertIsDisplayed()
        compose.onAllNodesWithText("🦊", useUnmergedTree = true).assertCountEquals(1)
    }

    /** Only the unrelated TTS dependency is inert; message/card/avatar rendering is production code. */
    private class SilentTts : CustomTtsState {
        override val isAvailable = MutableStateFlow(false)
        override val isSpeaking = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val currentChunk = MutableStateFlow(0)
        override val totalChunks = MutableStateFlow(0)
        override val playbackState = MutableStateFlow(PlaybackState())
        override fun speak(text: String, flushCalled: Boolean) = Unit
        override fun stop() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun skipNext() = Unit
        override fun fastForward(ms: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun cleanup() = Unit
        override fun enqueueText(text: String) = Unit
    }

    @Composable
    private fun MessageHarness(message: UIMessage, mode: ChatDisplayMode, loading: Boolean = false) {
        val display = DisplaySetting(chatDisplayMode = mode, showModelIcon = true)
        val scope = remember { AppScope() }
        DisposableEffect(scope) { onDispose { scope.cancel() } }
        val files = remember {
            // UIAvatar injects FilesManager even for a read-only emoji. No storage call is
            // expected; fail loudly if this UI test starts reaching the database.
            val dao = Proxy.newProxyInstance(ManagedFileDAO::class.java.classLoader,
                arrayOf(ManagedFileDAO::class.java)) { _, _, _ ->
                error("Read-only gift/avatar rendering must not query storage")
            } as ManagedFileDAO
            FilesManager(compose.activity, FilesRepository(dao), scope)
        }
        KoinApplication(application = { modules(module {
            single { AppEventBus() }
            single { files }
        }) }) {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDisplaySettings provides display,
                    LocalSettings provides Settings(displaySetting = display),
                    LocalNavController provides Navigator(mutableListOf()),
                    LocalTTSState provides remember { SilentTts() },
                ) {
                    ChatMessage(
                        node = MessageNode(messages = listOf(message)), assistant = assistant,
                        modifier = Modifier.testTag("message"), loading = loading,
                        onFork = {}, onRegenerate = {}, onEdit = {}, onShare = {}, onDelete = {}, onUpdate = {},
                    )
                }
            }
        }
    }
}
