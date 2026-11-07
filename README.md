# Нейро-стенографист

Командный проект хакатона: Android-приложение для офлайн-расшифровки аудио с говорящими, временными метками, локальной LLM-постобработкой и JSON-экспортом.

**Язык — только русский.** ASR — GigaAM-v3. Запись на другом языке приложение не расшифровывает: задача завершается понятной ошибкой, без падения. LLM может менять только пунктуацию и регистр; при замене, добавлении или потере слов, чисел и отрицаний валидатор возвращает `rawText`. Подробные результаты русского синтетического прогона — [docs/LLM_RUSSIAN_VALIDATION.md](docs/LLM_RUSSIAN_VALIDATION.md).

**Черновик Q6.** Описание цели не подтверждает готовность всех функций. На снимке main `9f01387` есть Android bootstrap, contracts, QA и CI; engines и общий pipeline интегрируются отдельными PR. LLM runtime исследуется в изолированном APK PR #6; физический телефон и общий end-to-end сценарий здесь ещё не подтверждены.

## Запуск и проверки

Полная настройка, версии и текущие блокеры: [docs/SETUP.md](docs/SETUP.md). Установка и измерения LLM spike: [docs/LLM_DEVICE_RUN.md](docs/LLM_DEVICE_RUN.md). QA и методика: [docs/Q1_TEST_PLAN.md](docs/Q1_TEST_PLAN.md).

```bash
bash scripts/check-environment.sh
bash scripts/check-forbidden.sh
(cd src/android && ./gradlew assembleDebug test lint)
python3 -m unittest discover -s quality/tests
```

## Требования к устройству

Свободного места нужно **не меньше 2 ГБ**. Замер на Samsung A51, release без whisper, PR #40:
- APK: 783 МБ (783 016 438 Б);
- копия весов в `filesDir/models` при первом запуске: 675 МБ (GigaAM-v3, Pyannote 3.0, ERes2Net, Qwen3-0.6B);
- итого 1.46 ГБ;
- запас около 0.5 ГБ на временный PCM: это примерно 230 МБ на час записи, плюс история расшифровок.

## Структура и общие данные

Android: `src/android/`. `core/contracts/` внутри него задаёт DTO и интерфейсы. UI → pipeline → contracts; engines реализуют contracts, не импортируют друг друга. PCM: mono 16 kHz headerless float32 LE, samples [-1,1]. Время: Long, миллисекунды оригинала, интервалы `[startMs, endMs)`. LLM может менять только `displayText`; ID, speakerId, времена, rawText, порядок и количество реплик сохраняются. JSON создаёт код.

Корневые `quality/` — проверки и синтетика, `docs/` — инструкции. Общие fetch-native/fetch-models и manifest ещё ожидаются от back; их отсутствие явно описано в SETUP.

## Источник локальной LLM

Исходная модель — Qwen3-0.6B. Используемая GGUF Q4_K_M опубликована **сторонним автором prithivMLmods**, а не официальным репозиторием Qwen: [prithivMLmods/Qwen3-0.6B-GGUF](https://huggingface.co/prithivMLmods/Qwen3-0.6B-GGUF/tree/3fa3255253c99e212675c0825241518cdba8e42c). Лицензия этой поставки — **Apache-2.0**, согласно зафиксированным метаданным модели. Источник и квантовка указаны отдельно от автора исходной модели.

Файл-источник: [Qwen3_0.6B.Q4_K_M.gguf](https://huggingface.co/prithivMLmods/Qwen3-0.6B-GGUF/resolve/3fa3255253c99e212675c0825241518cdba8e42c/Qwen3_0.6B.Q4_K_M.gguf); локальное имя — `Qwen3-0.6B-Q4_K_M.gguf`, размер — **396704928 байт**, SHA-256 — `2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9`. Ревизия источника закреплена; размеры, SHA и лицензии — в [models/manifest.json](models/manifest.json) и [dependencies.json](src/android/engine/llm/dependencies.json). Веса в git не входят.

Отчёты: [A51](docs/A51_LLM_CHECKPOINT.md), [KV-кэш и batching](docs/LLM_KV_PERFORMANCE.md). Проверенный на A51 diagnostic и новая KV-версия имеют разные SHA APK; результаты старой версии не подтверждают скорость новой.

## Работа команды

Санёк — front; Ярик — back/сборка/contracts; Илья — ASR/диаризация/alignment; Сюбик — LLM/QA/защита. Правила и владельцы путей: [AGENTS.md](AGENTS.md). Работаем в `feat/<роль>-<задача>` через PR и независимое ревью. В main напрямую не пушим, собственные PR не апрувим и не мерджим.

Данные кейса, реальные расшифровки, веса, локальные кэши и секреты не коммитить. Сеть допустима при подготовке сборки; inference приложения должен работать офлайн. Финальные размер APK, лицензии/хеши всех весов, устройство и измеренные WER/RTF будут добавлены после интеграции и испытаний. Пунктуация/регистр как обязательная роль LLM и лимит размера APK пока требуют подтверждения организаторов.
