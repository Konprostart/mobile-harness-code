package com.mobileharness.code.device

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Android storage for the wireless-ADB key and endpoint. The RSA key is generated once and kept in
 * app-private storage with owner-only permissions; it is a client authentication key (not a user
 * secret), but it is still never logged, backed up, or shared. The endpoint ("host:port") and the
 * loaded key are published to [WirelessAdbExecutor] so [PrivilegeController] can run it.
 */
object WirelessAdbStore {
    private const val PUBLIC_FILE = "wireless_adb_key.pub"
    private const val PRIVATE_FILE = "wireless_adb_key.pem"

    /** Loads the stored key + endpoint into [WirelessAdbExecutor]; returns true when ready to run. */
    fun load(context: Context, endpoint: String?): Boolean {
        WirelessAdbExecutor.endpoint = endpoint?.trim()?.takeIf { it.isNotEmpty() }
        val pair = readKey(context.filesDir) ?: return false
        WirelessAdbExecutor.key = pair
        return WirelessAdbExecutor.endpoint != null
    }

    /** Returns the existing key pair or generates + persists a new one. */
    fun ensureKey(context: Context): AdbKey.Pair {
        readKey(context.filesDir)?.let { return it }
        val pair = AdbKey.generate()
        writeKey(context.filesDir, pair)
        WirelessAdbExecutor.key = pair
        return pair
    }

    fun hasKey(context: Context): Boolean = readKey(context.filesDir) != null

    fun publicKeyLine(context: Context): String? =
        readKey(context.filesDir)?.let { AdbKey.publicKeyLine(it.publicKey) }

    private fun readKey(dir: File): AdbKey.Pair? = runCatching {
        val publicFile = File(dir, PUBLIC_FILE)
        val privateFile = File(dir, PRIVATE_FILE)
        if (!publicFile.isFile || !privateFile.isFile) return null
        val public = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
        val private = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
        AdbKey.Pair(private, public)
    }.getOrNull()

    private fun writeKey(dir: File, pair: AdbKey.Pair) = runCatching {
        val publicFile = File(dir, PUBLIC_FILE)
        val privateFile = File(dir, PRIVATE_FILE)
        publicFile.writeBytes(pair.publicKey.encoded)
        privateFile.writeBytes(pair.privateKey.encoded)
        for (f in listOf(publicFile, privateFile)) {
            f.setReadable(true, true)
            f.setWritable(true, true)
            f.setExecutable(false)
        }
    }
}
