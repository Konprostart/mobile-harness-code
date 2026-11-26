package com.mobileharness.code.cloud

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * A provider backed by an Android Storage Access Framework document tree.
 *
 * The user picks the folder once with `ACTION_OPEN_DOCUMENT_TREE`; Android then routes the choice
 * to whatever DocumentsProvider is installed — Google Drive, another cloud app, an SD card, or a
 * file manager. This is how "Google Drive as the first provider" is delivered without embedding a
 * vendor SDK: the connection lives in the OS, and this class only talks the generic document API.
 *
 * Only encrypted `.sgnbak` containers are ever written through this provider.
 */
class SafDirectoryStorageProvider(
    private val context: Context,
    private val treeUri: Uri,
    override val id: String = PROVIDER_SAF,
    override val displayName: String = "Cloud folder",
) : CloudStorageProvider {

    private var rootDoc: DocumentFile? = null

    override var isConnected: Boolean = false
        private set

    override fun connect() {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw CloudAuthException("Folder is no longer accessible. Re-select it.")
        if (!root.canWrite()) throw CloudAuthException("Folder is read-only.")
        rootDoc = root
        isConnected = true
    }

    override fun disconnect() {
        rootDoc = null
        isConnected = false
    }

    override fun list(prefix: String): List<CloudObject> {
        val out = mutableListOf<CloudObject>()
        collect(requireRoot(), prefix, "", out)
        return out.sortedBy { it.name }
    }

    override fun upload(name: String, bytes: ByteArray) {
        requireSafeName(name)
        val segments = name.split('/')
        val fileName = segments.last()
        val parent = ensureDirectory(requireRoot(), segments.dropLast(1))
        parent.findFile(fileName)?.takeIf { !it.isFile }?.let {
            throw CloudException("Path component is not a file: $name")
        }
        parent.findFile(fileName)?.takeIf { it.isFile }?.delete()
        val file = parent.createFile("application/octet-stream", fileName)
            ?: throw CloudException("Could not create object $name")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: throw CloudException("Could not write object $name")
    }

    override fun download(name: String): ByteArray {
        requireSafeName(name)
        val doc = resolve(name) ?: throw CloudNotFoundException("Object not found: $name")
        return context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            ?: throw CloudException("Could not read object $name")
    }

    override fun delete(name: String) {
        requireSafeName(name)
        val doc = resolve(name) ?: return
        if (!doc.delete()) throw CloudException("Could not delete $name")
    }

    override fun exists(name: String): Boolean {
        requireSafeName(name)
        return resolve(name) != null
    }

    // ---------------------------------------------------------------- internals

    private fun requireRoot(): DocumentFile {
        val root = rootDoc
        if (root == null || !isConnected) throw CloudNotConnectedException()
        return root
    }

    private fun collect(dir: DocumentFile, prefix: String, path: String, out: MutableList<CloudObject>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val childPath = if (path.isEmpty()) name else "$path/$name"
            when {
                child.isDirectory -> collect(child, prefix, childPath, out)
                child.isFile && childPath.startsWith(prefix) ->
                    out += CloudObject(childPath, child.length(), child.lastModified())
            }
        }
    }

    private fun resolve(name: String): DocumentFile? {
        var current = requireRoot()
        val segments = name.split('/')
        for (segment in segments.dropLast(1)) {
            current = current.findFile(segment) ?: return null
            if (!current.isDirectory) return null
        }
        val file = current.findFile(segments.last()) ?: return null
        return file.takeIf { it.isFile }
    }

    private fun ensureDirectory(root: DocumentFile, segments: List<String>): DocumentFile {
        var current = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = when {
                existing != null && existing.isDirectory -> existing
                existing != null -> throw CloudException("Path component is not a folder: $segment")
                else -> current.createDirectory(segment)
                    ?: throw CloudException("Could not create folder $segment")
            }
        }
        return current
    }

    private fun requireSafeName(name: String) {
        require(name.isNotBlank() && '\u0000' !in name && !name.startsWith("/") && ".." !in name.split('/')) {
            "Unsafe object name"
        }
    }

    companion object {
        const val PROVIDER_SAF = "saf"
    }
}
