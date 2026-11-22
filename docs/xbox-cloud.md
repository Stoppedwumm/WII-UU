# Xbox Cloud Gaming (native desktop integration)

WII-UU can start [OpenXCloud](https://github.com/shyim/openxcloud), a GPL-3.0-only
Rust/WebRTC Xbox Cloud Gaming desktop client, from its own bottom dock. This is a
**native companion integration**, not a web view or a copy of Microsoft's servers.
It does **not** embed OpenXCloud's renderer inside WII-UU's Java UI.

## Install and launch

1. Install or build OpenXCloud on your own computer. Follow its [build instructions](https://github.com/shyim/openxcloud#-get-started)
   and [platform packaging instructions](https://github.com/shyim/openxcloud/blob/main/PACKAGING.md).
   Use a build from a trusted source and verify its origin. WII-UU does not download
   arbitrary binaries, include the GPL-licensed project, or run an installer for you.
2. In **WII-UU > Settings > System > Plugin store**, find **Xbox Cloud Gaming**.
   Enable it. It appears in the bottom dock like other apps.
3. WII-UU searches `PATH`, standard installation paths, `~/.cargo/bin`, and
   `/Applications/OpenXCloud.app` on macOS. If it says **Not installed / not detected**,
   use **Choose OpenXCloud executable** to select the binary, or enter an absolute
   path in **Executable path / macOS app bundle**. The latter also accepts a macOS
   `.app` bundle path.
4. Launch Xbox Cloud Gaming from the dock. OpenXCloud starts in a separate native
   GPU-rendered window. Sign in using OpenXCloud's Microsoft device-code flow;
   WII-UU does not request or store Xbox tokens. Choose a game in OpenXCloud.

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
- In Linux console mode, WII-UU may exclusively grab a physical controller for
  its menu. If OpenXCloud cannot see it, disable WII-UU's controller takeover
  and restart WII-UU, or use a virtual Xbox pad provided by the GamePad server.
  Direct controller handoff between the two processes is not implemented.
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

Run `./build.sh` for WII-UU and `java -cp build/classes wiiuu.core.XboxCloudTest`
after compiling `test/wiiuu/core/XboxCloudTest.java` against `build/classes`.
For end-to-end tests, install OpenXCloud and check sign-in, an actual stream,
controller input, immediate/FIFO presentation and process cleanup on Linux,
macOS and Windows.
