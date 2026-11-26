package com.mobileharness.code.cloud

import java.io.IOException

/** A stored object returned by a [CloudStorageProvider]. */
data class CloudObject(
    val name: String,
    val size: Long,
    val modifiedAt: Long,
)

/** Base class for every cloud storage failure. Message text is user facing. */
open class CloudException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The provider has not been connected (or was disconnected) yet. */
class CloudNotConnectedException(message: String = "Cloud storage is not connected.") : CloudException(message)

/** Connecting requires an interactive sign-in / permission the user has not completed. */
class CloudAuthException(message: String, cause: Throwable? = null) : CloudException(message, cause)

/** The requested object does not exist. */
class CloudNotFoundException(message: String) : CloudException(message)

/** A transient network / I/O failure. */
class CloudNetworkException(message: String, cause: Throwable? = null) : CloudException(message, cause)

/**
 * A pluggable cloud storage backend.
 *
 * Implementations are synchronous (no coroutines) so the core backup logic stays plain JVM and is
 * unit tested; the UI layer runs them on `Dispatchers.IO`. A provider must never receive raw
 * keystore files or passwords: callers upload only encrypted `.sgnbak` containers.
 *
 * Adding a new cloud (e.g. a self-hosted server or another vendor) means implementing this
 * interface and registering it in [CloudStorageRegistry] — the signing backup engine is unchanged.
 */
interface CloudStorageProvider {
    /** Stable id used in configuration and the registry. */
    val id: String

    /** Human label shown in the UI. */
    val displayName: String

    val isConnected: Boolean

    /** Establishes or revalidates the connection. Throws [CloudException] on failure. */
    fun connect()

    /** Releases the connection; later calls throw [CloudNotConnectedException]. */
    fun disconnect()

    /** All objects whose name starts with [prefix]. Sorted for determinism. */
    fun list(prefix: String): List<CloudObject>

    /** Writes [bytes] to [name] (creating missing parent entries as needed). */
    fun upload(name: String, bytes: ByteArray)

    /** Reads [name] back; throws [CloudNotFoundException] when absent. */
    fun download(name: String): ByteArray

    /** Removes [name]; a no-op when already absent. */
    fun delete(name: String)

    fun exists(name: String): Boolean
}
