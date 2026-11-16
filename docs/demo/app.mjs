import { desktopMenu, drawDockIcons } from "./ui/desktop-menu.mjs";
import { GameScreen, SystemTheme } from "./ui/library.mjs";
import { storage, romID } from "./storage.mjs";
const $ = (id) => document.getElementById(id),
  screen = new GameScreen(),
  canvas = $("screen"),
  ctx = canvas.getContext("2d");
const worker = new Worker(new URL("./worker.mjs", import.meta.url), {
  type: "module",
});
const requests = new Map();
let serial = 0,
  ready = false,
  current = null,
  paused = false,
  busy = false,
  audio,
  node,
  muted = false,
  toastTimer;
let games = [{ id: "milk-run", name: "Milk Run", demo: true }],
  framePending = false,
  settings = {
    theme: "auto",
    filter: "off",
    blend: false,
    stretch: false,
    touch: true,
  };
let initialResolve;
const initialized = new Promise((resolve) => (initialResolve = resolve));
const held = new Map();
let lastMask = -1;
function tell(message) {
  $("toast").textContent = message;
  $("toast").classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $("toast").classList.remove("visible"), 4500);
}
function call(type, data = {}) {
  return new Promise((resolve, reject) => {
    const id = ++serial;
    const timeout = setTimeout(() => {
      requests.delete(id);
      reject(Error("Emulator did not respond. Reload the page to restart."));
    }, 20000);
    requests.set(id, { resolve, reject, timeout });
    worker.postMessage({ type, id, ...data });
  });
}
function notify(type, data = {}) {
  worker.postMessage({ type, ...data });
}
worker.onmessage = ({ data: m }) => {
  if (m.type === "ready") {
    ready = true;
    $("engine").textContent = "Choccy Milk · WebAssembly";
    initialResolve();
    return;
  }
  if (m.id) {
    const request = requests.get(m.id);
    if (request) {
      clearTimeout(request.timeout);
      requests.delete(m.id);
      if (m.type === "error") request.reject(Error(m.message));
      else request.resolve(m.result);
    }
    return;
  }
  if (m.type === "error") {
    tell(m.message);
    $("engine").textContent = "Emulator unavailable";
    return;
  }
  if (m.type === "frame") {
    if (current) {
      const rgb = new Uint32Array(m.w * m.h);
      for (let i = 0; i < rgb.length; i++)
        rgb[i] =
          (m.rgba[4 * i] << 16) | (m.rgba[4 * i + 1] << 8) | m.rgba[4 * i + 2];
      screen.frame(rgb, m.w, m.h, m.aspect);
      framePending = true;
    }
    notify("frame-drawn");
  }
  if (m.type === "audio" && node && !paused && !muted && !document.hidden)
    node.port.postMessage({ pcm: m.pcm, rate: m.rate }, [m.pcm.buffer]);
  if (m.type === "stats")
    $("stats").textContent = `${m.fps.toFixed(1)} / ${m.target.toFixed(1)} FPS`;
};
worker.onerror = (event) => {
  tell("Emulator worker failed: " + event.message);
  $("engine").textContent = "Emulator unavailable";
  for (const r of requests.values()) {
    clearTimeout(r.timeout);
    r.reject(Error(event.message));
  }
  requests.clear();
};
function applySettings() {
  const dark =
    settings.theme === "auto"
      ? SystemTheme.isDark()
      : settings.theme === "dark";
  document.documentElement.dataset.theme =
    settings.theme === "milk" ? "milk" : dark ? "dark" : "light";
  screen.look(
    settings.filter + (settings.blend ? "+blend" : ""),
    settings.stretch,
  );
  $("touch").hidden = !settings.touch;
  framePending = true;
}
async function saveSettings() {
  try {
    await storage("put", "settings", settings);
  } catch {
    tell("Settings could not be saved in this browser.");
  }
}
for (const key of ["theme", "filter", "blend", "stretch"])
  $(key).onchange = () => {
    settings[key] = $(key).type === "checkbox" ? $(key).checked : $(key).value;
    applySettings();
    saveSettings();
  };
$("touch-option").onchange = () => {
  settings.touch = $("touch-option").checked;
  applySettings();
  saveSettings();
};
matchMedia("(prefers-color-scheme: dark)").addEventListener(
  "change",
  applySettings,
);
function syncSettings() {
  for (const key of ["theme", "filter", "blend", "stretch"]) {
    if ($(key).type === "checkbox") $(key).checked = settings[key];
    else $(key).value = settings[key];
  }
  $("touch-option").checked = settings.touch;
  applySettings();
}
const standalone = new URLSearchParams(location.search).get("app") === "choccy";
const menu = desktopMenu({ library: () => games, play: launch, standalone });
drawDockIcons();
async function setupAudio() {
  if (!audio) {
    audio = new AudioContext({ latencyHint: "interactive" });
    await audio.resume();
    await audio.audioWorklet.addModule("audio-worklet.js");
    node = new AudioWorkletNode(audio, "choccy-audio", {
      outputChannelCount: [2],
    });
    node.connect(audio.destination);
  } else await audio.resume();
}
async function launch(game) {
  if (busy) return;
  busy = true;
  try {
    // Start audio within the click gesture, before awaiting ROM/network reads.
    try {
      await setupAudio();
    } catch (error) {
      tell("Audio unavailable: " + error.message);
    }
    if (!ready) {
      $("engine").textContent = "Loading WebAssembly…";
      await Promise.race([
        initialized,
        new Promise((_, reject) =>
          setTimeout(
            () => reject(Error("WebAssembly startup timed out.")),
            20000,
          ),
        ),
      ]);
    }
    let bytes = game.bytes;
    if (game.demo) {
      const response = await fetch("milk-run.gb");
      if (!response.ok) throw Error("Demo ROM unavailable");
      bytes = new Uint8Array(await response.arrayBuffer());
    }
    const id = await romID(bytes);
    let sram;
    try {
      sram = await storage("get", "sram:" + id);
    } catch {
      /* A game can run without storage. */
    }
    current = { ...game, hash: id };
    paused = false;
    $("pause").textContent = "Pause";
    $("home").hidden = true;
    $("player").hidden = false;
    $("game-title").textContent = game.name;
    screen.clear();
    screen.starting();
    framePending = true;
    await call("load", { rom: bytes, sram });
    clearInputs();
    canvas.focus();
    if (game.demo)
      tell(
        "Milk Run: move the white square to the outlined square. Collect as many as you can.",
      );
  } catch (error) {
    current = null;
    $("home").hidden = false;
    $("player").hidden = true;
    tell(error.message);
  } finally {
    busy = false;
  }
}
async function storeSRAM() {
  if (!current) return;
  const hash = current.hash;
  const bytes = await call("sram");
  if (bytes?.length) await storage("put", "sram:" + hash, bytes);
}
async function home() {
  if (!current || busy) return;
  busy = true;
  try {
    await storeSRAM();
  } catch {
    tell(
      "Battery save could not be stored. Export a state before closing this tab.",
    );
  } finally {
    await call("stop").catch((e) => tell(e.message));
    current = null;
    clearInputs();
    node?.port.postMessage({ clear: true });
    screen.clear();
    $("home").hidden = false;
    $("player").hidden = true;
    busy = false;
    menu.render();
  }
}
async function pause(value = !paused) {
  if (!current) return;
  paused = value;
  clearInputs();
  node?.port.postMessage({ clear: true });
  await call("pause", { paused: value });
  $("pause").textContent = value ? "Resume" : "Pause";
  if (!value) await audio?.resume();
}
function clearInputs() {
  held.clear();
  lastMask = -1;
  flushInputs();
  document.querySelectorAll(".held").forEach((b) => b.classList.remove("held"));
}
function flushInputs() {
  let mask = 0;
  for (const value of held.values()) mask |= value;
  if (paused || !current) mask = 0;
  if (mask !== lastMask) {
    lastMask = mask;
    notify("input", { mask });
  }
}
function keyMask(code) {
  const button = GameScreen.key(code);
  return (
    {
      B: 1,
      A: 256,
      Y: 2,
      X: 512,
      MINUS: 4,
      PLUS: 8,
      UP: 16,
      DOWN: 32,
      LEFT: 64,
      RIGHT: 128,
      L: 1024,
      R: 2048,
      ZL: 4096,
      ZR: 8192,
    }[button] ?? 0
  );
}
window.addEventListener("keydown", async (e) => {
  if (
    document.querySelector("dialog[open]") ||
    e.target.matches("input,select,textarea")
  )
    return;
  if (current) {
    if (e.code === "Escape") {
      e.preventDefault();
      if (!e.repeat) await pause().catch((x) => tell(x.message));
      return;
    }
    const mask = keyMask(e.code);
    if (mask) {
      e.preventDefault();
      held.set("key:" + e.code, mask);
      flushInputs();
    }
  } else if (!busy && !e.repeat) {
    const directions = {
      ArrowLeft: [-1, 0],
      ArrowRight: [1, 0],
      ArrowUp: [0, -1],
      ArrowDown: [0, 1],
    };
    if (directions[e.code]) menu.move(...directions[e.code]);
    else if (
      e.code === "KeyX" ||
      (e.code === "Enter" && !e.target.closest("button,a"))
    )
      menu.open();
    else if (e.code === "Escape" || e.code === "KeyZ") menu.back();
    else if (e.code === "KeyQ") menu.changePage(-1);
    else if (e.code === "KeyW") menu.changePage(1);
    else if (e.code === "F5" || e.key === "-") $("refresh-button").click();
    else if (e.key === "+") $("gamepad-button").click();
    else return;
    e.preventDefault();
  }
});
window.addEventListener("keyup", (e) => {
  held.delete("key:" + e.code);
  flushInputs();
});
window.addEventListener("blur", clearInputs);
document.addEventListener("visibilitychange", () => {
  if (document.hidden && current) {
    pause(true).catch((e) => tell(e.message));
    storeSRAM().catch(() => {});
  }
});
for (const button of document.querySelectorAll("[data-pad]")) {
  button.onpointerdown = (e) => {
    e.preventDefault();
    button.setPointerCapture(e.pointerId);
    held.set("touch:" + e.pointerId, 1 << Number(button.dataset.pad));
    button.classList.add("held");
    flushInputs();
  };
  const release = (e) => {
    held.delete("touch:" + e.pointerId);
    button.classList.remove("held");
    flushInputs();
  };
  button.onpointerup = release;
  button.onpointercancel = release;
  button.onlostpointercapture = release;
}
let lastPadCount = -1,
  lastMenuMask = 0;
let lastPadNav = 0,
  lastPadAction = false;
function draw(now) {
  const pads = navigator.getGamepads?.() ?? [];
  let mask = 0,
    navX = 0,
    navY = 0,
    action = false;
  for (const pad of pads) {
    if (!pad || pad.mapping !== "standard") continue;
    const bits = [0, 8, 1, 9, 10, 11, 12, 13, 2, 3, 14, 15, 4, 5, 6, 7];
    pad.buttons.forEach((b, i) => {
      if (b.pressed && i < bits.length) mask |= 1 << bits[i];
    });
    if (pad.axes[0] < -0.35) mask |= 64;
    if (pad.axes[0] > 0.35) mask |= 128;
    if (pad.axes[1] < -0.35) mask |= 16;
    if (pad.axes[1] > 0.35) mask |= 32;
    navX = (mask & 128 ? 1 : 0) - (mask & 64 ? 1 : 0);
    navY = (mask & 32 ? 1 : 0) - (mask & 16 ? 1 : 0);
    action = pad.buttons[0]?.pressed || false;
  }
  held.set("gamepads", mask);
  flushInputs();
  const gamePadCount = Array.from(pads).filter(
    (pad) => pad?.mapping === "standard",
  ).length;
  if (gamePadCount !== lastPadCount) {
    lastPadCount = gamePadCount;
    $("pad-status").classList.toggle("active", gamePadCount > 0);
    $("pad-label").textContent = gamePadCount
      ? `${gamePadCount} GamePad${gamePadCount === 1 ? "" : "s"}`
      : "No GamePad";
    $("pad-detail").textContent = gamePadCount
      ? "Controller connected."
      : "No standard controller detected.";
  }
  if (!current && !busy && !document.querySelector("dialog[open]")) {
    if ((navX || navY) && now - lastPadNav > 180) {
      lastPadNav = now;
      menu.move(navX, navY);
    }
    if (action && !lastPadAction) menu.open();
    if (mask & 256 && !(lastMenuMask & 256)) menu.back();
    if (mask & 1024 && !(lastMenuMask & 1024)) menu.changePage(-1);
    if (mask & 2048 && !(lastMenuMask & 2048)) menu.changePage(1);
  }
  lastMenuMask = mask;
  lastPadAction = action;
  if (
    current &&
    (framePending || screen.announcing() || screen.hintShowing())
  ) {
    const rect = canvas.getBoundingClientRect();
    const ratio = Math.min(devicePixelRatio, 2),
      w = Math.round(rect.width * ratio),
      h = Math.round(rect.height * ratio);
    if (w && h) {
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
      }
      screen.paint(ctx, w, h, null, `${18 * ratio}px system-ui`);
    }
    framePending = false;
  }
  requestAnimationFrame(draw);
}
new ResizeObserver(() => (framePending = true)).observe(canvas);
requestAnimationFrame(draw);
async function importROMs(files) {
  let imported = false;
  for (const file of files) {
    try {
      if (!/\.(gb|gbc)$/i.test(file.name))
        throw Error("This preview supports .gb and .gbc files.");
      if (file.size > 8 * 1024 * 1024 || file.size < 0x150)
        throw Error("Invalid Game Boy ROM size.");
      const bytes = new Uint8Array(await file.arrayBuffer());
      const id = await romID(bytes);
      const game = { id, bytes, name: file.name.replace(/\.(gb|gbc)$/i, "") };
      await storage("put", "game:" + id, { kind: "game", ...game });
      if (!games.some((g) => g.id === id)) games.push(game);
      imported = true;
      tell(`${game.name} added to your library.`);
    } catch (e) {
      tell(e.message);
    }
  }
  if (imported) menu.showLibrary();
}
$("open-rom").onclick = () => $("rom-file").click();
$("rom-file").onchange = async (e) => {
  await importROMs(e.target.files);
  e.target.value = "";
};
window.addEventListener("dragover", (e) => {
  e.preventDefault();
  document.body.classList.add("dragging");
});
window.addEventListener("dragleave", (e) => {
  if (!e.relatedTarget) document.body.classList.remove("dragging");
});
window.addEventListener("drop", (e) => {
  e.preventDefault();
  document.body.classList.remove("dragging");
  importROMs(e.dataTransfer.files);
});
function action(id, fn) {
  $(id).onclick = () =>
    Promise.resolve()
      .then(fn)
      .catch((e) => tell(e.message));
}
action("home-button", home);
action("pause", () => pause());
action("reset", () => call("reset"));
action("save", async () => {
  if (!current) return;
  const game = current;
  const bytes = await call("save");
  await storage("put", "state:" + game.hash, bytes);
  screen.announce("State saved", "Stored in this browser");
  framePending = true;
});
action("load", async () => {
  if (!current) return;
  const bytes = await storage("get", "state:" + current.hash);
  if (!bytes) throw Error("No saved state for this game yet.");
  await call("restore", { bytes });
  node?.port.postMessage({ clear: true });
  tell("State loaded.");
});
function download(bytes, name, type) {
  const url = URL.createObjectURL(new Blob([bytes], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}
action("export", async () => {
  if (!current) return;
  const game = current,
    bytes = await call("save");
  const text = JSON.stringify({
    format: "wiiuu-web-state-1",
    core: "gambatte-77f2567",
    game: game.hash,
    data: Array.from(bytes),
  });
  download(text, game.name + ".wiiuu-state.json", "application/json");
});
$("import").onclick = () => $("state-file").click();
$("state-file").onchange = async (e) => {
  try {
    const file = e.target.files[0];
    if (!file || !current) return;
    if (file.size > 16 * 1024 * 1024) throw Error("State file is too large.");
    const state = JSON.parse(await file.text());
    if (
      state.format !== "wiiuu-web-state-1" ||
      state.core !== "gambatte-77f2567" ||
      state.game !== current.hash ||
      !Array.isArray(state.data) ||
      !state.data.every((n) => Number.isInteger(n) && n >= 0 && n <= 255)
    )
      throw Error("This state belongs to another game or core.");
    await call("restore", { bytes: Uint8Array.from(state.data) });
    node?.port.postMessage({ clear: true });
    tell("State imported.");
  } catch (error) {
    tell(error.message);
  } finally {
    e.target.value = "";
  }
};
action("screenshot", async () => {
  if (!current) return;
  const name = current.name;
  canvas.toBlob((blob) => blob && download(blob, name + ".png", "image/png"));
});
$("sound").onclick = async () => {
  try {
    muted = !muted;
    if (!muted) await setupAudio();
    node?.port.postMessage({ clear: true });
    $("sound").textContent = muted ? "Sound off" : "Sound on";
  } catch (e) {
    tell(e.message);
  }
};
action("fullscreen", () =>
  document.fullscreenElement
    ? document.exitFullscreen()
    : $("player").requestFullscreen(),
);
$("settings-button").onclick = $("player-settings").onclick = () => {
  clearInputs();
  $("settings").showModal();
};
for (const tab of document.querySelectorAll("[data-settings]")) {
  tab.onclick = () => {
    for (const panel of document.querySelectorAll("[data-panel]"))
      panel.hidden = panel.dataset.panel !== tab.dataset.settings;
    for (const button of document.querySelectorAll("[data-settings]"))
      button.setAttribute("aria-pressed", String(button === tab));
    $("settings-crumb").textContent = `› ${tab.textContent}`;
  };
}
document.querySelector("[data-settings]").click();
$("gamepad-button").onclick = $("pad-status").onclick = () =>
  $("pad-dialog").showModal();
$("power-button").onclick = () => $("power-dialog").showModal();
$("about-button").onclick = () => {
  $("power-dialog").close();
  $("about").showModal();
};
action("menu-fullscreen", async () => {
  $("power-dialog").close();
  if (document.fullscreenElement) await document.exitFullscreen();
  else await document.documentElement.requestFullscreen();
});
action("refresh-button", async () => {
  const records = await storage("getAll");
  games = [games[0], ...records.filter((record) => record?.kind === "game")];
  menu.render();
  tell("Library refreshed.");
});
setInterval(() => {
  if (current) storeSRAM().catch(() => {});
}, 10000);
function clock() {
  $("clock").textContent = new Date().toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
  $("date").textContent = new Date().toLocaleDateString([], {
    weekday: "short",
    day: "numeric",
    month: "short",
  });
}
clock();
setInterval(clock, 1000);
if (standalone) {
  document.title = "Choccy Milk · Web preview";
  $("switch-app").textContent = "WII-UU menu";
  $("switch-app").href = "./";
  settings.theme = "milk";
}
try {
  const saved = await storage("get", "settings");
  if (saved) settings = { ...settings, ...saved };
  const records = await storage("getAll");
  games.push(...records.filter((r) => r?.kind === "game"));
} catch {
  tell("Browser storage is unavailable. The built-in demo still works.");
}
syncSettings();
menu.render();
fetch("version.json")
  .then((r) => r.json())
  .then((v) => ($("version").textContent = `WII-UU ${v.version} · Web preview`))
  .catch(() => {});
// Local integration API: no HTTP server or ROM uploading required.
window.wiiuuWeb = Object.freeze({
  getStatus: () => ({ ready, game: current?.name ?? null, paused }),
  pause: () => pause(true),
  resume: () => pause(false),
  stop: home,
});
