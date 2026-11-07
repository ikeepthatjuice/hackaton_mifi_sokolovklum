# A51: исходный замер и повторная Q4-диагностика

7 ноября 2026. Данные исходного прогона сообщил участник команды; агент лично телефон не запускал.

| Параметр | Сообщённый результат |
|---|---|
| Телефон | Samsung A51, Android 13 |
| RAM | 3.5 ГБ, как сообщил тестировщик |
| Режим | Авиарежим |
| Runtime / crash | Inference завершён, крэшей нет |
| Cold / warm, 7 слов | 12.4 с / 6.0 с |
| Пик PSS | около 917 МБ |
| Вход / ответ | «мы не переносим встречу нужно 12 папок», без изменений |
| SHA установленного APK | Не предоставлен; не приписываем прежний APK SHA этому замеру |

Старый SpikeActivity вызывал engine.generate напрямую и показывал ответ до production postprocessor. Если проверялся именно переданный spike, дословное повторение — ответ модели, а не действие валидатора.

## Новый отдельный APK: сначала пункты 1–3

Package `com.neurostenographer.llmspike.q4`, label «LLM Q4 RAW diagnostic». Устанавливается рядом с прежним spike. Только фиксированная вымышленная строка, ввода реального текста нет.

- Q4_K_M:396704928 bytes; SHA256 `2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9`. dependencies.json содержит эту запись; models/manifest.json исключён из diff #23 до merge #2. Затем в manifest из main добавляется только Q4, без изменений проверенных ML-записей.
- Проверен tokenizer.chat_template в реально скачанном GGUF: ChatML, eos151645, enable_thinking=false добавляет пустой `<think>\n\n</think>\n\n`. Этот префикс сохранён; `/no_think` задан отдельно. Native sampler — настоящий greedy, остановка по llama_vocab_is_eog. Добавлен русский prompt с двумя другими примерами (не тестовая фраза телефона).
- Raw callback вызывается до think-cleanup и валидатора. В production callback по умолчанию отсутствует. В изолированном APK он пишет только фиксированную синтетику в `LLM_SPIKE_RAW` и показывает raw на экране. `LLM_SPIKE_GUARD` сообщает accepted/reason/changed; итоговый displayText отдельно.
- Полные think-блоки удаляются; незавершённая мысль не считается видимым ответом. Если после очистки слова нарушены, guard возвращает исходный текст.

```bash
adb install -r llm-q4-raw-diagnostic.apk
adb shell am start -n com.neurostenographer.llmspike.q4/engine.llm.spike.SpikeActivity
adb logcat -s LLM_SPIKE_RAW:I LLM_SPIKE_GUARD:I AndroidRuntime:E
adb shell dumpsys meminfo com.neurostenographer.llmspike.q4
```

Нажать «Запустить локальную LLM» при авиарежиме/Wi-Fi off. Зафиксировать SHA нового APK, raw, очищенный ответ, accepted/reason, final displayText, cold/warm и PSS. Полный cold замер включает копирование assets, UI generate time — только generate/checksum/load/decode.

Результат повторного A51-прогона пока не получен. Наличие знаков и сохранность слов должны быть подтверждены новым выводом, а не заменой строки в UI.
