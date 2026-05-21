package fumi.day.literalmusi.data.repository

import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MusicRepository {

    private val excludedPaths = listOf(
        "Recordings", "录音", "CallRecordings", "Call Recorder",
        "Voice Recorder", "录音机", "Sound Recorder", "Audio Recorder",
        "通话录音", "电话录音", "Recorder", "VoiceRecords",
        "com.android.soundrecorder", "com.samsung.android.app.contacts",
        "WhatsApp Voice Notes", "Telegram", "Voices"
    )

    private val musicMimeTypes = listOf(
        "audio/mpeg", "audio/mp3", "audio/flac", "audio/ogg",
        "audio/wav", "audio/x-wav", "audio/aac", "audio/m4a",
        "audio/x-m4a", "audio/opus", "audio/wma", "audio/x-ms-wma",
        "audio/aiff", "audio/x-aiff", "audio/midi", "audio/x-midi",
        "audio/amr", "audio/3gpp", "audio/x-flac", "audio/ape",
        "audio/x-ape", "audio/vorbis", "audio/webm"
    )

    override fun observeAll(): Flow<List<Song>> = flow {
        while (true) {
            emit(scanSongs())
            delay(5000)
        }
    }.flowOn(Dispatchers.IO)

    private fun scanSongs(): List<Song> {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.IS_MUSIC
        )

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val data = cursor.getString(dataCol) ?: continue
                val duration = cursor.getLong(durationCol)
                val isMusic = cursor.getInt(isMusicCol)
                val artist = cursor.getString(artistCol) ?: ""
                val album = cursor.getString(albumCol) ?: ""
                val title = cursor.getString(titleCol) ?: ""
                val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null

                if (!isMusicFile(data, duration, isMusic, artist, album, title, mimeType)) continue

                songs.add(
                    Song(
                        id = id,
                        title = title.ifBlank { "Unknown" },
                        artist = artist.ifBlank { "Unknown Artist" },
                        album = album.ifBlank { "Unknown Album" },
                        duration = duration,
                        uri = data,
                        dataModified = cursor.getLong(dateCol) * 1000
                    )
                )
            }
        }
        return songs
    }

    private val nonMusicKeywords = listOf(
        "recording", "record", "rec_", "rec-", "录音", "通话",
        "call_", "call-", "voic", "voice", "audio note", "note_",
        "message", "语音", "unknown", "untitled"
    )

    private val knownBadArtist = setOf(
        "", "unknown", "unknown artist", "未知", "未知艺术家",
        "录音", "voice recorder", "sound recorder", "recording"
    )

    private fun isMusicFile(
        path: String,
        duration: Long,
        isMusic: Int,
        artist: String,
        album: String,
        title: String,
        mimeType: String?
    ): Boolean {
        if (duration < 30000) return false

        if (isMusic == 1) {
            if (hasRealMetadata(artist, album, title)) return true
        }

        if (mimeType != null && musicMimeTypes.none { it.equals(mimeType, ignoreCase = true) }) {
            return false
        }

        if (hasRealMetadata(artist, album, title)) return true

        val pathLower = path.lowercase()
        val fileName = pathLower.substringAfterLast(File.separatorChar)
        val dirPath = pathLower.substringBeforeLast(File.separatorChar)

        if (excludedPaths.any { dirPath.contains(it.lowercase()) }) return false
        if (nonMusicKeywords.any { fileName.contains(it) }) return false
        if (nonMusicKeywords.any { title.lowercase().contains(it) }) return false

        if (!fileName.endsWith(".mp3") && !fileName.endsWith(".flac") &&
            !fileName.endsWith(".ogg") && !fileName.endsWith(".wav") &&
            !fileName.endsWith(".m4a") && !fileName.endsWith(".aac") &&
            !fileName.endsWith(".opus") && !fileName.endsWith(".wma")
        ) return false

        return true
    }

    private fun hasRealMetadata(artist: String, album: String, title: String): Boolean {
        val a = artist.trim().lowercase()
        val al = album.trim().lowercase()
        val t = title.trim().lowercase()

        if (a.isBlank() || a in knownBadArtist) return false
        if (t.isBlank()) return false
        if (al.isBlank() || al in knownBadArtist) return false
        if (a == al) return false
        if (a.length <= 1) return false

        return true
    }
}
