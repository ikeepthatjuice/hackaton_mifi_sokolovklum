# Отдельный Android LLM spike

Тестовое приложение `com.neurostenographer.llmspike`. Оно не подключается к общей сборке и не содержит whisper.cpp, ASR или pipeline. Использует настоящий JNI inference на фиксированной вымышленной фразе. Общие contracts не копируются: source set включает только runtime и его собственный TextGenerator.

## Подготовка

JDK17; Android SDK34/build-tools34.0.0; NDK26.1.10909125; SDK CMake3.22.1; Gradle8.9. Версии плагинов повторяют bootstrap PR #3 для изолированного эксперимента; общие version catalog/settings.gradle.kts не меняются. `ANDROID_HOME` и `JAVA_HOME` должны указывать на установленный SDK и JDK. На устройстве нужен ARM64 и Android8.0/API26 или новее. Это минимальные требования сборки; фактическая память и совместимость телефона ещё проверяются.

Из корня репозитория:

```bash
bash src/android/engine/llm/fetch-runtime.sh
```

Модель: файл/URL, revision, SHA и лицензия закреплены в `../dependencies.json`. Скачать явно, проверить SHA, затем скопировать **локально** в `spike/src/main/assets/Qwen3-0.6B-Q8_0.gguf`. Этот файл игнорируется git, коммитить его запрещено. Сверять размер 639446688 bytes и SHA-256:

```text
9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031
```

В runtime скачивания нет. LICENSE-Qwen3.txt и LICENSE-llama.cpp.txt уже включены в assets вместе с notices. После установки APK вес переносится из assets в filesDir и проверяется перед загрузкой. Возможность такого размера APK у организаторов ещё не подтверждена.

## Сборка и запуск

```bash
gradle -p src/android/engine/llm/spike --no-daemon --max-workers=4 assembleDebug
adb install -r src/android/engine/llm/spike/build/outputs/apk/debug/llm-device-spike-debug.apk
```

Использовать Gradle8.9. После merge bootstrap можно вызвать его wrapper с `-p engine/llm/spike` из src/android; текущая main может ещё не содержать wrapper. Общую settings.gradle.kts для эксперимента не редактировать.

Включить авиарежим, открыть «LLM offline spike», нажать «Запустить локальную LLM». Приложение показывает ответ модели и время реального вызова; это runtime-spike, не оценка WER/RTF всего продукта. Проверить «Отменить» и повторный запуск. Для записи результата зафиксировать модель/Android/ABI/RAM телефона, APK SHA-256, время, факт авиарежима и исходную версию кода. Не добавлять реальные данные кейса.

Если assets отсутствует или checksum не совпадает, тест должен показать ошибку, а не исходный текст с заявлением об успешной LLM. Полный production guard проверяется unit/native integration тестами основного модуля.

## Текущий результат

Отдельный APK собран на Linux. На Android-устройстве NOT RUN: `adb devices` не показал устройств. Точный отчёт и хеш APK — в корневом docs/LLM_VALIDATION.md. Перед общим APK Ярик и Илья должны согласовать native/ggml интеграцию, а команда — пройти проверку на телефоне.
