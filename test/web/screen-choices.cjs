const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync('resources/web/pad.html', 'utf8');
const elements = new Map();
const $ = id => {
  if (!elements.has(id)) elements.set(id, { hidden: false, textContent: '', classList: {
    toggle(name, hidden) { if (name === 'hidden') elements.get(id).hidden = hidden; }
  }});
  return elements.get(id);
};
let selected;
const context = { $, openScreen: mode => { selected = mode; } };
vm.createContext(context);
vm.runInContext(html.match(/function updateScreenChoices\(second\) \{[\s\S]*?\n\}/)[0], context);
context.updateScreenChoices(null);
assert.equal($('playSecond').hidden, true);
context.updateScreenChoices('Touch screen');
assert.equal($('playSecond').hidden, false);
assert.equal($('playSecond').textContent, 'Show touch screen');
assert.equal($('segSecond').hidden, false);
assert.match(html, /id="playSecond"/);
vm.runInContext(html.match(/\$\("playSecond"\)\.onclick = .*;/)[0], context);
$('playSecond').onclick();
assert.equal(selected, 'second');
// Returning to Now playing must leave a selectable touch-screen button.
context.updateScreenChoices('Touch screen');
assert.equal($('playSecond').hidden, false);
context.updateScreenChoices(null);
assert.equal($('playSecond').hidden, true);
assert.equal($('segSecond').hidden, true);
console.log('GamePad screen-choice tests passed');

const refreshCode = html.slice(html.indexOf('const loadedPadBuild'), html.indexOf('// ---------------------------------------------------------------- networking'));
const saved = new Map();
const loads = [];
let releases = 0;
const refreshContext = {
  URL,
  document: { querySelector: () => ({ content: '1.14.3-old' }) },
  store: { set: (key, value) => saved.set(key, value) },
  location: { href: 'https://gamepad.example/?example=keep', replace: url => loads.push(url) },
  releasePadTouches: () => releases++,
  touchPointer: 1,
  touchEnd: () => releases++
};
vm.createContext(refreshContext);
vm.runInContext(refreshCode, refreshContext);
assert.equal(saved.get('loadedPadBuild'), '1.14.3-old');
assert.equal(refreshContext.refreshPadIfOutdated(undefined), false);
assert.equal(refreshContext.refreshPadIfOutdated('1.14.3-old'), false);
assert.equal(refreshContext.refreshPadIfOutdated('1.14.3-new'), true);
assert.equal(refreshContext.refreshPadIfOutdated('1.14.3-new'), true);
assert.deepEqual(loads, ['https://gamepad.example/?example=keep&pad-build=1.14.3-new']);
assert.equal(releases, 2);
const retryContext = { ...refreshContext, location: { ...refreshContext.location, href: loads[0] } };
vm.createContext(retryContext);
vm.runInContext(refreshCode, retryContext);
assert.equal(retryContext.refreshPadIfOutdated('1.14.3-new'), false);
assert.equal(loads.length, 1);
console.log('GamePad automatic refresh tests passed');
