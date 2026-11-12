/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localdata

import android.app.Application
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalDataIdentityTest {
    private val env = LocalDataTestEnvironment()
    private val actorA = LocalDataActor.Assistant(env.assistantA.id, env.assistantA.name, env.assistantA.avatar)

    @Test
    fun unknownRootFieldsAreRejectedBeforeDispatch() {
        val response = envelope(env.callJson("""{"version":1,"method":"localdata.info","params":{},"unexpected":true}"""))
        assertFalse(response.ok)
        assertEquals("invalid_request", response.error?.code)
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun envelope(raw: String): LocalDataEnvelope =
        LocalDataJson.json.decodeFromString(LocalDataEnvelope.serializer(), raw)

    @Test
    fun `identity fields in params or root are rejected`() {
        val inParams = envelope(
            env.callJson("""{"version":1,"method":"moments.list","params":{"actorId":"assistant-a"}}"""),
        )
        assertEquals("invalid_request", errorCode(inParams))
        val inRoot = envelope(
            env.callJson("""{"version":1,"method":"moments.list","params":{},"actorId":"assistant-a"}"""),
        )
        assertEquals("invalid_request", errorCode(inRoot))
        val viewer = envelope(
            env.callJson("""{"version":1,"method":"moments.list","params":{"viewerId":"x"}}"""),
        )
        assertEquals("invalid_request", errorCode(viewer))
    }

    @Test
    fun `assistant actor must exist in the host directory`() {
        val envelope = env.dispatcher.dispatch(
            LocalDataRequest(1, LocalDataMethods.LIST, JsonObject(emptyMap())),
            LocalDataCallContext(
                actor = actorA,
                grant = LocalDataGrant.all(),
                assistantDirectory = LocalDirectoryWithoutAlice,
            ),
        )
        assertFalse(envelope.ok)
        assertEquals("forbidden", errorCode(envelope))
    }

    @Test
    fun `capabilities gate every mutating method`() {
        val readOnly = LocalDataGrant.of(LocalDataCapability.READ)
        assertTrue(env.call(LocalDataMethods.LIST, grant = readOnly).ok)
        assertEquals(
            "capability_denied",
            errorCode(env.call(
                LocalDataMethods.CREATE,
                buildJsonObject { put("body", "x"); put("visibility", "all_assistants") },
                grant = readOnly,
            )),
        )
        assertEquals(
            "capability_denied",
            errorCode(env.call(
                LocalDataMethods.COMMENT_CREATE,
                buildJsonObject { put("postId", "p_missing"); put("body", "x") },
                grant = readOnly,
            )),
        )
    }

    @Test
    fun `assistant publishing needs the publish grant and host auto publish`() {
        val params = buildJsonObject { put("body", "auto"); put("visibility", "all_assistants") }
        assertEquals(
            "capability_denied",
            errorCode(env.call(
                LocalDataMethods.CREATE,
                params,
                actorA,
                grant = LocalDataGrant.of(LocalDataCapability.WRITE),
            )),
        )
        assertEquals(
            "capability_denied",
            errorCode(env.call(
                LocalDataMethods.CREATE,
                params,
                actorA,
                grant = LocalDataGrant.of(LocalDataCapability.WRITE, LocalDataCapability.PUBLISH),
                autoPublish = false,
            )),
        )
        assertTrue(env.call(
            LocalDataMethods.CREATE,
            params,
            actorA,
            grant = LocalDataGrant.of(LocalDataCapability.WRITE, LocalDataCapability.PUBLISH),
            autoPublish = true,
        ).ok)
    }

    @Test
    fun `assistant can save a draft without publish permission`() {
        assertTrue(env.call(
            LocalDataMethods.DRAFT_SAVE,
            buildJsonObject {
                put("body", "draft")
                put("visibility", "all_assistants")
            },
            actorA,
            grant = LocalDataGrant.of(LocalDataCapability.WRITE),
        ).ok)
    }

    @Test
    fun `version method and payload errors are stable`() {
        assertEquals(
            "unsupported_version",
            errorCode(envelope(env.callJson("""{"version":2,"method":"moments.list","params":{}}"""))),
        )
        assertEquals(
            "unknown_method",
            errorCode(envelope(env.callJson("""{"version":1,"method":"moments.nope","params":{}}"""))),
        )
        assertEquals(
            "invalid_request",
            errorCode(envelope(env.callJson("""{"version":1,"method":"moments.list","params":"x"}"""))),
        )
        assertEquals(
            "invalid_request",
            errorCode(envelope(env.callJson("""not json"""))),
        )
        assertEquals(
            "payload_too_large",
            errorCode(env.call(
                LocalDataMethods.CREATE,
                buildJsonObject {
                    put("body", "x".repeat(LocalDataApi.MAX_BODY_CHARS + 1))
                    put("visibility", "all_assistants")
                },
            )),
        )
    }

    @Test
    fun `chat snapshot rejects system and tool messages`() {
        val params = buildJsonObject {
            put("body", "card")
            put("visibility", "all_assistants")
            put(
                "chatSnapshot",
                buildJsonObject {
                    put(
                        "messages",
                        kotlinx.serialization.json.buildJsonArray {
                            add(buildJsonObject { put("role", "system"); put("text", "secret") })
                        },
                    )
                },
            )
        }
        assertEquals("invalid_request", errorCode(env.call(LocalDataMethods.CREATE, params)))
    }

    @Test
    fun `selected audience must reference existing assistants`() {
        assertEquals(
            "invalid_request",
            errorCode(env.call(
                LocalDataMethods.CREATE,
                buildJsonObject {
                    put("body", "x")
                    put("visibility", "selected")
                    put(
                        "selectedAssistantIds",
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("99999999-9999-9999-9999-999999999999"))
                        },
                    )
                },
            )),
        )
    }

    @Test
    fun `local user display name is host supplied and not page supplied`() {
        val post = env.createPost()
        assertEquals("Owner", post.author.name)

        val tampered = buildJsonObject {
            put("body", "x")
            put("visibility", "all_assistants")
            put("authorId", "someone-else")
        }
        assertEquals("invalid_request", errorCode(env.call(LocalDataMethods.CREATE, tampered)))
    }

    @Test
    fun `envelope has ok data on success and ok false with error on failure`() {
        val success = env.call(LocalDataMethods.INFO)
        assertTrue(success.ok)
        assertNotNull(success.data)
        assertEquals(null, success.error)

        val failure = env.call(LocalDataMethods.GET, buildJsonObject { put("postId", "p_missing") })
        assertFalse(failure.ok)
        assertEquals(null, failure.data)
        assertNotNull(failure.error)
        assertEquals("not_found", failure.error?.code)
        assertFalse(failure.error?.retryable ?: true)
    }
}

private val LocalDirectoryWithoutAlice: LocalAssistantDirectory =
    LocalAssistantDirectory.of(emptyList<LocalDataAssistantRef>())
