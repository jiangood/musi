package fumi.day.literalmusi.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.git.GitTransport
import fumi.day.literalmusi.data.git.OpLog
import fumi.day.literalmusi.data.git.OpType
import fumi.day.literalmusi.data.git.Operation
import fumi.day.literalmusi.data.git.SyncScheduler
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
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitTransport: GitTransport,
    private val opLog: OpLog,
    private val syncScheduler: SyncScheduler
) : MusicRepository {

    private val pileDir: File get() = gitTransport.pileDir
    private val audioExtensions = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")
    private val _refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val cacheFile: File get() = File(context.filesDir, "cache/music_cache.json")

    private data class CacheEntry(
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val dataModified: Long,
        val format: String? = null,
        val qualityLabel: String? = null
    )

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
        val files = pileDir.listFiles()
            ?.filter { it.isFile && isAudioFileName(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()

        val cache = readCache()
        val updatedEntries = mutableMapOf<String, CacheEntry>()
        val result = mutableListOf<Song>()

        for (file in files) {
            val relPath = "pile/${file.name}"
            val cached = cache[relPath]
            val song: Song?

            if (cached != null && cached.dataModified == file.lastModified()) {
                song = Song(
                    id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
                    title = cached.title,
                    artist = cached.artist,
                    album = cached.album,
                    duration = cached.duration,
                    uri = file.absolutePath,
                    dataModified = cached.dataModified,
                    format = cached.format,
                    qualityLabel = cached.qualityLabel
                )
            } else {
                song = extractSong(file)
                if (song != null) {
                    updatedEntries[relPath] = CacheEntry(
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        duration = song.duration,
                        dataModified = song.dataModified,
                        format = song.format,
                        qualityLabel = song.qualityLabel
                    )
                }
            }

            if (song != null) {
                result.add(song)
                if (relPath !in updatedEntries) {
                    updatedEntries[relPath] = cache[relPath]!!
                }
            }
        }

        if (updatedEntries.isNotEmpty()) {
            writeCache(updatedEntries)
        }

        val collator = Collator.getInstance(Locale.CHINESE)
        return result.sortedWith(compareBy(collator) { it.title })
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
            val format = file.extension.uppercase()
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
            val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                ?.toIntOrNull() ?: 0
            val qualityLabel = computeQualityLabel(format, bitrate, sampleRate)
            Song(
                id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                uri = file.absolutePath,
                dataModified = file.lastModified(),
                format = format,
                qualityLabel = qualityLabel
            )
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun computeQualityLabel(format: String, bitrate: Long, sampleRate: Int): String? {
        val lossyFormats = setOf("MP3", "AAC", "OGG", "OPUS", "WMA")
        val losslessFormats = setOf("FLAC", "WAV", "ALAC", "AIFF", "DSF", "DFF")
        return when {
            format in lossyFormats && bitrate > 0 -> "$format ${bitrate / 1000}"
            format in losslessFormats && sampleRate > 0 -> "$format ${sampleRate / 1000}kHz"
            else -> format
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase() in audioExtensions
    }

    private fun readCache(): Map<String, CacheEntry> {
        if (!cacheFile.exists()) return emptyMap()
        return try {
            val json = cacheFile.readText()
            val obj = org.json.JSONObject(json)
            val entries = obj.getJSONObject("entries")
            val map = mutableMapOf<String, CacheEntry>()
            for (key in entries.keys()) {
                val e = entries.getJSONObject(key)
                map[key] = CacheEntry(
                    title = e.getString("title"),
                    artist = e.getString("artist"),
                    album = e.getString("album"),
                    duration = e.getLong("duration"),
                    dataModified = e.getLong("dataModified"),
                    format = e.optString("format", null),
                    qualityLabel = e.optString("qualityLabel", null)
                )
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeCache(entries: Map<String, CacheEntry>) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, "music_cache.json.tmp")
            val obj = org.json.JSONObject()
            val entriesObj = org.json.JSONObject()
            for ((path, entry) in entries) {
                val e = org.json.JSONObject()
                e.put("title", entry.title)
                e.put("artist", entry.artist)
                e.put("album", entry.album)
                e.put("duration", entry.duration)
                e.put("dataModified", entry.dataModified)
                entry.format?.let { e.put("format", it) }
                entry.qualityLabel?.let { e.put("qualityLabel", it) }
                entriesObj.put(path, e)
            }
            obj.put("version", 1)
            obj.put("entries", entriesObj)
            tmp.writeText(obj.toString(2))
            tmp.renameTo(cacheFile)
        } catch (_: Exception) {
        }
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
                opLog.append(Operation(
                    type = OpType.ADD,
                    path = "pile/$fileName"
                ))
                syncScheduler.onOperationEnqueued()
                if (deleteSource) {
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                errors.add("Failed to import file: ${e.message}")
            }
        }
        _refresh.tryEmit(Unit)
        return errors
    }

    override fun refresh() {
        _refresh.tryEmit(Unit)
    }

    override suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        val file = File(song.uri)
        if (!file.exists()) return@withContext

        val trash = gitTransport.trashDir
        trash.mkdirs()
        file.renameTo(File(trash, file.name))

        opLog.append(Operation(
            type = OpType.DELETE,
            path = "pile/${file.name}"
        ))
        syncScheduler.onOperationEnqueued()

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
