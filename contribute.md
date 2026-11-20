# Contributing to WII-UU

Thanks for helping improve WII-UU. This guide covers development, pull requests, and how maintainers publish beta and **stable releases**. For the complete update-channel design, see [release/version documentation](docs/support/releases.md).

## Before you start

- Open an issue or discuss substantial changes before building a large feature.
- Keep PRs focused, explain platform-specific behavior, and include tests or reproduction steps.
- Do not include copyrighted ROMs, game firmware, API keys, tokens, personal data, or generated build folders.
- Treat build scripts and GitHub Actions changes as security-sensitive. Never execute untrusted PR scripts with release credentials.
- The launcher targets **Java 17+**. Choccy Milk's native code needs **JDK 22+** for a complete local build. GitHub Actions currently uses **JDK 25** for packaging, **JDK 27** for its native-startup test, and **Node.js 22** for plugin tests.
- Have Git, Bash, Python 3, `javac`, `jar`, `zip`, and `unzip` installed. On Windows, use a compatible shell such as WSL for the Bash build scripts. Node.js/npm are needed for JavaScript plugin development and relevant tests.

## Development

Fork the repository or create a branch from its default branch. The current default branch is `claude/focused-maxwell-03jh0f`; use the branch marked **Default** on GitHub if that changes.

```bash
git clone https://github.com/Stoppedwumm/WII-UU.git
cd WII-UU
git switch -c feature/my-change
./build.sh
```

The build produces `build/wiiuu.jar`, `build/wiiuu-engine.jar`, `build/ChoccyMilk.jar` and standalone tools. To build a distributable ZIP:

```bash
./package.sh
```

`package.sh` downloads/platform-packages Cloudflared helpers and writes the release ZIP to `dist/WII-UU-<version>.zip`. **A local package is not a published stable release.** By default it uses a beta identity; do not publish its output under a stable tag.

Run the tests relevant to the files you changed. Examples:

```bash
python3 -m unittest discover -s tools/tests -p 'test_release*.py'
node --test archive/test/*.test.mjs test/plugins/*.test.cjs
bash -n install.sh emulators.sh build.sh package.sh docs/get.sh
```

The GitHub Actions [Build workflow](.github/workflows/build.yml) runs additional Java, installer, Linux/macOS startup, Windows and UI tests. Allow those checks to complete before merging.

## Pull requests

1. Branch from the latest default branch and commit your changes.
2. Update or add tests, documentation and `CHANGELOG.md` entries as appropriate.
3. Push your branch (`git push -u origin feature/my-change`) and open a PR against the default branch.
4. Explain **what changed**, **why**, how you tested it, and any platform-specific limitations.
5. Address CI errors and review comments before merging.

Ordinary feature and documentation PRs **can keep the current `Main.VERSION`**. The version check only requires a valid release target that does not go backwards. Do not increment the patch version for every PR just to silence CI.

## Versions and channels

The source of truth for the target version is `public static final String VERSION` in `src/wiiuu/Main.java`. Use the canonical `MAJOR.MINOR.PATCH` format, such as `2.1.3`.

- **Feature branch or PR:** tested by CI and uploaded as a GitHub Actions artifact; it does not publish a public release or deploy the website.
- **Default-branch push:** produces a uniquely numbered **beta prerelease** and refreshes the beta feed. Development builds are not automatically stable.
- **Stable release:** requires an explicit release trigger: a matching `release/X.Y.Z` branch, an exact `vX.Y.Z` tag, or **Build → Run workflow → channel: stable** on the default branch.
- **Stable subscribers** use `https://wiiuu.stoppedwumm.net/version.json` and never receive betas. **Beta subscribers** use `/beta/version.json` and also consider newer final releases.

CI identifies beta builds using the GitHub Actions run/attempt plus commit (for example, `2.1.3-beta.500.1+g123456789abc`). Do not hand-edit that generated identity, `build/version.json`, or the published manifest.

## Publishing a stable release (maintainers)

You need write permission to the repository and authorization for its GitHub Actions release workflow. **Recommended: publish from a matching `release/X.Y.Z` branch.** This method also deploys the stable update feed after the GitHub release is created.

### 1. Prepare and merge the release changes

Choose the next version, update `src/wiiuu/Main.java`, and add a matching heading at the **top** of `CHANGELOG.md`:

```java
public static final String VERSION = "2.1.3";
```

```markdown
## 2.1.3 (YYYY-MM-DD)
- Describe the changes users will notice.
```

Replace `2.1.3` with the actual intended version; these are examples, **not** a request to publish that version. The packaging script refuses a build without a matching `## X.Y.Z` changelog section.

Submit a PR and merge it into the default branch **after all checks pass**. Check that the chosen version has not already been released: GitHub releases are immutable in normal operation and the workflow will not overwrite an existing release tag.

### 2. Push a release branch (recommended)

Update your local default branch to the exact tested commit first, then:

```bash
VERSION=2.1.3
git switch -c "release/$VERSION"
git push -u origin "release/$VERSION"
```

**Important:** `release/$VERSION` must match the `Main.VERSION` value in that commit. The `Build` workflow automatically runs with the **stable** channel for this branch. It runs tests, packages `WII-UU-$VERSION.zip`, creates a GitHub release named `v$VERSION` (not marked prerelease), stages both update feeds from published releases, and runs the GitHub Pages deployment **after** packaging.

The stable feed deploys from `release/*` as well as the default branch. This dependency is intentional: a website deployment that races ahead of the GitHub release can leave installed clients seeing the previous version.

### 3. Alternative: promote the default branch using Actions

Open [GitHub Actions → Build](https://github.com/Stoppedwumm/WII-UU/actions/workflows/build.yml), choose **Run workflow**, select the **default branch**, choose **channel: stable**, and start it.

Or, with an authenticated GitHub CLI:

```bash
gh workflow run build.yml --repo Stoppedwumm/WII-UU \
  --ref claude/focused-maxwell-03jh0f -f channel=stable
```

Use the repository's actual default branch if it has changed. The stable dispatch is **rejected on non-default branches**. It publishes the exact release version already declared by `Main.VERSION` and deploys the website through the default-branch run.

### 4. Alternative: push an exact version tag

After verifying the commit and changelog, a maintainer may also run:

```bash
VERSION=2.1.3
git tag "v$VERSION"
git push origin "v$VERSION"
```

The tag **must** equal `v` plus `Main.VERSION`; mismatched and unsupported tags fail. A tag-triggered run publishes the stable GitHub release, **but it does not itself run the GitHub Pages deployment**. To refresh the website stable feed, run the default-branch **Build** workflow afterward (the usual default beta run is sufficient to restage already-published stable releases), or use the recommended release-branch / stable-dispatch routes instead. Do **not** assume creating a GitHub release means WII-UU clients can already see it.

Avoid publishing the same stable version through several paths at once. The workflow deliberately does not overwrite an already-existing tag/release.

### 5. Verify the release and updater feed

Watch [GitHub Actions](https://github.com/Stoppedwumm/WII-UU/actions/workflows/build.yml). Verify all build and publication jobs succeeded, including **pages**. The build logs should report:

- `Stable update feed staged: X.Y.Z`
- `Beta update feed staged: ...`

Then verify the release exists and is not marked prerelease:

```bash
VERSION=2.1.3
gh release view "v$VERSION" --repo Stoppedwumm/WII-UU
```

Verify the **deployed** feed, not just the files in a checkout:

```bash
curl -fsSL "https://wiiuu.stoppedwumm.net/version.json" \
  | python3 -c 'import json,sys; x=json.load(sys.stdin); print(x["version"], x.get("channel"), x["zip"])'
```

The version must be `X.Y.Z`, the channel `stable`, and the ZIP must point at the corresponding published release. Check that the ZIP downloads and that its SHA-256 matches the `sha256` in the manifest. Finally, in an installed WII-UU, select **Settings → System → Updates → Stable → Check for updates**. An installed version older than `X.Y.Z` should now be offered the stable update.

If GitHub says the release exists but WII-UU still reports the previous version, check the **Pages job** and the live `/version.json` first. The GitHub release, the deployed feed, and the installed client are separate parts of the release process.

## Troubleshooting and recovery

- **PR checks fail:** look at the first failing Build job and reproduce its test locally; do not publish a release from a failing build.
- **Wrong release channel:** inspect the source `Main.VERSION`, branch name, tag, and `channel` in the generated manifest. `WIIUU_BUILD_CHANNEL=stable` is intended for controlled packaging, not a substitute for the release workflow.
- **Stable release exists, but update feed is old:** verify a successful GitHub Pages deployment occurred **after** the release was published. A successful `build` job alone does not prove Pages was updated. Trigger the default-branch Build workflow to restage the already-published releases if needed.
- **Website feed correct, client still sees old version:** check `Settings → System → Updates` channel, any custom `update.url` / `update.stableUrl` override, and possible HTTP caching.
- **Release already exists:** do not replace its ZIP or checksum. Publish a new version instead of mutating an immutable release.

## Related documentation

- [Release/version system](docs/support/releases.md)
- [Release archive server](archive/README.md)
- [JavaScript plugin development](docs/plugins.md)
- [Project README](README.md)
- [Build workflow](.github/workflows/build.yml)
