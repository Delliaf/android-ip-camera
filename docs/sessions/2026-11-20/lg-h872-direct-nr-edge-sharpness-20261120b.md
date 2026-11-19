# LG G6 H872 — резкость, шумоподавление и проблемы Direct Camera2

- **Статус:** working
- **Поступило:** 2026-11-20
- **Запрос:** проанализировать предоставленный ZIP диагностики `LG-G6-main0-20261120-015819.zip`, выяснить почему качество раньше было резче, почему отсутствует отдельное управление шумодавом, исправить код и проверить.
- **Ветка-основа:** `fix/web-controls-battery-temp-20261120@9f7c24508e8210ce7b4e38d499924429639a9b57` (draft PR #6, последняя подтверждённая .9).
- **Зависимости:** PR #1 → #3 → #4 → #5 → #6; PR #2 параллелен. Новая задача создаёт собственную `fix/lg-g6-noise-edge-sharpness-20261120`.
- **Архитектурные ограничения:** LG-H872, Camera2 ID0, Android 8, arm64-v8a only, сохранить CameraX+Direct/RTSP/ONVIF. Не публиковать сырые приватные ADB логи, IP и идентификаторы.

## Реальный исходный архив (приватный, исследован локально)

- `README-FIRST.txt`: ADB, root true, LG-H872; захват диагностического архива с 01:58.
- `20-camera-log-start.txt` и `40-camera-log-end.txt`: Direct Camera2 **PRIVATE 960×720**, ZSL/PHOTO profile 5, `SCALER_CROP_REGION=Rect(1568,1178-2608,1958)` (native ~1040×780, zoom 4×); `NR=4`, `EDGE=3`, `ABERRATION=1` **во всех 19 последних лог-записях**. `ISO=2750`, `SENSOR_EXPOSURE_TIME=100000000 ns` (100 ms), frameNs≈100105216; так что реальный вывод при плохом освещении ~10 fps, а не запрошенный FPS=15/30.
- UI текущего кода позволяет `lg_main0_processing` fast/hq **только на CameraX**; при Direct он disabled; в `LgG6Main0DirectCapture.submit` нет явных override NR/EDGE, шаблон ZSL оставляет ZSL шумодав/резкость HAL. Отсюда конкретное отсутствие независимого управления.
- Логи содержат **CameraX TimeoutException: Future ... is not done within 5000 ms** при переключении/открытии CameraX, а также много **TLS Handshake failed**, и сообщения Qualcomm metadata `enable`/`is_main` byte vs int32. Не путать это с качеством кадров, но оставить как потенциальную проблему HAL/сетевой конфигурации.
- `11-cpu.txt`: процесс приложения порядка 45% одного ядра, `mm-qcamera-daemon` порядка 42% одного ядра в одном срезе `top`; не считать эти проценты доказательством общей нагрузки.
- `04-battery.txt`: температура батареи `321` = **32.1°C** (значение на момент диагностики), уровень 100%, AC powered true. В ранее присланном автотесте SoC были ~49–61.9°C — это разные физические источники.
- `15-root-thermal-zones.txt`: диагностическая root-команда потерпела ошибку синтаксиса оболочки `syntax error: 'do' unexpected`, то есть этот файл не содержит достоверного списка thermal зон.
- Данные не содержат точного измерения perceptual sharpness на одной статичной сцене; нельзя утверждать, что конкретно NR=4 привёл к размытию: длинная выдержка и низкий source-detail тоже могут влиять.

## План

1. В новом `CaptureBackend` expose независимые `setLgNoiseReductionMode` и `setLgEdgeMode` с дефолтом `auto` (оставляет шаблон как прежде), поддержка OFF/FAST/HIGH_QUALITY/NR MINIMAL/ZSL, проверка advertised Camera2 modes.
2. Direct: применять override **после** profile/template; CameraX: объединять NR/EDGE override с существующим captureRequestOptions без очистки manual focus/ISO/shutter; поддерживать профили и fallback.
3. Web UI: отдельные selectors Noise reduction и Edge sharpening на LG ID0 для обоих backend; хранить prefs, показывать current request + actual `CaptureResult` в логах. Не обещать усиление реальной детализации по одному переключателю.
4. Добавить pure Kotlin tests логики выбора mode, UI JS tests и CI branch trigger. Сохранить старое поведение `auto`, по умолчанию без переопределения OEM шаблона.
5. Обновить этот журнал и подробный доменный файл, PR, Actions SHA/Artifact; физическое сравнение на LG — отдельный обязательный шаг.

## Продолжение

- Этап 1: проверить методы существующих камер и реализовать NR/EDGE.
- Этап 2: запустить CI на точном SHA; если не проходит — исправить.
- Этап 3: установить APK на телефон, снять сравнение при одном освещении/ISO/focus/zoom/шторке, получить фактический NR/EDGE и оценить качество вместе с FPS.


## 2026-11-20 — реализация завершена и CI успешно прошёл

- **Кодовая ветка:** `fix/lg-g6-noise-edge-sharpness-20261120`, отдельный [draft PR #7](https://github.com/Delliaf/android-ip-camera/pull/7), база PR #6.
- **Точный функциональный SHA:** `9764aea7df873e91d5ef5d83b3a276cb5296c926`.
- **Версия:** `0.18.0-delliaf.10` / versionCode 190, Android 8+, ARM64-V8A only.
- **CI:** https://github.com/Delliaf/android-ip-camera/actions/runs/44635287805 — `completed / success` на точно том же функциональном SHA, что и HEAD feature-ветки на момент записи.
- **Успешные CI шаги:** `Validate embedded browser JavaScript` (включая `scripts/test-lg-isp-ui.cjs` и старые UI control/thermal tests), `Test and build debug APK` (`testDebugUnitTest`, `assembleDebug`), `Verify APK contains only arm64-v8a JNI libraries`, `Upload installable debug APK`.
- **Artifact:** `android-ip-camera-android8-debug`, GitHub artifact ID `14583651422`, ZIP 11,254,553 bytes. Debug APK находится внутри ZIP, не выдавать имя архива за самостоятельный apk.
- Реальные изменения: `LgG6IspModes.kt`, `CaptureBackend.kt`, `LgG6Main0DirectCapture.kt`, `CameraXCapture.kt`, `StreamingService.kt`, `StreamingServerHelper.kt`, `LgG6Main0VendorProbe.kt`, `index.html`, тесты `LgG6IspModesTest.kt`, `scripts/test-lg-isp-ui.cjs`, `app/build.gradle` и CI.
- В Direct сначала создаётся заданный ZSL/RECORD/PREVIEW request, затем проверяются advertised стандартные Camera2 NR/EDGE и записываются **отдельные** значения. В CameraX NR/EDGE обновляются в общем request builder, не стирая ISO/shutter/focus. Строка `auto` ничего не навязывает и сохраняет старый профиль.
- `LGG6Camera0Result` выводит `requestNR` и `requestEDGE` рядом с фактическим `NR` и `EDGE`; пользователь сможет выявить отклонение запроса.
- По архиву предыдущего 73-кейсового YUV теста сравнены кейсы #28–31: `NR=4 EDGE=3` LaplacianVariance ~790, `NR=4 EDGE=1/2` ~3980 при похожих экспозициях. Визуальный приватный анализ показал усиление **шума вместе с контурами**. Нельзя выдавать эту разницу за выигрыш реального разрешения. Подробности: [доменный технический файл](../../workstreams/camera-lg-g6/2026-11-20-noise-edge-sharpness-diagnostics.md).
- **На реальном LG-H872 новая .10 ещё НЕ проверялась.** Команды управления и физический сенсор NR/EDGE могут вести себя иначе; для подтверждения требуется установка пользователем и фактический `CaptureResult`.

## Обязательные последующие испытания

1. Установить артефакт новой версии, в веб-интерфейсе открыть `/?v=10`, выбрать Camera0, **Direct Camera2 ZSL**.
2. Включить LG Camera0 CaptureResult telemetry, зафиксировать ISO/shutter/focus/zoom/NR/EDGE; сравнить NR=auto, minimal, fast, off при EDGE неизменном и затем EDGE=off,fast,hq при NR неизменном.
3. Для одинаковых профилей сохранить сцены/видео в приватном хранилище; по журналу подтвердить `requestNR=requested`, `NR=actual` и соответствие режиму. Проверить CameraX отдельно.
4. Если проблема резкости сохраняется, изучить 100мс экспозицию/ISO2750, реальную скорость 10fps и переключения CameraX с TimeoutException; тестовое YUV 13MP не равно обычному PRIVATE source960×720.
5. Не смешивать сетевые TLS Handshake failed с применением ISP; если пользователь использует HTTP на TLS-порту, исправить протокол/порт и проверить ONVIF/go2rtc отдельно.
6. Не публиковать сырой ZIP или приватные изображения/адреса в GitHub.

**Status:** `CI-passed` / APK-built. Device verified: **NO**. PR #7 оставить draft до аппаратных тестов.
