# OpLog Sync Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace full-diff stateless sync with operation-log-based sync (ADD/DELETE/RENAME/MODIFY operations appended to a JSONL file, processed in order on sync).

**Architecture:** New `OpLog`, `SyncProcessor`, `SyncScheduler` handle the operation log lifecycle. `GitSyncManager` orchestrates: upload via `OpLog → SyncProcessor → batchCommit`, download via existing `pull()`. Operations are only enqueued at explicit user-action points (import flows), not during sync.

**Tech Stack:** Kotlin, coroutines, GitHub REST API, JSON (org.json), Hilt DI

---

### Task 1: OpLog.kt

**Files:**
- Create: `app/src/main/java/fumi/day/literalmusi/data/git/OpLog.kt`

- [ ] **Step 1: Write OpLog class**

```kotlin
package fumi.day.literalmusi.data.git

import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class OpType { ADD, DELETE, RENAME, MODIFY }

data class Operation(
    val id: String,
    val type: OpType,
    val path: String,
    val oldPath: String? = null,
    val time: Long = System.currentTimeMillis()
)

@Singleton
class OpLog @Inject constructor() {
    private var oplogFile: File? = null
    private var pendingFile: File? = null

    fun init(repoDir: File) {
        oplogFile = File(repoDir, "oplog.jsonl")
        pendingFile = File(repoDir, "oplog.pending")
    }

    @Synchronized
    fun append(op: Operation) {
        val file = oplogFile ?: return
        file.parentFile?.mkdirs()
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

### Task 2: batchCommit to GitTransport + GitHubApiTransport

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitTransport.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitHubApiTransport.kt`

- [ ] **Step 1: Add BatchResult and batchCommit to interface**

Edit `GitTransport.kt` — add after `PullResult`:

```kotlin
data class BatchResult(
    val committed: Boolean = false,
    val errors: List<String> = emptyList()
)
```

Add to `GitTransport` interface:

```kotlin
    suspend fun batchCommit(ops: List<Operation>): BatchResult
```

- [ ] **Step 2: Ensure GitHubApiTransport has `withContext(Dispatchers.IO)` import**

The class already imports `Dispatchers` from existing code. No change needed.

- [ ] **Step 3: Implement batchCommit in GitHubApiTransport**

Add to `GitHubApiTransport` class:

```kotlin
    override suspend fun batchCommit(ops: List<Operation>): BatchResult {
        if (ops.isEmpty()) return BatchResult(committed = true)
        val currentFiles = remoteFiles
        val entries = mutableListOf<JSONObject>()
        for ((name, info) in currentFiles) {
            entries.add(JSONObject().apply {
                put("path", "pile/$name")
                put("mode", "100644")
                put("type", "blob")
                put("sha", info.sha)
            })
        }
        val errors = mutableListOf<String>()
        for (op in ops) {
            when (op.type) {
                OpType.ADD, OpType.MODIFY -> {
                    val name = op.path.removePrefix("pile/")
                    val file = File(pileDir, name)
                    if (!file.exists()) {
                        errors.add("${op.type} failed: ${op.path} not found")
                        continue
                    }
                    val sha = createBlob(file.readBytes())
                    entries.removeAll { it.getString("path") == op.path }
                    entries.add(JSONObject().apply {
                        put("path", op.path)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", sha)
                    })
                }
                OpType.DELETE -> {
                    entries.removeAll { it.getString("path") == op.path }
                }
                OpType.RENAME -> {
                    entries.removeAll { it.getString("path") == op.oldPath }
                    val name = op.path.removePrefix("pile/")
                    val file = File(pileDir, name)
                    if (!file.exists()) {
                        errors.add("RENAME failed: ${op.path} not found")
                        continue
                    }
                    val sha = createBlob(file.readBytes())
                    entries.add(JSONObject().apply {
                        put("path", op.path)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", sha)
                    })
                }
            }
        }
        try {
            val treeSha = createTree(entries)
            val parentSha = getRefSha()
            val commitSha = createCommit("sync: ${ops.size} ops", treeSha, parentSha)
            updateRef(commitSha)
            remoteFiles = listRemoteFiles()
            return BatchResult(committed = true, errors = errors)
        } catch (e: Exception) {
            return BatchResult(committed = false, errors = listOf("${e.javaClass.simpleName}: ${e.message}"))
        }
    }
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/GitTransport.kt app/src/main/java/fumi/day/literalmusi/data/git/GitHubApiTransport.kt
git commit -m "feat: add batchCommit to GitTransport for operation-based sync"
```

---

### Task 3: SyncProcessor.kt

**Files:**
- Create: `app/src/main/java/fumi/day/literalmusi/data/git/SyncProcessor.kt`

- [ ] **Step 1: Write SyncProcessor**

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
        val ops = opLog.rotate()
        var uploaded = 0
        var errors = emptyList<String>()

        if (ops.isNotEmpty()) {
            val batchResult = gitTransport.batchCommit(ops)
            if (batchResult.committed) {
                uploaded = ops.size
                opLog.completeSync()
            } else {
                opLog.failSync()
            }
            errors = batchResult.errors
        }

        val pullResult = try {
            gitTransport.pull()
        } catch (e: Exception) {
            PullResult(filesDownloaded = 0)
        }

        return SyncResult(
            uploaded = uploaded,
            downloaded = pullResult.filesDownloaded,
            errors = errors + pullResult.conflicts
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/SyncProcessor.kt
git commit -m "feat: add SyncProcessor for operation-based sync execution"
```

---

### Task 4: SyncScheduler.kt

**Files:**
- Create: `app/src/main/java/fumi/day/literalmusi/data/git/SyncScheduler.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/MainActivity.kt`

- [ ] **Step 1: Write SyncScheduler**

```kotlin
package fumi.day.literalmusi.data.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    private var syncCallback: (suspend () -> Unit)? = null

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun setSyncCallback(callback: suspend () -> Unit) {
        syncCallback = callback
    }

    @Synchronized
    fun onOperationEnqueued() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(3000)
            runSync()
        }
    }

    @Synchronized
    fun triggerSync() {
        debounceJob?.cancel()
        debounceJob = null
        runSync()
    }

    fun onResume() {
        triggerSync()
    }

    private fun runSync() {
        if (_isSyncing.value) return
        scope.launch {
            _isSyncing.value = true
            try {
                syncCallback?.invoke()
            } finally {
                _isSyncing.value = false
            }
        }
    }
}
```

- [ ] **Step 2: Wire SyncScheduler in GitSyncManager**

Read current `GitSyncManager.kt` contents before editing.

In `GitSyncManager` constructor, add `initOpLog()` and wire scheduler:

```kotlin
    init {
        val repoDir = File(context.filesDir, "repo")
        opLog.init(repoDir)
        syncScheduler.setSyncCallback {
            syncAndAwait()
        }
    }
```

- [ ] **Step 3: Wire onResume in MainActivity**

Edit `MainActivity.kt` to pass lifecycle events to SyncScheduler:

```kotlin
// Add imports:
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fumi.day.literalmusi.data.git.SyncScheduler

class MainActivity : ComponentActivity() {
    @Inject lateinit var syncScheduler: SyncScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... existing code ...

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncScheduler.onResume()
            }
        })

        // ... setContent ...
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/SyncScheduler.kt app/src/main/java/fumi/day/literalmusi/MainActivity.kt
git commit -m "feat: add SyncScheduler with debounce and lifecycle triggers"
```

---

### Task 5: Refactor GitSyncManager.kt

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/git/GitSyncManager.kt`

- [ ] **Step 1: Read current GitSyncManager**

Read the file to confirm current contents.

- [ ] **Step 2: Rewrite GitSyncManager to inject new deps and delegate**

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
    private val userPreferences: UserPreferences
) {
    val isSyncing: StateFlow<Boolean> = syncScheduler.isSyncing

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    init {
        val repoDir = File(context.filesDir, "repo")
        opLog.init(repoDir)
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
        opLog.init(repoDir)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/git/GitSyncManager.kt
git commit -m "refactor: GitSyncManager uses OpLog + SyncProcessor for queue-based sync"
```

---

### Task 6: Enqueue ADD operations at import points

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt`

- [ ] **Step 1: Inject OpLog into SettingsViewModel**

```kotlin
// Add to constructor:
import fumi.day.literalmusi.data.git.OpLog
import fumi.day.literalmusi.data.git.Operation
import fumi.day.literalmusi.data.git.OpType
import java.util.UUID

class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val syncManager: GitSyncManager,
    private val musicRepository: MusicRepository,
    private val opLog: OpLog
) : ViewModel() {
```

- [ ] **Step 2: Enqueue ADD after importFromMediaStore file copy**

Inside `importFromMediaStore`, after `srcFile.copyTo(destFile, overwrite = false)` succeeds and before `completed++`:

```kotlin
                    opLog.append(Operation(
                        id = UUID.randomUUID().toString(),
                        type = OpType.ADD,
                        path = "pile/$fileName"
                    ))
```

Also enqueue after successful copy in the `addFilesToPile` path. But `addFilesToPile` is in `MusicRepositoryImpl` which also needs OpLog injection.

- [ ] **Step 3: Inject OpLog into MusicRepositoryImpl**

```kotlin
import fumi.day.literalmusi.data.git.OpLog
import fumi.day.literalmusi.data.git.Operation
import fumi.day.literalmusi.data.git.OpType
import java.util.UUID

class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitTransport: GitTransport,
    private val opLog: OpLog
) : MusicRepository {
```

- [ ] **Step 4: Enqueue ADD in addFilesToPile**

After `destFile.outputStream().use { output -> input.copyTo(output) }` succeeds:

```kotlin
                    opLog.append(Operation(
                        id = UUID.randomUUID().toString(),
                        type = OpType.ADD,
                        path = "pile/$fileName"
                    ))
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/ui/settings/SettingsViewModel.kt app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt
git commit -m "feat: enqueue ADD operations on file import"
```

---

### Task 7: Verify project builds

**No new files. Run: Gradle build**

- [ ] **Step 1: Run gradle build to check for compilation errors**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: If any errors, fix them and rebuild**

Common issues to check:
- Missing imports in any new/modified file
- Type mismatches in batchCommit
- Hilt injection issues (all new classes are @Singleton with @Inject constructor, so Hilt can resolve them)

- [ ] **Step 3: Commit any build fixes**

```bash
git commit -am "fix: build errors from sync refactor"
```
