/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskImagePolicyTest {
    private val enabled = LocalTaskSettings(
        allowImages = true,
        allowGeneratedImages = true,
    ).normalized()

    @Test
    fun `all four native conditions are required`() {
        assertTrue(LocalTaskImagePolicy.allows(enabled, mediaGranted = true, imageModelConfigured = true))
        assertFalse(LocalTaskImagePolicy.allows(enabled, mediaGranted = false, imageModelConfigured = true))
        assertFalse(LocalTaskImagePolicy.allows(enabled, mediaGranted = true, imageModelConfigured = false))
    }

    @Test
    fun `the base image switch and the dedicated switch are both required`() {
        assertFalse(
            LocalTaskImagePolicy.allows(
                enabled.copy(allowImages = false).normalized(),
                mediaGranted = true,
                imageModelConfigured = true,
            ),
        )
        assertFalse(
            LocalTaskImagePolicy.allows(
                enabled.copy(allowGeneratedImages = false).normalized(),
                mediaGranted = true,
                imageModelConfigured = true,
            ),
        )
    }
}
