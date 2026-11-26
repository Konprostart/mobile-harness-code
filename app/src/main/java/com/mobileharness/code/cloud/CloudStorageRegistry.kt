package com.mobileharness.code.cloud

/**
 * How a provider is "connected".
 *
 * [PICK_FOLDER] — the user selects a folder (SAF document tree) once; the connection string is a
 * `content://` tree URI.
 * [NONE] — the provider already knows where it stores data (e.g. a local directory passed in).
 */
enum class ConnectionKind { PICK_FOLDER, NONE }

/** Describes a provider the user can select in the UI. */
data class CloudProviderSpec(
    val id: String,
    val displayName: String,
    val connectionKind: ConnectionKind,
)

/**
 * Registry of storage providers. New clouds are added by [register]-ing a spec plus a factory that
 * builds a [CloudStorageProvider] from a connection string — no change to the backup engine or UI.
 */
object CloudStorageRegistry {

    fun interface Factory {
        fun create(connection: String): CloudStorageProvider
    }

    private data class Entry(val spec: CloudProviderSpec, val factory: Factory)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun register(spec: CloudProviderSpec, factory: Factory) {
        entries[spec.id] = Entry(spec, factory)
    }

    @Synchronized
    fun unregister(id: String) {
        entries.remove(id)
    }

    @Synchronized
    fun list(): List<CloudProviderSpec> = entries.values.map { it.spec }.sortedBy { it.id }

    @Synchronized
    fun spec(id: String): CloudProviderSpec? = entries[id]?.spec

    @Synchronized
    fun create(id: String, connection: String): CloudStorageProvider =
        entries[id]?.factory?.create(connection)
            ?: throw CloudException("Unknown storage provider: $id")
}
