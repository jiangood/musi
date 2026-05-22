package fumi.day.literalmusi.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserPrefs(
    val ossEnabled: Boolean = false,
    val ossAccessKey: String = "",
    val ossSecretKey: String = "",
    val ossBucket: String = "",
    val ossRegion: String = "z0",
    val ossDomain: String = "",
    val lastSyncedAt: Long? = null,
    val lastSyncedShas: Map<String, String> = emptyMap()
)

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val encryptedPrefs: SharedPreferences = run {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _ossSecretKey = MutableStateFlow(
        encryptedPrefs.getString("oss_secret_key", "") ?: ""
    )

    private object Keys {
        val OSS_ENABLED = booleanPreferencesKey("oss_enabled")
        val OSS_ACCESS_KEY = stringPreferencesKey("oss_access_key")
        val OSS_BUCKET = stringPreferencesKey("oss_bucket")
        val OSS_REGION = stringPreferencesKey("oss_region")
        val OSS_DOMAIN = stringPreferencesKey("oss_domain")
        val LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
        val LAST_SYNCED_SHAS = stringPreferencesKey("last_synced_shas")
    }

    val userPrefs: Flow<UserPrefs> = combine(
        context.dataStore.data,
        _ossSecretKey
    ) { prefs, secretKey ->
        val shasJson = prefs[Keys.LAST_SYNCED_SHAS]
        val shas = if (shasJson != null) {
            val obj = JSONObject(shasJson)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } else {
            emptyMap()
        }
        UserPrefs(
            ossEnabled = prefs[Keys.OSS_ENABLED] ?: false,
            ossAccessKey = prefs[Keys.OSS_ACCESS_KEY] ?: "",
            ossSecretKey = secretKey,
            ossBucket = prefs[Keys.OSS_BUCKET] ?: "",
            ossRegion = prefs[Keys.OSS_REGION] ?: "z0",
            ossDomain = prefs[Keys.OSS_DOMAIN] ?: "",
            lastSyncedAt = prefs[Keys.LAST_SYNCED_AT],
            lastSyncedShas = shas
        )
    }

    suspend fun setOssConfig(
        enabled: Boolean,
        accessKey: String,
        secretKey: String,
        bucket: String,
        region: String,
        domain: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OSS_ENABLED] = enabled
            prefs[Keys.OSS_ACCESS_KEY] = accessKey
            prefs[Keys.OSS_BUCKET] = bucket
            prefs[Keys.OSS_REGION] = region
            prefs[Keys.OSS_DOMAIN] = domain
        }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString("oss_secret_key", secretKey).apply()
        }
        _ossSecretKey.value = secretKey
    }

    suspend fun resetSyncState() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.LAST_SYNCED_AT)
            prefs.remove(Keys.LAST_SYNCED_SHAS)
        }
    }

    suspend fun setLastSyncedAt(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNCED_AT] = timestamp
        }
    }

    suspend fun setLastSyncedShas(shas: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNCED_SHAS] = JSONObject(shas).toString()
        }
    }

    suspend fun clearOssConfig() {
        context.dataStore.edit { prefs ->
            prefs[Keys.OSS_ENABLED] = false
            prefs[Keys.OSS_ACCESS_KEY] = ""
            prefs[Keys.OSS_BUCKET] = ""
            prefs[Keys.OSS_REGION] = "z0"
            prefs[Keys.OSS_DOMAIN] = ""
            prefs.remove(Keys.LAST_SYNCED_AT)
            prefs.remove(Keys.LAST_SYNCED_SHAS)
        }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().remove("oss_secret_key").apply()
        }
        _ossSecretKey.value = ""
    }
}
