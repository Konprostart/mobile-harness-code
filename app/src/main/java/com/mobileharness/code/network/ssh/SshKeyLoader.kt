package com.mobileharness.code.network.ssh

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import java.io.StringReader
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils

/** Recognized private-key containers. */
enum class SshKeyKind { OPENSSH_V1, PKCS8, RSA_PKCS1, EC, ENCRYPTED_PKCS8, UNKNOWN }

/**
 * Loads an OpenSSH Ed25519 or RSA private key from a PEM string, with or without a passphrase.
 *
 * The pure [detect] and [normalize] helpers are unit-testable; [load] delegates to sshj's key
 * providers (which handle the OpenSSH "openssh-key-v1" Ed25519 format through Bouncy Castle,
 * as well as PKCS#8 and legacy PKCS#1 RSA) so the app never shells out to `ssh-keygen`.
 */
object SshKeyLoader {
    fun detect(pem: String): SshKeyKind {
        val head = pem.trim().substring(0, minOf(pem.trim().length, 220))
        return when {
            head.contains("BEGIN OPENSSH PRIVATE KEY") -> SshKeyKind.OPENSSH_V1
            head.contains("BEGIN ENCRYPTED PRIVATE KEY") -> SshKeyKind.ENCRYPTED_PKCS8
            head.contains("BEGIN RSA PRIVATE KEY") -> SshKeyKind.RSA_PKCS1
            head.contains("BEGIN EC PRIVATE KEY") -> SshKeyKind.EC
            head.contains("BEGIN PRIVATE KEY") -> SshKeyKind.PKCS8
            else -> SshKeyKind.UNKNOWN
        }
    }

    /** Trims surrounding whitespace and guarantees a trailing newline (PEM parsers expect one). */
    fun normalize(pem: String): String {
        val trimmed = pem.trim()
        return if (trimmed.endsWith("\n")) trimmed else "$trimmed\n"
    }

    sealed interface Result {
        data class Success(val provider: FileKeyProvider) : Result
        data class Failure(val message: String) : Result
    }

    fun load(pem: String, passphrase: String?): Result {
        val kind = detect(pem)
        if (kind == SshKeyKind.UNKNOWN) {
            return Result.Failure("Unsupported or invalid private key.")
        }
        return runCatching {
            val normalized = normalize(pem)
            val format = KeyProviderUtil.detectKeyFileFormat(normalized, false)
            val provider: FileKeyProvider = when (format) {
                KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
                KeyFormat.OpenSSH -> OpenSSHKeyFile()
                KeyFormat.PKCS8 -> PKCS8KeyFile()
                else -> return Result.Failure("Unsupported key format: $format")
            }
            val finder = if (passphrase.isNullOrEmpty()) null else PasswordUtils.createOneOff(passphrase.toCharArray())
            provider.init(StringReader(normalized), finder)
            // Force eager parsing so a malformed key or wrong passphrase fails at load time,
            // not silently at authentication time.
            provider.getPrivate()
            provider.getPublic()
            Result.Success(provider)
        }.getOrElse { Result.Failure(it.message ?: "Could not parse the private key.") }
    }
}
