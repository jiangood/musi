package fumi.day.literalmusi.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fumi.day.literalmusi.data.player.MusicPlayer
import fumi.day.literalmusi.data.repository.MusicRepository
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val musicPlayer: MusicPlayer
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
        viewModelScope.launch {
            val current = musicPlayer.state.value.currentSong
            musicRepository.deleteSong(song)
            if (current?.id == song.id) {
                musicPlayer.stop()
            }
        }
    }
}
