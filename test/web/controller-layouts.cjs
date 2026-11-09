const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync('resources/web/pad.html', 'utf8');

function element() {
  const classes = new Set();
  const handlers = {};
  return {
    handlers, value: '', children: [], dataset: {}, style: {},
    replaceChildren(...nodes) { this.children = nodes; },
    append(...nodes) { this.children.push(...nodes); },
    add() {},
    remove() {},
    setAttribute() {},
    showModal() { this.open = true; },
    close() { this.open = false; },
    addEventListener(type, handler) { handlers[type] = handler; },
    classList: {
      contains: name => classes.has(name),
      add: name => classes.add(name),
      remove: name => classes.delete(name),
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
let hostLayouts = [];
const context = vm.createContext({
  $,
  S: { game, mode: 'game' },
  store: { get: key => stored.get(key), set: (key, value) => stored.set(key, value) },
  sendButton: (button, down) => events.push([button, down]),
  wakeLock() {},
  encodeURIComponent,
  api: async (path, options) => {
    if (options?.body) {
      const layout = JSON.parse(options.body);
      hostLayouts = [...hostLayouts.filter(item => item.id !== layout.id), layout];
    } else if (options?.method === 'POST') {
      hostLayouts = hostLayouts.filter(item => !path.endsWith(item.id));
    }
    return { ok: true, json: async () => JSON.parse(JSON.stringify(hostLayouts)) };
  },
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
dispatch('pointermove', 1, null, 35, 88); // drifting diagonally must keep left held
assert.deepEqual(events, [['LEFT', true], ['A', true], ['R', true]]);
dispatch('pointermove', 1, null, 95, 62);
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
(async () => {
  context.S.game = game;
  $('editLayouts').onclick();
  assert.equal($('layoutEditor').open, true);
  $('layoutName').value = 'My two-thumb layout';
  const palette = $('layoutPalette'), canvas = $('layoutCanvas');
  palette.handlers.pointerdown({ pointerId: 3, clientX: 10, clientY: -20, preventDefault() {},
    target: { closest: () => ({ dataset: { kind: 'stick' } }) } });
  palette.handlers.pointerup({ pointerId: 3, type: 'pointerup', clientX: 40, clientY: 35 });
  const last = canvas.children.at(-1);
  assert.equal(last.widget.type, 'stick');
  canvas.handlers.pointerdown({ pointerId: 4, clientX: 40, clientY: 35, preventDefault() {},
    target: { closest: () => last, classList: { contains: () => false } } });
  canvas.handlers.pointermove({ pointerId: 4, clientX: 60, clientY: 55 });
  canvas.handlers.pointerup({ pointerId: 4, type: 'pointerup' });
  assert.equal(canvas.children.at(-1).widget.x, 51);
  await $('layoutForm').onsubmit({ preventDefault() {} });
  assert.equal($('layoutEditor').open, false);
  assert.equal(controlLayout.value, 'custom-1-2');
  assert.equal(pad.classList.contains('visual'), true);
  assert.equal(hostLayouts[0].name, 'My two-thumb layout');
  assert.equal(hostLayouts[0].widgets.at(-1).type, 'stick');

  layoutToggle.onclick();
  assert.equal(layoutToggle.textContent, 'Custom layout');
  layoutToggle.onclick();
  assert.equal(controlLayout.value, 'custom-1-2');
  $('editLayouts').onclick();
  assert.equal($('layoutName').value, 'My two-thumb layout');
  $('layoutName').value = 'Renamed layout';
  await $('layoutForm').onsubmit({ preventDefault() {} });
  assert.equal(hostLayouts[0].name, 'Renamed layout');
  $('editLayouts').onclick();
  await $('deleteLayout').onclick();
  assert.equal(controlLayout.value, 'auto');
  assert.deepEqual(hostLayouts, []);
  console.log('GamePad controller-layout tests passed');
})().catch(e => { console.error(e); process.exitCode = 1; });
