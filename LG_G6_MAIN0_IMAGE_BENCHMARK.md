# LG G6 H872 — automatic 4.5× image quality laboratory

**Status: EXPERIMENTAL, device-side verification required.**

This offline feature is launched **on the phone**, not remotely, and never uploads
images. It intentionally pauses the existing RTSP/ONVIF streaming service so
the camera is not shared between two active Camera2 sessions. When you return
to MainActivity, the original streaming service resumes.

## Open / run / export

1. Install the APK from GitHub Actions for `feature/lg-main0-auto-image-benchmark`
   (Android 8+, **arm64-v8a only**). Keep existing settings backed up.
2. In Android IP Camera on LG-H872 tap the new **gallery icon** near Settings,
   confirm that it may interrupt stream/recording.
3. Keep the phone mounted and scene still at fixed distance/lighting.
   Select **Quick** (up to 24 cases) or **Deep** (up to 90 cases).
4. Select **4:3** (default, keeps full 4160×3120 sensor aspect) or **1:1**.
5. Press **Run test**; stop anytime. A thermal safety threshold and a
   time limit automatically stop long experiments.
6. The ZIP is stored locally under app-specific external files
   `Android/data/<package>/files/camera-benchmarks`.
   Press **Save ZIP** to choose a destination using Android's document picker.
   The app does not need root for this feature.

## What it actually tests

* Camera2 **ID0** only, LG-H872 model guard. It queries YUV_420_888 sizes
  and attempts the **largest advertised image** first; if that session fails
  it tries smaller sizes, logging the accepted resolution.
* Fixed **4.5×** digital field-of-view crop, not optical zoom. Two pipelines:
  `isp` = sensor/ISP scaler crop + downsample to comparable information size,
  `cpu` = full camera YUV output + direct crop before JPEG. No stacked zoom.
* Repeating templates **PREVIEW(1)**, **RECORD(3)** and **ZSL(5)**.
  The matrix includes default/FAST/HQ/OFF noise & edge choices; deep mode
  additionally sweeps each *advertised* NR, edge, aberration and AWB mode
  plus bounded ISO×shutter samples and fixed-focus diopters.
* `CaptureResult`: actual ISO, shutter, frame duration, focus, crop, NR,
  EDGE, AWB and AE state; request values beside returned values.
* Image metrics computed locally: luminance average and contrast,
  1st/99th percentile, highlight and shadow clipping, edge energy,
  Laplacian variance (sharpness proxy) and two-frame temporal difference
  (motion-sensitive noise proxy).
* Sample JPEG for **each successful case** and a structured `report.json`
  plus `results.csv`.

**Important:** The app cannot enumerate literally every ISO/shutter/focus
floating-point value. Deep mode covers *all advertised finite mode
enumerations* in separate sweeps and representative analog ranges, capped
at 90 cases. It does not run a Cartesian product of all controls.

## Pixel accounting

For a real 4160×3120 YUV source and crop 4.5×:
- 4:3 effective source samples: ~924×693 (aligned JPEG ~924×692).
- Square effective source samples: ~693×693 (aligned ~692×692).
- The software-normalized sample JPEG is intentionally at this matched
  **native-information scale**, not a cosmetically upscaled 4160×3120 JPEG.
  The `maxNegotiatedYuvSize` field records actual camera source size.

Large full-resolution JPEG still capture (JPEG format) is a different
pipeline than maximum YUV preview. Do not interpret one as confirmation
of the other. CameraX-specific high-resolution preview is not yet part
of the direct Camera2 benchmark; compare the existing CameraX ISP/GPU
stream separately.

## Bias, limits, troubleshooting

- The scene MUST stay unchanged. Auto exposure and autofocus vary between
  cases; compare returned ISO/shutter/focus before ranking visual detail.
- Higher edge energy can come from halos, noise and sharpening artifacts,
  not more real detail. There is no automatic "best profile" claim.
- Full-res YUV processing is computationally intensive. Case timeouts,
  camera session fallback, battery/SoC thermal thresholds and output
  size caps are intentional.
- During the test, the RTSP camera is offline (and recording stops).
  Reopening camera0 after ending may require the stock camera service to
  release the session; re-run if the first open fails.
- The ZIP includes images of the monitored scene. Inspect before sharing.
- No root calls, no HAL modification, no bundled proprietary LG binaries.
- To debug: `adb logcat -s LGMain0Benchmark:V CameraService:W '*:S'`.
  If reports show only failures, send `report.json` and device logcat
  after removing identifying information.

## Engineering continuation

Read `DEVELOPMENT_HISTORY.txt`, `AGENTS.md` and PR descriptions.
CI passing proves Kotlin/JVM compile and synthetic image math, NOT
LG G6 13MP YUV viability or actual sensor 4.5x crop, JPEG processing
and thermal stability. Do not merge experimental PR before physical
LG-H872 validation.
