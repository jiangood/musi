# Music Player Transformation Implementation Plan

> **For agentic workers:** Execute tasks sequentially. Each task produces self-contained changes.

**Goal:** Transform the Literal Musi memo app into a minimal music player using Media3 ExoPlayer

**Architecture:** Singleton MusicPlayer (ExoPlayer wrapper) exposes StateFlow of PlayerState. MusicRepository scans MediaStore for audio files. UI observes these flows to display library and now-playing screen.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Media3 ExoPlayer, Android MediaStore

---

### Task 1: Create Song Domain Model

**Files:**
- Replace: `app/.../domain/model/Memo.kt`

Write the `Song` data class to `app/src/main/java/fumi/day/literalmusi/domain/model/Song.kt`

```kotlin
package fumi.day.literalmusi.domain.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: String,
    val dataModified: Long
) {
    val formattedDuration: String
        get() {
            val totalSeconds = duration / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
}
```

### Task 2: Create MusicRepository Interface

**Files:**
- Create: `app/.../data/repository/MusicRepository.kt`

```kotlin
package fumi.day.literalmusi.data.repository

import fumi.day.literalmusi.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface MusicRepository {
    fun observeAll(): Flow<List<Song>>
}
```

### Task 3: Create MusicRepositoryImpl

**Files:**
- Create: `app/.../data/repository/MusicRepositoryImpl.kt`

MediaStore scanner that polls every 5 seconds.

### Task 4: Create MusicPlayer Singleton

**Files:**
- Create: `app/.../data/player/MusicPlayer.kt`

ExoPlayer wrapper with StateFlow<PlayerState>.

### Task 5: Create MusicListScreen + ViewModel

**Files:**
- Create: `app/.../ui/list/MusicListScreen.kt`
- Create: `app/.../ui/list/MusicListViewModel.kt`

### Task 6: Create PlayerScreen + ViewModel

**Files:**
- Create: `app/.../ui/player/PlayerScreen.kt`
- Create: `app/.../ui/player/PlayerViewModel.kt`

### Task 7: Update Navigation, App, MainActivity

**Files:**
- Modify: `app/.../ui/navigation/NavGraph.kt`
- Modify: `app/.../ui/App.kt`
- Modify: `app/.../MainActivity.kt`

### Task 8: Simplify Settings and Preferences

**Files:**
- Modify: `app/.../ui/settings/SettingsScreen.kt`
- Modify: `app/.../ui/settings/SettingsViewModel.kt`
- Modify: `app/.../data/prefs/UserPreferences.kt`

### Task 9: Update DI Module

**Files:**
- Modify: `app/.../di/AppModule.kt`

### Task 10: Update AndroidManifest and Resources

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

### Task 11: Delete Old Memo Files

### Task 12: Build and Verify

### Task 13: Commit and Push
