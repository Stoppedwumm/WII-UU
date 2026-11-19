# WII-UU versions and release channels

`src/wiiuu/Main.java` declares the canonical `VERSION` release target, e.g. `2.0.9`. Runtime identity is generated once by `tools/build-version.py` and packaged into both applications. `Main.BUILD_VERSION` reads the packaged identity; `Main.VERSION` remains the plain target for compatible tooling. Folder names, working directories and source branches do not change an installed build's identity.

| Build | Identity | Publication |
| --- | --- | --- |
| Local default | `2.0.9-beta.0+gCOMMIT` | Local files only |
| CI branch/PR | `2.0.9-beta.RUN.ATTEMPT+gCOMMIT` | Actions artifact |
| Default branch | Same beta format | Prerelease + beta feed |
| Tag `v2.0.9-beta.4` | `2.0.9-beta.4+gCOMMIT` | Explicit prerelease |
| Tag `v2.0.9` | `2.0.9` | Stable release + stable feed |

ZIP names and release tags omit build metadata (`+gCOMMIT`). CI reruns get distinct attempt suffixes. Source timestamp defaults to the commit date and can be set with `SOURCE_DATE_EPOCH` for reproducible metadata. Published binaries and metadata remain immutable.

To release: update `Main.VERSION` and the matching changelog heading when starting a new release cycle. Ordinary PRs do not need to invent patch versions; CI only prevents the target version from going backwards. Create an exact version tag when the target is ready:

```bash
git tag v2.0.9
git push origin v2.0.9
```

Tags that disagree with `Main.VERSION` or use unsupported channel formats fail the build. `WIIUU_BUILD_CHANNEL=stable` supports deliberate local stable packaging; `release` remains an alias. Otherwise both build scripts produce betas. The UI library retains its independent `ui-library/VERSION`.

Update comparisons follow semantic-version precedence: beta.10 > beta.2, a final release > any prerelease of the same version, and build metadata is ignored. Legacy one/two-part stored versions remain comparable. Malformed update versions are rejected.

Settings > System > Updates controls `update.channel=stable|beta`. Stable is the default for stable installations; beta is the default for beta builds. Beta checks both stable and beta feeds and picks the newest valid release; stable never accepts a prerelease. Switching channels does not downgrade; install an older stable ZIP explicitly if that is desired.

The legacy stable feed stays at `/version.json`; beta uses `/beta/version.json`. Each includes an exact ZIP URL and SHA-256. Both channel downloads are assembled from published GitHub releases, rather than overwriting stable files with a development build. Historical stable releases without metadata are migrated from their ZIP; existing deployed branch releases are preserved during the first transition. Release-note cycle headings also apply to that cycle's betas without repeating on each beta update.

The optional [Node.js archive service](../../archive/README.md) listens on `127.0.0.1:8081` for Cloudflare Tunnel. Configure the repository variable `WIIUU_ARCHIVE_URL` and secret `WIIUU_ARCHIVE_TOKEN` after deploying it. CI then archives a release before publishing its feed; without configuration, GitHub Releases remains the download backend.

For a custom server, `update.stableUrl` and `update.betaUrl` override individual feeds. The historical `update.url` remains an explicit single-feed override, taking precedence over channel URLs.
