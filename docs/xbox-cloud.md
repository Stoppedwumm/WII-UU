# Xbox Cloud Gaming through Greenlight

WII-UU's **Xbox Cloud Gaming** dock app now launches
[Greenlight](https://github.com/unknownskl/greenlight), an open-source desktop
client for Xbox Cloud Gaming and home-console streaming. The streaming
window belongs to Greenlight, not to WII-UU's Java interface.
Greenlight uses Electron/Chromium and therefore needs more memory than
the previously used native Rust OpenXCloud client. The trade-off is the
availability of official Linux **AArch64** and x86-64 packages through Flathub.

## Install or update from WII-UU

1. Go to **Settings > System > Plugin store > Xbox Cloud Gaming**.
2. Select **Download / update Greenlight**.
3. WII-UU chooses the supported distribution for the host:
   - **Linux AArch64 or x86-64:** installs Greenlight's verified Flathub
     application `io.github.unknownskl.greenlight` to the current user's
     Flatpak installation; already user-installed copies are updated.
     Flatpak itself must already be installed. WII-UU adds the official
     per-user Flathub remote when necessary; it does not require `sudo`.
   - **macOS Intel or Apple Silicon:** downloads the official universal
     Greenlight DMG from GitHub Releases, checks its SHA-256 digest and
     copies `Greenlight.app` with `ditto` into WII-UU's data directory.
   - **Windows x64:** downloads the official Greenlight portable ZIP,
     checks SHA-256 and extracts the entire Electron app without running
     a Windows installer. Windows ARM64 is not automatically supported.
4. Activate **Xbox Cloud Gaming** in the plugin store. Launch it from the
   bottom dock. Sign in within Greenlight. WII-UU does not see or store
   Microsoft account tokens.

On Linux, Greenlight is launched with:

```sh
flatpak run io.github.unknownskl.greenlight
```

The install action uses the equivalent of:

```sh
flatpak remote-add --user --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
flatpak install --user --noninteractive -y flathub io.github.unknownskl.greenlight
```

Linux Flatpak applications are installed under Flatpak's normal user
installation, **not** the WII-UU native app directory. Updates are provided
by Flathub. A system-wide Flatpak installation can also be launched; an
explicit install through WII-UU creates a user-managed copy.

On macOS and Windows, verified packages are extracted into
`<WII-UU data directory>/native/greenlight/current`. Upgrades temporarily
preserve the previous copy and roll back the executable settings and
files if an installation fails. The downloader checks exact asset
filenames, official GitHub release URLs, advertised sizes and SHA-256,
and stops stalled transfers. Downloading never launches the app.
Installation on macOS/Windows does not execute downloaded installer
scripts or require administrator rights.

Greenlight's releases are not necessarily notarized or code-signed.
OS security warnings may occur. A real streaming session requires
compatible graphics drivers, a desktop session, and appropriate Xbox
Cloud Gaming access.

## Existing installations and migration

The built-in catalog ID stays `xcloud` so your dock activation preference
is preserved. However, the **old OpenXCloud executable path and low-latency
`--present` settings are intentionally ignored**. Those flags do not apply
to Greenlight.

For a custom Greenlight install, use **Choose existing Greenlight executable**
or specify an absolute path / macOS `.app` bundle in the plugin store.
Custom paths take priority over Flatpak; click the install action to
return to the managed build.

The prior OpenXCloud files, if installed, remain on disk and are not
launched by the new integration. WII-UU does not delete third-party
files or Microsoft credentials as part of migration.

## Controllers and performance

- While Greenlight is launched from WII-UU, the existing cloud-input
  guard keeps phone GamePad input from operating the WII-UU home menu.
  On Linux, WII-UU's virtual Xbox controller can provide game input.
  **Flatpak sandbox access to the controller depends on the host's
  device permissions and Greenlight's sandbox permissions**; validate
  controller input on your own machine.
- WII-UU can grab physical controllers in console mode. If Greenlight
  cannot see a controller, disable WII-UU's controller takeover and
  restart before using it.
- Greenlight renders in its own Electron window; it is not embedded in
  WII-UU's TV/GamePad output. Hardware acceleration depends on the
  installed GPU drivers, media stack and Flatpak runtime.
- Greenlight already provides Xbox streaming functionality. This
  integration does not emulate Microsoft's cloud backend, bypass any
  subscription or region restrictions, or expose sign-in tokens to WII-UU.

## Development

`src/wiiuu/core/Greenlight.java` owns launch and discovery.
`src/wiiuu/core/GreenlightInstaller.java` owns Flathub and native
package installation. The plugin catalog still allows only the reviewed
`xcloud` external identifier rather than arbitrary executables.

GitHub Actions runs `GreenlightTest` and `GreenlightInstallerTest`
across Linux, macOS and Windows. Tests check architecture routing,
release URL/digest validation, ZIP traversal protection and installation
layout without downloading third-party apps. A manual ARM64 Linux
device test must still verify Flatpak download, launch, Xbox login,
controller passthrough and a playable stream.
