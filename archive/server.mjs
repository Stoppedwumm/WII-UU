import http from 'node:http';
import { createReadStream } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash, timingSafeEqual, randomUUID } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { fileURLToPath } from 'node:url';

const VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-beta\.(0|[1-9]\d*)(?:\.([1-9]\d*))?)?$/;
const EXTRA = ['CHANGELOG.md', 'ChoccyMilk.jar', 'WII-UU-Cleanup.jar', 'WII-UU-OEM-Creator.jar', 'WII-UU-Reactions.jar'];
class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }
const fail = (status, message) => { throw new HttpError(status, message); };
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const channelOf = version => version.includes('-beta.') ? 'beta' : 'stable';
function compare(a, b) {
  const x = VERSION.exec(a), y = VERSION.exec(b);
  for (let i = 1; i <= 3; i++) { const p = BigInt(x[i]), q = BigInt(y[i]); if (p !== q) return p > q ? 1 : -1; }
  if (x[4] === undefined || y[4] === undefined) return x[4] === y[4] ? 0 : x[4] === undefined ? 1 : -1;
  const p = BigInt(x[4]), q = BigInt(y[4]);
  if (p !== q) return p > q ? 1 : -1;
  if (x[5] === undefined || y[5] === undefined) return x[5] === y[5] ? 0 : x[5] === undefined ? -1 : 1;
  return BigInt(x[5]) === BigInt(y[5]) ? 0 : BigInt(x[5]) > BigInt(y[5]) ? 1 : -1;
}
async function digest(filename) {
  const hash = createHash('sha256'); let size = 0;
  for await (const chunk of createReadStream(filename)) { hash.update(chunk); size += chunk.length; }
  return { sha256: hash.digest('hex'), size };
}
async function readJson(request, max = 65536) {
  let size = 0; const chunks = [];
  for await (const chunk of request) { size += chunk.length; if (size > max) fail(413, 'JSON body too large'); chunks.push(chunk); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { fail(400, 'Invalid JSON'); }
}

/** A single-process, immutable file archive. Publication is an atomic release.json rename. */
export async function createArchive({ dataDir, token, publicUrl, maxUpload = 2 * 1024 ** 3, maxConcurrentUploads = 4 }) {
  if (!Number.isSafeInteger(maxUpload) || maxUpload < 1 || !Number.isSafeInteger(maxConcurrentUploads) || maxConcurrentUploads < 1)
    throw new Error('Upload limits must be positive integers');
  if (!token || token.length < 32) throw new Error('ARCHIVE_TOKEN must be at least 32 characters');
  const origin = new URL(publicUrl);
  if (!['http:', 'https:'].includes(origin.protocol) || origin.pathname !== '/' || origin.search || origin.hash || origin.username || origin.password)
    throw new Error('PUBLIC_URL must be an HTTP(S) origin without a path');
  const publicBase = origin.origin;
  const root = path.resolve(dataDir);
  await fs.mkdir(root, { recursive: true });
  const catalog = new Map();
  for (const entry of await fs.readdir(root, { withFileTypes: true })) {
    if (!entry.isDirectory() || !VERSION.test(entry.name)) continue;
    try { const record = JSON.parse(await fs.readFile(path.join(root, entry.name, 'release.json'), 'utf8')); catalog.set(entry.name, record); }
    catch (e) { if (e.code !== 'ENOENT') throw e; }
  }
  const locks = new Map(); let uploads = 0;
  async function locked(version, action) {
    const previous = locks.get(version) ?? Promise.resolve();
    let unlock; const gate = new Promise(resolve => { unlock = resolve; });
    const queued = previous.then(() => gate); locks.set(version, queued);
    await previous;
    try { return await action(); }
    finally { unlock(); if (locks.get(version) === queued) locks.delete(version); }
  }
  const ordered = channel => [...catalog.entries()].filter(([v]) => !channel || channelOf(v) === channel).sort(([a], [b]) => compare(b, a));
  function json(response, status, value) {
    const body = JSON.stringify(value) + '\n';
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body),
      'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*', 'X-Content-Type-Options': 'nosniff' });
    response.end(body);
  }
  function authorized(request) {
    const supplied = Buffer.from(request.headers.authorization ?? '');
    const expected = Buffer.from(`Bearer ${token}`);
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) fail(401, 'Unauthorized');
  }
  async function serveFile(request, response, version, name) {
    const record = catalog.get(version);
    const asset = record && Object.hasOwn(record.assets, name) ? record.assets[name] : null;
    if (!asset) fail(404, 'Asset not found');
    const filename = path.join(root, version, name);
    const etag = `"${asset.sha256}"`;
    const headers = { 'Content-Type': name.endsWith('.md') ? 'text/plain; charset=utf-8' : 'application/octet-stream',
      'Content-Disposition': `attachment; filename="${name}"`, 'Accept-Ranges': 'bytes', ETag: etag,
      'Cache-Control': 'public, max-age=31536000, immutable', 'Access-Control-Allow-Origin': '*', 'X-Content-Type-Options': 'nosniff' };
    if (request.headers['if-none-match'] === etag) { response.writeHead(304, headers); response.end(); return; }
    let start = 0, end = asset.size - 1, status = 200;
    if (request.headers.range) {
      const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.range);
      if (!range || (!range[1] && !range[2]) || !asset.size) fail(416, 'Invalid byte range');
      if (!range[1]) { const suffix = Number(range[2]); if (!Number.isSafeInteger(suffix) || suffix <= 0) fail(416, 'Invalid byte range'); start = Math.max(0, asset.size - suffix); }
      else { start = Number(range[1]); if (range[2]) end = Math.min(end, Number(range[2])); }
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end || start >= asset.size) {
        response.setHeader('Content-Range', `bytes */${asset.size}`); fail(416, 'Invalid byte range');
      }
      headers['Content-Range'] = `bytes ${start}-${end}/${asset.size}`; status = 206;
    }
    // Open before sending headers so missing files produce a useful error rather than a broken response.
    const file = await fs.open(filename, 'r');
    headers['Content-Length'] = Math.max(0, end - start + 1);
    response.writeHead(status, headers);
    if (request.method === 'HEAD' || asset.size === 0) { await file.close(); response.end(); return; }
    try { await pipeline(file.createReadStream({ start, end }), response); }
    finally { await file.close().catch(() => {}); }
  }
  function normalize(version, input, zipHash) {
    if (!input || typeof input !== 'object' || Array.isArray(input)) fail(400, 'Expected a release manifest');
    if (typeof input.version !== 'string' || input.version.split('+')[0] !== version
        || !new RegExp(`^${version.replaceAll('.', '\\.')}(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$`).test(input.version)) fail(400, 'Manifest version mismatch');
    if (input.releaseVersion !== undefined && input.releaseVersion !== version) fail(400, 'Release identity mismatch');
    if (input.baseVersion !== undefined && input.baseVersion !== version.split('-')[0]) fail(400, 'Base version mismatch');
    if (input.channel !== channelOf(version)) fail(400, 'Manifest channel mismatch');
    if (!/^[0-9a-f]{64}$/i.test(input.sha256 ?? '') || input.sha256.toLowerCase() !== zipHash) fail(400, 'ZIP checksum mismatch');
    const output = { schema: 2, version: input.version, releaseVersion: version, channel: channelOf(version) };
    for (const key of ['baseVersion', 'build', 'attempt', 'dirty', 'commit', 'builtAt', 'branch']) {
      if (input[key] !== undefined) { if (typeof input[key] !== 'string' || input[key].length > 512) fail(400, `Invalid ${key}`); output[key] = input[key]; }
    }
    output.zip = `${publicBase}/releases/${version}/WII-UU-${version}.zip`; output.sha256 = zipHash;
    return output;
  }
  const server = http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://archive.local');
      let parts; try { parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent); } catch { fail(400, 'Invalid path'); }
      const reading = request.method === 'GET' || request.method === 'HEAD';
      if (reading && url.pathname === '/healthz') { json(response, 200, { status: 'ok', releases: catalog.size }); return; }
      if (reading && ['/version.json', '/beta/version.json'].includes(url.pathname)) {
        const channel = url.pathname.startsWith('/beta/') ? 'beta' : 'stable';
        const latest = ordered(channel)[0]; if (!latest) fail(404, `No ${channel} release published`);
        json(response, 200, latest[1].manifest); return;
      }
      if (reading && ['/changelog.md', '/beta/changelog.md'].includes(url.pathname)) {
        const latest = ordered(url.pathname.startsWith('/beta/') ? 'beta' : 'stable')[0];
        if (!latest) fail(404, 'No release published');
        await serveFile(request, response, latest[0], 'CHANGELOG.md'); return;
      }
      if (reading && parts[0] === 'api' && parts[1] === 'releases' && parts.length === 2) {
        const channel = url.searchParams.get('channel');
        if (channel && !['stable', 'beta'].includes(channel)) fail(400, 'Unknown channel');
        const limit = Number(url.searchParams.get('limit') ?? 50), offset = Number(url.searchParams.get('offset') ?? 0);
        if (!Number.isInteger(limit) || limit < 1 || limit > 100 || !Number.isSafeInteger(offset) || offset < 0) fail(400, 'Invalid pagination');
        const list = ordered(channel);
        json(response, 200, { total: list.length, releases: list.slice(offset, offset + limit).map(([version, r]) => ({ version, ...r.manifest, assets: r.assets, publishedAt: r.publishedAt })) }); return;
      }
      if (reading && parts[0] === 'releases' && parts.length === 3 && VERSION.test(parts[1])) {
        if (parts[2] === 'version.json') { const release = catalog.get(parts[1]); if (!release) fail(404, 'Release not found'); json(response, 200, release.manifest); return; }
        await serveFile(request, response, parts[1], parts[2]); return;
      }
      if (reading && url.pathname === '/') {
        const rows = ordered().map(([version, record]) => `<tr><td>${escape(version)}</td><td>${escape(record.manifest.channel)}</td><td>${escape(record.manifest.commit ?? '')}</td><td><a href="${escape(record.manifest.zip)}">Download ZIP</a> · <a href="/releases/${version}/CHANGELOG.md">Notes</a></td></tr>`).join('');
        const html = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>WII-UU Archive</title><style>body{font:16px system-ui;max-width:1000px;margin:50px auto;padding:20px;background:#181d25;color:#eef2f6}a{color:#55caff}td,th{text-align:left;padding:14px;border-bottom:1px solid #36404d}table{width:100%;border-collapse:collapse}</style><h1>WII-UU Archive</h1><p>Stable releases and numbered betas. Releases are immutable.</p><p><a href="/version.json">Stable feed</a> · <a href="/beta/version.json">Beta feed</a> · <a href="/api/releases">API</a></p><table><tr><th>Version</th><th>Channel</th><th>Commit</th><th>Files</th></tr>${rows}</table>${rows ? '' : '<p>No releases published yet.</p>'}</html>`;
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Content-Length': Buffer.byteLength(html), 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' }); response.end(html); return;
      }
      if (parts[0] === 'api' && parts[1] === 'releases' && VERSION.test(parts[2] ?? '') && ['PUT', 'POST'].includes(request.method)) {
        authorized(request);
        const version = parts[2], directory = path.join(root, version), zipName = `WII-UU-${version}.zip`;
        if (request.method === 'PUT' && parts.length === 4) {
          const name = parts[3]; if (![zipName, ...EXTRA].includes(name)) fail(400, 'Unsupported asset name');
          const length = Number(request.headers['content-length']);
          if (!Number.isSafeInteger(length) || length < 0) fail(411, 'Content-Length required');
          if (length > maxUpload) fail(413, 'Asset exceeds upload limit');
          if (uploads >= maxConcurrentUploads) fail(503, 'Too many uploads; retry later');
          uploads++;
          try {
            await locked(version, async () => {
              await fs.mkdir(directory, { recursive: true });
              const temporary = path.join(directory, `.upload-${randomUUID()}`);
              const hash = createHash('sha256'); let received = 0;
              const meter = new Transform({ transform(chunk, encoding, done) {
                received += chunk.length; if (received > length || received > maxUpload) { done(new HttpError(413, 'Upload exceeds declared length')); return; }
                hash.update(chunk); done(null, chunk);
              } });
              try {
                const file = await fs.open(temporary, 'wx', 0o600);
                await pipeline(request, meter, file.createWriteStream());
                if (received !== length) fail(400, 'Incomplete upload');
                const sha256 = hash.digest('hex');
                let existing;
                try { existing = await digest(path.join(directory, name)); } catch (e) { if (e.code !== 'ENOENT') throw e; }
                if (existing) { if (existing.sha256 !== sha256) fail(409, 'Asset is immutable'); }
                else { if (catalog.has(version)) fail(409, 'Release is immutable'); await fs.rename(temporary, path.join(directory, name)); }
                json(response, 200, { name, sha256, size: received });
              } finally { await fs.rm(temporary, { force: true }); }
            });
          } finally { uploads--; }
          return;
        }
        if (request.method === 'POST' && parts.length === 3) {
          const input = await readJson(request);
          await locked(version, async () => {
            const existing = catalog.get(version);
            if (existing) {
              const normalized = normalize(version, input, existing.manifest.sha256);
              if (JSON.stringify(normalized) !== JSON.stringify(existing.manifest)) fail(409, 'Release is immutable');
              json(response, 200, existing.manifest); return;
            }
            const assets = {};
            for (const name of [zipName, ...EXTRA]) {
              try { const info = await digest(path.join(directory, name)); assets[name] = { ...info, url: `${publicBase}/releases/${version}/${name}` }; }
              catch (e) { if (e.code !== 'ENOENT') throw e; }
            }
            if (!assets[zipName]) fail(400, 'Upload the release ZIP first');
            const manifest = normalize(version, input, assets[zipName].sha256);
            const record = { manifest, assets, publishedAt: new Date().toISOString() };
            const temporary = path.join(directory, `.release-${randomUUID()}`);
            await fs.writeFile(temporary, JSON.stringify(record) + '\n', { flag: 'wx', mode: 0o600 });
            await fs.rename(temporary, path.join(directory, 'release.json'));
            catalog.set(version, record);
            json(response, 201, manifest);
          }); return;
        }
      }
      fail(404, 'Not found');
    } catch (error) {
      if (response.headersSent || response.destroyed) { response.destroy(); return; }
      if (!error.status) console.error('Archive request failed:', error.message);
      response.setHeader('Connection', 'close');
      json(response, error.status ?? 500, { error: error.status ? error.message : 'Internal server error' });
      request.resume();
    }
  });
  server.requestTimeout = 120000; server.headersTimeout = 15000; server.keepAliveTimeout = 5000;
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = await createArchive({ dataDir: process.env.DATA_DIR ?? './data', token: process.env.ARCHIVE_TOKEN,
    publicUrl: process.env.PUBLIC_URL ?? 'https://wii-uu-archive.stoppedwumm.net',
    maxUpload: Number(process.env.MAX_UPLOAD_BYTES ?? 2 * 1024 ** 3) });
  const port = Number(process.env.PORT ?? 8081), host = process.env.HOST ?? '127.0.0.1';
  server.listen(port, host, () => console.log(`WII-UU archive listening on http://${host}:${port}`));
  process.on('SIGTERM', () => server.close(() => process.exit(0)));
  process.on('SIGINT', () => server.close(() => process.exit(0)));
}
