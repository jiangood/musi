package fumi.day.literalmusi.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.git.GitTransport
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitTransport: GitTransport
) : MusicRepository {

    private val pileDir: File get() = gitTransport.pileDir
    private val audioExtensions = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")
    private val _refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun observeAll(): Flow<List<Song>> = callbackFlow {
        trySend(scanPile())

        val observer = object : android.os.FileObserver(pileDir, CREATE or DELETE or MOVED_FROM or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && isAudioFileName(path)) {
                    trySend(scanPile())
                }
            }
        }
        observer.startWatching()

        val refreshJob = launch {
            _refresh.collect { trySend(scanPile()) }
        }

        awaitClose {
            observer.stopWatching()
            refreshJob.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun scanPile(): List<Song> {
        if (!pileDir.exists()) return emptyList()
        return pileDir.listFiles()
            ?.filter { it.isFile && isAudioFileName(it.name) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file -> extractSong(file) }
            ?: emptyList()
    }

    private fun extractSong(file: File): Song? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Song(
                id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                uri = file.absolutePath,
                dataModified = file.lastModified()
            )
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase() in audioExtensions
    }

    suspend fun addFilesToPile(uris: List<Uri>, deleteSource: Boolean = false): List<String> {
        val errors = mutableListOf<String>()
        for (uri in uris) {
            try {
                val fileName = getFileName(uri) ?: "track_${System.currentTimeMillis()}"
                val destFile = File(pileDir, fileName)
                if (destFile.exists()) {
                    errors.add("$fileName already exists in your music library")
                    continue
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (deleteSource) {
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                errors.add("Failed to import file: ${e.message}")
            }
        }
        return errors
    }

    override suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        val file = File(song.uri)
        if (!file.exists()) return@withContext

        val trash = gitTransport.trashDir
        trash.mkdirs()
        file.renameTo(File(trash, file.name))

        _refresh.tryEmit(Unit)
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return name ?: uri.lastPathSegment
    }
}
