package fumi.day.literalmusi.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.prefs.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val userPreferences: UserPreferences
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    fun launchSync() {
        if (_isSyncing.value) return
        appScope.launch { syncAndAwait() }
    }

    suspend fun syncAndAwait(): SyncResult? {
        if (_isSyncing.value) return null
        _isSyncing.value = true
        _syncError.value = null
        return try {
            val result = syncIfEnabled()
            if (result != null && result.errors.isNotEmpty()) {
                _syncError.value = result.errors.first()
            }
            result
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun mergeAndAwait(): SyncResult? {
        if (_isSyncing.value) return null
        _isSyncing.value = true
        _syncError.value = null
        return try {
            val result = mergeIfEnabled()
            if (result != null && result.errors.isNotEmpty()) {
                _syncError.value = result.errors.first()
            }
            result
        } finally {
            _isSyncing.value = false
        }
    }

    fun clearLocalData() {
        gitTransport.close()
        File(context.filesDir, "repo").deleteRecursively()
    }

    private suspend fun syncIfEnabled(): SyncResult? {
        val prefs = userPreferences.userPrefs.first()
        if (!prefs.gitHubEnabled || prefs.gitHubToken.isBlank() || prefs.gitHubRepo.isBlank()) {
            return null
        }

        val errors = mutableListOf<String>()

        try {
            gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)

            val pullResult = gitTransport.pull()

            val uploaded = gitTransport.stageAll()

            if (uploaded > 0 || pullResult.filesDownloaded > 0) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                gitTransport.commit("sync: $timestamp")

                val pushOk = gitTransport.push()
                if (!pushOk) {
                    errors.add("Push failed")
                }
            }

            if (pullResult.conflicts.isNotEmpty()) {
                errors.add("Resolved ${pullResult.conflicts.size} conflict(s): ${pullResult.conflicts.joinToString(", ")}")
            }

            userPreferences.setLastSyncedAt(System.currentTimeMillis())

            return SyncResult(
                uploaded = uploaded,
                downloaded = pullResult.filesDownloaded,
                errors = errors
            )
        } catch (e: Exception) {
            errors.add("${e.javaClass.simpleName}: ${e.message ?: "Sync failed"}")
            return SyncResult(errors = errors)
        }
    }

    private suspend fun mergeIfEnabled(): SyncResult? {
        return syncIfEnabled()
    }
}
