# OpLog Sync Refactor Design

**Date**: 2026-05-21
**Status**: Draft

## Goal

Replace the current full-diff stateless sync with an operation-log-based sync, inspired by cloud drives like OneDrive. Local changes are recorded as operations in a persistent log, and sync processes the log sequentially to sync with GitHub.

## Operation Log (OpLog)

### Storage

- **File**: `filesDir/oplog.jsonl`
- **Format**: JSON Lines, append-only, one operation per line
- **Operations**: ADD, DELETE, RENAME, MODIFY

```jsonl
{"id":"a1b2c3","type":"ADD","path":"pile/song.mp3","time":1000}
{"id":"d4e5f6","type":"DELETE","path":"pile/old.mp3","time":1001}
{"id":"g7h8i9","type":"RENAME","path":"pile/new.mp3","oldPath":"pile/old-old.mp3","time":1002}
{"id":"j0k1l2","type":"MODIFY","path":"pile/song.mp3","time":1003}
```

- `id`: UUID, unique operation identifier
- `type`: ADD / DELETE / RENAME / MODIFY
- `path`: relative path within repo (e.g. `pile/file.mp3`)
- `oldPath`: RENAME only — the source path before rename
- `time`: epoch millis of the operation

### Writing

Operations are appended via `FileOutputStream(append=true)` + `BufferedWriter`, flushed after each write. All writes happen on `Dispatchers.IO`.

### Enqueue Points

| Operation | Trigger |
|---|---|
| ADD | File imported to `pile/` (from SAF or MediaStore) |
| DELETE | File deleted from `pile/` (detected by renamed-to-trash or direct delete) |
| RENAME | File renamed within `pile/` |
| MODIFY | File replaced/modified (e.g. tag edit) |

## Sync Flow

### Upload Direction (Local → Remote)

```
1. Rename oplog.jsonl → oplog.pending
2. Create new empty oplog.jsonl
3. Load oplog.pending into memory as SyncQueue
4. Process all operations in order:
   ADD    → create git blob, add to tree
   DELETE → remove entry from git tree
   RENAME → remove old entry, add new entry
   MODIFY → replace git blob, update tree entry
5. POST /git/trees (with all changes)
6. POST /git/commits
7. PATCH /git/refs/heads/master
8. If success → delete oplog.pending
9. If failure → prepend oplog.pending content back to oplog.jsonl
```

- All operations in one batch produce **a single commit**
- On failure, the pending content is merged back so it will be retried next sync
- The new oplog.jsonl (step 2) guarantees that operations generated **during** sync are not lost

### Download Direction (Remote → Local)

After upload completes:

```
1. List remote files (GET /repos/{owner}/{repo}/contents/pile)
2. List local files (filesDir/pile/)
3. For each remote file not present locally → download
4. For each local file not present remotely → deleted locally already, no action needed
```

Downloaded files are **not** recorded in OpLog (they are sync reconciliation, not user operations).

### Sync Triggers

| Trigger | Behavior |
|---|---|
| App onResume | If sync not already running, start immediately |
| Operation enqueued | Delay 3s (debounce), then start if no other trigger reset the timer |
| "Sync Now" button | Cancel any debounce timer, start immediately |
| Sync already running | All triggers are ignored (isSyncing guard) |

## Affected Files

| File | Changes |
|---|---|
| `OpLog.kt` (new) | Low-level JSONL append, file rotation, read |
| `SyncQueue.kt` (new) | In-memory queue of pending operations |
| `SyncScheduler.kt` (new) | Trigger management, debounce, state guard |
| `SyncProcessor.kt` (new) | Batch operations → git commit flow |
| `GitTransport.kt` | Add `batchCommit(ops: List<Operation>): BatchResult` |
| `GitHubApiTransport.kt` | Implement `batchCommit` using Git Data API |
| `GitSyncManager.kt` | Refactor to use queue-based flow; remove full-diff logic |
| `SettingsViewModel.kt` | Add op log enqueue calls at import points |
| `MusicRepositoryImpl.kt` | Add op log enqueue on FileObserver DELETE events |

## Data Model

```kotlin
enum class OpType { ADD, DELETE, RENAME, MODIFY }

data class Operation(
    val id: String,        // UUID
    val type: OpType,
    val path: String,
    val oldPath: String?,  // only for RENAME
    val time: Long
)
```

## Error Handling

| Failure | Behavior |
|---|---|
| Network error in upload | Retain `oplog.pending`, retry on next sync trigger |
| Network error in download | Skip failed files, log error, don't affect upload success |
| Corrupt oplog.jsonl | Log warning, truncate at last valid line + newline |
| 25MB+ file (GitHub limit) | Already rejected at import; skip in download with warning |
