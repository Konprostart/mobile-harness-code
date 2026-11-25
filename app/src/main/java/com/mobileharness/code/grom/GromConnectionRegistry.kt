package com.mobileharness.code.grom

import android.content.Context
import com.mobileharness.code.data.ApiKeyVault
import com.mobileharness.code.data.AppPreferences

/**
 * Process-lifetime holder of the single [GromConnectionManager]. Every module
 * (the settings UI, the Free LLM API adapter, future services) obtains the same
 * instance, so there is exactly one GROM connection for the whole app.
 *
 * Secrets are delegated to [ApiKeyVault] (AES-256-GCM behind the Android
 * Keystore); config to [AppPreferences]. Nothing here holds a key itself.
 */
object GromConnectionRegistry {

    @Volatile
    private var instance: GromConnectionManager? = null

    fun get(context: Context): GromConnectionManager =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(appContext: Context): GromConnectionManager {
        val preferences = AppPreferences(appContext)
        val vault = ApiKeyVault(appContext)
        return GromConnectionManager(
            configStore = object : GromConfigStore {
                override fun load(): GromConfig = preferences.gromConfig
                override fun save(config: GromConfig) {
                    preferences.gromConfig = config
                }
            },
            secretStore = object : GromSecretStore {
                override fun put(id: String, secret: String) = vault.put(id, secret)
                override fun get(id: String): String? = vault.get(id)
                override fun remove(id: String) = vault.remove(id)
            },
        )
    }
}
