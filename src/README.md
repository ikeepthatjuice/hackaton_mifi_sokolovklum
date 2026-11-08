# Исходный код

[Нейро-стенографист](../README.md) — офлайн Android-приложение. Основной и единственный поддерживаемый в Release v1.0 язык — русский. ASR: GigaAM-v3 RNN-T с пунктуацией; диаризация: Pyannote + CAM++; локальная постобработка: Qwen3-0.6B Q4_K_M через llama.cpp с проверкой сохранения слов.

Gradle-проект находится в [android/](android/). UI обращается к pipeline через `core/contracts`; движки реализуют контракты. LLM может менять лишь `displayText`; JSON формируется кодом. Модели загружаются при подготовке сборки и поставляются в APK, в git не входят.

- [Настройка, fetch-native.sh, fetch-models.sh, сборка и установка](../docs/SETUP.md).
- [Архитектура](../docs/ARCHITECTURE.md), [манифест моделей](../models/manifest.json), [пример окружения](../config/example.env).
- [Протокол испытаний](../docs/PROTOCOL.md), [результаты](../docs/TEST_RESULTS.md), [ограничения](../docs/LIMITATIONS.md).
- [Release v1.0 и SHA APK](../README.md#release-v10), [правила команды](../AGENTS.md).

Команды Gradle запускаются из `src/android/`; корневые QA-команды — из корня репозитория. Прежние диагностические LLM APK не являются общим Release.
