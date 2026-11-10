/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

/**
 * 礼物自定义（展示名 + 赠礼文案偏好）的**全部**约束，纯 Kotlin、无 Android 依赖。
 *
 * 为什么集中在这里：用户要的是"能改名字/改说法"，但**协议不能跟着变**。
 * 所以：
 * - 名字只能是 key 落在 [VideoGiftCatalog.ids]（5 个稳定 id）里的条目，
 *   未知 key 直接被丢掉 —— 用户无法把标签改成程序不认识的值；
 * - id、特效档位、白名单、每条一次、禁真实金额等规则全部来自代码，不来自设置；
 * - 文案偏好只拼在提示词的**最后一段**，并且紧跟一句"这只是措辞偏好"，
 *   模型因此不会把它读成"规则被改掉了"。
 *
 * 展示名的取值顺序是：**当前设置** → 内置常量。历史记录不读这里 ——
 * 礼物卡/注解在写入当时就把名字快照存进 `VideoCallGift.giftName`，以后改名不会改写历史。
 */
object VideoGiftCustomization {
    /** 单个礼物展示名的长度上限（按字符，中文算 1 个）。 */
    const val MAX_DISPLAY_NAME_LENGTH: Int = 12

    /** 赠礼文案偏好的长度上限。 */
    const val MAX_GIFT_MESSAGE_LENGTH: Int = 120

    /** 收口空白：换行/制表/连续空格都不进提示词，避免把提示词结构撑坏。 */
    fun sanitizeDisplayName(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(MAX_DISPLAY_NAME_LENGTH)

    fun sanitizeGiftMessage(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().take(MAX_GIFT_MESSAGE_LENGTH)

    /** 只保留"白名单 id → 非空名字"的条目；未知 id / 空白名一律丢弃。 */
    fun sanitizedNames(setting: VideoCallSetting): Map<String, String> =
        sanitizedNames(setting.giftDisplayNames)

    fun sanitizedNames(raw: Map<String, String>): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        VideoGiftCatalog.all.forEach { gift ->
            val name = raw[gift.id]?.let(::sanitizeDisplayName).orEmpty()
            if (name.isNotEmpty() && name != gift.displayName) result[gift.id] = name
        }
        return result
    }

    fun sanitizedMessage(setting: VideoCallSetting): String = sanitizeGiftMessage(setting.giftMessage)

    /** 当前设置下这个礼物该显示成什么（写注解/气泡/汇总都走这一处）。 */
    fun nameOf(setting: VideoCallSetting, gift: VideoGift): String =
        sanitizedNames(setting)[gift.id] ?: gift.displayName

    fun nameOf(names: Map<String, String>, gift: VideoGift): String =
        sanitizedNames(names)[gift.id] ?: gift.displayName

    /** 给模型看的清单：与解析器同一份 id 白名单，名字用用户当前配置。 */
    fun labelList(setting: VideoCallSetting): String = labelList(sanitizedNames(setting))

    fun labelList(names: Map<String, String>): String {
        val sanitized = sanitizedNames(names)
        return VideoGiftCatalog.all.joinToString("、") { gift ->
            "${gift.id}（${sanitized[gift.id] ?: gift.displayName}）"
        }
    }

    /** 改一个礼物的展示名（保存时调用；非法 id 原样返回，绝不新增 key）。 */
    fun withDisplayName(setting: VideoCallSetting, giftId: String, raw: String): VideoCallSetting {
        if (VideoGiftCatalog.byId(giftId) == null) return setting
        val next = LinkedHashMap(setting.giftDisplayNames)
        val name = sanitizeDisplayName(raw)
        if (name.isEmpty()) next.remove(giftId) else next[giftId] = name
        return setting.copy(giftDisplayNames = sanitizedNames(next))
    }

    /** 批量保存（设置页"保存"按钮）：只认白名单 id，其余整条丢弃。 */
    fun withDisplayNames(
        setting: VideoCallSetting,
        names: Map<String, String>,
        message: String = setting.giftMessage,
    ): VideoCallSetting = setting.copy(
        giftDisplayNames = sanitizedNames(names),
        giftMessage = sanitizeGiftMessage(message),
    )

    fun withGiftMessage(setting: VideoCallSetting, raw: String): VideoCallSetting =
        setting.copy(giftMessage = sanitizeGiftMessage(raw))

    /** 恢复默认：清空映射与文案（内置名就是默认值）。 */
    fun withDefaults(setting: VideoCallSetting): VideoCallSetting =
        setting.copy(giftDisplayNames = emptyMap(), giftMessage = "")

    /** 有没有任何自定义（设置页据此显示"恢复默认"是否可用）。 */
    fun isCustomized(setting: VideoCallSetting): Boolean =
        sanitizedNames(setting).isNotEmpty() || sanitizedMessage(setting).isNotEmpty()
}

/**
 * 用**当前设置的名字**汇总礼物计数（通话卡礼物行 / 下一轮提示词共用）。
 *
 * 历史记录不重新计算：结束通话卡的 `giftSummary` 在挂断那一刻就写成了字符串，
 * 以后改名不会改写旧卡（与 `VideoCallGift.giftName` 的快照语义一致）。
 */
fun VideoGiftTally.summary(names: Map<String, String>): String {
    val sanitized = VideoGiftCustomization.sanitizedNames(names)
    return VideoGift.entries.mapNotNull { gift ->
        countOf(gift).takeIf { it > 0 }?.let { "${sanitized[gift.id] ?: gift.displayName} ×$it" }
    }.joinToString(" · ")
}

/** 结束通话卡上那一行；[names] 为空时就是内置名（老行为不变）。 */
fun giftSummaryLine(tally: VideoGiftTally, names: Map<String, String> = emptyMap()): String =
    if (tally.isEmpty) "" else "视频礼物：${tally.summary(names)}"
