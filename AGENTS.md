# AGENTS.md — правила для всех ИИ-агентов

Нейро-стенографист: офлайн Android APK (Kotlin). Источник общих правил — `PROJECT_PLAN.md` v3.0; публичные типы — код `core/contracts/`. При противоречии документа с кодом — согласовать с back (Ярик) до реализации.

## Перед работой

1. Прочитай этот файл, PROJECT_PLAN.md, свой персональный план и `core/contracts/`.
2. Проверь `git status` и текущую ветку. Ветка задачи: `feat/<роль>-<задача>` от актуальной `main`. Роли: `back`, `front`, `ml`, `llm-qa`.
3. Нет назначенной роли/задачи — спроси, не придумывай.

## Структура (шаблон организаторов grigorevmp/hacathon_template)

Android-проект (Gradle) — в `src/android/`; все команды сборки запускаются оттуда. В корне — документы сдачи (`docs/`, `artifacts/`, `README.md`), `models/`, `scripts/`, `.github/`.

## Владельцы путей

Пути модулей ниже — относительно `src/android/`.

| Роль | Пути |
|---|---|
| back (Ярик) | `core/*`, `*.gradle.kts`, `gradle/`, `gradle.properties`, `app/src/main/kotlin/com/neurostenographer/app/di/`; в корне: `.github/`, `models/manifest.json`, `scripts/`, `AGENTS.md`, `.gitignore` |
| front (Санёк) | `feature/import/`, `feature/transcript/`, остальной `app/` |
| ml (Илья) | `engine/asr/`, `engine/diarization/`, `engine/alignment/` |
| llm-qa (Сюбик) | `engine/llm/`, `quality/`; в корне: `docs/`, `artifacts/`, `demo/`, `README.md`, `src/README.md` (по согласованию с back) |

Модели поставляются внутри APK (эксперты только устанавливают APK, без adb и сети); веса в git не коммитятся.

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
(cd src/android && ./gradlew assembleDebug test lint)
```

Остановись, если в staged-файлах есть реальные записи, эталоны, ключи или выводы кейса.

## Проверка на эмуляторе

- `.github/workflows/emulator.yml` — на каждый PR в `main` и вручную (Actions → Emulator → Run workflow). Эмулятор API 34 x86_64 (google_apis), сценарий `scripts/emulator-smoke.sh`: установить APK, запустить `MainActivity`, проверить что процесс жив, затем `connectedDebugAndroidTest`.
- Артефакты в Actions run: `app-debug-<sha>` (APK из CI, 3 дня) и `emulator-report-<sha>` (logcat и отчёты androidTest).
- Веса для debug-запуска приложения — `/sdcard/Android/data/com.neurostenographer.app/files/models`. Для androidTest engine-модулей — assets тестового APK из `local_models/` (подключаются в build.gradle.kts, только если папка есть; в git не попадают). Финальная доставка — assets внутри APK.
- Данные кейса в эмулятор и CI не кладём. Для тестов — только `jfk.wav` из submodule whisper.cpp или сгенерированная синтетика.
- Скорость на эмуляторе (x86_64, swiftshader) — не Android RTF; для метрик нужен реальный телефон.
- Методика замеров WER/RTF — `docs/Q1_TEST_PLAN.md` (только реальный телефон, commit + хеш APK, cold/warm).

## Контракт данных (кратко)

Время — `Long`, мс от начала оригинала, интервалы `[startMs, endMs)`. PCM — mono 16 kHz float32 LE. `speaker_1`, `speaker_2`… по первому появлению, стабильны по всей записи. JSON экспорта собирается кодом, не LLM.

## Отчёт агента

Задача, ветка, PR, HEAD SHA, изменённые пути, выполненные команды и результат, непроверенные ограничения, кому передать результат.
