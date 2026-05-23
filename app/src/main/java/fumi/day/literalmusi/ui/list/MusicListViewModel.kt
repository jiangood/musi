package fumi.day.literalmusi.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fumi.day.literalmusi.data.git.CloudSyncManager
import fumi.day.literalmusi.data.player.MusicPlayer
import fumi.day.literalmusi.data.repository.MusicRepository
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

sealed class MusicListItem {
    data class SectionHeader(val label: String) : MusicListItem()
    data class SongEntry(val song: Song) : MusicListItem()
}

@HiltViewModel
class MusicListViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val musicPlayer: MusicPlayer,
    private val cloudSyncManager: CloudSyncManager
) : ViewModel() {

    private val collator = Collator.getInstance(Locale.CHINESE)

    val songs: StateFlow<List<Song>> = musicRepository.observeAll()
        .map { it.sortedWith(compareBy(collator) { it.title }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupedSongs: StateFlow<List<MusicListItem>> = songs
        .map { songs ->
            if (songs.isEmpty()) return@map emptyList()
            val result = mutableListOf<MusicListItem>()
            var lastLabel: String? = null
            for (song in songs) {
                val label = sectionLabel(song.title)
                if (label != lastLabel) {
                    result.add(MusicListItem.SectionHeader(label))
                    lastLabel = label
                }
                result.add(MusicListItem.SongEntry(song))
            }
            result
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun sectionLabel(title: String): String {
        val c = title.firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercase() else "#"
    }

    fun playSong(song: Song) {
        musicPlayer.play(song, songs.value)
    }

    fun deleteSong(song: Song) {
        deleteSong(song, false)
    }

    fun deleteSong(song: Song, alsoDeleteFromCloud: Boolean) {
        viewModelScope.launch {
            if (alsoDeleteFromCloud) {
                try {
                    val fileName = File(song.uri).name
                    cloudSyncManager.deleteRemoteFiles(listOf(fileName))
                } catch (_: Exception) { }
            }
            val current = musicPlayer.state.value.currentSong
            musicRepository.deleteSong(song)
            if (current?.id == song.id) {
                musicPlayer.stop()
            }
        }
    }

    fun reconcileCloudStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!cloudSyncManager.isConfigured()) return@launch
                val remoteFiles = cloudSyncManager.listRemoteFilenames().toSet()
                val localFiles = musicRepository.getPileDir().listFiles()
                    ?.filter { it.isFile }?.map { it.name }.orEmpty()
                val uploaded = localFiles.filter { it in remoteFiles }
                musicRepository.updateUploadState(uploaded, true)
                val notUploaded = localFiles.filter { it !in remoteFiles }
                musicRepository.updateUploadState(notUploaded, false)
            } catch (_: Exception) {
            }
        }
    }

    fun markUploaded(fileNames: List<String>) {
        viewModelScope.launch {
            musicRepository.updateUploadState(fileNames, true)
        }
    }

    fun markNotUploaded(fileNames: List<String>) {
        viewModelScope.launch {
            musicRepository.updateUploadState(fileNames, false)
        }
    }
}
