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
ошибка/повтор, callback без pending result и CancellationException. Device SAF/export сценарии пока NOT RUN.
Зависимости только из общего каталога; Lifecycle Compose/ViewModel aliases запрошены
у Ярика на существующей версии 2.8.6. До их появления и результата CI сборка не подтверждена.
Локально JDK отсутствует. Команда: cd src/android && ./gradlew assembleDebug test lint.

Ветка независима от F3 по production-файлам; при интеграции debug AppContent сохранить
F3 PipelineRoute и подключить TranscriptExportRoute в его resultContent. Core/engine/DI
front не меняет. Reviewer — Ярик @lockermon4, также «на ревью Claude» для интеграции.
