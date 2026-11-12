package me.rerere.rikkahub.plugin.webview

import org.junit.Assert.*
import org.junit.Test

class LocalDataWebBridgePolicyTest {
    @Test fun manifestDeclarationsNeverGrantThemselves() {
        assertTrue(approvedLocalDataCapabilities(setOf("localdata.read", "localdata.media"), emptySet()).isEmpty())
    }

    @Test fun updatesRequireApprovalForNewCapabilities() {
        assertEquals(setOf("localdata.read"), approvedLocalDataCapabilities(
            setOf("localdata.read", "localdata.write"), setOf("localdata.read"),
        ))
    }

    @Test fun removedDeclarationsDoNotRetainOldAuthority() {
        assertEquals(setOf("localdata.read"), approvedLocalDataCapabilities(
            setOf("localdata.read"), setOf("localdata.read", "localdata.backup"),
        ))
    }

    @Test fun reservedMediaOriginIsAlwaysInterceptedIncludingMalformedVariants() {
        assertTrue(isLocalDataMediaRequest("https://localdata.invalid/media/ldm_12345678/thumb"))
        assertTrue(isLocalDataMediaRequest("http://localdata.invalid/media/ldm_12345678/thumb"))
        assertTrue(isLocalDataMediaRequest("https://localdata.invalid:8443/not-a-resource"))
        assertFalse(isLocalDataMediaRequest("https://localdata.invalid.attacker.test/media/x/thumb"))
        assertFalse(isLocalDataMediaRequest("https://attacker.test/localdata.invalid"))
    }
}
