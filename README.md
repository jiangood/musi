# Literal Musi

Listen. Search. Move on.

A minimalist local music player with Git sync.

<p>
  <img src="https://img.shields.io/badge/Android-8%2B-blue">&nbsp;<img src="https://img.shields.io/badge/license-MIT-lightgrey">
</p>

## Why?

Remember when you just had music files?

No streaming. No recommendations. No algorithm telling you what to like.

Just folders of songs you chose.

Music services have become noisy — playlists shuffled by algorithms, recommendations you didn't ask for, songs that disappear when licenses expire.

Your music should be yours.

## The Idea

Literal Musi is built on a simple principle:

**Put music in. Listen. Search when you want something. Delete when you don't.**

No playlists. No ratings. No algorithmic recommendations. No cloud.

Just files.

## Features

- **Listen**: Simple player with playback controls
- **Search**: Search by file name across all music
- **Sync**: Git sync (GitHub) to keep your library in version control
- **Minimal**: No databases, no metadata scraping, no album art fetchers

## How it works

Music is stored in a simple directory structure:

```
repo/
├── pile/    ← music files
└── trash/   ← deleted files (Git sync only, for recovery)
```

No databases. No music library XML. Just files.

## Why no playlists?

Because you don't browse playlists.

You listen to albums. You search for that one track. You shuffle a folder.

Playlists are just saved search queries pretending to be something more.

If you want a playlist, make a folder.

## Why no metadata?

ID3 tags, album art, genre sorting — all of that requires a database, a scanner, and ongoing maintenance.

This app treats music as files. The file name is the title. The folder is the album.

If you need rich metadata, use a different player.

## Who is this for?

This app may appeal to people who:

- own their music as files
- want version control over their music library
- use search instead of browsing
- don't need recommendations to tell them what to listen to
- prefer simple, predictable tools
- are comfortable with Git-based workflows

## Philosophy

Literal Musi is not about organizing your music library.

It's about **not needing to**.

The app is intentionally simple. If a feature requires explaining in multiple steps, it probably doesn't belong here.

## Usage

### Adding Music

1. Copy music files into the `pile/` directory
2. Run `git add` and `git push` (or sync from the app)
3. Your music is available on all connected devices

### Multi-device Sync

Git Sync keeps your music synchronized across devices:

- Syncs automatically on launch
- Don't modify the same file on multiple devices simultaneously
- If conflicts occur, the first device to sync wins
- All changes are preserved in Git history

## Architecture

See [Architecture Guide](./docs/ARCHITECTURE.md) for details on project structure, data flow, and conventions.

## Development

- Kotlin / Jetpack Compose
- Target: Android 8.0+
- No Google APIs. No Firebase. No tracking.

This app was built with substantial assistance from AI.

## License

MIT

## PC Scripts

The `scripts/` directory has a few extras for terminal use:

- `new-track.sh` — copy a music file into pile/
- `search-music.sh` — search music files with fzf
- `sync-music.sh` — sync pile with remote repository
- `literalmusi.vim` — Vim integration (list / search)
- `literalmusi.lua` — Neovim integration via fzf-lua

See [User Guide](./docs/USER_GUIDE.md) for details.
