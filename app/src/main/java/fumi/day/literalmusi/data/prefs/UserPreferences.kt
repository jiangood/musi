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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserPrefs(
    val gitHubEnabled: Boolean = false,
    val gitHubToken: String = "",
    val gitHubRepo: String = "",
    val lastSyncedAt: Long? = null
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

    private val _gitHubToken = MutableStateFlow(
        encryptedPrefs.getString("github_token", "") ?: ""
    )

    private object Keys {
        val GITHUB_ENABLED = booleanPreferencesKey("github_enabled")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
    }

    val userPrefs: Flow<UserPrefs> = combine(
        context.dataStore.data,
        _gitHubToken
    ) { prefs, token ->
        UserPrefs(
            gitHubEnabled = prefs[Keys.GITHUB_ENABLED] ?: false,
            gitHubToken = token,
            gitHubRepo = prefs[Keys.GITHUB_REPO] ?: "",
            lastSyncedAt = prefs[Keys.LAST_SYNCED_AT]
        )
    }

    suspend fun setGitConfig(enabled: Boolean, token: String, repo: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GITHUB_ENABLED] = enabled
            prefs[Keys.GITHUB_REPO] = repo
        }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString("github_token", token).apply()
        }
        _gitHubToken.value = token
    }

    suspend fun resetSyncState() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.LAST_SYNCED_AT)
        }
    }

    suspend fun setLastSyncedAt(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNCED_AT] = timestamp
        }
    }

    suspend fun clearGitHubConfig() {
        context.dataStore.edit { prefs ->
            prefs[Keys.GITHUB_ENABLED] = false
            prefs[Keys.GITHUB_REPO] = ""
            prefs.remove(Keys.LAST_SYNCED_AT)
        }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().remove("github_token").apply()
        }
        _gitHubToken.value = ""
    }

}
