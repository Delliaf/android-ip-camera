# Архитектура приложения и карта исходников

**Срез 2026-11-20.** Реализации отличаются между `main` и экспериментальными ветками; проверяй каждый путь в **целевой ветке** перед изменениями.

## Основное приложение

- `app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/` — Kotlin/Android логика.
- `StreamingService.kt` — жизненный цикл камеры/стриминга, выбор capture API, пресеты/параметры, H.264/RTSP и ONVIF зависимые сервисы.
- `activities/MainActivity.kt`, `SettingsActivity.kt` — приложение и настройки, в экспериментальных ветках есть запуск автотеста.
- `app/src/main/assets/index.html` — встроенный HTML/CSS/JavaScript управления и MJPEG/H.264 viewer.
- `app/src/main/res/xml/preferences.xml`, `app/src/main/res/layout/activity_main.xml` — системные настройки, кнопки Android UI.
- `app/build.gradle` + `.github/workflows/*.yml` — APK, JVM tests, Rust/NDK, ARM64, GitHub CI.

## Захват / обработка

- `helpers/CaptureBackend.kt` — интерфейс различных capture backend.
- `helpers/CameraXCapture.kt` — CameraX/Camera2Interop, объединённые ISO, shutter, zoom, focus и стандартные ISP режимы.
- `helpers/LgG6Main0DirectCapture.kt` — экспериментальный **прямой Camera2 ID0** → PRIVATE SurfaceTexture; реальный `TEMPLATE_ZERO_SHUTTER_LAG(5)` против `TEMPLATE_RECORD(3)`, вывод в существующий GLES/H.264 путь. Не гарантируется, что шаблон ZSL воспроизводит проприетарный LG ISP.
- `helpers/CameraGlPipe.kt` + `RoiCrop.kt` — OpenGL crop/rotation/scale, aspect-aware ROI, которые должны учитывать фактические source/output размеры, а не только желаемое разрешение.
- `helpers/LgG6Main0Profile.kt`, `LgG6Main0VendorProbe.kt`, `LgG6Main0Math.kt` и соседние классы — защита LG-H872 + Camera ID0, проверка OEM ключей, ROI/экспозиция. Не путать advertised request keys и обнаруженные строки в OEM DEX.
- `helpers/H264HardwareEncoder.kt`, `helpers/H264StreamingEncoder.kt` — H.264 кодирование и распространение кадров. Запросить 4160×3120 ≠ получить реальный поток в этом разрешении.
- `helpers/LgG6PhotoPreviewTuning.kt` — стандартная настройка NR/EDGE и гипотеза LG PHOTO ZSL в стороннем Camera2 приложении.

## Сеть

- `helpers/StreamingServerHelper.kt` — веб-сервер, GET /info.json, /metrics.json, /video/mjpeg, /video/h264, управляющие endpoint и авторизация.
- `helpers/RtspServer.kt` + `helpers/OnvifServer.kt` — RTSP/ONVIF. go2rtc `onvif://` может пытаться отправить HTTP на ONVIF порт, где по умолчанию включён TLS; в экспериментальной ветке можно управлять TLS ONVIF отдельно. Учитывать HTTP Basic authentication / TLS.
- `helpers/TlsHelper.kt` — конфигурация TLS.
- `helpers/DevicePerformanceMonitor.kt` — CPU и thermal с учетом ограничений Android 8; на последних ветках есть optional read-only root и корректное различение SoC и Battery.

## Автоматические тесты изображения

- `helpers/LgBenchmarkPlan.kt` — ограниченная матрица Camera2 профилей; quick ≤24, deep ≤90, zoom фиксирован 4,5×.
- `helpers/LgBenchmarkImaging.kt` — YUV crop, JPEG, luma, clipping, edge/laplacian, temporal difference.
- `helpers/LgMain0ImageBenchmark.kt` + `activities/LgMain0BenchmarkActivity.kt` — эксклюзивная сессия Camera0, максимальное **фактически отдающее YUV** разрешение, ZIP с JPEG/CSV/JSON и пользовательский экспорт. H.264/RTSP во время теста приостанавливаются.
- UI-гарантии, корректность YUV, фокус, thermal и pause/resume требуют проверки LG H872; успешная JVM-сборка ≠ аппаратная проверка.

## Артефакты/документы с деталями

Подробные прежние заметки сохранены на прежних feature-ветках: `LG_G6_H872_MAIN0.md`, `LG_G6_MAIN0_IMAGE_BENCHMARK.md`, `FORK_ANDROID8.md`, `DEVELOPMENT_HISTORY.txt`. Их архивная копия и/или ссылки хранятся в [docs/history](../history/README.md). Для любой новой задачи открой актуальную версию этих файлов именно из выбранной ветки и сравни с исходниками.

**Важно:** никакие закрытые LG HAL SO или APK не добавляются в репозиторий. Разбор OEM библиотек из пользовательских материалов служит гипотезой и должен подтверждаться реальными логами/кадрами.
