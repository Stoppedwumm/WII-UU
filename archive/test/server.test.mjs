import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createArchive } from '../server.mjs';
import { publish } from '../client.mjs';

const token = 'test-only-token-with-more-than-32-characters';
const hash = value => createHash('sha256').update(value).digest('hex');
async function fixture(t, options = {}) {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'wiiuu-archive-test-'));
  const server = await createArchive({ dataDir, token, publicUrl: 'https://archive.example.test', ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await fs.rm(dataDir, { recursive: true, force: true }); });
  const request = (endpoint, method = 'GET', body, auth = token, headers = {}) => fetch(url + endpoint, {
    method, body, headers: { ...(auth ? { Authorization: `Bearer ${auth}` } : {}), ...headers }
  });
  return { dataDir, server, url, request };
}
async function release(request, version, payload = Buffer.from('release contents'), metadata = {}) {
  const prefix = `/api/releases/${version}`;
  assert.equal((await request(`${prefix}/WII-UU-${version}.zip`, 'PUT', payload)).status, 200);
  const manifest = { version, channel: version.includes('-') ? 'beta' : 'stable', sha256: hash(payload), ...metadata };
  const response = await request(prefix, 'POST', JSON.stringify(manifest), token, { 'Content-Type': 'application/json' });
  assert.equal(response.status, 201);
  return { prefix, manifest, published: await response.json(), payload };
}

test('auth, atomic visibility, checksum, immutable publication and byte ranges', async t => {
  const { request } = await fixture(t);
  assert.equal((await request('/version.json')).status, 404);
  assert.equal((await request('/api/releases/2.0.9/WII-UU-2.0.9.zip', 'PUT', Buffer.from('x'), '')).status, 401);
  assert.equal((await request('/api/releases/2.0.9/WII-UU-2.0.9.zip', 'PUT', Buffer.from('abcdef'))).status, 200);
  assert.equal((await request('/releases/2.0.9/WII-UU-2.0.9.zip')).status, 404);
  assert.equal((await request('/api/releases/2.0.9', 'POST', JSON.stringify({ version: '2.0.9', channel: 'stable', sha256: hash('wrong') }))).status, 400);
  const manifest = { version: '2.0.9', channel: 'stable', sha256: hash('abcdef') };
  assert.equal((await request('/api/releases/2.0.9', 'POST', JSON.stringify({ ...manifest, version: '2.0.9+g..bad' }))).status, 400);
  const publish = await request('/api/releases/2.0.9', 'POST', JSON.stringify(manifest));
  assert.equal(publish.status, 201);
  const canonical = await publish.json();
  assert.equal(canonical.zip, 'https://archive.example.test/releases/2.0.9/WII-UU-2.0.9.zip');
  assert.equal((await request('/api/releases/2.0.9', 'POST', JSON.stringify(manifest))).status, 200);
  assert.equal((await request('/api/releases/2.0.9', 'POST', JSON.stringify({ ...manifest, commit: 'changed' }))).status, 409);
  assert.equal((await request('/api/releases/2.0.9/WII-UU-2.0.9.zip', 'PUT', Buffer.from('different'))).status, 409);
  const ranged = await request('/releases/2.0.9/WII-UU-2.0.9.zip', 'GET', undefined, '', { Range: 'bytes=1-3' });
  assert.equal(ranged.status, 206); assert.equal(await ranged.text(), 'bcd');
  assert.equal(ranged.headers.get('content-range'), 'bytes 1-3/6');
  const tail = await request('/releases/2.0.9/WII-UU-2.0.9.zip', 'GET', undefined, '', { Range: 'bytes=-2' });
  assert.equal(await tail.text(), 'ef');
  assert.equal((await request('/releases/2.0.9/WII-UU-2.0.9.zip', 'GET', undefined, '', { Range: 'bytes=99-' })).status, 416);
  const cached = await request('/releases/2.0.9/WII-UU-2.0.9.zip', 'GET', undefined, '', { 'If-None-Match': `"${hash('abcdef')}"` });
  assert.equal(cached.status, 304);
  const head = await request('/releases/2.0.9/WII-UU-2.0.9.zip', 'HEAD');
  assert.equal(head.headers.get('content-length'), '6'); assert.equal(await head.text(), '');
  assert.equal((await request('/releases/2.0.9/__proto__')).status, 404);
  assert.equal((await request('/api/releases/2.0.9/..%2Fescape.zip', 'PUT', Buffer.from('x'))).status, 400);
});

test('stable and beta feeds use semantic ordering, survive restart, and escape archive text', async t => {
  const { request, dataDir } = await fixture(t);
  await release(request, '2.0.9-beta.2');
  await release(request, '2.0.9-beta.10');
  await release(request, '2.0.8');
  assert.equal((await (await request('/beta/version.json')).json()).version, '2.0.9-beta.10');
  assert.equal((await (await request('/version.json')).json()).version, '2.0.8');
  await release(request, '2.0.9');
  assert.equal((await (await request('/version.json')).json()).version, '2.0.9');
  const list = await (await request('/api/releases?limit=2')).json();
  assert.equal(list.total, 4); assert.equal(list.releases[0].version, '2.0.9');
  assert.equal((await (await request('/api/releases?channel=stable')).json()).total, 2);
  const restarted = await createArchive({ dataDir, token, publicUrl: 'https://archive.example.test' });
  await new Promise(resolve => restarted.listen(0, '127.0.0.1', resolve));
  const response = await fetch(`http://127.0.0.1:${restarted.address().port}/version.json`);
  assert.equal((await response.json()).version, '2.0.9');
  restarted.closeAllConnections(); await new Promise(resolve => restarted.close(resolve));
  await release(request, '1.0.0', Buffer.from('older'), { commit: '<script>alert(1)</script>' });
  const page = await (await request('/')).text();
  assert.ok(page.includes('&lt;script&gt;')); assert.ok(!page.includes('<script>alert'));
  assert.equal((await request('/api/releases?offset=-1')).status, 400);
});

test('upload limits and client end-to-end publish', async t => {
  const { request } = await fixture(t, { maxUpload: 4 });
  assert.equal((await request('/api/releases/1.0.0/WII-UU-1.0.0.zip', 'PUT', Buffer.from('too much'))).status, 413);
  const live = await fixture(t);
  const version = '3.0.0-beta.7', payload = Buffer.from('client zip contents');
  const source = path.join(live.dataDir, `WII-UU-${version}.zip`);
  await fs.writeFile(source, payload);
  const manifest = { version: `${version}+gabcdef123456`, releaseVersion: version, channel: 'beta', sha256: hash(payload) };
  const result = await publish({ url: live.url, token, manifest, assets: [source] });
  assert.equal(result.version, manifest.version);
  assert.equal((await (await live.request('/beta/version.json')).json()).sha256, hash(payload));
});

test('concurrent conflicting uploads cannot overwrite each other', async t => {
  const { request } = await fixture(t);
  const endpoint = '/api/releases/2.1.0-beta.1.2/WII-UU-2.1.0-beta.1.2.zip';
  const responses = await Promise.all([request(endpoint, 'PUT', Buffer.from('first')), request(endpoint, 'PUT', Buffer.from('second'))]);
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 409]);
  assert.equal((await request('/beta/version.json')).status, 404);
});
