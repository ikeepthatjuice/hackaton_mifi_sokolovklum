# Зависимости и спецификации ML (ASR и Diarization) — для Ярика (Back/Архитектура)

- **Отправитель:** Илья (ML — речь, голоса и время)
- **Кому:** Ярику (Back, сборка, contracts), Сюбику (QA, docs)
- **Согласовано с:** `PROJECT_PLAN.md` v3.0, `ILYA_ML.md` v2.0
- **Ветка:** `feat/ml-device-spike`

---

## 1. Точные ревизии внешних C++ библиотек (Git Submodules / CMake)

Никаких плавающих веток `main` или `latest`. Закрепляем точные релизные теги и commit hash:

| Библиотека | Роль | Репозиторий / Источник | Точный тег / Commit SHA | Лицензия |
|---|---|---|---|---|
| **whisper.cpp** | ASR runtime | `https://github.com/ggerganov/whisper.cpp.git` | Tag `v1.7.1`<br>Commit: `18503b8782bbbb5e8e3d06969562768565b9bf90` | MIT |
| **sherpa-onnx** | Diarization & VAD runtime | `https://github.com/k2-fsa/sherpa-onnx.git` | Tag `v1.10.30`<br>Commit: `803a6bc5594e9f73f553d1ff374bc73a985d898a` | Apache-2.0 |
| **onnxruntime (Android)** | Dependency for sherpa-onnx | Maven `com.microsoft.onnxruntime:onnxruntime-android:1.18.0` | Версия 1.18.0 | MIT |

### Способ подключения внешнего C++ кода:
Рекомендуется оформить как git submodule в папке `external/`:
```bash
git submodule add https://github.com/ggerganov/whisper.cpp.git external/whisper.cpp
cd external/whisper.cpp && git checkout 18503b8782bbbb5e8e3d06969562768565b9bf90 && cd ../..

git submodule add https://github.com/k2-fsa/sherpa-onnx.git external/sherpa-onnx
cd external/sherpa-onnx && git checkout 803a6bc5594e9f73f553d1ff374bc73a985d898a && cd ../..
```

---

## 2. Требования к NDK, CMake и ABI

- **NDK Version:** `26.1.10909125` (r26b) или `25.2.9519653` (r25c).
- **CMake Version:** `3.22.1` (штатная из Android SDK).
- **Целевые ABI:** `arm64-v8a` (основная) и `x86_64` (для Android эмулятора).
  - *Замечание:* 32-битный `armeabi-v7a` исключён (недостаточно виртуальной памяти для модели 150 МБ + граф вычислений, отсутствие FP16 NEON).
- **C++ Standard:** `c++17`.
- **Флаги компиляции C++:** `-O3 -fvisibility=hidden`.

### ВАЖНО: Решение конфликта GGML между whisper.cpp и llama.cpp
Поскольку Сюбик использует `llama.cpp` для локальной LLM, а ASR использует `whisper.cpp`, обе библиотеки имеют внутри исходники GGML (`ggml.c`).
Чтобы избежать ошибки линковки `duplicate symbol: ggml_*` в едином APK:
1. В `CMakeLists.txt` модулей `engine/asr` флаг `-fvisibility=hidden` гарантирует, что символы GGML не экспортируются из `libwhisper_jni.so`.
2. Экспортируются только JNI-функции (`JNIEXPORT`).
3. Аналогично Ярику необходимо применить `-fvisibility=hidden` к `llama_jni`.

---

## 3. Фрагмент для единого `gradle/libs.versions.toml` (для внесения Яриком)

```toml
[versions]
coroutines = "1.8.0"
junit = "4.13.2"
onnxruntime = "1.18.0"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { module = "junit:junit", version.ref = "junit" }
onnxruntime-android = { module = "com.microsoft.onnxruntime:onnxruntime-android", version.ref = "onnxruntime" }
```

---

## 4. Реестр весов и скрипт доставки (`models/manifest.json`)

Все веса описаны в `models/manifest.json`:
- **`ggml-base.bin`**: 147 964 211 байт, SHA-256 `60ed5bc3dd14eea856493d334349b405782ddcaf00eec29073ba81a4d4ea8aec`
- **`silero_vad.onnx`**: 1 827 490 байт, SHA-256 `7a3563969bcbe63cd3187b8f04746f34316279f6e6aa45550dfb0b92d6e4b854`
- **`3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx`**: 28 941 235 байт, SHA-256 `3cb40ff8d1796d11f6c4d7ecfef0f74577881c1626f2f01fbf9f42d2a45a31a9`

Для загрузки моделей подготовлены скрипты:
- `scripts/fetch-models.sh` (Linux/macOS/Bash)
- `scripts/fetch-models.ps1` (Windows PowerShell)

Скрипты загружают веса в локальный каталог и автоматически валидируют SHA-256. В репозиторий веса не коммитятся.

---

## 5. Доставка моделей на устройство Android

Для локального запуска и проверки приложения без сети:
```bash
adb shell mkdir -p /data/local/tmp/models/
adb push local_models/ggml-base.bin /data/local/tmp/models/
adb push local_models/silero_vad.onnx /data/local/tmp/models/
adb push local_models/3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx /data/local/tmp/models/
```
В приложении пути передаются в конфигурацию `WhisperAsrConfig` и `SherpaOnnxDiarizationConfig`.

