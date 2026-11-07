# F4 — сохранение JSON

TranscriptExportRoute(result, exporter, onChooseAnotherAudio) принимает только shared
TranscriptResult/TranscriptExporter. ACTION_CREATE_DOCUMENT (application/json) выбирает
назначение; строка URI передаётся экспортеру без преобразования в путь. JSON в production UI
не сериализуется. Сообщение «JSON сохранён» появляется только после успешного Result<Unit>.

JsonExportSession удерживается ViewModel, захватывает точный result до запуска picker,
запрещает повторные picker/export, сообщает ошибки. Отмена picker ничего не сохраняет.
CancellationException пробрасывается. После потери процесса callback без pending result
не начинает запись другого результата, а предлагает повторить выбор назначения.

Debug-only FrontExportDemo пишет фиксированный синтетический JSON через ContentResolver
и тот же TranscriptExporter contract. Он принимает только synthetic-export-demo и не может
экспортировать настоящую расшифровку. Release не содержит demo/fixture. B5 подключает Ярик.
В debug меню можно переключить импорт и проверку сохранения.

Шесть unit-тестов: отмена picker, точные result/URI и защита от дубля, ожидание успеха экспортера,
ошибка/повтор, callback без pending result и CancellationException. Все 6 тестов PASS в debug/release локальной интеграции.
Зависимости только из общего каталога; Lifecycle Compose/ViewModel aliases на существующей
2.8.6 пришли из #19 через merge origin/main (main 477ce4c).
SAF имя: transcript-yyyy-MM-dd-HHmmss.json (время устройства).
Ночная проверка в отдельном detached integration checkout с back #18/#19:
Temurin JDK17, SDK34; assembleDebug test lint assembleDebugAndroidTest — PASS.
Все 6 JsonExportSessionTest прошли в debug/release. Это не CI ветки #15:
после #19 в main самостоятельные assembleDebug test lint этой ветки — PASS;
актуальный штатный CI и Draft фиксируются в описании #15.
Device SAF/экспорт PASS в AppFlow #20 + back #18/#19, API34 arm64 Pixel6 AVD в авиарежиме.
FrontFlowTest действительно выбрал синтетический WAV через системный SAF, отменил и повторил
обработку, пережил recreate, сохранил JSON через CreateDocument, прочитал файл и проверил
строгие поля JSON / две реплики. Модели и телефонные метрики NOT RUN.
Back-изменения включаются в опубликованную ветку только через принятую origin/main;
непринятые back-ветки использовались исключительно в detached проверочном checkout.
Команда: cd src/android && ./gradlew assembleDebug test lint.

Ветка независима от F3 по production-файлам; при интеграции debug AppContent сохранить
F3 PipelineRoute и подключить TranscriptExportRoute в его resultContent. Core/engine/DI
front не меняет. Reviewer — Ярик @lockermon4, также «на ревью Claude» для интеграции.
