# LG G6 H872 / Android 8 / MAIN0 camera profile

Target: stock LG-H872 (lucye_tmo_us), API26, **main rear Camera2 ID 0 only**.
This feature builds on the existing `feature/manual-camera-4k-stream` fork.
The upstream RTSP/RTSPS, RTP/RTCP, ONVIF Profile S, local recordings and authentication
remain in place. No proprietary LGCameraApp binaries or vendor libraries are shipped.

## Source fidelity (static data from the device)

| Camera2 ID0 characteristic | Measured metadata |
| --- | --- |
| Active array | Rect(8, 8, 4160, 3120) |
| Sensor pixels | 4176×3136 |
| `LENS_INFO_MINIMUM_FOCUS_DISTANCE` | 10.0 D |
| `SENSOR_INFO_SENSITIVITY_RANGE` | ISO 50–800 |
| `SENSOR_MAX_ANALOG_SENSITIVITY` | ISO 800 |
| `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` | 8× |
| `CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE` | 100–3199% |
| Camera2 high-resolution outputs | 4160×2340, 4160×3120, 4160×2080, 2880×2160, etc. |

The LG legacy `lg-iso-values` list up to ISO3200 was observed for *Camera1 ID3*,
not proven for **Camera2 ID0**. No high analog ISO is fabricated or hardcoded.
The original LG camera DEX shows standard `SENSOR_SENSITIVITY`,
`SENSOR_EXPOSURE_TIME` and `LENS_FOCUS_DISTANCE` calls. Its OEM
`com.lge.camera.zoom.ratio` **Float** and `com.lge.camera.focus_peaking`
**Integer** keys are static findings, not proof of support in third-party APKs.

## How to test on the phone

1. Backup the original app configuration. Debug APK uses a different signing
   certificate and may not update the release APK without uninstalling it.
2. Select **MAIN0 / Camera ID0**, preferably **CameraX**, not the Camera1 fallback.
   Enable RTSP in Settings, then open `rtsp(s)://PHONE_IP:8554/live` with a player.
3. In the browser select **Camera2 CaptureResult telemetry = On**. This reconnects
   the camera to install the read-only callback. Read `/info.json` -> `lgMain0` ->
   `readback` for actual ISO/shutter/focus/crop. The advertised vendor keys are
   shown separately; advertisement does not guarantee their effect.
4. For **low-heat ISP 4×**: Camera Zoom=4×, LG zoom strategy=ISP, ROI zoom is
   disabled. Start with output ~960×540, 15–20FPS, 1.5–3Mbps H.264, camera source
   capture mode=Match. Compare with reference photo details.
5. For **GPU 4×**: choose LG zoom strategy=GPU (forces CameraX zoom=1×),
   source capture request **4160×2340**, ROI zoom=4×, output ~960×540. Check both
   actual `captureFrame.gpuInputWidth/Height` and actual readout before claiming
   full-resolution detail. Real 4K-sized surface capture can fail on older LG HALs.
6. **Sensor crop** and **LG vendor zoom** are strictly experimental. Both force
   CameraX zoom=1× and disable GPU crop; only one camera crop is active at a time.
   Sensor mode sets `SCALER_CROP_REGION` to a YUV-even, 16:9 region.
   LG vendor mode additionally sets the OEM Float zoom key **only if camera0
   advertises it**. Compare `requestedCrop`, `crop`, focus/ISO readback,
   image sharpness and thermal behavior. The vendor tag may be ignored/denied.
7. Focus: use the precise 0–10 D input (0 D=infinity), then inspect actual
   `CaptureResult.LENS_FOCUS_DISTANCE`. Manual ISO 50–800 is genuine standard
   Camera2; if useful, enable post-RAW boost at 200/400% as a separate **digital**
   gain experiment and inspect readback. Shutter is capped to requested FPS
   frame period for this profile, with 1ms margin; actual frame rate can still
   be lower, especially in low light.
8. Switch OFF experimental zoom/peaking if any stall. Keep a separate
   baseline at ISO auto, AF auto, sensor crop OFF; avoid double zoom.

### Reference detail math

3840×2160 cropped 4× yields **960×540 source pixels**, not 1080p.
The Camera0 active array is 4152×3112; a centered 16:9, ~4× ROI from this
array is 1024×576 after even/aspect alignment. `LgG6Main0MathTest` checks the geometry.
Cropping to a smaller encoder surface can lower encode/network load, but does
**not** establish reduced sensor/ISP activity, nor guarantee 4K readout.

## Root

**Root is not used by the streaming code.** The optional script
`scripts/lg_g6_h872_readonly_diag.sh` only collects selected Camera2/app
logs, properties and thermal readings on the device. It does not change
SELinux, system partitions, frequency governors, permissions or binaries.

## Smoke test / verification

```bash
ffprobe -rtsp_transport tcp -v error -select_streams v:0 \
  -show_entries stream=codec_name,width,height,avg_frame_rate,bit_rate \
  -of default=noprint_wrappers=1 rtsp://PHONE_IP:8554/live
```

Verify RTSP, ONVIF discovery, MJPEG, still JPEG, manual controls,
camera restart/fallback, reconnect, recording and 20–30 minutes heat.
A green GitHub Actions build verifies compilation/unit tests, **not**
LG Camera HAL compatibility. Never merge this feature to main until field A/B
captures prove sensor fidelity and vendor key effects.

## Source provenance (not published)

Analyzed from user-owned local
`LG_G6_H872_MAIN0_Camera_Developer_Handoff_v2.zip`:
`DEVICE_FINDINGS.md`, `addendum_main0/LG_OEM_TAGS_AND_CALLSITES.md`,
`addendum_main0/MAIN0_IMPLEMENTATION_NOTES.md` and DEX callsite tables.
LGCameraApp.apk and shared libraries deliberately remain outside GitHub.
