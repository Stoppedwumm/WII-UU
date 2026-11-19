#!/usr/bin/env python3
"""Manually rebuild historical WII-UU versions; never invoked by Actions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

VERSION_FILE = 'src/wiiuu/Main.java'
VERSION = re.compile(r'\bVERSION\s*=\s*"(\d+\.\d+\.\d+)"')


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def versions(repo, ref):
    # Newest commit for each version, including fixes made after its version bump.
    found = {}
    for commit in git(repo, 'rev-list', '--first-parent', ref).splitlines():
        target = subprocess.run(['git', '-C', str(repo), 'show', f'{commit}:VERSION'], capture_output=True, text=True)
        if target.returncode == 0 and re.fullmatch(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)', target.stdout.strip()):
            found.setdefault(target.stdout.strip(), commit)
        else:
            result = subprocess.run(['git', '-C', str(repo), 'show', f'{commit}:{VERSION_FILE}'],
                                    capture_output=True, text=True)
            match = VERSION.search(result.stdout)
            if match:
                found.setdefault(match[1], commit)
    return sorted(found.items(), key=lambda item: tuple(map(int, item[0].split('.'))))


def digest(path):
    sha = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            sha.update(chunk)
    return sha.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', default='https://github.com/Stoppedwumm/WII-UU.git',
                        help='Git URL or local repository (must be trusted: builds execute its scripts)')
    parser.add_argument('--ref', default='HEAD', help='Branch/tag/commit to walk (default: remote default branch)')
    parser.add_argument('--output', type=Path, default=Path('WII-UU-version-archive.zip'))
    parser.add_argument('--work-dir', type=Path, help='Drive/directory for temporary clones and builds')
    parser.add_argument('--version', action='append', help='Only this version; repeat for multiple versions')
    parser.add_argument('--list', action='store_true', help='List discovered versions without building')
    parser.add_argument('--source-only', action='store_true', help='Archive source snapshots without running builds')
    args = parser.parse_args()
    output = args.output.resolve()
    if not args.list and output.exists():
        parser.error(f'Output already exists: {output}; choose a new filename')
    if args.work_dir:
        args.work_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='wiiuu-archive-', dir=args.work_dir) as temporary:
        root = Path(temporary)
        repo = root / 'history.git'
        subprocess.run(['git', 'clone', '--mirror', args.repository, str(repo)], check=True)
        if git(repo, 'rev-parse', '--is-shallow-repository') == 'true':
            parser.error('Repository history is shallow; use a full repository or the GitHub URL')
        selected = versions(repo, args.ref)
        if args.version:
            missing = set(args.version) - {v for v, _ in selected}
            if missing:
                parser.error('Versions not found: ' + ', '.join(sorted(missing)))
            selected = [(v, c) for v, c in selected if v in args.version]
        if not selected:
            parser.error('No WII-UU versions found in this history')
        if args.list:
            for version, commit in selected:
                print(f'{version}\t{commit}')
            return 0
        output.parent.mkdir(parents=True, exist_ok=True)
        # Stage beside the destination so final rename is on the same filesystem.
        fd, partial = tempfile.mkstemp(prefix=output.name + '.', suffix='.partial', dir=output.parent)
        os.close(fd)
        manifest = {'repository': args.repository, 'ref': args.ref,
                    'selection': 'Latest first-parent commit per version', 'versions': []}
        failed = False
        try:
            with zipfile.ZipFile(partial, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
                for version, commit in selected:
                    print(f'Archiving {version} ({commit[:12]})', flush=True)
                    entry = {'version': version, 'commit': commit, 'files': []}
                    prefix = f'WII-UU-{version}'
                    def add(path, name):
                        archive.write(path, f'{prefix}/{name}')
                        entry['files'].append({'path': f'{prefix}/{name}',
                                               'bytes': path.stat().st_size, 'sha256': digest(path)})
                    source = root / 'source.zip'
                    subprocess.run(['git', '-C', str(repo), 'archive', '--format=zip',
                                    f'--output={source}', f'--prefix={prefix}/', commit], check=True)
                    add(source, 'source.zip')
                    source.unlink()
                    entry['status'] = 'source-only'
                    if not args.source_only:
                        checkout = root / 'build'
                        subprocess.run(['git', '-C', str(repo), 'worktree', 'add', '--detach',
                                        str(checkout), commit], check=True, stdout=subprocess.DEVNULL)
                        try:
                            package = checkout / 'dist' / f'{prefix}.zip'
                            package.unlink(missing_ok=True)
                            log = root / 'build.log'
                            with log.open('wb') as stream:
                                # Use each version's own packaging recipe, not today's recipe.
                                result = subprocess.run(['bash', 'package.sh'], cwd=checkout,
                                    env={**os.environ, 'WIIUU_BUILD_CHANNEL': 'release'},
                                    stdout=stream, stderr=subprocess.STDOUT)
                            add(log, 'build.log')
                            package = checkout / 'dist' / f'{prefix}.zip'
                            # Never accept an old tracked package as the result of a failed build.
                            if result.returncode == 0 and package.is_file():
                                add(package, package.name)
                                entry['status'] = 'built'
                            else:
                                entry.update(status='failed', exit_code=result.returncode)
                                failed = True
                                print(f'  Build failed; source and log retained for {version}', file=sys.stderr)
                        finally:
                            subprocess.run(['git', '-C', str(repo), 'worktree', 'remove', '--force',
                                            str(checkout)], check=True)
                    manifest['versions'].append(entry)
                archive.writestr('manifest.json', json.dumps(manifest, indent=2) + '\n')
            os.replace(partial, output)
        finally:
            Path(partial).unlink(missing_ok=True)
        print(f'Archive: {output}\nSHA-256: {digest(output)}')
        return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError) as error:
        print(f'Archive failed: {error}', file=sys.stderr)
        sys.exit(1)
