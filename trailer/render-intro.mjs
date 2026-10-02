// Renders intro3d.html (three.js) frame by frame and encodes it with its soundtrack:
//   node trailer/render-intro.mjs <soundtrack.wav> <out.mp4> [fps] [from] [to]
//   STILLS=1.5,4,7 node trailer/render-intro.mjs <soundtrack.wav> <prefix>    (single frames as PNGs)
// Serves the repository over http on localhost (ES modules don't load from file://), and runs
// Chromium's software WebGL, so it needs no GPU. Needs Playwright and ffmpeg.
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const [wav, out, fpsArg, fromArg, toArg] = process.argv.slice(2);
const fps = Number(fpsArg || 30);
let playwright;
for (const p of [process.env.PLAYWRIGHT, "playwright", "/opt/node22/lib/node_modules/playwright"].filter(Boolean)) {
  try { playwright = require(p); break; } catch (e) { /* next */ }
}
if (!playwright) { console.error("Playwright not found: npm i -g playwright (or set PLAYWRIGHT)"); process.exit(1); }

const types = { ".html": "text/html", ".js": "text/javascript", ".json": "application/json", ".png": "image/png", ".woff2": "font/woff2" };
const server = http.createServer((req, res) => {
  const file = path.join(root, decodeURIComponent(new URL(req.url, "http://x").pathname));
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "Content-Type": types[path.extname(file)] || "application/octet-stream" });
  fs.createReadStream(file).pipe(res);
});
await new Promise((ok) => server.listen(0, "127.0.0.1", ok));
const port = server.address().port;

const browser = await playwright.chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"] });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
page.on("pageerror", (e) => console.error("page error:", e.message));
await page.goto(`http://127.0.0.1:${port}/trailer/intro3d.html?render=1`);
await page.waitForFunction(() => window.READY === true, null, { timeout: 120000 });
const duration = await page.evaluate(() => window.DURATION);
if (process.env.STILLS) {
  // STILLS="1.5,4,7" saves those moments as PNGs next to out (for checking the picture)
  for (const t of process.env.STILLS.split(",").map(Number)) {
    await page.evaluate((x) => window.seek(x), t);
    await page.screenshot({ path: `${out}-${t.toFixed(2)}.png` });
  }
  await browser.close();
  server.close();
  process.exit(0);
}
const from = Number(fromArg || 0), to = Math.min(Number(toArg || duration), duration);
const f0 = Math.round(from * fps), f1 = Math.round(to * fps);

const ffmpeg = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(fps), "-c:v", "png", "-i", "-",
  "-ss", String(from), "-t", String(to - from), "-i", wav, "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p",
  "-c:a", "aac", "-b:a", "256k", "-shortest", "-movflags", "+faststart", out], { stdio: ["pipe", "inherit", "inherit"] });
const done = new Promise((ok, bad) => ffmpeg.on("close", (c) => (c === 0 ? ok() : bad(new Error("ffmpeg exited " + c)))));

const started = Date.now();
for (let i = f0; i < f1; i++) {
  await page.evaluate((t) => window.seek(t), i / fps);
  const png = await page.screenshot({ type: "png" });
  if (!ffmpeg.stdin.write(png)) await new Promise((r) => ffmpeg.stdin.once("drain", r));
  if ((i - f0) % fps === 0) process.stdout.write(`\r  frame ${i - f0}/${f1 - f0} (${Math.round((Date.now() - started) / 1000)} s)`);
}
ffmpeg.stdin.end();
await done;
await browser.close();
server.close();
console.log(`\n  wrote ${out} (${f1 - f0} frames)`);
