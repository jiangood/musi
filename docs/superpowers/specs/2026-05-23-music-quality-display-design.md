# Music Quality Display on Player Screen

## Summary

Add a small text label on the music player interface showing audio quality information
(e.g., "MP3 320" for MP3 at 320kbps, "FLAC 96kHz" for lossless FLAC).

## Changes

### 1. Song Data Model (`domain/model/Song.kt`)

Add two nullable fields:

- `format: String?` — format name derived from file extension (MP3, FLAC, WAV, AAC, OGG, OPUS)
- `qualityLabel: String?` — pre-computed display string (e.g. "MP3 320", "FLAC 96kHz")

### 2. Metadata Extraction (`data/repository/MusicRepositoryImpl.kt`)

In `extractSong()`, extract additional metadata via `MediaMetadataRetriever`:

- `METADATA_KEY_BITRATE` → bitrate in bps
- `METADATA_KEY_SAMPLERATE` → sample rate in Hz

Quality label logic:

```
lossyFormats   = {"mp3", "aac", "ogg", "opus", "wma"}
losslessFormats = {"flac", "wav", "alac", "aiff"}

if format in lossyFormats && bitrate > 0:
    label = "$format ${bitrate / 1000}"         // "MP3 320"
elif format in losslessFormats && sampleRate > 0:
    label = "$format ${sampleRate / 1000}kHz"   // "FLAC 96kHz"
elif format != null:
    label = format                               // fallback: just the format name
else:
    label = null                                 // no info
```

Update `CacheEntry` and cache serialization to persist `format` and `qualityLabel`.

### 3. Player Screen UI (`ui/player/PlayerScreen.kt`)

Below the album name, add a single line:

```
Text(
    text = song.qualityLabel ?: "",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```

### Key Details

- **No new dependencies** — uses only `android.media.MediaMetadataRetriever` (already used)
- **Backward compatible** — cached songs without quality fields display nothing
- **Lossy display**: format + bitrate in kbps (e.g., "MP3 320", "AAC 256")
- **Lossless display**: format + sample rate in kHz (e.g., "FLAC 96kHz", "WAV 44.1kHz")
- **Bit depth unavailable** — `MediaMetadataRetriever` does not expose bit depth
- **Sample rate format**: lowercase "k" (e.g., "96kHz")
