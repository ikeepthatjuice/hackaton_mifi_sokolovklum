# Исходный код

Android-проект расположен в [android/](android/); Gradle-команды выполняются из src/android. Модули UI/feature, core и engine реализуют локальную обработку без облачного inference. Основной язык — русский, английский поддерживается.

Начать с [SETUP.md](../docs/SETUP.md) и [ARCHITECTURE.md](../docs/ARCHITECTURE.md). SETUP содержит исторические разделы: наличие bootstrap/Debug APK не подтверждает realEngines release; B6 интегрирован merge f48d417 в main, источник статуса — [PR #24](https://github.com/ikeepthatjuice/hackaton_mifi_sokolovklum/pull/24). Здесь команды сборки и архитектурные описания повторно не копируются.

Проверки: [Q1_TEST_PLAN.md](../docs/Q1_TEST_PLAN.md), [PROTOCOL.md](../docs/PROTOCOL.md), [TEST_RESULTS.md](../docs/TEST_RESULTS.md), [ограничения](../docs/LIMITATIONS.md). Границы LLM и замеры: [русская валидация](../docs/LLM_RUSSIAN_VALIDATION.md), [A51](../docs/A51_LLM_CHECKPOINT.md), [KV/batching](../docs/LLM_KV_PERFORMANCE.md). Пример host-окружения — [config/example.env](../config/example.env); приложение этот файл не читает.

Правила работы и владельцы путей — [AGENTS.md](../AGENTS.md). Ветки feat/<роль>-<задача>, Pull Request и независимое ревью; напрямую в main не пушить и свой PR не сливать. ARCHITECTURE принадлежит Илье. Core contracts не копировать и не менять без согласования. Аудио кейса, реальные тексты/экспорты, веса и секреты в git не помещать.

Материалы сдачи: [результаты](../artifacts/test-results/README.md), [место для демо](../artifacts/demo/README.md).
