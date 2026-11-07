# Q4: диагностика, batching и бюджет POSTPROCESS

7 ноября 2026, продолжение #6 в #23. Изменения только через feat/llm-qa-q4-budget; самостоятельного merge нет. Получен командный A51-прогон standalone 1d68a73: cold 71448ms / warm 15168ms, PSS 693MB, accepted/changed=true; общий batching/budget на телефоне ещё не проверен.

## Пункты 1–3: новый артефакт телефона

Исходники диагностического APK: `1d68a73658051ab302b03581405e946f36128a94`. Файл `llm-q4-raw-diagnostic.apk`:400819172 bytes, SHA256 `9aa2906ca79a30702b64d2265c0f8cef17cc12b2f62e0119dc2363adcc3c8da1`. Package `com.neurostenographer.llmspike.q4`, label «LLM Q4 RAW diagnostic». Устанавливается рядом с прежним spike. Доставка: LLM_Q4_RAW_DIAGNOSTIC.zip, внутри APK и INSTALL.txt.

Ручная сборка: Kotlin2.0.21/JDK17.0.17+10, D8 8.5.35, build-tools34.0.0, NDK26.1.10909125, CMake3.31.6. ARM64 native compile, Kotlin/D8 compile, signature v2/v3, zipalign, ZIP integrity, package/activity/ABI и SHA встроенной Q4 — PASS. Gradle assemble/lint standalone APK этим ручным процессом не заявляется. В APK нет INTERNET permission; raw logging доступен только на фиксированной вымышленной строке. Production callback по умолчанию null.

Проверена реальная metadata tokenizer.chat_template в Q4 GGUF: ChatML, enable_thinking=false → пустой think-prefill, EOS151645. Сохранены /no_think, greedy native sampler (без stochastic temperature) и llama_vocab_is_eog stop. Два русских few-shot примера отличаются от фразы A51. Cleanup удаляет think-блоки после raw callback, незавершённые мысли не выводятся как ответ.

Настоящий Linux Q4 ответ на фразу телефона:

```text
RAW_BEFORE_CLEANUP=мы не переносим встречу. Нужно 12 папок.
NORMALIZED=мы не переносим встречу. Нужно 12 папок.
DECISION=Decision(accepted=true, reason=null)
```

Русский набор из #13 с новым prompt:14/16 accepted,2 ошибки имён откатились; все14 принятых outputs получили пунктуацию, EN2/2 accepted.15 corrupt controls и6 допустимых format controls прошли. Это Linux, не качество/скорость Android. Исходный A51 cold12.4s/warm6s/PSS917MB — отдельный пользовательский замер старого APK, см. [A51_LLM_CHECKPOINT.md](A51_LLM_CHECKPOINT.md).

## Пункт 4: несколько реплик за вызов

LocalTranscriptPostprocessor по умолчанию объединяет до4 raw chunks в пакет длиной не более900 символов (включая разметку). Исходная реплика делится только по whitespace, обычный chunk до400 символов. Один chunk остаётся обычным scalar-запросом. Пакет имеет вид `[0] текст\n[1] текст`; это короткие transport ids, отображённые на исходные id. speakerId/времена/другие metadata в модель не передаются.

Ответы связываются по id, а не позиции. Перестановка строк допустима; пропуск/дубликат id, неизвестный id или плохая строка не переназначают текст другой реплике. Каждый raw chunk независимо проходит прежний TranscriptValidator. При ошибке любого chunk вся исходная Utterance откатывается к rawText; остальные валидные реплики сохраняют оформление. Итоговые id/speakerId/startMs/endMs/rawText, число и порядок строго проверяются. Диагностика — enum FORMAT_REJECTED/BATCH_FORMAT_REJECTED, без текста.

Реальный Q4 batching probe на8 русских репликах: **2 native calls,8/8 guard accepted,0 malformed/word rejections**, elapsed4918ms на Linux. Это не сравнение скорости телефона. Проверены да/нет, фраза A51, имена, ё, ведущие нули, числа и отрицания. Намеренные duplicate/missing/unknown/reordered ids проверены отдельными unit-тестами.

## Пункт 5: bounded POSTPROCESS и warning

DefaultTranscriptionPipeline ограничивает **только генерацию** после resolve/copy и EngineSpec.load, бюджетом `floor(audio.durationMs * 0.30)` (минимум1ms). Используется исходная длительность аудио, не сумма реплик. Собственный deadline даёт Completed с `displayText = rawText` для всех реплик и предупреждением в `TranscriptResult.warnings`: «Пунктуация и регистр не обработаны: исчерпан лимит времени LLM. Показан исходный текст.» (текущий экран уже выводит warnings пользователю); экспорт JSON по-прежнему не включает внутренние warnings.

Контракт process возвращает список атомарно, поэтому при timeout генерации не публикуются частичные результаты: все реплики возвращаются как rawText. Это сознательная безопасная стратегия, а не утверждение о сохранении ранее оформленных пакетов. Без timeout форматирование сохраняется построчно. Любая ошибка локальной LLM, включая копирование/загрузку, native/OOM и внутреннюю CancellationException при активной задаче, даёт Completed со всеми rawText и warning «Пунктуация не обработана: ошибка локальной LLM». Это fallback, а не успешный inference. Явная отмена пользователя остаётся Cancelled, а не Completed с warning.

Лимит кооперативный: JNI abort callback реагирует на отмену, затем pipeline ждёт безопасного освобождения модели. На реальном устройстве возможен небольшой scheduler/native-cleanup overhead после deadline; точный wall-time/пик RAM надо измерить на A51. Нельзя выдавать виртуальное время unit-теста за физический RTF.

LocalTranscriptPostprocessor теперь AutoCloseable: существующий stage.finally закрывает принадлежащий ему LlamaCppTextGenerator. Это предотвращает накопление native weights после timeout/success/cancel. Тест с настоящим Q4 проверяет, что после processor.close runtime больше не открывается; close идемпотентен.

## Валидация и интеграция

- LLM unit/JNI: scalar guard, think cleanup, batch ids/per-row fallback, отмена/reuse и ownership настоящего runtime.
- Pipeline unit: deadline → raw+warning/cleanup, подготовка/загрузка не расходуют бюджет генерации; ошибки LLM дают все rawText + warning, быстрый путь без warning, user cancel и чужая engine cancellation различаются. Для5s synthetic audio deadline проверяется на1500ms виртуального времени.
- Общий CI/Emulator проверяются на текущем SHA PR отдельно. Веса, SDK, downloaded runtime sources, signing key и APK не коммитятся.
- models/manifest.json по повторному поручению добавлен до merge #2: проверенные три ML-записи из #2 SHA84cd472 сохранены дословно, добавлена только Q4. После merge #2 main подтягивается обычным merge; дубликат Q4 не добавляется; app DI/общие contracts этим изменением не переписаны. Потребители pipeline и warning должны ревьюить core/pipeline.

Перед merge нужны Илья (LLM/native), Ярик (manifest/pipeline), Санёк (docs и потребитель pipeline). A51 raw/guard/PSS/time пунктов 1–3 получены, см. A51_LLM_CHECKPOINT.md; batching/budget затем проверяют на общем pipeline с реальным аудио без передачи кейса в логи.

## Уточнение перед merge #23

Для production EngineSpec.load использовать `LocalTranscriptPostprocessor.load(file, sha256)`:
фабрика выполняет checksum и native open до возврата процессора. Прямой конструктор runtime
сохраняет lazy load для standalone spike/тестов и не предназначен для load callback pipeline.
Связывание app DI принадлежит back; app DI этим PR не изменён.
Таймаут оборачивает только `processor.process`, подготовка и освобождение runtime вне него.
Контракт возвращает список атомарно: при ошибке любой генерации отбрасывается весь частичный результат.
Тесты проверяют 2s copy + 3s load + 1.4s generation → успех и тот же load + зависшую generation → timeout на 6.5s виртуального времени.

Проверки этой правки: pipeline **24/24**, LLM/JNI **34/34** с реальным Q4
(`LLM_SMOKE_MODEL=/tmp/syubik-ru-models/Q4_K_M.gguf`), Python **28/28**,
`bash scripts/check-forbidden.sh` и `git diff --check` — PASS.
JVM-наборы скомпилированы Kotlin 2.0.21/JDK 17 и запущены через JUnitCore.
Для pipeline извлечены без изменения platform-free объявления ModelStore и
AudioPreparationException из Android-исходников; Android ModelStore/AudioPreparer
этим JVM-прогоном не проверяются. Полный Gradle/Android CI — отдельные проверки нового SHA.
Новый native тест вызывает prepare(), удаляет временную ссылку на вес и успешно
генерирует через уже загруженный handle: загрузка действительно завершена до generate.
Ошибки copy/load/inference/internal cancellation/native/OOM/close, неизменность всех DTO
при fallback и отмена во время подготовки и генерации покрыты тестами.
Агент физический A51 не запускал и APK новых budget-правок не собирал; тестировщик подтвердил отдельный standalone 1d68a73, см. A51_LLM_CHECKPOINT.md.
