const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync('resources/web/pad.html', 'utf8');

function element() {
  const classes = new Set();
  const handlers = {};
  return {
    handlers,
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
const els = { pad, raceDrive, controlLayout };
const game = { name: 'Mario Kart DS (Europe)', system: 'Nintendo DS' };
const events = [], timers = new Map();
let nextTimer = 0;
const context = vm.createContext({
  $: id => els[id] || element(),
  S: { game, mode: 'game' },
  store: { get: () => null, set() {} },
  sendButton: (button, down) => events.push([button, down]),
  wakeLock() {},
  document: element(), window: element(),
  setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
  clearTimeout: id => timers.delete(id)
});
vm.runInContext(html.slice(html.indexOf('const pad = $("pad");'), html.indexOf('// physical keyboard')), context);
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
controlLayout.value = 'standard';
controlLayout.onchange();
assert.equal(pad.classList.contains('racing'), false);
assert.equal(timers.size, 0);
assert.deepEqual(events, [
  ['LEFT', true], ['A', true], ['R', true], ['LEFT', false], ['RIGHT', true], ['R', false],
  ['L', true], ['L', false], ['L', true], ['A', false], ['RIGHT', false], ['L', false]
]);
console.log('GamePad controller-layout tests passed');
