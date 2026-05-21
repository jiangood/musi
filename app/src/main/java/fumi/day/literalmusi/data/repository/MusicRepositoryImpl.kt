package fumi.day.literalmusi.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.prefs.UserPreferences
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences
) : MusicRepository {

    override fun observeAll(): Flow<List<Song>> = combine(
        userPreferences.includedFolderPaths,
        userPreferences.excludedFolderPaths
    ) { included, excluded ->
        scanSongs(included, excluded)
    }.flowOn(Dispatchers.IO)

    private fun scanSongs(included: Set<String>, excluded: Set<String>): List<Song> {
        if (included.isEmpty()) return emptyList()

        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.IS_MUSIC
        )

        val selection = buildSelection(included, excluded)
        val selectionArgs = buildSelectionArgs(included, excluded)

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)

            while (cursor.moveToNext()) {
                val data = cursor.getString(dataCol) ?: continue
                val dir = File(data).parent
                if (dir != null && dir in excluded) continue

                val duration = cursor.getLong(durationCol)
                if (duration < 30000) continue

                val artist = cursor.getString(artistCol) ?: ""
                val album = cursor.getString(albumCol) ?: ""
                val title = cursor.getString(titleCol) ?: ""

                songs.add(
                    Song(
                        id = cursor.getLong(idCol),
                        title = title.ifBlank { File(data).nameWithoutExtension },
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

    private fun buildSelection(included: Set<String>, excluded: Set<String>): String {
        val parts = mutableListOf<String>()
        parts.add("${MediaStore.Audio.Media.DURATION} >= 30000")

        if (included.isNotEmpty()) {
            val orClauses = included.map { "${MediaStore.Audio.Media.DATA} LIKE ?" }
            parts.add("(${orClauses.joinToString(" OR ")})")
        }

        return parts.joinToString(" AND ")
    }

    private fun buildSelectionArgs(included: Set<String>, excluded: Set<String>): Array<String> {
        val args = mutableListOf<String>()
        included.forEach { path ->
            args.add("${File(path)}/%")
        }
        return args.toTypedArray()
    }

    fun convertTreeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            if (split.size >= 2 && split[0] == "primary") {
                "/storage/emulated/0/${split[1]}"
            } else if (split.size >= 2) {
                "/storage/${split[0]}/${split[1]}"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isAudioFile(uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri) ?: ""
            mimeType.startsWith("audio/")
        } catch (e: Exception) {
            false
        }
    }
}
