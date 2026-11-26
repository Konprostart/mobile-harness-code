package com.mobileharness.code.cloud

import java.io.File

/**
 * A provider backed by a real directory on the device (or an externally synced folder).
 *
 * This is both the "custom storage" backend and the fully-testable reference implementation used
 * by the unit tests; the cloud-agnostic backup engine is exercised end to end through it.
 */
class LocalDirectoryStorageProvider(
    private val root: File,
    override val id: String = PROVIDER_LOCAL,
    override val displayName: String = "Local folder",
) : CloudStorageProvider {

    override var isConnected: Boolean = false
        private set

    override fun connect() {
        if (root.exists() && !root.isDirectory) {
            throw CloudException("Storage path is not a folder.")
        }
        if (!root.exists() && !root.mkdirs()) {
            throw CloudException("Could not create storage folder.")
        }
        isConnected = true
    }

    override fun disconnect() {
        isConnected = false
    }

    override fun list(prefix: String): List<CloudObject> {
        requireConnected()
        return root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative.startsWith(prefix)) {
                    CloudObject(relative, file.length(), file.lastModified())
                } else {
                    null
                }
            }
            .toList()
            .sortedBy { it.name }
    }

    override fun upload(name: String, bytes: ByteArray) {
        requireConnected()
        requireSafeName(name)
        val target = File(root, name)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    override fun download(name: String): ByteArray {
        requireConnected()
        requireSafeName(name)
        val file = File(root, name)
        if (!file.isFile) throw CloudNotFoundException("Object not found: $name")
        return file.readBytes()
    }

    override fun delete(name: String) {
        requireConnected()
        requireSafeName(name)
        val file = File(root, name)
        if (file.exists() && !file.delete()) throw CloudException("Could not delete $name")
    }

    override fun exists(name: String): Boolean {
        requireConnected()
        requireSafeName(name)
        return File(root, name).isFile
    }

    private fun requireConnected() {
        if (!isConnected) throw CloudNotConnectedException()
    }

    private fun requireSafeName(name: String) {
        require(name.isNotBlank() && '\u0000' !in name && !name.startsWith("/") && ".." !in name.split('/')) {
            "Unsafe object name"
        }
    }

    companion object {
        const val PROVIDER_LOCAL = "local"
    }
}
