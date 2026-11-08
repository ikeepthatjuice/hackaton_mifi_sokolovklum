# Итоговые результаты

[results.csv](results.csv) — только финальный commit Release v1.0 и проверка установки опубликованного APK. Источник device-чисел: [итоговый отчёт Ярика](https://github.com/ikeepthatjuice/hackaton_mifi_sokolovklum/issues/47#issuecomment-6717862616). Автор документа device run не выполнял. Промежуточные диагностические APK в эту таблицу не включены.

Поля: `test_id` — сценарий; `status` — результат или REPORTED (число из отчёта); `evidence_source` — ссылка; `source_commit` — исходники; `apk_sha256`, `build_variant` — только если явно известны для этого прогона; `device/android/airplane_mode` — окружение; `audio_id/language/audio_duration_ms/elapsed_ms/rtf` — набор и время; `speaker_count`, `llm_completed_utterances/llm_total_utterances` — обработка; `reference_words/wer/wer_no_digits` — точность; `export_validation` — структура; `notes` — границы интерпретации.

WER записан долей (0.0338 = 3,38%). Пустые поля означают «не сообщено». RTF — полное время / длительность аудио; для FLEURS финальный RTF отсутствует. SHA Release не переносится на измерительный APK, чья идентичность не опубликована. PASS экспорта не подтверждает speaker identity и WER.

Методика — [PROTOCOL](../../docs/PROTOCOL.md); интерпретация — [TEST_RESULTS](../../docs/TEST_RESULTS.md). Содержательные записи, эталоны и логи кейса не публикуются.
