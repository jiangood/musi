package fumi.day.literalmusi.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val errors: List<String> = emptyList(),
    val newShas: Map<String, String> = emptyMap()
)

@Singleton
class GitSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitTransport: GitTransport,
    private val syncProcessor: SyncProcessor,
    private val syncScheduler: SyncScheduler,
    private val userPreferences: UserPreferences
) {
    val isSyncing: StateFlow<Boolean> = syncScheduler.isSyncing

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    init {
        syncScheduler.setSyncCallback {
            syncAndAwait()
        }
    }

    fun launchSync() {
        syncScheduler.triggerSync()
    }

    suspend fun syncAndAwait(): SyncResult? {
        if (isSyncing.value) return null
        return withContext(Dispatchers.IO) {
            val prefs = userPreferences.userPrefs.first()
            if (!prefs.ossEnabled || prefs.ossAccessKey.isBlank() || prefs.ossSecretKey.isBlank() || prefs.ossBucket.isBlank()) {
                return@withContext null
            }
            _syncError.value = null
            try {
                gitTransport.ensureInitialized(
                    prefs.ossAccessKey,
                    prefs.ossSecretKey,
                    prefs.ossBucket,
                    prefs.ossRegion,
                    prefs.ossDomain
                )
                val result = syncProcessor.process(prefs.lastSyncedShas, prefs.lastSyncedAt)
                if (result.errors.isNotEmpty()) {
                    _syncError.value = result.errors.first()
                } else {
                    userPreferences.setLastSyncedAt(System.currentTimeMillis())
                    userPreferences.setLastSyncedShas(result.newShas)
                }
                result
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message ?: "Sync failed"}"
                _syncError.value = msg
                SyncResult(errors = listOf(msg))
            }
        }
    }

    suspend fun mergeAndAwait(): SyncResult? {
        return syncAndAwait()
    }

    fun clearLocalData() {
        gitTransport.close()
        val repoDir = File(context.filesDir, "repo")
        repoDir.deleteRecursively()
    }
}
