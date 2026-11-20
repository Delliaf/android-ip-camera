const assert = require('node:assert/strict');
const fs = require('node:fs');
const service = fs.readFileSync('app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt','utf8');
const onvif = fs.readFileSync('app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/OnvifServer.kt','utf8');
const rtsp = fs.readFileSync('app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/AndroidRtspHost.kt','utf8');
const prefs = fs.readFileSync('app/src/main/res/xml/preferences.xml','utf8');

assert(service.includes('tls = { null }'), 'ONVIF SOAP must be plain HTTP for LAN go2rtc');
assert(service.includes('getBoolean("onvif_auth_enabled", false)'),
  'ONVIF auth independent of global web auth');
assert(service.includes('getBoolean("rtsp_tls_enabled", false)'),
  'RTSP TLS must be separately configurable');
assert(rtsp.includes('getBoolean("rtsp_auth_enabled"'),
  'RTSP auth must be separately configurable in AndroidRtspHost');
assert(service.includes('if (!prefs.contains("rtsp_auth_enabled"))'),
  'Preserve previous RTSP auth behavior through migration');
assert(rtsp.includes('rtsp_auth_enabled') && !rtsp.includes('if (!prefs.getBoolean("enable_auth", true))'),
  'RTSP auth must not follow web auth directly');

for (const key of ['onvif_auth_enabled','rtsp_auth_enabled','rtsp_tls_enabled',
                   'lg_main0_background_hold'])
  assert(prefs.includes('android:key="'+key+'"'), 'Missing Android preference '+key);
assert(!prefs.includes('android:key="onvif_tls_enabled"'),
  'Old TLS checkbox should not mislead ONVIF go2rtc users');
assert(service.includes('holdCameraForRtsp()'),
  'Persistent LG H872 background camera policy absent');
assert(onvif.includes('SetVideoEncoderConfiguration') &&
       onvif.includes('SetImagingSettings') &&
       onvif.includes('SetRelayOutputState') &&
       onvif.includes('apph:DeviceHealth'),
  'ONVIF SOAP control/health capabilities missing');
console.log('OK: ONVIF HTTP without web auth dependency, independent RTSP, safe legacy auth migration and focus retention');
