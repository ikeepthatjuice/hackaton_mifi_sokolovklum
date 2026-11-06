# F1: экраны фронтенда — передача Ярику и Сюбику

Статус: исходники трёх экранов подготовлены; Android-сборка и приёмка F1 пока не выполнены.
База: main `6894b29`, в которой отсутствуют файлы проекта и общая сборка.

## Пути и зависимости

- `feature/import`: ImportScreen и ProcessingScreen, пакет `feature.importaudio`.
- `feature/transcript`: TranscriptScreen, пакет `feature.transcript`.
- `app/src/debug`: FrontScreensDemo и только синтетические fixtures.
- DTO импортируются из `core.contracts`, публичных копий нет. Совместимость пока ориентирована на
  `origin/feat/ml-device-spike` `427cf4e`; это НЕ согласованные или слитые contracts main.

Для исходников нужны артефакты `androidx.compose.runtime:runtime`,
`androidx.compose.runtime:runtime-saveable`, `androidx.compose.ui:ui`,
`androidx.compose.foundation:foundation`, `androidx.compose.material3:material3`.
`feature/transcript` зависит от проекта contracts; app debug — от обоих feature-модулей и contracts.
Отдельных navigation, icons и tooling библиотек здесь нет.

Точные версии Compose, Kotlin, Compose compiler и Android Gradle plugin пока не определены:
в main нет version catalog, Wrapper или toolchain. Версии не угаданы, Gradle-декларации
не добавлены до согласования общей сборки. Ярик фиксирует совместимые версии, пути модулей,
namespace и DI-путь; затем front добавляет зависимости своих модулей через общий каталог.
Эта передача не означает, что зависимости установлены или сборка воспроизводима.

## Подключение после bootstrap

1. Слить общую сборку и согласованные contracts; проверить package и сигнатуры импортов.
2. Объявить согласованные зависимости feature-модулей; app подключает модули через back.
3. В debug entry point вызвать `FrontScreensDemo()` внутри Android Activity и передать
   безопасные window insets из app-host. Production entry point не должен ссылаться на demo.
4. Выполнить сборку, lint и UI-проверку на Android, включая крупный шрифт и поворот.
5. Проверить, что release source set не содержит fixtures и debug entry point.

В текущем окружении `java -version` не находит Java Runtime; Gradle не найден в PATH,
Android SDK отсутствует в стандартном `~/Library/Android/sdk`. Сборка не запускалась.
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
Нужно получить у Ярика merged SHA contracts, точные start/observe/cancel, типы ошибок и экспорт API.

Основной reviewer — Ярик; дополнительная UX-проверка — Сюбик.
GitHub-логины участников пока не известны, reviewer автоматически не назначен.
