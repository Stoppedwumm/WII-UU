# Xbox Cloud Gaming: native WII-UU login and portable WebRTC player

WII-UU 2.1.8 replaces the Greenlight desktop companion with a **native
Java interface** for Microsoft device-code sign-in and game selection. A
separate portable Chrome for Testing process performs WebRTC media playback
and controller capture. Greenlight is no longer needed.

## Start

1. In WII-UU open **Settings > System > Plugin store**.
2. Select **Install / update Xbox Cloud runtime**. This explicitly installs
   the pinned \`xal-node\` and \`xbox-xcloud-player\` dependencies and
   a small \`esbuild\` bundler; it also downloads the **official stable
   portable Chrome for Testing** package for this OS and CPU.
3. Activate **Xbox Cloud Gaming** to keep it in the bottom dock.
4. Open the Xbox Cloud Gaming dock item. The device-code login and game list open **inside the main WII-UU TV/GamePad interface**, using the active WII-UU theme.
5. Select **Get Microsoft login code**. On your phone or another PC,
   visit the verification URL shown in the native window and enter the
   displayed code. Complete the sign-in on Microsoft's website, not in
   an unofficial password dialog.
6. After authorization, WII-UU requests the playable game catalog.
   Search for a title, then select **Play selected game**. A separate
   portable Chromium window performs the WebRTC streaming session.

The username and password are entered **only on Microsoft's site**.
The Xbox streaming service decides availability, entitlement, queue
time and supported geographic regions.

## Architecture

- \`src/wiiuu/ui/XboxCloudNativeScreen.java\`: native account code display,
  game list, search, and launch/stop controls. The player window is separate.
- \`src/wiiuu/core/XboxCloudRuntime.java\`: explicit installer and platform
  selection. Uses the Chrome for Testing official release index, restricts
  download URLs to Google's release bucket, limits archive size and verifies
  ZIP paths. GitHub does not supply an asset SHA-256 in that index, so this
  browser download currently has **HTTPS origin validation, not a
  published SHA-256 check**.
- \`resources/xcloud/service.cjs\`: a localhost-only helper using
  \`xal-node\` for device-code authorization and streaming grants.
  It sends structured messages to Java via stdin/stdout; Xbox tokens
  are never sent to Chromium or included in WII-UU's logs.
- \`resources/xcloud/player-entry.js\`: uses the third-party
  \`xbox-xcloud-player\` WebRTC engine to exchange SDP/ICE and render
  video/audio directly in the separate browser process.
- \`resources/xcloud/mapping-core.js\`, \`page-hook.js\`: the
  \`20d6:a713\` controller mapping supplied for WII-UU. These scripts
  run inside the *local player page*, before its WebRTC code. They do
  not modify global browser installations or unrelated websites.

The backend binds exclusively to **127.0.0.1** and requires an unpredictable
session key in the local player request headers. The key is provided in
a URL fragment and is not a Microsoft token. The browser uses an isolated
WII-UU user profile. Credential grants are stored only in the WII-UU
user data folder, in a file restricted to the current user on Unix.

Native browsing controls are integrated into the WII-UU menu; this does
**not** include a native Java WebRTC decoder. The media plane runs in
Chromium to use its hardware decode, WebRTC stack and Gamepad API.

## Supported desktop packages

| Platform | Portable Google browser |
| --- | --- |
| Linux ARM64 / AArch64 | \`linux-arm64\` |
| Linux x86-64 | \`linux64\` |
| macOS Apple Silicon | \`mac-arm64\` |
| macOS Intel | \`mac-x64\` |
| Windows x64 | \`win64\` |

Windows ARM64 and 32-bit Linux are not automatically supported.
The installer does not run as root and does not disable Chromium's
sandbox. Linux must have a graphical session, working video drivers,
\`node\` and \`npm\` on PATH. On a system with broken unprivileged user
namespaces Chromium may fail to start; WII-UU intentionally avoids
automatically adding unsafe \`--no-sandbox\` flags.

## Controller routing

Existing Linux phone GamePads and WII-UU-managed physical controllers
still flow through \`/dev/uinput\`. During the cloud stream, the
Chromium player process is tracked as a running external game so
those inputs do not activate the WII-UU home menu. The game's player
uses the W3C Gamepad API; the bundled Switch mapper remaps the
specific measured \`20d6:a713\` device to an Xbox-style layout.
Other controllers retain their standard mapping.

Press Escape in Chromium to show/hide player controls. Leaving the Xbox Cloud Gaming screen ends its streaming process. The separate Chromium window also provides Disconnect.

## Compatibility caveats

The Xbox authentication and WebRTC service endpoints are unofficial
third-party implementations, modeled after the MIT-licensed Greenlight
and xbox-xcloud-player libraries. Microsoft may change these APIs,
deny a client registration's streaming grants, or require additional
network/codec configuration.

**Automated tests can verify architecture, package metadata, ZIP
boundaries, service responses and build compatibility, but they cannot
prove a successful Microsoft grant, entitlement or playable Xbox cloud
stream.** Device-code approval and actual cloud gameplay must be
tested manually with an eligible Microsoft account and real hardware.

The browser downloads are large, and the first-time dependency
installation downloads npm packages; subsequent launches reuse
the local installation. Existing Greenlight files and tokens are not
deleted automatically.

## WII-UU interface

The Xbox Cloud Gaming dock entry opens a full-screen WII-UU view instead of
creating a second Swing window. It uses the same menu theme colors, typefaces,
rounded tiles and selection highlighting. Use D-pad or left stick to navigate,
A to select, B/HOME to return, L/R to change catalog pages, and click/tap for
pointer selection. The **Search** tile opens an on-screen character keyboard
which can be operated with a gamepad, or typed into using a physical keyboard.

Pairing uses a large device-code card with copy and reload actions. Games are
shown as responsive multi-column tiles. The last action in the screen opens
the separate Chromium WebRTC media player; authentication and catalog remain
in WII-UU's native rendering engine.
