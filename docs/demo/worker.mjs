import createChoccy from "./cores/choccy-gambatte.mjs";
let core,
  running = false,
  loaded = false,
  timer,
  deadline = 0,
  sent = 0,
  epoch = 0,
  awaitingFrame = false;
const ready = createChoccy({
  print: () => {},
  printErr: (text) => console.warn("[choccy]", text),
}).then((m) => {
  core = m;
  postMessage({ type: "ready" });
});
function bytesAt(ptr, size) {
  return core.HEAPU8.slice(ptr, ptr + size);
}
function withBytes(bytes, fn) {
  const ptr = core._malloc(bytes.length);
  if (!ptr) throw Error("Not enough emulator memory");
  try {
    core.HEAPU8.set(bytes, ptr);
    return fn(ptr, bytes.length);
  } finally {
    core._free(ptr);
  }
}
function tick() {
  if (!running) return;
  try {
    const period = 1000 / core._cm_fps();
    let now = performance.now();
    // Discard accumulated debt after sleep/tab suspension rather than speed up.
    if (now - deadline > period * 3) deadline = now;
    if (now >= deadline) {
      core._cm_run();
      deadline += period;
      sent++;
      const w = core._cm_width(),
        h = core._cm_height();
      if (w && h && !awaitingFrame) {
        const rgba = bytesAt(core._cm_pixels(), w * h * 4);
        awaitingFrame = true;
        postMessage({ type: "frame", rgba, w, h, aspect: core._cm_aspect() }, [
          rgba.buffer,
        ]);
      }
      const frames = core._cm_audio_frames();
      if (frames) {
        const pcm = new Int16Array(
          bytesAt(core._cm_audio(), frames * 4).buffer,
        );
        postMessage({ type: "audio", pcm, rate: core._cm_rate() }, [
          pcm.buffer,
        ]);
      }
      if (now - epoch > 1000) {
        postMessage({
          type: "stats",
          fps: (sent * 1000) / (now - epoch),
          target: core._cm_fps(),
        });
        epoch = now;
        sent = 0;
      }
    }
    timer = setTimeout(tick, Math.max(0, deadline - performance.now()));
  } catch (error) {
    running = false;
    postMessage({ type: "error", message: error.message });
  }
}
function setRunning(value) {
  running = value && loaded;
  clearTimeout(timer);
  if (running) {
    deadline = performance.now();
    epoch = deadline;
    sent = 0;
    tick();
  }
}
globalThis.onmessage = async ({ data: m }) => {
  try {
    await ready;
    let result;
    switch (m.type) {
      case "frame-drawn":
        awaitingFrame = false;
        break;
      case "load":
        setRunning(false);
        awaitingFrame = false;
        loaded = false;
        if (!withBytes(m.rom, (p, n) => core._cm_load(p, n)))
          throw Error("The core could not load this Game Boy ROM.");
        loaded = true;
        if (m.sram?.length === core._cm_sram_size())
          core.HEAPU8.set(m.sram, core._cm_sram());
        setRunning(true);
        result = { fps: core._cm_fps(), rate: core._cm_rate() };
        break;
      case "pause":
        setRunning(!m.paused);
        break;
      case "stop":
        setRunning(false);
        core._cm_stop();
        loaded = false;
        awaitingFrame = false;
        break;
      case "reset":
        core._cm_reset();
        break;
      case "input":
        core._cm_buttons(0, m.mask);
        break;
      case "pointer":
        core._cm_pointer(m.x, m.y, m.down);
        break;
      case "save": {
        const n = core._cm_state_size();
        if (!n) throw Error("Save states are unavailable.");
        const p = core._malloc(n);
        try {
          if (!p || !core._cm_save(p, n)) throw Error("Saving failed");
          result = bytesAt(p, n);
        } finally {
          core._free(p);
        }
        break;
      }
      case "restore":
        if (!withBytes(m.bytes, (p, n) => core._cm_restore(p, n)))
          throw Error("This state is incompatible with the running game/core.");
        break;
      case "sram":
        result = bytesAt(core._cm_sram(), core._cm_sram_size());
        break;
      case "menu-count":
        core._ui_count(m.count);
        break;
      case "menu-select":
        result = core._ui_select(m.index);
        break;
      case "menu-move":
        result = core._ui_move(m.dx, m.dy, m.columns);
        break;
    }
    if (m.id) postMessage({ type: "reply", id: m.id, result });
  } catch (error) {
    postMessage({ type: "error", id: m.id, message: error.message });
  }
};
ready.catch((error) =>
  postMessage({
    type: "error",
    message: "Could not load WebAssembly: " + error.message,
  }),
);
