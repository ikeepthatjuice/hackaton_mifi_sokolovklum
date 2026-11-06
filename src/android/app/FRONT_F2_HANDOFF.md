# F2 — локальный выбор аудио через SAF

Все Android-пути относительны к src/android/. Изменены feature/import и debug-host app;
core/*, engine/* и общий version catalog не менялись. Используются только aliases B2.

- AudioImportRoute запускает ACTION_OPEN_DOCUMENT с audio/* и EXTRA_LOCAL_ONLY.
- Сохраняется read-grant; display name читается через ContentResolver на IO dispatcher.
- URI сохраняется целиком строкой, без преобразования в путь, и по кнопке передаётся
  в TranscriptionPipeline.start(audioUri). Возвращённый jobId передаётся host-слою.
- Отмена picker оставляет прежний выбор; нет выбора — нет запуска. Ошибки доступа
  и старта видимы в UI. CancellationException не превращается в обычную ошибку.
- Сохранённые URI/имя/jobId восстанавливаются через rememberSaveable. Обработка
  и наблюдение lifecycle настоящего pipeline — F3; модели F2 не подключает.
- В debug выбор файла настоящий, pipeline — явно синтетическая fixture.
  Содержимое выбранного аудио fixture не читает, результат появляется по debug-кнопке.
  Release не содержит fixture или синтетических результатов.

## Проверки

Шесть unit-тестов AudioImportControllerTest: отмена с/без прежнего выбора, точная передача
URI, повторный start, повтор после ошибки, восстановление jobId и CancellationException.
Команда: `cd src/android && ./gradlew :feature:import:testDebugUnitTest assembleDebug test lint`.
Локальная команда пока блокируется отсутствием JDK; фактический CI фиксируется в PR.

На Android вручную: выбрать собственный локальный файл → отменить повторный picker →
прежнее имя остаётся → начать → debug processing → отменить → выбрать заново.
Проверить поворот до старта, запрет повторных кликов, provider без display name и отказ доступа.
Эти device-проверки пока NOT RUN.

Ветка F2 основана на актуальном main с добавлением F1. До merge #1 PR F2 может иметь
базу feat/front-screens; после merge #1 retarget на main и повторные проверки.
Reviewer: Ярик @lockermon4. Запрос апрува только после снятия Draft и зелёного CI.
