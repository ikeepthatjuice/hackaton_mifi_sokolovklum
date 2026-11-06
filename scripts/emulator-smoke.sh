#!/usr/bin/env bash
# Smoke on a running emulator: install, start, check the process is alive, run instrumented tests.
# Called by .github/workflows/emulator.yml from src/android. It is a separate file because
# android-emulator-runner runs each `script:` line in its own shell, so variables between lines are lost.
set -uo pipefail

PKG=com.neurostenographer.app
ROOT=$(git rev-parse --show-toplevel)
MODELS_SRC="$ROOT/local_models"
# Test-only location; the release APK ships weights as assets.
MODELS_DST="/sdcard/Android/data/$PKG/files/models"

adb logcat -c
# Save logcat on every exit, including a failed installDebug.
trap 'adb logcat -d > "$ROOT/emulator-logcat.txt"' EXIT

./gradlew --no-daemon installDebug || exit 1

adb shell am start -W -n "$PKG/.MainActivity"
sleep 5
if ! adb shell pidof "$PKG" >/dev/null; then
  echo "APP CRASHED ON START"
  exit 1
fi
echo "SMOKE OK: $PKG is running"

if [ -d "$MODELS_SRC" ]; then
  adb shell mkdir -p "$MODELS_DST"
  adb push "$MODELS_SRC/." "$MODELS_DST/"
fi

test_failed=0
./gradlew --no-daemon connectedDebugAndroidTest || test_failed=1
exit $test_failed
