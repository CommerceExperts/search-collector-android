#!/usr/bin/env bash
set -euo pipefail

ADB="$HOME/Android/Sdk/platform-tools/adb"
DEST="${1:-$HOME/Downloads/emulator}"

mkdir -p "$DEST"

echo "Pulling /sdcard/Download/ → $DEST"
"$ADB" pull /sdcard/Download/ "$DEST"

echo "Files:"
ls -lh "$DEST/Download/"
