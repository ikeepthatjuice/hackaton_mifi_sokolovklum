# Нейро-стенографист

Командный проект хакатона: Android-приложение для офлайн-расшифровки аудио с говорящими, временными метками, локальной LLM-постобработкой и JSON-экспортом.

**Черновик Q6.** Описание цели не подтверждает готовность всех функций. На снимке main `9f01387` есть Android bootstrap, contracts, QA и CI; engines и общий pipeline интегрируются отдельными PR. LLM runtime исследуется в изолированном APK PR #6; физический телефон и общий end-to-end сценарий здесь ещё не подтверждены.

## Запуск и проверки

Полная настройка, версии и текущие блокеры: [docs/SETUP.md](docs/SETUP.md). Установка и измерения LLM spike: [docs/LLM_DEVICE_RUN.md](docs/LLM_DEVICE_RUN.md). QA и методика: [docs/Q1_TEST_PLAN.md](docs/Q1_TEST_PLAN.md).

```bash
bash scripts/check-environment.sh
bash scripts/check-forbidden.sh
(cd src/android && ./gradlew assembleDebug test lint)
python3 -m unittest discover -s quality/tests
```

## Структура и общие данные

Android: `src/android/`. `core/contracts/` внутри него задаёт DTO и интерфейсы. UI → pipeline → contracts; engines реализуют contracts, не импортируют друг друга. PCM: mono 16 kHz headerless float32 LE, samples [-1,1]. Время: Long, миллисекунды оригинала, интервалы `[startMs, endMs)`. LLM может менять только `displayText`; ID, speakerId, времена, rawText, порядок и количество реплик сохраняются. JSON создаёт код.

Корневые `quality/` — проверки и синтетика, `docs/` — инструкции. Общие fetch-native/fetch-models и manifest ещё ожидаются от back; их отсутствие явно описано в SETUP.

## Работа команды

Санёк — front; Ярик — back/сборка/contracts; Илья — ASR/диаризация/alignment; Сюбик — LLM/QA/защита. Правила и владельцы путей: [AGENTS.md](AGENTS.md). Работаем в `feat/<роль>-<задача>` через PR и независимое ревью. В main напрямую не пушим, собственные PR не апрувим и не мерджим.

Данные кейса, реальные расшифровки, веса, локальные кэши и секреты не коммитить. Сеть допустима при подготовке сборки; inference приложения должен работать офлайн. Финальные размер APK, лицензии/хеши всех весов, устройство и измеренные WER/RTF будут добавлены после интеграции и испытаний. Пунктуация/регистр как обязательная роль LLM и лимит размера APK пока требуют подтверждения организаторов.
