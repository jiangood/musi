package fumi.day.literalmusi.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fumi.day.literalmusi.data.player.MusicPlayer
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val musicPlayer: MusicPlayer
) : ViewModel() {

    override fun onCleared() {
        super.onCleared()
    }
}
