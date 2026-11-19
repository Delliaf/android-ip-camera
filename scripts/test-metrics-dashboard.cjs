// Fast DOM-less regression test for Android 8 dashboard CPU/temperature overlays.
// CI loads the real updateStatusOverlay() function from index.html and exercises
// states that previously resulted in invisible metrics.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const begin = source.indexOf('    function updateStatusOverlay(data) {');
const finish = source.indexOf('    function loadDeviceInfo(', begin);
assert(begin >= 0 && finish > begin, 'Dashboard metrics renderer missing');
const js = source.slice(begin, finish);
const ids = ['batteryStatus', 'wifiStatus', 'cpuStatus', 'temperatureStatus', 'statusOverlay'];
const elements = Object.fromEntries(ids.map(id => [
  id, {style: {display: 'none'}, textContent: '', innerHTML: '', title: ''}
]));
const ctx = vm.createContext({getElById: id => elements[id], console});
vm.runInContext(js, ctx);
function render(value) {ctx.updateStatusOverlay(value);}
function visible(id) {return elements[id].style.display !== 'none';}

render({batteryPercent: 75, wifiStrength: 89, cpuPercent: -1, cpuScope: 'unavailable',
        cpuNote: 'collecting first sample', temperatureCelsius: null,
        temperatureSource: 'unavailable', temperatureNote: 'access denied'});
assert(visible('cpuStatus'), 'CPU must NOT be silently hidden while warming up');
assert(visible('temperatureStatus'), 'Unavailable temperature must show explicit placeholder');
assert.match(elements.cpuStatus.textContent, /CPU/);
assert.match(elements.temperatureStatus.textContent, /Temp/);
assert.match(elements.temperatureStatus.title, /access denied/);
assert(visible('statusOverlay'));

render({batteryPercent: 91, wifiStrength: 45, cpuPercent: 27, cpuScope: 'app',
        cpuNote: 'process CPU only', temperatureCelsius: 38.4,
        temperatureSource: 'Battery', temperatureNote: 'battery fallback'});
assert.match(elements.cpuStatus.textContent, /App CPU 27%/);
assert.match(elements.temperatureStatus.textContent, /Battery 38.4°C/);
assert.equal(elements.temperatureStatus.title, 'battery fallback');

render({cpuPercent: 58, cpuScope: 'system',
        temperatureCelsius: 66.8, temperatureSource: 'SoC'});
assert.match(elements.cpuStatus.textContent, /CPU 58%/);
assert.doesNotMatch(elements.cpuStatus.textContent, /App/);
assert.match(elements.temperatureStatus.textContent, /SoC 66.8°C/);
assert(visible('cpuStatus') && visible('temperatureStatus'));
console.log('OK: metrics visible on warm-up, fallback and working CPU/SoC sensor states');
