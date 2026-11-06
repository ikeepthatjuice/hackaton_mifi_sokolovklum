# Зависимости и спецификации ML (ASR и Diarization) — для Ярика (Back/Архитектура)

- **Отправитель:** Илья (ML — речь, голоса и время)
- **Кому:** Ярику (Back, сборка, contracts), Сюбику (QA, docs)
- **Согласовано с:** `PROJECT_PLAN.md` v3.0, `ILYA_ML.md` v2.0
- **Ветка:** `feat/ml-device-spike`

---

## 1. Точные ревизии внешних C++ библиотек (Git Submodules / CMake)

| Библиотека | Роль | Репозиторий / Источник | Точный тег / Commit SHA | Лицензия |
|---|---|---|---|---|
| **whisper.cpp** | ASR runtime | `https://github.com/ggerganov/whisper.cpp.git` | Tag `v1.7.1`<br>Commit: `18503b8782bbbb5e8e3d06969562768565b9bf90` | MIT |
| **sherpa-onnx** | Diarization & VAD runtime | `https://github.com/k2-fsa/sherpa-onnx.git` | Tag `v1.10.30`<br>Commit: `803a6bc5594e9f73f553d1ff374bc73a985d898a` | Apache-2.0 |
| **onnxruntime (Android)** | Dependency for sherpa-onnx | Maven `com.microsoft.onnxruntime:onnxruntime-android:1.18.0` | Версия 1.18.0 | MIT |

### Способ подключения внешнего C++ кода:
- `whisper.cpp`: Git submodule в `external/whisper.cpp` (commit `18503b8782bbbb5e8e3d06969562768565b9bf90`). `CMakeLists.txt` в `src/android/engine/asr` компилирует исходники `whisper.cpp` и `ggml` напрямую в `libwhisper_jni.so`.
- `sherpa-onnx`: официальная prebuilt Android-сборка `v1.10.30` и maven-зависимость `onnxruntime-android` из `libs.versions.toml`.

---

## 2. Требования к NDK, CMake и ABI

- **NDK Version:** `26.1.10909125` (r26b) или `25.2.9519653` (r25c).
- **CMake Version:** `3.22.1`.
- **Целевые ABI:** `arm64-v8a` (основная) и `x86_64` (для эмулятора CI).
- **C++ Standard:** `c++17`.
- **Флаги компиляции C++:** `-O3 -fvisibility=hidden`.

### ОТКРЫТЫЙ ВОПРОС: Потенциальный конфликт GGML между whisper.cpp и llama.cpp
Сюбик использует `llama.cpp` для текстовой модели, а ASR использует `whisper.cpp`. Обе библиотеки содержат реализацию GGML.
> **Важно:** Решает ли флаг `-fvisibility=hidden` конфликт между JNI-библиотеками в Android динамическом загрузчике — **открытый вопрос**, на устройстве это пока не доказано.
> Будем тестировать и согласовывать решение совместно (Ярик, Сюбик, Илья) на этапе совместной сборки (возможно, потребуется единый статический GGML runtime или переименование пространств имён).

---

## 3. Реестр весов и размещение в приложении

По согласованному решению команды веса **не требуют adb push**:
- Модели поставляются внутри assets APK без сжатия (`androidResources { noCompress += listOf("bin", "onnx") }`).
- При первом запуске приложение копирует их в `context.filesDir/models/`.
- В конфигах `WhisperAsrConfig` и `SherpaOnnxDiarizationConfig` пути считаются относительно `filesDir` (по умолчанию `models/ggml-base.bin`, `models/silero_vad.onnx`, `models/3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx`).

### Модели в `models/manifest.json`:
- `ggml-base.bin`: 147 964 211 байт, SHA-256 `60ed5bc3dd14eea856493d334349b405782ddcaf00eec29073ba81a4d4ea8aec`
- `silero_vad.onnx`: 1 827 490 байт, SHA-256 `7a3563969bcbe63cd3187b8f04746f34316279f6e6aa45550dfb0b92d6e4b854`
- `3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx`: 28 941 235 байт, SHA-256 `3cb40ff8d1796d11f6c4d7ecfef0f74577881c1626f2f01fbf9f42d2a45a31a9`

---

## 4. Входной аудиопоток (PCM)

Движки ожидают входной файл от `core/audio` (B3):
- Формат: `float32` Little-Endian, mono, 16 000 Hz, диапазон samples `[-1.0, 1.0]`.
- Чтение в `WhisperAsrEngine` реализовано потоково (буфер 64 КБ), предотвращая перерасход heap памяти на длительных записях.
