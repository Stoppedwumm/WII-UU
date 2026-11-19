#!/usr/bin/env python3
"""Write the packaged release manifest to its channel without touching the other channel."""
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import quote

identity = json.loads(Path('build/version.json').read_text())
version = identity['releaseVersion']
name = f'WII-UU-{version}.zip'
zip_path = Path('dist') / name
site = os.environ.get('WIIUU_SITE', 'https://wiiuu.stoppedwumm.net').rstrip('/')
repository = os.environ.get('GITHUB_REPOSITORY', '')
url = (f'https://github.com/{repository}/releases/download/v{version}/{quote(name)}'
       if repository else f'{site}/download/{quote(name)}')
manifest = {**identity, 'schema': 2, 'zip': url, 'sha256': hashlib.sha256(zip_path.read_bytes()).hexdigest()}
root = Path('docs') if identity['channel'] == 'stable' else Path('docs/beta')
root.mkdir(parents=True, exist_ok=True)
(root / 'version.json').write_text(json.dumps(manifest, indent=2) + '\n')
feed = Path('build/release-feed')
feed.mkdir(parents=True, exist_ok=True)
(feed / 'version.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(f'Packaged {identity["version"]} ({identity["channel"]})')
