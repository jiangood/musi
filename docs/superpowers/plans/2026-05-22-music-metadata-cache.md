# Music Metadata Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JSON metadata cache to `MusicRepositoryImpl` so that subsequent app launches only scan new/modified files instead of all files.

**Architecture:** Add a `music_cache.json` file under `context.filesDir/cache/` that stores parsed song metadata keyed by relative file path, with `dataModified` as the invalidation signal. `scanPile()` reads the cache first, then only calls `MediaMetadataRetriever` for files whose `lastModified()` has changed. Atomic write via `.tmp` + `renameTo`.

**Tech Stack:** Android SDK `org.json` (built-in, no new dependency), Kotlin coroutines, `Dispatchers.IO`

---

### Task 1: Add metadata cache to `MusicRepositoryImpl.kt`

**Files:**
- Modify: `app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt`

- [ ] **Step 1: Add cache file path and helper functions**

Add after the `audioExtensions` / `_refresh` fields:

```kotlin
private val cacheFile: File get() = File(context.filesDir, "cache/music_cache.json")
```

Add after `isAudioFileName()`:

```kotlin
private data class CacheEntry(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val dataModified: Long
)
```

- [ ] **Step 2: Implement `readCache()` function**

```kotlin
private fun readCache(): Map<String, CacheEntry> {
    if (!cacheFile.exists()) return emptyMap()
    return try {
        val json = cacheFile.readText()
        val obj = org.json.JSONObject(json)
        val entries = obj.getJSONObject("entries")
        val map = mutableMapOf<String, CacheEntry>()
        for (key in entries.keys()) {
            val e = entries.getJSONObject(key)
            map[key] = CacheEntry(
                title = e.getString("title"),
                artist = e.getString("artist"),
                album = e.getString("album"),
                duration = e.getLong("duration"),
                dataModified = e.getLong("dataModified")
            )
        }
        map
    } catch (e: Exception) {
        emptyMap()
    }
}
```

- [ ] **Step 3: Implement `writeCache()` function**

```kotlin
private fun writeCache(entries: Map<String, CacheEntry>) {
    try {
        cacheFile.parentFile?.mkdirs()
        val tmp = File(cacheFile.parentFile, "music_cache.json.tmp")
        val obj = org.json.JSONObject()
        val entriesObj = org.json.JSONObject()
        for ((path, entry) in entries) {
            val e = org.json.JSONObject()
            e.put("title", entry.title)
            e.put("artist", entry.artist)
            e.put("album", entry.album)
            e.put("duration", entry.duration)
            e.put("dataModified", entry.dataModified)
            entriesObj.put(path, e)
        }
        obj.put("version", 1)
        obj.put("entries", entriesObj)
        tmp.writeText(obj.toString(2))
        tmp.renameTo(cacheFile)
    } catch (_: Exception) {
        // Cache write failure is non-fatal
    }
}
```

- [ ] **Step 4: Modify `scanPile()` to use cache**

Replace the existing `scanPile()` with:

```kotlin
private fun scanPile(): List<Song> {
    if (!pileDir.exists()) return emptyList()
    val files = pileDir.listFiles()
        ?.filter { it.isFile && isAudioFileName(it.name) }
        ?.sortedBy { it.name }
        .orEmpty()

    val cache = readCache()
    val updatedEntries = mutableMapOf<String, CacheEntry>()
    val result = mutableListOf<Song>()

    for (file in files) {
        val relPath = "pile/${file.name}"
        val cached = cache[relPath]
        val song: Song?

        if (cached != null && cached.dataModified == file.lastModified()) {
            song = Song(
                id = file.absolutePath.hashCode().toLong() and 0xFFFFFFFFL,
                title = cached.title,
                artist = cached.artist,
                album = cached.album,
                duration = cached.duration,
                uri = file.absolutePath,
                dataModified = cached.dataModified
            )
        } else {
            song = extractSong(file)
            if (song != null) {
                updatedEntries[relPath] = CacheEntry(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    duration = song.duration,
                    dataModified = song.dataModified
                )
            }
        }

        if (song != null) {
            result.add(song)
            if (relPath !in updatedEntries) {
                updatedEntries[relPath] = cache[relPath]!!
            }
        }
    }

    if (updatedEntries.isNotEmpty()) {
        writeCache(updatedEntries)
    }

    return result
}
```

- [ ] **Step 5: Verify the build compiles**

Run: `cd /root/musi && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/fumi/day/literalmusi/data/repository/MusicRepositoryImpl.kt
git commit -m "feat: add metadata cache to speed up music list loading

Cache parsed song metadata to context.filesDir/cache/music_cache.json
so that subsequent launches only scan new or modified files, avoiding
repeated MediaMetadataRetriever calls for unchanged files."
```
