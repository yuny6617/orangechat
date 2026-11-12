/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

/**
 * Phase 17 stability — context every tool factory in [LocalTools.getTools] sees about WHO
 * is invoking it. Until this layer existed, tools that needed to know the calling
 * conversation / assistant id (sub-agent recursion guard, workflow_create authoring-id
 * persistence) had no way to read it — both shipped with placeholder defaults and silent
 * gaps the audit caught.
 *
 * Convention: every getTools() caller MUST construct a ToolInvocationContext with the most
 * specific data it has. The default ([EMPTY]) is a no-op safe fallback used when the
 * caller doesn't track the data (legacy / one-off paths) — but factories should treat the
 * empty context as "I don't know" not "no constraints", and apply conservative defaults.
 *
 * Fields:
 *  - [callerAssistantId]: the assistant whose toggles are being dispatched. ChatService
 *    knows this from `settings.getCurrentAssistant().id`. Cron / workflow / sub-agent
 *    paths know it from their respective entity's assistant id.
 *  - [callerConversationId]: the conversation-uuid of the user-facing chat (interactive)
 *    or the headless conversation (cron / workflow / sub-agent / external-automation).
 *  - [isHeadless]: true when the dispatch is happening from a system flow rather than the
 *    user typing in a chat. Sub-agents, cron jobs, workflows, and external-automation
 *    runs all set this to true so the recursion guard fires.
 *  - [modelCanSeeImages]: true iff the model handling this turn — after provider + conversation
 *    overrides have been resolved — has image input in its modalities. The LocalMoments image
 *    tools read this before attaching image bytes: a text-only model gets metadata plus a
 *    `model_cannot_see_images` denial instead of pixels it would confabulate about.
 *    Defaults to `false` (conservative): a caller that does not know which model actually runs
 *    this turn must not claim image vision. ChatService, the only LLM-driven interactive
 *    dispatch path, always computes it explicitly from `settings.getChatModelOf(conversation)`,
 *    so a conversation-level model override is honoured. Headless callers (workflow / sub-agent)
 *    keep the false default; image bytes stay denied rather than being handed to a model that
 *    may not understand them.
 */
data class ToolInvocationContext(
    val callerAssistantId: String? = null,
    val callerConversationId: String? = null,
    val isHeadless: Boolean = false,
    val modelCanSeeImages: Boolean = false,
) {
    companion object {
        /** No-knowledge fallback. Factories that depend on context MUST handle this. */
        val EMPTY = ToolInvocationContext()
    }
}
