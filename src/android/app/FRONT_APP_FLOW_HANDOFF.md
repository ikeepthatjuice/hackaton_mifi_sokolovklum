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
