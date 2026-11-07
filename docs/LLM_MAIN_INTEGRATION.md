# Интеграция LLM с main — 7 ноября 2026

LocalTranscriptPostprocessor реализует core.contracts.TranscriptPostprocessor; DTO не копируются. Общая сборка включает :engine:llm, зависимость — :core:contracts. Изменение displayText проверяется валидатором; id/speakerId/startMs/endMs/rawText/порядок сохраняются.

В PR #6 main 886c022fa9b84557f0bee75a5a83fd983d774bbe подтянут merge-коммитом без force-push. Общие изменения: settings.gradle.kts включает модуль; scripts/fetch-native.sh перед sherpa cache short-circuit вызывает закреплённый fetch-runtime.sh. Оба CI jobs используют этот шаг. ABI совпадают с app: arm64-v8a и x86_64. Версии каталога и contracts не менялись.

llama/ggml остаются статическими внутри libtranscript_llm.so; наружу version script выпускает только три JNI entry points. Модуль собирается в общей Gradle-сборке как Android library. App DI/зависимость app на LLM здесь не добавлены: совместное APK с whisper, загрузка обоих runtime и inference на телефоне требуют отдельной интеграции после #2 (на момент проверки Draft).

CI assembleDebug/test/lint и Emulator должны быть проверены на новом HEAD перед Ready/review. NativeSmokeTest без LLM_SMOKE_MODEL пропускает настоящий inference; обычные unit-тесты его не доказывают. Исторические native/device результаты не переносить на новый SHA.

Ревью: Илья @gexxpel — LLM/native; Ярик @lockermon4 — settings/общий fetch и API интеграции; Санёк @ikeepthatjuice — docs. Самостоятельного merge нет. Ответ организаторов о лимите APK и встроенных весах не получен в доступных комментариях #6; Telegram не подключён.
