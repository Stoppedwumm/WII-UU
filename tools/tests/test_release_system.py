import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]

def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

identity = module('build_version', 'build-version.py').identity
site = module('stage_site', 'stage-release-site.py')

class ReleaseSystemTests(unittest.TestCase):
    def test_build_identities_and_tag_validation(self):
        env = {'GITHUB_RUN_NUMBER': '123', 'GITHUB_RUN_ATTEMPT': '2', 'GITHUB_SHA': 'a' * 40, 'SOURCE_DATE_EPOCH': '0'}
        beta = identity('2.0.9', env)
        self.assertEqual(beta['releaseVersion'], '2.0.9-beta.123.2')
        self.assertEqual(beta['version'], '2.0.9-beta.123.2+g' + 'a' * 12)
        stable = identity('2.0.9', {**env, 'GITHUB_REF': 'refs/tags/v2.0.9'})
        self.assertEqual((stable['version'], stable['channel']), ('2.0.9', 'stable'))
        pinned = identity('2.0.9', {**env, 'GITHUB_REF': 'refs/heads/release/2.0.9'})
        self.assertEqual((pinned['version'], pinned['channel']), ('2.0.9', 'stable'))
        with self.assertRaises(ValueError): identity('2.0.9', {**env, 'GITHUB_REF': 'refs/heads/release/2.0.8'})
        tagged = identity('2.0.9', {**env, 'GITHUB_REF': 'refs/tags/v2.0.9-beta.4'})
        self.assertEqual(tagged['releaseVersion'], '2.0.9-beta.4')
        for bad in ['refs/tags/v2.0.8', 'refs/tags/v2.0.9-rc.1']:
            with self.assertRaises(ValueError): identity('2.0.9', {**env, 'GITHUB_REF': bad})
        with self.assertRaises(ValueError): identity('02.0.9', env)
        with self.assertRaises(ValueError): identity('2.0.9', {**env, 'WIIUU_BUILD_CHANNEL': 'typo'})
        self.assertEqual(identity('2.0.9', {**env, 'WIIUU_BUILD_CHANNEL': 'release'})['channel'], 'stable')

    def fixture(self, root, version, beta=False, legacy=False):
        directory = root / version
        directory.mkdir()
        archive = directory / f'WII-UU-{version}.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            output.writestr(f'WII-UU-{version}/CHANGELOG.md', f'## {version}\n- Notes\n')
            output.writestr(f'WII-UU-{version}/ChoccyMilk.jar', version)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        manifest = {'version': version, 'channel': 'beta' if beta else 'stable', 'sha256': digest, 'zip': archive.as_uri()}
        feed = directory / 'version.json'
        feed.write_text(json.dumps(manifest))
        assets = [{'name': archive.name, 'browser_download_url': archive.as_uri()}]
        if not legacy: assets.append({'name': 'version.json', 'browser_download_url': feed.as_uri()})
        return {'tag_name': 'v' + version, 'prerelease': beta, 'draft': False, 'assets': assets}

    def test_channels_coexist_and_old_stable_releases_migrate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stable = self.fixture(root, '2.0.8', legacy=True)
            beta = self.fixture(root, '2.0.9-beta.123.2', beta=True)
            deployed = root / 'docs'
            site.stage(deployed, stable, False)
            stable_bytes = (deployed / 'download/WII-UU-latest.zip').read_bytes()
            site.stage(deployed, beta, True)
            self.assertEqual((deployed / 'download/WII-UU-latest.zip').read_bytes(), stable_bytes)
            self.assertEqual(json.loads((deployed / 'version.json').read_text())['version'], '2.0.8')
            self.assertEqual(json.loads((deployed / 'beta/version.json').read_text())['version'], '2.0.9-beta.123.2')
            self.assertEqual(site.select([stable, beta], False), stable)
            self.assertEqual(site.select([stable, beta], True), beta)
            early = self.fixture(root, '2.0.9-beta.123.1', beta=True)
            self.assertEqual(site.select([beta, early], True), beta)
            malformed = {**stable, 'tag_name': 'wiiuu-ui-v99.0.0'}
            self.assertIsNone(site.select([malformed], False))

    def test_stable_release_pages_deploy_from_default_branch(self):
        """GitHub Pages must deploy from its permitted default branch, not release/*."""
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        pages = workflow.split('\n  pages:\n', 1)[1]
        self.assertIn('needs: build', pages)
        self.assertIn("github.ref_name == github.event.repository.default_branch", pages)
        self.assertIn("github.event_name != 'pull_request'", pages)
        self.assertNotIn("startsWith(github.ref, 'refs/heads/release/')", pages)

        release_stage = workflow.index('name: Publish versioned application release')
        feed_stage = workflow.index('name: Assemble stable and beta website channels')
        package_site = workflow.index('name: Package the website')
        self.assertLess(release_stage, feed_stage)
        self.assertLess(feed_stage, package_site)

        followup = (ROOT / '.github/workflows/publish-stable-pages.yml').read_text()
        self.assertIn('workflow_run:', followup)
        self.assertIn('workflows: ["Build"]', followup)
        self.assertIn('types: [completed]', followup)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", followup)
        self.assertIn("github.event.workflow_run.event == 'push'", followup)
        self.assertIn("startsWith(github.event.workflow_run.head_branch, 'release/')", followup)
        self.assertIn("github.event.workflow_run.head_repository.full_name == github.repository", followup)
        self.assertIn('ref: ${{ github.event.repository.default_branch }}', followup)
        self.assertIn('name: github-pages', followup)
        self.assertIn('tools/stage-release-site.py build/published-releases.json', followup)
        self.assertIn('uses: actions/deploy-pages@v4', followup)

    def test_first_deployment_preserves_live_stable_instead_of_old_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            root.mkdir(exist_ok=True)
            (root / 'version.json').write_text('{"version":"1.13.0"}')
            releases = root / 'releases.json'
            releases.write_text('[]')
            output = io.BytesIO()
            with zipfile.ZipFile(output, 'w') as archive:
                archive.writestr('WII-UU-2.0.7/CHANGELOG.md', '## 2.0.7')
            payload = output.getvalue()
            manifest = {'version': '2.0.7', 'zip': 'http://site.test/download/WII-UU-2.0.7.zip',
                        'sha256': hashlib.sha256(payload).hexdigest()}
            def fetch(url):
                return json.dumps(manifest).encode() if url.endswith('/version.json') else payload
            with mock.patch.object(site, 'fetch', side_effect=fetch), mock.patch.dict('os.environ', {'WIIUU_SITE':'http://site.test'}), mock.patch.object(sys, 'argv', ['stage', str(releases), '--root', str(root)]):
                site.main()
            self.assertEqual(json.loads((root / 'version.json').read_text())['version'], '2.0.7')
            self.assertEqual((root / 'download/WII-UU-latest.zip').read_bytes(), payload)

    def test_beta_manifest_writer_leaves_stable_feed_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'build').mkdir(); (root / 'dist').mkdir(); (root / 'docs').mkdir()
            data = identity('2.0.9', {'GITHUB_SHA': 'a' * 40, 'SOURCE_DATE_EPOCH': '0'})
            (root / 'build/version.json').write_text(json.dumps(data))
            (root / 'dist' / f'WII-UU-{data["releaseVersion"]}.zip').write_bytes(b'zip contents')
            stable = root / 'docs/version.json'
            stable.write_text('{"version":"2.0.8"}')
            subprocess.run([sys.executable, str(ROOT / 'tools/write-update-manifest.py')], cwd=root, check=True, capture_output=True)
            self.assertEqual(stable.read_text(), '{"version":"2.0.8"}')
            feed = json.loads((root / 'docs/beta/version.json').read_text())
            self.assertEqual(feed['sha256'], hashlib.sha256(b'zip contents').hexdigest())
            self.assertEqual(feed['channel'], 'beta')

if __name__ == '__main__': unittest.main()
