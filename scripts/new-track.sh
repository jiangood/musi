#!/bin/bash
# new-track.sh - copy a music file into pile/
# Usage: ./new-track.sh /path/to/music.mp3

cd "$(dirname "$0")/../pile" || exit 1

if [ -n "$1" ]; then
  cp "$1" .
  echo "Copied $(basename "$1") to pile/"
else
  echo "Usage: $0 /path/to/music/file"
  exit 1
fi
