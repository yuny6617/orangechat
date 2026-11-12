package me.rerere.rikkahub.plugin.webview

import java.io.File
import java.net.URI

/** New privileged local-data APIs are available only to the plugin's own local page. */
internal fun isTrustedLocalPluginPage(pageUrl: String?, pluginDirectory: File): Boolean = runCatching {
    if (pageUrl.isNullOrBlank()) return false
    val uri = URI(pageUrl)
    if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.userInfo != null) return false
    val root = pluginDirectory.canonicalFile
    val file = File(uri.path).canonicalFile
    file.isFile && file.toPath().startsWith(root.toPath()) && file != root
}.getOrDefault(false)

internal fun isLocalDataMediaRequest(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.host.equals("localdata.invalid", ignoreCase = true)
}.getOrDefault(false)

internal const val OVERLAY_ORIGIN = "https://overlay.orangechat.invalid/"

internal fun isTrustedOverlayPage(url: String?, documentUrl: String): Boolean = runCatching {
    val actual = URI(url ?: return false)
    val expected = URI(documentUrl)
    actual.scheme == "https" && actual.host == "overlay.orangechat.invalid" && actual.port == -1 &&
        actual.userInfo == null && actual.query == null && actual.path == expected.path &&
        expected.scheme == "https" && expected.host == actual.host && expected.port == -1
}.getOrDefault(false)

internal fun allowsOverlayBridge(
    mainFrame: Boolean, pageUrl: String?, documentUrl: String, suppliedToken: String?, pageToken: String,
): Boolean = mainFrame && pageToken.isNotBlank() && suppliedToken == pageToken &&
    isTrustedOverlayPage(pageUrl, documentUrl)

internal fun approvedLocalDataCapabilities(declared: Set<String>, approved: Set<String>): Set<String> =
    declared intersect approved
