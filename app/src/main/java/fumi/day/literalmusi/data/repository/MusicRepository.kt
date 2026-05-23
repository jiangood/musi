package fumi.day.literalmusi.data.repository

import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.flow.Flow
import java.io.File

interface MusicRepository {
    fun observeAll(): Flow<List<Song>>
    suspend fun deleteSong(song: Song)
    fun refresh()
    suspend fun updateUploadState(fileNames: List<String>, uploaded: Boolean)
    fun getPileDir(): File
}
