/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import me.rerere.rikkahub.ui.theme.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.files.SafeFileResolver
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginHookConfig
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.effectivePluginConfig
import me.rerere.rikkahub.plugin.repository.PluginRepository
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "PluginWebViewPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginWebViewPage(
    pluginId: String,
    htmlEntryPath: String,
    pluginManager: PluginManager,
    onNavigateBack: () -> Unit,
    conversationId: String = "",
) {
    val context = LocalContext.current
    val pluginLoader = koinInject<PluginLoader>()
    val pluginRepository = koinInject<PluginRepository>()
    val conversationRepository = koinInject<ConversationRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val usageRecorder = koinInject<UsageRecorder>()
    val chatService = koinInject<ChatService>()
    val filesManager = koinInject<FilesManager>()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingImageCallback by remember { mutableStateOf<String?>(null) }
    var pendingImagesCallback by remember { mutableStateOf<String?>(null) }
    var pendingFileCallback by remember { mutableStateOf<String?>(null) }
    var pendingBinaryFileCallback by remember { mutableStateOf<String?>(null) }
    var pendingImportAudioCallback by remember { mutableStateOf<String?>(null) }
    var webViewFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // State for saveFileAs (SAF CreateDocument)
    var pendingSaveCallbackId by remember { mutableStateOf<String?>(null) }
    var pendingSaveBase64Data by remember { mutableStateOf<String?>(null) }

    val plugins by pluginManager.plugins.collectAsStateWithLifecycle()
    val pluginInfo = plugins.find { it.manifest.id == pluginId }

    val dataStore = remember(pluginId) {
        PluginDataStore(context, pluginId)
    }
    val scope = rememberCoroutineScope()

    // Overlay WebView reference for pomodoro lock screen
    var overlayWebView by remember { mutableStateOf<WebView?>(null) }

    // Launcher for overlay permission (SYSTEM_ALERT_WINDOW)
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Result is handled by checking Settings.canDrawOverlays when needed
    }

    // Launcher for POST_NOTIFICATIONS permission
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Result is handled; proceed regardless
    }

    // Timer end broadcast receiver
    val timerEndReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == PomodoroTimerService.ACTION_TIMER_END) {
                    webView?.post {
                        webView?.evaluateJavascript(
                            "if(typeof window.onTimerEnd === 'function') { window.onTimerEnd(); }",
                            null
                        )
                    }
                    overlayWebView?.post {
                        overlayWebView?.evaluateJavascript(
                            "if(typeof window.onTimerEnd === 'function') { window.onTimerEnd(); }",
                            null
                        )
                    }
                }
            }
        }
    }

    // Music completion broadcast receiver
    val musicCompletedReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == MusicPlayerService.ACTION_MUSIC_COMPLETED) {
                    Log.i(TAG, "[DEBUG] musicCompletedReceiver received broadcast, webView is null: ${webView == null}")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "if(typeof window.onMusicCompleted === 'function') { window.onMusicCompleted(); } else { console.error('[DEBUG] onMusicCompleted not a function'); }",
                            null
                        )
                    }
                }
            }
        }
    }

    // Register broadcast receivers
    DisposableEffect(Unit) {
        LocalBroadcastManager.getInstance(context).registerReceiver(
            timerEndReceiver,
            IntentFilter(PomodoroTimerService.ACTION_TIMER_END)
        )
        LocalBroadcastManager.getInstance(context).registerReceiver(
            musicCompletedReceiver,
            IntentFilter(MusicPlayerService.ACTION_MUSIC_COMPLETED)
        )
        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(timerEndReceiver)
            LocalBroadcastManager.getInstance(context).unregisterReceiver(musicCompletedReceiver)
        }
    }

    // 文件选择器 - 用于 Bridge.pickFile()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val bridgeCallback = pendingFileCallback
        if (bridgeCallback != null) {
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val content = inputStream?.bufferedReader()?.readText()
                    inputStream?.close()
                    val fileName = uri.lastPathSegment ?: "unknown.txt"
                    webView?.post {
                        webView?.evaluateJavascript(
                            pluginBridgeResultJavascript(bridgeCallback, JSONObject().put("success", true).put("fileName", fileName).put("content", content ?: "")), null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            pluginBridgeResultJavascript(bridgeCallback, JSONObject().put("success", false).put("error", errMsg)), null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        pluginBridgeResultJavascript(bridgeCallback, JSONObject().put("success", false).put("error", "User cancelled")), null
                    )
                }
            }
            pendingFileCallback = null
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val callbackId = pendingImageCallback
        if (callbackId != null && uri != null) {
            try {
                val base64 = uriToBase64(context, uri)
                webView?.evaluateJavascript(
                    pluginBridgeJsonJavascript(callbackId, base64),
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process picked image", e)
                webView?.evaluateJavascript(
                    pluginBridgeResultJavascript(callbackId, null),
                    null
                )
            }
        } else if (callbackId != null) {
            webView?.evaluateJavascript(
                pluginBridgeResultJavascript(callbackId, null),
                null
            )
        }
        pendingImageCallback = null
    }

    // Multi-image API returns an array; keep pickImage's existing scalar result compatible.
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        val callbackId = pendingImagesCallback
        pendingImagesCallback = null
        if (callbackId != null) {
            scope.launch {
                try {
                    val javascript = withContext(Dispatchers.IO) {
                        val images = JSONArray(uris.map { uri ->
                            context.contentResolver.openInputStream(uri)?.use {
                                Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
                            } ?: error("Cannot read selected image")
                        })
                        pluginBridgeResultJavascript(callbackId, images)
                    }
                    webView?.evaluateJavascript(javascript, null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to process picked images", error)
                    webView?.evaluateJavascript(pluginBridgeResultJavascript(callbackId, null), null)
                }
            }
        }
    }

    // Launcher for binary file picking (Bridge.pickBinaryFile)
    val binaryFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callbackId = pendingBinaryFileCallback
        if (callbackId != null) {
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    val base64 = if (bytes != null) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
                    val fileName = uri.lastPathSegment ?: "unknown"
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    webView?.post {
                        webView?.evaluateJavascript(
                            pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true).put("fileName", fileName).put("mimeType", mimeType).put("base64", base64)), null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", errMsg)), null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", "User cancelled")), null
                    )
                }
            }
            pendingBinaryFileCallback = null
        }
    }

    // Launcher for importing audio files (Bridge.importAudioFile) - multi-select with duplicate detection
    val audioFileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<@JvmSuppressWildcards Uri>? ->
        val callbackId = pendingImportAudioCallback
        if (callbackId != null) {
            if (uris.isNullOrEmpty()) {
                // null or empty list -> user cancelled
                webView?.post {
                    webView?.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", "User cancelled")), null
                    )
                }
            } else {
                val imported = JSONArray()
                val duplicates = JSONArray()
                val errors = JSONArray()
                val seenInBatch = mutableSetOf<String>()

                // Create music/ subdirectory once
                val musicDir = File(dataStore.getDataDir(), "music")
                if (!musicDir.exists()) {
                    musicDir.mkdirs()
                }

                for (uri in uris) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes()
                        inputStream?.close()

                        if (bytes == null) {
                            val errName = uri.lastPathSegment ?: "unknown"
                            errors.put(JSONObject().put("fileName", errName).put("error", "Failed to read file"))
                            continue
                        }

                        // Get original file name from URI (reuse existing path/colon logic)
                        var fileName = uri.lastPathSegment ?: "unknown.mp3"
                        val lastSlash = fileName.lastIndexOf('/')
                        val lastColon = fileName.lastIndexOf(':')
                        val cutIndex = maxOf(lastSlash, lastColon)
                        if (cutIndex >= 0) {
                            fileName = fileName.substring(cutIndex + 1)
                        }

                        // Duplicate detection: existing in music/ OR already seen in this batch
                        val targetFile = dataStore.resolveDataFile("music/$fileName")
                            ?: throw SecurityException("Invalid file name")
                        if (targetFile.exists() || seenInBatch.contains(fileName)) {
                            duplicates.put(fileName)
                        } else {
                            targetFile.writeBytes(bytes)
                            seenInBatch.add(fileName)

                            val filePath = targetFile.absolutePath
                            imported.put(JSONObject().put("filePath", filePath).put("fileName", fileName))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "importAudioFile: failed to process uri=$uri, err=${e.message}", e)
                        val errName = uri.lastPathSegment ?: "unknown"
                        errors.put(JSONObject().put("fileName", errName).put("error", e.message ?: "Unknown error"))
                    }
                }

                webView?.post {
                    webView?.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true).put("imported", imported).put("duplicates", duplicates).put("errors", errors)), null
                    )
                }
            }
            pendingImportAudioCallback = null
        }
    }

    // Launcher for saveFileAs (SAF CreateDocument - user picks save location)
    val saveFileAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val callbackId = pendingSaveCallbackId
        val base64Data = pendingSaveBase64Data
        if (callbackId != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    PluginImageSave.write(base64Data, uri == null) {
                        context.contentResolver.openOutputStream(requireNotNull(uri), "wt")
                    }
                }
                if (result.optBoolean("success")) result.put("fileName", uri?.lastPathSegment ?: "saved_file")
                webView?.evaluateJavascript(
                    pluginBridgeJsonJavascript(callbackId, result.toString()),
                    null,
                )
                pendingSaveCallbackId = null
                pendingSaveBase64Data = null
            }
        }
    }

    // Launcher for native HTML <input type="file"> file chooser
    val htmlFileChooserLauncher = rememberLauncherForActivityResult(
        contract = PluginHtmlFileChooser()
    ) { uris ->
        val callback = webViewFileChooserCallback
        webViewFileChooserCallback = null
        callback?.onReceiveValue(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pluginInfo?.manifest?.name ?: "\u63D2\u4EF6\u7BA1\u7406")
                        if (pluginInfo?.manifest?.permissions?.contains("ai_chat") == true) {
                            Text(if (conversationId.isBlank()) "AI：助手默认模型" else "AI：当前会话模型",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "\u8FD4\u56DE")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (pluginInfo == null) {
                Text(
                    text = "\u63D2\u4EF6\u4E0D\u5B58\u5728",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val aiCall = PluginAiCall(
                            permissions = pluginInfo.manifest.permissions,
                            loadSettings = { settingsStore.settingsFlow.first() },
                            loadConversation = if (conversationId.isBlank()) null else {
                                { conversationRepository.getConversationById(Uuid.parse(conversationId)) }
                            },
                            generateText = { providerSetting, messages, params ->
                                val manager: ProviderManager = org.koin.java.KoinJavaComponent.get(ProviderManager::class.java)
                                usageRecorder.wrap(
                                    manager.getProviderByType(providerSetting), UsageTag(UsageSource.PLUGIN),
                                )
                                    .generateText(providerSetting, messages, params)
                                    .choices.firstOrNull()?.message?.parts
                                    ?.filterIsInstance<UIMessagePart.Text>()?.firstOrNull()?.text.orEmpty()
                            },
                            onError = { Log.e(TAG, "AI generation failed", it) },
                        )
                        WebView(ctx).apply {
                            settings.apply {
                                @SuppressLint("SetJavaScriptEnabled")
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                databaseEnabled = true
                            }

                            webViewClient = PluginWebViewClient(
                                pluginInfo = pluginInfo,
                                dataStore = dataStore,
                                pluginLoader = pluginLoader,
                                pluginManager = pluginManager,
                                pluginRepository = pluginRepository,
                                conversationRepository = conversationRepository,
                                settingsStore = settingsStore,
                                aiCall = aiCall,
                                chatService = chatService,
                                filesManager = filesManager,
                                scope = scope,
                                onPickImage = { callbackId ->
                                    pendingImageCallback = callbackId
                                    pickImageLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickImages = { callbackId ->
                                    if (pendingImagesCallback != null) {
                                        webView?.evaluateJavascript(pluginBridgeResultJavascript(callbackId, null), null)
                                    } else {
                                        pendingImagesCallback = callbackId
                                        try {
                                            pickImagesLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        } catch (error: Exception) {
                                            pendingImagesCallback = null
                                            Log.e(TAG, "Cannot open multi-image picker", error)
                                            webView?.evaluateJavascript(pluginBridgeResultJavascript(callbackId, null), null)
                                        }
                                    }
                                },
                                onPickFile = { callbackId ->
                                    pendingFileCallback = callbackId
                                    filePickerLauncher.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream"))
                                },
                                onPickBinaryFile = { callbackId ->
                                    pendingBinaryFileCallback = callbackId
                                    binaryFilePickerLauncher.launch(arrayOf("*/*"))
                                },
                                onImportAudioFile = { callbackId ->
                                    pendingImportAudioCallback = callbackId
                                    audioFileImportLauncher.launch(arrayOf("audio/*"))
                                },
                                onSaveFileAs = { callbackId, fileName, base64Data ->
                                    if (pendingSaveCallbackId != null) {
                                        webView?.evaluateJavascript(
                                            pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", "Another save is in progress")), null
                                        )
                                    } else {
                                        pendingSaveCallbackId = callbackId
                                        pendingSaveBase64Data = base64Data
                                        try {
                                            saveFileAsLauncher.launch(fileName)
                                        } catch (_: Exception) {
                                            pendingSaveCallbackId = null
                                            pendingSaveBase64Data = null
                                            webView?.evaluateJavascript(
                                                pluginBridgeResultJavascript(callbackId, JSONObject().put("success", false).put("error", "Cannot open save picker")), null
                                            )
                                        }
                                    }
                                },
                                onClose = onNavigateBack,
                                onStartTimer = { wv, seconds ->
                                    // Check overlay permission
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayPermissionLauncher.launch(intent)
                                        wv.post {
                                            wv.evaluateJavascript(
                                                pluginBridgeResultJavascript(null, JSONObject().put("success", false).put("error", "Overlay permission required. Please grant it in settings and try again.")), null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }
                                    // Check notification permission
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    PomodoroTimerService.start(context, seconds)
                                    wv.post {
                                        // Find the callbackId from the pending bridge call
                                        // We use a simplified approach - the callback is handled in handleBridgeCall
                                    }
                                },
                                onStopTimer = {
                                    PomodoroTimerService.stop(context)
                                },
                                onShowOverlay = { wv, html ->
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        wv.post {
                                            wv.evaluateJavascript(
                                                pluginBridgeResultJavascript(null, JSONObject().put("success", false).put("error", "Overlay permission not granted")), null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }

                                    val activity = context as? Activity
                                    val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                                    if (windowManager == null) {
                                        wv.post {
                                            wv.evaluateJavascript(
                                                pluginBridgeResultJavascript(null, JSONObject().put("success", false).put("error", "Cannot access WindowManager")), null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }

                                    // Remove existing overlay if any
                                    overlayWebView?.let { existingOverlay ->
                                        try {
                                            windowManager.removeView(existingOverlay)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error removing existing overlay", e)
                                        }
                                        existingOverlay.destroy()
                                    }

                                    val overlayWv = WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                    }
                                    overlayWv.requestFocus()

                                    // Inject bridge JS for overlay
                                    overlayWv.webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.evaluateJavascript(overlayBridgeJavascript, null)
                                            // Auto-focus input/textarea on click so keyboard appears
                                            view?.evaluateJavascript(
                                                "document.addEventListener('click', function(e){ if(e.target.tagName==='INPUT'||e.target.tagName==='TEXTAREA'){ e.target.focus(); } });",
                                                null
                                            )
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val url = request?.url?.toString() ?: return false
                                            if (url.startsWith("bridge://")) {
                                                val uri = Uri.parse(url)
                                                val method = uri.host ?: return false
                                                val params = uri.queryParameterNames.associateWith {
                                                    uri.getQueryParameter(it) ?: ""
                                                }
                                                when (method) {
                                                    "hideOverlay" -> {
                                                        try {
                                                            windowManager.removeView(overlayWv)
                                                        } catch (e: Exception) {
                                                            Log.w(TAG, "Error removing overlay", e)
                                                        }
                                                        overlayWv.destroy()
                                                        overlayWebView = null
                                                    }
                                                    "getTimerState" -> {
                                                        val running = PomodoroTimerService.isRunning()
                                                        val remaining = PomodoroTimerService.getRemainingSeconds()
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                pluginBridgeResultJavascript(cbId, JSONObject().put("running", running).put("remaining", remaining)), null
                                                            )
                                                        }
                                                    }
                                                    "getData" -> {
                                                        val key = params["key"] ?: ""
                                                        val value = dataStore.getData(key)
                                                        val result = if (value != null) {
                                                            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
                                                        } else "null"
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                pluginBridgeJsonJavascript(cbId, result.toString()), null
                                                            )
                                                        }
                                                    }
                                                    "setData" -> {
                                                        val key = params["key"] ?: ""
                                                        val value = params["value"] ?: ""
                                                        dataStore.setData(key, value)
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                pluginBridgeResultJavascript(cbId, true), null
                                                            )
                                                        }
                                                    }
                                                    "callAI" -> {
                                                        val cbId = params["callbackId"] ?: ""
                                                        val prompt = params["prompt"] ?: ""
                                                        val contextJson = params["context"] ?: "{}"
                                                        scope.launch(Dispatchers.IO) {
                                                            val aiResult = aiCall.call(prompt) {
                                                                "你是一个番茄钟陪伴助手。用户正在使用番茄钟专注。请用简短温暖的话回应，鼓励用户保持专注。当前上下文：$contextJson"
                                                            }
                                                            overlayWv.post {
                                                                overlayWv.evaluateJavascript(pluginAiResultJavascript(cbId, aiResult), null)
                                                            }
                                                        }
                                                    }
                                                }
                                                return true
                                            }
                                            return false
                                        }
                                    }

                                    // Block back key
                                    overlayWv.setOnKeyListener { _, keyCode, event ->
                                        keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
                                    }

                                    val params = WindowManager.LayoutParams(
                                        WindowManager.LayoutParams.MATCH_PARENT,
                                        WindowManager.LayoutParams.MATCH_PARENT,
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                        else
                                            @Suppress("DEPRECATION")
                                            WindowManager.LayoutParams.TYPE_PHONE,
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                        android.graphics.PixelFormat.TRANSLUCENT
                                    )
                                    params.gravity = Gravity.CENTER
                                    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

                                    // Load HTML content
                                    overlayWv.loadDataWithBaseURL(
                                        "https://rikkahub.local",
                                        html,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )

                                    windowManager.addView(overlayWv, params)
                                    overlayWebView = overlayWv
                                },
                                onHideOverlay = {
                                    val activity = context as? Activity
                                    val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                                    overlayWebView?.let { existingOverlay ->
                                        try {
                                            windowManager?.removeView(existingOverlay)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error removing overlay", e)
                                        }
                                        existingOverlay.destroy()
                                        overlayWebView = null
                                    }
                                }
                            )

                            // 处理 HTML <input type="file"> 文件选择
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    // Cancel any previous callback
                                    webViewFileChooserCallback?.onReceiveValue(null)
                                    webViewFileChooserCallback = filePathCallback

                                    try {
                                        htmlFileChooserLauncher.launch(PluginHtmlFileRequest(
                                            acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray(),
                                            mode = fileChooserParams?.mode,
                                        ))
                                    } catch (error: Exception) {
                                        webViewFileChooserCallback = null
                                        filePathCallback?.onReceiveValue(null)
                                        Log.e(TAG, "Cannot open HTML file picker", error)
                                    }
                                    return true
                                }
                            }

                            // 禁用原生长按选择菜单 - 通过CSS/JS控制，不再用原生拦截
                            // （原生setOnLongClickListener会阻止批注模式的文字选择）

                            val htmlFile = SafeFileResolver.resolveInside(pluginInfo.directory, htmlEntryPath)
                            if (htmlFile?.isFile == true) {
                                loadUrl("file://${htmlFile.absolutePath}")
                            } else {
                                loadData(
                                    "<html><body><h2>\u9875\u9762\u6587\u4EF6\u4E0D\u5B58\u5728</h2><p>$htmlEntryPath</p></body></html>",
                                    "text/html",
                                    "UTF-8"
                                )
                            }

                            webView = this@apply
                        }
                    },
                    update = { wv ->
                        webView = wv
                    }
                )
            }
        }
    }

    DisposableEffect(pluginId, htmlEntryPath) {
        onDispose {
            pendingImageCallback = null
            pendingImagesCallback = null
            pendingFileCallback = null
            pendingBinaryFileCallback = null
            pendingImportAudioCallback = null
            pendingSaveCallbackId = null
            pendingSaveBase64Data = null
            webViewFileChooserCallback?.onReceiveValue(null)
            webViewFileChooserCallback = null
            // Clean up main WebView
            webView?.destroy()
            // Clean up overlay WebView
            overlayWebView?.let { overlay ->
                try {
                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    windowManager?.removeView(overlay)
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing overlay on dispose", e)
                }
                overlay.destroy()
            }
            // Stop timer service
            PomodoroTimerService.stop(context)
        }
    }
}

private class PluginWebViewClient(
    private val pluginInfo: PluginInfo,
    private val dataStore: PluginDataStore,
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager,
    private val pluginRepository: PluginRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val aiCall: PluginAiCall,
    private val chatService: ChatService,
    private val filesManager: FilesManager,
    private val scope: CoroutineScope,
    private val onPickImage: (callbackId: String) -> Unit,
    private val onPickImages: (callbackId: String) -> Unit,
    private val onPickFile: (callbackId: String) -> Unit,
    private val onPickBinaryFile: (callbackId: String) -> Unit,
    private val onImportAudioFile: (callbackId: String) -> Unit,
    private val onSaveFileAs: (callbackId: String, fileName: String, base64Data: String) -> Unit,
    private val onClose: () -> Unit,
    private val onStartTimer: (webView: WebView, seconds: Int) -> Unit,
    private val onStopTimer: () -> Unit,
    private val onShowOverlay: (webView: WebView, html: String) -> Unit,
    private val onHideOverlay: () -> Unit
) : WebViewClient() {
    private val json = Json { ignoreUnknownKeys = true }
    private val chatImageTransfers = mutableMapOf<String, StringBuilder>()

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        if (url.startsWith("bridge://")) {
            handleBridgeCall(view!!, url)
            return true
        }
        if (!url.startsWith("file://") && !url.startsWith("about:blank")) {
            // Open external URLs (e.g. Supabase download links) in system browser
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                view?.context?.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open external URL: $url", e)
            }
            return true
        }
        return false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(bridgeJavascript, null)
    }

    private fun handleBridgeCall(webView: WebView, url: String) {
        val uri = Uri.parse(url)
        val method = uri.host ?: return
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }

        when (method) {
            "getPluginConfig" -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val savedConfig = pluginRepository.getPluginConfig(pluginInfo.manifest.id)
                        val mergedConfig = effectivePluginConfig(pluginInfo.manifest.config, savedConfig)

                        val jsonObj = JsonObject(mergedConfig)
                        val result = json.encodeToString(JsonObject.serializer(), jsonObj)
                        webView.post {
                            webView.evaluateJavascript(
                                pluginBridgeJsonJavascript(params["callbackId"], result.toString()), null
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get plugin config", e)
                        webView.post {
                            webView.evaluateJavascript(
                                pluginBridgeResultJavascript(params["callbackId"], JSONObject()), null
                            )
                        }
                    }
                }
            }

            "getData" -> {
                val key = params["key"] ?: ""
                val value = dataStore.getData(key)
                val result = if (value != null) {
                    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
                } else "null"
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeJsonJavascript(params["callbackId"], result.toString()), null
                    )
                }
            }

            "setData" -> {
                val key = params["key"] ?: ""
                val value = params["value"] ?: ""
                dataStore.setData(key, value)
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(params["callbackId"], true), null
                    )
                }
            }

            "deleteData" -> {
                val key = params["key"] ?: ""
                dataStore.deleteData(key)
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(params["callbackId"], true), null
                    )
                }
            }

            "listData" -> {
                val keys = dataStore.listData()
                val jsonArray = JSONArray(keys)
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(params["callbackId"], jsonArray), null
                    )
                }
            }

            "pickImage" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickImage(callbackId)
            }

            "pickImages" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickImages(callbackId)
            }

            "pickFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickFile(callbackId)
            }

            "pickBinaryFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickBinaryFile(callbackId)
            }

            "importAudioFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onImportAudioFile(callbackId)
            }

            "writeFile" -> {
                val fileName = params["fileName"] ?: ""
                val base64Data = params["data"] ?: ""
                try {
                    val file = dataStore.resolveDataFile(fileName)
                        ?: throw SecurityException("Invalid data file path")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    file.writeBytes(bytes)
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], file.absolutePath), null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], null), null
                        )
                    }
                }
            }

            "readFile" -> {
                val fileName = params["fileName"] ?: ""
                try {
                    val file = dataStore.resolveDataFile(fileName)
                        ?: throw SecurityException("Invalid data file path")
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        webView.post {
                            webView.evaluateJavascript(
                                pluginBridgeResultJavascript(params["callbackId"], base64), null
                            )
                        }
                    } else {
                        webView.post {
                            webView.evaluateJavascript(
                                pluginBridgeResultJavascript(params["callbackId"], null), null
                            )
                        }
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], null), null
                        )
                    }
                }
            }

            "listFiles" -> {
                val dirPath = params["dir"] ?: ""
                val baseDir = dataStore.resolveDataFile(dirPath)
                val files = if (baseDir?.exists() == true && baseDir.isDirectory) {
                    baseDir.listFiles()?.map { it.name } ?: emptyList()
                } else emptyList()
                val jsonArray = JSONArray(files)
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(params["callbackId"], jsonArray), null
                    )
                }
            }

            "deleteFile" -> {
                val fileName = params["fileName"] ?: ""
                val file = dataStore.resolveDataFile(fileName)
                val result = file?.delete() == true
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeJsonJavascript(params["callbackId"], result.toString()), null
                    )
                }
            }

            "musicPlay" -> {
                val filePath = params["filePath"] ?: ""
                val title = params["title"] ?: ""
                val artist = params["artist"] ?: ""
                try {
                    val safeFile = dataStore.resolveDataFile(filePath)?.takeIf { it.isFile }
                        ?: throw SecurityException("Invalid music file path")
                    MusicPlayerService.play(webView.context, safeFile.absolutePath, title, artist)
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", true)), null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Unknown error"
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", false).put("error", errMsg)), null
                        )
                    }
                }
            }

            "musicPause" -> {
                try {
                    MusicPlayerService.pause(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", true)), null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", false)), null
                        )
                    }
                }
            }

            "musicResume" -> {
                try {
                    MusicPlayerService.resume(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", true)), null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", false)), null
                        )
                    }
                }
            }

            "musicStop" -> {
                try {
                    MusicPlayerService.stop(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", true)), null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", false)), null
                        )
                    }
                }
            }

            "musicSeek" -> {
                val position = params["position"]?.toIntOrNull() ?: 0
                try {
                    MusicPlayerService.seekTo(webView.context, position)
                    webView.post { webView.evaluateJavascript(pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", true)), null) }
                } catch (e: Exception) {
                    Log.e(TAG, "musicSeek failed, position=$position, err=${e.message}", e)
                    webView.post { webView.evaluateJavascript(pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("success", false)), null) }
                }
            }

            "musicGetProgress" -> {
                try {
                    val position = MusicPlayerService.getCurrentPosition()
                    val duration = MusicPlayerService.getDuration()
                    webView.post {
                        webView.evaluateJavascript(
                            pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("position", position).put("duration", duration)), null
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "musicGetProgress failed, err=${e.message}", e)
                    webView.post { webView.evaluateJavascript(pluginBridgeResultJavascript(params["callbackId"], JSONObject().put("position", 0).put("duration", 0)), null) }
                }
            }

            "close" -> {
                onClose()
            }

            "callTool" -> {
                val toolName = params["toolName"] ?: ""
                val toolParams = params["params"] ?: "{}"
                scope.launch(Dispatchers.Main) {
                    try {
                        val result = callPluginTool(toolName, toolParams)
                        webView.post {
                            webView.evaluateJavascript(
                                pluginBridgeJsonJavascript(params["callbackId"], result.toString()), null
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val errorResult = JSONObject().put("success", false).put("error", e.message ?: "Unknown error")
                        webView.post {
                            webView.evaluateJavascript(
                                pluginBridgeResultJavascript(params["callbackId"], errorResult), null
                            )
                        }
                    }
                }
            }

            "callAI" -> {
                val callbackId = params["callbackId"] ?: ""
                val prompt = params["prompt"] ?: ""
                val contextJson = params["context"] ?: "{}"

                scope.launch(Dispatchers.IO) {
                    val aiResult = callAI(prompt, contextJson)
                    webView.post {
                        webView.evaluateJavascript(pluginAiResultJavascript(callbackId, aiResult), null)
                    }
                }
            }

            "listConversations" -> {
                handleConversationBridge(webView, params) {
                    val currentAssistant = settingsStore.settingsFlow.first().getCurrentAssistant()
                    val items = JSONArray()
                    conversationRepository.getConversationsOfAssistant(currentAssistant.id).first()
                        .sortedByDescending { it.updateAt }
                        .forEach { conversation ->
                            items.put(JSONObject()
                                .put("id", conversation.id.toString())
                                .put("title", conversation.title.ifBlank { "未命名聊天" })
                                .put("messageCount", conversation.messageNodes.size)
                                .put("updatedAt", conversation.updateAt.toString()))
                        }
                    JSONObject().put("success", true).put("conversations", items)
                }
            }

            "chatImageChunk" -> {
                handleConversationBridge(webView, params) {
                    val transferId = params["transferId"].orEmpty()
                    require(transferId.isNotBlank()) { "transferId is required" }
                    synchronized(chatImageTransfers) {
                        val builder = if (params["reset"] == "true") {
                            StringBuilder().also { chatImageTransfers[transferId] = it }
                        } else checkNotNull(chatImageTransfers[transferId]) { "Image transfer has not started" }
                        builder.append(params["chunk"].orEmpty())
                    }
                    JSONObject().put("success", true)
                }
            }

            "sendChatMessage" -> {
                handleConversationBridge(webView, params) {
                    val conversationId = parseConversationId(params["conversationId"])
                    require(conversationRepository.getConversationById(conversationId) != null) {
                        "conversation not found"
                    }
                    val text = params["text"]?.trim().orEmpty()
                    require(text.isNotEmpty()) { "text is required" }
                    chatService.initializeConversation(conversationId)
                    attachmentReferenceGuard.beginWrite().use {
                        val content = buildList<UIMessagePart> {
                            add(UIMessagePart.Text(text))
                            val transferredImage = params["imageTransferId"]?.takeIf { it.isNotBlank() }?.let { id ->
                                synchronized(chatImageTransfers) {
                                    checkNotNull(chatImageTransfers.remove(id)) { "Image transfer not found" }.toString()
                                }
                            }
                            (transferredImage ?: params["image"])?.takeIf { it.isNotBlank() }?.let { image ->
                                require(image.startsWith("data:image/") && image.contains(";base64,")) {
                                    "Expected a base64 image"
                                }
                                val mime = image.substringAfter("data:").substringBefore(';')
                                val bytes = Base64.decode(image.substringAfter(";base64,"), Base64.DEFAULT)
                                require(bytes.isNotEmpty()) { "Image is empty" }
                                val asset = filesManager.saveUploadFromBytes(
                                    bytes = bytes,
                                    displayName = "plugin_image.${mime.substringAfter('/').substringBefore('+')}",
                                    mimeType = mime,
                                )
                                add(UIMessagePart.Image(Uri.fromFile(filesManager.getFile(asset)).toString()))
                            }
                        }
                        checkNotNull(chatService.submitMessage(
                            conversationId = conversationId,
                            content = content,
                            answer = true,
                            transientSystemPrompt = params["hiddenContext"]?.takeIf { it.isNotBlank() },
                        )) { "Conversation no longer accepts messages" }
                    }
                    JSONObject().put("success", true).put("status", "accepted")
                }
            }

            "notifyHook" -> {
                val callbackId = params["callbackId"] ?: ""
                val hookName = params["hookName"] ?: ""
                val hookContextJson = params["context"] ?: "{}"

                scope.launch(Dispatchers.IO) {
                    try {
                        val hookResult = handleHookTrigger(webView, hookName, hookContextJson)
                        webView.post {
                            webView.evaluateJavascript(
                                pluginBridgeJsonJavascript(callbackId, hookResult.toString()), null
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "notifyHook failed", e)
                        val errorResult = JSONObject().put("success", false).put("error", e.message ?: "Unknown error")
                        webView.post {
                            webView.evaluateJavascript(
                                pluginBridgeResultJavascript(callbackId, errorResult), null
                            )
                        }
                    }
                }
            }

            "startTimer" -> {
                val callbackId = params["callbackId"] ?: ""
                val seconds = params["seconds"]?.toIntOrNull() ?: (25 * 60)
                onStartTimer(webView, seconds)
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true)), null
                    )
                }
            }

            "stopTimer" -> {
                val callbackId = params["callbackId"] ?: ""
                onStopTimer()
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true)), null
                    )
                }
            }

            "getTimerState" -> {
                val callbackId = params["callbackId"] ?: ""
                val running = PomodoroTimerService.isRunning()
                val remaining = PomodoroTimerService.getRemainingSeconds()
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("running", running).put("remaining", remaining)), null
                    )
                }
            }

            "showOverlay" -> {
                val callbackId = params["callbackId"] ?: ""
                val html = params["html"] ?: ""
                onShowOverlay(webView, html)
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true)), null
                    )
                }
            }

            "saveImageAsset" -> {
                val callbackId = params["callbackId"].orEmpty()
                scope.launch {
                    try {
                        val asset = withContext(Dispatchers.IO) {
                            PluginImageSave.load(dataStore, params["assetKey"].orEmpty())
                        }
                        onSaveFileAs(callbackId, asset.fileName, asset.encoded)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        postBridgeJson(webView, callbackId, JSONObject().put("success", false).put("error", "Image asset unavailable"))
                    }
                }
            }

            "saveFileAs" -> {
                val callbackId = params["callbackId"] ?: ""
                val fileName = params["fileName"] ?: "untitled.json"
                val base64Data = params["data"] ?: ""
                onSaveFileAs(callbackId, fileName, base64Data)
            }

            "hideOverlay" -> {
                val callbackId = params["callbackId"] ?: ""
                onHideOverlay()
                webView.post {
                    webView.evaluateJavascript(
                        pluginBridgeResultJavascript(callbackId, JSONObject().put("success", true)), null
                    )
                }
            }
        }
    }

    private fun handleConversationBridge(
        webView: WebView,
        params: Map<String, String>,
        block: suspend () -> JSONObject,
    ) {
        val callbackId = params["callbackId"].orEmpty()
        if (!pluginInfo.manifest.permissions.contains("conversation_sync")) {
            postBridgeJson(
                webView,
                callbackId,
                JSONObject()
                    .put("success", false)
                    .put("error", "Permission denied: conversation_sync permission not declared"),
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { block() }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Conversation sync bridge failed", error)
                JSONObject()
                    .put("success", false)
                    .put("error", error.message ?: "Unknown error")
            }
            postBridgeJson(webView, callbackId, result)
        }
    }

    private fun parseConversationId(value: String?): Uuid =
        value?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: throw IllegalArgumentException("invalid conversationId")

    private fun postBridgeJson(webView: WebView, callbackId: String, value: JSONObject) {
        webView.post {
            webView.evaluateJavascript(
                pluginBridgeResultJavascript(callbackId, value),
                null,
            )
        }
    }

    private suspend fun callAI(prompt: String, contextJson: String): String {
        return aiCall.call(prompt) {
            buildString {
                append("\u4F60\u662F\u4E00\u4E2A\u9605\u8BFB\u52A9\u624B\u3002\u8BF7\u6839\u636E\u7528\u6237\u7684\u95EE\u9898\u7ED9\u51FA\u6709\u6DF1\u5EA6\u7684\u3001\u6E29\u67D4\u7684\u56DE\u7B54\u3002")
                if (contextJson.isNotBlank() && contextJson != "{}") {
                    try {
                        val ctx = JSONObject(contextJson)
                        append("\n\n\u5F53\u524D\u9605\u8BFB\u4E0A\u4E0B\u6587\uFF1A")
                        if (ctx.has("book")) append("\n\u4E66\u540D\uFF1A").append(ctx.getString("book"))
                        if (ctx.has("chapter")) append("\n\u7AE0\u8282\uFF1A\u7B2C").append(ctx.getString("chapter")).append("\u7AE0")
                        if (ctx.has("page")) append("\n\u9875\u7801\uFF1A\u7B2C").append(ctx.getString("page")).append("\u9875")
                        if (ctx.has("annotations")) append("\n\u5DF2\u6709\u6279\u6CE8\uFF1A").append(ctx.getString("annotations"))
                        if (ctx.has("content")) append("\n\u9875\u9762\u5185\u5BB9\uFF1A").append(ctx.getString("content"))
                        if (ctx.has("quote")) append("\n\u5F15\u7528\u539F\u6587\uFF1A").append(ctx.getString("quote"))
                        if (ctx.has("note")) append("\n\u7528\u6237\u6279\u6CE8\uFF1A").append(ctx.getString("note"))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse context JSON", e)
                    }
                }
            }
        }
    }

    private suspend fun handleHookTrigger(webView: WebView, hookName: String, contextJson: String): String {
        val hookConfig = pluginInfo.manifest.hookConfigs[hookName]
            ?: return JSONObject()
                .put("success", false)
                .put("error", "Hook '$hookName' not configured in manifest")
                .toString()

        when (hookConfig.action) {
            "call_js_function" -> {
                val functionName = hookConfig.function
                    ?: return JSONObject()
                        .put("success", false)
                        .put("error", "function is required for call_js_function action")
                        .toString()

                val jsCode = pluginHookJavascript(functionName, contextJson)
                    ?: return JSONObject()
                        .put("success", false)
                        .put("error", "function must be a JavaScript identifier")
                        .toString()

                if (!hookConfig.autoTrigger) {
                    return JSONObject()
                        .put("success", false)
                        .put("error", "Hook '$hookName' is not auto-triggered")
                        .toString()
                }

                webView.post {
                    webView.evaluateJavascript(jsCode, null)
                }
                return JSONObject()
                    .put("success", true)
                    .put("action", "call_js_function")
                    .put("function", functionName)
                    .toString()
            }

            "call_ai" -> {
                val template = hookConfig.promptTemplate
                    ?: return """{"success":false,"error":"promptTemplate is required for call_ai action"}"""

                val contextObj = try {
                    JSONObject(contextJson)
                } catch (e: Exception) {
                    return """{"success":false,"error":"Invalid context JSON"}"""
                }

                var prompt = template
                val variableKeys = listOf("book", "chapter", "page", "quote", "note", "content", "annotations")
                for (key in variableKeys) {
                    if (contextObj.has(key)) {
                        val value = contextObj.getString(key)
                        prompt = prompt.replace("{$key}", value)
                    }
                }

                val aiResult = callAI(prompt, contextJson)
                return aiResult
            }

            else -> {
                return """{"success":false,"error":"Unknown hook action: ${hookConfig.action}"}"""
            }
        }
    }

    private fun escapeJsString(str: String): String {
        val escaped = str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private suspend fun callPluginTool(toolName: String, params: String): String {
        return try {
            pluginManager.awaitInitialization()
            val candidates = pluginManager.plugins.value.filter { plugin ->
                plugin.isEnabled && plugin.loadError == null &&
                    plugin.manifest.tools.any { it.name == toolName }
            }
            val target = candidates.find { it.manifest.id == pluginInfo.manifest.id }
                ?: candidates.firstOrNull()

            if (target == null) {
                return JSONObject().put("success", false).put("error", "Tool not found: $toolName").toString()
            }

            val jsonParams = Json.parseToJsonElement(params)

            val result = pluginLoader.callTool(
                pluginId = target.manifest.id,
                toolName = toolName,
                params = jsonParams
            )

            return result.fold(
                onSuccess = { jsonElement: kotlinx.serialization.json.JsonElement ->
                    Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
                },
                onFailure = { error: Throwable ->
                    JSONObject().put("success", false).put("error", error.message ?: "Plugin call failed").toString()
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", e.message ?: "Plugin call failed").toString()
        }
    }
}

private const val bridgeJavascript = """
(function() {
    if (window.Bridge) return;

    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;

    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;

            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId);
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }

            var iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            iframe.src = url;
            document.body.appendChild(iframe);
            setTimeout(function() {
                document.body.removeChild(iframe);
            }, 100);
        });
    }

    window.Bridge = {
        getPluginConfig: function() {
            return bridgeCall('getPluginConfig', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        deleteData: function(key) {
            return bridgeCall('deleteData', {key: key});
        },
        listData: function() {
            return bridgeCall('listData', {});
        },
        pickImage: function() {
            return bridgeCall('pickImage', {});
        },
        pickImages: function() {
            return bridgeCall('pickImages', {});
        },
        pickFile: function() {
            return bridgeCall('pickFile', {});
        },
        pickBinaryFile: function() {
            return bridgeCall('pickBinaryFile', {});
        },
        importAudioFile: function() {
            return bridgeCall('importAudioFile', {});
        },
        callTool: function(toolName, params) {
            return bridgeCall('callTool', {toolName: toolName, params: params || '{}'});
        },
        writeFile: function(fileName, base64Data) {
            return bridgeCall('writeFile', {fileName: fileName, data: base64Data});
        },
        saveImageAsset: function(assetKey) {
            return bridgeCall('saveImageAsset', {assetKey: assetKey});
        },
        saveFileAs: function(fileName, base64Data) {
            return bridgeCall('saveFileAs', {fileName: fileName, data: base64Data});
        },
        readFile: function(fileName) {
            return bridgeCall('readFile', {fileName: fileName});
        },
        listFiles: function(dirPath) {
            return bridgeCall('listFiles', {dir: dirPath || ''});
        },
        deleteFile: function(fileName) {
            return bridgeCall('deleteFile', {fileName: fileName});
        },
        close: function() {
            bridgeCall('close', {});
        },
        callAI: function(prompt, context) {
            return bridgeCall('callAI', {prompt: prompt, context: context || '{}'});
        },
        listConversations: function() {
            return bridgeCall('listConversations', {});
        },
        uploadChatImage: async function(image) {
            if (!image) return '';
            var transferId = 'ci_' + Date.now() + '_' + Math.random().toString(36).slice(2);
            var chunkSize = 24000;
            for (var offset = 0; offset < image.length; offset += chunkSize) {
                var result = await bridgeCall('chatImageChunk', {
                    transferId: transferId,
                    chunk: image.slice(offset, offset + chunkSize),
                    reset: offset === 0
                });
                if (!result || result.success === false) {
                    throw new Error(result && result.error || 'Image transfer failed');
                }
            }
            return transferId;
        },
        sendChatMessage: function(conversationId, text, hiddenContext, image) {
            if (!image) return bridgeCall('sendChatMessage', {
                conversationId: conversationId, text: text, hiddenContext: hiddenContext || '', image: ''
            });
            return this.uploadChatImage(image).then(function(transferId) {
                return bridgeCall('sendChatMessage', {
                    conversationId: conversationId,
                    text: text,
                    hiddenContext: hiddenContext || '',
                    imageTransferId: transferId
                });
            });
        },
        // Compatibility for installed drawing plugins: only generic chat/image delivery remains.
        listWatchConversations: function() {
            return this.listConversations();
        },
        sendWatchMessage: function(conversationId, text, hiddenContext, image) {
            return this.sendChatMessage(conversationId, text, hiddenContext, image);
        },
        notifyHook: function(hookName, context) {
            return bridgeCall('notifyHook', {hookName: hookName, context: context || '{}'});
        },
        startTimer: function(seconds) {
            return bridgeCall('startTimer', {seconds: seconds});
        },
        stopTimer: function() {
            return bridgeCall('stopTimer', {});
        },
        getTimerState: function() {
            return bridgeCall('getTimerState', {});
        },
        showOverlay: function(html) {
            return bridgeCall('showOverlay', {html: html});
        },
        hideOverlay: function() {
            return bridgeCall('hideOverlay', {});
        },
        musicPlay: function(filePath, title, artist) {
            return bridgeCall('musicPlay', {filePath: filePath, title: title || '', artist: artist || ''});
        },
        musicPause: function() {
            return bridgeCall('musicPause', {});
        },
        musicResume: function() {
            return bridgeCall('musicResume', {});
        },
        musicStop: function() {
            return bridgeCall('musicStop', {});
        },
        musicSeek: function(position) {
            return bridgeCall('musicSeek', {position: position});
        },
        musicGetProgress: function() {
            return bridgeCall('musicGetProgress', {});
        }
    };

    window.onTimerEnd = window.onTimerEnd || function() {};
    window.onMusicCompleted = window.onMusicCompleted || function() {};

    console.log('Bridge API initialized');
})();
"""

private const val overlayBridgeJavascript = """
(function() {
    if (window.__overlayBridgeReady) return;
    window.__overlayBridgeReady = true;

    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;

    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;
            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId);
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }
            var iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            iframe.src = url;
            document.body.appendChild(iframe);
            setTimeout(function() { document.body.removeChild(iframe); }, 100);
        });
    }

    window.Bridge = {
        hideOverlay: function() {
            return bridgeCall('hideOverlay', {});
        },
        getTimerState: function() {
            return bridgeCall('getTimerState', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        callAI: function(prompt, context) {
            return bridgeCall('callAI', {prompt: prompt, context: context || '{}'});
        }
    };

    window.onTimerEnd = function() {};

    console.log('Overlay Bridge API initialized');
})();
"""

private fun uriToBase64(context: android.content.Context, uri: Uri): String {
    val inputStream = context.contentResolver.openInputStream(uri) ?: return "null"
    val bytes = inputStream.readBytes()
    inputStream.close()
    return "\"" + Base64.encodeToString(bytes, Base64.NO_WRAP) + "\""
}
