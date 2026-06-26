#!/usr/bin/env bash
# One-command Android release: bump versionCode -> build signed AAB -> publish to Play.
#
#   ./scripts/release.sh "What's new text for this release"
#
# Requires (set up once):
#   - local.properties with RELEASE_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD (gitignored)
#   - service-account JSON at ~/keys/play-publisher.json  (override with PLAY_KEY=)
#   - publish venv at ~/.playpub  (override with PLAY_PY=)
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

NOTES="${1:-Minor improvements and fixes.}"
PLAY_KEY="${PLAY_KEY:-$HOME/keys/play-publisher.json}"
PLAY_PY="${PLAY_PY:-$HOME/.playpub/bin/python}"
GRADLE_FILE="app/build.gradle.kts"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ -z "${JAVA_HOME:-}" ]; then
  for j in "$HOME/dev-tools/jdk-17.0.19+10/Contents/Home" "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
    if [ -n "$j" ] && [ -x "$j/bin/java" ]; then export JAVA_HOME="$j"; break; fi
  done
fi
echo "JAVA_HOME=$JAVA_HOME"

# --- bump versionCode ---
cur=$(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+' | head -1)
next=$((cur + 1))
sed -i '' "s/versionCode = $cur/versionCode = $next/" "$GRADLE_FILE"
echo "versionCode: $cur -> $next"

# --- build signed release bundle ---
echo "Building signed AAB..."
./gradlew bundleRelease -q
AAB="app/build/outputs/bundle/release/app-release.aab"
[ -f "$AAB" ] || { echo "ERROR: $AAB not produced"; exit 1; }

# --- publish to Play ---
echo "Publishing to Play..."
PLAY_KEY="$PLAY_KEY" AAB="$PWD/$AAB" NOTES="$NOTES" "$PLAY_PY" scripts/play_publish.py

# --- record the version bump ---
git add "$GRADLE_FILE"
git commit -q -m "Release: bump Android versionCode to $next" \
  -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" || true
echo "Released versionCode $next. Run 'git push' when ready."
