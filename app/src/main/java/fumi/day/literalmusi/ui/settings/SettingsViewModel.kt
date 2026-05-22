package fumi.day.literalmusi.ui.settings

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.git.GitSyncManager
import fumi.day.literalmusi.data.git.SyncResult
import fumi.day.literalmusi.data.git.SyncScheduler
import fumi.day.literalmusi.data.prefs.UserPreferences
import fumi.day.literalmusi.data.prefs.UserPrefs
import fumi.day.literalmusi.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ImportProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val errors: List<String> = emptyList(),
    val isImporting: Boolean = false
)

data class MediaStoreSong(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: String,
    val parentFolder: String
) {
    val formattedDuration: String
        get() {
            val totalSeconds = duration / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val syncManager: GitSyncManager,
    private val musicRepository: MusicRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    val userPrefs: StateFlow<UserPrefs> = userPreferences.userPrefs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPrefs()
        )

    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing

    val lastSyncError: StateFlow<String?> = syncManager.syncError

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _showOverwriteConfirm = MutableStateFlow(false)
    val showOverwriteConfirm: StateFlow<Boolean> = _showOverwriteConfirm.asStateFlow()

    private var pendingAccessKey: String = ""
    private var pendingSecretKey: String = ""
    private var pendingBucket: String = ""
    private var pendingRegion: String = ""
    private var pendingDomain: String = ""

    val mediaStoreSongs: MutableStateFlow<List<MediaStoreSong>> = MutableStateFlow(emptyList())

    fun loadMediaStoreSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )
            val songs = mutableListOf<MediaStoreSong>()
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataCol) ?: continue
                    songs.add(
                        MediaStoreSong(
                            id = cursor.getLong(idCol),
                            title = cursor.getString(titleCol) ?: File(data).nameWithoutExtension,
                            artist = cursor.getString(artistCol) ?: "Unknown Artist",
                            duration = cursor.getLong(durationCol),
                            uri = data,
                            parentFolder = File(data).parent ?: ""
                        )
                    )
                }
            }
            mediaStoreSongs.value = songs
        }
    }

    fun importFromMediaStore(uris: List<String>) {
        viewModelScope.launch {
            _importProgress.value = ImportProgress(isImporting = true)
            val errors = mutableListOf<String>()
            var completed = 0
            val total = uris.size

            withContext(Dispatchers.IO) {
                val pileDir = getPileDir()

                for (uriString in uris) {
                    val srcFile = File(uriString)
                    val fileName = srcFile.name
                    val destFile = File(pileDir, fileName)

                    if (destFile.exists()) {
                        errors.add("$fileName already exists in your music library")
                        completed++
                        _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                        continue
                    }

                    try {
                        srcFile.copyTo(destFile, overwrite = false)
                        syncScheduler.onOperationEnqueued()
                    } catch (e: Exception) {
                        errors.add("Failed to import $fileName: ${e.message}")
                    }
                    completed++
                    _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                }
            }

            _importProgress.value = ImportProgress(total, completed, errors, false)
        }
    }

    private fun getPileDir(): File {
        return File(context.filesDir, "repo/pile").also { it.mkdirs() }
    }

    private fun autoDomain(bucket: String, region: String): String {
        val r = region.ifEmpty { "z0" }
        return "https://$bucket.s3-$r.qiniucs.com"
    }

    fun saveOssConfig(
        accessKey: String,
        secretKey: String,
        bucket: String,
        region: String,
        domain: String
    ) {
        val resolvedDomain = domain.ifBlank { autoDomain(bucket, region) }
        val resolvedRegion = region.ifEmpty { "z0" }
        viewModelScope.launch {
            val current = userPreferences.userPrefs.first()
            val repoDir = File(context.filesDir, "repo")
            val needsCleanup = bucket.isNotBlank() && (
                bucket != current.ossBucket ||
                (repoDir.exists() && repoDir.listFiles()?.isNotEmpty() == true)
            )
            if (needsCleanup) {
                if (repoDir.exists()) {
                    pendingAccessKey = accessKey
                    pendingSecretKey = secretKey
                    pendingBucket = bucket
                    pendingRegion = resolvedRegion
                    pendingDomain = resolvedDomain
                    _showOverwriteConfirm.value = true
                    return@launch
                }
                syncManager.clearLocalData()
                userPreferences.resetSyncState()
            }
            userPreferences.setOssConfig(
                enabled = accessKey.isNotBlank() && secretKey.isNotBlank() && bucket.isNotBlank(),
                accessKey = accessKey,
                secretKey = secretKey,
                bucket = bucket,
                region = resolvedRegion,
                domain = resolvedDomain
            )
            if (accessKey.isNotBlank() && secretKey.isNotBlank() && bucket.isNotBlank()) {
                syncNow()
            }
        }
    }

    fun confirmOverwrite() {
        viewModelScope.launch {
            _showOverwriteConfirm.value = false
            syncManager.clearLocalData()
            userPreferences.resetSyncState()
            userPreferences.setOssConfig(
                enabled = pendingAccessKey.isNotBlank() && pendingSecretKey.isNotBlank() && pendingBucket.isNotBlank(),
                accessKey = pendingAccessKey,
                secretKey = pendingSecretKey,
                bucket = pendingBucket,
                region = pendingRegion,
                domain = pendingDomain
            )
            if (pendingAccessKey.isNotBlank() && pendingSecretKey.isNotBlank() && pendingBucket.isNotBlank()) {
                syncNow()
            }
            pendingAccessKey = ""
            pendingSecretKey = ""
            pendingBucket = ""
            pendingRegion = ""
            pendingDomain = ""
        }
    }

    fun confirmMerge() {
        viewModelScope.launch {
            _showOverwriteConfirm.value = false
            userPreferences.setOssConfig(
                enabled = pendingAccessKey.isNotBlank() && pendingSecretKey.isNotBlank() && pendingBucket.isNotBlank(),
                accessKey = pendingAccessKey,
                secretKey = pendingSecretKey,
                bucket = pendingBucket,
                region = pendingRegion,
                domain = pendingDomain
            )
            if (pendingAccessKey.isNotBlank() && pendingSecretKey.isNotBlank() && pendingBucket.isNotBlank()) {
                _syncResult.value = syncManager.mergeAndAwait()
            }
            pendingAccessKey = ""
            pendingSecretKey = ""
            pendingBucket = ""
            pendingRegion = ""
            pendingDomain = ""
        }
    }

    fun cancelOverwrite() {
        _showOverwriteConfirm.value = false
        pendingAccessKey = ""
        pendingSecretKey = ""
        pendingBucket = ""
        pendingRegion = ""
        pendingDomain = ""
    }

    fun disconnectCloudSync() {
        viewModelScope.launch {
            userPreferences.clearOssConfig()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncResult.value = null
            _syncResult.value = syncManager.syncAndAwait()
        }
    }

    fun clearSyncResult() {
        _syncResult.value = null
    }

    fun clearImportResult() {
        _importProgress.value = ImportProgress()
    }
}
