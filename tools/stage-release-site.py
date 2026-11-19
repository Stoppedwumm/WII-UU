#!/usr/bin/env python3
"""Rebuild BOTH website channels from GitHub release assets, never a stale checkout's binaries.

Input is `gh api --paginate --slurp repos/OWNER/REPO/releases` JSON. Assets are public;
no credential is passed to this script. Also migrates historical stable releases without feeds.
"""
import argparse
import hashlib
import io
import json
import os
import urllib.error
from pathlib import Path
import re
import shutil
import urllib.request
import zipfile

TAG = re.compile(r'^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-beta\.(0|[1-9][0-9]*)(?:\.([1-9][0-9]*))?)?$')


def select(releases, beta):
    found = []
    for release in releases:
        match = TAG.fullmatch(release.get('tag_name', ''))
        if release.get('draft') or not match or bool(match[4] is not None) != beta:
            continue
        if bool(release.get('prerelease')) != beta:
            continue
        key = tuple(int(match[i]) for i in range(1, 4)) + ((int(match[4]), int(match[5] or 0)) if beta else ())
        found.append((key, release))
    return max(found, key=lambda x: x[0])[1] if found else None


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'WII-UU-release-site'})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def stage(root, release, beta):
    channel = 'beta' if beta else 'stable'
    version = release['tag_name'][1:]
    assets = {item['name']: item['browser_download_url'] for item in release['assets']}
    name = f'WII-UU-{version}.zip'
    if name not in assets:
        raise ValueError(f'{release["tag_name"]}: missing {name}')
    payload = fetch(assets[name])
    digest = hashlib.sha256(payload).hexdigest()
    manifest = json.loads(fetch(assets['version.json'])) if 'version.json' in assets else {
        'version': version, 'channel': channel, 'schema': 2, 'sha256': digest, 'zip': assets[name]
    }
    if (manifest['version'].split('+')[0] != version or manifest.get('channel', 'stable') != channel
            or manifest.get('sha256') != digest):
        raise ValueError(f'{release["tag_name"]}: manifest identity/checksum mismatch')
    directory = root / 'beta' if beta else root
    downloads = directory / 'download'
    downloads.mkdir(parents=True, exist_ok=True)
    (directory / 'version.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (downloads / name).write_bytes(payload)
    shutil.copyfile(downloads / name, downloads / 'WII-UU-latest.zip')
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        # Only explicitly named regular release payloads are copied; paths never come from the ZIP.
        for filename in ['install.sh', 'WII-UU-Cleanup.jar', 'WII-UU-OEM-Creator.jar', 'ChoccyMilk.jar', 'CHANGELOG.md']:
            entries = [entry for entry in archive.infolist() if not entry.is_dir() and Path(entry.filename).name == filename]
            if len(entries) == 1:
                destination = directory / 'changelog.md' if filename == 'CHANGELOG.md' else downloads / filename
                destination.write_bytes(archive.read(entries[0]))
        if 'WII-UU-Reactions.jar' in assets:
            (downloads / 'WII-UU-Reactions.jar').write_bytes(fetch(assets['WII-UU-Reactions.jar']))
    return downloads / name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('releases', type=Path)
    parser.add_argument('--root', type=Path, default=Path('docs'))
    args = parser.parse_args()
    releases = json.loads(args.releases.read_text())
    if releases and isinstance(releases[0], list):
        releases = [item for page in releases for item in page]
    keep = set()
    for beta in [False, True]:
        release = select(releases, beta)
        if release:
            keep.add(stage(args.root, release, beta))
        elif not beta:
            # Preserve the currently deployed stable channel during migration from branch releases.
            site = os.environ.get('WIIUU_SITE', 'https://wiiuu.stoppedwumm.net').rstrip('/')
            try:
                manifest = json.loads(fetch(site + '/version.json'))
                version = manifest['version'].split('+')[0]
                if not TAG.fullmatch('v' + version) or '-' in version:
                    raise ValueError('Deployed stable feed contains a prerelease or invalid version')
                migrated = {'tag_name': 'v' + version, 'assets': [
                    {'name': 'version.json', 'browser_download_url': site + '/version.json'},
                    {'name': f'WII-UU-{version}.zip', 'browser_download_url': manifest['zip']} ]}
                keep.add(stage(args.root, migrated, False))
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                manifest = json.loads((args.root / 'version.json').read_text())
                if '-' in manifest['version'].split('+')[0]:
                    raise ValueError('Refusing a beta stable feed')
                keep.add(args.root / 'download' / f'WII-UU-{manifest["version"]}.zip')
    for directory in [args.root / 'download', args.root / 'beta/download']:
        for old in directory.glob('WII-UU-*.zip'):
            if old.name != 'WII-UU-latest.zip' and old not in keep:
                old.unlink()


if __name__ == '__main__':
    main()
