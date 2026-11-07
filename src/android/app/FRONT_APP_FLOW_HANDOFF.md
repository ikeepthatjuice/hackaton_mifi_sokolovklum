# Экранный поток и точка B6

AppFlow(pipeline, exporter): импорт SAF → PipelineRoute start/observe/cancel →
TranscriptExportRoute → SAF CreateDocument и экспорт. AppContent обоих вариантов получает
объекты через rememberAppGraph() из DI back (#18). UI не создаёт engine, не сериализует JSON,
не читает PCM и не меняет публичные contracts. B6 меняет только pipelineEngines() в DI.
Release не импортирует src/debug и не содержит фиктивных результатов. До B6 реальный
release pipeline из #18 честно возвращает MODEL_MISSING, который F3 показывает как ошибку.
Debug banner сообщает, что движки синтетические; декод и JSON exporter в #18 реальные.

Зависимости перед merge: #14 (F3), #15 (F4), #19 (catalog и androidTest setup),
#10/#12/#17/#18 (audio/pipeline/export/DI). Ветка от main, только UI app-файлы;
чужие ветки в опубликованный PR не влиты. Их можно соединить в отдельном detached checkout
только для локальной проверки. Пока зависимости не в main, CI этого PR будет красным;
не снимать Draft и не просить апрув до актуального зелёного CI.

Проверки и ограничения обновляются в описании PR. Полный цикл с настоящими моделями,
телефонные RTF/RAM/WER и данные кейса не проверяются этим UI PR.

Ночная проверка отдельного detached integration checkout (не опубликованного PR HEAD):
- Temurin17 / SDK34: assembleDebug test lint assembleDebugAndroidTest — PASS.
- app assembleRelease — PASS; apkanalyzer: нет FrontUxPreviewActivity, app.frontdebug,
  SyntheticAsr/SyntheticDiarization и INTERNET в release APK. APK unsigned, модели не подключены.
- connectedDebugAndroidTest на API34 Google APIs arm64-v8a Pixel6 AVD, авиарежим — 15/15 PASS:
  app 5, audio 3, export 2, pipeline 5. FrontFlowTest: настоящий SAF, cancel выбора, cancel pipeline,
  повтор, recreate, Completed, recreate, CreateDocument, JSON прочитан и проверен, повтор после Completed.
- Только debug synthetic engines; реальные модели/телефон/RTF/RAM/WER не проверялись.
- Штатные CI #14/#15/#20 ждут зависимостей в main. Независимое ревью не запрошено до зелёного CI.

Debug-only ObservedDebugPipeline делегирует реальному pipeline без изменения результата;
FrontDebugStarts хранит только job IDs для проверки числа вызовов start (не URI/текст).
FrontFlowTest поворачивает MainActivity в landscape во время обработки, затем в portrait,
делает recreate и проверяет неизменность списка start IDs. Всего ровно три запуска:
первый отменён, второй сохранён в JSON, третий после Completed; поворот их не добавляет.
