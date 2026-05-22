# OpLog Sync Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace stateless-diff sync with operation-log-based (OpLog) incremental sync

**Architecture:** Local mutations (ADD/DELETE) are recorded to a JSONL file (`oplog.jsonl`). On sync, the log is rotated to `oplog.pending`, processed as a batch commit via GitHub Git Data API, then either committed (success) or restored (failure). Download direction remains full-diff (unchanged).

**Tech Stack:** Kotlin, coroutines, GitHub REST API, org.json, Hilt DI

---

### Task 1: OpLog.kt

**Files:**
- Create: `app/src/main/java/fumi/day/literalmusi/data/git/OpLog.kt`

- [ ] **Step 1: Write OpLog class**

```kotlin
package fumi.day.literalmusi.data.git

import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class OpType { ADD, DELETE, RENAME, MODIFY }

data class Operation(
    val id: String = UUID.randomUUID().toString(),
    val type: OpType,
    val path: String,
    val oldPath: String? = null,
    val time: Long = System.currentTimeMillis()
)

@Singleton
class OpLog @Inject constructor() {
    private var oplogDir: File? = null

    private val oplogFile: File? get() = oplogDir?.let { File(it, "oplog.jsonl") }
    private val pendingFile: File? get() = oplogDir?.let { File(it, "oplog.pending") }

    fun init(dir: File) {
        oplogDir = dir
        dir.mkdirs()
        // Recover from previous crash: if pending exists but oplog doesn't, restore
        val pf = pendingFile
        val of = oplogFile
        if (pf != null && of != null && pf.exists() && !of.exists()) {
            pf.renameTo(of)
        }
    }

    @Synchronized
    fun append(op: Operation) {
        val file = oplogFile ?: return
        val json = JSONObject().apply {
            put("id", op.id)
            put("type", op.type.name)
            put("path", op.path)
            op.oldPath?.let { put("oldPath", it) }
            put("time", op.time)
        }
        file.appendText(json.toString() + "\n")
    }

    @Synchronized
    fun rotate(): List<Operation> {
        val file = oplogFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        val pend = pendingFile ?: return emptyList()
        pend.delete()
        file.renameTo(pend)
        return loadOperations(pend)
    }

    @Synchronized
    fun completeSync() {
        pendingFile?.delete()
    }

    @Synchronized
    fun failSync() {
        val file = oplogFile ?: return
        val pend = pendingFile ?: return
        if (!pend.exists()) return
        val pendingContent = pend.readText()
        val existingContent = if (file.exists()) file.readText() else ""
        file.writeText(pendingContent + existingContent)
        pend.delete()
    }

    @Synchronized
    fun pendingCount(): Int {
        val file = oplogFile ?: return 0
        if (!file.exists()) return 0
        return file.readLines().count { it.isNotBlank() }
    }

    private fun loadOperations(file: File): List<Operation> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try {
                val json = JSONObject(line)
                Operation(
                    id = json.getString("id"),
                    type = OpType.valueOf(json.getString("type")),
                    path = json.getString("path"),
                    oldPath = json.optString("oldPath", null),
                    time = json.optLong("time", System.currentTimeMillis())
                )
            } catch (_: Exception) { null }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/OpLog.kt
git commit -m "feat: add OpLog for tracking sync operations"
```

---

### Task 2: Update GitTransport interface

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitTransport.kt`

- [ ] **Step 1: Read current GitTransport.kt to confirm contents**

- [ ] **Step 2: Remove old methods, add batchCommit**

Replace entire content of `GitTransport.kt` with:

```kotlin
package fumi.day.literalmusi.data.git

import java.io.File

data class BatchResult(
    val committed: Boolean = false,
    val errors: List<String> = emptyList()
)

interface GitTransport {
    suspend fun ensureInitialized(token: String, repo: String)
    suspend fun pull(): PullResult
    suspend fun batchCommit(ops: List<Operation>): BatchResult
    val pileDir: File
    val trashDir: File
    fun close()
}

data class PullResult(
    val conflicts: List<String> = emptyList(),
    val filesDownloaded: Int = 0
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/GitTransport.kt
git commit -m "refactor: GitTransport removes stageAll/commit/push, adds batchCommit"
```

---

### Task 3: Implement batchCommit in GitHubApiTransport

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitHubApiTransport.kt`

- [ ] **Step 1: Read current GitHubApiTransport.kt to confirm contents**

- [ ] **Step 2: Replace old commit() with batchCommit()**

Keep all existing helper methods (`openConnection`, `checkResponse`, `readBody`, `listRemoteFiles`, `downloadFile`, `ensureRepoInitialized`, `createInitialCommitViaContents`, `createRef`, `createBlob`, `createTree`, `getRefSha`, `createCommit`, `updateRef`). Remove `stageAll()`, `commit()`, `push()`.

Replace `stageAll()`, `commit()`, `push()` with:

```kotlin
    override suspend fun batchCommit(ops: List<Operation>): BatchResult {
        if (ops.isEmpty()) return BatchResult(committed = true)

        val errors = mutableListOf<String>()
        val entries = mutableListOf<JSONObject>()

        // Build baseline tree from current remote files
        for ((name, info) in remoteFiles) {
            entries.add(JSONObject().apply {
                put("path", "pile/$name")
                put("mode", "100644")
                put("type", "blob")
                put("sha", info.sha)
            })
        }

        for (op in ops) {
            when (op.type) {
                OpType.ADD, OpType.MODIFY -> {
                    val name = op.path.removePrefix("pile/")
                    val file = File(pileDir, name)
                    if (!file.exists()) {
                        errors.add("${op.type} failed: ${op.path} not found locally")
                        continue
                    }
                    try {
                        val sha = createBlob(file)
                        entries.removeAll { it.getString("path") == op.path }
                        entries.add(JSONObject().apply {
                            put("path", op.path)
                            put("mode", "100644")
                            put("type", "blob")
                            put("sha", sha)
                        })
                    } catch (e: Exception) {
                        errors.add("${op.type} ${op.path} failed: ${e.message}")
                    }
                }
                OpType.DELETE -> {
                    entries.removeAll { it.getString("path") == op.path }
                }
                OpType.RENAME -> {
                    entries.removeAll { it.getString("path") == op.oldPath }
                    val name = op.path.removePrefix("pile/")
                    val file = File(pileDir, name)
                    if (!file.exists()) {
                        errors.add("RENAME failed: ${op.path} not found locally")
                        continue
                    }
                    try {
                        val sha = createBlob(file)
                        entries.add(JSONObject().apply {
                            put("path", op.path)
                            put("mode", "100644")
                            put("type", "blob")
                            put("sha", sha)
                        })
                    } catch (e: Exception) {
                        errors.add("RENAME ${op.path} failed: ${e.message}")
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            return BatchResult(committed = true, errors = errors)
        }

        try {
            ensureRepoInitialized()
            val treeSha = createTree(entries)
            val parentSha = getRefSha()
            val commitSha = createCommit("sync: ${ops.size} ops", treeSha, parentSha)
            updateRef(commitSha)
            remoteFiles = listRemoteFiles()
            return BatchResult(committed = true, errors = errors)
        } catch (e: Exception) {
            errors.add("${e.javaClass.simpleName}: ${e.message}")
            return BatchResult(committed = false, errors = errors)
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/GitHubApiTransport.kt
git commit -m "feat: implement batchCommit for operation-based sync"
```

---

### Task 4: Rewrite SyncProcessor to use OpLog

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/SyncProcessor.kt`

- [ ] **Step 1: Read current SyncProcessor.kt to confirm contents**

- [ ] **Step 2: Replace with OpLog-based implementation**

```kotlin
package fumi.day.literalmusi.data.git

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncProcessor @Inject constructor(
    private val gitTransport: GitTransport,
    private val opLog: OpLog
) {
    suspend fun process(): SyncResult {
        var uploaded = 0
        val errors = mutableListOf<String>()

        // Upload: process pending operations
        val ops = opLog.rotate()
        if (ops.isNotEmpty()) {
            val result = gitTransport.batchCommit(ops)
            if (result.committed) {
                uploaded = ops.size
                opLog.completeSync()
            } else {
                opLog.failSync()
            }
            errors.addAll(result.errors)
        }

        // Download: pull remote changes (unchanged)
        val pullResult = try {
            gitTransport.pull()
        } catch (e: Exception) {
            PullResult(filesDownloaded = 0)
        }

        errors.addAll(pullResult.conflicts)

        return SyncResult(
            uploaded = uploaded,
            downloaded = pullResult.filesDownloaded,
            errors = errors
        )
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/SyncProcessor.kt
git commit -m "refactor: SyncProcessor uses OpLog for upload, keeps pull unchanged"
```

---

### Task 5: Update GitSyncManager

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitSyncManager.kt`

- [ ] **Step 1: Read current GitSyncManager.kt to confirm contents**

- [ ] **Step 2: Inject OpLog, remove SHA tracking, update syncAndAwait**

```kotlin
package fumi.day.literalmusi.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fumi.day.literalmusi.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val errors: List<String> = emptyList()
)

@Singleton
class GitSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitTransport: GitTransport,
    private val syncProcessor: SyncProcessor,
    private val syncScheduler: SyncScheduler,
    private val userPreferences: UserPreferences,
    private val opLog: OpLog
) {
    val isSyncing: StateFlow<Boolean> = syncScheduler.isSyncing

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    init {
        opLog.init(File(context.filesDir, "oplog"))
        syncScheduler.setSyncCallback {
            syncAndAwait()
        }
    }

    fun launchSync() {
        syncScheduler.triggerSync()
    }

    suspend fun syncAndAwait(): SyncResult? {
        if (isSyncing.value) return null
        return withContext(Dispatchers.IO) {
            val prefs = userPreferences.userPrefs.first()
            if (!prefs.gitHubEnabled || prefs.gitHubToken.isBlank() || prefs.gitHubRepo.isBlank()) {
                return@withContext null
            }
            _syncError.value = null
            try {
                gitTransport.ensureInitialized(prefs.gitHubToken, prefs.gitHubRepo)
                val result = syncProcessor.process()
                if (result.errors.isNotEmpty()) {
                    _syncError.value = result.errors.first()
                }
                userPreferences.setLastSyncedAt(System.currentTimeMillis())
                result
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message ?: "Sync failed"}"
                _syncError.value = msg
                SyncResult(errors = listOf(msg))
            }
        }
    }

    suspend fun mergeAndAwait(): SyncResult? {
        return syncAndAwait()
    }

    fun clearLocalData() {
        gitTransport.close()
        val repoDir = File(context.filesDir, "repo")
        repoDir.deleteRecursively()
        opLog.init(File(context.filesDir, "oplog"))
    }
}
```

- [ ] **Step 3: Remove `resetSyncState()` from UserPreferences if it exists** (it won't be needed anymore since SHA tracking is gone). Check the file first.

```bash
# Check if resetSyncState and setLastSyncedShas exist
rg "resetSyncState|setLastSyncedShas" app/src/main/java/fumi/day/literalmusi/data/prefs/UserPreferences.kt
```

If they exist, remove them or mark as no-op.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/GitSyncManager.kt
git commit -m "refactor: GitSyncManager uses OpLog, removes SHA tracking"
```

---

### Task 6: Inject OpLog into MusicRepositoryImpl and enqueue operations

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt`

- [ ] **Step 1: Read current MusicRepositoryImpl.kt to confirm contents**

- [ ] **Step 2: Add OpLog and SyncScheduler injection, enqueue ADD on addFilesToPile, enqueue DELETE on deleteSong**

```kotlin
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
        return errors
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
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt
git commit -m "feat: enqueue ADD/DELETE ops in MusicRepositoryImpl on mutations"
```

---

### Task 7: Enqueue ADD in SettingsViewModel after import

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Read current SettingsViewModel.kt to confirm contents**

- [ ] **Step 2: Inject OpLog and enqueue ADD in importFromMediaStore**

Add imports:
```kotlin
import fumi.day.literalmusi.data.git.OpLog
import fumi.day.literalmusi.data.git.OpType
import fumi.day.literalmusi.data.git.Operation
```

Add `opLog: OpLog` to constructor:
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

Inside `importFromMediaStore`, after `srcFile.copyTo(destFile, overwrite = false)` succeeds and before `syncScheduler.onOperationEnqueued()`:
```kotlin
                    try {
                        srcFile.copyTo(destFile, overwrite = false)
                        opLog.append(Operation(
                            type = OpType.ADD,
                            path = "pile/$fileName"
                        ))
                        syncScheduler.onOperationEnqueued()
                    } catch (e: Exception) {
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsViewModel.kt
git commit -m "feat: enqueue ADD op in SettingsViewModel after media import"
```

---

### Task 8: Clean up UserPreferences (remove SHA tracking)

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/prefs/UserPreferences.kt`

- [ ] **Step 1: Read current UserPreferences.kt to confirm contents**

- [ ] **Step 2: Remove `lastSyncedShas` from `UserPrefs`, its key, JSON parsing, and `setLastSyncedShas` method**

Changes to `UserPrefs` data class:
```kotlin
data class UserPrefs(
    val gitHubEnabled: Boolean = false,
    val gitHubToken: String = "",
    val gitHubRepo: String = "",
    val lastSyncedAt: Long? = null
)
```

Remove from `Keys`:
```kotlin
val LAST_SYNCED_SHAS = stringPreferencesKey("last_synced_shas")
```

Remove SHA parsing from `userPrefs`:
```kotlin
val userPrefs: Flow<UserPrefs> = combine(
    context.dataStore.data,
    _gitHubToken
) { prefs, token ->
    UserPrefs(
        gitHubEnabled = prefs[Keys.GITHUB_ENABLED] ?: false,
        gitHubToken = token,
        gitHubRepo = prefs[Keys.GITHUB_REPO] ?: "",
        lastSyncedAt = prefs[Keys.LAST_SYNCED_AT]
    )
}
```

Remove `setLastSyncedShas` method entirely.

Keep `resetSyncState()` — it still clears `lastSyncedAt` which is useful when changing repos:
```kotlin
suspend fun resetSyncState() {
    context.dataStore.edit { prefs ->
        prefs.remove(Keys.LAST_SYNCED_AT)
    }
}
```

Also remove `LAST_SYNCED_SHAS` import usage — `JSONObject` may no longer be needed unless used elsewhere in the file. Check and clean up unused imports.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/prefs/UserPreferences.kt
git commit -m "chore: remove unused SHA tracking from UserPreferences"
```

---

### Task 9: Verify and fix build

- [ ] **Step 1: Run build**

```bash
./gradlew assembleDebug 2>&1 | tail -50
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation errors**

Common issues:
- SyncResult import in GitSyncManager (already defined in same package, no import needed)
- OpLog injection: Hilt will auto-resolve since OpLog is @Singleton with @Inject constructor
- MusicRepositoryImpl now needs OpLog and SyncScheduler in constructor — Hilt resolves both
- SettingsViewModel now needs OpLog in constructor — Hilt resolves it

- [ ] **Step 3: Commit any build fixes**

```bash
git commit -am "fix: resolve build errors after OpLog sync refactor"
```
