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
