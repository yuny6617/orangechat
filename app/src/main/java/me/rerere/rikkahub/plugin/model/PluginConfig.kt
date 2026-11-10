package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.json.JsonElement

/**
 * Effective values are read-time defaults plus explicit saved overrides; neither input is mutated.
 * Key presence wins, including false, zero, empty strings and JsonNull. A missing key restores
 * its manifest default. Kotlin null means no default; JsonNull is an explicit JSON default.
 * Unknown saved keys are retained for plugin compatibility. Persist overrides, not this result.
 */
fun effectivePluginConfig(
    fields: List<PluginConfigField>,
    savedConfig: Map<String, JsonElement>
): Map<String, JsonElement> = buildMap {
    fields.forEach { field ->
        if (!containsKey(field.name)) {
            field.default?.let { put(field.name, it) }
        }
    }
    putAll(savedConfig)
}

/** Model-specific runtime enrichment runs after merging, and never changes the saved overrides. */
fun resolvePluginConfig(
    fields: List<PluginConfigField>,
    savedConfig: Map<String, JsonElement>,
    resolveModels: (MutableMap<String, JsonElement>) -> Unit
): Map<String, JsonElement> = effectivePluginConfig(fields, savedConfig).toMutableMap().also(resolveModels)
