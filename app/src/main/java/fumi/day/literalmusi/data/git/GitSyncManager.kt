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
    val errors: List<String> = emptyList()
)

@Singleton
class GitSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitTransport: GitTransport,
    private val syncProcessor: SyncProcessor,
    private val syncScheduler: SyncScheduler,
    private val userPreferences: UserPreferences,
    private val opLog: OpLog
) {
    val isSyncing: StateFlow<Boolean> = syncScheduler.isSyncing

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _currentOperation = MutableStateFlow<String?>(null)
    val currentOperation: StateFlow<String?> = _currentOperation.asStateFlow()

    private val _localFileCount = MutableStateFlow(0)
    val localFileCount: StateFlow<Int> = _localFileCount.asStateFlow()

    private val _remoteFileCount = MutableStateFlow<Int?>(null)
    val remoteFileCount: StateFlow<Int?> = _remoteFileCount.asStateFlow()

    init {
        opLog.init(File(context.filesDir, "oplog"))
        syncScheduler.setSyncCallback {
            syncAndAwait()
        }
        refreshFileCounts()
    }

    fun launchSync() {
        syncScheduler.triggerSync()
    }

    suspend fun syncAndAwait(): SyncResult? {
        if (isSyncing.value) return null
        return withContext(Dispatchers.IO) {
            val prefs = userPreferences.userPrefs.first()
            if (!prefs.gitHubEnabled || prefs.gitHubToken.isBlank() || prefs.gitHubRepo.isBlank()) {
                return@withContext null
            }
            _syncError.value = null
            _currentOperation.value = "Initializing..."
            try {
                gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
                _remoteFileCount.value = gitTransport.remoteFileCount()
                val result = syncProcessor.process { phase ->
                    _currentOperation.value = phase
                }
                _currentOperation.value = null
                refreshFileCounts()
                if (result.errors.isNotEmpty()) {
                    _syncError.value = result.errors.first()
                } else {
                    userPreferences.setLastSyncedAt(System.currentTimeMillis())
                }
                result
            } catch (e: Exception) {
                _currentOperation.value = null
                val msg = "${e.javaClass.simpleName}: ${e.message ?: "Sync failed"}"
                _syncError.value = msg
                SyncResult(errors = listOf(msg))
            }
        }
    }

    private fun refreshFileCounts() {
        _localFileCount.value = gitTransport.pileDir.listFiles()?.filter { it.isFile }?.size ?: 0
        _remoteFileCount.value = gitTransport.remoteFileCount()
    }

    suspend fun mergeAndAwait(): SyncResult? {
        return syncAndAwait()
    }

    fun clearLocalData() {
        gitTransport.close()
        val repoDir = File(context.filesDir, "repo")
        repoDir.deleteRecursively()
        opLog.init(File(context.filesDir, "oplog"))
    }
}
