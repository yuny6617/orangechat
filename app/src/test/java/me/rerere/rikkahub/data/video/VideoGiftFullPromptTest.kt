package me.rerere.rikkahub.data.video

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VideoGiftFullPromptTest {
    @Test
    fun `full prompt persists and blank restores complete default`() {
        val old = Json.decodeFromString<VideoCallSetting>("{}")
        assertEquals("", old.giftPrompt)
        assertEquals(VideoCallPrompts.GIFT_INSTRUCTION, VideoCallPrompts.effectiveGiftPrompt(old))
        val custom = old.copy(giftsEnabled = true, giftPrompt = "自定义完整内容\n第二行 <ws_gift id=\"meteor\"/>")
        assertEquals(custom, Json.decodeFromString<VideoCallSetting>(Json.encodeToString(custom)))
        assertEquals(custom.giftPrompt, VideoCallPrompts.effectiveGiftPrompt(custom))
        assertEquals(VideoCallPrompts.GIFT_INSTRUCTION, VideoCallPrompts.effectiveGiftPrompt(custom.copy(giftPrompt = " \n")))
    }

    @Test
    fun `live setting changes reach next context while executable whitelist stays fixed`() {
        fun context(setting: VideoCallSetting) = VideoCallPrompts.turnContext(videoCallContextFromSetting(
            setting, false, "unavailable", false, false, false,
        ))
        val off = VideoCallSetting()
        val enabled = off.copy(giftsEnabled = true, giftPrompt = "随心送礼 id=anything")
        assertTrue(context(off).contains(VideoCallPrompts.GIFT_DISABLED_NOTE))
        assertTrue(context(enabled).contains(enabled.giftPrompt))
        assertFalse(context(enabled).contains(VideoCallPrompts.GIFT_DISABLED_NOTE))
        assertTrue(context(enabled).contains(VideoCallPrompts.giftProtocolBoundary(emptyMap())))
        assertNull(VideoGiftProtocol.firstGift("<ws_gift id=\"anything\"/>"))
        VideoGiftCatalog.all.forEach {
            assertEquals(it, VideoGiftProtocol.firstGift("<ws_gift id=\"${it.id}\"/>"))
        }
        assertTrue(context(enabled.copy(giftsEnabled = false)).contains(VideoCallPrompts.GIFT_DISABLED_NOTE))
    }
}
