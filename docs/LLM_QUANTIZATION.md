# Резерв Qwen3-0.6B Q4_K_M

6 ноября 2026. Проверка на полностью синтетическом тексте, Linux x86_64, не Android. Общий APK с whisper не собирался. Веса в git не добавлены.

**Обновление 7 ноября:** расширенный русский прогон — [LLM_RUSSIAN_VALIDATION.md](LLM_RUSSIAN_VALIDATION.md). На 16 русских примерах Q4 изменил слова/имена в 4 ответах; настоящий production postprocessor откатил все четыре к точному rawText. 15/15 намеренно испорченных controls дали откат, 6/6 допустимых форматирований приняты. Исторические 3/3 ниже не означают безошибочность модели на русском.

## Источники и проверенные байты

| Вариант | Источник / фиксированная revision | Файл | Bytes | SHA-256 |
|---|---|---|---:|---|
| Q8_0 | `Qwen/Qwen3-0.6B-GGUF`, `23749fefcc72300e3a2ad315e1317431b06b590a` | `Qwen3-0.6B-Q8_0.gguf` | 639446688 | `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031` |
| Q4_K_M | `prithivMLmods/Qwen3-0.6B-GGUF`, `3fa3255253c99e212675c0825241518cdba8e42c` | `Qwen3_0.6B.Q4_K_M.gguf` | 396704928 | `2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9` |

Обе модели реально скачаны; размер и SHA-256 вычислены локально и совпали с LFS metadata издателя. Q4 — сторонняя квантизация, не файл из официального Qwen GGUF repository; model card заявляет Apache-2.0. Перед распространением Ярик должен перенести источник/лицензии/хеши в общий manifest и включить notices. В закреплённой официальной Qwen revision есть Q8_0, Q4 там нет.

Q4 источник: https://huggingface.co/prithivMLmods/Qwen3-0.6B-GGUF/resolve/3fa3255253c99e212675c0825241518cdba8e42c/Qwen3_0.6B.Q4_K_M.gguf

Экономия весов: 242741760 bytes (37,96%), отношение Q8/Q4 ≈ 1,612. Это размер весов, не измеренный размер запасного APK. Прежний spike жёстко фиксирует Q8 имя/SHA: простая подмена assets файлом Q4 не работает. Для Android Q4 нужны согласованная конфигурация имени/SHA, отдельная сборка и повторный device test. В shared app ничего не линковалось.

## Методика

Один и тот же runtime: llama.cpp MIT commit `954de248ac6907dd05dc19f0f511ab18f2807a54`, JNI/Kotlin/TranscriptValidator из #6 `a0117d4304172a9dc95e456f067bdb915f217a76`. JDK 17.0.17+10; Kotlin compiler 2.0.21; coroutines-core-jvm 1.8.0; Linux CMake 3.31.6, Ninja 1.11.1.3; Release native library, max generation 256, timeout 120 s/case. Linux native library реально собрана; это не ARM64 device run. Сборки других компонентов шли параллельно, поэтому время ниже не бенчмарк скорости моделей.

Синтетические входы: «мы не переносим встречу нужно 12 папок»; «завтра в 10 придёт иван он не опоздает»; «стоимость 12,5 рублей скидка 10%». Никаких данных кейса.

Первый запуск сравнения:

| Модель | Допущено валидатором | Guarded DTO валиден | Изменён текст | generate, мс по трём cases |
|---|---:|---:|---:|---|
| Q8_0 | 3/3 | 3/3 | 1/3 | 1530 / 822 / 900 |
| Q4_K_M | 3/3 | 3/3 | 1/3 | 1201 / 607 / 648 |

Ответы обоих вариантов на всех трёх входах совпали. На втором входе `иван` → `Иван`; первый и третий остались неизменными. Добавления пунктуации здесь не получено. Валидатор не обнаружил изменений слов, чисел, отрицаний; metadata/порядок/rawText проверены на созданных DTO с реальным результатом генерации. Это проверка сохранности на трёх коротких примерах, не доказательство качества пунктуации, WER/DER или пригодности для всего кейса. Постпроцессор всего pipeline и физический телефон этим harness не проверяются.

Версия harness, включённая в PR (каталог весов через `LLM_COMPARE_MODEL_DIR`), также скомпилирована и выполнена: обе модели снова 3/3, выводы совпали с первым запуском. Повторные времена Q8: 1750/801/943 мс, Q4: 2011/612/615 мс. Колебание cold-времени дополнительно показывает, почему по этому прогону нельзя заявлять ускорение на телефоне.

## Воспроизведение

Исходник harness — `quality/llm_compare/Compare.kt`. Нужны исходники `engine/llm` из #6 и точные `core/contracts` из main. Веса хранить вне git; назвать локальные копии `Q8_0.gguf` и `Q4_K_M.gguf`. Harness проверяет SHA через настоящий runtime. Один Linux JNI `.so` используется для обоих вариантов.

```bash
bash src/android/engine/llm/fetch-runtime.sh
cmake -S src/android/engine/llm/src/main/cpp -B /tmp/syubik-linux-build -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_CPP_DIR="$PWD/src/android/engine/llm/external/llama.cpp"
cmake --build /tmp/syubik-linux-build -j 4
# kotlinc 2.0.21; COROUTINES_JAR указывает на kotlinx-coroutines-core-jvm 1.8.0
kotlinc quality/llm_compare/Compare.kt src/android/engine/llm/src/main/kotlin/engine/llm/*.kt src/android/core/contracts/src/main/kotlin/core/contracts/Contracts.kt -cp "$COROUTINES_JAR" -include-runtime -d /tmp/llm-compare.jar
LLM_COMPARE_MODEL_DIR=/path/to/local/weights java -Djava.library.path=/tmp/syubik-linux-build -cp "/tmp/llm-compare.jar:$COROUTINES_JAR" CompareKt
```

Для сравнения используются только публично описанные вымышленные строки; вывод `SYNTHETIC_OUTPUT` разрешён только для этого harness. Общий production runtime текст в логи не пишет. Ярику: это кандидат для manifest, не автоматическое переключение production модели.
