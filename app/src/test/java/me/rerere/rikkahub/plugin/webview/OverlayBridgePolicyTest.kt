package me.rerere.rikkahub.plugin.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBridgePolicyTest {
    private val document = "${OVERLAY_ORIGIN}random-page/index.html"
    private val token = "opaque-random-token"

    @Test fun onlyTrustedMainDocumentWithCurrentTokenCanCallBridge() {
        assertTrue(allowsOverlayBridge(true, document, document, token, token))
        assertFalse(allowsOverlayBridge(false, document, document, token, token))
        assertFalse(allowsOverlayBridge(true, "https://evil.example/", document, token, token))
        assertFalse(allowsOverlayBridge(true, document, document, "wrong", token))
        assertFalse(allowsOverlayBridge(true, document, document, null, token))
    }


    @Test fun tokenExpiresOnReloadAndOriginCannotBeSpoofed() {
        assertFalse(allowsOverlayBridge(true, document, document, "", ""))
        assertFalse(isTrustedOverlayPage("https://overlay.orangechat.invalid.evil.example/random-page/index.html", document))
        assertFalse(isTrustedOverlayPage("https://user@overlay.orangechat.invalid/random-page/index.html", document))
        assertFalse(isTrustedOverlayPage("https://overlay.orangechat.invalid:444/random-page/index.html", document))
        assertFalse(isTrustedOverlayPage("https://overlay.orangechat.invalid/other/index.html", document))
        assertTrue(isTrustedOverlayPage(document, document))
    }
}
