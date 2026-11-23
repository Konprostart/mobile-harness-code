package com.mobileharness.code.data

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** The three AI tools that share the central provider library and can each hold a default model. */
enum class AiToolTarget(val storageKey: String) {
    DEEPSEEK("deepseek"),
    CLAUDE_CODE("claude"),
    SIDEKICK("sidekick"),
}

/**
 * A named, saved model configuration for one provider. A user can keep several of these per
 * provider (e.g. "chat", "reasoning", "vision") and reuse them across DeepSeek Harness, Claude
 * Code and the Sidekick assistant without re-entering the base URL, wire protocol or key.
 */
data class SavedProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val model: String,
    val baseUrl: String = "",
    val dshApi: String = "",
    val openRouterProviderOrder: String = "",
    val openRouterAllowFallbacks: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

/** Everything persisted for one provider: its named configs plus the per-tool default model. */
data class ProviderConfigSet(
    val configs: List<SavedProviderConfig> = emptyList(),
    val defaultModels: Map<AiToolTarget, String> = emptyMap(),
)

/** Pure JSON codec for [ProviderConfigSet]; no Android dependency so it is unit-testable. */
object ProviderConfigCodec {
    fun toJson(set: ProviderConfigSet): String {
        val configs = JSONArray()
        set.configs.forEach { config ->
            configs.put(
                JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("model", config.model)
                    .put("baseUrl", config.baseUrl)
                    .put("dshApi", config.dshApi)
                    .put("openRouterProviderOrder", config.openRouterProviderOrder)
                    .put("openRouterAllowFallbacks", config.openRouterAllowFallbacks)
                    .put("createdAtMillis", config.createdAtMillis),
            )
        }
        val defaults = JSONObject()
        set.defaultModels.forEach { (tool, model) -> defaults.put(tool.storageKey, model) }
        return JSONObject().put("configs", configs).put("defaults", defaults).toString()
    }

    fun fromJson(raw: String): ProviderConfigSet = runCatching {
        val root = JSONObject(raw)
        val configsArray = root.optJSONArray("configs") ?: JSONArray()
        val configs = buildList {
            for (index in 0 until configsArray.length()) {
                val item = configsArray.optJSONObject(index) ?: continue
                val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                val model = item.optString("model")
                if (model.isNotBlank()) {
                    add(
                        SavedProviderConfig(
                            id = id,
                            name = item.optString("name").ifBlank { "Config ${index + 1}" },
                            model = model,
                            baseUrl = item.optString("baseUrl"),
                            dshApi = item.optString("dshApi"),
                            openRouterProviderOrder = item.optString("openRouterProviderOrder"),
                            openRouterAllowFallbacks = item.optBoolean("openRouterAllowFallbacks", true),
                            createdAtMillis = item.optLong("createdAtMillis", 0L),
                        ),
                    )
                }
            }
        }
        val defaultsObj = root.optJSONObject("defaults") ?: JSONObject()
        val defaults = mutableMapOf<AiToolTarget, String>()
        AiToolTarget.entries.forEach { tool ->
            defaultsObj.optString(tool.storageKey).takeIf(String::isNotBlank)?.let { defaults[tool] = it }
        }
        ProviderConfigSet(configs, defaults)
    }.getOrDefault(ProviderConfigSet())
}

/** File-backed storage for per-provider named configs and per-tool default models. */
class ProviderConfigStore(private val dir: File) {
    fun load(providerId: String): ProviderConfigSet {
        val file = File(dir, "configs_$providerId.json")
        if (!file.exists()) return ProviderConfigSet()
        return runCatching { ProviderConfigCodec.fromJson(file.readText()) }.getOrDefault(ProviderConfigSet())
    }

    fun save(providerId: String, set: ProviderConfigSet) {
        runCatching {
            dir.mkdirs()
            val temporary = File(dir, ".configs_$providerId.json.tmp")
            temporary.writeText(ProviderConfigCodec.toJson(set))
            val destination = File(dir, "configs_$providerId.json")
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        }
    }

    fun addConfig(providerId: String, config: SavedProviderConfig): List<SavedProviderConfig> {
        val current = load(providerId)
        val updated = current.copy(configs = (current.configs + config).distinctBy { it.id })
        save(providerId, updated)
        return updated.configs
    }

    fun deleteConfig(providerId: String, configId: String): List<SavedProviderConfig> {
        val current = load(providerId)
        val updated = current.copy(configs = current.configs.filterNot { it.id == configId })
        save(providerId, updated)
        return updated.configs
    }

    fun setDefaultModel(providerId: String, tool: AiToolTarget, model: String) {
        val current = load(providerId)
        val defaults = current.defaultModels.toMutableMap()
        if (model.isBlank()) defaults.remove(tool) else defaults[tool] = model
        save(providerId, current.copy(defaultModels = defaults))
    }

    fun defaultModel(providerId: String, tool: AiToolTarget): String? =
        load(providerId).defaultModels[tool]
}
