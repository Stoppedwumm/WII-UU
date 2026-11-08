const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync('resources/web/pad.html', 'utf8');

function element() {
  const classes = new Set();
  const handlers = {};
  return {
    handlers, value: '', children: [],
    append(...nodes) { this.children.push(...nodes); },
    add() {},
    setAttribute() {},
    showModal() { this.open = true; },
    close() { this.open = false; },
    addEventListener(type, handler) { handlers[type] = handler; },
    classList: {
      contains: name => classes.has(name),
      toggle(name, on) { if (on) classes.add(name); else classes.delete(name); }
    },
    querySelectorAll: () => [],
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 100, height: 100 }),
    setPointerCapture() {}
  };
}

const pad = element(), raceDrive = element(), controlLayout = element();
const layoutToggle = element();
const els = { pad, raceDrive, controlLayout, layoutToggle };
const stored = new Map();
const $ = id => els[id] ||= element();
const game = { name: 'Mario Kart DS (Europe)', system: 'Nintendo DS' };
const events = [], timers = new Map();
let nextTimer = 0;
const context = vm.createContext({
  $,
  S: { game, mode: 'game' },
  store: { get: key => stored.get(key), set: (key, value) => stored.set(key, value) },
  sendButton: (button, down) => events.push([button, down]),
  wakeLock() {},
  document: { ...element(), createElement: element }, window: element(),
  Option: function(text, value) { Object.assign(this, { text, value }); },
  crypto: { getRandomValues: () => [1, 2] },
  setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
  clearTimeout: id => timers.delete(id)
});
vm.runInContext(html.slice(html.indexOf('const pad = $("pad");'), html.indexOf('// physical keyboard')), context);
vm.runInContext(html.slice(html.indexOf('const layoutEditor ='), html.indexOf('// ---------------------------------------------------------------- start')), context);
context.updateControlLayout();
assert.equal(pad.classList.contains('racing'), true);
assert.equal(context.racingLayout(game, 'standard'), false);
assert.equal(context.racingLayout({ ...game, name: 'Another game' }, 'auto'), false);
assert.equal(context.racingLayout({ ...game, name: 'Renamed ROM' }, 'mario-kart-ds'), true);

const dpad = element();
const dispatch = (type, pointerId, selector, clientX, clientY) => {
  const target = { closest: value => value === selector ? (selector === '#raceDrive' ? raceDrive : dpad) : null };
  pad.handlers[type]({ type, pointerId, target, clientX, clientY, preventDefault() {} });
};
dispatch('pointerdown', 1, '[data-dpad]', 5, 50);
dispatch('pointerdown', 2, '#raceDrive', 50, 50);
dispatch('pointermove', 2, null, 50, 10);
dispatch('pointermove', 1, null, 95, 50);
dispatch('pointermove', 2, null, 50, 50);
dispatch('pointermove', 2, null, 50, 90);
dispatch('pointermove', 2, null, 50, 95);
assert.equal(timers.size, 1);
timers.values().next().value();
dispatch('pointermove', 2, null, 50, 50);
dispatch('pointermove', 2, null, 50, 90);
dispatch('lostpointercapture', 2, null, 50, 90);
assert.equal(layoutToggle.textContent, 'Standard layout');
layoutToggle.onclick();
assert.equal(controlLayout.value, 'standard');
assert.equal(layoutToggle.textContent, 'Racing layout');
assert.equal(pad.classList.contains('racing'), false);
assert.equal(timers.size, 0);
assert.deepEqual(events, [
  ['LEFT', true], ['A', true], ['R', true], ['LEFT', false], ['RIGHT', true], ['R', false],
  ['L', true], ['L', false], ['L', true], ['A', false], ['RIGHT', false], ['L', false]
]);
layoutToggle.onclick();
assert.equal(controlLayout.value, 'mario-kart-ds');
assert.equal(pad.classList.contains('racing'), true);
context.S.game = null;
context.updateControlLayout();
assert.equal(layoutToggle.classList.contains('hidden'), true);
assert.match(html, /data-b="MINUS">Select</);
assert.match(html, /data-b="PLUS">Start</);
context.S.game = game;
$('editLayouts').onclick();
assert.equal($('layoutEditor').open, true);
$('layoutName').value = 'My two-thumb layout';
const fields = $('layoutFields').children;
fields[0].children[2].value = 'B';
fields[1].children[2].value = 'ZR';
fields[2].children[2].value = 'ZL';
fields[2].children[3].value = 'hold';
$('layoutForm').onsubmit({ preventDefault() {} });
assert.equal($('layoutEditor').open, false);
assert.equal(controlLayout.value, 'custom-1-2');
const saved = JSON.parse(stored.get('customLayouts'));
assert.equal(saved[0].name, 'My two-thumb layout');
events.length = 0;
dispatch('pointerdown', 3, '#raceDrive', 50, 50);
dispatch('pointermove', 3, null, 50, 10);
dispatch('pointermove', 3, null, 50, 90);
layoutToggle.onclick();
assert.deepEqual(events, [['B', true], ['ZR', true], ['ZR', false], ['ZL', true], ['ZL', false], ['B', false]]);
assert.equal(layoutToggle.textContent, 'Custom layout');
layoutToggle.onclick();
assert.equal(controlLayout.value, 'custom-1-2');
$('editLayouts').onclick();
assert.equal($('layoutName').value, 'My two-thumb layout');
$('layoutName').value = 'Renamed layout';
$('layoutForm').onsubmit({ preventDefault() {} });
assert.equal(JSON.parse(stored.get('customLayouts'))[0].name, 'Renamed layout');
$('editLayouts').onclick();
$('deleteLayout').onclick();
assert.equal(controlLayout.value, 'auto');
assert.deepEqual(JSON.parse(stored.get('customLayouts')), []);
console.log('GamePad controller-layout tests passed');
