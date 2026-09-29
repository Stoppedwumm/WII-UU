// Renders trailer.html frame by frame (window.seek) and encodes it with the soundtrack:
//   node trailer/render.mjs <soundtrack.wav> <out.mp4> [fps]
// Needs Playwright (npm i -g playwright, or PLAYWRIGHT=/path/to/playwright) and ffmpeg.
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const [wav, out, fpsArg] = process.argv.slice(2);
const fps = Number(fpsArg || 30);
let playwright;
for (const p of [process.env.PLAYWRIGHT, "playwright", "/opt/node22/lib/node_modules/playwright"].filter(Boolean)) {
  try { playwright = require(p); break; } catch (e) { /* next */ }
}
if (!playwright) { console.error("Playwright not found: npm i -g playwright (or set PLAYWRIGHT)"); process.exit(1); }

const browser = await playwright.chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
await page.goto(pathToFileURL(path.join(here, "trailer.html")).href + "?render=1");
await page.evaluate(() => document.fonts.ready);
await page.waitForFunction(() => [...document.images].every((i) => i.complete && i.naturalWidth > 0));
const duration = await page.evaluate(() => window.DURATION);
const frames = Math.round(duration * fps);

const ffmpeg = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(fps), "-c:v", "mjpeg", "-i", "-",
  "-i", wav, "-c:v", "libx264", "-preset", "slow", "-crf", "19", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
  "-shortest", "-movflags", "+faststart", out], { stdio: ["pipe", "inherit", "inherit"] });
const done = new Promise((ok, bad) => ffmpeg.on("close", (c) => (c === 0 ? ok() : bad(new Error("ffmpeg exited " + c)))));

const started = Date.now();
for (let i = 0; i < frames; i++) {
  await page.evaluate((t) => window.seek(t), i / fps);
  const jpg = await page.screenshot({ type: "jpeg", quality: 92 });
  if (!ffmpeg.stdin.write(jpg)) await new Promise((r) => ffmpeg.stdin.once("drain", r));
  if (i % (fps * 5) === 0) process.stdout.write(`\r  frame ${i}/${frames} (${Math.round((Date.now() - started) / 1000)} s)`);
}
ffmpeg.stdin.end();
await done;
await browser.close();
console.log(`\n  wrote ${out} (${frames} frames, ${duration} s)`);
