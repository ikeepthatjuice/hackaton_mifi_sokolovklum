# F1: экраны фронтенда — передача Ярику и Сюбику

Статус: три экрана перенесены в Android-проект и подключены к сборке после B2.
`origin/main` влит обычным merge; исходная база bootstrap/contracts — `95cd659`.
Все Android-пути ниже относительны к `src/android/`.

## Пути и зависимости

- `feature/import`: ImportScreen и ProcessingScreen, пакет `feature.importaudio`.
- `feature/transcript`: TranscriptScreen, пакет `feature.transcript`.
- `app/src/debug`: FrontScreensDemo и только синтетические fixtures.
- DTO импортируются из merged B2 `core.contracts` (`95cd659`), публичных копий нет.

Для исходников нужны артефакты `androidx.compose.runtime:runtime`,
`androidx.compose.runtime:runtime-saveable`, `androidx.compose.ui:ui`,
`androidx.compose.foundation:foundation`, `androidx.compose.material3:material3`.
`feature/transcript` зависит от проекта contracts; app debug — от обоих feature-модулей и contracts.
Отдельных navigation, icons и tooling библиотек здесь нет.

Оба feature-модуля имеют Gradle-декларации и Android manifests. Все зависимости берутся
из общего version catalog: Compose BOM 2024.09.03, Kotlin/Compose compiler plugin 2.0.21,
AGP 8.5.2. Новых версий или aliases front не добавляет. `settings.gradle.kts`
подключает `:feature:import` и `:feature:transcript` по согласованию с Яриком;
app зависит от обоих. DTO и сборка core/engine не менялись.

## Запуск и оставшаяся проверка

1. Из `src/android/`: `./gradlew assembleDebug test lint`.
2. Установить debug APK и пройти три экрана. MainActivity вызывает variant-specific AppContent:
   debug открывает FrontScreensDemo, release показывает честный статус отсутствующего pipeline.
   Fixtures и ссылка на demo остаются в debug source set. Insets обрабатывает main Activity.
3. Проверить крупный шрифт, поворот, отмену, повтор, длинную и пустую расшифровки на Android.
4. Дополнительно проверить release: `./gradlew assembleRelease`.

В текущем окружении `java -version` не находит Java Runtime; Gradle не найден в PATH,
Android SDK отсутствует в стандартном `~/Library/Android/sdk`. Локальная команда сборки
останавливается до Gradle из-за отсутствия Java Runtime; результат CI фиксируется в PR.
Фактические требования установки передать в docs/SETUP.md через Сюбика после bootstrap.

## Ручной сценарий для Сюбика

- На экране импорта кнопка запуска выключена без выбранного примера.
- В debug выбрать синтетический пример, открыть обработку, отменить, повторить.
- Результат появляется только по явной debug-кнопке, а не по таймеру или запуску модели.
- Проверить speaker_1 → speaker_2 → speaker_1, времена и текст; экспорт выключен до F4.
- Для пустого TranscriptResult ожидается «Речь в записи не обнаружена».
- Для длинной расшифровки и увеличенного шрифта проверить прокрутку и доступность кнопок.

Экраны не запускают jobs при композиции. URI/file picker — F2, lifecycle/start/observe/cancel — F3,
выбор назначения и настоящий экспорт — F4. До согласования back API собственных версий API нет.
API B2 подтверждён: `start(audioUri: String): String` возвращает jobId,
`observe(jobId): StateFlow<PipelineState>`, `cancel(jobId)`,
`TranscriptExporter.export(result, destinationUri: String): Result<Unit>` (suspend).
Реализации pipeline/exporter пока не слиты; запуск и экспорт в release не имитируются.

Основной reviewer — Ярик (@lockermon4); дополнительная UX-проверка — Сюбик (@bondarjuice).
Ревью запрашивается после снятия Draft и зелёного CI на актуальном HEAD.

## Правило ревью — перед каждым запросом апрува

1. Проверить актуальную базу и изменённые файлы PR:

   ```bash
   git diff --name-only origin/main...HEAD
   ```

2. Определить владельцев затронутых путей. Пути Android-модулей ниже относительны
   к `src/android/`; пути с пометкой «корень» относительны к корню репозитория.

   | Владелец | Пути |
   |---|---|
   | Ярик, back, @lockermon4 | `core/*`, `*.gradle.kts`, `settings.gradle.kts`, `gradle/`; корень: `.github/`, `scripts/`, `models/manifest.json`, `AGENTS.md`, `.gitignore` |
   | Санёк, front, @ikeepthatjuice | `feature/import/`, `feature/transcript/`, `app/`, кроме `app/.../di/` (back) |
   | Илья, ml, @gexxpel | `engine/asr/`, `engine/diarization/`, `engine/alignment/` |
   | Сюбик, llm-qa, @bondarjuice | `engine/llm/`; корень: `quality/`, `docs/`, `demo/`, `README.md` |

   Gradle-файлы feature-модулей front добавляет по согласованию с Яриком;
   изменения сборки также требуют его ревью. До переноса текущие корневые
   `feature/*` и `app/*` из F1 относятся к соответствующим Android-путям этой таблицы.

3. Составить список ревьюеров без дублей:
   - основной по роли автора: front/ml → Ярик; back → Санёк или Сюбик;
     LLM-код Сюбика → Илья; docs Сюбика → Санёк;
   - добавить владельца каждого чужого пути, затронутого PR;
   - для `core/contracts/` добавить всех потребителей: Санька, Илью, Сюбика;
   - при изменении pipeline, экспорта, PCM, DTO или другого API добавить
     затронутых потребителей, даже если их файлы не менялись;
   - исключить автора PR из запросов апрува самому себе. Если автор одновременно
     потребитель contracts, его изменения всё равно проверяют независимые потребители.

3а. Общие изменения дополнительно проверяет Claude — ревьюер-планировщик команды.
   Его ревью требуется, если PR меняет:
   - корневые `README.md`, `src/README.md`, `AGENTS.md`, `PROJECT_PLAN.md`;
   - `docs/SETUP.md` или другие документы, которые читают все;
   - сразу несколько ролей: сквозные PR, интеграцию, release candidate.

   Claude не назначается GitHub-ревьюером и не ставит GitHub approval.
   Передать Ярику ссылку на PR с пометкой «на ревью Claude»; ответ приходит через Ярика.
   Это дополнительное ревью не заменяет апрув человека-владельца и потребителей API.
   Для запроса подготовить пользователю отдельный текст Ярику: номер, название,
   ссылка, пометка «на ревью Claude», затронутые общие документы/роли,
   2–3 пункта проверки, текущий CI и что блокирует merge.

4. Проверить, что PR не Draft и CI зелёный именно на текущем HEAD.
   До этого не назначать ревьюеров для апрува и не выдавать сообщения с запросом апрува.
   После успешных проверок назначить всех требуемых независимых ревьюеров в PR
   и подготовить пользователю отдельный текст для каждого:

   ```text
   [Имя], нужен апрув PR #N — <название>
   Ссылка: <url>
   Тебя касается: <какие твои пути/API затронуты и как>
   Проверь: <2–3 конкретных пункта>
   CI: <зелёный/красный/не запускался>
   Блокирует: <что ждёт этот merge>
   ```

   Текст для отправки выдаётся пользователю; личные сообщения агент сам не отправляет
   без отдельного поручения. В запросе апрува статус CI должен быть «зелёный»;
   остальные значения подходят только для отчёта о блокере без запроса апрува.

5. Свой PR не апрувить и не мерджить. После новых коммитов прежний апрув
   считать устаревшим; проверить diff и CI актуального HEAD и запросить ревью заново.
   Фактический автоматический сброс апрува обеспечивает серверная защита GitHub,
   наличие Markdown-правила само по себе её не включает.

Для #1: основной reviewer — @lockermon4, также владелец согласованных Gradle/settings изменений.
При готовности CI передать ему ссылку с пометкой «на ревью Claude», поскольку PR
включает интеграцию feature-модулей и app. Пока #1 в Draft, запрос апрува не отправляется.
