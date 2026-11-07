# Зависимости и спецификации ML (ASR и Diarization) — для Ярика (Back/Архитектура)

- **Отправитель:** Илья (ML — речь, голоса и время)
- **Кому:** Ярику (Back, сборка, contracts), Сюбику (QA, docs)
- **Согласовано с:** `PROJECT_PLAN.md` v3.0, `ILYA_ML.md` v2.0
- **Ветка:** `feat/ml-device-spike`

---

## 1. Точные ревизии внешних C++ библиотек (Git Submodules / CMake)

| Библиотека | Роль | Репозиторий / Источник | Точный тег / Commit SHA | Лицензия |
|---|---|---|---|---|
| **whisper.cpp** | ASR runtime | `https://github.com/ggerganov/whisper.cpp.git` | Tag `v1.7.1`<br>Commit: `ebca09a3d1033417b0c630bbbe607b0f185b1488` | MIT |
| **sherpa-onnx** | Diarization runtime | Официальный Android prebuilt v2.0.1 (via `scripts/fetch-native.sh` из PR #9) | Релиз `v2.0.1` (`c-api.h` + `libsherpa-onnx-c-api.so` + `libonnxruntime.so`) | Apache-2.0 |

### Способ подключения внешнего C++ кода:
- `whisper.cpp`: Git submodule в `external/whisper.cpp` (commit `ebca09a3d1033417b0c630bbbe607b0f185b1488`). `CMakeLists.txt` в `src/android/engine/asr` подключает сабмодуль через `add_subdirectory(...)` и линкует таргет `whisper`.
- `sherpa-onnx`: Официальный prebuilt Android-архив распаковывается в `external/sherpa-onnx-android/` скриптом `scripts/fetch-native.sh` (PR #9). `CMakeLists.txt` в `src/android/engine/diarization` линкует `sherpa-onnx-c-api` и `onnxruntime` как `IMPORTED` библиотеки и собирает `sherpa_onnx_jni.so`.

---

## 2. Требования к NDK, CMake и ABI

- **NDK Version:** `26.1.10909125` (r26b) или `25.2.9519653` (r25c).
- **CMake Version:** `3.22.1`.
- **Целевые ABI:** `arm64-v8a` (основная) и `x86_64` (для эмулятора CI).
- **C++ Standard:** `c++17`.

---

## 3. Реестр весов и размещение в приложении

По согласованному решению команды веса **не требуют adb push**:
- Модели поставляются внутри assets APK без сжатия (`androidResources { noCompress += listOf("bin", "onnx") }`).
- При первом запуске приложение копирует их в `context.filesDir/models/`.
- В конфигах `WhisperAsrConfig` и `SherpaOnnxDiarizationConfig` пути считаются относительно `filesDir` (по умолчанию `models/ggml-base.bin`, `models/sherpa-onnx-pyannote-segmentation-3-0.onnx`, `models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`).

### Модели в `models/manifest.json`:
- `ggml-base.bin`: 147 964 211 байт, SHA-256 `60ed5bc3dd14eea856493d334349b405782ddcaf00eec29073ba81a4d4ea8aec`
- `sherpa-onnx-pyannote-segmentation-3-0.onnx`: 6 059 288 байт, SHA-256 `893c52e411b439c2c8f85f3ce010a300da58908ec8e0f6c278fb7785beab4aa7`
- `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx`: 26 861 502 байт, SHA-256 `4b68079a4058f967ad7f5e8e3c54d398f51a44e5914fa6463994326588db2b90`
