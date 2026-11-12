/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.Closeable
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class LocalDataTestEnvironment(
    pluginId: String = "test.localdata.${UUID.randomUUID()}",
) : Closeable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val pluginId: String = pluginId
    var now: Long = 1_700_000_000_000L
    var autoPublish: Boolean = true

    val assistantA = LocalDataAssistantRef("11111111-1111-1111-1111-111111111111", "Alice", "alice.png")
    val assistantB = LocalDataAssistantRef("22222222-2222-2222-2222-222222222222", "Bob", "bob.png")
    val assistantC = LocalDataAssistantRef("33333333-3333-3333-3333-333333333333", "Carol", "carol.png")
    val directory: LocalAssistantDirectory = LocalAssistantDirectory.of(listOf(assistantA, assistantB, assistantC))

    val database = LocalDataDatabase(context, pluginId)
    val store = LocalDataStore(database)
    val inspector = LocalImageInspector { _, _ -> LocalImageInfo("image/png", 4, 4) }
    val thumbnailer = LocalThumbnailer { _, target, _ ->
        target.writeBytes(byteArrayOf(1, 2, 3, 4))
        true
    }
    val media = LocalDataMediaStore(database, inspector, thumbnailer) { now }
    val backup = LocalDataBackup(database, store, media) { now }
    val dispatcher = LocalDataDispatcher(store, media)

    fun contextFor(
        actor: LocalDataActor = LocalDataActor.User,
        grant: LocalDataGrant = LocalDataGrant.all(),
        userDisplayName: String = "Owner",
        autoPublish: Boolean = this.autoPublish,
        timestamp: Long = now,
    ): LocalDataCallContext = LocalDataCallContext(
        actor = actor,
        grant = grant,
        assistantDirectory = directory,
        assistantAutoPublish = autoPublish,
        userDisplayName = userDisplayName,
        now = { timestamp },
    )

    fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        actor: LocalDataActor = LocalDataActor.User,
        grant: LocalDataGrant = LocalDataGrant.all(),
        timestamp: Long = now,
        autoPublish: Boolean = this.autoPublish,
    ): LocalDataEnvelope = dispatcher.dispatch(
        LocalDataRequest(version = 1, method = method, params = params),
        contextFor(actor = actor, grant = grant, timestamp = timestamp, autoPublish = autoPublish),
    )

    fun callJson(
        raw: String,
        actor: LocalDataActor = LocalDataActor.User,
        grant: LocalDataGrant = LocalDataGrant.all(),
    ): String = dispatcher.dispatchJson(raw, contextFor(actor = actor, grant = grant))

    fun request(method: String, params: JsonObject = JsonObject(emptyMap())): String =
        buildJsonObject {
            put("version", 1)
            put("method", method)
            put("params", params)
        }.toString()

    fun createPost(
        body: String = "hello moments",
        visibility: String = "all_assistants",
        selected: List<String> = emptyList(),
        mediaIds: List<String> = emptyList(),
        chatSnapshot: LocalChatSnapshotDto? = null,
        clientKey: String? = null,
        actor: LocalDataActor = LocalDataActor.User,
        timestamp: Long = now,
    ): LocalPostDto {
        val params = buildJsonObject {
            put("body", body)
            put("visibility", visibility)
            put("mediaIds", stringArray(mediaIds))
            put("selectedAssistantIds", stringArray(selected))
            if (chatSnapshot != null) {
                put(
                    "chatSnapshot",
                    LocalDataJson.json.encodeToJsonElement(LocalChatSnapshotDto.serializer(), chatSnapshot),
                )
            }
            if (clientKey != null) put("clientKey", clientKey)
        }
        val envelope = call(LocalDataMethods.CREATE, params, actor, timestamp = timestamp)
        check(envelope.ok) { envelope.error?.message ?: "create failed" }
        return LocalDataJson.json.decodeFromJsonElement(
            LocalPostCreateResultDto.serializer(),
            envelope.data!!.jsonObject,
        ).post
    }

    fun comment(
        postId: String,
        body: String = "nice",
        actor: LocalDataActor = LocalDataActor.User,
        replyToId: String? = null,
        timestamp: Long = now,
    ): LocalDataEnvelope {
        val params = buildJsonObject {
            put("postId", postId)
            put("body", body)
            if (replyToId != null) put("replyToId", replyToId)
        }
        return call(LocalDataMethods.COMMENT_CREATE, params, actor, timestamp = timestamp)
    }

    fun importMedia(seed: Int = 1): LocalMediaDto {
        val bytes = ByteArray(64) { index -> (seed + index).toByte() }
        return media.importImage(bytes, "image/png", "test-$seed.png").media
    }

    fun list(
        actor: LocalDataActor = LocalDataActor.User,
        params: JsonObject = JsonObject(emptyMap()),
    ): LocalPostPageDto {
        val envelope = call(LocalDataMethods.LIST, params, actor)
        check(envelope.ok) { envelope.error?.message ?: "list failed" }
        return LocalDataJson.json.decodeFromJsonElement(
            LocalPostPageDto.serializer(),
            envelope.data!!.jsonObject,
        )
    }

    override fun close() {
        database.close()
        context.deleteDatabase(database.paths.databaseName)
        database.paths.root.deleteRecursively()
    }

    private fun stringArray(values: List<String>): JsonElement = buildJsonArray {
        values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }
}

internal fun errorCode(envelope: LocalDataEnvelope): String? = envelope.error?.code

internal fun LocalPostPageDto.ids(): List<String> = items.map { it.id }
