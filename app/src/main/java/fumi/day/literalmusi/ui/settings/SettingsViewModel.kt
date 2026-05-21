package fumi.day.literalmusi.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fumi.day.literalmusi.data.git.GitSyncManager
import fumi.day.literalmusi.data.git.SyncResult
import fumi.day.literalmusi.data.prefs.UserPreferences
import fumi.day.literalmusi.data.prefs.UserPrefs
import fumi.day.literalmusi.data.repository.MusicRepository
import fumi.day.literalmusi.data.repository.MusicRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val syncManager: GitSyncManager,
    private val musicRepository: MusicRepository
) : ViewModel() {

    val userPrefs: StateFlow<UserPrefs> = userPreferences.userPrefs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPrefs()
        )

    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()

    private val _includedFolders = MutableStateFlow<Set<String>>(emptySet())
    val includedFolders: StateFlow<Set<String>> = _includedFolders.asStateFlow()

    fun loadIncludedFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = userPreferences.userPrefs.first()
            _includedFolders.value = prefs.includedFolderPaths
        }
    }

    fun addMusicFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val repo = musicRepository as? MusicRepositoryImpl ?: return@launch
            val path = repo.convertTreeUriToPath(uri) ?: return@launch
            userPreferences.addIncludedFolder(path)
            val current = _includedFolders.value.toMutableSet()
            current.add(path)
            _includedFolders.value = current
        }
    }

    fun removeMusicFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            userPreferences.removeIncludedFolder(path)
            val current = _includedFolders.value.toMutableSet()
            current.remove(path)
            _includedFolders.value = current
        }
    }

    fun saveGitConfig(token: String, repo: String) {
        viewModelScope.launch {
            val current = userPreferences.userPrefs.first()
            val repoChanged = current.gitHubRepo.isNotBlank() && repo != current.gitHubRepo
            if (repoChanged) {
                syncManager.clearLocalData()
                userPreferences.resetSyncState()
            }
            userPreferences.setGitConfig(
                enabled = token.isNotBlank() && repo.isNotBlank(),
                token = token,
                repo = repo
            )
            if (token.isNotBlank() && repo.isNotBlank()) {
                syncNow()
            }
        }
    }

    fun disconnectGitHub() {
        viewModelScope.launch {
            userPreferences.clearGitHubConfig()
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
}
