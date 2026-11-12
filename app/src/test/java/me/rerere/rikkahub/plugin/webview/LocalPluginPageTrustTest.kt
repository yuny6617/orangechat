package me.rerere.rikkahub.plugin.webview

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPluginPageTrustTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyExistingFilesInsideOwnPluginAreTrusted() {
        val root = temporary.newFolder("plugin")
        val page = java.io.File(root, "ui/index.html").apply { parentFile.mkdirs(); writeText("page") }
        val sibling = temporary.newFile("other.html")
        assertTrue(isTrustedLocalPluginPage(page.toURI().toString(), root))
        assertFalse(isTrustedLocalPluginPage(sibling.toURI().toString(), root))
        assertFalse(isTrustedLocalPluginPage(java.io.File(root, "missing.html").toURI().toString(), root))
        assertFalse(isTrustedLocalPluginPage(root.toURI().toString(), root))
        assertFalse(isTrustedLocalPluginPage("https://example.org/", root))
        assertFalse(isTrustedLocalPluginPage("about:blank", root))
        assertFalse(isTrustedLocalPluginPage("file://evil/index.html", root))
        assertFalse(isTrustedLocalPluginPage(null, root))
        assertFalse(isTrustedLocalPluginPage(page.toURI().toString() + "?redirect=https://example.org", root))
        val escape = root.toURI().toString() + "../other.html"
        assertFalse(isTrustedLocalPluginPage(escape, root))
        assertTrue(isTrustedLocalPluginPage(page.toURI().toString() + "#compose", root))
        assertFalse(isTrustedLocalPluginPage("file://user@localhost" + page.absolutePath, root))
        assertFalse(isTrustedLocalPluginPage("file:///android_asset/index.html", root))
        val adjacent = temporary.newFolder("plugin-other")
        val adjacentPage = java.io.File(adjacent, "index.html").apply { writeText("other") }
        assertFalse(isTrustedLocalPluginPage(adjacentPage.toURI().toString(), root))
    }
}
