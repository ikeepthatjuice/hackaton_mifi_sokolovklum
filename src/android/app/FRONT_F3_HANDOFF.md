# F3 — pipeline UI

Android-пути относительны к src/android/. Core/engine/DI/version catalog front не меняет.

PipelineRoute(pipeline, resultContent) — точка подключения B4. PipelineSession принадлежит
ViewModel; только пользовательское действие запускает start. Старт вынесен на IO dispatcher,
observe собирается в scope ViewModel, UI подписывается через collectAsStateWithLifecycle.
Поворот/повторная композиция не создают новую модель или job. Повтор start того же активного
URI возвращает текущий jobId; другой файл нельзя запустить до окончания/отмены текущего.
При уничтожении ViewModel активная задача отменяется; фонового продолжения после убийства
процесса эта часть не обещает. attach(jobId) только подписывается, не вызывает start.

Защита от старых состояний: generation + jobId; после терминального состояния jobId
обнуляется, результат/ошибка сохраняются до reset(). reset возвращает импорт. Отмена
показывается только после PipelineState.Cancelled, а не сразу после нажатия кнопки.
Поле actionError отделено от типизированных ошибок pipeline.

Debug pipeline только в app/src/debug: экземпляр удерживает отдельный ViewModel,
состояния и реплики синтетические, выбранное аудио не читается. Release без fixtures.
На main-старте release настоящий pipeline подключает Ярик через согласованный DI-путь.

Экран PipelineFailureScreen принимает requiredBytes и показывает «Освободите N МБ»
с округлением вверх без переполнения Long. Пока #11 не в main, чтение Failed.requiredBytes
в route не включено. После merge #11 добавить typed INSUFFICIENT_STORAGE в mapper
и передать requiredBytes в экран; никакого разбора строк ошибок не требуется.

Зависимости: требуются aliases androidx-lifecycle-viewmodel-ktx,
androidx-lifecycle-viewmodel-compose, androidx-lifecycle-runtime-compose на существующем
version.ref androidx-lifecycle (2.8.6). Запрошены у Ярика; до их появления сборка заблокирована.
Корневой каталог front не редактирует.

Семь тестов PipelineSessionTest: повторный start, позднее состояние старого job,
attach без start, подтверждённая отмена, запрет нового URI при активном job,
закрытие владельца, безопасное округление storage. Это не UI/device-тесты.
Команды: bash scripts/check-forbidden.sh;
cd src/android && ./gradlew assembleDebug test lint.
Локального JDK нет; до результата CI успех тестов/сборки не заявлен.
Reviewer: Ярик @lockermon4; интеграцию также передать «на ревью Claude» через него.
