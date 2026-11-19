# LG H872 — отдельное управление шумодавом и резкостью (PR #7)

**Дата:** 2026-11-20 · **Код:** [fix/lg-g6-noise-edge-sharpness-20261120](https://github.com/Delliaf/android-ip-camera/tree/fix/lg-g6-noise-edge-sharpness-20261120) · **PR:** [#7](https://github.com/Delliaf/android-ip-camera/pull/7) · **Предыдущий функциональный код:** .9/PR #6.

## Источник и ограничение конфиденциальности

Исследован пользовательский приватный архив `LG-G6-main0-20261120-015819.zip` (ADB, logcat, dumpsys CameraService, hardware/thermal). **Исходный ZIP, raw logcat, реальные IP и видео не копируются в публичный репозиторий**. Ниже только проверенные обезличенные численные результаты.

### Проверенные факты

- Приложение в ZIP: `versionName=0.18.0-delliaf.9`, `versionCode=189`, LG-H872 Android8 (SDK26), root true.
- Основная Camera2 `ID0` в Direct: `PRIVATE SurfaceTexture 960×720`; H264 `960×720` и sensor ROI `Rect(1568,1178-2608,1958)` (1040×780, **4×**). Здесь **НЕ используется 4160×3120 YUV**, ранее доказанное только отдельным ImageReader benchmark.
- 19/19 последних периодических `LGG6Camera0Result`: профиль `ZSL`, `NR=4`, `EDGE=3`, `ABERRATION=1`. Стандартный NR=4 = ZERO_SHUTTER_LAG, EDGE=3 = ZERO_SHUTTER_LAG. Это значения реального `CaptureResult`, а не запрос UI.
- ISO автоэкспозиции `2750`, выдержка `100000000 ns` = 100ms, длительность кадра `100105216ns` ≈ **9.99fps**. До 12/15 fps запрошенного H264 FPS это физически не дотягивает при такой выдержке. Причина визуальной мягкости может быть длинной выдержкой, сильной обработкой ZSL, движением, фокусом, а не битрейтом.
- В коде до PR #7 `lg_main0_processing` существовал только для CameraX и был **отключён** в режиме Direct; ZSL шаблон там никак не переопределял NR/EDGE. Поэтому действительно не было доступного управления аппаратным шумодавом в текущем режиме.
- В логах есть два разных старта H264 (данные разных эпизодов): `960×720 @15fps 2000kbps` и затем `960×720 @12fps 20000kbps`. Последний битрейт **20 Mbps**, поэтому гипотеза «сделать просто больше H.264 bitrate» недостаточно обоснована. Для сравнения всё равно нужен настоящий поток и стабильная сцена.
- Есть `CameraX TimeoutException: ... is not done within 5000 ms` при повторной настройке, неоднократные TLS `Handshake failed` от отдельного клиента, а также Camera2 `Mismatched tag type ... enable/is_main: expected byte, received int32` на стороне HAL/CameraService. Эти ошибки не доказывают, что стандартные NR OFF/EDGE HQ сломаны: их ещё не было в этой сборке. Не пытаться вслепую писать в Qualcomm proprietary vendor keys.
- Сервис батареи стабильно сообщает `temperature: 321` (32.1°C) на всех четырёх временных срезах, заряд 100%; это **НЕ** температура SoC.
- `15-root-thermal-zones.txt` содержит ошибку `/system/bin/sh: syntax error: 'do' unexpected`, список зон через root не получен; причина в составленной диагностической shell-команде, не в отсутствии датчиков.
- В `top` одномоментно приложение ~45% одного ядра, qcamera daemon ~42% одного ядра; не приравнивать к процентам суммарной CPU загрузки.

## Что сделано в коде PR #7

- `helpers/LgG6IspModes.kt`: pure Kotlin выбор по Camera2 стандартным режимам, строго по `CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES` / `EDGE_AVAILABLE_EDGE_MODES`. NR `auto/off(0)/fast(1)/hq(2)/minimal(3)/zsl(4)`; EDGE `auto/off(0)/fast(1)/hq(2)/zsl(3)`. Если unsupported — режим не отправляется; без молчаливой подмены на другой.
- `CaptureBackend.kt`: независимые методы `setLgNoiseReductionMode` и `setLgEdgeMode`.
- `LgG6Main0DirectCapture.kt`: `submit` устанавливает каждую опцию **после** `LgG6PhotoPreviewTuning.apply`, шаблона ZSL и combined FAST/HQ. Отложенная 40мс коалесценция сохранена. `auto` не меняет старого поведения.
- `CameraXCapture.kt`: новые настройки устанавливаются **после** legacy CameraX combined preset в том же `CaptureRequestOptions.Builder`, где manual ISO, shutter, focus, crop; их изменение не стирает ручной фокус/экспозицию.
- `StreamingService.kt`: сохраняет и восстанавливает `lg_main0_noise_reduction` и `lg_main0_edge_mode` для LG Camera0, валидирует строки. Параметры применяются к обоим backend через `applyStored`.
- `StreamingServerHelper.kt`: новые `lgNoiseReduction`/`lgEdgeMode` в `/info.json.settings`.
- `index.html`: два отдельных dropdown (не отключаются в Direct), есть OFF/FAST/HQ/MINIMAL/ZSL и честная сноска: выключение стандартного NR может не отключить proprietary Qualcomm temporal denoise, реальное применение проверять `CaptureResult`.
- `LgG6Main0Telemetry.report`: рядом с фактическими `NR/EDGE` теперь журнал выводит `requestNR/requestEDGE`.
- Новый JUnit `LgG6IspModesTest`; Node `scripts/test-lg-isp-ui.cjs` и существующие UI tests.
- Версия `0.18.0-delliaf.10` (190), Android 8+, ARM64-V8A-only.

## Почему это может улучшить или ухудшить резкость

- `NR=off` позволяет сохранить шумную мелкую текстуру, но может усилить шум, «цветные точки», расход H264 bitrate и заметность сенсорных дефектов.
- `NR=minimal` балансирует между пиксельными дефектами и агрессивным ZSL сглаживанием.
- `EDGE=hq` может увеличить контраст контуров, но не восстанавливает детали, потерянные длинной выдержкой или низким source resolution; возможны ореолы, ringing и перешарп.
- ISO2750 и 100мс в ZSL auto необходимы для данной очень тёмной сцены; для настоящей резкости желательно неподвижный телефон, больше света и более короткая выдержка, но ручной ISO выше800 в штатном Camera2 не гарантирован.
- CameraX GPU ROI из 4160×3120 и Direct PRIVATE 960×720 — разные paths. A/B делать строго одинаково, не сравнивать их без учёта native ROI и exposure.

## Программа аппаратной проверки

1. После установки нового CI-APK на LG G6 открыть `/?v=10`, выбрать Camera ID0 и Direct Camera2 ZSL; включить `LG Main0 Telemetry`.
2. Зафиксировать телефон на штативе и сцену/свет. Сохранить контрольный frame при NR=auto+EDGE=auto и одинаковом zoom.
3. Оставив EDGE=auto, попробовать NR `off`, `minimal`, `fast`, `hq`, `zsl`; сравнить мелкую текстуру и количество шума. Проверить `requestNR` и фактическое `NR` в логах; если не совпадают — режим HAL не применил.
4. При предпочтительном NR попробовать EDGE `off/fast/hq/zsl`, сравнить контуры и ореолы, фактические запрос/ответ.
5. Повторить нужную пару в CameraX ISP crop и CameraX GPU ROI; фиксировать source/encoder resolution, zoom, ISO, shutter, FPS, формат H264/MJPEG и bitrate.
6. Если всё ещё «не всё работает», проверить логи `CameraX TimeoutException` и TLS handshake отдельно от NR, не смешивая проблему перезапуска с эффектом шумодава.
7. Не загружать сырые изображения/лог IP на публичный GitHub; отчёт с обезличенными фактами положить в собственный session в main.

**CI статус:** проверять живой Actions run PR #7 на **точном SHA** после изменения исходного кода. Зеленая сборка не доказывает работу HAL, поэтому PR остаётся draft до фактических кадров.
