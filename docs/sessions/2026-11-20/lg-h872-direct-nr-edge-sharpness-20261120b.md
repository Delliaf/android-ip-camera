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
