# go2rtc / ONVIF and device telemetry — LG G6 H872

## Why `connection reset by peer` can happen before authentication

The application's old ONVIF service on tcp/8000 inherited the **web server's
TLS** setting. Web TLS is enabled by default. Go2rtc's `onvif://` client sends
an **HTTP SOAP POST**, not an HTTPS handshake, to that device-service URL. If
port 8000 expects HTTPS, the TLS stack closes the connection and go2rtc reports
`Post "http://PHONE_IP:8000/onvif/device_service": read: connection reset by peer`.
Adding a username/password does not fix a transport-layer mismatch.

### Recommended setup for an isolated, trusted LAN

1. On the phone, enable RTSP and the ONVIF service. In **Settings →
   RTSP / ONVIF**, turn **OFF** the new **ONVIF over HTTPS (TLS)** switch.
   This changes only port 8000 to plaintext HTTP; the web server and RTSPS
   retain their own TLS setting.
2. For **no password**, separately disable the app's optional authentication
   (Settings → Authentication). Restrict port 8000 on your LAN; unauthenticated
   ONVIF makes camera details/stream URLs visible to devices on that network.
3. In go2rtc use the ONVIF device **host:port**, not a raw HTTP device-service URL:

   ```yaml
   streams:
     lg_g6_main0: onvif://PHONE_IP:8000
   ```

4. To check the SOAP endpoint from Windows on the same LAN (no password):

   ```powershell
   $body='<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><s:Body><tds:GetCapabilities/></s:Body></s:Envelope>'
   Invoke-WebRequest -Uri 'http://PHONE_IP:8000/onvif/device_service' -Method Post -ContentType 'application/soap+xml; charset=utf-8' -Body $body
   ```

   Expect HTTP 200 with a `GetCapabilitiesResponse`. When HTTPS is enabled
   on the ONVIF port, use an HTTPS-capable client instead and trust the
   configured certificate. Regular go2rtc `onvif://` expects HTTP.
5. If ONVIF discovery works but the video does not, test the advertised
   `rtsp://` or `rtsps://PHONE_IP:8554/live` separately. When web TLS is on,
   RTSPS may still be advertised. Go2rtc can handle RTSP(S); for local
   self-signed certificates a direct `rtspx://` URL may be useful.
   Do **not** assume an ONVIF SOAP success proves RTSP decoder success.

If the error persists, check the app logcat for `ONVIF TLS handshake failed`
vs `ONVIF HTTP connection ... failed`. Attach diagnostic output only after
reviewing it for network addresses, credentials or other private information.

## New live status-overlay metrics

`/metrics.json` (subject to the same authentication as `/info.json`)
returns battery and Wi-Fi plus:

- `cpuPercent`: 0–100; actual **system CPU** based on the difference
  between two `/proc/stat` tick samples, or **app CPU** when Android
  restricts system counters. `cpuScope` identifies the source.
- `temperatureCelsius` and `temperatureSource`: prefer an accessible
  CPU or SoC thermal-zone sensor, else **battery temperature** from Android's
  sticky ACTION_BATTERY_CHANGED intent. No fake CPU reading is invented.
  It is explicitly marked as Battery on devices where the CPU sensor
  requires privileged access.
- The browser polls every **5 seconds**, using a lightweight endpoint so
  Camera2 capabilities are not re-enumerated on every refresh. System CPU
  is sampled at most every 4 seconds; thermal sensors at most every 20s.
- No root/SU request is issued. If CPU/SoC sensor temperature isn't exposed
  to third-party Android 8 apps, the display falls back to battery temperature.

## Build / test status

Branch: `fix/onvif-go2rtc-telemetry` based on
`feature/manual-camera-4k-stream` at commit
`986d3c8972334b1aa689478a3484b3ac8c304f66`.
Experimental APK: `0.18.0-delliaf.5`, only **arm64-v8a**.

Tests added: plain HTTP SOAP `GetCapabilities` and `GetProfiles` without
credentials over actual TCP; `/proc/stat` delta and thermal unit
normalization. Real-go2rtc and LG-H872 hardware interoperability must be
confirmed on the phone; a successful Gradle build alone is not evidence
the connection has been fixed in the user's network.
