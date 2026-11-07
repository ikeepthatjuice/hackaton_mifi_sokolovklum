# Q4 RAW diagnostic — отдельный Android spike

Package `com.neurostenographer.llmspike.q4`, label «LLM Q4 RAW diagnostic», ARM64/API26+. Это отдельное приложение рядом с прежним Q8 spike, без whisper и сети. Ввод текста отсутствует: проверяется только фиксированная вымышленная фраза.

JDK17, SDK34/build-tools34.0.0, NDK26.1.10909125, CMake3.22.1, Gradle8.9. Из корня репо получить runtime: `bash src/android/engine/llm/fetch-runtime.sh`. Модель загрузить явно по `engine/llm/dependencies.json`, сверить размер396704928 bytes и SHA256 `2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9`, положить локально в `spike/src/main/assets/Qwen3-0.6B-Q4_K_M.gguf`. Файл игнорируется git. Имя исходного URL Qwen3_0.6B.Q4_K_M.gguf отличается от локального имени: использовать поле file из dependencies.json.

```bash
(cd src/android && ./gradlew -p engine/llm/spike assembleDebug lintDebug)
adb install -r src/android/engine/llm/spike/build/outputs/apk/debug/llm-device-spike-debug.apk
adb shell am start -n com.neurostenographer.llmspike.q4/engine.llm.spike.SpikeActivity
adb logcat -s LLM_SPIKE_RAW:I LLM_SPIKE_GUARD:I AndroidRuntime:E
```

На телефоне включить авиарежим/Wi-Fi off, открыть приложение, нажать «Запустить локальную LLM». На экране отдельные raw до think cleanup, очищенный ответ, решение валидатора и итоговый displayText. Лог содержит только фиксированную синтетику; production runtime по умолчанию не пишет сырой текст. Старый spike показывал прямой ответ engine.generate без валидатора: дословное повторение там не объясняется guard fallback.

Промпт: русский, два других few-shot примера; canonical Qwen3 ChatML + /no_think + пустой think-prefill, native greedy и EOG stop. Неполные think-блоки не выдаются за ответ. Полный отчёт и протокол повторного A51: docs/A51_LLM_CHECKPOINT.md.

Linux Q4 v2: тестовая фраза реально стала «мы не переносим встречу. Нужно 12 папок.», guard принял. Расширенный русский набор14/16 принят,2 искажения имён откатились; это не результат телефона. Новый APK нужно прогнать на A51, записав SHA, raw/guard/display, cold/warm и PSS. Бюджет полного pipeline и batching проверяются отдельно от этого runtime spike.

## KV / 12-row diagnostic update

See root docs/LLM_KV_PERFORMANCE.md. Runtime now caches only the fixed prompt prefix;
use the same button repeatedly for warm measurement. New synthetic batch button tests
12 rows with an 18-second generation budget; prepare/load is timed separately.
APK target package remains com.neurostenographer.llmspike.q4 and can update the old
standalone diagnostic with the same signing key. Fixed synthetic text only, no INTERNET.
New APK SHA256:606483934febe6e493f856fc637abd5b2adfdff3b6fd9b3ffb4b442d80f92a12.
Do not claim A51 performance until the new build has been tested on the device.
