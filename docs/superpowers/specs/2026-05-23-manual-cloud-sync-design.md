# Manual Cloud Sync Design

## Overview

Replace the existing automatic Git-based sync with a fully manual upload/download model. The user explicitly chooses when and which files to upload to or download from GitHub.

## Motivation

The existing auto-sync (OpLog → SyncScheduler → batchCommit/pull) is opaque and uncontrolled. Users want to decide per-file what goes to the cloud.

## Architecture

```
SettingsScreen ──→ CloudSyncDialog (upload/download/import)
                        │
                        ▼
                 CloudSyncManager ──→ GitHubApiTransport (kept)
                        │
                        ▼
               Song.isUploaded field ──→ music_cache.json
                        │
                        ▼
               MusicListScreen ☁ icon
```

### Files changed/created

| File | Action |
|------|--------|
| `data/git/CloudSyncManager.kt` | **New** — manual upload/download API |
| `domain/model/Song.kt` | **Modify** — add `isUploaded` field |
| `data/repository/MusicRepositoryImpl.kt` | **Modify** — update isUploaded on mutations |
| `ui/settings/SettingsScreen.kt` | **Modify** — add upload/download buttons |
| `ui/settings/SettingsViewModel.kt` | **Modify** — add upload/download logic |
| `ui/list/MusicListScreen.kt` | **Modify** — add cloud icon, update delete dialog |
| `ui/list/MusicListViewModel.kt` | **Modify** — manage isUploaded state, reconcile |
| `data/git/GitSyncManager.kt` | **Deprecated** — replaced by CloudSyncManager |
| `data/git/SyncProcessor.kt` | **Deprecated** — replaced by CloudSyncManager |
| `data/git/OpLog.kt` | **Deprecated** — no longer needed |
| `data/git/SyncScheduler.kt` | **Deprecated** — no longer needed |

## Design Details

### 1. CloudSyncManager (new)

```kotlin
class CloudSyncManager @Inject constructor(
    private val gitTransport: GitTransport,  // GitHubApiTransport
    private val preferences: UserPreferences
) {
    suspend fun listRemoteFiles(): Result<List<String>>
    suspend fun uploadFiles(files: List<File>, onProgress: (fileName: String, index: Int, total: Int) -> Unit): Result<List<String>>
    suspend fun downloadFiles(fileNames: List<String>, onProgress: (fileName: String, index: Int, total: Int) -> Unit): Result<List<String>>
    suspend fun deleteRemoteFiles(fileNames: List<String>, onProgress: (fileName: String, index: Int, total: Int) -> Unit): Result<List<String>>
}
```

- `listRemoteFiles()` → GitHubApiTransport lists `pile/` directory
- `uploadFiles()` → for each file, create blob → tree → commit → update ref (same mechanism as current batchCommit)
- `downloadFiles()` → for each name, fetch raw content, write to `pile/`
- `deleteRemoteFiles()` → create tree without those files, commit, update ref
- All operations are sequential per batch, with progress callbacks
- Cancel support via `Job.cancel()` — completed files stay, pending files are skipped

### 2. Song Model Extension

```kotlin
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val formattedDuration: String,
    val filePath: String,
    val isUploaded: Boolean = false   // NEW
)
```

Persisted in `music_cache.json` alongside existing metadata.

### 3. Cloud Status Reconciliation

- Every time `MusicListScreen` is entered, or upload/download/delete completes:
  1. Call `cloudSyncManager.listRemoteFiles()` → get remote filenames
  2. For each local song, set `isUploaded = fileName in remoteFiles`
  3. Update cache and Flow

External deletion (via GitHub web UI or another client) is automatically detected.

### 4. Settings Screen UI Changes

**Upload button:**
- Click → `listRemoteFiles()` → filter local `pile/` files not in remote list
- Dialog shows songs with checkboxes + Select All
- Confirm → progress dialog: "Uploading: song.mp3 (3/5)" + Cancel button
- No GitHub configured → Snackbar "请先配置 GitHub"

**Download button:**
- Click → `listRemoteFiles()` → filter remote files not in local `pile/`
- Dialog shows remote songs with checkboxes + Select All
- Confirm → progress dialog: "Downloading: song.mp3 (3/5)" + Cancel button

**Conflict resolution:**
- When uploading/downloading, if a file with same name exists but different content → AlertDialog: "Override / Skip"

**Import dialog:**
- New checkbox `☐ 同时上传到云端`
- If checked → after all local imports complete, automatically call `uploadFiles()`

**Connect GitHub:**
- Existing connect/disconnect + token/repo dialog kept
- "Upload" / "Download" buttons placed in a new `CloudSyncCard`

**SyncStatusCard:**
- Simplified: shows local file count + remote file count only
- "Sync Now" button removed

### 5. Delete Dialog Changes

In `MusicListScreen`:

```
长按歌曲 → AlertDialog
  "Delete '${song.title}'?"
  [If isUploaded] ☐ Also delete from cloud
  [Cancel] [Delete]
```

If "Also delete from cloud" checked:
1. Call `cloudSyncManager.deleteRemoteFiles(listOf(song.fileName))`
2. Then delete local file and update cache

### 6. Cloud Icon in List

- Each `SongItem` shows a cloud icon at the right side (before duration)
- `Icons.Default.Cloud` (outlined/empty) = `isUploaded == false`
- `Icons.Default.CloudQueue` (filled) = `isUploaded == true`
- Tapping the icon does nothing (purely visual)

### 7. Concurrency

- Upload and download share a single `Job` — only one cloud operation at a time
- While uploading/downloading, buttons show "Uploading..." / "Downloading..." and are disabled
- Cancel button on progress dialog → `Job.cancel()`
- Local operations (play, browse, import) are unaffected

### 8. Removal of Auto-Sync Components

- `OpLog.kt` — delete (no more operation logging)
- `SyncScheduler.kt` — delete (no more auto-trigger)
- `SyncProcessor.kt` — delete (replaced by CloudSyncManager)
- `GitSyncManager.kt` — delete (replaced by CloudSyncManager)
- `MusicRepositoryImpl` — remove `opLog.append()` calls
- `MusicRepositoryImpl` — remove `syncScheduler.onOperationEnqueued()` calls

## Data Flow

### Upload flow:
```
User clicks "Upload to Cloud"
  → SettingsVM calls cloudSyncManager.listRemoteFiles()
  → Compare with local pile/ files
  → Show dialog (selectable songs, local only)
  → User selects + confirms
  → SettingsVM calls cloudSyncManager.uploadFiles(selected, onProgress)
  → On complete, update each song's isUploaded=true in cache
  → MusicListVM reconciles remote list → refresh UI
```

### Download flow:
```
User clicks "Download from Cloud"
  → SettingsVM calls cloudSyncManager.listRemoteFiles()
  → Compare with local pile/ files
  → Show dialog (selectable songs, cloud only)
  → User selects + confirms
  → SettingsVM calls cloudSyncManager.downloadFiles(selected, onProgress)
  → On complete, update isUploaded=true in cache
  → MusicListVM refreshes list → new songs appear
```

### Delete flow:
```
User long-presses song
  → AlertDialog with "Also delete from cloud" checkbox (if uploaded)
  → If checked: cloudSyncManager.deleteRemoteFiles(name)
  → Delete local file
  → Update cache (isUploaded=false or remove entry)
```

### Import flow:
```
User opens import dialog, selects songs, checks "同时上传"
  → SettingsVM imports files to local pile/ (existing flow)
  → After all imports done, calls cloudSyncManager.uploadFiles(newFiles)
  → On complete, update isUploaded for each
```
