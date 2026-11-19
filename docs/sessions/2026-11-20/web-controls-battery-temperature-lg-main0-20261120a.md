# Исправление неработающих веб-команд и отдельная температура батареи LG G6

- **Статус:** working
- **Дата:** 2026-11-20
- **Запрос пользователя:** на последнем экспериментальном APK веб-панель полностью перестала применять настройки (включая фокус). Пользователь выполнил автоматический Camera0 4,5× benchmark и передал ZIP; просит также независимый показатель температуры батареи.
- **Кодовая ветка:** https://github.com/Delliaf/android-ip-camera/tree/fix/web-controls-battery-temp-20261120
- **Основа:** `fix/lg-g6-metrics-visible@faf3184ba197fed83ffe54d835f77b01c6a5e461`
- **Зависимости:** PR #1 → #3 → #4 → #5; новая работа поверх #5. Параллельный PR #2 содержит отдельную ветку и не считается замерженным.
- **Ограничения:** LG H872 / Android 8 / Camera2 ID0 / ARM64-V8A; стабильность RTSP+ONVIF, безопасность HTTP-auth, не публиковать приватный ZIP с кадрами.

## Выявлено из реального кода

В `helpers/StreamingServerHelper.kt` у метода `handleClientConnection` проверка `if (path == "/" || path == "")` **выдаёт index.html и возвращается раньше**, чем общий обработчик `if (uri.contains("?"))`. В то же время `app/src/main/assets/index.html` функция `sendControl` отправляет `fetch('/?'+param+'='+value+'&ts='+...)`. Поэтому HTTP 200 с HTML воспринимается как успех, но `onControlCommand` вообще не вызывается. Объясняет одновременный отказ *всех* веб-команд без необходимости предполагать поломку Camera2.

План:
- Исправить маршрутизацию, обеспечить legacy `/?zoom=...` совместимость + отдельный проверяемый endpoint `/control/set`, корректный HTTP/JSON ACK и отображаемые ошибки JS.
- Сохранить `/?v=8` для обхода кеша как *страницу*, а не команду, и не отправлять query других GET endpoints в настройки камеры.
- Изолировать температуру Battery от CPU/SoC thermal. `DevicePerformanceMonitor.kt` уже читает battery только при отсутствии SoC, так что добавляется всегда читаемый отдельный `batteryTemperatureCelsius` в `/metrics.json` и `/info.json` + отдельный badge.
- Добавить JVM тесты решения маршрутов и battery tenths, JS тест sendControl с ответами/ошибками и UI battery badge, проверить новую ARM64 Actions build и точный SHA, записать результат.

## Анализ предоставленного ZIP с изображениями (локально, файл не публикуется)

Пользовательский `LG-H872-main0-4p5x-1795122680391.zip` содержит `device.json`, `report.json`, `results.csv`, 73 JPG. `requestedCases=attempted=completed=73`, `stopReason=finished`, `durationMs=107644`, **реально доставлен YUV_420_888 4160×3120**, нормализованный 4,5× JPEG 924×692; точное `image.timestamp` ↔ `SENSOR_TIMESTAMP` совпало в 73/73.
- Templates PREVIEW1: 8/8 кадры нормальной видимой сцены; RECORD3: 8/8 почти чёрные (яркость обычно ~2.76/255, ISO 198, shutter 33.33ms); ZSL5: 57/57 успешных кадров, при AE видны ISO ~2600–2800 и shutter ~90–140ms в этой сцене.
- В режиме manual ISO50–800 и выдержки 4, 8.33, 16.67, 33.33ms `CaptureResult` действительно отвечает на команды, но кадры в темноте при низком ISO почти черные. Это не является доказательством качества RTSP, нет гарантии работы шаблона RECORD3 при других условиях.
- Указана SoC температура 49..61.9°C, CPU -1 на первом замере, позже до 96%. Эти цифры из тестового ZIP, не постоянный idle диапазон и не battery temp.
- Изображения содержат приватную сцену. **Не копировать JPEG/ZIP/неочищенные файлы в публичный GitHub**, только обезличенные технические итоги.

## Хронология

### 2026-11-20 — начало
- GitHub refs/PR сверены, выбрана ветка с последним кодом PR #5.
- Выявлен критический дефект маршрутизации веб-команд. Проведена первичная оценка архива и визуальный просмотр нескольких тестовых JPG.
- **Остаётся:** исправление кода, регрессионные тесты, ARM64 сборка, описание что ещё нужно проверить физически (remote focus/ISO/zoom, battery badge на устройстве).

## Следующие действия
1. Создать независимый testable router и API для control, переставить старый обработчик перед HTML.
2. Добавить battery thermal independently и UI.
3. Запустить CI и проверить SHA/artifact, записать любые ошибки; user hardware test ещё не проведён.


### 2026-11-20 — исправление внесено в отдельную ветку, CI запущен

- Ветка `fix/web-controls-battery-temp-20261120` от `fix/lg-g6-metrics-visible`; [draft PR #6](https://github.com/Delliaf/android-ip-camera/pull/6) в ту же базовую ветку.
- `helpers/WebControlRoute.kt`: чистая логика распознавания legacy root control query, исключение `/?v=...`, проверка key/value.
- `helpers/StreamingServerHelper.kt`: раньше `GET /?focus_distance=...` возвращал HTML, теперь обработка управляющих запросов **перед рендером страницы**. Добавлен `POST /control/set?key=...&value=...&ts=...` с JSON `{ok:true,forwarded:true,keys:[...],timestamp:...}`, 405/400/500 при ошибках. Legacy GET и reset camera сохранены. Ответ `forwarded` сообщает только о вызове обработчика, не доказывает фактическую работу драйвера.
- `index.html`: новый sendControl на отдельном POST endpoint, проверяет JSON + указанный ключ, выводит успех/ошибку рядом с контролами и предупреждает в console; больше не считает HTML-содержимое успешным ответом.
- `helpers/DevicePerformanceMonitor.kt`: независимые измерения SoC/CPU и batteryTemperatureCelsius; battery читается раз в 20 секунд через ACTION_BATTERY_CHANGED, не теряется при работающем SoC датчике; SoC не подменяется температурой АКБ.
- `/metrics.json`, `/info.json`: новое `batteryTemperatureCelsius`; на веб-панели отдельный `🌡️ АКБ xx.x°C` и CPU/SoC остаётся отдельно.
- Проверки: `WebControlRouteTest.kt` (focus/zoom/root query/media endpoints/input guards); `DevicePerformanceMonitorTest.kt` (battery tenths); `scripts/test-web-controls.cjs` (реальный JS sender принимает корректный JSON, отклоняет HTML/401/неправильный ключ); `scripts/test-metrics-dashboard.cjs` (SoC и батарея одновременно в overlay).
- Версия: `0.18.0-delliaf.9` / versionCode `189`, ARM64-V8A only, Android8; CI workflow расширен новой веткой и JS тестом. **Точный функциональный HEAD до документационной записи:** `9f7c24508e8210ce7b4e38d499924429639a9b57`.
- [CI run #44623993393](https://github.com/Delliaf/android-ip-camera/actions/runs/44623993393) на момент записи находится в `in_progress` (Gradle JVM test + assemble). **Не считать APK готовым без SUCCESS**; предыдущий run 44623985932 отменён по concurrency при следующем функциональном коммите.

### Результаты архива — подробная общая документация

Создан [docs/workstreams/camera-lg-g6/2026-11-20-h872-4p5x-real-yuv-results.md](../../workstreams/camera-lg-g6/2026-11-20-h872-4p5x-real-yuv-results.md) с обезличенными 73/73 YUV 4160×3120 результатами, предупреждениями RECORD(3) dark и разделением SoC/аккумулятора.

### Остаточные обязательные проверки

1. Ожидается итог GitHub Actions #44623993393 для SHA 9f7c2450; при ошибке исправить код, перезапустить и записать новый SHA/run/исход ошибок.
2. Реальный LG H872 после установки: проверить **фокусировку, zoom, ISO/exposure, переключение camera**, запросы `/control/set` и legacy `/?focus_distance=...`, наличие JSON ack, фактический CaptureResult. Стабильный feedback на web panel.
3. Проверить `/metrics.json`: одновременно числовые `temperatureCelsius` (CPU/SoC) и `batteryTemperatureCelsius`; правильные подписи, не подменять SoC батареей.
4. Regression: StreamingService restart, Camera0 benchmark, RTSP/ONVIF go2rtc, снижение FPS и температурные лимиты.
5. Нельзя публиковать приватный ZIP/кадры в public GitHub.


### 2026-11-20 — CI для версии 0.18.0-delliaf.9 УСПЕШЕН

**Точно проверенный функциональный SHA:** `9f7c24508e8210ce7b4e38d499924429639a9b57`.

**GitHub Actions run:** https://github.com/Delliaf/android-ip-camera/actions/runs/44623993393
- Статус: `completed`, conclusion `success`.
- `Validate embedded browser JavaScript`: SUCCESS; включая `node scripts/test-metrics-dashboard.cjs` (SoC + отдельная батарея) и `node scripts/test-web-controls.cjs` (POST и JSON подтверждение, ошибка при HTML/401).
- `Test and build debug APK`: SUCCESS; JVM unit tests и `:app:assembleDebug` включая `WebControlRouteTest`, `BatteryTemperatureMath`.
- `Verify APK contains only arm64-v8a JNI libraries`: SUCCESS.
- `Upload installable debug APK`: SUCCESS.
- Artifact: `android-ip-camera-android8-debug`, artifact ID **14578982845**, ZIP 11,251,542 байт, внутри APK `0.18.0-delliaf.9`.
- **Физически не проверено на LG H872**: пользователь должен установить APK и испытать кнопки фокусировки/zoom/экспозиции, проверить реальный `CaptureResult`, `/metrics.json` и `🌡️ АКБ`.
- Запуск #44623985932 завершился `cancelled` по concurrency после более свежего коммита, это не ошибка кода.

При продолжении из нового чата брать код `fix/web-controls-battery-temp-20261120`, draft PR #6; не переиспользовать старый отладочный APK .8 как новую сборку. Дальше: hardware feedback от пользователя, при необходимости правки нового коммита/новая CI.
