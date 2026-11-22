package com.mobileharness.code.data

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password-verifier crypto for the local Mobile Harness account. Kept free of Android so it can
 * be unit-tested on the JVM. The account password is never stored anywhere; only a salted
 * PBKDF2 verifier is persisted, so a database leak on this phone cannot recover the password.
 */
object AccountCrypto {
    const val SALT_BYTES = 16
    const val ITERATIONS = 210_000
    const val KEY_BITS = 256

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_BYTES).also(random::nextBytes)

    fun derive(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time comparison so the verifier cannot be probed byte by byte. */
    fun verify(expected: ByteArray, actual: ByteArray): Boolean {
        if (expected.size != actual.size) return false
        var diff = 0
        for (index in expected.indices) diff = diff or (expected[index].toInt() xor actual[index].toInt())
        return diff == 0
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}

/** Pure, testable validation rules for the account identity and master password. */
object AccountPolicy {
    const val MIN_USERNAME_LENGTH = 2
    const val MAX_USERNAME_LENGTH = 60
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_PASSWORD_LENGTH = 256

    /** Returns a human-readable problem, or null when the name is acceptable. */
    fun validateUsername(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.length < MIN_USERNAME_LENGTH -> "Account name must be at least $MIN_USERNAME_LENGTH characters"
            trimmed.length > MAX_USERNAME_LENGTH -> "Account name must be at most $MAX_USERNAME_LENGTH characters"
            else -> null
        }
    }

    fun validatePassword(password: CharArray): String? = when {
        password.size < MIN_PASSWORD_LENGTH -> "Password must be at least $MIN_PASSWORD_LENGTH characters"
        password.size > MAX_PASSWORD_LENGTH -> "Password must be at most $MAX_PASSWORD_LENGTH characters"
        else -> null
    }
}

/**
 * A local, password-based Mobile Harness account. There is no server: the "account" is a named
 * identity whose master password encrypts the portable `.mhbackup` (see the backup package).
 * Only a salted PBKDF2 verifier is persisted — the password itself lives in memory for the
 * duration of a dialog and is wiped afterward.
 */
class AccountManager(context: Context, private val iterations: Int = AccountCrypto.ITERATIONS) {
    private val preferences = context.getSharedPreferences("pocket_account", Context.MODE_PRIVATE)

    sealed interface Result {
        data class Success(val username: String) : Result
        data class Error(val message: String) : Result
    }

    val hasAccount: Boolean
        get() = username.isNotBlank() && verifier != null

    val username: String
        get() = preferences.getString(KEY_USERNAME, "") ?: ""

    val createdAtMillis: Long
        get() = preferences.getLong(KEY_CREATED_AT, 0L)

    /** When the last password-protected backup was created on this device (0 = never). */
    var lastBackupMillis: Long
        get() = preferences.getLong(KEY_LAST_BACKUP, 0L)
        set(value) { preferences.edit().putLong(KEY_LAST_BACKUP, value).apply() }

    fun create(username: String, password: CharArray): Result {
        if (hasAccount) return Result.Error("An account already exists on this device")
        AccountPolicy.validateUsername(username)?.let { return Result.Error(it) }
        AccountPolicy.validatePassword(password)?.let { return Result.Error(it) }
        val name = username.trim()
        val salt = AccountCrypto.newSalt()
        val hash = AccountCrypto.derive(password, salt, iterations)
        password.fill('\u0000')
        preferences.edit()
            .putString(KEY_USERNAME, name)
            .putString(KEY_SALT, AccountCrypto.encode(salt))
            .putString(KEY_VERIFIER, AccountCrypto.encode(hash))
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .apply()
        return Result.Success(name)
    }

    /** True when [password] matches the stored master password. */
    fun verify(password: CharArray): Boolean {
        val salt = salt ?: return false
        val expected = verifier ?: return false
        val derived = AccountCrypto.derive(password, salt, iterations)
        password.fill('\u0000')
        return AccountCrypto.verify(expected, derived)
    }

    /** Removes the local identity. The encrypted backup files elsewhere are unaffected. */
    fun signOut() {
        preferences.edit().clear().apply()
    }

    private val salt: ByteArray?
        get() = preferences.getString(KEY_SALT, null)?.let { runCatching { AccountCrypto.decode(it) }.getOrNull() }

    private val verifier: ByteArray?
        get() = preferences.getString(KEY_VERIFIER, null)?.let { runCatching { AccountCrypto.decode(it) }.getOrNull() }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_SALT = "salt"
        const val KEY_VERIFIER = "verifier"
        const val KEY_CREATED_AT = "created_at"
        const val KEY_LAST_BACKUP = "last_backup_millis"
    }
}
