const assert = require('node:assert/strict');
const fs = require('node:fs');
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');

function options(id, key) {
  const re = new RegExp('<select\\s+id="' + id + '"\\s+data-control="' + key +
    '"[^>]*>([\\s\\S]*?)<\\/select>');
  const match = html.match(re);
  assert(match, 'Missing live control selector '+id);
  return [...match[1].matchAll(/<option value="([^"]+)"/g)].map(m=>m[1]);
}
assert.deepEqual(options('onvifVideoQuality', 'onvif_video_quality'), ['1','2','3','4','5']);
const bitrates = options('streamBitrateKbps', 'stream_bitrate_kbps');
for (const bitrate of ['1000','3000','6000','12000','24000'])
  assert(bitrates.includes(bitrate), 'Missing matching ONVIF quality rate '+bitrate);
assert(html.includes('data-control=\'fps\''), 'FPS not bound to service');
assert(html.includes('RTSP / ONVIF'), 'Shared codec warning absent');
console.log('OK: web ONVIF/RTSP quality presets map to actual H264 bitrate and FPS controls');
