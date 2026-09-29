// Records the live demo for the second trailer: the real WII-UU on a virtual 1080p TV (Xvfb +
// ffmpeg x11grab) while a real browser, sized like a phone, uses its GamePad page. Both are
// recorded at the same time; the phone as timestamped frames, so the two line up exactly.
//
//   node trailer/demo.mjs <wiiuu.jar> <home dir> <out dir>
// Writes <out>/tv.mp4, <out>/phone.mp4 and <out>/sync.json (phone start offset in seconds).
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";

const require = createRequire(import.meta.url);
const [jar, home, out] = process.argv.slice(2);
let playwright;
for (const p of [process.env.PLAYWRIGHT, "playwright", "/opt/node22/lib/node_modules/playwright"].filter(Boolean)) {
  try { playwright = require(p); break; } catch (e) { /* next */ }
}
const DISPLAY = ":83", PORT = 18093, LENGTH = 38;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
fs.mkdirSync(path.join(out, "phone"), { recursive: true });

const xvfb = spawn("Xvfb", [DISPLAY, "-screen", "0", "1920x1080x24", "-nolisten", "tcp"], { stdio: "ignore" });
await sleep(1000);
// the TV: record from before WII-UU appears, so the boot animation is in the clip
const tv = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "x11grab", "-framerate", "30", "-video_size", "1920x1080",
  "-draw_mouse", "0", "-i", DISPLAY, "-t", String(LENGTH), "-c:v", "libx264", "-preset", "veryfast", "-crf", "16",
  "-pix_fmt", "yuv420p", path.join(out, "tv.mp4")], { stdio: ["ignore", "inherit", "inherit"] });
const tvDone = new Promise((ok) => tv.on("close", ok));
const tvStart = Date.now();
const app = spawn("java", ["-jar", jar, "--home", home, "--port", String(PORT), "--fullscreen"],
  { env: { ...process.env, DISPLAY }, stdio: ["ignore", "ignore", "ignore"] });
const at = (s) => sleep(Math.max(0, tvStart + s * 1000 - Date.now()));

// the phone
await at(4.6);
const browser = await playwright.chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 844, height: 390 }, deviceScaleFactor: 2, hasTouch: true });
const page = await ctx.newPage();
await page.goto(`http://localhost:${PORT}/?code=4821`);
// every repaint of the phone page, with its timestamp (Chrome's screencast)
const cdp = await ctx.newCDPSession(page);
const frames = [];
let phoneStart = 0;
cdp.on("Page.screencastFrame", async ({ data, metadata, sessionId }) => {
  const t = metadata.timestamp;                 // seconds (wall clock)
  if (!phoneStart) phoneStart = t * 1000;
  const name = `f${String(frames.length).padStart(5, "0")}.jpg`;
  fs.writeFileSync(path.join(out, "phone", name), Buffer.from(data, "base64"));
  frames.push([name, t - phoneStart / 1000]);
  cdp.send("Page.screencastFrameAck", { sessionId }).catch(() => {});
});
await cdp.send("Page.startScreencast", { format: "jpeg", quality: 90, maxWidth: 1688, maxHeight: 780, everyNthFrame: 1 });

async function press(sel, hold = 130) {
  const box = await page.locator(sel).first().boundingBox();
  if (!box) return;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await sleep(hold);
  await page.mouse.up();
}
const tap = (sel) => page.locator(sel).first().click();

await at(8.0); await press('[data-dir="RIGHT"]');
await at(8.7); await press('[data-dir="RIGHT"]');
await at(9.4); await press('[data-dir="RIGHT"]');
await at(10.2); await press('[data-dir="DOWN"]');
await at(11.2); await press('[data-b="A"]');            // opens that console's games on the TV
await at(13.8); await press('[data-b="B"]');            // back
await at(15.2); await tap('.chip:has-text("SNES")');   // the phone's own library
await at(16.2); await tap('.game:has-text("Star Runner")');   // starts on the TV
await at(20.0); await press('[data-b="HOME"]');
await at(21.2); await tap("#sheetClose");
await at(23.5); await tap("#tvBtn");                    // the TV, mirrored on the phone
await at(27.0); await press('[data-dir="RIGHT"]');
await at(27.7); await press('[data-dir="RIGHT"]');
await at(28.4); await press('[data-dir="DOWN"]');
await at(29.4); await press('[data-dir="RIGHT"]');
await at(LENGTH - 0.5);
await cdp.send("Page.stopScreencast");
await browser.close();

// phone frames -> video with each frame's real duration
const list = frames.map(([n, t], i) => `file '${n}'\nduration ${((frames[i + 1]?.[1] ?? t + 0.05) - t).toFixed(4)}`).join("\n");
fs.writeFileSync(path.join(out, "phone", "list.txt"), list + `\nfile '${frames.at(-1)[0]}'\n`);
await new Promise((ok) => spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", path.join(out, "phone", "list.txt"),
  "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-crf", "16", path.join(out, "phone.mp4")], { stdio: "inherit" }).on("close", ok));
await tvDone;
fs.writeFileSync(path.join(out, "sync.json"), JSON.stringify({ phoneOffset: (phoneStart - tvStart) / 1000, phoneFps: frames.length / ((Date.now() - phoneStart) / 1000) }));
app.kill(); xvfb.kill();
console.log(`  demo: ${frames.length} phone frames, phone starts at ${((phoneStart - tvStart) / 1000).toFixed(2)} s`);
