import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import createChoccy from "../../docs/demo/cores/choccy-gambatte.mjs";
import { scale2x, scale3x, xbr2, crt } from "../../docs/demo/ui/filters.mjs";

const rom = await readFile(
  new URL("../../docs/demo/milk-run.gb", import.meta.url),
);
const core = await createChoccy({ print: () => {}, printErr: () => {} });
const ptr = core._malloc(rom.length);
core.HEAPU8.set(rom, ptr);
assert.equal(core._cm_load(ptr, rom.length), 1);
core._free(ptr);
for (let i = 0; i < 40; i++) core._cm_run();
assert.equal(core._cm_width(), 160);
assert.equal(core._cm_height(), 144);
assert.ok(core._cm_audio_frames() > 0);
const frame = () =>
  core.HEAPU8.slice(core._cm_pixels(), core._cm_pixels() + 160 * 144 * 4);
const initial = frame();
const size = core._cm_state_size(),
  state = core._malloc(size);
assert.ok(size > 0);
assert.equal(core._cm_save(state, size), 1);
core._cm_buttons(0, 1 << 7);
for (let i = 0; i < 30; i++) core._cm_run();
assert.notDeepEqual(frame(), initial);
core._cm_buttons(0, 0);
assert.equal(core._cm_restore(state, size), 1);
core._free(state);
for (let i = 0; i < 3; i++) core._cm_run();
assert.deepEqual(frame(), initial);
core._cm_stop();
assert.equal(core._cm_width(), 0);
core._ui_count(6);
assert.equal(core._ui_move(-1, 0, 5), 5);
assert.equal(core._ui_move(0, 1, 5), 4);

const input = Uint32Array.of(0x224466, 0xff0000, 0x0055aa, 0x22ff33);
for (const [filter, scale] of [
  [scale2x, 2],
  [scale3x, 3],
  [xbr2, 2],
]) {
  const output = new Uint32Array(4 * scale * scale);
  filter(input, 2, 2, output);
  assert.ok(output.every((x) => x !== 0));
  assert.equal(output[0], input[0]);
}
const output = new Uint32Array(64);
crt(input, 2, 2, 4, output);
assert.ok(output.every((x) => x !== 0));
console.log(
  "WebAssembly ROM playback, input, audio, state restore and filter smoke tests passed.",
);
