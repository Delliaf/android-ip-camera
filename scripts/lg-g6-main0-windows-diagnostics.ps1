#requires -version 5.1
<#
Read-only LG-H872 Camera ID0 camera/codec/thermal diagnosis on Windows.
Requires adb in PATH and USB debugging authorized. Optional 'su' root.
Run: powershell -NoProfile -ExecutionPolicy Bypass -File .\lg-g6-main0-windows-diagnostics.ps1
#>
param([ValidateRange(0,300)][int]$Seconds=15)
$ErrorActionPreference='Stop'
if(-not (Get-Command adb -ErrorAction SilentlyContinue)){throw 'adb missing from PATH'}
if(((adb get-state 2>&1 | Out-String).Trim()) -ne 'device'){throw 'ADB connection not authorized'}
$dir=Join-Path ([Environment]::GetFolderPath('Desktop')) ("LG-G6-main0-"+(Get-Date -Format yyyyMMdd-HHmmss))
New-Item -ItemType Directory -Force -Path $dir | Out-Null
function Capture([string]$name,[string[]]$adbArgs){
  Write-Host $name
  try { (& adb @adbArgs 2>&1 | Out-String) | Set-Content -LiteralPath (Join-Path $dir $name) -Encoding UTF8 }
  catch { $_ | Out-String | Set-Content -LiteralPath (Join-Path $dir $name) }
}
Capture 'device.txt' @('devices','-l')
Capture 'properties.txt' @('shell','getprop')
Capture 'camera.txt' @('shell','dumpsys','media.camera')
Capture 'codecs.txt' @('shell','dumpsys','media.codec')
Capture 'battery.txt' @('shell','dumpsys','battery')
Capture 'thermal.txt' @('shell','dumpsys','thermalservice')
Capture 'meminfo.txt' @('shell','dumpsys','meminfo','com.github.digitallyrefined.androidipcamera')
Capture 'cpu.txt' @('shell','top','-n','1')
Capture 'surfaces.txt' @('shell','dumpsys','SurfaceFlinger','--list')
Capture 'processes.txt' @('shell','ps','-A')
$root = ((& adb shell su -c id 2>&1 | Out-String) -match 'uid=0')
if($root){ Capture 'root-camera.txt' @('shell','su','-c','dumpsys media.camera') }
$pattern='LGG6Camera0|LgMain0Direct|CameraXCapture|CameraGlPipe|StreamingService|MediaCodec|H264|RTSP|ONVIF|cameraserver|CameraDevice|FATAL EXCEPTION|SensorExposure|NOISE_REDUCTION'
function Logs([string]$name){
  $output=& adb logcat -d -t 30000 -v threadtime 2>&1 | Select-String -Pattern $pattern
  $output | ForEach-Object {$_.Line} | Set-Content -LiteralPath (Join-Path $dir $name) -Encoding UTF8
}
Logs 'log-before.txt'
for($i=5;$i -le $Seconds;$i+=5){
 Start-Sleep -Seconds 5
 Capture ("camera-t"+$i+".txt") @('shell','dumpsys','media.camera')
 Capture ("battery-t"+$i+".txt") @('shell','dumpsys','battery')
}
Logs 'log-after.txt'
"Read-only. Root available: $root. Contains possibly sensitive device IDs, network and logs. REVIEW before sharing." |
 Set-Content -LiteralPath (Join-Path $dir 'README.txt') -Encoding UTF8
$zip=$dir+'.zip'; Compress-Archive -Path (Join-Path $dir '*') -DestinationPath $zip -Force
Write-Host "DONE: $zip"
