package me.rerere.rikkahub.ui.pages.video

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import me.rerere.rikkahub.data.video.VideoGift
import me.rerere.rikkahub.data.video.VideoGiftEffectQueue
import me.rerere.rikkahub.data.video.VideoGiftEffectRequest
import me.rerere.rikkahub.data.video.skipIfMatching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real overlay in a small state/queue harness, not a phone/service integration test. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w420dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoGiftEffectUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun skipHasAccessibleButtonSemanticsAndReportsEachRenderedIdentity() {
        val queue = VideoGiftEffectQueue()
        val first = requireNotNull(queue.offer("first", VideoGift.METEOR_SHOWER))
        val second = requireNotNull(queue.offer("second", VideoGift.METEOR_SHOWER))
        var active by mutableStateOf(queue.poll())
        val clickedKeys = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                VideoGiftEffectOverlay(
                    effect = active,
                    onSkip = { key ->
                        clickedKeys += key
                        active = active.skipIfMatching(key)
                        // Harness advances explicitly; VideoCallPage's coroutine is not under test here.
                        if (active == null) active = queue.poll()
                    },
                )
            }
        }
        compose.onNodeWithContentDescription(SKIP_DESCRIPTION)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf(first.key), clickedKeys)
            assertSame(second, active)
            assertEquals(0, queue.size)
        }
        compose.onNodeWithContentDescription(SKIP_DESCRIPTION)
            .assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(listOf(first.key, second.key), clickedKeys)
            assertNull(active)
        }
        compose.onNodeWithContentDescription(SKIP_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun canvasPassesRealTouchesThroughWhileSkipConsumesOnlyItsOwnTouch() {
        var underlyingClicks = 0
        val skipped = mutableListOf<String>()
        val request = VideoGiftEffectRequest("galaxy#1", "galaxy", VideoGift.GALAXY_TRAIN)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("touch-root")) {
                    Button(
                        onClick = { underlyingClicks++ },
                        modifier = Modifier.align(Alignment.Center).testTag("underlying-control"),
                    ) { Text("Underlying control") }
                    VideoGiftEffectOverlay(request, onSkip = { skipped += it })
                }
            }
        }
        // Inject a physical touch at the root's center, not a semantics click bypassing hit testing.
        compose.onNodeWithTag("touch-root").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, underlyingClicks)
            assertEquals(emptyList<String>(), skipped)
        }
        compose.onNodeWithContentDescription(SKIP_DESCRIPTION).performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(listOf(request.key), skipped)
            assertEquals(1, underlyingClicks)
        }
    }

    private companion object {
        const val SKIP_DESCRIPTION = "跳过当前礼物特效，继续播放队列"
    }
}
