#!/usr/bin/env bash
# Только проверяет инструменты, ничего не устанавливает и не меняет.
# Требуемые версии должны совпадать с docs/SETUP.md и build-файлами.
set -uo pipefail

NEED_JAVA=17
NEED_COMPILE_SDK=34
NEED_NDK=26.1.10909125
NEED_CMAKE=3.22.1

fail=0
ok()   { echo "OK    $1"; }
miss() { echo "MISS  $1"; fail=1; }

# "1.8.0_x" → 8, "17.0.x" → 17
java_ver=$(java -version 2>&1 | head -1 | sed -E 's/.*version "(1\.)?([0-9]+).*/\2/')
[ "$java_ver" = "$NEED_JAVA" ] && ok "JDK $java_ver" || miss "JDK $NEED_JAVA (сейчас: ${java_ver:-нет})"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
  miss "ANDROID_HOME не задан или каталога нет"
else
  ok "Android SDK: $sdk"
  [ -d "$sdk/platforms/android-$NEED_COMPILE_SDK" ] && ok "platforms;android-$NEED_COMPILE_SDK" || miss "platforms;android-$NEED_COMPILE_SDK"
  [ -d "$sdk/ndk/$NEED_NDK" ] && ok "ndk;$NEED_NDK" || miss "ndk;$NEED_NDK (нужен только для native-модулей engine/*)"
  [ -d "$sdk/cmake/$NEED_CMAKE" ] && ok "cmake;$NEED_CMAKE" || miss "cmake;$NEED_CMAKE (нужен только для native-модулей engine/*)"
fi

command -v adb >/dev/null && ok "adb" || miss "adb (нужен для установки на устройство)"

exit $fail
