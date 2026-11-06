#!/usr/bin/env python3
"""Bundle pinned upstream executables; no binary is fetched when the app runs."""
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
manifest = json.loads((ROOT / 'tools/cloudflared.json').read_text())
selected = sys.argv[1:]

def package(item):
    platform, asset = item
    if selected and platform not in selected:
        return
    out = ROOT / 'build/cloudflared' / platform
    out.mkdir(parents=True, exist_ok=True)
    cache = ROOT / 'build/cloudflared-downloads' / asset['sha256']
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        request = urllib.request.Request(asset['url'], headers={'User-Agent': 'WII-UU-build'})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != asset['sha256']:
            raise RuntimeError('cloudflared checksum mismatch: ' + platform)
        cache.write_bytes(data)
    data = cache.read_bytes()
    if hashlib.sha256(data).hexdigest() != asset['sha256']:
        raise RuntimeError('corrupt cached cloudflared: ' + platform)
    if asset['url'].endswith('.tgz'):
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            members = [m for m in archive.getmembers() if m.isfile() and Path(m.name).name == 'cloudflared']
            if len(members) != 1:
                raise RuntimeError('unexpected upstream archive')
            data = archive.extractfile(members[0]).read()
    filename = 'cloudflared.exe' if platform.startswith('windows-') else 'cloudflared'
    binary = out / filename
    binary.write_bytes(data)
    binary.chmod(0o755)
    (out / 'sha256').write_text(hashlib.sha256(data).hexdigest() + '\n')
    print('Bundled cloudflared ' + manifest['version'] + ': ' + platform)

if set(selected) - manifest['assets'].keys():
    raise SystemExit('unknown platform')
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(package, manifest['assets'].items()))
