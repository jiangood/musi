# Literal Musi User Guide

Literal Musi is a minimalist music player inspired by howm — a philosophy of "listen first, organize never."

## Getting Started

1. Copy music files into the `pile/` directory (or use Git sync)
2. Open the app
3. Tap a track to start playing

## Search

Type in the search bar. Results show matching tracks by file name.

## Deletion

Swipe left on any track to delete.

Deleted tracks are moved to `trash/` in your remote repository. They disappear from the app immediately.

There is no local trash. There is no restore button. This is intentional — deletion should feel final to keep your pile clean.

If you really need something back, find it in `trash/` in your repo and copy it back to `pile/`.

## GitHub Sync

### Setup

1. Create a GitHub repository (private recommended)
2. Generate a Personal Access Token:
   - Go to github.com > Settings > Developer settings > Personal access tokens > Tokens (classic)
   - Click "Generate new token (classic)"
   - Give it a name like "Literal Musi"
   - Select the "repo" scope (Full control of private repositories)
   - Click "Generate token"
   - Copy the token immediately
3. In the app, go to Settings > Connect GitHub
4. Paste your token and enter your repository name (e.g., `username/music`)

### Multi-device Sync

- Syncs automatically on app launch
- Don't modify the same file on multiple devices simultaneously
- If conflicts occur, the first device to sync wins
- All changes are preserved in Git history

### Repository Structure

```
repo/
├── pile/    ← active music files
└── trash/   ← deleted files (Git sync only, for recovery)
```

### PC Usage

Manage tracks directly from your terminal:

```bash
git pull
# add music files to pile/
git add . && git commit -m "add new tracks" && git push
```

Permanently delete:

```bash
rm trash/old_track.mp3
git add . && git commit -m "cleanup" && git push
```

## Supported Formats

- MP3 (.mp3)
- FLAC (.flac)
- OGG (.ogg)
- Opus (.opus)
- WAV (.wav)
- M4A / AAC (.m4a, .aac)

## Tips

- Name files clearly. The file name is what you search for.
- Use folders in `pile/` as albums.
- Sync often on multiple devices.
- Don't organize. Search.
