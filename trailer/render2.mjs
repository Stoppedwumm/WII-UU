// Renders trailer2.html (the narrated demo) frame by frame and mixes its sound:
//   node trailer/render2.mjs <config.json> <music.wav> <voice dir> <out.mp4> [fps]
// config.json: { phoneOffset, tvFirst, voiceLen: { v1: seconds, ... } }
// The page is served over http from the repository root, so it can drive trailer.html in an iframe.
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const [cfgFile, music, voiceDir, out, fpsArg] = process.argv.slice(2);
const fps = Number(fpsArg || 30);
const cfg = JSON.parse(fs.readFileSync(cfgFile, "utf8"));
let playwright;
for (const p of [process.env.PLAYWRIGHT, "playwright", "/opt/node22/lib/node_modules/playwright"].filter(Boolean)) {
  try { playwright = require(p); break; } catch (e) { /* next */ }
}

const TYPES = { ".html": "text/html", ".js": "text/javascript", ".png": "image/png", ".jpg": "image/jpeg", ".woff2": "font/woff2" };
const server = http.createServer((req, res) => {
  const file = path.join(root, decodeURIComponent(new URL(req.url, "http://x").pathname));
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(file)] || "application/octet-stream" });
  fs.createReadStream(file).pipe(res);
}).listen(0, "127.0.0.1");
await new Promise((r) => server.once("listening", r));
const port = server.address().port;

const browser = await playwright.chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
page.on("pageerror", (e) => console.error("page error:", e.message));
await page.goto(`http://127.0.0.1:${port}/trailer/trailer2.html`);
await page.waitForFunction(() => document.getElementById("t1").contentWindow?.seek);
await page.evaluate(() => document.fonts.ready);
await page.evaluate(() => document.getElementById("t1").contentWindow.document.fonts.ready);
await page.evaluate((c) => window.setup(c), cfg);
// trailer 1's own captions would clash with the subtitles
await page.evaluate(() => {
  const s = document.getElementById("t1").contentWindow.document.createElement("style");
  s.textContent = "#s5c1, #s5c2, #s5c3 { display: none !important; }";
  document.getElementById("t1").contentWindow.document.head.appendChild(s);
});
const duration = await page.evaluate(() => window.DURATION);
const voice = await page.evaluate(() => window.VOICE);

// sound: the music bed ducks under the narration, then everything is levelled for the web
const audio = path.join(path.dirname(cfgFile), "audio.wav");   // next to config.json, in the build folder
const inputs = ["-i", music], parts = [], labels = [];
voice.forEach(([id, start], i) => {
  inputs.push("-i", path.join(voiceDir, id + ".wav"));
  const ms = Math.round(start * 1000);
  parts.push(`[${i + 1}]aformat=sample_rates=48000:channel_layouts=stereo,adelay=${ms}|${ms},apad[v${i}]`);
  labels.push(`[v${i}]`);
});
const filter = parts.join(";") + `;${labels.join("")}amix=inputs=${labels.length}:normalize=0:duration=longest,atrim=0:${duration}[vo];`
  + `[vo]asplit[vo1][vo2];[0]aformat=sample_rates=48000:channel_layouts=stereo,volume=0.55[m];`
  + `[m][vo1]sidechaincompress=threshold=0.02:ratio=8:attack=30:release=500[duck];`
  + `[duck][vo2]amix=inputs=2:normalize=0,atrim=0:${duration},loudnorm=I=-16:TP=-1.5:LRA=11[a]`;
await new Promise((ok, bad) => spawn("ffmpeg", ["-y", "-loglevel", "error", ...inputs, "-filter_complex", filter, "-map", "[a]", "-ar", "48000", audio],
  { stdio: "inherit" }).on("close", (c) => (c === 0 ? ok() : bad(new Error("audio mix failed")))));

const ffmpeg = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(fps), "-c:v", "mjpeg", "-i", "-",
  "-i", audio, "-c:v", "libx264", "-preset", "slow", "-crf", "19", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
  "-shortest", "-movflags", "+faststart", out], { stdio: ["pipe", "inherit", "inherit"] });
const done = new Promise((ok, bad) => ffmpeg.on("close", (c) => (c === 0 ? ok() : bad(new Error("ffmpeg exited " + c)))));
const frames = Math.round(duration * fps), started = Date.now();
for (let i = 0; i < frames; i++) {
  await page.evaluate((t) => window.seek(t), i / fps);
  const jpg = await page.screenshot({ type: "jpeg", quality: 92 });
  if (!ffmpeg.stdin.write(jpg)) await new Promise((r) => ffmpeg.stdin.once("drain", r));
  if (i % (fps * 5) === 0) process.stdout.write(`\r  frame ${i}/${frames} (${Math.round((Date.now() - started) / 1000)} s)`);
}
ffmpeg.stdin.end();
await done;
await browser.close();
server.close();
console.log(`\n  wrote ${out} (${frames} frames, ${duration} s)`);
