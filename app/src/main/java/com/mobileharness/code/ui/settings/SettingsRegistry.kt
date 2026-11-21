package com.mobileharness.code.ui.settings

/**
 * Extensible settings registry.
 *
 * The existing settings screen keeps its 19 hard-coded sections unchanged; this registry is the
 * *additive* extensibility point new modules and plugins use to declare their own sections. A
 * section is a pure spec (id + title + ordinal); the Compose rendering stays in the UI layer and
 * can adopt the registry incrementally without refactoring the current screen.
 */
object SettingsRegistry {
    data class SectionSpec(
        val id: String,
        val title: String,
        val ordinal: Int = 100,
    )

    private val sections = LinkedHashMap<String, SectionSpec>()

    @Synchronized
    fun register(spec: SectionSpec) {
        sections[spec.id] = spec
    }

    @Synchronized
    fun unregister(id: String) {
        sections.remove(id)
    }

    /** Registered sections sorted by (ordinal, id) for stable ordering. */
    @Synchronized
    fun list(): List<SectionSpec> =
        sections.values.sortedWith(compareBy({ it.ordinal }, { it.id }))

    @Synchronized
    fun get(id: String): SectionSpec? = sections[id]

    @Synchronized
    fun clear() = sections.clear()
}
