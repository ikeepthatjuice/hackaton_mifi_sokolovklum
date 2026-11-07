# Настройка и сборка

Черновик Q6, 6 ноября 2026. Проверенный снимок main: `9f01387cf74991099bb6c4e68c2fe8b1b2230293`. Команды ниже разделяют общую сборку и изолированный LLM spike из PR #6. Общая интеграция engines, запуск на физическом телефоне и итоговый release APK ещё не подтверждены этим документом.

## Инструменты

Linux/macOS, Git, Bash, Python 3 (QA использует стандартную библиотеку), JDK 17. Android SDK: platform 34, build-tools 34.0.0, platform-tools (`adb`). Для LLM native: NDK 26.1.10909125 и SDK CMake 3.22.1. Gradle 8.9 закреплён wrapper; AGP 8.5.2 и Kotlin 2.0.21 — в конфигурации проекта. Windows/Bash здесь не проверялся.

Установите Android SDK через Android Studio SDK Manager. Выберите перечисленные версии NDK и CMake в Show Package Details. Задайте `JAVA_HOME` и `ANDROID_HOME` путями вашей установки и добавьте `$ANDROID_HOME/platform-tools` в PATH. `local.properties`, SDK и кэши не коммитить.

## Чистый clone и общая сборка

```bash
git clone https://github.com/ikeepthatjuice/hackaton_mifi_sokolovklum.git
cd hackaton_mifi_sokolovklum
git switch -c feat/llm-qa-local-check origin/main
git status --short --branch
bash scripts/check-environment.sh
bash scripts/check-forbidden.sh
(cd src/android && ./gradlew --no-daemon assembleDebug test lint)
python3 -m unittest discover -s quality/tests
python3 quality/check_export.py quality/fixtures/synthetic_export.json
```

`check-environment.sh` также проверяет наличие native toolchain, даже если текущая общая сборка ещё не подключает engines. Текущий debug APK: `src/android/app/build/outputs/apk/debug/app-debug.apk`. Успешная сборка bootstrap сама по себе не подтверждает ASR/LLM в приложении.

## fetch-native.sh и fetch-models.sh

На указанном снимке main **нет** `scripts/fetch-native.sh`, `scripts/fetch-models.sh` и `models/manifest.json`. Это блокер воспроизводимой общей сборки с моделями; владелец — Ярик, native-пины согласует Илья. Не создавать собственные несовместимые копии этих скриптов. После их PR сюда нужно внести точные аргументы, выходные пути и SHA-проверки и повторить чистую сборку.

Для отдельного LLM spike уже существует модульный скрипт `src/android/engine/llm/fetch-runtime.sh` в PR #6: он получает llama.cpp по commit и проверяет чистоту tracked sources. Это не общий fetch всех движков. Веса скачиваются отдельным явным шагом ниже; во время inference загрузок нет.

## Отдельный LLM APK из PR #6

В отдельном рабочем каталоге проверьте ветку, чтобы не переключать незакоммиченные изменения:

```bash
git fetch origin feat/llm-qa-model
git worktree add ../llm-device-spike origin/feat/llm-qa-model
cd ../llm-device-spike
bash src/android/engine/llm/fetch-runtime.sh
python3 - <<'PY'
import hashlib, pathlib, urllib.request
root = pathlib.Path('src/android/engine/llm')
import json
m = json.loads((root / 'dependencies.json').read_text())['model']
p = root / 'spike/src/main/assets' / m['file']
tmp = p.with_suffix('.partial')
try:
    with urllib.request.urlopen(m['source'], timeout=60) as response, tmp.open('wb') as output:
        h = hashlib.sha256()
        size = 0
        while block := response.read(1024 * 1024):
            output.write(block)
            h.update(block)
            size += len(block)
    assert size == m['size_bytes'], 'model size mismatch'
    assert h.hexdigest() == m['sha256'], 'model SHA mismatch'
    tmp.replace(p)
finally:
    tmp.unlink(missing_ok=True)
PY
(cd src/android && ./gradlew -p engine/llm/spike --no-daemon --max-workers=4 assembleDebug lintDebug)
```

APK: `src/android/engine/llm/spike/build/outputs/apk/debug/llm-device-spike-debug.apk`. Проверить SHA своего APK (`shasum -a 256 <apk>` на macOS или `sha256sum <apk>` на Linux); разные debug signing keys могут давать разные APK SHA. Исходный отчёт PR #6 указывает 652849208 bytes и SHA `ec9bb68716dacbaf02bd2c834ac96325d7b19f7dfccdbd5f826e3f1bf429dc83`; это идентификатор прежнего артефакта, не обещание идентичности новой сборки.

Требуется ARM64, Android 8/API 26+. Вес Q8_0 занимает 639446688 bytes и копируется из APK в private files при первом старте; предусмотрите место для установки и дополнительной копии. Фактический пик RAM пока не измерен. Приложение `com.neurostenographer.llmspike` не включает whisper и не подключается к общему app. Совместную ggml-линковку согласуют Ярик, Илья и Сюбик после сборки #2.

## Установка и офлайн-проверка

```bash
adb devices
adb install -r src/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.neurostenographer.app/.MainActivity
```

Для LLM spike используйте отдельный APK и activity из [LLM_DEVICE_RUN.md](LLM_DEVICE_RUN.md). Не путать запуск bootstrap UI с запуском модели. Финальная сдача должна работать после обычной установки APK без adb, сети и дополнительной ручной доставки весов. Размер APK и допустимость весов внутри пока требуют ответа организаторов.

QA: [Q1_TEST_PLAN.md](Q1_TEST_PLAN.md). На устройстве проверять только синтетику или явно разрешённые материалы. В git, PR, CI artifacts и общие логи не помещать данные кейса, расшифровки, веса и ключи.

## Приёмка инструкции

Ярик/Claude проверяет toolchain и будущие общие fetch-скрипты, Илья — native/ASR/diarization, Санёк — установку/запуск UI. Чистый clone и физическое устройство по этой инструкции пока NOT RUN. Перед сдачей дополнить фактическими SHA release APK, моделей, телефона, cold/warm времени, RAM и результатом авиарежима.
