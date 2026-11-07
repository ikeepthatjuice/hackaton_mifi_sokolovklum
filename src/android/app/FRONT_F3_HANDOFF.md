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
обнуляется, результат/ошибка сохраняются до reset(). reset возвращает импорт. После подтверждённого Cancelled route автоматически вызывает reset;
при возврате AudioImportRoute также сбрасывает controller.reset(), если job принадлежит F3. Отмена
показывается только после PipelineState.Cancelled, а не сразу после нажатия кнопки.
Поле actionError отделено от типизированных ошибок pipeline.

Debug pipeline только в app/src/debug: экземпляр удерживает отдельный ViewModel,
состояния и реплики синтетические, выбранное аудио не читается. Release без fixtures.
На main-старте release настоящий pipeline подключает Ярик через согласованный DI-путь.

Экран PipelineFailureScreen принимает requiredBytes и показывает «Освободите N МБ»
с округлением вверх без переполнения Long. #11 влит обычным merge origin/main;
route передаёт Failed.requiredBytes, mapper обрабатывает typed INSUFFICIENT_STORAGE.
Для других кодов requiredBytes не используется даже при ошибочном заполнении back.

Зависимости: требуются aliases androidx-lifecycle-viewmodel-ktx,
androidx-lifecycle-viewmodel-compose, androidx-lifecycle-runtime-compose на существующем
version.ref androidx-lifecycle (2.8.6). Back добавил их в #19; #19 уже merged в main 477ce4c, origin/main влит в F3.
Корневой каталог front не редактирует.

Девять тестов PipelineSessionTest: повторный start, позднее состояние старого job,
attach без start, подтверждённая отмена, запрет нового URI при активном job,
закрытие владельца, безопасное округление storage, retry после storage, защита Completed от поздней ошибки. Это не UI/device-тесты.
Команды: bash scripts/check-forbidden.sh;
cd src/android && ./gradlew assembleDebug test lint.
Ночная локальная интеграция (detached checkout, с back #18/#19, не опубликованная ветка):
Temurin JDK17 / SDK34; assembleDebug test lint assembleDebugAndroidTest — PASS.
16 import unit tests (9 PipelineSession + 7 controller) в debug/release — PASS.
Стенд FrontUxPreviewActivity только src/debug, 500 вымышленных реплик, fontScale 2.0.
4 FrontLayoutTest: landscape/recreate, длинный список, storage ceil, disabled Save.
Все 4 FrontLayoutTest прошли на API34 Google APIs arm64-v8a (Pixel6 AVD), авиарежим.
Интеграционный FrontFlowTest из #20 также PASS: настоящий SAF/decode/export, отмена выбора,
отмена pipeline, повтор, recreate, JSON и запуск после Completed (движки только synthetic debug).
Общий connectedDebugAndroidTest: app 5, audio 3, export 2, pipeline 5 — 15/15 PASS.
Guard picker блокирует повторный launch; cancel снимает busy, launch-error отделён от selectionFailed.
После merge origin/main самостоятельные assembleDebug test lint в F3 — PASS.
Актуальный штатный CI и состояние Draft фиксируются в описании #14.
В опубликованную front-ветку попадают back-изменения только через принятую origin/main;
непринятые back-ветки соединялись только в detached checkout для проверки.
Reviewer: Ярик @lockermon4; интеграцию также передать «на ревью Claude» через него.
