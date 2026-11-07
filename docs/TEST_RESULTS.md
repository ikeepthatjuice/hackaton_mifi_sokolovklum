# Результаты проверок

Сводка на 7 ноября 2026. Машиночитаемые данные: [artifacts/test-results/results.csv](../artifacts/test-results/results.csv); [схема и статусы](../artifacts/test-results/README.md). Условия/границы измерений — [PROTOCOL.md](PROTOCOL.md), выводы и ограничения — [LIMITATIONS.md](LIMITATIONS.md).

## Физический A51: JFK, английский

Источник: отчёт Ярика [PR #24](https://github.com/ikeepthatjuice/hackaton_mifi_sokolovklum/pull/24), прочитанный на SHA f735cbf8d3d22308f779ca92519e1d1ad73b03d9. Это снимок источника отчёта, **не подтверждённый SHA протестированного debug APK**. Условия: Android 13, авиарежим ON, Wi-Fi OFF, debug -PrealEngines, native -O3.

| Сценарий | Аудио | Обработка | Копирование отдельно | RTF без копирования |
|---|---:|---:|---:|---:|
| Cold, reported | ≈11 с | 27.3 с | 5.4 с | ≈2.482 |
| Warm, reported | ≈11 с | 26.2 с | Не сообщено | ≈2.382 |

Стадии, reported: ASR 16.5 с, diarization 5.8 с; PSS ASR около 500 МБ, LLM до 750 МБ. Export quality/check_export.py — PASS по сообщению Ярика; крэшей нет. SHA debug APK, точная длительность и признак чистой установки не переданы. RTF рассчитан из округлённых 11 с, это предварительная оценка.

Release f735cbf собран отдельно: 670745630 байт, SHA-256 `9ff85b2aa2e567a004227924421657aae1e4eeb1bb02393bc68342c4d8af7877`; в #24 сообщено отсутствие INTERNET и 4 Stored-модели. **Этот release на телефоне через UI ещё не проверен**, его SHA не подставляется в строки debug-прогона CSV.

## Русский диалог

До UTF-8 исправления: вход 73.5 с, крэш в ASR из-за границ UTF-8 токенов. Наблюдения до крэша: decode 17–26 с; diarization 181–192 с (стадийный RTF ≈2.46–2.61 при длительности 73.5 с). Общий RTF не вычисляется для незавершённого конвейера. Нельзя подменять им скорость окончательной версии.

Фикс UTF-8 есть в #24 f735cbf; успешный повтор, выгруженный verdict экспорта, N/M и WER **ожидаются от Ярика**. Для обновления нужны source/APK SHA, точная duration, cold/warm и stage times, copy/load границы, PSS/RSS, N/M/changed/rejected, предупреждения и export PASS/FAIL. При получении обновить существующую pending-строку/добавить отдельные реальные повторы, а не выдавать план за результат. Реальный текст диалога сюда не переносить.

## Локальная LLM

Телефонный scalar Q4 diagnostic source1d68a73 / APK SHA9aa2906c…: cold71.448 с, warm15.168 с, reported PSS693 МБ/RSS≈750 МБ; guard accepted/changed=true на фиксированной синтетике. Длительность аудио отсутствует — это текстовый тест, **RTF неприменим**. Полное описание — [A51_LLM_CHECKPOINT.md](A51_LLM_CHECKPOINT.md).

Ускоренная KV-версия source067d4ca / APK SHA60648393… собрана; A51-прогон и целевые ≥50% реплик за 18 с пока не подтверждены. Linux-замеры и тесты не копируются сюда: [LLM_KV_PERFORMANCE.md](LLM_KV_PERFORMANCE.md). Исходная [русская валидация](LLM_RUSSIAN_VALIDATION.md) — синтетика, не оценка WER русского ASR.

## Автоматические проверки

Методика QA и список сценариев — [Q1_TEST_PLAN.md](Q1_TEST_PLAN.md). LLM/JNI/pipeline unit и команды описаны в существующих LLM-отчётах; они относятся к указанным там SHA. CI #24 назван зелёным на f735cbf в отчёте автора, этот docs-PR не повторял Android/device tests. Собственные проверки данного PR — структура CSV, ссылки, diff и forbidden files; их фактический статус приведён в описании PR.

## Материалы сдачи

[Демо-видео](../artifacts/demo/README.md) — ссылка ещё не предоставлена. [SETUP](SETUP.md), [ARCHITECTURE](ARCHITECTURE.md) и [src/README.md](../src/README.md) переиспользуются без копирования инструкций. Отсутствующие измерения помечены pending/not_run, а не PASS.
