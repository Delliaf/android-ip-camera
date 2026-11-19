# LG G6 H872 — main camera ID0 implementation

## Latest output format update (2026-11-19)

**16:9 is optional, NOT the target default.** The user accepts native photo
**4:3** and square **1:1**. The LG Main0 panel now exposes an atomic
`Output aspect` selector: 4:3 → **960×720** (recommended for full
4160×3120 input), 1:1 → **768×768**, and legacy optional 16:9 →
960×540. The ISP4×/GPU4×/safe1× buttons honor that selector. This
avoids an unnecessary second vertical crop when working with the
13MP/4:3 sensor.

With exact 4160×3120 capture, GPU 4× + 4:3 encodes ~1040×780
native source pixels into 960×720, while GPU 4× + 1:1 encodes a
~780×780 square into 768×768. These sizes assume the actual
Camera2/CameraX PRIVATE/Preview stream is truly 4160×3120;
observe negotiation in logcat before claiming quality.

The GitHub Actions workflow may be triggered twice by internal PR
and branch pushes. Internal same-repo pull-request jobs now skip
(the branch-push job owns the build) and Gradle retries only
transient Maven dependency-resolution failures. See
`DEVELOPMENT_HISTORY.txt` for precise build SHA and run URLs.



This file documents the Android 8 / API 26 **LG-H872** extensions of the experimental
[feature/manual-camera-4k-stream](https://github.com/Delliaf/android-ip-camera/tree/feature/manual-camera-4k-stream)
fork. This is **not** a firmware/SELinux modification. No LG or Qualcomm proprietary APKs,
shared libraries, signing keys or private binaries have been committed.

## Verified source facts (from the owner's LG H872 handoff)

Camera2 **ID `0`**, rear **main** lens (not ultrawide ID2/ID3):

| Characteristic | Camera0 result |
| --- | --- |
| Active array | Rect(8,8,4160,3120); 4152×3112 pixels |
| Pixel array | 4176×3136 |
| ISO (Camera2) | **50–800**, max analog sensitivity 800 |
| Exposure | 20651–33025763644 nanoseconds (limits do not imply 30 fps at 33 seconds) |
| Focus | up to 10.0 diopters; hyperfocal 0.24826208 D |
| Digital crop | up to 8×; cropping type **CENTER_ONLY** |
| Capability | MANUAL_SENSOR, MANUAL_POST_PROCESSING, RAW, LEVEL_3 |
| 16:9 YUV modes | 4160×2340 is listed; 3840×2160 not explicitly advertised |
| Post RAW digital gain | 100–3199%, separate from analog ISO |

The OEM LGCameraApp.apk identified by SHA-256
`3e4359e84ff865af3658a1455d4e2edf8677720da5768a56dae75b6e78c75525`
contains actual Camera2 callsites applying `SENSOR_SENSITIVITY`,
`SENSOR_EXPOSURE_TIME`, and `LENS_FOCUS_DISTANCE`. Its internal
`iso`/`exposure-time` settings are **not** proof of extra vendor ISO request tags.
The legacy camera ID3 shim advertises `lg-iso-values` through 3200;
this **does not prove** main camera ID0 can set sensor ISO 3200.

`com.lge.camera.zoom.ratio` (`CaptureRequest.Key<Float>`) and
`com.lge.camera.focus_peaking` (`CaptureRequest.Key<Int>`) were found
in the stock DEX and callsites. Whether a third-party UID can submit them
to camera ID0 remains to be tested on the actual phone.

## Browser controls

The LG panel appears only when `Build.MODEL == "LG-H872"` and the
selected camera is **exactly Camera2 ID0**. Other devices/lenses use the
unmodified generic controls. The H872 mode does not need root.

* **ISP / CameraX zoom:** one zoom mechanism, centered; GPU ROI is forced to 1×.
  Default experimental preference. The Camera2 crop readback should be inspected.
* **GPU ROI zoom:** CameraX zoom is forced to 1×; the source is cropped in OpenGL
  before H.264 encoder compression. ROI X/Y work only in this mode.
* **Vendor zoom:** optional developer A/B setting; forces CameraX zoom to 1×
  and submits guarded OEM tag + a sensor crop rectangle **only when the
  Camera0 request-key list advertises the tag**. Falls back to ordinary
  ISP zoom if unsupported. This mode is **not proven safe across LG firmware versions**.
* **Focus:** normal AF or precise `0.00–10.00 D` control; 0 means infinity.
  The estimate `distance[m] ≈ 1/diopters` is not a mechanical calibration.
* **ISO/exposure:** the existing manual sensor controls automatically respect
  the actual Camera2 sensitivity range. Values beyond 800 are clamped unless
  Camera0 itself reports more. A too-long shutter extends frame duration,
  so actual FPS can fall below the configured maximum.
* **Post-RAW sensitivity boost:** 100, 200, or 400%; digital amplification,
  not ISO1600/3200 and not extra sensor detail. Only submitted with manual
  exposure if available.
* **OEM peaking:** OFF by default and only submitted when advertised; the LG
  overlay may be visible only in the OEM app's preview, not the RTSP feed.
* **CaptureResult:** OFF by default; when enabled, reports actual ISO, exposure,
  frame duration, focus, crop region and digital gain every 30th frame.
  It is displayed in `/info.json` / browser and in Android logcat.
  This readback is substantially more useful than requested values alone.

The three buttons set atomic A/B profiles: **ISP 4×**, **GPU 4×**, and
**1× safe**. They now request 960×720 output by default (4:3), 15 fps, 2 Mbps H.264, 90% MJPEG;
GPU profile requests 4160×2340 source and ISP profile uses the output-size
camera source. Use the on-screen negotiated CameraX source/Preview dimensions
and `CaptureResult` to tell what the device actually delivered.

## First hardware tests

1. Stop LGCameraApp and every other camera user, select camera **0** and
   choose **CameraX** backend (not Camera1 fallback).
2. Enable `Camera2 CaptureResult readback` in the LG panel.
   Inspect `adb logcat -s LGG6Camera0Result:I LGG6Camera0Probe:I CameraXCapture:I '*:S'`.
3. Use **ISP 4×** and note `CaptureResult.SCALER_CROP_REGION`, autofocus
   behavior and text/image detail. EIS/OIS should not be assumed to be active.
4. Use **GPU 4×** on the exact same scene and lighting, without moving the
   tripod; verify negotiated 4160×2340 source rather than believing the menu.
5. Compare cropped images at native pixel scale and inspect focus precision.
   On a 3840×2160 frame, 4× zoom has only **960×540 original source pixels**.
   A 1920×1080 output cannot create additional real details.
6. Test manual ISO 50/100/400/800 with equal shutter, focus 0/0.25/1/5/10 D,
   1/50 s and 1/20 s exposures. Observe `CaptureResult`, not just the sliders.
7. **Only if advertised** try vendor zoom and OEM peaking one at a time;
   revert instantly to ISP mode if an anomaly or HAL stall occurs.
8. For video transport, enable RTSP and test
   `ffprobe -rtsp_transport tcp -v error -select_streams v:0 -show_entries stream=codec_name,width,height,avg_frame_rate -of default=noprint_wrappers=1 rtsp://PHONE_IP:8554/live`.
   Authenticate and use RTSPS if TLS is enabled. Check ONVIF separately.
9. Compare phone temperature, CPU, dropped frames and battery drain after
   comparable steady-state sessions. One requested surface is not evidence
   of a reduced sensor readout or reduced ISP power.

Read-only rooted diagnostics, if desired, are in
`scripts/lg-g6-main0-diagnostics.sh`. Do not disable SELinux, replace
camera HAL libraries, load proprietary code into the public APK or grant
persistent shell access to the web server.

## Verification limits

Unit tests exercise the 16:9 even-aligned sensor crop and ROI bounds;
CI exercises Kotlin/Gradle compilation, JVM tests and debug APK packaging.
**None of those tests can certify camera ID0 OEM vendor-tag access,
4K full-resolution Preview delivery, physical 4× detail, thermal savings,
focus-peaking overlay behavior or RTSP/ONVIF interoperability on the phone.**
Those require the physical H872 and real captured evidence. Keep the PR
draft until the device-side tests succeed.


## 2026-11-19: photo-preview ZSL handoff v4 and on-device findings

The user's OEM DEX trace establishes that `CameraOps.setPreviewRequestToMainSession`
creates a repeating request using `TEMPLATE_ZERO_SHUTTER_LAG` (5) for stock
PHOTO preview, while recording mode uses `TEMPLATE_RECORD` (3). Stock
LG applies additional private solution tuning; TEMPLATE_ZSL alone is an
A/B hypothesis, **not a guarantee of OEM rendering parity**.

**Latest H872 log changes the ISO interpretation.** With AE enabled,
Camera ID0 `CaptureResult.SENSOR_SENSITIVITY` reached **1381..1484**
even though manual Camera2 characteristics advertise **ISO 50..800**.
Manual requested 36 and 3600 were clamped by our previous CameraX
implementation to 50 and 800. Therefore an opt-in **experimental
manual >800** switch now submits ISO 801..3200 without a software
clamp; users must compare actual request ISO and returned result ISO.
The switch is disabled by default. AUTO >800 is not itself proof that
manual ISO >800 is accepted, nor that the gain is analog.

**Main0 12/13 MP:** source requests now include 4160×3120 (4:3,
about 13 MP), 4000×3000 (12MP experimental), 4160×2340 (16:9),
3840×2160, and 3264×2448 (8MP comparison). The effective
PRIVATE or CameraX output size may be smaller; the browser readback
and Android logcat reveal negotiation. A 4160×3120 source cropped
4× then to 16:9 yields roughly 1040×585 real camera pixels, which
are appropriate for about 960×540 output, not lossless 1080p.

**Zoom bug fix:** the GL texture crop must match encoded aspect before
zoom. The original normalized rectangular ROI took 4:3 texture into
16:9 encoder and letterboxed/cropped inconsistently. The corrected
`RoiCrop.windowForOutput` explicitly includes the camera source and
encoder ratios, and the LG GPU pipeline fills the encoder viewport.

**New opt-in capture API** `lg-main0-direct`: single direct
Camera2 PRIVATE SurfaceTexture -> existing GL GPU crop -> hardware H.264
-> existing RTSP/ONVIF. Select PHOTO_ZSL(5), VIDEO_REFERENCE(3),
PHOTO_DEFAULT(1), PHOTO_FAST or PHOTO_HQ in the LG panel to compare the
same sensor/output/bitrate/FPS/scene. The CameraX option remains the
default stable fallback. MJPEG, motion detection, still snapshots and
on-phone Preview intentionally switch back to CameraX, because a
single-surface direct engine cannot deliver those streams concurrently.
If Camera2 fails to deliver frames, startup switches to CameraX.

**NR/EDGE:** the LG panel separately offers CameraX OFF / FAST / HQ
for standard ISP noise reduction and sharpening keys. NR high-quality
can reduce FPS and may not operate on large streams; always read back
actual NR/EDGE and inspect fine-text detail/halos, not just visual contrast.

**ARM64:** native Rust OpenTunnel is built only for aarch64 and release
splits only produce arm64-v8a. CI inspects the debug APK's `lib/`
entries and rejects a different ABI. An existing installed APK signed
with another key cannot necessarily be upgraded in-place.

### Controlled first experiment

Use fixed focus, fixed shutter, stable lighting and a tripod. In
H264 mode (NOT MJPEG), set Camera0, 960×720 encoded output (4:3), 15fps,
~2Mbps. Choose the **Direct Camera2** engine and switch between
**VIDEO_REFERENCE (template3)** and **PHOTO_ZSL (template5)**.
Compare actual frame detail and noise at equal framing. For the next
test, set source to 4160×3120 and choose **GPU 4×** and compare it
with **ISP 4×**, keeping encoder output the same.

Windows read-only root-assisted diagnostic collector:
`scripts/lg-g6-main0-windows-diagnostics.ps1`; run from PowerShell
with ADB connected and stream already started. Inspect the resulting
ZIP for private device/network information before sharing. A/B on the
real H872 and thermal stability are still required even when CI is green.
