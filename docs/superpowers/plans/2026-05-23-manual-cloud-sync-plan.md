# Manual Cloud Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace automatic Git-based sync with manual upload/download buttons, cloud status indicators per song, and cloud-aware delete/import flows.

**Architecture:** CloudSyncManager wraps GitHubApiTransport for manual file operations. Song model gains `isUploaded` field persisted in music_cache.json. Every UI operation reconciles against remote file list.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, GitHub REST API

---

### Task 1: Add `isUploaded` to Song model and cache

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/domain/model/Song.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt`

- [ ] **Step 1: Add `isUploaded` field to Song**

In `Song.kt`, add `isUploaded` field:

```kotlin
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: String,
    val dataModified: Long,
    val format: String? = null,
    val qualityLabel: String? = null,
    val isUploaded: Boolean = false   // NEW
)
```

- [ ] **Step 2: Add `isUploaded` to CacheEntry**

In `MusicRepositoryImpl.kt`, add `isUploaded` to `CacheEntry`:

```kotlin
private data class CacheEntry(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val dataModified: Long,
    val format: String? = null,
    val qualityLabel: String? = null,
    val isUploaded: Boolean = false   // NEW
)
```

- [ ] **Step 3: Read `isUploaded` from cache in `scanPile`**

In `scanPile()`, after reading cached entry, pass `isUploaded` to Song:

```kotlin
if (cached != null && cached.dataModified == file.lastModified() && cached.format != null) {
    song = Song(
        id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
        title = cached.title,
        artist = cached.artist,
        album = cached.album,
        duration = cached.duration,
        uri = file.absolutePath,
        dataModified = cached.dataModified,
        format = cached.format,
        qualityLabel = cached.qualityLabel,
        isUploaded = cached.isUploaded    // NEW
    )
}
```

- [ ] **Step 4: Write `isUploaded` to cache entries**

In `scanPile()` where `updatedEntries` is populated, add `isUploaded`:

```kotlin
updatedEntries[relPath] = CacheEntry(
    title = song.title,
    artist = song.artist,
    album = song.album,
    duration = song.duration,
    dataModified = song.dataModified,
    format = song.format,
    qualityLabel = song.qualityLabel,
    isUploaded = song.isUploaded    // NEW
)
```

And in the fallback when reading from cache:

```kotlin
if (relPath !in updatedEntries) {
    updatedEntries[relPath] = cache[relPath]!!
}
```

This is fine because cache already contains the existing entry. No change needed.

- [ ] **Step 5: Update `writeCache` to persist `isUploaded`**

In `writeCache()`:

```kotlin
for ((path, entry) in entries) {
    val e = org.json.JSONObject()
    e.put("title", entry.title)
    e.put("artist", entry.artist)
    e.put("album", entry.album)
    e.put("duration", entry.duration)
    e.put("dataModified", entry.dataModified)
    entry.format?.let { e.put("format", it) }
    entry.qualityLabel?.let { e.put("qualityLabel", it) }
    e.put("isUploaded", entry.isUploaded)    // NEW
    entriesObj.put(path, e)
}
```

- [ ] **Step 6: Read `isUploaded` from JSON cache**

In `readCache()`:

```kotlin
map[key] = CacheEntry(
    title = e.getString("title"),
    artist = e.getString("artist"),
    album = e.getString("album"),
    duration = e.getLong("duration"),
    dataModified = e.getLong("dataModified"),
    format = e.optString("format", null),
    qualityLabel = e.optString("qualityLabel", null),
    isUploaded = e.optBoolean("isUploaded", false)    // NEW
)
```

- [ ] **Step 7: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add isUploaded field to Song model and cache"
```

---

### Task 2: Create CloudSyncManager

**Files:**
- Create: `app/src/main/java/fumi/day/literalmusi/data/git/CloudSyncManager.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitTransport.kt` (add `listRemoteFiles` method)

- [ ] **Step 1: Add `listRemoteFiles()` to GitTransport**

In `GitTransport.kt`, add a new suspend fun:

```kotlin
interface GitTransport {
    suspend fun ensureInitialized(token: String, repo: String)
    suspend fun pull(): PullResult
    suspend fun batchCommit(ops: List<Operation>): BatchResult
    val pileDir: File
    val trashDir: File
    fun remoteFileCount(): Int?
    suspend fun listRemoteFilenames(): List<String>    // NEW
    fun close()
}
```

- [ ] **Step 2: Implement `listRemoteFilenames()` in GitHubApiTransport**

In `GitHubApiTransport.kt`, add the implementation (reuses existing `listRemoteFiles()`):

```kotlin
override suspend fun listRemoteFilenames(): List<String> {
    return listRemoteFiles().keys.toList()
}

override suspend fun downloadFile(fileName: String, target: File) {
    doDownloadFile(fileName, target)
}
```

Also rename the existing private `downloadFile` method in `GitHubApiTransport.kt`:

```kotlin
// Change:
private fun downloadFile(fileName: String, target: File) {
// To:
private fun doDownloadFile(fileName: String, target: File) {
```

And update the call inside `pull()`:
```kotlin
// Change:
downloadFile(fileName, localFile)
// To:
doDownloadFile(fileName, localFile)
```

Refresh remoteFiles at the same time:

```kotlin
override suspend fun listRemoteFilenames(): List<String> {
    remoteFiles = listRemoteFiles()
    return remoteFiles.keys.toList()
}
```

- [ ] **Step 3: Create CloudSyncManager**

Create `app/src/main/java/fumi/day/literalmusi/data/git/CloudSyncManager.kt`:

```kotlin
package fumi.day.literalmusi.data.git

import fumi.day.literalmusi.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncManager @Inject constructor(
    private val gitTransport: GitTransport,
    private val userPreferences: UserPreferences
) {

    private var token: String = ""
    private var repo: String = ""

    suspend fun isConfigured(): Boolean {
        val prefs = userPreferences.userPrefs.first()
        return prefs.gitHubEnabled && prefs.gitHubToken.isNotBlank() && prefs.gitHubRepo.isNotBlank()
    }

    suspend fun listRemoteFilenames(): List<String> {
        val prefs = userPreferences.userPrefs.first()
        gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
        return gitTransport.listRemoteFilenames()
    }

    suspend fun uploadFiles(
        files: List<File>,
        onProgress: (fileName: String, index: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val prefs = userPreferences.userPrefs.first()
            gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
            val ops = files.map { file ->
                Operation(type = OpType.ADD, path = "pile/${file.name}")
            }
            val result = gitTransport.batchCommit(ops)
            if (result.committed) {
                result.errors.forEachIndexed { i, _ ->
                    onProgress(files[i].name, i + 1, files.size)
                }
                Result.success(files.map { it.name })
            } else {
                Result.failure(Exception(result.errors.joinToString(", ")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFiles(
        fileNames: List<String>,
        onProgress: (fileName: String, index: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val prefs = userPreferences.userPrefs.first()
            gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
            val pileDir = gitTransport.pileDir
            val downloaded = mutableListOf<String>()
            for ((i, fileName) in fileNames.withIndex()) {
                onProgress(fileName, i, fileNames.size)
                val target = File(pileDir, fileName)
                gitTransport.downloadFile(fileName, target)
                downloaded.add(fileName)
                onProgress(fileName, i + 1, fileNames.size)
            }
            Result.success(downloaded)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteRemoteFiles(
        fileNames: List<String>,
        onProgress: (fileName: String, index: Int, total: Int) -> Unit = { _, _, _ -> }
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val prefs = userPreferences.userPrefs.first()
            gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
            val ops = fileNames.map { fileName ->
                Operation(type = OpType.DELETE, path = "pile/$fileName")
            }
            val result = gitTransport.batchCommit(ops)
            if (result.committed) {
                fileNames.forEachIndexed { i, name ->
                    onProgress(name, i + 1, fileNames.size)
                }
                Result.success(fileNames)
            } else {
                Result.failure(Exception(result.errors.joinToString(", ")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

- [ ] **Step 4: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add CloudSyncManager and listRemoteFilenames to GitTransport"
```

---

### Task 3: Add `updateUploadState` to MusicRepository and implement

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepository.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt`

- [ ] **Step 1: Add `updateUploadState` to MusicRepository interface**

```kotlin
interface MusicRepository {
    fun observeAll(): Flow<List<Song>>
    suspend fun deleteSong(song: Song)
    suspend fun deleteSongWithCloud(song: Song)    // NEW
    fun refresh()
    suspend fun updateUploadState(fileNames: List<String>, uploaded: Boolean)    // NEW
    fun getPileDir(): File    // NEW
}
```

- [ ] **Step 2: Implement `updateUploadState` in MusicRepositoryImpl**

```kotlin
override suspend fun updateUploadState(fileNames: List<String>, uploaded: Boolean) {
    withContext(Dispatchers.IO) {
        val cache = readCache().toMutableMap()
        for (fileName in fileNames) {
            val relPath = "pile/$fileName"
            val entry = cache[relPath] ?: continue
            cache[relPath] = entry.copy(isUploaded = uploaded)
        }
        writeCache(cache)
        _refresh.tryEmit(Unit)
    }
}
```

- [ ] **Step 3: Implement `getPileDir`**

```kotlin
override fun getPileDir(): File = pileDir
```

- [ ] **Step 4: Implement `deleteSongWithCloud`**

```kotlin
override suspend fun deleteSongWithCloud(song: Song) {
    // Cloud deletion is handled by caller before this
    // This just does local deletion
    deleteSong(song)
}
```

Actually, let's keep it simpler. The ViewModel will handle cloud deletion first, then call regular `deleteSong`. So we don't need a separate method. Let's just remove that and keep only `updateUploadState` and `getPileDir`.

- [ ] **Step 5: Remove opLog/syncScheduler references from MusicRepositoryImpl**

Remove the import lines:
```kotlin
import fumi.day.literalmusi.data.git.OpLog
import fumi.day.literalmusi.data.git.OpType
import fumi.day.literalmusi.data.git.Operation
import fumi.day.literalmusi.data.git.SyncScheduler
```

Remove constructor params:
```kotlin
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitTransport: GitTransport,
    private val opLog: OpLog,        // REMOVE
    private val syncScheduler: SyncScheduler    // REMOVE
) : MusicRepository {
```

Remove calls in `addFilesToPile()`:
```kotlin
// Remove these lines:
opLog.append(Operation(
    type = OpType.ADD,
    path = "pile/$fileName"
))
syncScheduler.onOperationEnqueued()
```

Remove calls in `deleteSong()`:
```kotlin
// Remove these lines:
opLog.append(Operation(
    type = OpType.DELETE,
    path = "pile/${file.name}"
))
syncScheduler.onOperationEnqueued()
```

Leave the `_refresh.tryEmit(Unit)` calls. Leave the `gitTransport.trashDir` usage.

- [ ] **Step 6: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add updateUploadState and getPileDir to MusicRepository, remove OpLog/SyncScheduler"
```

---

### Task 4: Add `reconcileCloudStatus` and `setUploaded` to MusicListViewModel

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/list/MusicListViewModel.kt`

- [ ] **Step 1: Inject CloudSyncManager and add reconcile/upload methods**

```kotlin
@HiltViewModel
class MusicListViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val musicPlayer: MusicPlayer,
    private val cloudSyncManager: CloudSyncManager    // NEW
) : ViewModel() {
```

- [ ] **Step 2: Add reconcile function**

```kotlin
fun reconcileCloudStatus() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            if (!cloudSyncManager.isConfigured()) return@launch
            val remoteFiles = cloudSyncManager.listRemoteFilenames().toSet()
            val localFiles = musicRepository.getPileDir().listFiles()
                ?.filter { it.isFile }?.map { it.name }.orEmpty()
            val uploaded = localFiles.filter { it in remoteFiles }
            musicRepository.updateUploadState(uploaded, true)
            val notUploaded = localFiles.filter { it !in remoteFiles }
            musicRepository.updateUploadState(notUploaded, false)
        } catch (_: Exception) {
            // Silently fail — cloud status will be stale
        }
    }
}
```

- [ ] **Step 3: Add setUploaded helper**

```kotlin
fun markUploaded(fileNames: List<String>) {
        viewModelScope.launch {
            musicRepository.updateUploadState(fileNames, true)
        }
    }

    fun markNotUploaded(fileNames: List<String>) {
        viewModelScope.launch {
            musicRepository.updateUploadState(fileNames, false)
        }
    }
```

- [ ] **Step 4: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add cloud status reconcile to MusicListViewModel"
```

---

### Task 5: Update MusicListScreen with cloud icon and delete dialog changes

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/list/MusicListScreen.kt`

- [ ] **Step 1: Add LaunchedEffect to reconcile on screen entry**

Add after `viewModel` line at top of MusicListScreen:

```kotlin
val groupedSongs by viewModel.groupedSongs.collectAsState()
var deleteSong by remember { mutableStateOf<Song?>(null) }
var deleteFromCloud by remember { mutableStateOf(false) }    // NEW

// NEW: Reconcile cloud status when screen appears
LaunchedEffect(Unit) {
    viewModel.reconcileCloudStatus()
}
```

- [ ] **Step 2: Update delete dialog to show cloud option**

Replace the delete dialog section:

```kotlin
deleteSong?.let { song ->
    AlertDialog(
        onDismissRequest = {
            deleteSong = null
            deleteFromCloud = false
        },
        title = { Text("Delete Song") },
        text = {
            Column {
                Text("Delete \"${song.title}\"? The file will be moved to trash.")
                if (song.isUploaded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteFromCloud,
                            onCheckedChange = { deleteFromCloud = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Also delete from cloud", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val songToDelete = song
                val cloudDelete = deleteFromCloud
                deleteSong = null
                deleteFromCloud = false
                viewModel.deleteSong(songToDelete, cloudDelete)
            }) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                deleteSong = null
                deleteFromCloud = false
            }) {
                Text("Cancel")
            }
        }
    )
}
```

- [ ] **Step 3: Update deleteSong call in ViewModel**

Add a new deleteSong overload in MusicListViewModel:

```kotlin
fun deleteSong(song: Song, alsoDeleteFromCloud: Boolean = false) {
    viewModelScope.launch {
        if (alsoDeleteFromCloud) {
            try {
                val fileName = File(song.uri).name
                cloudSyncManager.deleteRemoteFiles(listOf(fileName))
                markNotUploaded(listOf(fileName))
            } catch (_: Exception) {
                // Continue with local deletion
            }
        }
        val current = musicPlayer.state.value.currentSong
        musicRepository.deleteSong(song)
        if (current?.id == song.id) {
            musicPlayer.stop()
        }
    }
}
```

- [ ] **Step 4: Remove old `deleteSong` (single param) and rename the new one accordingly**

Replace the existing `deleteSong`:

```kotlin
fun deleteSong(song: Song) {
    deleteSong(song, false)
}

fun deleteSong(song: Song, alsoDeleteFromCloud: Boolean) {
    viewModelScope.launch {
        if (alsoDeleteFromCloud) {
            try {
                val fileName = File(song.uri).name
                cloudSyncManager.deleteRemoteFiles(listOf(fileName))
            } catch (_: Exception) { }
        }
        val current = musicPlayer.state.value.currentSong
        musicRepository.deleteSong(song)
        if (current?.id == song.id) {
            musicPlayer.stop()
        }
    }
}
```

- [ ] **Step 5: Update SongItem to accept cloud status param and show cloud icon**

Modify SongItem signature:

```kotlin
@Composable
private fun SongItem(
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
```

The Song already has `isUploaded`. Add cloud icon to the Row, before the duration:

```kotlin
// After artist text Column and before Spacer(width = 8.dp)
Spacer(modifier = Modifier.width(8.dp))

// Cloud icon
Icon(
    imageVector = if (song.isUploaded) Icons.Filled.Cloud else Icons.Outlined.Cloud,
    contentDescription = if (song.isUploaded) "Uploaded to cloud" else "Not uploaded",
    modifier = Modifier.size(18.dp),
    tint = if (song.isUploaded) MaterialTheme.colorScheme.primary
           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
)

Spacer(modifier = Modifier.width(8.dp))
```

Add imports for Icons:
```kotlin
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.Cloud
```

- [ ] **Step 6: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add cloud icon to song list and cloud delete option"
```

---

### Task 6: Update SettingsViewModel with upload/download/cloud import logic

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Replace git-related injections with CloudSyncManager**

Current constructor:
```kotlin
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val syncManager: GitSyncManager,
    private val musicRepository: MusicRepository,
    private val syncScheduler: SyncScheduler,
    private val opLog: OpLog
) : ViewModel() {
```

Replace with:
```kotlin
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val cloudSyncManager: CloudSyncManager,
    private val musicRepository: MusicRepository,
    private val musicListViewModel: MusicListViewModel   // Actually, don't inject VM
) : ViewModel() {
```

Wait, injecting a ViewModel into another ViewModel is bad practice. Instead, let's pass what we need directly. The SettingsViewModel will manage its own upload/download state and call MusicRepository directly.

Let me revise:

```kotlin
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val cloudSyncManager: CloudSyncManager,
    private val musicRepository: MusicRepository
) : ViewModel() {
```

- [ ] **Step 2: Remove old sync flow references**

Remove:
```kotlin
val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
val lastSyncError: StateFlow<String?> = syncManager.syncError
val currentOperation: StateFlow<String?> = syncManager.currentOperation
val localFileCount: StateFlow<Int> = syncManager.localFileCount
val remoteFileCount: StateFlow<Int?> = syncManager.remoteFileCount
private val _syncResult = MutableStateFlow<SyncResult?>(null)
val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()
```

Remove `syncNow()`, `clearSyncResult()`, `clearLocalData()` related methods.

- [ ] **Step 3: Add new state flows**

```kotlin
private val _uploadProgress = MutableStateFlow(UploadProgress())
val uploadProgress: StateFlow<UploadProgress> = _uploadProgress.asStateFlow()

private val _downloadProgress = MutableStateFlow(DownloadProgress())
val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

private val _localFileCount = MutableStateFlow(0)
val localFileCount: StateFlow<Int> = _localFileCount.asStateFlow()

private val _remoteFileCount = MutableStateFlow<Int?>(null)
val remoteFileCount: StateFlow<Int?> = _remoteFileCount.asStateFlow()

private var uploadJob: Job? = null
private var downloadJob: Job? = null
```

- [ ] **Step 4: Add data classes for progress**

```kotlin
data class UploadProgress(
    val isUploading: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentFile: String = "",
    val errors: List<String> = emptyList()
)

data class DownloadProgress(
    val isDownloading: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentFile: String = "",
    val errors: List<String> = emptyList()
)
```

- [ ] **Step 5: Add methods to get local-only and cloud-only song lists**

```kotlin
fun getLocalOnlySongs(): List<Song> {
    val remoteFiles = runBlocking(Dispatchers.IO) {
        try { cloudSyncManager.listRemoteFilenames().toSet() }
        catch (_: Exception) { emptySet() }
    }
    return musicRepository.getPileDir().listFiles()
        ?.filter { it.isFile && isAudioFile(it.name) && it.name !in remoteFiles }
        ?.map { file ->
            Song(
                id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
                title = file.nameWithoutExtension,
                artist = "",
                album = "",
                duration = 0,
                uri = file.absolutePath,
                dataModified = file.lastModified(),
                isUploaded = false
            )
        }.orEmpty()
}
```

Wait, this is getting complicated. Let me think of a simpler approach.

The SettingsViewModel should:
1. Expose upload candidates (local files not on cloud) as state
2. Expose download candidates (cloud files not local) as state
3. Handle upload action with progress
4. Handle download action with progress
5. Handle import with optional cloud upload

Let me simplify:

```kotlin
private val _uploadCandidates = MutableStateFlow<List<Song>>(emptyList())
val uploadCandidates: StateFlow<List<Song>> = _uploadCandidates.asStateFlow()

private val _downloadCandidates = MutableStateFlow<List<CloudFile>>(emptyList())
val downloadCandidates: StateFlow<List<CloudFile>> = _downloadCandidates.asStateFlow()

data class CloudFile(
    val fileName: String,
    val title: String
)

fun refreshUploadCandidates() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            if (!cloudSyncManager.isConfigured()) {
                _uploadCandidates.value = emptyList()
                return@launch
            }
            val remoteFiles = cloudSyncManager.listRemoteFilenames().toSet()
            val localFiles = musicRepository.getPileDir().listFiles()
                ?.filter { it.isFile && isAudioFile(it.name) }.orEmpty()
            // Direct comparison: local files not in remote list
            val notUploaded = localFiles.filter { it.name !in remoteFiles }
            // Build Song-like objects for display
            _uploadCandidates.value = notUploaded.mapNotNull { file ->
                // Try to read metadata from cache first
                val songs = musicRepository.observeAll().first()
                songs.find { File(it.uri).name == file.name }
                    ?: Song(
                        id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
                        title = file.nameWithoutExtension,
                        artist = "",
                        album = "",
                        duration = 0,
                        uri = file.absolutePath,
                        dataModified = file.lastModified(),
                        isUploaded = false
                    )
            }
        } catch (_: Exception) {
            _uploadCandidates.value = emptyList()
        }
    }
}

fun refreshDownloadCandidates() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            if (!cloudSyncManager.isConfigured()) {
                _downloadCandidates.value = emptyList()
                return@launch
            }
            val remoteFiles = cloudSyncManager.listRemoteFilenames().toSet()
            val localNames = musicRepository.getPileDir().listFiles()
                ?.map { it.name }.orEmpty().toSet()
            _downloadCandidates.value = remoteFiles
                .filter { it !in localNames }
                .map { CloudFile(fileName = it, title = it.removeSuffix(it.substringAfterLast('.'))) }
        } catch (_: Exception) {
            _downloadCandidates.value = emptyList()
        }
    }
}
```

- [ ] **Step 6: Add upload/download actions**

```kotlin
fun uploadToCloud(fileNames: List<String>) {
    if (uploadJob?.isActive == true) return
    uploadJob = viewModelScope.launch(Dispatchers.IO) {
        _uploadProgress.value = UploadProgress(isUploading = true, total = fileNames.size)
        val files = fileNames.map { File(musicRepository.getPileDir(), it) }.filter { it.exists() }
        var completed = 0
        val errors = mutableListOf<String>()
        for ((i, file) in files.withIndex()) {
            if (!isActive) break
            _uploadProgress.value = _uploadProgress.value.copy(
                currentFile = file.name,
                completed = i,
                total = files.size
            )
            try {
                val result = cloudSyncManager.uploadFiles(listOf(file))
                if (result.isFailure) {
                    errors.add("${file.name}: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errors.add("${file.name}: ${e.message}")
            }
            completed++
        }
        musicRepository.updateUploadState(fileNames.take(completed), true)
        _uploadProgress.value = UploadProgress(errors = errors)
        refreshFileCounts()
    }
}

fun cancelUpload() {
    uploadJob?.cancel()
    _uploadProgress.value = UploadProgress()
}

fun downloadFromCloud(fileNames: List<String>) {
    if (downloadJob?.isActive == true) return
    downloadJob = viewModelScope.launch(Dispatchers.IO) {
        _downloadProgress.value = DownloadProgress(isDownloading = true, total = fileNames.size)
        var completed = 0
        val errors = mutableListOf<String>()
        val downloaded = mutableListOf<String>()
        val pileDir = musicRepository.getPileDir()
        for ((i, name) in fileNames.withIndex()) {
            if (!isActive) break
            _downloadProgress.value = _downloadProgress.value.copy(
                currentFile = name,
                completed = i,
                total = fileNames.size
            )
            // Skip if local file already exists (conflict)
            if (File(pileDir, name).exists()) {
                errors.add("$name: skipped (already exists locally)")
                completed++
                continue
            }
            try {
                val result = cloudSyncManager.downloadFiles(listOf(name))
                if (result.isSuccess) {
                    downloaded.add(name)
                } else {
                    errors.add("$name: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errors.add("$name: ${e.message}")
            }
            completed++
        }
        musicRepository.updateUploadState(downloaded, true)
        musicRepository.refresh()
        _downloadProgress.value = DownloadProgress(errors = errors)
        refreshFileCounts()
    }
}

fun cancelDownload() {
    downloadJob?.cancel()
    _downloadProgress.value = DownloadProgress()
}

private fun refreshFileCounts() {
    _localFileCount.value = musicRepository.getPileDir().listFiles()?.filter { it.isFile }?.size ?: 0
    viewModelScope.launch {
        try {
            _remoteFileCount.value = cloudSyncManager.listRemoteFilenames().size
        } catch (_: Exception) {
            _remoteFileCount.value = null
        }
    }
}
```

- [ ] **Step 7: Modify `importFromMediaStore` for cloud upload option**

Add a `alsoUploadToCloud` parameter:

```kotlin
fun importFromMediaStore(uris: List<String>, alsoUploadToCloud: Boolean = false) {
    viewModelScope.launch {
        _importProgress.value = ImportProgress(isImporting = true)
        val errors = mutableListOf<String>()
        var completed = 0
        val total = uris.size
        val importedFiles = mutableListOf<String>()

        withContext(Dispatchers.IO) {
            val pileDir = musicRepository.getPileDir()

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

                try {
                    srcFile.copyTo(destFile, overwrite = false)
                    importedFiles.add(fileName)
                } catch (e: Exception) {
                    errors.add("Failed to import $fileName: ${e.message}")
                }
                completed++
                _importProgress.value = ImportProgress(total, completed, errors.toList(), true)
            }
        }

        _importProgress.value = ImportProgress(total, completed, errors, false)
        musicRepository.refresh()

        // Optionally upload to cloud
        if (alsoUploadToCloud && importedFiles.isNotEmpty()) {
            uploadToCloud(importedFiles)
        }
    }
}
```

- [ ] **Step 8: Remove `getPileDir()` private method, use `musicRepository.getPileDir()`**

Remove the private `getPileDir()` method. Also remove imports for `OpLog`, `OpType`, `Operation`, `SyncResult`, `SyncScheduler`.

- [ ] **Step 9: Add `isAudioFile` helper**

```kotlin
private fun isAudioFile(name: String): Boolean {
    val audioExtensions = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")
    return name.substringAfterLast('.', "").lowercase() in audioExtensions
}
```

- [ ] **Step 10: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: update SettingsViewModel with upload/download/cloud import"
```

---

### Task 7: Update SettingsScreen UI with upload/download buttons and cloud import checkbox

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Update composable function with new state**

Replace the state collection at the top:

```kotlin
val userPrefs by viewModel.userPrefs.collectAsState()
val importProgress by viewModel.importProgress.collectAsState()
val showOverwriteConfirm by viewModel.showOverwriteConfirm.collectAsState()
val mediaStoreSongs by viewModel.mediaStoreSongs.collectAsState()
val uploadProgress by viewModel.uploadProgress.collectAsState()
val downloadProgress by viewModel.downloadProgress.collectAsState()
val uploadCandidates by viewModel.uploadCandidates.collectAsState()
val downloadCandidates by viewModel.downloadCandidates.collectAsState()
val localFileCount by viewModel.localFileCount.collectAsState()
val remoteFileCount by viewModel.remoteFileCount.collectAsState()
```

Remove old state:
```kotlin
val isSyncing by viewModel.isSyncing.collectAsState()
val syncResult by viewModel.syncResult.collectAsState()
val lastSyncError by viewModel.lastSyncError.collectAsState()
val currentOperation by viewModel.currentOperation.collectAsState()
```

- [ ] **Step 2: Add state variables for dialogs**

```kotlin
var showGitDialog by remember { mutableStateOf(false) }
var showMediaStorePicker by remember { mutableStateOf(false) }
var showUploadDialog by remember { mutableStateOf(false) }
var showDownloadDialog by remember { mutableStateOf(false) }
var showConflictDialog by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Replace the settings screen content layout**

Replace the Column content between `paddingValues)` and the "About" section:

```kotlin
ImportMusicCard(
    onImportFromMediaStore = {
        viewModel.loadMediaStoreSongs()
        showMediaStorePicker = true
    }
)

HorizontalDivider()

CloudSyncCard(
    userPrefs = userPrefs,
    gitHubEnabled = userPrefs.gitHubEnabled,
    isUploading = uploadProgress.isUploading,
    isDownloading = downloadProgress.isDownloading,
    onConnectClick = { showGitDialog = true },
    onEditClick = { showGitDialog = true },
    onDisconnectClick = viewModel::disconnectGitHub,
    onUploadClick = {
        viewModel.refreshUploadCandidates()
        showUploadDialog = true
    },
    onDownloadClick = {
        viewModel.refreshDownloadCandidates()
        showDownloadDialog = true
    }
)

HorizontalDivider()

SyncStatusCard(
    gitHubEnabled = userPrefs.gitHubEnabled,
    localFileCount = localFileCount,
    remoteFileCount = remoteFileCount
)
```

- [ ] **Step 4: Add CloudSyncCard composable**

```kotlin
@Composable
private fun CloudSyncCard(
    userPrefs: UserPrefs,
    gitHubEnabled: Boolean,
    isUploading: Boolean,
    isDownloading: Boolean,
    onConnectClick: () -> Unit,
    onEditClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onUploadClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Cloud Sync",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (!gitHubEnabled) {
                Button(
                    onClick = onConnectClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect GitHub")
                }
            } else {
                Text(
                    text = "GitHub",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = userPrefs.gitHubRepo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(onClick = onEditClick, modifier = Modifier.weight(1f)) {
                        Text("Edit")
                    }
                    OutlinedButton(onClick = onDisconnectClick, modifier = Modifier.weight(1f)) {
                        Text("Disconnect")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onUploadClick,
                        enabled = !isUploading && !isDownloading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(if (isUploading) "Uploading..." else "Upload to Cloud")
                    }

                    Button(
                        onClick = onDownloadClick,
                        enabled = !isUploading && !isDownloading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(if (isDownloading) "Downloading..." else "Download from Cloud")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Update SyncStatusCard to simplified version**

```kotlin
@Composable
private fun SyncStatusCard(
    gitHubEnabled: Boolean,
    localFileCount: Int,
    remoteFileCount: Int?
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Sync Status",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Local files: $localFileCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (remoteFileCount != null) {
                Text(
                    text = "Cloud files: $remoteFileCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (gitHubEnabled) {
                Text(
                    text = "Cloud files: --",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **Step 6: Add UploadDialog**

```kotlin
@Composable
private fun UploadDialog(
    candidates: List<Song>,
    onUpload: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload to Cloud") },
        text = {
            if (candidates.isEmpty()) {
                Text("All local songs are already uploaded to cloud.")
            } else {
                Column {
                    Text(
                        text = "${candidates.size} songs not yet uploaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (selected.size == candidates.size) emptySet()
                                        else candidates.map { it.uri }.toSet()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected.size == candidates.size,
                                    onCheckedChange = {
                                        selected = if (selected.size == candidates.size) emptySet()
                                        else candidates.map { it.uri }.toSet()
                                    }
                                )
                                Text("Select All", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(candidates, key = { it.uri }) { song ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (song.uri in selected) selected - song.uri
                                        else selected + song.uri
                                    }
                                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = song.uri in selected,
                                    onCheckedChange = {
                                        selected = if (song.uri in selected) selected - song.uri
                                        else selected + song.uri
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(song.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onUpload(selected.map { File(it).name })
                },
                enabled = selected.isNotEmpty()
            ) {
                Text("Upload (${selected.size})")
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

- [ ] **Step 7: Add DownloadDialog**

```kotlin
@Composable
private fun DownloadDialog(
    candidates: List<CloudFile>,
    onDownload: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download from Cloud") },
        text = {
            if (candidates.isEmpty()) {
                Text("No new songs available on cloud.")
            } else {
                Column {
                    Text(
                        text = "${candidates.size} songs available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (selected.size == candidates.size) emptySet()
                                        else candidates.map { it.fileName }.toSet()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected.size == candidates.size,
                                    onCheckedChange = {
                                        selected = if (selected.size == candidates.size) emptySet()
                                        else candidates.map { it.fileName }.toSet()
                                    }
                                )
                                Text("Select All", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(candidates, key = { it.fileName }) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (file.fileName in selected) selected - file.fileName
                                        else selected + file.fileName
                                    }
                                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = file.fileName in selected,
                                    onCheckedChange = {
                                        selected = if (file.fileName in selected) selected - file.fileName
                                        else selected + file.fileName
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(file.title, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDownload(selected.toList())
                },
                enabled = selected.isNotEmpty()
            ) {
                Text("Download (${selected.size})")
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

- [ ] **Step 8: Add Upload/Download progress dialogs**

```kotlin
// Upload progress dialog
if (uploadProgress.isUploading) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Uploading to Cloud") },
        text = {
            Column {
                Text("Uploading: ${uploadProgress.currentFile}")
                Spacer(modifier = Modifier.height(8.dp))
                if (uploadProgress.total > 0) {
                    Text("${uploadProgress.completed + 1} / ${uploadProgress.total}")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (uploadProgress.completed + 1).toFloat() / uploadProgress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.cancelUpload() }) {
                Text("Cancel")
            }
        }
    )
}

// Download progress dialog
if (downloadProgress.isDownloading) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Downloading from Cloud") },
        text = {
            Column {
                Text("Downloading: ${downloadProgress.currentFile}")
                Spacer(modifier = Modifier.height(8.dp))
                if (downloadProgress.total > 0) {
                    Text("${downloadProgress.completed + 1} / ${downloadProgress.total}")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (downloadProgress.completed + 1).toFloat() / downloadProgress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.cancelDownload() }) {
                Text("Cancel")
            }
        }
    )
}
```

- [ ] **Step 9: Add "Also upload to cloud" checkbox in MediaStorePickerDialog**

Add a state for the checkbox:

```kotlin
var alsoUploadToCloud by remember { mutableStateOf(false) }

// In the dialog confirm button:
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = alsoUploadToCloud,
            onCheckedChange = { alsoUploadToCloud = it }
        )
        Text("同时上传到云端", style = MaterialTheme.typography.bodySmall)
    }
    TextButton(
        onClick = { onImport(selected.toList(), alsoUploadToCloud) },
        enabled = selected.isNotEmpty()
    ) {
        Text("Import (${selected.size})")
    }
}
```

The `onImport` callback signature needs to change to include the cloud flag. Update the MediaStorePickerDialog parameter and usage:

```kotlin
@Composable
private fun MediaStorePickerDialog(
    songs: List<MediaStoreSong>,
    onImport: (List<String>, Boolean) -> Unit,    // CHANGED
    onDismiss: () -> Unit
)
```

And update the call site:

```kotlin
if (showMediaStorePicker && mediaStoreSongs.isNotEmpty()) {
    MediaStorePickerDialog(
        songs = mediaStoreSongs,
        onImport = { selectedUris, alsoUpload ->
            viewModel.importFromMediaStore(selectedUris, alsoUpload)
            showMediaStorePicker = false
        },
        onDismiss = { showMediaStorePicker = false }
    )
}
```

- [ ] **Step 10: Add dialog triggers for upload/download**

Add after the sync result dialog block and before showSyncStatusDialog:

```kotlin
if (showUploadDialog) {
    UploadDialog(
        candidates = uploadCandidates,
        onUpload = { fileNames ->
            viewModel.uploadToCloud(fileNames)
            showUploadDialog = false
        },
        onDismiss = { showUploadDialog = false }
    )
}

if (showDownloadDialog) {
    DownloadDialog(
        candidates = downloadCandidates,
        onDownload = { fileNames ->
            viewModel.downloadFromCloud(fileNames)
            showDownloadDialog = false
        },
        onDismiss = { showDownloadDialog = false }
    )
}
```

- [ ] **Step 11: Remove old sync-related dialog triggers**

Remove the old blocks:
```kotlin
// Remove these:
if (isSyncing) { ... }    // Syncing dialog
syncResult?.let { ... }   // Sync result dialog
if (showSyncStatusDialog) { SyncStatusDialog(...) }
```

- [ ] **Step 12: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat: update SettingsScreen with upload/download UI and cloud import option"
```

---

### Task 8: Remove deprecated auto-sync components

**Files:**
- Delete: `app/src/main/java/fumi/day/literalmusi/data/git/OpLog.kt`
- Delete: `app/src/main/java/fumi/day/literalmusi/data/git/SyncProcessor.kt`
- Delete: `app/src/main/java/fumi/day/literalmusi/data/git/SyncScheduler.kt`
- Delete: `app/src/main/java/fumi/day/literalmusi/data/git/GitSyncManager.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/di/AppModule.kt` (no change needed — remove nothing)
- Check: `PlaybackService.kt` and other files for remaining references

- [ ] **Step 1: Search for remaining references**

Run: `rg "OpLog|SyncProcessor|SyncScheduler|GitSyncManager" --include "*.kt" app/src/`

Expected: only references from deleted files or files already updated

- [ ] **Step 2: Delete the deprecated files**

```bash
rm app/src/main/java/fumi/day/literalmusi/data/git/OpLog.kt
rm app/src/main/java/fumi/day/literalmusi/data/git/SyncProcessor.kt
rm app/src/main/java/fumi/day/literalmusi/data/git/SyncScheduler.kt
rm app/src/main/java/fumi/day/literalmusi/data/git/GitSyncManager.kt
```

- [ ] **Step 3: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove deprecated OpLog, SyncProcessor, SyncScheduler, GitSyncManager"
```

---

### Task 9: Update DI module — bind CloudSyncManager

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/di/AppModule.kt`

- [ ] **Step 1: Add CloudSyncManager binding if needed**

`CloudSyncManager` has `@Inject` on constructor and `@Singleton`, so Hilt will auto-discover it. No binding needed. Verify AppModule needs no change.

- [ ] **Step 2: Run build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: no DI changes needed — CloudSyncManager auto-discovered by Hilt"
```

---

### Task 10: Remove SyncStatusDialog and unused composables

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Remove SyncStatusDialog composable and old GitSyncCard**

They are no longer referenced. Keep them for now (dead code) — the compiler will warn but not error. Actually remove them for cleanliness.

Remove `SyncStatusDialog` composable function.
Remove `SyncStatusCard` — replaced by simplified version.

Wait, we already replaced `SyncStatusCard`. Let me make sure the old `SyncStatusCard` with all its params is gone. The old one had params: `gitHubEnabled, isSyncing, currentOperation, lastSyncedAt, lastSyncError, localFileCount, remoteFileCount, onSyncNowClick, onClick`. The new one has: `gitHubEnabled, localFileCount, remoteFileCount`. Just rename.

- [ ] **Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: clean up unused SyncStatusDialog and old SyncStatusCard"
```

---

### Task 11: Final integration — verify full build

- [ ] **Step 1: Full clean build**

```bash
./gradlew clean assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify no dead import references**

```bash
rg "OpLog|SyncProcessor|SyncScheduler|GitSyncManager" app/src/ --include "*.kt"
```

Expected: no results

- [ ] **Step 3: Commit any remaining fixes**

```bash
git add -A
git commit -m "chore: final cleanup after manual cloud sync implementation"
```
