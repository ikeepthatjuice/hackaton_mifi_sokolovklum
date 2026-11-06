# AGENTS.md — правила для всех ИИ-агентов

Нейро-стенографист: офлайн Android APK (Kotlin). Источник общих правил — `PROJECT_PLAN.md` v3.0; публичные типы — код `core/contracts/`. При противоречии документа с кодом — согласовать с back (Ярик) до реализации.

## Перед работой

1. Прочитай этот файл, PROJECT_PLAN.md, свой персональный план и `core/contracts/`.
2. Проверь `git status` и текущую ветку. Ветка задачи: `feat/<роль>-<задача>` от актуальной `main`. Роли: `back`, `front`, `ml`, `llm-qa`.
3. Нет назначенной роли/задачи — спроси, не придумывай.

## Владельцы путей

| Роль | Пути |
|---|---|
| back (Ярик) | `core/*`, `.github/`, корневые `*.gradle.kts`, `gradle/`, `gradle.properties`, `models/manifest.json`, `scripts/`, `app/src/main/kotlin/com/neurostenographer/app/di/`, `AGENTS.md`, `.gitignore` |
| front (Санёк) | `feature/import/`, `feature/transcript/`, остальной `app/` |
| ml (Илья) | `engine/asr/`, `engine/diarization/`, `engine/alignment/` |
| llm-qa (Сюбик) | `engine/llm/`, `quality/`, `docs/`, `demo/`, `README.md` (по согласованию с back) |

Чужие пути меняем только после согласования с владельцем. Зависимости модулей: UI → pipeline → contracts; engines реализуют contracts и не импортируют UI и друг друга.

## Запрещено

- Push в `main`, force-push, слияние собственного PR, обход ревью, отключение защиты.
- Свои копии или переименования DTO из `core/contracts/`. Изменение контракта — issue → согласование потребителей → отдельный PR.
- Версии `latest`, `+`, плавающие ветки. Версии библиотек — только в `gradle/libs.versions.toml` (меняет back).
- Данные кейса в git/PR/issues/CI: аудио, эталоны, расшифровки, их фрагменты, логи с содержимым. Веса моделей, ключи, `.env`, `local.properties`.
- Облачные API (ASR/LLM/диаризация) в приложении; stub вместо модели в release.
- Заявлять проверки, которые не выполнялись.

## Перед PR

```bash
git status && git diff --cached --stat
bash scripts/check-forbidden.sh
./gradlew assembleDebug test lint
```

Остановись, если в staged-файлах есть реальные записи, эталоны, ключи или выводы кейса.

## Контракт данных (кратко)

Время — `Long`, мс от начала оригинала, интервалы `[startMs, endMs)`. PCM — mono 16 kHz float32 LE. `speaker_1`, `speaker_2`… по первому появлению, стабильны по всей записи. JSON экспорта собирается кодом, не LLM.

## Отчёт агента

Задача, ветка, PR, HEAD SHA, изменённые пути, выполненные команды и результат, непроверенные ограничения, кому передать результат.
