# LG G6 H872 — main camera ID0 implementation

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
**1× safe**. They request 960×540 output, 15 fps, 2 Mbps H.264, 90% MJPEG;
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
