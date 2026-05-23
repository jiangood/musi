# Music Metadata Cache

## Problem

When the user has many music files in `pile/`, opening the app takes several seconds before the song list appears. This is because `MusicRepositoryImpl.scanPile()` calls `MediaMetadataRetriever.setDataSource()` + 4 `extractMetadata()` calls on every single file, with no caching — on every app launch and every FileObserver-triggered refresh.

## Design

Add a JSON metadata cache file outside `pile/` so that on subsequent launches, only new or modified files need actual metadata extraction.

### Cache File

- **Path**: `context.filesDir/cache/music_cache.json` (under internal storage, outside `pile/`, so it doesn't affect Git sync)
- **Format**:
```json
{
  "version": 1,
  "entries": {
    "pile/song.mp3": {
      "title": "Song Title",
      "artist": "Artist",
      "album": "Album",
      "duration": 240000,
      "dataModified": 1700000000000
    }
  }
}
```

### Behavior

**`scanPile()`**:
1. Read cache file (`music_cache.json`) into a `Map<String, CachedEntry>`
2. List all audio files in `pile/`
3. For each file:
   - If cache has an entry with matching `dataModified` → use cached metadata
   - Otherwise → call `extractSong()` to get fresh metadata
4. Merge: cached entries + newly scanned entries, sorted by filename
5. Serialize updated cache map and write atomically (write to `.tmp`, then rename)

**FileObserver / refresh**:
No changes needed — same `scanPile()` path. The incremental logic handles it.

### Atomic Write

Write to `music_cache.json.tmp` first, then `renameTo("music_cache.json")` to avoid partial writes on crash.

### Error Handling

- If cache file is missing, corrupt, or unparseable → fall back to full scan (no crash)
- If `extractSong()` fails for a file → skip it (existing behavior preserved)

### Files Changed

Only **`MusicRepositoryImpl.kt`** — no UI, no ViewModel, no model changes.

### Performance

| Scenario | Before | After |
|---|---|---|
| First launch | O(n) metadata reads | O(n) metadata reads + O(1) cache write |
| Subsequent launch | O(n) metadata reads | O(1) cache read + O(n) `lastModified()` checks (no IO per file) |
| Single file added/changed | O(n) metadata reads | O(n) `lastModified()` checks + 1 metadata read |
| All files unchanged | O(n) metadata reads | O(1) cache read + O(n) `lastModified()` checks |

### Constraints

- No database (Room, SQLite, etc.)
- No Google Play services / Firebase
- Cache is disposable — deleting it just triggers a full re-scan
- Cache lives in `filesDir`, not `cacheDir`, so the OS won't clear it under pressure
- Cache file references files by relative path from `pileDir`; if `pileDir` changes, cache is invalidated automatically (since paths won't match)
