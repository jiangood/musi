# Unified pile/ Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace MediaStore-based music scanning with direct `filesDir/pile/` file scanning, and provide a unified import flow (SAF + MediaStore) so all music lives in `pile/` for seamless GitHub sync.

**Architecture:** `MusicRepositoryImpl` changes from `contentResolver.query(MediaStore...)` to `File(pileDir).listFiles()` + `MediaMetadataRetriever` per file + `FileObserver` for auto-refresh. Settings screen replaces folder-picker UI with "Import Music" entry offering two modes: SAF folder picker and MediaStore browser. `GitHubSyncManager` switches from `readText`/`writeText` to `readBytes`/`writeBytes` for binary file support. `GitForgeApi` and `GitHubRepository` switch from `String` to `ByteArray` for file content.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, ExoPlayer, Android MediaMetadataRetriever

---

### Task 1: Update GitForgeApi interface and RemoteFile for binary content

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitForge.kt`

- [ ] **Step 1: Change `RemoteFile.content` from `String` to `ByteArray`, update `putFile` signature**

```kotlin
data class RemoteFile(
    val path: String,
    val sha: String,
    val content: ByteArray = ByteArray(0)
)

interface GitForgeApi {
    suspend fun putFile(token: String, repo: String, path: String, content: ByteArray, sha: String? = null, message: String = "Update $path"): Result<RemoteFile>
    // other methods unchanged
}
```

- [ ] **Step 2: Update `moveToTrash` default implementation**

```kotlin
suspend fun moveToTrash(token: String, repo: String, fileName: String, sha: String, content: ByteArray): Result<Unit> {
    return try {
        putFile(token, repo, "trash/$fileName", content, null, "Trash: $fileName").getOrThrow()
        deleteFile(token, repo, "pile/$fileName", sha, "Move to trash: $fileName").getOrThrow()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/GitForge.kt
git commit -m "feat: change GitForgeApi content from String to ByteArray"
```

---

### Task 2: Update GitHubRepository for binary content

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/github/GitHubRepository.kt`

- [ ] **Step 1: Update `getFile` to return `ByteArray` content**

```kotlin
override suspend fun getFile(token: String, repo: String, path: String): Result<RemoteFile> {
    return try {
        val (code, body) = makeRequest("GET", "$baseUrl/repos/$repo/contents/$path", token)
        when (code) {
            200 -> {
                val obj = JSONObject(body)
                val encoded = obj.getString("content").replace("\n", "")
                val content = Base64.decode(encoded, Base64.DEFAULT)
                Result.success(RemoteFile(path = obj.getString("path"), sha = obj.getString("sha"), content = content))
            }
            else -> Result.failure(Exception("Failed to get file: $code"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 2: Update `putFile` to accept `ByteArray`**

```kotlin
override suspend fun putFile(
    token: String, repo: String, path: String, content: ByteArray,
    sha: String?, message: String
): Result<RemoteFile> {
    return try {
        val encoded = Base64.encodeToString(content, Base64.NO_WRAP)
        val bodyObj = JSONObject().apply {
            put("message", message)
            put("content", encoded)
            if (sha != null) put("sha", sha)
        }
        val (code, body) = makeRequest("PUT", "$baseUrl/repos/$repo/contents/$path", token, bodyObj.toString())
        when (code) {
            200, 201 -> {
                val obj = JSONObject(body).getJSONObject("content")
                Result.success(
                    RemoteFile(path = obj.getString("path"), sha = obj.getString("sha"), content = content)
                )
            }
            else -> Result.failure(Exception("Failed to put file: $code - $body"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/github/GitHubRepository.kt
git commit -m "feat: update GitHubRepository for ByteArray content"
```

---

### Task 3: Update GitHubSyncManager for binary files + large file handling

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/github/GitHubSyncManager.kt`

- [ ] **Step 1: Replace all `readText(Charsets.UTF_8)` with `readBytes()`, `writeText(...)` with `writeBytes()`**

In the `sync()` method, change:
- `localPileFiles[fileName]!!.readText(Charsets.UTF_8)` → `localPileFiles[fileName]!!.readBytes()`
- `File(pileDir, fileName).writeText(contentResult.getOrThrow().content, Charsets.UTF_8)` → `File(pileDir, fileName).writeBytes(contentResult.getOrThrow().content)`
- `localFile.writeText(remoteContent, Charsets.UTF_8)` → `localFile.writeBytes(remoteContent)`
- `File(pileDir, conflictName).writeText(localContent, Charsets.UTF_8)` → `File(pileDir, conflictName).writeBytes(localContent)`
- `localFile.readText(Charsets.UTF_8)` → `localFile.readBytes()`
- `localContent: String` → `localContent: ByteArray`
- `remoteContent: String` → `remoteContent: ByteArray`
- Update conflict file naming to append `_conflict_<timestamp>` before extension (e.g., `song_conflict_20260521_120000.mp3`)

- [ ] **Step 2: Add large file (>25MB) skip during download**

In the `sync()` method, when downloading a remote file (the `!inLocalPile && inRemotePile` case and the remote-wins conflict case), add a size check:

```kotlin
// Before writing downloaded content to file
val contentBytes = contentResult.getOrThrow().content
if (contentBytes.size > 25 * 1024 * 1024) {
    errors.add("Skipped $fileName (>25MB, use PC git client to sync)")
    continue
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/github/GitHubSyncManager.kt
git commit -m "feat: update GitHubSyncManager for binary files and large file handling"
```

---

### Task 4: Clean up UserPreferences — remove included/excluded folder paths

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/prefs/UserPreferences.kt`

- [ ] **Step 1: Remove `includedFolderPaths` and `excludedFolderPaths` from `UserPrefs` data class and `UserPreferences` class**

Delete these members from the data class:
```kotlin
val includedFolderPaths: Set<String> = emptySet(),
val excludedFolderPaths: Set<String> = emptySet()
```

Remove these fields and methods entirely:
- `Keys.INCLUDED_FOLDER_PATHS`
- `Keys.EXCLUDED_FOLDER_PATHS`
- `_includedFolderPaths` MutableStateFlow
- `_excludedFolderPaths` MutableStateFlow
- `includedFolderPaths: Flow<Set<String>>`
- `excludedFolderPaths: Flow<Set<String>>`
- `addIncludedFolder()`
- `removeIncludedFolder()`
- `setIncludedFolders()`

Update `userPrefs` combine to remove the `included`/`excluded` parameters:
```kotlin
val userPrefs: Flow<UserPrefs> = combine(
    context.dataStore.data,
    _gitHubToken
) { prefs, token ->
    UserPrefs(
        gitHubEnabled = prefs[Keys.GITHUB_ENABLED] ?: false,
        gitHubToken = token,
        gitHubRepo = prefs[Keys.GITHUB_REPO] ?: "",
        lastSyncedAt = prefs[Keys.LAST_SYNCED_AT],
        lastSyncedShas = prefs[Keys.LAST_SYNCED_SHAS]?.let { parseShas(it) } ?: emptyMap()
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/prefs/UserPreferences.kt
git commit -m "refactor: remove included/excluded folder paths from UserPreferences"
```

---

### Task 5: Rewrite MusicRepositoryImpl — scan pile/ with MediaMetadataRetriever + FileObserver

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt`

- [ ] **Step 1: Rewrite `observeAll()` with `callbackFlow` + `FileObserver`**

Remove all MediaStore-related code (`scanSongs`, `buildSelection`, `buildSelectionArgs`). Keep `convertTreeUriToPath()` and `isAudioFile()` for import flow reuse.

New implementation:

```kotlin
package fumi.day.literalmusi.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MusicRepository {

    private val pileDir: File
        get() = File(context.filesDir, "pile").also { it.mkdirs() }

    private val audioExtensions = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac")

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
        awaitClose { observer.stopWatching() }
    }.flowOn(Dispatchers.IO)

    private fun scanPile(): List<Song> {
        return pileDir.listFiles()
            ?.filter { it.isFile && isAudioFileName(it.name) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file -> extractSong(file) }
            ?: emptyList()
    }

    private fun extractSong(file: File): Song? {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            mmr.release()
            Song(
                id = file.name.hashCode().toLong(),
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                uri = file.absolutePath,
                dataModified = file.lastModified()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        return name.substringAfterLast('.', "").lowercase() in audioExtensions
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt
git commit -m "feat: replace MediaStore scanning with pile/ file scanning + FileObserver"
```

---

### Task 6: Update MusicListScreen empty state text

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/list/MusicListScreen.kt`

- [ ] **Step 1: Change the empty state message**

Replace `"Go to Settings and add your music folders"` with `"Go to Settings to import music"` in `MusicListScreen.kt:83`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/ui/list/MusicListScreen.kt
git commit -m "fix: update empty state text for new import flow"
```

---

### Task 7: Rewrite SettingsViewModel — remove folder management, add import logic

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Rewrite SettingsViewModel**

Remove folder-related state and methods. Add import methods for SAF and MediaStore.

```kotlin
package fumi.day.literalmusi.ui.settings

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.github.GitHubSyncManager
import fumi.day.literalmusi.data.github.SyncResult
import fumi.day.literalmusi.data.prefs.UserPreferences
import fumi.day.literalmusi.data.prefs.UserPrefs
import fumi.day.literalmusi.data.repository.MusicRepository
import fumi.day.literalmusi.data.repository.MusicRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class ImportProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val errors: List<String> = emptyList(),
    val isImporting: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val syncManager: GitHubSyncManager,
    private val musicRepository: MusicRepository
) : ViewModel() {

    val userPrefs: StateFlow<UserPrefs> = userPreferences.userPrefs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPrefs()
        )

    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing

    private val _syncResult = MutableStateFlow<SyncResult?>(null)
    val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    fun importFromFolder(treeUri: Uri) {
        viewModelScope.launch {
            _importProgress.value = ImportProgress(isImporting = true)
            val errors = mutableListOf<String>()
            var completed = 0
            var total = 0

            withContext(Dispatchers.IO) {
                val pileDir = File(context.filesDir, "pile").also { it.mkdirs() }
                val audioFiles = discoverAudioFiles(treeUri)
                total = audioFiles.size

                for (uri in audioFiles) {
                    val fileName = getFileName(uri) ?: continue
                    val destFile = File(pileDir, fileName)

                    if (destFile.exists()) {
                        errors.add("$fileName already exists in your music library")
                        completed++
                        _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                        continue
                    }

                    if (isLargerThan25MB(uri)) {
                        errors.add("$fileName is too large (>25MB) for GitHub sync. Use PC git client to sync this file.")
                        completed++
                        _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                        continue
                    }

                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        errors.add("Failed to import $fileName: ${e.message}")
                    }
                    completed++
                    _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                }
            }

            _importProgress.value = ImportProgress(total, completed, errors, false)
        }
    }

    val mediaStoreSongs: MutableStateFlow<List<MediaStoreSong>> = MutableStateFlow(emptyList())

    fun loadMediaStoreSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )
            val songs = mutableListOf<MediaStoreSong>()
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataCol) ?: continue
                    songs.add(
                        MediaStoreSong(
                            id = cursor.getLong(idCol),
                            title = cursor.getString(titleCol) ?: File(data).nameWithoutExtension,
                            artist = cursor.getString(artistCol) ?: "Unknown Artist",
                            duration = cursor.getLong(durationCol),
                            uri = data
                        )
                    )
                }
            }
            mediaStoreSongs.value = songs
        }
    }

    fun importFromMediaStore(uris: List<String>) {
        viewModelScope.launch {
            _importProgress.value = ImportProgress(isImporting = true)
            val errors = mutableListOf<String>()
            var completed = 0
            val total = uris.size

            withContext(Dispatchers.IO) {
                val pileDir = File(context.filesDir, "pile").also { it.mkdirs() }

                for (uriString in uris) {
                    val srcFile = File(uriString)
                    val fileName = srcFile.name
                    val destFile = File(pileDir, fileName)

                    if (destFile.exists()) {
                        errors.add("$fileName already exists in your music library")
                        completed++
                        _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                        continue
                    }

                    if (srcFile.length() > 25 * 1024 * 1024) {
                        errors.add("$fileName is too large (>25MB) for GitHub sync. Use PC git client to sync this file.")
                        completed++
                        _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                        continue
                    }

                    try {
                        srcFile.copyTo(destFile, overwrite = false)
                    } catch (e: Exception) {
                        errors.add("Failed to import $fileName: ${e.message}")
                    }
                    completed++
                    _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
                }
            }

            _importProgress.value = ImportProgress(total, completed, errors, false)
        }
    }

    private fun discoverAudioFiles(treeUri: Uri): List<Uri> {
        val result = mutableListOf<Uri>()
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) { return result }

        fun walkDir(dirUri: Uri) {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, android.provider.DocumentsContract.getDocumentId(dirUri))
            context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                val mimeCol = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                val docCol = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeCol) ?: ""
                    val docId = cursor.getString(docCol) ?: continue
                    if (mime.startsWith("audio/")) {
                        result.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                    } else if (mime.startsWith(DocumentsContract.Document.MIME_TYPE_DIR)) {
                        val subDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        walkDir(subDirUri)
                    }
                }
            }
        }

        walkDir(treeUri)
        return result
    }

    private fun getFileName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameCol >= 0) return cursor.getString(nameCol)
            }
        }
        return null
    }

    private fun isLargerThan25MB(uri: Uri): Boolean {
        return try {
            val sizeCol = android.provider.DocumentsContract.Document.COLUMN_SIZE
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(sizeCol)
                    if (col >= 0) cursor.getLong(col) > 25 * 1024 * 1024 else false
                } else false
            } ?: false
        } catch (e: Exception) { false }
    }

    fun saveGitConfig(token: String, repo: String) {
        viewModelScope.launch {
            val current = userPreferences.userPrefs.first()
            val repoChanged = current.gitHubRepo.isNotBlank() && repo != current.gitHubRepo
            if (repoChanged) {
                syncManager.clearLocalData()
                userPreferences.resetSyncState()
            }
            userPreferences.setGitConfig(
                enabled = token.isNotBlank() && repo.isNotBlank(),
                token = token,
                repo = repo
            )
            if (token.isNotBlank() && repo.isNotBlank()) {
                syncNow()
            }
        }
    }

    fun disconnectGitHub() {
        viewModelScope.launch {
            userPreferences.clearGitHubConfig()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncResult.value = null
            _syncResult.value = syncManager.syncAndAwait()
        }
    }

    fun clearSyncResult() {
        _syncResult.value = null
    }

    fun clearImportResult() {
        _importProgress.value = ImportProgress()
    }
}

data class MediaStoreSong(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: String
)
```

Note: `MediaStoreSong` is placed in the same file for simplicity. It can be extracted later if needed.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsViewModel.kt
git commit -m "feat: rewrite SettingsViewModel for import flow, remove folder management"
```

---

### Task 8: Rewrite SettingsScreen — replace MusicFoldersCard with Import Music UI

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Replace `MusicFoldersCard` with `ImportMusicCard` and add import dialogs**

Replace the entire `MusicFoldersCard` call and composable with an `ImportMusicCard`. Add:
- Import progress dialog
- MediaStore song picker dialog

```kotlin
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val userPrefs by viewModel.userPrefs.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val mediaStoreSongs by viewModel.mediaStoreSongs.collectAsState()

    var showGitDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showMediaStorePicker by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importFromFolder(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ImportMusicCard(
                onImportFromFolder = { folderPicker.launch(null) },
                onImportFromMediaStore = {
                    viewModel.loadMediaStoreSongs()
                    showMediaStorePicker = true
                }
            )

            HorizontalDivider()

            GitSyncCard(
                userPrefs = userPrefs,
                isSyncing = isSyncing,
                accentColor = MaterialTheme.colorScheme.primary,
                onConnectClick = { showGitDialog = true },
                onSyncNowClick = viewModel::syncNow,
                onEditClick = { showGitDialog = true },
                onDisconnectClick = viewModel::disconnectGitHub
            )

            HorizontalDivider()

            Text(
                text = "About",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Musi v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "A minimal music player. All music is stored in the app's internal pile/ folder for seamless sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Git settings dialog (unchanged)
    if (showGitDialog) {
        GitSettingsDialog(
            initialToken = userPrefs.gitHubToken,
            initialRepo = userPrefs.gitHubRepo,
            onSave = { token, repo ->
                viewModel.saveGitConfig(token, repo)
                showGitDialog = false
            },
            onDismiss = { showGitDialog = false }
        )
    }

    // Import progress dialog
    if (importProgress.isImporting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Importing...") },
            text = {
                Column {
                    if (importProgress.total > 0) {
                        Text("${importProgress.completed} / ${importProgress.total} files")
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { importProgress.completed.toFloat() / importProgress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    if (importProgress.errors.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = importProgress.errors.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = { }
        )
    }

    // Import finished dialog
    if (!importProgress.isImporting && (importProgress.total > 0 || importProgress.errors.isNotEmpty())) {
        AlertDialog(
            onDismissRequest = { viewModel.clearImportResult() },
            title = { Text("Import Complete") },
            text = {
                Text(
                    if (importProgress.errors.isEmpty())
                        "Successfully imported ${importProgress.completed} files."
                    else
                        "Imported ${importProgress.completed} files with ${importProgress.errors.size} errors:\n${importProgress.errors.joinToString("\n")}"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImportResult() }) {
                    Text("OK")
                }
            }
        )
    }

    // Sync progress dialog (unchanged)
    if (isSyncing) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Syncing...") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Downloading and uploading files...")
                }
            },
            confirmButton = { }
        )
    }

    // Sync result dialog (unchanged)
    syncResult?.let { result ->
        if (!isSyncing) {
            AlertDialog(
                onDismissRequest = { viewModel.clearSyncResult() },
                title = { Text(if (result.errors.isEmpty()) "Sync Complete" else "Sync Error") },
                text = {
                    if (result.errors.isEmpty()) {
                        Text("Downloaded ${result.downloaded} files\nUploaded ${result.uploaded} files")
                    } else {
                        Text(result.errors.joinToString("\n"))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearSyncResult() }) {
                        Text("OK")
                    }
                }
            )
        }
    }

    // MediaStore picker dialog
    if (showMediaStorePicker && mediaStoreSongs.isNotEmpty()) {
        MediaStorePickerDialog(
            songs = mediaStoreSongs,
            onImport = { selectedUris ->
                viewModel.importFromMediaStore(selectedUris)
                showMediaStorePicker = false
            },
            onDismiss = { showMediaStorePicker = false }
        )
    }
}
```

Actually, the MediaStore picker dialog needs a proper multi-select UI. Let me write it as a separate composable for clarity:

```kotlin
@Composable
private fun ImportMusicCard(
    onImportFromFolder: () -> Unit,
    onImportFromMediaStore: () -> Unit
) {
    var showOptions by remember { mutableStateOf(false) }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Import Music",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Add music files to your library. Files are copied to the app's internal storage for syncing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showOptions = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import Music")
            }
        }
    }

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("Import Music") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showOptions = false
                            onImportFromFolder()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("From folder")
                    }
                    Button(
                        onClick = {
                            showOptions = false
                            onImportFromMediaStore()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("From existing music")
                    }
                }
            },
            confirmButton = { }
        )
    }
}
```

And a MediaStore picker:

```kotlin
@Composable
private fun MediaStorePickerDialog(
    songs: List<MediaStoreSong>,
    onImport: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Music to Import") },
        text = {
            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier.height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No music found on device.")
                }
            } else {
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(songs, key = { it.id }) { song ->
                        val isChecked = song.uri in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (isChecked) selected - song.uri
                                    else selected + song.uri
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = {
                                selected = if (isChecked) selected - song.uri
                                else selected + song.uri
                            })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${song.artist} · ${song.formattedDuration}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                Text("Import (${selected.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

Add `formattedDuration` to `MediaStoreSong`:
```kotlin
data class MediaStoreSong(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: String
) {
    val formattedDuration: String
        get() {
            val totalSeconds = duration / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
}
```

Also need to add these imports at the top of SettingsScreen.kt:
- `import androidx.compose.foundation.lazy.items`
- `import androidx.compose.material3.Checkbox`
- `import androidx.compose.material3.LinearProgressIndicator`

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsScreen.kt
git commit -m "feat: replace MusicFoldersCard with ImportMusicCard and picker dialogs"
```

---

### Task 9: Remove unused AppModule dependency injection for UserPreferences

**Files:**
- Check: `app/src/main/java/fumi/day/literalmusi/di/AppModule.kt`

No changes needed — `MusicRepositoryImpl` no longer takes `UserPreferences`. Hilt constructor injection handles it automatically.

But we need to verify that `MusicRepositoryImpl` no longer references `userPreferences`. It was removed in Task 5.

- [ ] **Step 1: Verify no compilation errors**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit (if any fixes needed)**

---

### Task 10: Build and verify

**Files:**
- Run build

- [ ] **Step 1: Run assembleDebug**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Address any compilation errors and fix**

Common issues to watch for:
- `MusicListViewModel` still injects `UserPreferences` but doesn't use it after the change — remove unused parameter
- Any reference to `MusicRepositoryImpl` as `MusicRepositoryImpl` (the SettingsViewModel cast) may need updating
- Missing imports in SettingsScreen

- [ ] **Step 3: Commit final fixes**

```bash
git add -A
git commit -m "fix: address compilation issues after pile/ unification"
```

---

## Self-Review Checklist

- [ ] Spec coverage: All sections covered (storage/scanning, import flow, settings, sync, large file handling)
- [ ] Placeholders: None
- [ ] Consistency: 
  - `RemoteFile.content` type changed from `String` to `ByteArray` everywhere
  - `putFile` signature updated in both interface and implementation
  - `GitHubSyncManager` uses `readBytes`/`writeBytes` consistently
  - All file paths referenced correctly
