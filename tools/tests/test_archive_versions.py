import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / 'archive-versions.py'


class ArchiveTest(unittest.TestCase):
    def test_history_build_failure_and_sources(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            repo = root / 'repo'
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()
            git('init', '-q')
            git('config', 'user.email', 'test@example.invalid')
            git('config', 'user.name', 'Test')
            main = repo / 'src/wiiuu/Main.java'
            main.parent.mkdir(parents=True)
            for version in ('1.0.0', '1.0.1'):
                main.write_text(f'public static final String VERSION = "{version}";')
                (repo / 'package.sh').write_text('exit 7\n' if version == '1.0.1' else
                    'mkdir -p dist\nprintf test > dist/WII-UU-1.0.0.zip\n')
                git('add', '.')
                git('commit', '-qm', version)
                if version == '1.0.0':
                    (repo / 'fix.txt').write_text('later fix')
                    git('add', '.')
                    git('commit', '-qm', 'fix without version bump')
                    fixed = git('rev-parse', 'HEAD')
            def run(*args):
                return subprocess.run([sys.executable, str(SCRIPT), '--repository', str(repo),
                                       *args], capture_output=True, text=True)
            listed = run('--list')
            self.assertEqual(listed.returncode, 0, listed.stderr)
            self.assertIn(f'1.0.0\t{fixed}', listed.stdout)
            output = root / 'archive.zip'
            built = run('--output', str(output), '--work-dir', str(root / 'work'))
            self.assertEqual(built.returncode, 1, built.stderr)
            with zipfile.ZipFile(output) as archive:
                entries = json.loads(archive.read('manifest.json'))['versions']
                self.assertEqual([e['status'] for e in entries], ['built', 'failed'])
                self.assertEqual(entries[0]['commit'], fixed)
                for entry in entries:
                    for file in entry['files']:
                        self.assertEqual(hashlib.sha256(archive.read(file['path'])).hexdigest(), file['sha256'])
            original = output.read_bytes()
            self.assertNotEqual(run('--output', str(output)).returncode, 0)
            self.assertEqual(original, output.read_bytes())
            sources = root / 'sources.zip'
            result = run('--source-only', '--version', '1.0.1', '--output', str(sources))
            self.assertEqual(result.returncode, 0, result.stderr)
            with zipfile.ZipFile(sources) as archive:
                entries = json.loads(archive.read('manifest.json'))['versions']
                self.assertEqual(len(entries), 1)
                self.assertEqual(entries[0]['status'], 'source-only')
            self.assertNotEqual(run('--version', '9.9.9', '--list').returncode, 0)
            self.assertEqual(list((root / 'work').iterdir()), [])
            self.assertEqual(git('status', '--porcelain'), '')


if __name__ == '__main__':
    unittest.main()
