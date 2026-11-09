# Local integration API and OEM setup

WII-UU and standalone Choccy Milk expose the same versioned HTTP API for local launchers, appliance shells and other programs. The app window and its emulator still run in the user's graphical session. The API is independent of the WII-UU phone GamePad server and its relay.

## Enable the API

Close the apps before editing the active data directory's config.properties. By default this is ~/.wiiuu. Both apps also support --home DIR.

```properties
integration.wiiuu.port=18770
integration.choccy.port=18771
```

Each port defaults to 0 (disabled). Assign separate ports when running both apps. Generate a per-device bearer token before starting either app. Keep the token file readable only by the console account and grant access to a companion app only if it should be able to start and stop games.

```sh
data="$HOME/.wiiuu"
mkdir -p "$data"
(umask 077; set -C; openssl rand -hex 32 > "$data/integration.token")
```

If the token already exists, keep it. Each app reads it at startup. On Windows, use the console account's file permissions and write a cryptographically random 32-byte value as 64 hexadecimal characters. Requests must use the literal loopback host 127.0.0.1, the configured port, and an Authorization: Bearer header. A client on a different machine should use WII-UU's existing GamePad pairing and relay flow.

## HTTP contract

Base URL for WII-UU in the example is http://127.0.0.1:18770/api/v1/. Change the port to 18771 for Choccy Milk. POST requests take an empty body.

| Method and relative path | Success | Response or effect |
| --- | --- | --- |
| GET status | 200 | JSON with app (wiiuu or choccy), version, and game (null or a game object). |
| GET games | 200 | JSON with a games array. Each game has system, id, and name. Choccy Milk lists systems for which it has a core catalogue. |
| POST launch/{system}/{id} | 202 | Starts the game or queues a retry while Java or a core is installed. Use IDs from the current library. |
| POST stop | 202 | Requests the app's normal game shutdown and cancels a pending dependency retry. Safe to call while idle. |
| POST rescan | 202 | Requests an asynchronous library rescan. Poll games for the new list. |

A 202 response is {"accepted":true}. Poll status for the running game. A queued launch may take longer or fail if dependencies are unavailable. An API launch made while a game is running returns 409. A new request while a dependency download is pending supersedes the previous launch. Failures have a JSON error code. Missing or invalid credentials return 401, unknown routes or games return 404, wrong methods return 405, and a rejected launch returns 409.

```sh
base=http://127.0.0.1:18770/api/v1
token=$(cat "$HOME/.wiiuu/integration.token")
curl -fsS -H "Authorization: Bearer $token" "$base/games"
# Use the system and ID from the returned library.
curl -fsS -X POST -H "Authorization: Bearer $token" "$base/launch/nds/GAME_ID"
curl -fsS -H "Authorization: Bearer $token" "$base/status"
```

## Provision a Linux console image

1. Start with a Linux desktop image supported by [Console Mode](../README.md), and create a dedicated unprivileged console account. Use an X11 session for the existing TV, emulator and GamePad integration.
2. From the pinned WII-UU package, run the installer as that account with sudo available. For example, use `./install.sh --yes --oem --no-shortcut --roms "$HOME/WiiUU/roms"`. For an existing installation run `sudo wiiuu-console install --user console --oem --yes`, replacing console with the account name.
3. Populate the library with content you can distribute. Pre-stage the correct emulator cores and system files for the device architecture. Use a Java 22+ runtime for Choccy Milk playback and test cold launch offline so the first customer boot does not depend on downloads.
4. Optionally provision a .wtheme in the data directory's themes folder and select it through ui.theme. Enable the integration API only if a local companion needs it. Generate the bearer token per device and pair GamePads per device after imaging.
5. Reboot a test unit and verify fullscreen startup, controller input, audio, a game launch, the TV/GamePad display split and Desktop Mode recovery. OEM mode skips automatic setup and update dialogs. Explicit setup remains available with `wiiuu --appliance --setup --no-intro`. Maintain the installed version using your image or update process.

For a custom session manager use `wiiuu --appliance`. A standalone Choccy Milk station can use `choccymilk --fullscreen --no-intro` under its graphical session manager.
