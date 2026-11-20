# LG-H872: удержание фокуса, фоновой Camera2 и полноценная ONVIF/RTSP интеграция

**Дата:** 2026-11-20 · **Разработчик:** отдельный чат · **Код:** [fix/lg-g6-focus-onvif-lifecycle-20261120](https://github.com/Delliaf/android-ip-camera/tree/fix/lg-g6-focus-onvif-lifecycle-20261120) · **Draft PR:** [#8](https://github.com/Delliaf/android-ip-camera/pull/8).
**Основа:** `fix/lg-g6-noise-edge-sharpness-20261120@9764aea7...` (PR #7, .10).
Подробный журнал: [docs/sessions/2026-11-20/focus-background-onvif-control-20261120c.md](../sessions/2026-11-20/focus-background-onvif-control-20261120c.md).

## Реальный источник и причины

Пользователь передал приватный ADB ZIP `LG-G6-main0-20261120-035901.zip`. 20/40 camera-log: CameraX пытается source YUV `4160x3120`, не получает кадры в 8s и переходит в Camera1 `960x720 focus=continuous-video`. После fallback точный ручной фокус (диоптрии) утрачен, Camera1 не предоставляет API физической позиции линзы. Много `ONVIF TLS handshake failed` потому что ONVIF TLS был включён по умолчанию, а LAN клиент `onvif://` говорит plain HTTP; пароль здесь не имеет значения.

Дополнительно код `MainActivity.onPause` всегда отвязывал PreviewView, а `StreamingService.setPreviewSurface` всегда restart-ил серверную Camera2 сессию на любое изменение поверхности. `handleClientDisconnected` после последнего RTSP клиента намеренно 'shed' весь H264 до preview-only. При повторном подключении это опять захват/освобождение HAL с автофокусировкой.

**Нельзя вывести из логов, что Android 8 действительно аппаратно отозвал доступ к Camera2 на каждом screen lock.** Мы убираем лишнее пересоздание на уровне приложения. Если kernels/cameraserver гасит устройство отдельно, потребуется отдельный физический лог.

## Реализация Android background camera

`StreamingService.kt`:
- `holdCameraForRtsp()` — LG H872 Main0, RTSP включён, Android настройка `lg_main0_background_hold` (по умолчанию TRUE); опция OFF позволяет экономить батарею и вернуться к режиму on-demand.
- Виртуальный H264 consumer в `cameraDemand()`, `PARTIAL_WAKE_LOCK` + начальный запуск камеры даже без клиентов, запрет закрытия при screen lock/last-client disconnect. PreviewView может отсоединяться и возвращаться, но если CaptureSession обслуживает тот же demand, нет HAL rebind.
- Если CameraX high-res не выдаёт кадры, LG H264 пробует запасной **Direct Camera2 PRIVATE** до Camera1. Последний всё ещё остаётся как аварийный fallback и логирует невозможность точных diopters.
- `Camera1Capture.setFocusDiopters`: для позитивного manual lock выбирает однократный `AUTO` фокус и НЕ возвращает `CONTINUOUS_VIDEO`; для 0D предпочитает INFINITY. Это приближённая блокировка фокуса, **НЕ точное значение D**. Direct Camera2 сохраняет `CONTROL_AF_MODE_OFF`/заданное `LENS_FOCUS_DISTANCE`.
- `CameraSessionTest` воспроизводит экран включён/выключен при постоянном виртуальном H264 viewer и подтверждает, что `serves` не требует restart.
- **Ограничения:** неизбежные перезапуски при смене API, resolution, encoding и при HAL timeouts; Android OS/killer может завершить сервис. Постоянный wake lock/encoder увеличит энергопотребление и SoC температуру.

## ONVIF, RTSP: транспорт и авторизация независимы

`OnvifServer` всегда запускает HTTP SOAP/WS-Discovery на `http://<LAN-IP>:8000/onvif/device_service`, при этом старое `onvif_tls_enabled=true` игнорируется (в Android UI прежний toggle удалён, текстовое предупреждение). Параметр `onvif_auth_enabled` по умолчанию FALSE, может включаться независимо от Web `enable_auth`; при ON и отсутствии сохранённого username/password ONVIF не стартует (fail closed).
`AndroidRtspHost` использует `rtsp_auth_enabled` FALSE по умолчанию (уже не Web auth). `RtspServer tls` определяется отдельным `rtsp_tls_enabled` FALSE (обычный `rtsp://` по умолчанию). Web HTTPS `tls_enabled` остаётся независимым.
**Security:** при выключенной авторизации ONVIF/RTSP принимают только loopback, link-local и site-local LAN IP, внешние публичные клиенты отвергаются; всё равно **не настраивать port-forwarding** и не использовать open HTTP на недоверенной Wi-Fi сети. Опции Android Settings относятся ко всем потокам, нет двойного phantom RTSP auth.

## ONVIF Imaging + relay для реального управления

`OnvifServer.kt` вводит callbacks `cameraStatus: () -> OnvifControlStatus` и `cameraControl(key,value):Boolean`; `StreamingService` подключает их к реальным Camera0 preferences и `handleRemoteControl` с белым списком команд и проверкой границ. Стандартные запросы:
- Imaging `GetImagingSettings`, `GetOptions`, `GetMoveOptions`, `GetStatus`: AUTO/MANUAL, положение фокуса (0..1) нормализовано по `LENS_INFO_MINIMUM_FOCUS_DISTANCE`.
- `SetImagingSettings` (AUTO/MANUAL), `Move` с `Absolute/Position` (0..1) → `lg_main0_focus_mode` и `lg_main0_focus_diopters`.
- Device `GetRelayOutputs` + `SetRelayOutputState` для `torch0` с `active/inactive`; это виртуальное включение физического фонарика.
- Unsupported/unknown focus/torch tokens must get SOAP Fault, not fake 200; commands of this kind are unavailable on non-LG devices.
- **Важное ограничение:** клиент go2rtc/Frigate/VLC может не показывать Imaging/relay controls в UI, даже если ONVIF сервер их реализует. Работа проверяется SOAP клиентом, а не только наличием меню.

## ONVIF Media1/Media2 — реальное качество shared H264

Основной RTSP `/live` и ONVIF профиля main используют **один H264 encoder**: OnvifStreamInfo.main FPS, bitrate, resolution + quality. Отдельный sub `/live/sub` имеет независимые RTSP sub encoder параметры из `rtsp_sub_*`.

`SetVideoEncoderConfiguration` Media1/Media2 проверяет H264, токен `vec_main/vec_sub`, границы FPS/bitrate/quality, **запрещает неподдерживаемую смену resolution**, вызывает whitelist `cameraControl`. Main quality 1..5 переключает bitrate preset 1000/3000/6000/12000/24000 kbps; явный BitrateLimit имеет приоритет, FPS1..60. Sub FPS 10/15/20/30, bitrate 300/500/800/1200/2000 kbps. Ошибки дают SOAP Fault, а не прежний success-no-op. Оба Get*Configuration и Get*Options показывают отдельные main/sub значения. StreamUri sub указывает фактический `/live/sub` и **не переключает RTSP→RTSPS из-за ONVIF HTTP/TLS**.
`StreamingService.handleRemoteControl("fps")` теперь сохраняет FPS и делает реальный рестарт H264; прежде FPS только MjpegEncoder сохранял без H264 rebuild. Веб интерфейс содержит отдельные quality presets и битрейт, `/info.json.settings.onvifVideoQuality`.
**ACK = команда проверена и передана**, реальные output fps/bitrate могут быть ограничены Android Qualcomm HAL/encoder и доступной экспозицией. Не объявлять физическое качество гарантированным по XML.

## ONVIF DeviceHealth — вендорское расширение событий

Стандартный Profile S не даёт универсальных числовых полей батареи/SoC/CPU для всех клиентов. Добавлен явный namespace `urn:android-ip-camera:device-health:1` с префиксом `apph`. `GetEventProperties` объявляет события BatteryPercent, BatteryTemperatureC, SoCTemperatureC, CpuPercent. `CreatePullPointSubscription` и `SetSynchronizationPoint` включают текущее значение, `PullMessages` обновляет каждые 10s. Источник: `DevicePerformanceMonitor.sample()` + Android `ACTION_BATTERY_CHANGED`. Разделены `batteryCelsius` и `socCelsius`, no fake CPU when Android access denied.
**Клиенты не обязаны показывать vendor events в GUI**; ONVIF Profile S, M или сертификация НЕ утверждаются. Для полной UI поддержки в NVR может потребоваться отдельный плагин/скрипт обработки PullPoint событий.

## Тесты, необходимые для готовности

- `OnvifControlIntegrationTest.kt` вызывает настоящие `OnvifServer.handle` SOAP (Get/Move/SetFocus, Relay, Media1+2 settings, GetStreamUri sub, Health PullPoint и Fault paths); старые `OnvifServerTest.kt` покрывают digest auth, plain HTTP sockets, go2rtc GetCapabilities/GetProfiles.
- `CameraSessionTest`: виртуальный H264 viewer удерживает Camera2 при PreviewView attach/detach.
- `scripts/test-onvif-rtsp-ui.cjs`: настоящая веб-верстка содержит quality/FPS/bitrate.
- После успешного `android8-fork-ci.yml`: сверить **точный SHA** последнего functional коммита, `testDebugUnitTest`, `assembleDebug`, ABI `arm64-v8a only`, artifact `android-ip-camera-android8-debug`.
- **Необходимо железо:** 10–30 минут H872 screen-off + RTSP client connect/disconnect, ручной D до/после, отсутствие CameraX Camera1 fallback, torch, go2rtc `onvif://`, чтение SOAP GetCapabilities/GetProfiles/GetEventProperties/SetVideoEncoderConfiguration, оба RTP URL, CPU/battery events, thermal throttling.
- Архив пользователя с raw IP/логами остаётся приватным, в GitHub только этот обезличенный инженерный отчёт.

**Статус:** source implemented, CI verification to be confirmed by live workflow run; hardware not yet verified. PR remains DRAFT.
