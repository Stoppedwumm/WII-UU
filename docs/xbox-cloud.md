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

On **macOS**, WII-UU starts this *isolated* Chrome for Testing profile with
`--use-mock-keychain`. This prevents the native "Chrome Safe Storage"
Keychain authorization popup, which cannot be dismissed using a GamePad.
It is a Chromium testing option: cookies or browser secrets inside this
profile do **not** receive the normal macOS Keychain-backed protection.
Use this Chrome instance only for WII-UU's localhost Xbox streaming player,
not general web browsing or password storage. Microsoft device-code
credentials are handled separately by WII-UU's Node backend; they are
not stored in Chrome's Keychain.

Windows ARM64 and 32-bit Linux are not automatically supported.
The installer does not run as root and does not disable Chromium's
sandbox. Linux must have a graphical session, working video drivers,
\`node\` and \`npm\` on PATH. On a system with broken unprivileged user
namespaces Chromium may fail to start; WII-UU intentionally avoids
automatically adding unsafe \`--no-sandbox\` flags.

## Favorite games and quick launch

In the native Xbox game catalog, highlight a game with the controller and
press **X** to add/remove it from Favorites. Mouse and touch users can select
the star on a game tile. Favorites appear in a **quick-launch shelf** above
the catalog; choose a favorite with **A** to immediately start that title
without searching again. The shelf has left/right page buttons (or use L/R
when focused on the shelf) to reach additional favorites, up to 64.

Favorites are saved per WII-UU user under
`<user-home>/xcloud/favorites.json`. Their trusted Microsoft Store cover
images are cached as bounded PNG files in `xcloud/favorite-covers/`, so
artwork stays visible between restarts even when cover downloads are
unavailable. These favorites do not sync with your Microsoft account and do
not store Xbox login credentials. Removing a favorite also deletes its cached
cover. Existing WII-UU per-user backup/restore covers these files when the
user home is included.

Quick launch only starts titles present in the current live Xbox catalog;
games that no longer appear there are marked Unavailable rather than trying
to start using a stale entitlement. Reload the catalog to refresh availability.

## GamePad display during Xbox Cloud streams

The phone GamePad treats the isolated Chromium Xbox player as a running game,
even though it was not launched through the normal emulator launcher.
Opening a stream therefore automatically switches the GamePad's center LCD
to its TV mirror if screen capture is enabled. The existing TV button remains
available for switching manually, and closing the player returns the phone
to Home. Without screen capture, the phone shows Now Playing rather than
the library while the game runs.

The screen streamer locates the window titled `WII-UU Xbox Cloud Player`
and captures that rectangle, not the WII-UU menu or the entire desktop. The
same screen-capture permissions and performance limitations as other streamed
WII-UU game windows apply. Xbox media stays in Chromium; the phone's
picture uses the existing MJPEG GamePad screen channel and does not start a
second Xbox Cloud session.

## WII-UU controller-manager integration

Xbox Cloud Gaming now uses the same live controller-manager output as WII-UU games:
**Settings > GamePad & players > Controller buttons** remaps are applied once,
inside WII-UU, before the input reaches the stream. The current player order
(Controllers/phone GamePads) determines Xbox slots 1–4. Phone controllers,
managed USB/Bluetooth controllers and their analog sticks can therefore work
even on macOS, where Linux `uinput` controllers do not exist.

The Java application forwards only numeric button and axis frames over its
private stdin channel to the Xbox helper. The Chromium page retrieves those
frames through a session-key-protected localhost endpoint. Controller names,
persistent IDs, WII-UU configuration and Microsoft tokens are not sent to
the player page. When a managed controller is available it overrides the raw
Chromium Gamepad API route, avoiding duplicate input from a physical controller
and its Linux virtual pad. If WII-UU has no managed controllers, the existing
Chromium gamepad support is used as a fallback.

WII-UU face-button names represent physical positions (A east, B south,
X north, Y west), while Xbox uses A south, B east, X west, Y north.
The bridge translates these positions accordingly. HOME maps to Xbox Guide;
View + Menu simultaneously also sends Guide. Up to four player slots are
forwarded; viewers and higher-numbered local players are ignored. Controller
disconnects and player changes release previously held buttons.

Debug: the Chromium console should show
`[xcloud-gamepad] WII-UU controller manager active` and
`[xcloud-gamepad] WII-UU player 1 attached to Xbox slot 0`
after the WebRTC input handshake. This route needs WII-UU's GamePad
server to be running; on an installation without it, the standard browser
gamepad fallback remains available.

## Controller routing

Existing Linux phone GamePads and WII-UU-managed physical controllers
still flow through \`/dev/uinput\`. During the cloud stream, the
Chromium player process is tracked as a running external game so
those inputs do not activate the WII-UU home menu. The game's player
uses the W3C Gamepad API; the bundled Switch mapper remaps the
specific measured \`20d6:a713\` device to an Xbox-style layout.
Other controllers retain their standard mapping.

The player automatically forwards up to four standard Gamepad API controllers to
Xbox input slots 0–3. Existing WII-UU Linux `/dev/uinput` virtual controllers
are supported if Chromium can see them through `navigator.getGamepads()`.
The `20d6:a713` mapper converts its nonstandard button/axis layout into
standard gamepad positions. Face buttons, shoulders, analog triggers,
stick-clicks, both analog sticks, D-pad, View/Menu and Home/Nexus are
handled. When Home is absent, pressing View + Menu together sends Nexus.

Controllers can be connected and disconnected during a stream without
restarting it. Input frames are flushed at the WebRTC input tick instead of
waiting for unrelated video metadata. The Chromium console prints
`[xcloud-gamepad]` messages after the input handshake and on each
controller attach/detach. If nothing appears, focus the Chromium stream
window and check that the controller is exposed to the browser Gamepad API.
Some device drivers expose fewer than 17 buttons; missing buttons remain
released rather than causing a stream error.

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

## Microsoft pairing versus Xbox game access

When you finish the code at `microsoft.com/link`, WII-UU recognizes
Microsoft authorization separately from subsequent Xbox cloud streaming access.
The pairing card changes to the game catalog after the Microsoft user token
is returned, even if Xbox entitlement or game discovery later fails. While the
code is displayed, WII-UU periodically checks the **local** authentication
helper; **I've signed in** performs an immediate local recheck. The message
\"Sign in first\" should not appear simply because you tried loading games
while the pairing code was still pending.

The local Node helper is refreshed from the installed WII-UU resources when
Xbox Cloud Gaming next opens. **You do not need to re-download Chromium**, and
existing Microsoft account tokens are left untouched. Restart WII-UU after
installing the updated version to use the updated authorization helper.

An accepted Microsoft device code does not itself guarantee Xbox Cloud Gaming
entitlement or a successful stream; any subsequent failure should be presented
as a separate Xbox access or catalog error, not as a sign-in failure.

## Troubleshooting Microsoft device-code approval

The local Node helper writes privacy-limited authentication diagnostics to
`backend.log` in WII-UU's Xbox runtime directory (next to `service.cjs`).
This log includes `[xcloud-auth]` milestones: `device-flow-issued`,
`device-poll-start`, periodic `device-poll-waiting`, `device-poll-resolved`,
`user-token-saved`, `signed-in-event-sent`, and `native-status-check`.
It also records catalog attempts separately from Microsoft sign-in.

If Microsoft shows that `Xbox Web` is signed in but WII-UU stays on the
pairing screen, reopen the Xbox Cloud Gaming view and inspect the latest
`[xcloud-auth]` entries. `device-poll-waiting` after Microsoft approval
indicates the helper has not received a completed device-code exchange.
`device-poll-resolved` without `user-token-saved` indicates a token-store
problem. `signed-in-event-sent` with `native-status-check signedIn=true`
indicates the Java UI should advance; if it does not, investigate the UI
callback/bridge. Logging is deliberately limited to stages and booleans:
never paste OAuth credentials, device codes, or `xcloud-tokens.json` in bug
reports.
