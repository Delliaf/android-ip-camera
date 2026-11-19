# Android 8 high-detail camera fork

Upstream: DigitallyRefined/android-ip-camera (v0.18.0). Experimental features of this fork are maintained separately on the `feature/manual-camera-4k-stream` branch. The upstream MIT licence is retained.

## New capabilities
- Per-lens manual ISO/shutter in CameraX (when `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR` is present), combined with manual focus in a single Camera2 capture-request options owner. Auto exposure can be restored without losing the focus setting.
- Main hardware H.264 bitrate (auto or explicit) and browser JPEG quality (auto, 85–100%; the app reduces JPEG quality under memory pressure).
- Independent requested CameraX sensor capture resolution (match, 2560x1440, 3840x2160), encoded output resolution (set in the normal Resolution control), and normalized ROI zoom/center (up to 8x).
- GPU texture crop before H.264 / RTSP encoding on the surface path; ROI cropping from NV21 before JPEG encoding in the MJPEG path.
- Preserves upstream RTSP/RTSPS, ONVIF (Profile S) and substream. The two streams share the original hardware H.264 encoder.

## First setup
1. Keep a copy of upstream settings. This APK has the same application ID as upstream, but a different signing key. Android may refuse an in-place update, so back up configuration before uninstalling the original.
2. Start camera with `CameraX` capture backend. Android 8 is allowed by `minSdk 24`, but manual exposure requires the actual camera to advertise `MANUAL_SENSOR`.
3. For a quarter-frame source crop set **Resolution = 1920×1080**, **Camera capture resolution = 3840×2160**, **ROI zoom = 2×**. The X/Y sliders move the center.
4. Prefer H.264 / RTSP for smooth streaming. Enable RTSP in Android app settings. The main path is typically `rtsp(s)://PHONE_IP:8554/live` and ONVIF is at port 8000 (see upstream README).
5. Check actual negotiated frame sizes; selecting 4K requests it but does not guarantee the device HAL delivers it. If the HAL fails, use Match or reduce capture size.

## Important caveats
- The 4K capture + GPU crop path is experimental and requires the CameraX surface path. Camera1 fallback can crop only from its negotiated lower-resolution preview. A source resolution dropdown is not proof of 4K pixels.
- Large CameraX ImageAnalysis + Preview + ImageCapture combinations may stall on old HALs; fallback to Camera1 is kept. Android 8 compatibility must be verified on a real device.
- ROI crop does **not** increase optical detail. It preserves pixels when the device actually supplies a higher-resolution source. A 2× crop on an existing 1080p source has only ~960×540 source pixels.
- Changing encoder output/capture size or bitrate requires restarting the capture session and can interrupt recording. Changing ROI X/Y/zoom does not.
- Advanced manual controls are best effort for Camera2, ignored on Camera1. Check camera capabilities and actual results.
- This branch has automated compile/unit-test checks, but physical camera/RTSP/ONVIF interoperability and sensor fidelity are not proven by JVM tests.

## Quality checks
The GitHub Actions workflow `android8-fork-ci.yml` runs unit tests and assembles an installable debug APK; download it from the run's Artifacts panel after a successful run.

Validate the RTSP codec, size and FPS:
```bash
ffprobe -rtsp_transport tcp -v error -select_streams v:0 -show_entries stream=codec_name,width,height,avg_frame_rate,bit_rate -of default=noprint_wrappers=1 'rtsp://PHONE_IP:8554/live'
```
Note: `ffprobe` shows the **encoded** resolution, not proof of the source Camera2 capture resolution. Check `adb logcat` for CameraX's negotiated `Preview.SurfaceRequest.resolution` and compare sharpness against a full 4K photo. If using HTTPS/RTSPS with a self-signed certificate, use a client that accepts the certificate.

## Privacy and stability
Retain upstream authentication/TLS defaults. Do not expose HTTP, RTSP or ONVIF ports to the public Internet without a VPN or secure reverse proxy. High-resolution continuous capture can overheat or damage old battery devices; monitor temperature and charging.
