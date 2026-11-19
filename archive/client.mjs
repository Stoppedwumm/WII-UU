import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Stream assets to staging, then atomically publish the manifest. Safe to retry identical releases. */
export async function publish({ url, token, manifest, assets }) {
  if (!token) throw new Error('Set ARCHIVE_TOKEN (never pass it as a command-line argument)');
  const version = manifest.releaseVersion ?? manifest.version.split('+')[0];
  const base = url.replace(/\/$/, '');
  async function request(endpoint, options) {
    const response = await fetch(base + endpoint, { ...options, headers: { ...options.headers, Authorization: `Bearer ${token}` } });
    const body = await response.text();
    if (!response.ok) throw new Error(`Archive ${response.status}: ${body.slice(0, 1000)}`);
    return JSON.parse(body);
  }
  for (const file of assets) {
    const stat = await fs.stat(file);
    await request(`/api/releases/${encodeURIComponent(version)}/${encodeURIComponent(path.basename(file))}`, {
      method: 'PUT', headers: { 'Content-Length': String(stat.size), 'Content-Type': 'application/octet-stream' },
      body: createReadStream(file), duplex: 'half', signal: AbortSignal.timeout(120000)
    });
  }
  const result = await request(`/api/releases/${encodeURIComponent(version)}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(manifest), signal: AbortSignal.timeout(30000)
  });
  if (result.version !== manifest.version || result.sha256 !== manifest.sha256) throw new Error('Published identity does not match input');
  return result;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const options = { assets: [] };
  for (let i = 2; i < process.argv.length; i++) {
    const flag = process.argv[i];
    if (!['--url', '--manifest', '--asset', '--write-manifest'].includes(flag) || !process.argv[i + 1])
      throw new Error('Usage: node client.mjs --url URL --manifest FILE --asset FILE [--asset FILE ...] [--write-manifest FILE]');
    const value = process.argv[++i];
    if (flag === '--asset') options.assets.push(value); else options[flag.slice(2)] = value;
  }
  if (!options.url || !options.manifest || !options.assets.length) throw new Error('--url, --manifest and --asset are required');
  const manifest = JSON.parse(await fs.readFile(options.manifest, 'utf8'));
  const result = await publish({ url: options.url, token: process.env.ARCHIVE_TOKEN, manifest, assets: options.assets });
  if (options['write-manifest']) await fs.writeFile(options['write-manifest'], JSON.stringify(result, null, 2) + '\n');
  console.log(`Archived ${result.version} (${result.channel})`);
}
