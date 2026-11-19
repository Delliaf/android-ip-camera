#!/system/bin/sh
# Run from rooted LG-H872 Termux: su -c 'sh /sdcard/lg-g6-main0-diagnostics.sh'
# READ-ONLY: never changes camera properties, SELinux or system partitions.
OUT="${1:-/sdcard/lg-g6-main0-report}"
mkdir -p "$OUT" || exit 1
getprop ro.product.model > "$OUT/model.txt"
getprop ro.build.version.release > "$OUT/android.txt"
getprop ro.build.fingerprint > "$OUT/firmware.txt"
dumpsys media.camera > "$OUT/media-camera.txt" 2>&1
dumpsys media.codec > "$OUT/media-codec.txt" 2>&1
dumpsys battery > "$OUT/battery.txt" 2>&1
# Limit logs to camera tags. Inspect for sensitive data before distributing.
logcat -d -v threadtime 2>/dev/null | grep -iE 'LGG6Camera0|CameraXCapture|SCALER_CROP_REGION|cameraserver|Camera3|CameraDevice' | tail -n 1200 > "$OUT/camera-logcat.txt"
for p in /sys/class/thermal/thermal_zone*/temp; do
  [ -r "$p" ] && printf '%s %s\n' "$p" "$(cat "$p")"
done > "$OUT/thermal.txt"
echo "Read-only report in $OUT"
