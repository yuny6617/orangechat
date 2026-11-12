/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

/**
 * Pure gate for assistant-initiated image generation. Kept separate from the executor so the four
 * native conditions can be tested without Android.
 *
 * `allowGeneratedImages` is deliberately not enough on its own: it must also be a subset of the
 * base image grant, the plugin must hold `localdata.media`, and the user must have configured an
 * image model/provider. Any missing condition means the model is not even offered the `image`
 * field.
 */
internal object LocalTaskImagePolicy {
    fun allows(
        settings: LocalTaskSettings,
        mediaGranted: Boolean,
        imageModelConfigured: Boolean,
    ): Boolean = settings.allowImages &&
        settings.allowGeneratedImages &&
        mediaGranted &&
        imageModelConfigured
}
