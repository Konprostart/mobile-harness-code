package com.mobileharness.code.data

import com.mobileharness.code.network.DiscoveredModel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Last successfully discovered model list for one provider, kept for offline display. */
data class CachedModels(
    val models: List<DiscoveredModel>,
    val endpoint: String,
    val checkedAtMillis: Long,
)

/** Pure JSON codec for the model-discovery cache; independent of Android so it is unit-testable. */
object ModelDiscoveryCodec {
    fun toJson(cached: CachedModels): String {
        val array = JSONArray()
        cached.models.forEach { model ->
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("name", model.displayName)
                    .put("free", model.isFree),
            )
        }
        return JSONObject()
            .put("endpoint", cached.endpoint)
            .put("checkedAtMillis", cached.checkedAtMillis)
            .put("models", array)
            .toString()
    }

    fun fromJson(raw: String): CachedModels? = runCatching {
        val root = JSONObject(raw)
        val array = root.optJSONArray("models") ?: throw IllegalArgumentException("missing models array")
        val models = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isNotBlank()) {
                    add(DiscoveredModel(id, item.optString("name").ifBlank { id }, item.optBoolean("free")))
                }
            }
        }
        CachedModels(
            models = models,
            endpoint = root.optString("endpoint"),
            checkedAtMillis = root.optLong("checkedAtMillis", 0L),
        )
    }.getOrNull()
}

/**
 * Persists the last successful model list per provider so a user reopening the app (or offline) can
 * still see their known models without another network round-trip. Stored under the app-private
 * files dir, separate from the API key vault — the cache holds only public model ids, never keys.
 */
class ModelDiscoveryStore(private val dir: File) {
    fun save(providerId: String, models: List<DiscoveredModel>, endpoint: String) {
        if (models.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val temporary = File(dir, ".models_$providerId.json.tmp")
            temporary.writeText(ModelDiscoveryCodec.toJson(CachedModels(models, endpoint, System.currentTimeMillis())))
            val destination = File(dir, "models_$providerId.json")
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        }
    }

    fun load(providerId: String): CachedModels? {
        val file = File(dir, "models_$providerId.json")
        if (!file.exists()) return null
        return runCatching { ModelDiscoveryCodec.fromJson(file.readText()) }.getOrNull()
    }

    fun clear(providerId: String) {
        File(dir, "models_$providerId.json").delete()
    }
}
