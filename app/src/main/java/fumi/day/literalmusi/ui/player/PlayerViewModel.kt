package fumi.day.literalmusi.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fumi.day.literalmusi.data.player.MusicPlayer
import fumi.day.literalmusi.data.player.PlayerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val musicPlayer: MusicPlayer
) : ViewModel() {

    val state: StateFlow<PlayerState> = musicPlayer.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerState())

    fun togglePlayPause() = musicPlayer.playPause()
    fun seekTo(position: Long) = musicPlayer.seekTo(position)
    fun skipToNext() = musicPlayer.skipToNext()
    fun skipToPrevious() = musicPlayer.skipToPrevious()
}
