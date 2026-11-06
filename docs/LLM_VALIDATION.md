# Результаты Q1/Q2 и передачи команды

Дата: 6 ноября 2026. Роль: Сюбик/llm-qa. Исходный main был пустым 6894b29; при завершении bootstrap уже слит командой в main ea045caccf9bcfb3d563aea70cdf34495d8bf689. Агент в main не писал и PR не сливал.

## PR

- Q1: #4, feat/llm-qa-test-plan, SHA 3eba002509b2dfab53e500977af1134e1ce82075. Ветка обновлена merge из main без force-push. Изменены только корневые quality/ и docs/Q1_TEST_PLAN.md. Ревьюер Санёк, @ikeepthatjuice. CI success, run42284215716. Новый SHA требует актуального approval.
- Q2/Q3: #6, feat/llm-qa-model. Точный итоговый SHA см. PR. Изменены только src/android/engine/llm/ и корневые docs/. Ветка включает bootstrap через merge main, общие файлы не редактируются. Draft: апрув не запрашивается. Илья — код LLM, Санёк — документация, Ярик — build/native/API после технического согласования.
- B1 #3 уже APPROVED Саньком, затем слит командой; дополнительный approval не создавался.
- B2 #5, SHA662c71688f0df5a349b3c750388bb6af803ea16b: проверен полный diff, API подтверждён и APPROVED Сюбиком. На момент review CI отсутствовал из-за базы feat/back-bootstrap; это указано в review. B2 не выдавался за слитый в main.

## Выполненные проверки

1. `python3 -m unittest discover -s quality/tests` — 28 tests OK, Python3.12.14. `python3 quality/check_export.py quality/fixtures/synthetic_export.json --duration-ms 4000` — PASS. На ветке Q1 после merge bootstrap выполнен штатный `bash scripts/check-forbidden.sh` — PASS. `git diff --name-only origin/main...HEAD` показывает только семь файлов Q1; git status чистый.
2. Kotlin2.0.21/JRE17.0.20/coroutines1.8.0/JUnit4.13.2: exact Contracts.kt, Pipeline.kt и ContractsTest.kt из B2 #5 собраны с исходниками и тестами LLM. JUnitCore: `core.contracts.ContractsTest engine.llm.TranscriptValidatorTest engine.llm.LocalTranscriptPostprocessorTest engine.llm.NativeSmokeTest` — 28 tests OK (4 contracts +24 LLM). Использован compiler-embeddable вне git; собственных DTO не добавлено. Прямой вызов настоящей Qwen дал ответ; проверенная синтетическая реплика принята без fallback. Native cancellation и reuse прошли. Linux-время тестов не является Android RTF.
3. CMake native build target transcript_llm: Linux/GNU13.3/CMake3.31.6 — PASS; Android arm64/API26/NDK26.1.10909125 — PASS с CMake3.31.6 и отдельно SDK CMake3.22.1. `nm -D --defined-only` на Linux и ARM64 показывает только Java_engine_llm_NativeBridge_open/generate/free. Совместный APK с whisper не проверялся и не линкуется.
4. Отдельный Android spike: Gradle8.9, Temurin JDK17.0.17+10, AGP8.5.2, Kotlin2.0.21, SDK34/build-tools34.0.0, NDK26.1.10909125, SDK CMake3.22.1. Команда `gradle -p src/android/engine/llm/spike --no-daemon --max-workers=4 -PllmSpikeBuildDir=/tmp/syubik-llm-spike-build assembleDebug lintDebug` — BUILD SUCCESSFUL, 49 tasks. JAVA_HOME/ANDROID_HOME заданы локально; сетевой proxy среды применялся только для загрузки build-зависимостей. Native inference не использует сеть. Первая повторная упаковка в общем scratch build-каталоге упала на повреждённом промежуточном архиве; чистая сборка с внешним output-каталогом прошла. Ошибка не скрыта отключением lint.
5. `apksigner verify --verbose` — Verifies, подпись APK v2. `aapt2 dump permissions` — только package com.neurostenographer.llmspike, разрешения INTERNET нет. ZIP integrity PASS, внутри assets — правильный GGUF, MIT/Apache лицензии и notices; lib/arm64-v8a/libtranscript_llm.so.
6. Staged-файлы проверены: только код, синтетика, документация и лицензии. Веса/SDK/native runtime sources/build outputs/signing key не отслеживаются. git diff --cached --check и check-forbidden — PASS. Для common build/manifest/DI отправлен технический вопрос Ярику и Илье в PR #6; approval Draft не запрашивался.

## APK и веса

Отдельный test APK, не релиз продукта: 652849208 bytes (~623 MiB).

APK SHA-256: `ec9bb68716dacbaf02bd2c834ac96325d7b19f7dfccdbd5f826e3f1bf429dc83`.

Встроенная Qwen3-0.6B-Q8_0.gguf: 639446688 bytes; SHA-256 `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`. Источник и ревизии закреплены в engine/llm/dependencies.json; веса реально проверены как отдельно, так и внутри APK. Код llvm runtime — llama.cpp954de248ac6907dd05dc19f0f511ab18f2807a54, MIT; веса Qwen revision23749fefcc72300e3a2ad315e1317431b06b590a, Apache-2.0. Сведения переданы back в PR #6 для единого manifest; чужой models/manifest.json не менялся.

## Не выполнено

Android-устройство: отсутствует, `adb devices` — пусто. Установка/запуск на телефоне, авиарежим, RAM/нагрев/Android RTF — NOT RUN. Реальный Linux inference, сборка Android .so и APK не заменяют этот критерий. Полный pipeline/экспорт с настоящим аудио, реальные эталоны и holdout — NOT RUN; Q1 целиком и Q2 device acceptance не закрыты.

Попытка сделать большой APK доступным для скачивания не завершилась успешно; исходники и инструкция отдельной сборки доступны в PR #6. APK не коммитится.

Организаторам/общему чату сообщения не отправлены: пользователь выбрал переслать сам. Готовый текст: «Подтвердите, пожалуйста: засчитывается ли локальная LLM для пунктуации/регистра с неизменными словами как обязательное использование LLM? Каков максимальный размер APK и можно ли включать веса внутрь? Наш отдельный Qwen3-0.6B Q8_0 spike со встроенными весами получается около623 MiB; Android-запуск на телефоне ещё проверяем».
