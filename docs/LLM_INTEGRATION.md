# Q2/Q3 — локальный postprocessor: передача Ярику и Илье

Ветка: feat/llm-qa-model. Основа main на старте: 6894b29b0c43f1edc27969b2825fe387e00ab15c (пустой initial commit). PR #1, #2, #3 проверены как открытые, не слиты. Общую сборку, contracts, model manifest, CI и app/DI эта ветка не меняет.

Прочитаны приложенные PROJECT_PLAN.md v3.0, SYUBIK_LLM_QA.md, TEAM_START.md. Санёк уже APPROVED PR #3 f09c91f (19:31 UTC); дополнительный approval не создавался. После старта появился B2 PR #5, SHA 662c71688f0df5a349b3c750388bb6af803ea16b: проверен фактический diff, LLM-модуль с этими исходниками прошёл 28 tests (4 contracts +24 LLM/JNI). Как потребитель Сюбик одобрил этот API; CI на SHA пока отсутствует, B2 не слит. В main нет AGENTS.md: прочитано предложение из PR #3 f09c91f, принято расположение модулей src/android/. Изначально проверялись предложенные типы PR #2 427cf4e; итоговая проверка выполнена на точных Contracts.kt/Pipeline.kt из B2 PR #5 662c716. Это НЕ утверждённые merged contracts. Их копия в этой ветке не создаётся. До согласования и слияния bootstrap/contracts этот PR не готов к merge.

## Реализация

- LocalTranscriptPostprocessor реализует общий `suspend process(List<Utterance>): List<Utterance>` из текущего предложения contracts.
- LlamaCppTextGenerator загружает проверенный GGUF и действительно вызывает локальный llama.cpp через JNI. JSON, ID, speakerId и времена модель не получает.
- TranscriptValidator проверяет число/порядок реплик и неизменность id, speakerId, startMs, endMs, rawText. Сравнивает слова по порядку после NFC и lower-case (Locale.ROOT). Ё отличается от е; отрицания и числа словами не удаляются. Отдельно сохраняет числа с десятичными разделителями, знаками, датами/дробями и символами единиц, а также защищённые символы. Разрешено менять ограниченный набор пунктуации и регистр. Это консервативная лексическая проверка, не доказательство неизменности любого оттенка смысла пунктуации.
- При искажении displayText возвращается rawText с диагностикой FORMAT_REJECTED. Ошибка загрузки/модели/decode не превращается в успешный raw fallback; исключение идёт в pipeline. CancellationException не поглощается.
- Реплики >400 символов делятся по пробелам с сохранением разделителей; неразрывный токен длиннее лимита — явная ошибка. Выход ограничен токенами, байтами и контекстом. На достижении лимита генерации — ошибка, не усечённый «успех».
- Model хранится до suspend close(); контекст создаётся на каждую порцию и освобождается RAII. Mutex сериализует native-вызовы/освобождение. Отмена runInterruptible проверяется при загрузке, prefill, decode и через CPU abort callback. Close выполняется в NonCancellable после завершения текущего вызова.
- В native-библиотеке наружу экспортируются только JNI symbols. ggml/llama статические и скрыты version-script; совместный APK с whisper всё равно требует проверки Ярика.
- Диагностика — enum без реплик/путей. Нативные логи отключены. Аудио/кейсов/секретов в тестах нет.

## Закреплённые зависимости

Источник — `src/android/engine/llm/dependencies.json`. llama.cpp MIT, commit 954de248ac6907dd05dc19f0f511ab18f2807a54. Внешний код явно получает fetch-runtime.sh; CMake сверяет HEAD и отсутствие правок tracked sources, сам ничего не скачивает.

Официальная модель Qwen/Qwen3-0.6B-GGUF, revision 23749fefcc72300e3a2ad315e1317431b06b590a, Q8_0, 639446688 bytes, Apache-2.0. SHA-256 9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031. Выбрана доступная официальная квантовка, не неподтверждённый Q4 файл. Потребление RAM на телефоне ещё не измерено. Веса и внешние исходники не коммитятся. URL/хеши проверены по метаданным официального HF repository; вес проверяется перед native load.

В основном модуле Kotlin/AGP/coroutines/JUnit берутся из libs aliases bootstrap PR #3; изолированный spike закрепляет те же версии отдельно, не меняя общий каталог: Kotlin 2.0.21, AGP 8.5.2, coroutines 1.8.0, JUnit 4.13.2. В модуле compileSdk 34, minSdk 26, JVM17, NDK26.1.10909125, CMake3.22.1. Корневой каталог версий не изменён.

## Предложение общих изменений — выполняет/подтверждает Ярик

До отдельного согласования Ярика и Ильи module не линкуется с whisper или общим APK. Отдельная Android spike-сборка находится в engine/llm/spike и использует только runtime, без копий DTO. Дальнейшие предложения после согласования и независимого ревью bootstrap/contracts:

1. Согласовать пакет core.contracts и расположение его исходников в стандартном source set модуля. Не переносить Contracts.kt из PR #2 вслепую поверх других типов.
2. В src/android/settings.gradle.kts добавить `include(":engine:llm")`. В app dependencies — `implementation(project(":engine:llm"))`. Эти изменения здесь не внесены.
3. Внести сведения из dependencies.json в единый models/manifest.json и явную доставку весов. По AGENTS из bootstrap эксперту нужен APK с весами: при сборке включить GGUF в assets (без коммита веса), а при первом запуске скопировать в app-private filesDir, сверить SHA. Процедура/размер APK и лимиты сдачи требуют подтверждения. Не использовать недоступный app путь /data/local/tmp как production-путь.
4. App/DI создаёт runtime с File из filesDir и закреплённым SHA, затем LocalTranscriptPostprocessor(runtime, diagnostic=...). Pipeline переводит исключения в типизированное состояние Failed и накапливает enum diagnostics в job warnings; отмена должна оставаться отменой. При пустом тексте INPUT_SKIPPED не означает, что обязательная LLM запускалась.
5. По окончании владения runtime вызвать suspend close() из finally. Одновременные ASR/LLM jobs и RAM контролирует pipeline. При offline-проверке веса уже установлены; runtime не скачивает их.
6. После подключения запускать из src/android: `./gradlew :engine:llm:testDebugUnitTest :engine:llm:assembleDebug :engine:llm:lintDebug`. Это предлагаемые tasks Android library plugin, их выполнение в общей сборке пока не подтверждено.

Этот документ — запрос технического согласования через Draft PR, не утверждение, что согласование уже получено.

## Проверка runtime отдельно от Android

На Linux с JDK17 и JNI headers:

```bash
bash src/android/engine/llm/fetch-runtime.sh
cmake -S src/android/engine/llm/src/main/cpp -B /tmp/llm-build \
  -DLLAMA_CPP_DIR="$PWD/src/android/engine/llm/external/llama.cpp" -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/llm-build --target transcript_llm -j4
```

NativeSmokeTest — опциональный JUnit integration test. LLM_SMOKE_MODEL задаёт внешний локальный GGUF; java.library.path указывает каталог libtranscript_llm.so. Без переменной тесты явно skipped. Облачные API не используются; Linux результат не заменяет Android acceptance.

Android native compile отдельно от Gradle (при установленном NDK): передать CMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake, ANDROID_ABI=arm64-v8a и ANDROID_PLATFORM=android-26. Сборка .so не доказывает работу APK на телефоне.

## QA starter

QA starter вынесен в отдельную ветку feat/llm-qa-test-plan, PR #4, с quality/ в корне, без реальных данных: checker экспорта, WER/RTF, 28 Python-тестов и synthetic fixture. Python3.12.14, только стандартная библиотека. Команды из корня репозитория:

```bash
python3 -m unittest discover -s quality/tests -v
python3 quality/check_export.py quality/fixtures/synthetic_export.json --duration-ms 4000
```

WER/RTF tool не используется как production validator: его более мягкая нормализация чисел предназначена для согласуемой методики ASR. Production guard отдельно защищает пунктуацию числовых выражений. Подробные данные/референсы — только локально. Ни WER, ни Android RTF в этом PR не заявлены.

## Android acceptance — пока NOT RUN

Чистая установка APK со встроенными весами; авиарежим; неизвестная запись; зарегистрирован INFERENCE_COMPLETED для непустого текста; сравнение raw/display, ID и времени; отмена/retry; память/нагрев; экспорт JSON отдельным checker. Reviewer LLM — Илья, интеграция/common build — Ярик, docs — Санёк. Слияние только командой после реальных проверок.
