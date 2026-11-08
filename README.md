# Нейро-стенографист

Командный проект хакатона: Android-приложение для офлайн-расшифровки аудио с говорящими, временными метками, локальной LLM-постобработкой и JSON-экспортом.

**Язык — только русский.** ASR — GigaAM-v3. Запись на другом языке приложение не расшифровывает: задача завершается понятной ошибкой, без падения. LLM может менять только пунктуацию и регистр; при замене, добавлении или потере слов, чисел и отрицаний валидатор возвращает `rawText`. Подробные результаты русского синтетического прогона — [docs/LLM_RUSSIAN_VALIDATION.md](docs/LLM_RUSSIAN_VALIDATION.md).

## Release v1.0

[Скачать Release v1.0](https://github.com/ikeepthatjuice/hackaton_mifi_sokolovklum/releases/tag/v1.0) · [APK](https://github.com/ikeepthatjuice/hackaton_mifi_sokolovklum/releases/download/v1.0/neurostenographer-v1.0.apk).

- Исходный commit: `f571636846fe35231405a78ad3fb2463cff18c67`.
- Файл: `neurostenographer-v1.0.apk`, **743 422 356 байт**, arm64-v8a.
- SHA-256: `5ea4e8a86aa994236ea30b4449a41843c1fdd2003603c3cee83197f420312bdf`.
- Модели внутри APK; разрешения INTERNET нет. Сеть нужна только для скачивания и подготовки сборки.

Ярик сообщил о чистой установке и запуске Release на Galaxy A51 (Android 13), совпадении SHA на телефоне и отсутствии крэшей: [итоговый отчёт](https://github.com/ikeepthatjuice/hackaton_mifi_sokolovklum/issues/47#issuecomment-6717862616). Хеш выше получен из digest GitHub Release и совпадает с отчётом; автор документа локально APK не перехешировал.

Финальный отчёт команды: русский диалог 73,5 с обработан за 160,9 с (RTF 2,19); LLM — **0 из 9** реплик, остаётся текст ASR с его пунктуацией. FLEURS ru: WER **3,38%** на 15 клипах / 296 словах. Эти цифры относятся к отчёту на commit релиза; отдельный SHA APK измерительного прогона не указан. Это не собственный device run автора документа.

## Запуск и проверки

Полная настройка, версии и текущие блокеры: [docs/SETUP.md](docs/SETUP.md). Установка и измерения LLM spike: [docs/LLM_DEVICE_RUN.md](docs/LLM_DEVICE_RUN.md). QA и методика: [docs/Q1_TEST_PLAN.md](docs/Q1_TEST_PLAN.md).

```bash
bash scripts/check-environment.sh
bash scripts/check-forbidden.sh
(cd src/android && ./gradlew assembleDebug test lint)
python3 -m unittest discover -s quality/tests
```

## Требования к устройству

Свободного места нужно **не меньше 2 ГБ**: APK занимает 743 МБ, при первом запуске модели копируются в приватное хранилище. Нужен дополнительный запас на временное аудио: mono 16 kHz float32 занимает около 230,4 МБ в час. Проверенное командой устройство — Samsung Galaxy A51, Android 13, arm64, около 3,5 ГБ доступной RAM по отчёту испытаний. Это не подтверждает работу на всех устройствах с такими параметрами.

## Структура и общие данные

Android: `src/android/`. `core/contracts/` внутри него задаёт DTO и интерфейсы. UI → pipeline → contracts; engines реализуют contracts, не импортируют друг друга. PCM: mono 16 kHz headerless float32 LE, samples [-1,1]. Время: Long, миллисекунды оригинала, интервалы `[startMs, endMs)`. LLM может менять только `displayText`; ID, speakerId, времена, rawText, порядок и количество реплик сохраняются. JSON создаёт код.

Корневые `quality/` — проверки и синтетика, `docs/` — инструкции. Подготовка зависимостей: [scripts/fetch-native.sh](scripts/fetch-native.sh), [scripts/fetch-models.sh](scripts/fetch-models.sh); закреплённые модели — [models/manifest.json](models/manifest.json). Команды и сборка Release описаны в SETUP.

## Источник локальной LLM

Исходная модель — Qwen3-0.6B. Используемая GGUF Q4_K_M опубликована **сторонним автором prithivMLmods**, а не официальным репозиторием Qwen: [prithivMLmods/Qwen3-0.6B-GGUF](https://huggingface.co/prithivMLmods/Qwen3-0.6B-GGUF/tree/3fa3255253c99e212675c0825241518cdba8e42c). Лицензия этой поставки — **Apache-2.0**, согласно зафиксированным метаданным модели. Источник и квантовка указаны отдельно от автора исходной модели.

Файл-источник: [Qwen3_0.6B.Q4_K_M.gguf](https://huggingface.co/prithivMLmods/Qwen3-0.6B-GGUF/resolve/3fa3255253c99e212675c0825241518cdba8e42c/Qwen3_0.6B.Q4_K_M.gguf); локальное имя — `Qwen3-0.6B-Q4_K_M.gguf`, размер — **396704928 байт**, SHA-256 — `2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9`. Ревизия источника закреплена; размеры, SHA и лицензии — в [models/manifest.json](models/manifest.json) и [dependencies.json](src/android/engine/llm/dependencies.json). Веса в git не входят.

Отчёты: [A51](docs/A51_LLM_CHECKPOINT.md), [KV-кэш и batching](docs/LLM_KV_PERFORMANCE.md). Проверенный на A51 diagnostic и новая KV-версия имеют разные SHA APK; результаты старой версии не подтверждают скорость новой.

## Работа команды

Санёк — front; Ярик — back/сборка/contracts; Илья — ASR/диаризация/alignment; Сюбик — LLM/QA/защита. Правила и владельцы путей: [AGENTS.md](AGENTS.md). Работаем в `feat/<роль>-<задача>` через PR и независимое ревью. В main напрямую не пушим, собственные PR не апрувим и не мерджим.

Данные кейса, реальные расшифровки, веса, локальные кэши и секреты не коммитить. Сеть допустима при подготовке сборки; inference приложения должен работать офлайн. Подтверждённого ответа организаторов о лимите APK и зачёте пунктуации/регистра как обязательной роли LLM в материалах этого отчёта нет.

## Комплект сдачи

- [Сборка и установка](docs/SETUP.md), [архитектура](docs/ARCHITECTURE.md), [вводная src](src/README.md), [пример окружения](config/example.env).
- [Ограничения](docs/LIMITATIONS.md), [протокол](docs/PROTOCOL.md), [результаты и точность ASR](docs/TEST_RESULTS.md).
- [Таблица результатов](artifacts/test-results/results.csv), [описание измерений](artifacts/test-results/README.md), [демонстрационное видео: статус ссылки](artifacts/demo/README.md).

В main релиза есть импорт, отображение стенограммы и JSON-экспорт. Новые редактирование/склейка, история и проигрывание фрагментов из отдельных feature PR не включены в описание готовых возможностей: их device QA здесь не подтверждён. Полный сценарный план — [Q1_TEST_PLAN](docs/Q1_TEST_PLAN.md).
