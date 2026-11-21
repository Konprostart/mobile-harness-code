package com.mobileharness.code.plugins

/**
 * In-memory registry of installed plugins with an optional persistence sink. The Android layer owns
 * the actual file persistence (JSON in device-only storage); this class stays pure and testable.
 * Registry mutations are validated by [PluginPolicy] so an invalid manifest is never stored.
 */
class PluginRegistry(
    private val maxPlugins: Int = PluginPolicy.MAX_PLUGINS,
    private val persist: (String) -> Unit = {},
) {
    private val plugins = LinkedHashMap<String, Plugin>()

    fun load(json: String?) {
        plugins.clear()
        PluginCodec.decodeRegistry(json).forEach { plugins[it.manifest.id] = it }
    }

    fun snapshot(): List<Plugin> = plugins.values.toList()

    fun get(id: String): Plugin? = plugins[id]

    /** Installs or updates a plugin; returns null when the manifest is invalid or the registry is full. */
    fun install(plugin: Plugin): Boolean {
        if (!PluginPolicy.isValidManifest(plugin.manifest)) return false
        if (plugin.installPath.isBlank() || plugin.installPath.contains("..")) return false
        if (plugins.size >= maxPlugins && plugins[plugin.manifest.id] == null) return false
        plugins[plugin.manifest.id] = plugin
        commit()
        return true
    }

    fun remove(id: String): Boolean {
        val removed = plugins.remove(id) != null
        if (removed) commit()
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val existing = plugins[id] ?: return false
        plugins[id] = existing.copy(enabled = enabled)
        commit()
        return true
    }

    fun clear() {
        plugins.clear()
        commit()
    }

    private fun commit() = persist(PluginCodec.encodeRegistry(snapshot()))
}
