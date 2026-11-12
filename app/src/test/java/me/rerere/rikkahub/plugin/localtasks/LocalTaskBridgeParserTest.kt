/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalTaskBridgeParserTest {
    private val parser = LocalTaskBridgeRequestParser

    @Test
    fun `start only accepts post assistant and client key`() {
        val request = parser.parse(
            """{"version":1,"method":"task.start","params":{"postId":"post_1","assistantId":"${ASSISTANT_A.id}","clientKey":"k1"}}""",
        )

        assertEquals(LocalTaskBridgeRequestParser.METHOD_START, request.method)
        assertEquals("post_1", request.params["postId"]?.toString()?.trim('"'))
    }

    @Test
    fun `a page cannot pass media ids through start`() {
        val error = assertThrows(LocalTaskException::class.java) {
            parser.parse(
                """{"version":1,"method":"task.start","params":{"postId":"post_1","mediaIds":["ldm_12345678"]}}""",
            )
        }

        assertEquals(LocalTaskErrorCode.INVALID_REQUEST, error.code)
    }

    @Test
    fun `a page cannot pass publishing or permission flags`() {
        listOf("publish", "autoPublish", "permissions", "allowImages", "maxRounds").forEach { key ->
            val error = assertThrows(LocalTaskException::class.java) {
                parser.parse(
                    """{"version":1,"method":"task.start","params":{"postId":"post_1","$key":true}}""",
                )
            }
            assertEquals(LocalTaskErrorCode.INVALID_REQUEST, error.code)
        }
    }

    @Test
    fun `settings are read only`() {
        val error = assertThrows(LocalTaskException::class.java) {
            parser.parse(
                """{"version":1,"method":"task.settings","params":{"enabled":true}}""",
            )
        }

        assertEquals(LocalTaskErrorCode.INVALID_REQUEST, error.code)
    }

    @Test
    fun `unknown methods are rejected`() {
        val error = assertThrows(LocalTaskException::class.java) {
            parser.parse("""{"version":1,"method":"task.settings.update","params":{}}""")
        }

        assertEquals(LocalTaskErrorCode.UNKNOWN_METHOD, error.code)
    }

    @Test
    fun `unsupported versions are rejected`() {
        val error = assertThrows(LocalTaskException::class.java) {
            parser.parse("""{"version":2,"method":"task.settings","params":{}}""")
        }

        assertEquals(LocalTaskErrorCode.UNSUPPORTED_VERSION, error.code)
    }

    @Test
    fun `identity fields in the root are rejected`() {
        val error = assertThrows(LocalTaskException::class.java) {
            parser.parse("""{"version":1,"method":"task.start","actorId":"x","params":{"postId":"post_1"}}""")
        }

        assertEquals(LocalTaskErrorCode.INVALID_REQUEST, error.code)
    }

    @Test
    fun `cancel all and review resolution are allowed shapes`() {
        val cancelAll = parser.parse("""{"version":1,"method":"task.cancel","params":{"all":true}}""")
        val review = parser.parse(
            """{"version":1,"method":"task.review.resolve","params":{"reviewId":"ltr_1","approve":true,"body":"edited"}}""",
        )

        assertEquals(LocalTaskBridgeRequestParser.METHOD_CANCEL, cancelAll.method)
        assertEquals(LocalTaskBridgeRequestParser.METHOD_REVIEW_RESOLVE, review.method)
    }

    @Test
    fun `review reject without a body is allowed`() {
        val review = parser.parse(
            """{"version":1,"method":"task.review.resolve","params":{"reviewId":"ltr_1","approve":false}}""",
        )

        assertEquals(LocalTaskBridgeRequestParser.METHOD_REVIEW_RESOLVE, review.method)
    }

    @Test
    fun `review body is bounded before the native dialog is shown`() {
        val tooLong = "x".repeat(LocalTasksApi.MAX_MAX_OUTPUT_CHARS + 1)
        val error = assertThrows(LocalTaskException::class.java) {
            parser.parse(reviewRequest("""{"reviewId":"ltr_1","approve":true,"body":"$tooLong"}"""))
        }

        assertEquals(LocalTaskErrorCode.INVALID_REQUEST, error.code)
    }

    @Test
    fun `review body must be a non blank string when supplied`() {
        listOf(
            """{"reviewId":"ltr_1","approve":true,"body":""}""",
            """{"reviewId":"ltr_1","approve":true,"body":"   "}""",
            """{"reviewId":"ltr_1","approve":true,"body":null}""",
            """{"reviewId":"ltr_1","approve":true,"body":42}""",
        ).forEach { params ->
            val error = assertThrows(LocalTaskException::class.java) { parser.parse(reviewRequest(params)) }
            assertEquals(LocalTaskErrorCode.INVALID_REQUEST, error.code)
        }
    }

    @Test
    fun `review approve must be a boolean`() {
        val error = assertThrows(LocalTaskException::class.java) {
            parser.parse(reviewRequest("""{"reviewId":"ltr_1","approve":"yes"}"""))
        }

        assertEquals(LocalTaskErrorCode.INVALID_REQUEST, error.code)
    }

    private fun reviewRequest(paramsJson: String): String =
        """{"version":1,"method":"task.review.resolve","params":$paramsJson}"""
}
