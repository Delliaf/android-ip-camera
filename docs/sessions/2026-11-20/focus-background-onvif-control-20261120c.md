# LG G6 H872 — focus persist, screen lock lifecycle, standalone ONVIF HTTP, imaging/encoder/health

- **Status:** working
- **User request:** 2026-11-20: after last APK, fixed manual focus eventually reverts in background; camera pipeline restarts on lock/unlock and other surface changes; ONVIF does not connect; wants ONVIF independent of auth/TLS, encoder quality control for RTSP+ONVIF, ONVIF flash/focus and temperature/battery/CPU telemetry.
- **Diagnostics source (PRIVATE):** user ZIP `LG-G6-main0-20261120-035901.zip`, taken 03:59, Android8 SDK26, rooted LG-H872. Private raw logs/images/IP excluded from public GitHub. Onvif repeated TLS handshake failed; CameraX requested 4160×3120 and timed out waiting for session/frames, then fallback Camera1 opened 960×720 with `focus=continuous-video`. Focus did not persist across backend fallback. The archive does not prove that Android forcibly revoked camera access on every screen lock.
- **Code base:** `fix/lg-g6-noise-edge-sharpness-20261120@9764aea7df873e91d5ef5d83b3a276cb5296c926`, successful 0.18.0-delliaf.10 Actions 44635287805, draft PR #7.
- **Proposed branch:** `fix/lg-g6-focus-onvif-lifecycle-20261120` based on #7. Independent draft PR targeting #7.
- **Dependencies:** PR1→PR3→PR4→PR5→PR6→PR7, independent PR2 not assumed merged.

## Root causes verified from code

1. `MainActivity.onPause` unconditionally calls `streamingService.setPreviewSurface(null)`. `StreamingService.setPreviewSurface` always schedules `debouncedStartCamera` when another viewer exists, and may fully stop the camera when no viewer exists. Screen locking and returning toggles this state.
2. `StreamingService.handleClientDisconnected` forcibly opens a new idle/preview configuration once the last RTSP viewer leaves. This changes camera backend and may discard focus, and viewer reconnect forces another CameraX session.
3. CameraX at 4160x3120 can bind without frames and timeout; existing code falls back to `Camera1Capture`, which opens with continuous-video autofocus and does not represent precise fixed focus in diopters. This invalidates UI's "manual focus held" claim.
4. ONVIF service `tls` currently defaults ON (`onvif_tls_enabled=true`), although go2rtc uses plain HTTP `onvif://`. ONVIF `credentials={streamCredentials()}` is coupled to global authentication. Raw diagnostics record many SSLHandshakeException from ONVIF client. RTSP authentication remains separate and must be preserved securely; open LAN ONVIF must not imply disabling RTSP security.
5. ONVIF media1 and media2 `SetVideoEncoderConfiguration` return a success XML without changing any encoder setting; `GetVideoEncoderConfigurationOptions` advertises no useful bitrate range in media1. ONVIF imaging `GetOptions/GetImagingSettings/GetMoveOptions/GetStatus` return empty elements; no Move, no control actual camera. No device relay mapping for torch, health reports absent.

## Implementation plan

- Stabilize camera lifecycle on preview changes; make background persistent streaming mode on LG main0, preserving same backend across lock/unlock and viewer disconnects; avoid churn when already running.
- Restore/pin manual focus from stored prefs after every rebind; avoid Camera1 fallback for main0 manual focus where a Direct Camera2 H264 path is possible; honest Camera1 limitation, avoid misleading UI.
- Split ONVIF TLS (default HTTP LAN), ONVIF auth (default disabled) from global web/RTSP auth; retain explicit options and security warning; endpoint advertise true actual transport, not web TLS.
- Implement real ONVIF imaging focus, torch device relay, encoder FPS/bitrate configuration, and health/temperature telemetry using interoperable ONVIF extensions and events where justified; don't falsely advertise capabilities or silently accept unsupported configuration.
- UI make RTSP/ONVIF H264 encoding quality (bitrate/FPS/resolution) clear; ONVIF configured video uses shared RTSP encoder, avoid imaginary independent stream.
- Add ONVIF protocol tests (HTTP plaintext, SOAP, Set/Get video & focus, telemetry, authorization mode), camera lifecycle/prefs tests, run ARM64 CI and record exact SHA. Physical H872 validation remains mandatory.

## Evidence checks and next steps

- Verify exact code behavior and daemon reports, test with go2rtc actual HTTP/WS-Discovery and RTSP URL/credentials.
- Ensure ONVIF extensions don't claim formal ONVIF certification, nor publish unauthenticated control service to WAN by default.
- Update separate domain workstream doc and PR with precise code changes and regression tests.


## 2026-11-20 — implemented source, review, tests, CI pending

- **Draft PR #8:** https://github.com/Delliaf/android-ip-camera/pull/8, base `fix/lg-g6-noise-edge-sharpness-20261120`. Исходники и тесты в `fix/lg-g6-focus-onvif-lifecycle-20261120`.
- **Точный HEAD на момент записи:** `f5ffc37334c6b067d6f551271bf321e28170ad6a`. Версия `0.18.0-delliaf.11`, versionCode191, arm64-v8a only, Android 8 target.
- Изменённые модули: `StreamingService.kt`, `Camera1Capture.kt`, `OnvifServer.kt`, `OnvifSoap.kt`, `AndroidRtspHost.kt`, `RtspServer.kt`, `MainActivity.kt`, `StreamingServerHelper.kt`, `SettingsActivity.kt`, `preferences.xml`, `index.html`, `CameraSessionTest.kt`, `OnvifControlIntegrationTest.kt`, JS tests, CI workflow, Gradle version.
- Реализовано: LG Main0 pinned headless camera через `lg_main0_background_hold=true` при RTSP enabled, session reuse на screen lock и client reconnect; PARTIAL_WAKE_LOCK; Direct Camera2 PRIVATE fallback до Camera1; Camera1 one-shot AF lock вместо continuous-video, без ложного обещания точных D. На LG H872 cold-start ID0 используется реальный Camera2 ID.
- ONVIF SOAP всегда plain HTTP (legacy `onvif_tls_enabled` игнорируется), ONVIF auth отдельно `onvif_auth_enabled` OFF по умолчанию; RTSP auth отдельно `rtsp_auth_enabled`, старая настройка `enable_auth` переносится безопасно, без неожиданного раскрытия ранее закрытого RTSP; RTSP TLS отдельно `rtsp_tls_enabled` OFF по умолчанию; anonymous ONVIF/RTSP соединения ограничены локальными IP. Web TLS не меняется.
- ONVIF Device `GetRelayOutputs`, `SetRelayOutputState`, Device.IO `RelayOutputs` capability; Imaging `GetImagingSettings/Options/MoveOptions/Status`, `SetImagingSettings`, `Move`, Stop; Control callbacks whitelist и фактические prefs. Media1/Media2 encoder settings → реальные FPS/битрейт main и sub, отдельный `/live/sub`; неподдерживаемые H265/resolution/quality отказывают SOAP Fault 400.
- `onvif_video_quality` 1–5 → 1/3/6/12/24 Mbps H264 (общий RTSP+ONVIF main encoder); FPS web/ONVIF теперь сохраняется и перезапускает H264, а не только MJPEG. `/info.json.settings.onvifVideoQuality` позволяет UI восстановить состояние.
- Vendor `apph:DeviceHealth` ONVIF PullPoint содержит BatteryPercent, BatteryTemperatureC, SoCTemperatureC, CpuPercent. Это НЕ стандартный ONVIF-сенсор, поэтому клиенты вправе не отображать показатели.
- Regression tests: `OnvifControlIntegrationTest` (focus/torch/media1+2/health/invalid config), `CameraSessionTest` (pinned H264 survives preview changes), JS `test-onvif-rtsp-ui.cjs` и `test-onvif-transport.cjs`.
- **CI STATUS:** запрошенный Android8 workflow включён в `.github/workflows/android8-fork-ci.yml` для новой ветки; но GitHub Actions API по этой ветке пока возвращает **0 workflow runs/check-runs**, при этом GitHub branch/PR/commits существуют. Поэтому ни `testDebugUnitTest`, ни `assembleDebug`, ни artifact .11 **не подтверждены**. НЕ выдавать старый .10 APK в качестве .11. Проверить Actions доступность/настройки в GitHub UI; после успешного запуска записать run URL, SHA и artifact. Если CI потребует исправлений Kotlin/tests, выполнять только в этой ветке и дописать журнал.
- **DEVICE TEST:** отсутствует для .11. После сборки проверить фокус (D) + блокировку 10–30 минут, RTSP client reconnect, TCP `http://<LAN-IP>:8000/onvif/device_service`, `/live` и `/live/sub`, SOAP Imaging/Relay/Media и vendor health events. Не открывать anonymous ONVIF/RTSP порты в Internet.
- Устранение дрейфа фокуса, вызванного самой прошивкой или физической потерей HAL при screen lock, ещё не доказано; тест `CameraSession.serves` проверяет логику приложения, а не железо.
- Подробный инженерный handoff: `main/docs/workstreams/onvif-20261120-h872-focus-transport-quality-telemetry.md`. Пользовательский ZIP остаётся приватным.


### 2026-11-20 — final source audit / availability recheck
- **Current PR #8 code HEAD:** `eb6e0289d1179bebc759a70614aabe4509d4dbda`. Последний изменённый Kotlin источник: `OnvifServer.kt` теперь размещает `DeviceCapabilities.IO` **перед** `Security` согласно порядку XML schema, чтобы NVR обнаруживал 1 relay torch0 и не отвергал GetCapabilities из-за неверного порядка. Это дополнительный функциональный коммит поверх предыдущего SHA; старые CI, если появятся, надо сверять с текущим HEAD.
- Проверено через Github API: `actions/runs?branch=fix/lg-g6-focus-onvif-lifecycle-20261120` вернул `total_count=0` **после** source commits и PR #8; CI не запускался и не опубликован APK .11. Причину отсутствия запуска API не показывает; не предполагать ни квоты, ни неисправность репозитория без доказательства.
- Дополнительный тест `scripts/test-onvif-transport.cjs` в Android8 workflow проверяет независимый ONVIF HTTP, RTSP auth migration и включённые SOAP команды; `scripts/test-onvif-rtsp-ui.cjs` проверяет реальные селекторы качества.
- Окончательный статус: **source-implemented / CI-blocked / not device-verified**, PR draft. Следующий исполнитель должен сначала обеспечить GitHub Actions запуск для exact HEAD и исправить возможные ошибки Kotlin/JUnit; только после SUCCESS выдавать APK.


## 2026-11-20 — fresh build requested; automated CI bootstrap and real failures

User explicitly requested an installable fresh build with PR8 changes. Existing feature-branch CI had no runs. Added a **reusable default-branch builder** at `.github/workflows/h872-experimental-apk.yml` on `main`, which accepts `source_ref` via workflow_dispatch, and its initial push action checked out PR8 HEAD, ran JS and Gradle and verified ARM64. This also restored automatic push CI runs on PR8 branch.

CI attempts:
1. [default-branch run 44744330085](https://github.com/Delliaf/android-ip-camera/actions/runs/44744330085): failed at JS test `scripts/test-onvif-transport.cjs` line13; test mistakenly searched `StreamingService.kt` instead of actual RTSP auth owner `AndroidRtspHost.kt`. Corrected in `683ad39f4141231916371ee81e91d9ab670fbb26`.
2. [feature branch run 44744551017](https://github.com/Delliaf/android-ip-camera/actions/runs/44744551017): JavaScript passed, Kotlin compiler failed `UNRESOLVED_REFERENCE anyBackCameraWithFlash` in ONVIF status adapter. Existing `flashAnchorCameraId()` is the correct known flash capability check; corrected in `e752d1c364abd29905d0bd8b080495d4a7fc13a2`.
3. [feature branch run 44745253243](https://github.com/Delliaf/android-ip-camera/actions/runs/44745253243): Kotlin compilation successful; **188 unit tests, 187 passed, 1 failed**. Old `TlsServerTest.kt:161` expected ONVIF HTTPS to rewrite plain `rtsp://` to `rtsps://`; newer architecture correctly decouples ONVIF HTTP/TLS and RTP/RTSP schemes. Updated test to assert `rtsp://` unchanged and no `rtsps://` unless separately configured, in `a50d279124f1fa8e7f632f0ec87aa00436b39447`.
4. [feature branch run 44746118951](https://github.com/Delliaf/android-ip-camera/actions/runs/44746118951) triggered for latest `a50d279...`; **currently awaiting final CI result**. Must verify all tests, APK upload and arm64-v8a-only before calling build complete.

New main builder records source-sha.txt and apk-sha256.txt with the APK and targets only ARM64-V8A; it does NOT merge PR8 into main. Prior main retry run 44744576842 used older source SHA and should not be distributed as latest APK.

Next developer: check CI run 44746118951, resolve remaining failures and confirm exact source SHA and artifacts; update THIS journal and PR8 with final verified result. Do not claim hardware validation by JVM.
