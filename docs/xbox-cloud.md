# Xbox Cloud Gaming (native desktop integration)

WII-UU can start [OpenXCloud](https://github.com/shyim/openxcloud), a GPL-3.0-only
Rust/WebRTC Xbox Cloud Gaming desktop client, from its own bottom dock. This is a
**native companion integration**, not a web view or a copy of Microsoft's servers.
It does **not** embed OpenXCloud's renderer inside WII-UU's Java UI.

## Download, install and launch

1. Open **WII-UU > Settings > System > Plugin store**, find **Xbox Cloud Gaming**
   and select **Download / update OpenXCloud**. WII-UU queries the latest stable
   release of [shyim/openxcloud](https://github.com/shyim/openxcloud/releases)
   and selects the native package for your operating system and CPU.
2. WII-UU downloads over HTTPS and verifies the exact file size and SHA-256 digest
   published in the official GitHub Release metadata. A missing digest or unknown
   download URL is rejected. Installation is performed off the UI thread, with
   progress and error messages in the plugin settings.
3. The verified package is extracted into **`<WII-UU home>/native/openxcloud/current`**.
   The previous managed installation is retained until the new one is committed,
   and is restored if installation fails. The executable path is then configured
   automatically. Clicking the action again checks for a newer release and
   reinstalls the official package; it never silently updates in the background.
4. Activate **Xbox Cloud Gaming** to add it to the bottom dock and launch it.
   OpenXCloud starts in a separate GPU-rendered window. Complete OpenXCloud's
   Microsoft account sign-in and select a game. WII-UU never sees Xbox credentials.

### Platforms and manual installs

| Platform | Package | Installation |
| --- | --- | --- |
| macOS Apple Silicon | `OpenXCloud-macos-arm64.zip` | Extract the `OpenXCloud.app` bundle using macOS `ditto`, retaining bundle metadata |
| Windows x64 | `OpenXCloud-windows-x64.zip` | Extract the complete portable app, including FFmpeg DLLs, without running an installer |
| Debian/Ubuntu x64 | `OpenXCloud-linux-amd64.deb` | Use `dpkg-deb --extract` within WII-UU's data directory; **no root access or package scripts** |

macOS Intel and Linux ARM64 have no matching upstream packages in this installer.
The macOS release is not notarized; Windows releases are not code-signed. macOS
and Windows may show security warnings. Downloaded programs are not launched
until you select the dock entry.

On Linux, extracting a Debian package does **not** install missing system
dependencies. OpenXCloud's Linux package is built on Ubuntu 24.04. If your
distribution lacks the FFmpeg or GPU runtime libraries it requires, the app
might not start. The verified `.deb` is also saved under the managed
`native/openxcloud` directory for optional system installation with
`sudo apt install ./OpenXCloud-linux-amd64.deb` from that directory.

You can also [build OpenXCloud from source](https://github.com/shyim/openxcloud#get-started)
or manually install it using the
[upstream packaging instructions](https://github.com/shyim/openxcloud/blob/main/PACKAGING.md).
**Choose OpenXCloud executable** accepts a native binary or macOS `.app` path.
The custom path is preserved until you explicitly install or update from the
WII-UU download action.

The **Low-latency video presentation** toggle starts OpenXCloud with
`--present immediate` (default) or `--present fifo` (V-sync). Immediate display
can reduce rendering latency but may tear. Video and audio decoding, frame
presentation, WebRTC transport and gamepad handling belong to OpenXCloud.

The Java UI does not send frames between processes and does not add a browser
renderer. If OpenXCloud is already running from the dock, selecting the entry
again will not start a second copy. Deactivating the entry terminates the
process WII-UU launched; independently started OpenXCloud instances are unaffected.

## Compatibility and limits

- OpenXCloud requires a Microsoft account entitled to Xbox Cloud Gaming, a
  compatible game, a supported region, a working graphical session and its native
  FFmpeg/GPU dependencies. Check OpenXCloud's own platform support documentation.
- **Phone GamePad input on Linux:** While OpenXCloud is running, WII-UU forwards
  its paired phone GamePad buttons and analog sticks to the existing virtual Xbox
  360 controller via uinput. WII-UU's background menu will not react to those
  inputs. The virtual pad is released when the stream process exits.
- In Linux console mode, WII-UU may exclusively grab a physical controller for
  its menu. The existing helper can map captured controller input to a virtual
  pad, but if that setup is unavailable, disable WII-UU's controller takeover
  and restart WII-UU so OpenXCloud can read the physical controller directly.
  macOS/Windows use OpenXCloud's native controller input; phone-to-virtual-pad
  forwarding remains Linux-only.
- The separate OpenXCloud window is not currently embedded in the TV/GamePad
  display. WII-UU's phone GamePad virtual controller can be used on Linux when
  created through its existing uinput helper; other platforms rely on the
  controllers supported by OpenXCloud.
- There is no browser fallback. Errors about missing executables are shown
  in WII-UU; errors inside the streaming client belong to OpenXCloud's logs.
- The OpenXCloud service protocol is unofficial and can change. WII-UU does
  not circumvent account eligibility, region restrictions or Microsoft security.

## Development / test

The Java launcher is in `src/wiiuu/core/XboxCloud.java`. The source-bundled
`resources/plugins/catalog.json` declares the only reviewed external native
entry. `PluginStore` only accepts the fixed `xcloud` external identifier and
starts the executable without a shell or command interpolation. No Electron
installation is needed for this entry.

Run `./build.sh` for WII-UU. The CI suite exercises `XboxCloudTest` and
`XboxCloudInstallerTest` across Linux, macOS and Windows using offline release
metadata and archive fixtures. The downloader is implemented in
`src/wiiuu/core/XboxCloudInstaller.java`.
For end-to-end tests, install OpenXCloud and check sign-in, an actual stream,
controller input, immediate/FIFO presentation and process cleanup on Linux,
macOS and Windows.
