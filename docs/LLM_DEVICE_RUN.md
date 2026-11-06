# Передача LLM spike на телефон

Изолированный APK PR #6, package `com.neurostenographer.llmspike`, ARM64/API 26+. Новый артефакт пересобран из того же кода вручную через Android build-tools; это отдельный артефакт от прежней Gradle-сборки. Сборка Gradle: [SETUP.md](SETUP.md). Запуск на физическом телефоне пока NOT RUN.

## Новый артефакт передачи

- Исходники LLM: `a0117d4304172a9dc95e456f067bdb915f217a76`; последующий коммит #6 меняет только удаление docs/REVIEW_RULES.md.
- APK: `llm-device-spike-debug.apk`, 643547988 bytes (около 614 MiB).
- SHA-256: `b1d950dc0ecb791bdf711748c5b654555fa0c10a8e12f828467ea4bef8c10eb4`.
- Q8_0 внутри: 639446688 bytes, SHA `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031` проверен после упаковки.
- Инструменты: JDK 17.0.17+10, kotlinc 2.0.21, coroutines core/android 1.8.0, D8 8.5.35; aapt2/zipalign/apksigner build-tools 34.0.0; native CMake 3.31.6, Ninja 1.11.1.3, NDK 26.1.10909125, API 26/ARM64. Debug signing key локальный, не коммитится.
- Проверены v2/v3 APK signatures, ZIP integrity, zipalign 4 KiB, ARM64 JNI и ровно три экспортируемых JNI entry points. `aapt2 dump badging`: нужные package/activity, minSDK 26, targetSDK 34, permissions отсутствуют.
- Gradle `assembleDebug lintDebug` в текущем окружении НЕ завершён: получение плагинов/dependencies. Нельзя переносить прежний BUILD SUCCESSFUL на новый ручной артефакт. Физический запуск, UI и RAM этим статическим контролем не подтверждены.

Для передачи подготовлены `LLM_APK_part1.zip` (419431757 bytes) и `LLM_APK_part2.zip` (224118945 bytes), внутри — две части APK и INSTALL.txt. Распаковать оба в одну папку и выполнить:

```bash
cat llm-device-spike-debug.apk.part1 llm-device-spike-debug.apk.part2 > llm-device-spike-debug.apk
shasum -a 256 llm-device-spike-debug.apk
```

Сверить SHA выше перед установкой. Веса и APK находятся вне истории исходников. Telegram/чат организаторов не подключены; фактическая отправка Ярику и ответ организаторов ещё не подтверждены.

## Что нажать

1. Установить `llm-device-spike-debug.apk`; включить авиарежим, отдельно выключить Wi-Fi и мобильную сеть.
2. Открыть «LLM offline spike». Нажать «Запустить локальную LLM». Первый запуск копирует веса и проверяет SHA, затем запускает JNI inference.
3. Дождаться «Настоящий inference завершён за … мс». На экране будут фиксированная вымышленная исходная фраза и ответ LLM. Сфотографировать экран или записать результат на синтетике. Сообщение об ошибке/отмене не засчитывать как успешный inference.
4. Повторить (warm run), затем проверить «Отменить» и повторный запуск. Не загружать реальные материалы в этот spike.

## Одной командой после подключения USB

Из корня репозитория, APK лежит по стандартному пути после сборки:

```bash
adb install -r src/android/engine/llm/spike/build/outputs/apk/debug/llm-device-spike-debug.apk && adb shell am start -n com.neurostenographer.llmspike/engine.llm.spike.SpikeActivity
```

После старта нажать кнопку на экране. Без компьютера достаточно передать APK файлом и установить через Android installer.

## Что смотреть в logcat

`SpikeActivity` текущего PR показывает результат **на экране**, отдельного `Log.i` с временем/ответом нет. Logcat нужен для crash, JNI load и системных ошибок; отсутствие ошибок в нём не подтверждает inference.

```bash
adb logcat -b crash -d
adb shell pidof com.neurostenographer.llmspike
# Подставьте PID из предыдущей команды (после перезапуска он меняется):
adb logcat --pid=<PID> -v threadtime
adb shell dumpsys meminfo com.neurostenographer.llmspike
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
adb shell settings get global airplane_mode_on
```

Снимите `dumpsys meminfo` во время inference и отдельно после него. Разовый PSS после завершения не является пиком RAM; если пик не измерен, так и написать. Время в UI охватывает `generate`, включая ленивую загрузку модели, но исключает копирование assets до вызова; полный cold-start замерять отдельно секундомером от нажатия.

## Запись результата Ярика

```text
Commit приложения:
SHA-256 и размер APK:
Телефон / Android / ABI / общая RAM:
Дата и время с часовым поясом:
Авиарежим / Wi-Fi выключен / mobile data выключен:
Cold: полное время от нажатия / время generate из UI:
Warm: время generate из UI:
RAM: PSS во время inference / после; пик измерен да/нет:
Ответ LLM показан / ошибка:
Отмена и повторный запуск:
```

Это доказательство runtime на Android; качество postprocessor и весь аудиопайплайн требуют отдельных проверок. Веса и APK в исходники git не добавлять.
