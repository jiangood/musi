package fumi.day.literalmusi.ui.settings

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.git.CloudSyncManager
import fumi.day.literalmusi.data.prefs.UserPreferences
import fumi.day.literalmusi.data.prefs.UserPrefs
import fumi.day.literalmusi.data.repository.MusicRepository
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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

data class UploadProgress(
    val isUploading: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentFile: String = "",
    val errors: List<String> = emptyList()
)

data class DownloadProgress(
    val isDownloading: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentFile: String = "",
    val errors: List<String> = emptyList()
)

data class CloudFile(
    val fileName: String,
    val title: String
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
    private val cloudSyncManager: CloudSyncManager,
    private val musicRepository: MusicRepository
) : ViewModel() {

    val userPrefs: StateFlow<UserPrefs> = userPreferences.userPrefs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPrefs()
        )

    private val _localFileCount = MutableStateFlow(0)
    val localFileCount: StateFlow<Int> = _localFileCount.asStateFlow()

    private val _remoteFileCount = MutableStateFlow<Int?>(null)
    val remoteFileCount: StateFlow<Int?> = _remoteFileCount.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _uploadProgress = MutableStateFlow(UploadProgress())
    val uploadProgress: StateFlow<UploadProgress> = _uploadProgress.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    private val _uploadCandidates = MutableStateFlow<List<Song>>(emptyList())
    val uploadCandidates: StateFlow<List<Song>> = _uploadCandidates.asStateFlow()

    private val _downloadCandidates = MutableStateFlow<List<CloudFile>>(emptyList())
    val downloadCandidates: StateFlow<List<CloudFile>> = _downloadCandidates.asStateFlow()

    private val _showOverwriteConfirm = MutableStateFlow(false)
    val showOverwriteConfirm: StateFlow<Boolean> = _showOverwriteConfirm.asStateFlow()

    private var pendingToken: String = ""
    private var pendingRepo: String = ""

    private var uploadJob: Job? = null
    private var downloadJob: Job? = null

    val mediaStoreSongs: MutableStateFlow<List<MediaStoreSong>> = MutableStateFlow(emptyList())

    init {
        refreshFileCounts()
    }

    // ---- MediaStore Import ----

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

    fun importFromMediaStore(uris: List<String>, alsoUploadToCloud: Boolean = false) {
        viewModelScope.launch {
            _importProgress.value = ImportProgress(isImporting = true)
            val errors = mutableListOf<String>()
            var completed = 0
            val total = uris.size
            val importedFiles = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                val pileDir = musicRepository.getPileDir()

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
                        importedFiles.add(fileName)
                    } catch (e: Exception) {
                        errors.add("Failed to import $fileName: ${e.message}")
                    }
                    completed++
                    _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                }
            }

            _importProgress.value = ImportProgress(total, completed, errors, false)
            musicRepository.refresh()

            if (alsoUploadToCloud && importedFiles.isNotEmpty()) {
                uploadToCloud(importedFiles)
            }
        }
    }

    // ---- GitHub Config ----

    fun saveGitConfig(token: String, repo: String) {
        viewModelScope.launch {
            val current = userPreferences.userPrefs.first()
            val repoDir = File(context.filesDir, "repo")
            val needsCleanup = repo.isNotBlank() && (
                repo != current.gitHubRepo ||
                (repoDir.exists() && !File(repoDir, ".git").exists())
            )
            if (needsCleanup) {
                if (repoDir.exists()) {
                    pendingToken = token
                    pendingRepo = repo
                    _showOverwriteConfirm.value = true
                    return@launch
                }
                clearLocalData()
                userPreferences.resetSyncState()
            }
            userPreferences.setGitConfig(
                enabled = token.isNotBlank() && repo.isNotBlank(),
                token = token,
                repo = repo
            )
            refreshFileCounts()
        }
    }

    fun confirmOverwrite() {
        viewModelScope.launch {
            _showOverwriteConfirm.value = false
            clearLocalData()
            userPreferences.resetSyncState()
            userPreferences.setGitConfig(
                enabled = pendingToken.isNotBlank() && pendingRepo.isNotBlank(),
                token = pendingToken,
                repo = pendingRepo
            )
            pendingToken = ""
            pendingRepo = ""
            refreshFileCounts()
        }
    }

    fun confirmMerge() {
        viewModelScope.launch {
            _showOverwriteConfirm.value = false
            userPreferences.setGitConfig(
                enabled = pendingToken.isNotBlank() && pendingRepo.isNotBlank(),
                token = pendingToken,
                repo = pendingRepo
            )
            pendingToken = ""
            pendingRepo = ""
            refreshFileCounts()
        }
    }

    fun cancelOverwrite() {
        _showOverwriteConfirm.value = false
        pendingToken = ""
        pendingRepo = ""
    }

    fun disconnectGitHub() {
        viewModelScope.launch {
            userPreferences.clearGitHubConfig()
            refreshFileCounts()
        }
    }

    private fun clearLocalData() {
        val repoDir = File(context.filesDir, "repo")
        repoDir.deleteRecursively()
    }

    // ---- Cloud Upload ----

    fun refreshUploadCandidates() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!cloudSyncManager.isConfigured()) {
                    _uploadCandidates.value = emptyList()
                    return@launch
                }
                val remoteFiles = cloudSyncManager.listRemoteFilenames().toSet()
                val localFiles = musicRepository.getPileDir().listFiles()
                    ?.filter { it.isFile && isAudioFile(it.name) }.orEmpty()
                val notUploaded = localFiles.filter { it.name !in remoteFiles }
                _uploadCandidates.value = notUploaded.mapNotNull { file ->
                    val songs = musicRepository.observeAll().first()
                    songs.find { File(it.uri).name == file.name }
                        ?: Song(
                            id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
                            title = file.nameWithoutExtension,
                            artist = "",
                            album = "",
                            duration = 0,
                            uri = file.absolutePath,
                            dataModified = file.lastModified(),
                            isUploaded = false
                        )
                }
            } catch (_: Exception) {
                _uploadCandidates.value = emptyList()
            }
        }
    }

    fun uploadToCloud(fileNames: List<String>) {
        if (uploadJob?.isActive == true) return
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            _uploadProgress.value = UploadProgress(isUploading = true, total = fileNames.size)
            val files = fileNames.map { File(musicRepository.getPileDir(), it) }.filter { it.exists() }
            var completed = 0
            val errors = mutableListOf<String>()
            for ((i, file) in files.withIndex()) {
                if (!currentCoroutineContext().isActive) break
                _uploadProgress.value = _uploadProgress.value.copy(
                    currentFile = file.name,
                    completed = i,
                    total = files.size
                )
                try {
                    val result = cloudSyncManager.uploadFiles(listOf(file))
                    if (result.isFailure) {
                        errors.add("${file.name}: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    errors.add("${file.name}: ${e.message}")
                }
                completed++
            }
            musicRepository.updateUploadState(fileNames.take(completed), true)
            _uploadProgress.value = UploadProgress(errors = errors)
            refreshFileCounts()
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        _uploadProgress.value = UploadProgress()
    }

    // ---- Cloud Download ----

    fun refreshDownloadCandidates() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!cloudSyncManager.isConfigured()) {
                    _downloadCandidates.value = emptyList()
                    return@launch
                }
                val remoteFiles = cloudSyncManager.listRemoteFilenames().toSet()
                val localNames = musicRepository.getPileDir().listFiles()
                    ?.map { it.name }.orEmpty().toSet()
                _downloadCandidates.value = remoteFiles
                    .filter { it !in localNames }
                    .map { CloudFile(fileName = it, title = it.removeSuffix(it.substringAfterLast('.'))) }
            } catch (_: Exception) {
                _downloadCandidates.value = emptyList()
            }
        }
    }

    fun downloadFromCloud(fileNames: List<String>) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            _downloadProgress.value = DownloadProgress(isDownloading = true, total = fileNames.size)
            var completed = 0
            val errors = mutableListOf<String>()
            val downloaded = mutableListOf<String>()
            val pileDir = musicRepository.getPileDir()
            for ((i, name) in fileNames.withIndex()) {
                if (!currentCoroutineContext().isActive) break
                _downloadProgress.value = _downloadProgress.value.copy(
                    currentFile = name,
                    completed = i,
                    total = fileNames.size
                )
                if (File(pileDir, name).exists()) {
                    errors.add("$name: skipped (already exists locally)")
                    completed++
                    continue
                }
                try {
                    val result = cloudSyncManager.downloadFiles(listOf(name))
                    if (result.isSuccess) {
                        downloaded.add(name)
                    } else {
                        errors.add("$name: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    errors.add("$name: ${e.message}")
                }
                completed++
            }
            musicRepository.updateUploadState(downloaded, true)
            musicRepository.refresh()
            _downloadProgress.value = DownloadProgress(errors = errors)
            refreshFileCounts()
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _downloadProgress.value = DownloadProgress()
    }

    // ---- Helpers ----

    private fun refreshFileCounts() {
        _localFileCount.value = musicRepository.getPileDir().listFiles()?.filter { it.isFile }?.size ?: 0
        viewModelScope.launch {
            try {
                _remoteFileCount.value = cloudSyncManager.listRemoteFilenames().size
            } catch (_: Exception) {
                _remoteFileCount.value = null
            }
        }
    }

    fun clearImportResult() {
        _importProgress.value = ImportProgress()
    }

    private fun isAudioFile(name: String): Boolean {
        val audioExtensions = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")
        return name.substringAfterLast('.', "").lowercase() in audioExtensions
    }
}
