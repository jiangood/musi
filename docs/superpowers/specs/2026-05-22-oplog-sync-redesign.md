# OpLog Sync Redesign

**Date**: 2026-05-22
**Status**: Draft

## Overview

Replace the current stateless-diff sync with an operation-log (OpLog) based approach. Local changes (ADD/DELETE/RENAME) are recorded as JSONL operations in an append-only file. Sync processes the log sequentially, producing a single git commit per batch. The download direction (remote → local) remains as-is for now.

## Motivation

The current `GitHubApiTransport.commit()` performs a full diff between local and remote file trees on every sync: list all remote files, compare with local SHA tracking, compute upload set. This is:

- **O(n) API calls per sync** — every sync lists every file in the repo
- **Fragile SHA tracking** — relies on `UserPreferences` to carry SHA state across syncs
- **Crash-unsafe** — if sync is interrupted, there's no recovery; state is inconsistent
- **Monolithic** — `commit()` is 80+ lines handling upload, conflict detection, and deletion in one method

OpLog replaces this with an incremental approach: record what changed locally, then just execute those changes.

## Architecture

```
filesDir/
├── repo/
│   ├── pile/       ← music files (read by ExoPlayer)
│   └── trash/      ← deleted files (retained for recovery)
└── oplog/
    ├── oplog.jsonl     ← operations since last successful sync
    └── oplog.pending   ← temp file during active sync (crash recovery)
```

## Data Model

### Operation

```kotlin
enum class OpType { ADD, DELETE, RENAME, MODIFY }

data class Operation(
    val id: String,          // UUID
    val type: OpType,
    val path: String,        // repo-relative path, e.g. "pile/song.mp3"
    val oldPath: String? = null, // required for RENAME
    val time: Long = System.currentTimeMillis()
)
```

### OpLog format (JSONL)

```jsonl
{"id":"a1b2c3","type":"ADD","path":"pile/song.mp3","time":1000}
{"id":"d4e5f6","type":"DELETE","path":"pile/old.mp3","time":1001}
{"id":"g7h8i9","type":"RENAME","path":"pile/new.mp3","oldPath":"pile/old-old.mp3","time":1002}
```

## OpLog Class

```kotlin
class OpLog {
    fun init(oplogDir: File)
    fun append(op: Operation)
    fun rotate(): List<Operation>
    fun completeSync()
    fun failSync()
    fun pendingCount(): Int
}
```

| Method | Behavior |
|--------|----------|
| `init` | Set the oplog directory |
| `append` | Synchronized JSONL append to `oplog.jsonl` |
| `rotate` | Rename `oplog.jsonl` → `oplog.pending`, create new empty `oplog.jsonl`, return loaded ops |
| `completeSync` | Delete `oplog.pending` on success |
| `failSync` | Prepend `oplog.pending` content back to `oplog.jsonl` for retry |
| `pendingCount` | Number of pending operations (for UI display) |

All disk I/O uses `Dispatchers.IO`. `append`, `rotate`, `completeSync`, `failSync` are `@Synchronized` for thread safety.

## GitTransport Interface Changes

### Removed

```kotlin
suspend fun stageAll(): Int
suspend fun commit(message: String, knownShas: Map<String, String>, lastSyncedAt: Long?): CommitResult
suspend fun push(): Boolean
```

### Added

```kotlin
suspend fun batchCommit(ops: List<Operation>): BatchResult

data class BatchResult(
    val committed: Boolean = false,
    val errors: List<String> = emptyList()
)
```

### Unchanged

```kotlin
suspend fun ensureInitialized(token: String, repo: String)
suspend fun pull(): PullResult
val pileDir: File
val trashDir: File
fun close()
```

## batchCommit Algorithm

```
1. Get current ref SHA (GET /git/refs/heads/master)
2. Get current tree (GET /git/trees/<tree_sha>)
3. Build new tree entries from ops:
   ADD    → read local file → createBlob → add entry
   DELETE → remove entry from tree (by path)
   RENAME → remove oldPath entry → createBlob(newPath) → add entry
   MODIFY → createBlob → replace entry SHA
4. Create tree with new entries (POST /git/trees)
5. Create commit (POST /git/commits, parent = current ref)
6. Update ref (PATCH /git/refs/heads/master)
7. Refresh remote file list
```

No SHA comparison needed. The Operation log is the source of truth for local changes.

## Sync Flow

```
Trigger (onResume / op enqueued / manual)
    │
    ▼
SyncScheduler (debounce 3s on op, immediate on resume/manual)
    │
    ▼
GitSyncManager.syncAndAwait()
    │
    ▼
SyncProcessor.process()
    │
    ├─ Upload ──────────────────────
    │   1. opLog.rotate()
    │   2. ops not empty? → gitTransport.batchCommit(ops)
    │   3. committed?   → opLog.completeSync()
    │      failed?      → opLog.failSync() (auto retry next sync)
    │
    └─ Download ────────────────────
        4. gitTransport.pull()       ← existing full-diff, kept as-is
        5. Return SyncResult
```

## Enqueue Points

| Operation | Trigger | File |
|-----------|---------|------|
| ADD | SAF / MediaStore import success | `SettingsViewModel.importFromMediaStore` |
| ADD | `addFilesToPile()` success | `MusicRepositoryImpl` |
| DELETE | `deleteFile()` or FileObserver DELETE event | `MusicRepositoryImpl` |
| RENAME | (future, no rename UI yet) | — |
| MODIFY | (future, no tag editing yet) | — |

Each enqueue is followed by `syncScheduler.onOperationEnqueued()` to trigger 3s debounced sync.

## Crash Recovery

```
Normal state:    oplog.jsonl [ADD, ADD, DELETE]
rotate →         oplog.pending [ADD, ADD, DELETE], empty oplog.jsonl
sync succeeds →  delete oplog.pending ✓
sync fails →     prepend oplog.pending → oplog.jsonl, retry next sync ✓
```

If the app is killed during sync:
- After rotate but before batchCommit: ops are in `oplog.pending`, `oplog.jsonl` is empty → on next init, the pending file is detected and recovered automatically
- After batchCommit but before completeSync: commit already happened on GitHub, but pending file still exists → can re-process ops safely (idempotent — re-adding same blob SHA is a no-op, re-deleting same path is a no-op)

## Modified Files

| File | Change |
|------|--------|
| `data/git/OpLog.kt` (new) | JSONL append, rotation, crash recovery |
| `data/git/GitTransport.kt` | Remove `stageAll()`/`commit()`/`push()`, add `batchCommit()`/`BatchResult` |
| `data/git/GitHubApiTransport.kt` | Replace `commit()` with `batchCommit()`; remove unused methods |
| `data/git/SyncProcessor.kt` | Rewrite: rotate → batchCommit → pull |
| `data/git/GitSyncManager.kt` | Inject `OpLog`, `init()` call, remove SHA tracking |
| `data/repository/MusicRepositoryImpl.kt` | Inject `OpLog`, enqueue ADD/DELETE at mutation points |
| `ui/settings/SettingsViewModel.kt` | Inject `OpLog`, enqueue ADD after import |

## Unchanged Files

| File | Reason |
|------|--------|
| `data/git/SyncScheduler.kt` | Already correct, no changes needed |
| `ui/settings/SettingsScreen.kt` | No UI changes needed |
| `MainActivity.kt` | Already wired to SyncScheduler |
| `scripts/sync-music.sh` | PC side is out of scope |

## Testing

1. Import a music file → verify ADD op is appended to oplog.jsonl
2. Sync → verify batchCommit is called with correct ops
3. Delete a file → verify DELETE op is appended
4. Kill app during sync → verify oplog.pending recovery on next launch
5. Network failure during batchCommit → verify failSync restores ops
6. Build: `./gradlew assembleDebug` must pass
