package me.rerere.rikkahub.data.service

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.AssistantProactiveConfig
import me.rerere.rikkahub.data.datastore.ProactiveGlobalPolicy
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.plugin.di.pluginModule
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.testing.TestAndroidKeyStore
import me.rerere.rikkahub.utils.JsonInstant
import java.security.Security
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.uuid.Uuid

/** Real service entry, production Koin graph, real DataStore/Room/ChatService; only the network is fake. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProactiveMessageServiceIntegrationTest {
    private lateinit var app: Application
    private lateinit var database: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repository: ConversationRepository
    private lateinit var chat: ChatService
    private lateinit var scope: AppScope
    private lateinit var controller: ServiceController<ProactiveMessageTriggerService>
    private val keyStoreProvider = TestAndroidKeyStore.TestProvider()
    private val provider = RecordingProvider()
    private var startId = 0

    @Before fun setUp() {
        stopKoin()
        Security.addProvider(keyStoreProvider)
        app = ApplicationProvider.getApplicationContext()
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        // Production repository indexing uses this table; no native tokenizer is needed in a JVM fixture.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)",
        )
        startKoin {
            androidContext(app)
            allowOverride(true)
            modules(appModule, dataSourceModule, repositoryModule, pluginModule, module {
                single<AppDatabase> { database }
                // Robolectric ApplicationInfo can expose a null nativeLibraryDir, while the
                // production WorkspaceRepository eagerly wraps it in File(...).
                single<me.rerere.workspace.WorkspaceManager> {
                    me.rerere.workspace.WorkspaceManager(java.io.File(app.filesDir, "workspaces-test"))
                }
                single<ProviderManager> {
                    ProviderManager(OkHttpClient(), app).also { it.registerProvider("openai", provider) }
                }
            })
        }
        val koin = GlobalContext.get()
        scope = koin.get()
        settings = koin.get()
        repository = koin.get()
        // Prewarm on the real Robolectric main thread, as production startup does.
        chat = koin.get()
        controller = Robolectric.buildService(ProactiveMessageTriggerService::class.java).create()
        ProactiveTaskExecutor.initialize(app)
    }

    @After fun tearDown() {
        // Observe only the lifecycle Job so non-cancellable service cleanup finishes before Room closes.
        // This is not an injected fake or a bypass of onStartCommand / generation / persistence.
        if (::controller.isInitialized) {
            val field = ProactiveMessageTriggerService::class.java.getDeclaredField("serviceJob").apply { isAccessible = true }
            val serviceJob = field.get(controller.get()) as Job
            controller.destroy()
            runBlocking { withTimeout(20_000) { serviceJob.join() } }
        }
        if (::chat.isInitialized) chat.cleanup()
        if (::scope.isInitialized) {
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { withTimeout(10_000) { scope.coroutineContext[Job]?.join() } }
        }
        stopKoin()
        if (::database.isInitialized) database.close()
        Security.removeProvider(keyStoreProvider.name)
    }

    private data class Target(val assistant: Assistant, val conversation: Conversation, val model: Model)

    private suspend fun installTargets(count: Int): List<Target> {
        settings.settingsFlowRaw.first()
        val targets = (1..count).map { index ->
            val model = Model(modelId = "integration-model-$index")
            val assistant = Assistant(name = "participant-$index", chatModelId = model.id,
                localTools = emptyList(), enableMessageReactions = false, enableAvatarInteractions = false)
            Target(assistant, Conversation(assistantId = assistant.id, messageNodes = listOf(
                UIMessage.user("original-user-$index").toMessageNode(),
                UIMessage.assistant("original-reply-$index").toMessageNode(),
            )), model)
        }
        val foreground = Assistant(name = "foreground-not-enrolled", chatModelId = targets.first().model.id)
        val providers = targets.mapIndexed { index, target -> ProviderSetting.OpenAI(
            name = "provider-$index", apiKey = "synthetic", models = listOf(target.model),
        ) }
        settings.update(Settings(assistantId = foreground.id, assistants = listOf(foreground) + targets.map { it.assistant },
            chatModelId = targets.first().model.id, providers = providers,
            proactiveMessageSetting = ProactiveMessageSetting(enabled = true, activeHoursEnabled = false,
                fullToolChancePercent = 0, maxFollowUpMessages = 1,
                assistantConfigs = targets.map { AssistantProactiveConfig(it.assistant.id.toString(), it.conversation.id.toString()) },
                globalPolicy = ProactiveGlobalPolicy(budgetZoneId = "UTC", dailyModelTurnLimit = 40))))
        targets.forEach { repository.insertConversation(it.conversation) }
        return targets
    }

    private fun trigger() {
        val intent = Intent(app, ProactiveMessageTriggerService::class.java).apply {
            action = ProactiveMessageService.ACTION_PROACTIVE_MESSAGE
            putExtra(ProactiveMessageService.EXTRA_TRIGGER_SOURCE, "manual")
            putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
        }
        assertEquals(android.app.Service.START_NOT_STICKY, controller.get().onStartCommand(intent, 0, ++startId))
    }

    private suspend fun awaitCondition(description: String, predicate: suspend () -> Boolean) {
        withTimeout(20_000) {
            while (!predicate()) {
                // Service runs on real IO; pump only the main-thread work it posts (Settings/ChatService).
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
        }
        assertTrue(description, predicate())
    }

    private suspend fun awaitBody(target: Target, body: String = "body:${target.model.modelId}") {
        awaitCondition("Room must contain the provider body for ${target.assistant.name}") {
            repository.getConversationById(target.conversation.id)?.currentMessages?.any {
                it.role == MessageRole.ASSISTANT && it.toText() == body
            } == true
        }
    }

    @Test fun nonForegroundEnrolledAssistantUsesItsProviderAndPersistsInItsRoomWindow() = runBlocking {
        val target = installTargets(1).single()
        assertNotEquals(target.assistant.id, settings.settingsFlow.value.assistantId)
        trigger()
        awaitBody(target)
        assertEquals(listOf(target.model.modelId), provider.requests.map { it.modelId })
        val saved = repository.getConversationById(target.conversation.id)!!
        assertEquals(target.assistant.id, saved.assistantId)
        assertEquals(listOf(target.conversation.currentMessages.first().id),
            saved.currentMessages.filter { it.role == MessageRole.USER }.map { it.id })
    }

    @Test fun oneManualIntentDispatchesBothAssistantsToTheirOwnProvidersAndRoomWindows() = runBlocking {
        val targets = installTargets(2)
        trigger()
        targets.forEach { awaitBody(it) }
        assertEquals(targets.map { it.model.modelId }.toSet(), provider.requests.map { it.modelId }.toSet())
        assertEquals(2, provider.requests.size)
        assertEquals(2, provider.requests.map { it.providerId }.distinct().size)
        targets.forEach { target ->
            val saved = repository.getConversationById(target.conversation.id)!!
            assertEquals(target.assistant.id, saved.assistantId)
            assertFalse(saved.currentMessages.any { it.toText() == "body:${targets.first { other -> other != target }.model.modelId}" })
        }
    }

    @Test fun manualBypassesStopAndFollowupLimitWithoutCreatingAnotherUserMessage() = runBlocking {
        val target = installTargets(1).single()
        val stateStore = ProactiveMessageStateStore(app, "${target.assistant.id}_${target.conversation.id}")
        var state = stateStore.synchronizeWithUser(target.conversation.currentMessages.first().id.toString())
        state = stateStore.recordSent(state, listOf(Uuid.random().toString()), "old proactive reply")
        stateStore.stopUntilUserReturns(state)
        val stopped = stateStore.synchronizeWithUser(target.conversation.currentMessages.first().id.toString())
        assertTrue(stopped.stopUntilUserReturns)
        assertEquals(1, stopped.followUpCount)
        trigger()
        awaitBody(target)
        val saved = repository.getConversationById(target.conversation.id)!!
        assertEquals(target.conversation.currentMessages.filter { it.role == MessageRole.USER }.map { it.id },
            saved.currentMessages.filter { it.role == MessageRole.USER }.map { it.id })
        assertEquals(1, provider.requests.size)
    }

    @Test fun blockedCommitPendingRecoveryDoesNotHoldPeerServiceDispatch() = runBlocking {
        val targets = installTargets(2)
        val pending = targets.first()
        val peer = targets.last()
        val host = ProactiveHostSnapshot(settings.settingsFlow.value.proactiveMessageSetting,
            targets.map { it.assistant.id.toString() }.toSet(),
            targets.associate { it.conversation.id.toString() to it.assistant.id.toString() },
            targets.associate { it.conversation.id.toString() to it.conversation.currentMessages.first().id.toString() })
        val store = ProactiveTaskStore(app)
        val task = ProactiveTask(assistantId = pending.assistant.id.toString(),
            conversationId = pending.conversation.id.toString(), trigger = "manual",
            anchorUserMessageId = pending.conversation.currentMessages.first().id.toString(),
            createdAtMillis = System.currentTimeMillis())
        assertTrue(store.enqueue(task))
        assertEquals(task.taskId, store.claimNext(host, System.currentTimeMillis(), manualBatch = true)?.taskId)
        val committed = UIMessage.assistant("recovered-body").copy(id = Uuid.parse(proactiveCommitMessageId(task.taskId, 0)))
        assertTrue(store.beginCommit(task.taskId, host, System.currentTimeMillis(),
            listOf(committed.id.toString()), JsonInstant.encodeToString(listOf(committed))))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) {
            chat.getOrCreateSession(pending.conversation.id).withStateLock {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            trigger() // Recovery enters the real session lock, outside the dispatcher mutex.
            awaitCondition("first service run must enter durable CP recovery") {
                store.pendingCommits().singleOrNull()?.outcome == "commit_in_progress"
            }
            trigger() // A separate onStartCommand must dispatch the unrelated peer now.
            awaitBody(peer)
            assertFalse("peer must finish while CP is still blocked", release.isCompleted)
            assertEquals(listOf(peer.model.modelId), provider.requests.map { it.modelId })
        } finally {
            release.complete(Unit)
            blocker.join()
        }
        awaitBody(pending, "recovered-body")
    }

    private data class Request(val modelId: String, val providerId: Uuid)
    private class RecordingProvider : Provider<ProviderSetting> {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk {
            requests += Request(params.model.modelId, providerSetting.id)
            return MessageChunk(id = "synthetic-${requests.size}", model = params.model.modelId,
                choices = listOf(UIMessageChoice(0, null, UIMessage.assistant("body:${params.model.modelId}"), "stop")))
        }
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> = flow {
            emit(generateText(providerSetting, messages, params))
        }
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult = error("not used")
    }
}
