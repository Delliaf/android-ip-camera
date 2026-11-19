# LG G6 H872 / основная Camera2 ID 0

**Статус:** кодовая реализация экспериментальная, сборки пройдены, полная оценка качества на телефоне не подтверждена. Подробности: [LG_G6_H872_MAIN0.md в feature-ветке](https://github.com/Delliaf/android-ip-camera/blob/fix/lg-g6-metrics-visible/LG_G6_H872_MAIN0.md) и [архив исследований](../history/README.md).

## Цель пользователя

Максимальная **реальная** детализация при 4–4,5× цифровом приближении, ISO/выдержка/фокус без нежелательной автообработки, качество как в штатном LG PHOTO preview. Формат **4:3 или квадрат 1:1** важнее обязательного 16:9. Для 4160×3120 источника с 4× реальный ROI примерно 1040×780 (4:3); при 4,5× ~924×693. Выход 960×720 или 768×768 не создаёт новых исходных пикселей. Просить 12/13 МП — не то же самое, что фактически их получить.

## Аппаратные сведения (из предыдущих исследований / логов пользователя)

- `LG-H872`, задняя **основная**, Camera2 ID `0`, активная область приблизительно `Rect(8,8,4160,3120)`; не путать с широкоугольной/legacy ID.
- Camera2 ручной `SENSOR_INFO_SENSITIVITY_RANGE` **ISO 50–800**, `LENS_INFO_MINIMUM_FOCUS_DISTANCE` около 10D; есть MANUAL_SENSOR. При AE `CaptureResult` встречались ISO ~1381–1484: это **не доказательство**, что ручной ISO1600/3200 будет применён. Экспериментальная опция extended ISO — off по умолчанию.
- ISP hardware crop до 8×, тип `CENTER_ONLY`. Камера может подтверждать JPEG-size, но не отдавать такую же `YUV_420_888` / `PRIVATE SurfaceTexture` конфигурацию.
- Стоковый LG PHOTO repeating request использовал `TEMPLATE_ZERO_SHUTTER_LAG=5`, видео `TEMPLATE_RECORD=3`; полного OEM набора chromatix/шумоподавления этот шаблон сам по себе не воспроизводит.
- В OEM DEX встречаются vendor zoom / focus peaking keys, но для стороннего приложения запросы не обязательно доступны в `availableCaptureRequestKeys`; не отправлять неизвестные ключи без проверки.

## Основные экспериментальные файлы

`StreamingService.kt`, `helpers/CameraXCapture.kt`, `helpers/LgG6Main0DirectCapture.kt`, `helpers/LgG6PhotoPreviewTuning.kt`, `helpers/LgG6Main0Math.kt`, `helpers/LgG6Main0VendorProbe.kt`, `helpers/CameraGlPipe.kt`, `helpers/RoiCrop.kt`, `app/src/main/assets/index.html`.

## Особенности реализации и риски

- CameraX — стабильный fallback; Direct Camera2 с **одной PRIVATE SurfaceTexture** пригоден для H.264/RTSP, но не одновременно для MJPEG ImageAnalysis, photo snapshots, motion analysis и in-app preview.
- Zoom ISP и GPU не умножать случайно: 4××4× = ошибочная 16× обрезка. Применять ровно один выбранный механизм; GPU ROI позволяет panning, аппаратный центрированный crop — нет.
- Единый владелец CaptureRequest options необходим, иначе смена фокуса может очистить ISO/выдержку.
- Max capture size, template и запрошенные ISO — **пожелания**. Ключ проверки — фактические результаты `CaptureResult`, настоящий размер YUV/PRIVATE и примеры изображений.
- Android8 и старый HAL могут зависать на сочетаниях Preview+ImageAnalysis+ImageCapture. Должен оставаться fallback, понятный лог и thermal предел.

## Следующее исследование

Сравнить при равных scene/light/focus/ISO/shutter/FPS: CameraX ISP 4×, GPU ROI из реального высокого источника, direct ZSL(5) и RECORD(3), со снимками и логами на реальном H872; убедиться в приемлемой 10–30 минутной стабильности, звуке, RTSP и памяти. При новых фактах писать уникальный session/workstream файл, не переписывать общий.
