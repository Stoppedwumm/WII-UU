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
