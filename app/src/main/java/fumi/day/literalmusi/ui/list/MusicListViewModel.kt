package fumi.day.literalmusi.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fumi.day.literalmusi.data.player.MusicPlayer
import fumi.day.literalmusi.data.repository.MusicRepository
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MusicListViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val musicPlayer: MusicPlayer
) : ViewModel() {

    val songs: StateFlow<List<Song>> = musicRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun playSong(song: Song) {
        musicPlayer.play(song, songs.value)
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }
}
