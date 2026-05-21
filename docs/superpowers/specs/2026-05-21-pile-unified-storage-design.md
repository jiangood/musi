# Unified pile/ Storage Design

**Date**: 2026-05-21
**Status**: Draft

## Goal

Unify Literal Musi's music storage under the internal `filesDir/pile/` directory, replacing MediaStore-based scanning with direct file scanning of `pile/`. This enables seamless GitHub sync for all music files.

## Storage & Scanning

### Directory Structure

- All music files live flat in `filesDir/pile/`
- No subdirectory organization by artist/album
- On filename conflict during import: reject with user-facing error message
- Supported audio extensions: `.mp3`, `.flac`, `.wav`, `.ogg`, `.m4a`, `.aac`

### Scanning Flow

`MusicRepositoryImpl.observeAll()` changes from MediaStore cursor query to:

```
kotlin
fun observeAll(): Flow<List<Song>> = callbackFlow {
    val pileDir = File(context.filesDir, "pile")
    val files = pileDir.listFiles()
        ?.filter { it.isFile && isAudioFile(it.name) }
        ?.sortedBy { it.name }
        ?.map { file -> extractSong(file) }
        ?: emptyList()
    trySend(files)

    // Watch for file changes
    val observer = object : FileObserver(pileDir, CREATE or DELETE or MOVED_FROM or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null && isAudioFile(path)) {
                blockingQueue.put(Unit)  // debounce and re-scan
            }
        }
    }
    observer.startWatching()
    awaitClose { observer.stopWatching() }
}.flowOn(Dispatchers.IO)
```

### Metadata Extraction

`extractSong(file)` uses `MediaMetadataRetriever`:

```kotlin
fun extractSong(file: File): Song {
    val mmr = MediaMetadataRetriever()
    mmr.setDataSource(file.absolutePath)
    return Song(
        id = file.name.hashCode().toLong(),
        title = mmr.extractMetadata(TITLE)?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension,
        artist = mmr.extractMetadata(ARTIST)?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist",
        album = mmr.extractMetadata(ALBUM)?.takeIf { it.isNotBlank() }
            ?: "Unknown Album",
        duration = mmr.extractMetadata(DURATION)?.toLongOrNull() ?: 0L,
        uri = file.absolutePath,
        dataModified = file.lastModified()
    ).also { mmr.release() }
}
```

- Close 30-second minimum duration filter is removed (no need with pile/ being curated)
- `IS_MUSIC` filter is removed (file extension filtering is sufficient)

### FileObserver

- Watches `pile/` directory for CREATE, DELETE, MOVED_FROM, MOVED_TO events
- Debounces rapid events with a small delay (e.g., 500ms)
- Emits new scan results through the Flow
- Covers both manual imports and GitHub sync file changes

## Import Flow

The Settings screen replaces "Add music folder" with an "Import Music" entry point.

### Entry UX

- Settings screen shows a "Import Music" button
- Tapping opens a dialog with two options:
  1. **From folder** (SAF file picker)
  2. **From existing music** (MediaStore browser)

### Option 1: From Folder (SAF)

```
User taps "From folder"
→ ACTION_OPEN_DOCUMENT_TREE launched
→ User selects a folder
→ App recursively discovers all audio files in the tree
→ For each file:
   - Check if filename already exists in pile/ → REJECT, show error message with filename
   - Check file size > 25MB (GitHub API limit) → REJECT, advise PC sync
   - Copy file to pile/
→ Show import progress (current / total)
→ On completion: FileObserver triggers list refresh
```

- `convertTreeUriToPath()` helper from existing code is reused for uri-to-path resolution
- Copy uses `context.contentResolver.openInputStream(uri)` for SAF compatibility

### Option 2: From Existing Music (MediaStore)

```
User taps "From existing music"
→ Shows existing MediaStore music list (reuse scanSongs() logic, but in read-only mode)
→ User selects multiple songs via checkboxes
→ "Import Selected" button → for each selected song:
   - Check if filename already exists in pile/ → REJECT, show error
   - Check file size > 25MB → REJECT, advise PC sync
   - Copy file from Song.uri to pile/
→ Show import progress
→ On completion: FileObserver triggers list refresh
```

### Filename Conflict Handling

- If `pile/` already contains a file with the same name (including extension), the import is **rejected** for that file
- A user-visible error message is shown: "`filename` already exists in your music library"
- No automatic rename, no overwrite

### Large File Handling (GitHub API Constraint)

- Files > 25MB cannot be synced via GitHub REST API
- During import: check file size and reject with message:
  "`filename` is too large (>25MB) for GitHub sync. Use PC git client to sync this file."
- This applies to both SAF and MediaStore import paths

## Settings Changes

| Before | After |
|---|---|
| "Add music folder" (SAF folder picker, stored as includedFolderPath) | Removed |
| "Included folders" list | Removed |
| "Excluded folders" path exclusion | Removed |
| — | **"Import Music"** button → dialog with 2 options |
| UserPreferences.includedFolderPaths | Removed |
| UserPreferences.excludedFolderPaths | Removed |

- `UserPreferences` no longer needs `includedFolderPaths` / `excludedFolderPaths` flows
- No other prefs changes needed (GitHub token, repo, sync state remain)

## GitHub Sync

### What Changes in Sync

`GitHubSyncManager` already operates on `filesDir/pile/`. The sync algorithm (two-way reconciliation) stays the same:

- Listing remote `pile/` contents
- Comparing local vs remote filenames
- Uploading new local files
- Downloading new remote files
- Conflict resolution (remote wins, local backed up)

### What Stays the Same

- Token-based GitHub REST API auth
- `lastSyncedAt` / `lastSyncedShas` tracking in DataStore
- `moveToTrash` (PUT to `trash/` + DELETE from `pile/`)
- Conflict backup with `_conflict_<timestamp>_` prefix

### What's New / Changes

- Large file handling: during download, if file > 25MB, skip with a warning log (user was already warned at import time)
- No special handling needed — audio files are just regular files to the sync engine. Sync continues to handle ALL files in `pile/` (audio + text), unchanged.

## Files to Modify

| File | Changes |
|---|---|
| `MusicRepositoryImpl.kt` | Replace `observeAll()` with `pile/` scanning + `MediaMetadataRetriever` + `FileObserver` |
| `MusicRepository.kt` | No change needed (interface stays the same) |
| `MusicListViewModel.kt` | No change needed (still subscribes to `observeAll()`) |
| `SettingsViewModel.kt` | Replace folder management with import flow; remove included/excluded folder methods |
| `SettingsScreen.kt` | Replace "Add music folder" UI with "Import Music" button + dialogs |
| `UserPreferences.kt` | Remove `includedFolderPaths`, `excludedFolderPaths` flows and related methods |
| `AppModule.kt` | No change needed |
| `GitHubSyncManager.kt` | Add audio file filtering; add large-file (>25MB) skip during download |
| `GitHubRepository.kt` | No change needed |

## Files to Remove

- None. `scanSongs()`, `convertTreeUriToPath()`, `isAudioFile()` may be reused or relocated.

## Removed Features

- **Included/Excluded folder paths**: No longer needed since all music lives in `pile/`
- **30-second duration filter**: Not needed for curated `pile/` library
- **`IS_MUSIC` MediaStore filter**: Replaced by file extension filter

## Unchanged

- `Song` data class
- `MusicPlayer` (uses `Song.uri` — now a local file path, same as before)
- `MusicListScreen` (UI stays the same)
- `GitHubSyncManager` conflict resolution logic
- `UserPreferences` GitHub token/repo/sync state fields
- `GitForgeApi` interface
