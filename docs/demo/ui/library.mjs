/** Browser port of every public type in WII-UU UI Library 1.0.0.
 * Canvas2D replaces Graphics2D; ImageData replaces BufferedImage.
 * Pixel filters are generated from the desktop source, with parity tests.
 */
import { scale2x, scale3x, xbr2, crt } from "./filters.mjs";
const names =
  "A B X Y L R ZL ZR PLUS MINUS HOME UP DOWN LEFT RIGHT L3 R3 LS_UP LS_DOWN LS_LEFT LS_RIGHT RS_UP RS_DOWN RS_LEFT RS_RIGHT BUZZ_RED BUZZ_BLUE BUZZ_ORANGE BUZZ_GREEN BUZZ_YELLOW".split(
    " ",
  );
export const PadButton = Object.freeze(
  Object.assign(Object.fromEntries(names.map((n) => [n, n])), {
    parse: (value) => {
      const n = String(value ?? "")
        .trim()
        .toUpperCase();
      return names.includes(n) ? n : null;
    },
    isBuzz: (value) => names.includes(value) && value.startsWith("BUZZ_"),
    isDirection: (value) =>
      [
        "UP",
        "DOWN",
        "LEFT",
        "RIGHT",
        "LS_UP",
        "LS_DOWN",
        "LS_LEFT",
        "LS_RIGHT",
      ].includes(value),
  }),
);
export const SystemTheme = Object.freeze({
  isDark: () =>
    globalThis.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false,
});
const keys = {
  ArrowUp: "UP",
  ArrowDown: "DOWN",
  ArrowLeft: "LEFT",
  ArrowRight: "RIGHT",
  KeyX: "A",
  KeyZ: "B",
  KeyS: "X",
  KeyA: "Y",
  KeyQ: "L",
  KeyW: "R",
  KeyE: "ZL",
  KeyR: "ZR",
  Enter: "PLUS",
  Space: "MINUS",
  ShiftLeft: "MINUS",
  ShiftRight: "MINUS",
};
export class GameScreen {
  static HINT = "#D7A27A";
  static key(code) {
    return keys[code] ?? null;
  }
  constructor() {
    this.surface = document.createElement("canvas");
    this.context = this.surface.getContext("2d");
    this.look("off", false);
    this.clear();
  }
  look(filter = "off", stretch = false) {
    this.blend = filter.endsWith("+blend");
    this.filter = this.blend ? filter.slice(0, -6) : filter;
    this.stretch = stretch;
    this.before = null;
  }
  frame(rgb, w, h, aspect = 0) {
    if (
      !Number.isInteger(w) ||
      !Number.isInteger(h) ||
      w < 1 ||
      h < 1 ||
      w * h > 4194304 ||
      rgb.length < w * h
    )
      throw Error("Invalid frame dimensions");
    if (this.blend) {
      const was = this.before;
      this.before = Uint32Array.from(
        rgb.subarray ? rgb.subarray(0, w * h) : rgb.slice(0, w * h),
      );
      if (was?.length === w * h)
        rgb = Uint32Array.from(
          this.before,
          (v, i) =>
            ((v & 0xfefefe) >>> 1) +
            ((was[i] & 0xfefefe) >>> 1) +
            (v & was[i] & 0x010101),
        );
    }
    let n =
      { scale3x: 3, scale2x: 2, xbr2: 2, xbr4: 4, crt: 4 }[this.filter] ?? 1;
    if (n > 1 && w * h > 200000) n = this.filter === "crt" ? 2 : 1;
    const out = new Uint32Array(w * h * n * n);
    if (this.filter === "crt") crt(rgb, w, h, n, out);
    else if (n === 4) {
      const mid = new Uint32Array(w * h * 4);
      xbr2(rgb, w, h, mid);
      xbr2(mid, w * 2, h * 2, out);
    } else if (n === 3) scale3x(rgb, w, h, out);
    else if (n === 2) (this.filter === "xbr2" ? xbr2 : scale2x)(rgb, w, h, out);
    else out.set(rgb.subarray ? rgb.subarray(0, w * h) : rgb.slice(0, w * h));
    if (this.surface.width !== w * n || this.surface.height !== h * n) {
      this.surface.width = w * n;
      this.surface.height = h * n;
    }
    const image = this.context.createImageData(w * n, h * n);
    for (let i = 0; i < out.length; i++) {
      let c = out[i];
      image.data[i * 4] = (c >>> 16) & 255;
      image.data[i * 4 + 1] = (c >>> 8) & 255;
      image.data[i * 4 + 2] = c & 255;
      image.data[i * 4 + 3] = 255;
    }
    this.context.putImageData(image, 0, 0);
    this.hasPicture = true;
    const a = aspect > 0 ? aspect : w / h;
    this.aspect = this.stretch && a < 1.7 ? 16 / 9 : a;
  }
  starting(message = "Starting Choccy Milk…") {
    this.startup = message;
  }
  showing() {
    return this.hasPicture || this.startup !== null;
  }
  picture() {
    return this.hasPicture ? this.surface : null;
  }
  clear() {
    this.hasPicture = false;
    this.startup = null;
    this.shownAt = 0;
    this.before = null;
    this.note = null;
  }
  pictureBounds(w, h) {
    if (!this.hasPicture || w <= 0 || h <= 0)
      return { x: 0, y: 0, width: 0, height: 0 };
    let dh = h,
      dw = Math.round(h * this.aspect);
    if (dw > w) {
      dw = w;
      dh = Math.round(w / this.aspect);
    }
    return { x: (w - dw) / 2, y: (h - dh) / 2, width: dw, height: dh };
  }
  announce(title, text, trophy = false, millis = 4000) {
    this.note = {
      title,
      text,
      trophy,
      from: performance.now(),
      until: performance.now() + millis,
    };
  }
  announcing() {
    return !!this.note && performance.now() < this.note.until;
  }
  hintShowing() {
    return !this.shownAt || performance.now() - this.shownAt < 4000;
  }
  paint(g, w, h, hint = null, font = "18px system-ui") {
    g.save();
    g.fillStyle = "#000";
    g.fillRect(0, 0, w, h);
    g.font = font;
    g.textAlign = "center";
    g.textBaseline = "middle";
    if (!this.hasPicture) {
      g.fillStyle = GameScreen.HINT;
      g.fillText(this.startup ?? "Starting Choccy Milk…", w / 2, h / 2);
    } else {
      const b = this.pictureBounds(w, h);
      g.imageSmoothingEnabled = this.filter !== "off";
      g.drawImage(this.surface, b.x, b.y, b.width, b.height);
      this.shownAt ||= performance.now();
    }
    if (hint && this.hintShowing()) {
      g.globalAlpha = Math.min(
        1,
        (4000 - (performance.now() - this.shownAt)) / 600,
      );
      g.fillStyle = "#000b";
      g.fillRect(10, 10, g.measureText(hint).width + 24, 36);
      g.fillStyle = GameScreen.HINT;
      g.textAlign = "left";
      g.fillText(hint, 22, 28);
    }
    if (this.announcing()) {
      const n = this.note,
        now = performance.now();
      g.globalAlpha = Math.min(1, (now - n.from) / 250, (n.until - now) / 400);
      g.fillStyle = "#140e0ae1";
      g.beginPath();
      g.roundRect(w * 0.1, h - 100, w * 0.8, 80, 20);
      g.fill();
      g.strokeStyle = GameScreen.HINT;
      g.stroke();
      g.textAlign = "center";
      g.fillStyle = "#f3e6da";
      g.fillText(n.title, w / 2, h - 72);
      g.fillStyle = GameScreen.HINT;
      if (n.text) g.fillText(n.text, w / 2, h - 43);
      if (n.trophy) {
        g.fillStyle = "#f2c14e";
        g.beginPath();
        g.arc(w * 0.14, h - 60, 16, 0, Math.PI * 2);
        g.fill();
        g.fillStyle = "#8a5a12";
        g.fillRect(w * 0.14 - 7, h - 68, 14, 10);
        g.fillRect(w * 0.14 - 2, h - 58, 4, 8);
        g.fillRect(w * 0.14 - 6, h - 50, 12, 3);
      }
    }
    g.restore();
  }
}
