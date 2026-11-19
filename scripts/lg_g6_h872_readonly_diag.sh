#!/system/bin/sh
# LG G6 H872 MAIN0: local, read-only diagnostic collection; root optional.
# Run on the LG via Termux or adb shell; does NOT install/modify system files.
OUT="${1:-/sdcard/Download/lg_g6_main0_diagnostic.txt}"
{
  echo '=== LG H872 Camera0 diagnostic (read only) ==='
  date
  echo '-- device --'
  getprop ro.product.model
  getprop ro.build.version.release
  getprop ro.build.version.sdk
  getprop ro.product.device
  echo '-- camera0 characteristics/active sessions (filtered) --'
  dumpsys media.camera 2>&1 | grep -E -i 'camera id|camera 0|camera2|client|stream|resolution|manual|crop|sensitivity|sensor info|focus|exposure' | head -n 140
  echo '-- selected application telemetry --'
  logcat -d -v time -s LGG6Main0Probe:I LGG6Main0Result:I CameraXCapture:I StreamingService:I '*:S' 2>&1 | tail -n 180
  echo '-- thermal zones (read only) --'
  for p in /sys/class/thermal/thermal_zone*/temp; do
    [ -r "$p" ] && printf '%s=' "$p" && cat "$p"
  done
  echo '-- battery --'
  dumpsys battery 2>&1 | grep -E -i 'temperature|level|status|voltage|plugged'
} > "$OUT"
echo "Saved local diagnosis: $OUT"
