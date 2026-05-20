# User Guide

## Getting Started

1. Copy music files into the pile/ directory
2. Open the app
3. Tap a track to play

That's it.

## Search

Type in the search bar. Results show matching tracks by file name.

## Deletion

Swipe left on any track to delete.

Deleted tracks are moved to trash/ in your remote repository. They disappear from the app immediately.

There is no local trash. There is no restore button.

## GitHub Sync

### Setup

1. Create a GitHub repository (private recommended)
2. Generate a Personal Access Token:
   - Go to github.com > Settings > Developer settings > Personal access tokens > Tokens (classic)
   - Click "Generate new token (classic)"
   - Give it a name like "Literal Musi"
   - Select the "repo" scope (Full control of private repositories)
   - Click "Generate token"
   - Copy the token immediately (you won't see it again)
3. In the app, go to Settings > Connect GitHub
4. Paste your token and enter your repository name (e.g., username/music)

### Repository Structure
```
repo/
├── pile/
│   ├── 01_song.mp3
│   └── album_track.flac
└── trash/
    └── old_track.ogg
```

### Multi-device

Sync before editing on a new device.

Don't modify the same file on multiple devices simultaneously.

Deletion propagates to all devices.

### PC Usage

Manage tracks directly:
```bash
git pull
# add files to pile/
git add . && git commit -m "update" && git push
```

Permanently delete:
```bash
rm trash/old_track.mp3
git add . && git commit -m "cleanup" && git push
```

## Tips

- Name files clearly. The file name is what you search for.
- Use folders as albums.
- Sync often on multiple devices.
