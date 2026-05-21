package fumi.day.literalmusi.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fumi.day.literalmusi.data.github.GitHubSyncManager
import fumi.day.literalmusi.data.github.SyncResult
import fumi.day.literalmusi.data.prefs.UserPreferences
import fumi.day.literalmusi.data.prefs.UserPrefs
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
    private val syncManager: GitHubSyncManager
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
