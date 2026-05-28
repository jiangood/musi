# Background Sync Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Make upload/download non-blocking with a top-bar status icon and a progress panel showing speed/ETA.

**Architecture:** A singleton `SyncStateHolder` holds `StateFlow<SyncState>` with bytes-tracked progress. `GitHubApiTransport` reports per-byte progress via callbacks. `MusicListScreen` observes the state for the status icon + bottom sheet. `SettingsViewModel` delegates progress to `SyncStateHolder`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Material 3

---

### Task 1: Create SyncState data model and SyncStateHolder

**Files:**
- Create: `app/src/main/java/fumi/day/literalmusi/data/sync/SyncState.kt`
- Create: `app/src/main/java/fumi/day/literalmusi/data/sync/SyncStateHolder.kt`
- Test: (manual via build)

- [ ] **Step 1: Create SyncState.kt**

```kotlin
package fumi.day.literalmusi.data.sync

enum class SyncDirection { IDLE, UPLOAD, DOWNLOAD }

data class SyncState(
    val isActive: Boolean = false,
    val direction: SyncDirection = SyncDirection.IDLE,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val currentFileName: String = "",
    val totalBytes: Long = 0L,
    val transferredBytes: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val errors: List<String> = emptyList()
) {
    val progress: Float
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes) else 0f

    val formattedSpeed: String
        get() = when {
            speedBytesPerSec >= 1_000_000 -> "%.1f MB/s".format(speedBytesPerSec / 1_000_000)
            speedBytesPerSec >= 1_000 -> "%.0f KB/s".format(speedBytesPerSec / 1_000)
            else -> "0 B/s"
        }

    val etaSeconds: Long?
        get() = if (speedBytesPerSec > 0 && transferredBytes < totalBytes)
            ((totalBytes - transferredBytes) / speedBytesPerSec).toLong() else null

    val formattedEta: String
        get() = etaSeconds?.let { sec ->
            if (sec > 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
        } ?: "--"
}
```

- [ ] **Step 2: Create SyncStateHolder.kt**

```kotlin
package fumi.day.literalmusi.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private var startTime = 0L

    fun start(direction: SyncDirection, fileNames: List<String>, fileSizes: List<Long>) {
        _state.value = SyncState(
            isActive = true,
            direction = direction,
            totalFiles = fileNames.size,
            totalBytes = fileSizes.sum(),
            currentFileName = fileNames.firstOrNull() ?: ""
        )
        startTime = System.currentTimeMillis()
    }

    fun reportCurrentFile(fileName: String) {
        _state.value = _state.value.copy(currentFileName = fileName)
    }

    fun reportBytes(transferred: Long, total: Long) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val speed = if (elapsed > 0) transferred / elapsed else 0.0
        _state.value = _state.value.copy(
            transferredBytes = transferred,
            totalBytes = total,
            speedBytesPerSec = speed
        )
    }

    fun completeFile() {
        _state.value = _state.value.copy(
            completedFiles = _state.value.completedFiles + 1
        )
    }

    fun finish(errors: List<String> = emptyList()) {
        _state.value = SyncState(errors = errors)
    }

    fun cancel() {
        _state.value = SyncState()
    }
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 2: Instrument GitHubApiTransport with byte-tracking callbacks

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitHubApiTransport.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitTransport.kt`

- [ ] **Step 1: Add progress callback to GitTransport.downloadFile**

```kotlin
// GitTransport.kt - modify downloadFile signature
suspend fun downloadFile(fileName: String, target: File, onBytesTransferred: (Long) -> Unit = {})
```

- [ ] **Step 2: Add byte tracking to doDownloadFile in GitHubApiTransport**

Change `doDownloadFile`:
```kotlin
private fun doDownloadFile(fileName: String, target: File, onBytesTransferred: (Long) -> Unit = {}) {
    val conn = openConnection(repoApi("/contents/pile/$fileName"))
    conn.setRequestProperty("Accept", "application/vnd.github.v3.raw")
    checkResponse(conn)
    target.outputStream().use { output ->
        conn.inputStream.copyTo(output, bufferSize = 8192) { bytesSoFar ->
            onBytesTransferred(bytesSoFar)
        }
    }
}
```

Also update `downloadFile` override:
```kotlin
override suspend fun downloadFile(fileName: String, target: File, onBytesTransferred: (Long) -> Unit) {
    doDownloadFile(fileName, target, onBytesTransferred)
}
```

Note: `copyTo` doesn't have a built-in progress callback in its standard form. We need to write a manual copy loop:

```kotlin
private fun doDownloadFile(fileName: String, target: File, onBytesTransferred: (Long) -> Unit) {
    val conn = openConnection(repoApi("/contents/pile/$fileName"))
    conn.setRequestProperty("Accept", "application/vnd.github.v3.raw")
    checkResponse(conn)
    val buf = ByteArray(8192)
    target.outputStream().use { output ->
        conn.inputStream.use { input ->
            var total = 0L
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
                total += read
                onBytesTransferred(total)
            }
        }
    }
}
```

- [ ] **Step 3: Add byte tracking to createBlob**

```kotlin
private fun createBlob(file: File, onBytesTransferred: (Long) -> Unit = {}): String {
    val conn = openConnection(repoApi("/git/blobs"), "POST").apply {
        connectTimeout = 30000
        readTimeout = 120000
    }
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    val os = conn.outputStream
    os.write("{\"content\":\"".toByteArray())
    val encoder = Base64.getEncoder()
    val buf = ByteArray(3 * 4096)
    var totalBytes = 0L
    file.inputStream().use { input ->
        var bytesRead: Int
        while (input.read(buf).also { bytesRead = it } != -1) {
            val encoded = encoder.encode(buf.copyOfRange(0, bytesRead))
            os.write(encoded)
            totalBytes += bytesRead
            onBytesTransferred(totalBytes)
        }
    }
    os.write("\",\"encoding\":\"base64\"}".toByteArray())
    os.flush()
    checkResponse(conn)
    return JSONObject(readBody(conn)).getString("sha")
}
```

- [ ] **Step 4: Update batchCommit to pass callback through**

In `batchCommit`, change the `createBlob(file)` calls to `createBlob(file) { ... }`. Since batchCommit processes all files in one HTTP request (tree + commit), we'll track per-file bytes in the callback. Implementation:

Inside the `OpType.ADD, OpType.MODIFY` branch:
```kotlin
val sha = createBlob(file) { bytes ->
    // batchCommit handles files sequentially, so we track cumulative
}
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 3: Update CloudSyncManager to use SyncStateHolder

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/CloudSyncManager.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitTransport.kt`

- [ ] **Step 1: Inject SyncStateHolder into CloudSyncManager**

```kotlin
class CloudSyncManager @Inject constructor(
    private val gitTransport: GitTransport,
    private val userPreferences: UserPreferences,
    private val syncStateHolder: SyncStateHolder
)
```

- [ ] **Step 2: Update uploadFiles to report progress**

```kotlin
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
        val fileSizes = files.map { it.length() }
        syncStateHolder.start(SyncDirection.UPLOAD, files.map { it.name }, fileSizes)
        val result = gitTransport.batchCommit(ops)
        if (result.committed) {
            files.forEachIndexed { i, file ->
                onProgress(file.name, i + 1, files.size)
                syncStateHolder.completeFile()
            }
            syncStateHolder.finish()
            Result.success(files.map { it.name })
        } else {
            syncStateHolder.finish(result.errors)
            Result.failure(Exception(result.errors.joinToString(", ")))
        }
    } catch (e: Exception) {
        syncStateHolder.finish(listOf(e.message ?: "Unknown error"))
        Result.failure(e)
    }
}
```

Wait, but `batchCommit` handles all files in one tree. We need to track bytes within the batch commit. This is tricky because batchCommit creates blobs internally. Let me think about this...

Actually, the current code uploads files one at a time (SettingsViewModel loops calling `uploadFiles(listOf(file))`). So each call to `uploadFiles` handles ONE file. That means the progress tracking at this level is per-file. We should track bytes at a coarser level here.

For single-file uploads:
- We know the file size before starting
- We can start the SyncState with the file size
- We need createBlob to report bytes

For the batchCommit case with multiple files:
- We know all file sizes
- createBlob is called for each file
- We can report bytes as each blob is created

Let me reconsider. The current code calls `batchCommit` with one operation at a time (from SettingsViewModel loop). So batchCommit handles one file per call. The createBlob inside batchCommit is for that single file.

Wait, no: `uploadFiles(listOf(file))` passes one file, but batchCommit still does the full tree creation + commit for that one file. The createBlob is called for that one file's blob.

OK so for the single-file case, the progress is just:
1. Start with totalBytes = file.length()
2. createBlob reports bytes as it reads the file
3. After createBlob + tree + commit, complete the file

For the SettingsViewModel loop case (multiple files), each call to uploadFiles handles one file, and we accumulate progress in SyncStateHolder.

Actually, I realize the simpler approach: let me handle all progress tracking in the SettingsViewModel sync methods, not in CloudSyncManager. This way I don't need to change CloudSyncManager's interface much.

Let me reconsider the architecture:

1. `SyncStateHolder` - global state
2. `SettingsViewModel.uploadToCloud()` is refactored to:
   - Calculate total bytes from all file sizes upfront
   - Call `syncStateHolder.start(UPLOAD, fileNames, fileSizes)` 
   - Loop through files and track bytes via createBlob's callback
   - Call `syncStateHolder.finish()` when done
3. `SettingsViewModel.downloadFromCloud()` similarly

But this requires the ViewModel to have access to the createBlob/downloadFile byte callbacks, which it currently doesn't - it calls through CloudSyncManager.

Let me simplify: I'll modify CloudSyncManager to accept a progress lambda that reports cumulative bytes, and SettingsViewModel wires it to SyncStateHolder.

Actually, let me take the simplest approach that works:

1. SyncStateHolder tracks file-level and byte-level progress
2. Before starting, SettingsViewModel calculates total bytes from file sizes
3. CloudSyncManager.uploadFiles/downloadFiles accept a callback for (fileName, bytesTransferredSoFar, totalBytesForThisFile)
4. SettingsViewModel wires this to SyncStateHolder

Let me just modify the existing onProgress callback to include bytes.

Actually, the simplest approach is:
1. Before upload, figure out all file sizes 
2. Track progress at file boundaries within the SettingsViewModel loop
3. Instrument createBlob and doDownloadFile to report bytes to SyncStateHolder

Let me just do it step by step. The key insight: I'll modify CloudSyncManager to pass byte info, and have the transport layer report bytes.

Let me simplify even further: I'll add byte tracking to CloudSyncManager without changing the transport. For uploads, we know the local file size. For downloads, the remote file info has size. We report progress at file boundaries. Speed is calculated from total bytes / elapsed time.

This is much simpler and still gives a good speed approximation.

OK let me just revise my approach for the plan:

CloudSyncManager changes:
- Inject SyncStateHolder
- uploadFiles: before upload, calculate total bytes, call syncStateHolder.start(). After each file, call syncStateHolder.reportBytes(cumulativeBytes) and completeFile()
- downloadFiles: same approach

SettingsViewModel changes:
- Remove _uploadProgress, _downloadProgress state
- Remove cancelUpload/cancelDownload
- Add cancelSync that calls syncStateHolder.cancel()
- Add syncStateHolder.cancel() check in the loop

Actually wait, the user said they want speed. If we only report at file boundaries, for large files (like FLAC files > 20MB) the speed would be accurate enough if we track time. But for small MP3s (3-5MB) it would show nothing until the file completes, then show the average speed for that file. That's actually fine - it's still useful.

Let me go with this simpler approach: byte tracking at per-file granularity. Report transferred bytes cumulative when a file completes.

Actually, I want to do it properly. Let me instrument both createBlob and downloadFile with progress callbacks. It's not that much code and gives smooth progress.

Let me revise the plan:

CloudSyncManager:
- Inject SyncStateHolder
- uploadFiles: starts syncStateHolder, then for each file calls batchCommit. batchCommit internally calls createBlob which reports bytes. After batchCommit, completeFile.
- downloadFiles: same pattern, downloadFile reports bytes

The tricky part is that batchCommit calls createBlob internally, and we need to wire the callback through. The simplest way is to add an optional callback to batchCommit.

Let me just write the plan out properly now.

Actually, I think I'm overthinking this. Let me take a pragmatic approach:

1. Create SyncStateHolder 
2. In SettingsViewModel's uploadToCloud/downloadFromCloud, calculate total bytes upfront
3. Have the CloudSyncManager's onProgress callback report byte-level progress (which we can track at file granularity)
4. For byte-level granularity within a file, instrument createBlob and downloadFile

For the plan, let me just write the implementation tasks concisely.

Let me keep it simpler - write a shorter plan and just implement.<｜end▁of▁thinking｜>

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="todowrite">
<｜｜DSML｜｜parameter name="todos" string="false">[{"content": "Create SyncState data model and SyncStateHolder singleton", "status": "in_progress", "priority": "high"}, {"content": "Instrument GitHubApiTransport with byte-tracking callbacks", "status": "pending", "priority": "high"}, {"content": "Update CloudSyncManager to use SyncStateHolder", "status": "pending", "priority": "high"}, {"content": "Add status icon + progress bottom sheet to MusicListScreen", "status": "pending", "priority": "high"}, {"content": "Refactor SettingsViewModel/SettingsScreen to use SyncStateHolder", "status": "pending", "priority": "high"}, {"content": "Update DI module if needed", "status": "pending", "priority": "medium"}, {"content": "Build and verify", "status": "pending", "priority": "high"}]