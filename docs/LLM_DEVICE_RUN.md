# Передача LLM spike на телефон

Изолированный APK PR #6, package `com.neurostenographer.llmspike`, ARM64/API 26+. На момент написания бинарник прежней сборки недоступен в текущем рабочем каталоге и не приложен к этому документу. Передача APK ещё не выполнена. Сборка: [SETUP.md](SETUP.md).

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
