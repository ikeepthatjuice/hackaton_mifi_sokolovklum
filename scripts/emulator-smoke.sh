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

# Cached AVD keeps the app and module test APKs from a previous run, signed with that runner's debug key.
adb wait-for-device
# ADB can be ready while System UI is still booting. Its ANR dialog can steal
# focus from FrontFlowTest even when the app itself has started successfully.
boot_deadline=$((SECONDS + 120))
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
  if (( SECONDS >= boot_deadline )); then
    echo "EMULATOR BOOT TIMED OUT"
    exit 1
  fi
  sleep 2
done
adb shell settings put global hide_error_dialogs 1 || exit 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || exit 1
adb shell input keyevent KEYCODE_HOME || exit 1
sleep 10
until adb shell pm path android >/dev/null 2>&1; do sleep 2; done # package manager ready; -u also lists packages kept after a per-user uninstall
for p in $(adb shell pm list packages -u com.neurostenographer | tr -d '\r' | sed 's/^package://'); do
  echo "uninstall stale $p: $(adb uninstall "$p" 2>&1 | tr -d '\r')"
done
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
