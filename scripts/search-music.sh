#!/bin/bash
# search-music.sh - search music files with fzf
# Usage: ./search-music.sh [query]
#
# Requires: fzf

PILE="$(dirname "$0")/../pile"
cd "$PILE" || exit 1

if [ -n "$1" ]; then
  selected=$(find . -type f \( -name '*.mp3' -o -name '*.flac' -o -name '*.ogg' -o -name '*.opus' -o -name '*.wav' -o -name '*.m4a' -o -name '*.aac' \) | sed 's|^\./||' | fzf --query="$1")
else
  selected=$(find . -type f \( -name '*.mp3' -o -name '*.flac' -o -name '*.ogg' -o -name '*.opus' -o -name '*.wav' -o -name '*.m4a' -o -name '*.aac' \) | sed 's|^\./||' | fzf --preview 'file {}')
fi

[ -n "$selected" ] && echo "$selected"
