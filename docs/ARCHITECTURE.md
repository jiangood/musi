# Literal Musi – Architecture

## Overview

Literal Musi is a minimalist Android music player. No playlists, no metadata scraping, no algorithmic recommendations — just audio files in `pile/` and `trash/`, synced via Git.

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Hilt** (DI) + **KSP** (annotation processing)
- **ExoPlayer** (audio playback)
- **DataStore Preferences** (settings) + **EncryptedSharedPreferences** (GitHub token)
- **Target**: Android 8.0+ (API 26), compileSdk/targetSdk 35
- **No Google APIs, no Firebase, no tracking**

## Key Architecture

| Path | Role |
|---|---|
| `app/src/main/java/fumi/day/literalmusi/` | All source |
| `data/repository/MusicRepositoryImpl.kt` | File-backed music CRUD (reads/writes `filesDir/pile/`) |
| `data/player/MusicPlayer.kt` | Audio playback wrapper around ExoPlayer |
| `data/github/GitHubSyncManager.kt` | Two-way sync logic (local ↔ remote) |
| `data/github/GitHubRepository.kt` | Raw GitHub REST API calls |
| `data/git/GitForge.kt` | Interface abstraction for forge API |
| `ui/navigation/NavGraph.kt` | Routes: list → player, settings |
| `ui/list/MusicListScreen.kt` | Music list + search + swipe-to-delete |
| `ui/player/PlayerScreen.kt` | Now-playing with playback controls |
| `ui/settings/SettingsScreen.kt` | Git config, theme, audio settings |
| `data/prefs/UserPreferences.kt` | Settings via DataStore + EncryptedSharedPrefs |

## Data Flow

```
User taps file
  → MusicListScreen
  → MusicRepository (resolves file path)
  → MusicPlayer (starts ExoPlayer playback)

Search
  → MusicListScreen (search bar)
  → MusicRepository (filters pile/* by filename)
  → displays matching files

Sync
  → onAppResume / after mutation
  → GitHubSyncManager
  → GitHubRepository (REST API)
  → MusicRepository (updates local files)
```

## Directory Structure

```
repo/
├── pile/    ← music files (mp3, flac, opus, etc.)
└── trash/   ← deleted files (Git sync only, for recovery)
```

No database. No music library index. The filesystem **is** the library.

## Build & Run

```powershell
# Debug
./gradlew assembleDebug

# Release (requires signing config in local.properties or env vars)
./gradlew assembleRelease

# Install debug APK
./gradlew installDebug
```

Release signing: `local.properties` keys `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, or CI env vars `CI_KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`.

## Conventions

- **Kotlin code style**: `kotlin.code.style=official` in gradle.properties
- **File naming**: Music files keep their original filenames in `pile/`
- **Sync**: Auto-sync on `onResume()`; first-to-sync-wins on conflict
- **No backup** (`android:allowBackup="false"`)
- **ProGuard** enabled for release; Hilt, ViewModel, coroutines kept
- **Supported formats**: mp3, flac, ogg, opus, wav, m4a, aac

## Important Constraints

- App namespace: `fumi.day.literalmusi`
- Min SDK 26 → no Java 8+ APIs that require higher (but `java.time` is available)
- EncryptedSharedPreferences for token storage only; general prefs in DataStore
- No metadata scanning on launch — filesystem is the source of truth
