#!/usr/bin/env bash
set -euo pipefail

ADB="$HOME/Android/Sdk/platform-tools/adb"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

echo "Building APK…"
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" :demo-app:assembleDebug

APK="$REPO_ROOT/demo-app/build/outputs/apk/debug/demo-app-debug.apk"

echo "Installing $APK…"
"$ADB" install -r "$APK"

echo "Done."
