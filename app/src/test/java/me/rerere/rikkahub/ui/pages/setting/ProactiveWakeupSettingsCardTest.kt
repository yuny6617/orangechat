package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.service.ProactiveWakeup
import me.rerere.rikkahub.data.service.ProactiveWakeupStore
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProactiveWakeupSettingsCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `page displays durable outcomes and cancels only pending agreement`() {
        val context = compose.activity
        context.getSharedPreferences("proactive_wakeups", 0).edit().clear().commit()
        val store = ProactiveWakeupStore(context)
        val assistant = Assistant(name = "南枳")
        val base = ProactiveWakeup(assistant.id.toString(), "pending", System.currentTimeMillis() + 60_000, "晚饭后聊聊")
        val sent = base.copy(conversationId = "sent", token = "sent", reason = "问问今天过得怎么样")
        val failed = base.copy(conversationId = "failed", token = "failed", reason = "睡前说晚安")
        store.put(sent); store.consume(sent); store.finish(sent, "sent")
        store.put(failed); store.consume(failed); store.finish(failed, "failed")
        store.put(base)
        val reschedules = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalDisplaySettings provides DisplaySetting()) {
            MaterialTheme {
                Surface {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        ProactiveWakeupSettingsCard(
                            ProactiveMessageSetting(enabled = true, allowAiScheduledWakeup = true),
                            listOf(assistant),
                        ) { reschedules.incrementAndGet() }
                    }
                }
            }
            }
        }
        compose.onNodeWithText("后台活动记录").assertIsDisplayed()
        compose.onNodeWithText("查看主动唤醒记录").performClick()
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("等待执行", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            println("Lifecycle: ${compose.activity.lifecycle.currentState}")
            println("Records: ${store.snapshot()}")
            println(compose.onRoot().printToString())
            throw failure
        }
        compose.onNodeWithText("等待执行", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已发送", substring = true).assertIsDisplayed()
        compose.onNodeWithText("未完成", substring = true).assertIsDisplayed()
        compose.onNodeWithText("晚饭后聊聊", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("取消").assertCountEquals(1)
        val beforeViewing = store.snapshot()
        compose.onNodeWithText("晚饭后聊聊", substring = true).performClick()
        compose.onNodeWithText("后台活动详情").assertIsDisplayed()
        compose.onNodeWithText("约定原因：\n晚饭后聊聊", substring = true).assertExists()
        compose.onNodeWithText("会话 ID：pending", substring = true).assertExists()
        compose.onNodeWithText("关闭").performClick()
        assertEquals(beforeViewing, store.snapshot())
        assertEquals(0, reschedules.get())
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/test-screenshots/proactive-wakeup-history.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("已取消", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("已发送", substring = true).assertExists()
        assertEquals(emptyList<ProactiveWakeup>(), store.all())
        assertEquals(1, reschedules.get())
    }
}
