package fumi.day.literalmusi.data.repository

import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface MusicRepository {
    fun observeAll(): Flow<List<Song>>
    suspend fun deleteSong(song: Song)
    fun refresh()
}
