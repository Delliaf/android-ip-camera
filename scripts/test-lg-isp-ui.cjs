// Non-Android sanity checks on the REAL dashboard bindings for LG H872.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');

function selectBlock(id, key) {
  const re = new RegExp('<select\\s+id="' + id + '"\\s+data-control="' + key +
    '"[^>]*>([\\s\\S]*?)<\\/select>');
  const m = html.match(re);
  assert(m, id + ' missing data-control binding');
  const options = [...m[1].matchAll(/<option value="([^"]+)"/g)].map(x => x[1]);
  assert.equal(options.length, new Set(options).size, 'duplicate mode values in ' + id);
  return options;
}
assert.deepEqual(selectBlock('lgMain0NoiseReduction', 'lg_main0_noise_reduction'),
  ['auto', 'off', 'fast', 'hq', 'minimal', 'zsl']);
assert.deepEqual(selectBlock('lgMain0EdgeMode', 'lg_main0_edge_mode'),
  ['auto', 'off', 'fast', 'hq', 'zsl']);
assert(html.includes('lgMain0NoiseReduction: settings.lgNoiseReduction'),
  'NR selection missing from server state restore');
assert(html.includes('lgMain0EdgeMode: settings.lgEdgeMode'),
  'EDGE selection missing from server state restore');
// Unlike the older bundled CameraX preset, new independent selectors
// must NEVER be marked disabled when using the direct backend.
assert(!/getElById\('lgMain0NoiseReduction'\)[^;]*disabled\s*=/.test(html));
assert(!/getElById\('lgMain0EdgeMode'\)[^;]*disabled\s*=/.test(html));
assert(html.includes('Camera0') && html.includes('CaptureResult'),
  'Actual HAL readback guidance not visible');
console.log('OK: independent NR + EDGE controls in BOTH camera backends, state restoration and HAL readback note');
