package me.rerere.rikkahub.plugin.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginConfigTest {
    private fun field(name: String, default: JsonElement? = null, type: String = "string") =
        PluginConfigField(name = name, type = type, label = name, default = default)

    private val fields = listOf(
        field("label", JsonPrimitive("initial")),
        field("enabled", JsonPrimitive(true), "boolean"),
        field("count", JsonPrimitive(7), "number")
    )

    private fun restart(saved: Map<String, JsonElement>): Map<String, JsonElement> =
        Json.decodeFromString(Json.encodeToString(saved))

    @Test fun freshInstallCanResolveToolConfigWithoutOpeningSettings() {
        val saved = emptyMap<String, JsonElement>()
        val runtime = resolvePluginConfig(fields, saved) { }
        assertEquals(JsonPrimitive("initial"), runtime["label"])
        assertEquals(JsonPrimitive(true), runtime["enabled"])
        assertEquals(JsonPrimitive(7), runtime["count"])
        assertTrue(saved.isEmpty())
    }

    @Test fun savingUneditedDraftDoesNotPersistDefaults() {
        val draft = emptyMap<String, JsonElement>().toMutableMap()
        val displayed = effectivePluginConfig(fields, draft)
        val persisted = restart(draft)
        assertTrue(persisted.isEmpty())
        assertEquals(displayed, resolvePluginConfig(fields, persisted) { })
        assertEquals(JsonPrimitive("updated"), effectivePluginConfig(
            listOf(field("label", JsonPrimitive("updated"))), persisted
        )["label"])
    }

    @Test fun overridesAndUnknownKeysSurviveSerializationRestart() {
        val saved = mapOf("label" to JsonPrimitive("custom"), "private_extra" to JsonPrimitive("fixture"))
        val reloaded = restart(saved)
        val effective = effectivePluginConfig(fields, reloaded)
        saved.forEach { (key, value) -> assertEquals(value, effective[key]) }
        assertEquals(saved, reloaded)
    }

    @Test fun manifestAddedDefaultsDoNotOverwriteExistingValues() {
        val saved = mapOf("label" to JsonPrimitive("custom"))
        val upgraded = fields + field("added", JsonPrimitive("new"))
        val effective = effectivePluginConfig(upgraded, restart(saved))
        assertEquals(JsonPrimitive("custom"), effective["label"])
        assertEquals(JsonPrimitive("new"), effective["added"])
        assertFalse(saved.containsKey("added"))
    }

    @Test fun falseZeroEmptyAndJsonNullAreExplicitOverrides() {
        val saved = mapOf(
            "enabled" to JsonPrimitive(false), "count" to JsonPrimitive(0),
            "label" to JsonPrimitive(""), "nullable" to JsonNull
        )
        val effective = effectivePluginConfig(fields + field("nullable", JsonPrimitive("fallback")), restart(saved))
        assertEquals(saved, effective)
    }

    @Test fun missingDefaultAndExplicitNullDefaultDiffer() {
        val effective = effectivePluginConfig(listOf(field("absent"), field("null_default", JsonNull)), emptyMap())
        assertFalse(effective.containsKey("absent"))
        assertTrue(effective.containsKey("null_default"))
        assertEquals(JsonNull, effective["null_default"])
    }

    @Test fun clearingAndRestoringDefaultAreDifferentOperations() {
        val draft = mutableMapOf<String, JsonElement>("label" to JsonPrimitive(""))
        assertEquals(JsonPrimitive(""), effectivePluginConfig(fields, restart(draft))["label"])
        draft["label"] = JsonNull
        assertEquals(JsonNull, effectivePluginConfig(fields, restart(draft))["label"])
        draft.remove("label")
        assertEquals(JsonPrimitive("initial"), effectivePluginConfig(fields, restart(draft))["label"])
    }

    @Test fun pageWebAndToolOrdinaryValuesAgreeAcrossSerialization() {
        val saved = mapOf("enabled" to JsonPrimitive(false), "count" to JsonPrimitive(0), "label" to JsonNull)
        val page = effectivePluginConfig(fields, saved)
        val web = Json.parseToJsonElement(JsonObject(effectivePluginConfig(fields, restart(saved))).toString())
        val tool = resolvePluginConfig(fields, restart(saved)) { }
        assertEquals(JsonObject(page), web)
        assertEquals(page, tool)
    }

    @Test fun defaultModelUuidIsMergedBeforeResolverAndDerivedValuesAreNotOverwritten() {
        val uuid = "00000000-0000-0000-0000-000000000001"
        val modelFields = fields + listOf(
            field("model", JsonPrimitive(uuid), "model"),
            field("model_base_url", JsonPrimitive("manifest-fixture"))
        )
        var calls = 0
        val runtime = resolvePluginConfig(modelFields, emptyMap()) { effective ->
            calls++
            assertEquals(JsonPrimitive(uuid), effective["model"])
            assertEquals(JsonPrimitive("initial"), effective["label"])
            effective["model"] = JsonPrimitive("resolved-fixture-model")
            effective["model_base_url"] = JsonPrimitive("resolved-fixture-url")
        }
        assertEquals(1, calls)
        assertEquals(JsonPrimitive("resolved-fixture-model"), runtime["model"])
        assertEquals(JsonPrimitive("resolved-fixture-url"), runtime["model_base_url"])
    }

    @Test fun explicitModelOverrideReachesResolverAndSavedMapIsNotMutated() {
        val modelFields = listOf(field("model", JsonPrimitive("default-uuid"), "model"))
        for (override in listOf(JsonPrimitive("saved-uuid"), JsonPrimitive(""), JsonNull)) {
            val saved = mapOf("model" to override)
            resolvePluginConfig(modelFields, saved) { effective ->
                assertEquals(override, effective["model"])
                effective["runtime_only"] = JsonPrimitive(true)
            }
            assertEquals(mapOf("model" to override), saved)
        }
    }

    @Test fun pluginInfoAccessorUsesSameEffectiveValues() {
        val saved = mapOf("label" to JsonNull)
        val info = PluginInfo(
            PluginManifest("fixture", "Fixture", "", "1", "Test", "", "main.js", config = fields),
            File("fixture"), isEnabled = true, config = saved
        )
        val effective = effectivePluginConfig(fields, saved)
        fields.forEach { assertEquals(effective[it.name], info.getConfigValue(it.name)) }
        assertNull(info.getConfigValue("missing"))
    }
}
