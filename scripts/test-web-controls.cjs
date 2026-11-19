// Browser regression tests: real sendControl() must not silently accept HTML
// from GET "/" and should display an HTTP/JSON error. No browser required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const start = html.indexOf('    function sendControl(param, val) {');
const end = html.indexOf('    // ---- Recording mode:', start);
assert(start >= 0 && end > start, 'Dashboard sendControl() missing');
assert(html.includes('id="batteryTemperatureStatus"'), 'Independent battery temperature badge missing');

async function scenario(reply, expectedSuccess) {
  const status = {style: {display:'none'}, textContent:'', title:''};
  const calls = [], updates = [], warnings = [];
  const ctx = {
    getElById: id => id === 'controlStatus' ? status : null,
    updateLocalLensSetting: (...args) => updates.push(args),
    nextControlTs: () => 1800000000001,
    encodeURIComponent,
    fetch: async (...args) => {calls.push(args); return reply;},
    console: {warn: (...args) => warnings.push(args)}
  };
  vm.createContext(ctx);
  vm.runInContext(html.slice(start, end), ctx);
  const result = await ctx.sendControl('focus_distance', '4.5');
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], '/control/set?key=focus_distance&value=4.5&ts=1800000000001');
  assert.equal(calls[0][1].method, 'POST');
  assert.equal(calls[0][1].credentials, 'same-origin');
  assert.equal(status.style.display, 'block');
  if(expectedSuccess) {
    assert.equal(result.ok, true);
    assert.equal(updates.length, 1);
    assert.match(status.textContent, /Команда передана/);
  } else {
    assert.equal(result, null);
    assert.equal(updates.length, 0);
    assert.match(status.textContent, /Ошибка управления/);
    assert(warnings.length > 0);
  }
}

(async()=>{
  await scenario({ok:true,status:200,json: async()=>({ok:true,keys:['focus_distance']})},true);
  await scenario({ok:true,status:200,json: async()=>{throw Error('Unexpected <html>');}},false);
  await scenario({ok:false,status:401,json: async()=>({ok:false})},false);
  await scenario({ok:true,status:200,json: async()=>({ok:true,keys:['zoom']})},false);
  console.log('OK: explicit POST controls, verified response, HTML/401/wrong-key errors surfaced');
})().catch(err=>{console.error(err);process.exitCode=1;});
