package fumi.day.literalmusi.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fumi.day.literalmusi.data.player.MusicPlayer
import fumi.day.literalmusi.data.prefs.UserPreferences
import fumi.day.literalmusi.data.repository.MusicRepository
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MusicListViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val musicPlayer: MusicPlayer,
    private val userPreferences: UserPreferences
) : ViewModel() {

    val songs: StateFlow<List<Song>> = musicRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playSong(song: Song) {
        musicPlayer.play(song, songs.value)
    }
}
