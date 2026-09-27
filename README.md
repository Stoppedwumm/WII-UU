# WII-UU

A Wii U Menu–style emulator frontend written in Java, with a built-in web server that turns
any phone into a Wii U–like GamePad. There are no dependencies beyond Java 17+.

![Menu](docs/menu.png)
![Phone GamePad](docs/gamepad.png)

It covers consoles from the NES to the Wii U and Switch, and Sony up to the PS4:

| Nintendo | Sega | Sony |
|---|---|---|
| NES, SNES, Game Boy/Color, N64, GBA, GameCube, DS, Wii, 3DS, **Wii U**, **Switch** | Master System, Genesis/Mega Drive, Saturn, Dreamcast | PS1, PS2, PSP, PS3, **PS4** |

WII-UU is a **launcher**: it finds your games, shows them in the Wii U–style UI and starts
the right emulator for each one. Each emulator (Mesen, Snes9x, Mupen64Plus, Dolphin, Cemu,
Ryujinx, DuckStation, PCSX2, PPSSPP, RPCS3, shadPS4 …) is installed separately. You also need
your own legally dumped games and, where an emulator requires it, your own firmware or keys.

## Install

Download `WII-UU-1.0.0.zip`, unzip it, then:

* **Linux / macOS:** `./install.sh`
  (on Linux, `./install.sh --with-emulators` also builds the emulators from source and
  configures them for you; see below.)
* **Windows:** double-click `install.bat`

The installer:

* checks for Java 17+ and offers to install it (apt, dnf, pacman, zypper, Homebrew or winget)
* installs the app and adds a `wiiuu` command plus a menu, Start-menu or Desktop shortcut
* creates `~/WiiUU/roms/<system>` folders
* on Windows it adds a firewall rule when run as administrator; on Linux it prints the `ufw` or
  `firewalld` command to run

To uninstall, run `./install.sh --uninstall` or `install.ps1 -Uninstall`. Your ROMs and settings are kept.

## Emulators built from source (Linux, Raspberry Pi)

`emulators.sh` (installed as `wiiuu-emulators`) clones each emulator's newest source, compiles
it natively for the machine (x86-64 or 64-bit ARM such as a Raspberry Pi 4/5), and sets
WII-UU's command for the matching systems. It doesn't use Flatpak or containers.

```sh
wiiuu-emulators              # light set: FCEUX, Snes9x, mGBA, Mupen64Plus, Mednafen, melonDS, PPSSPP, Flycast
wiiuu-emulators --all        # + Dolphin, Azahar, Cemu, Ryujinx, DuckStation, RPCS3 (and shadPS4 on x86-64)
wiiuu-emulators --only ppsspp,dolphin
wiiuu-emulators --update     # pull the newest source of everything built so far and rebuild
wiiuu-emulators --list       # what is available / already built on this machine
```

* **What it does:**
  * installs build dependencies with apt (Raspberry Pi OS, Debian, Ubuntu). It only requests
    the packages your release actually has.
  * picks the number of parallel compile jobs from your RAM, so a Pi doesn't run out of memory.
  * logs each build to `~/.wiiuu/logs/build/<name>.log`.
  * keeps going when one emulator fails, and prints a summary at the end.
* **Where things go:** source is kept in `~/.local/share/wiiuu-emulators/emu-src` and the
  builds in `.../emulators`. Uninstalling WII-UU keeps them.
* **The light set** builds in minutes to about an hour on a Pi 5 and runs well there.
* **The heavy set** can take several hours on a Pi and needs about 4 GB of swap on boards with
  under 6 GB of RAM. Those consoles are mostly too demanding for a Pi to play well anyway.
* **Not built:**
  * **PCSX2 (PS2)** only runs on x86-64. On a PC, use its official AppImage.
  * **shadPS4 (PS4)** also needs an x86-64 CPU.
* **Other distros:** without apt, install the dependencies yourself first.

## Use

1. Copy games into `~/WiiUU/roms/<system>/`. The folder names are `nes snes gb n64 gba gc nds wii 3ds wiiu
   switch sms genesis saturn dc ps1 ps2 psp ps3 ps4`.
   * PS3 and PS4 games are folders. WII-UU finds the `EBOOT.BIN`/`eboot.bin` inside each one.
   * Wii U: `.wua`, `.wud`, `.wux`, or the `code/*.rpx` of an unpacked game.
   * Covers: put `covers/<game name>.png` (or `.jpg`) in a system folder, or place the image next to the ROM
     with the same name. PS3 `ICON0.PNG` and PS4 `sce_sys/icon0.png` are used automatically.
2. Start WII-UU and press **F1** (Settings). Check each system's emulator command, or use
   *Emulator program…* to pick the executable. In a command, `{rom}` is the game file, `{dir}`
   is its folder and `{name}` is its title.
3. Choose a system, then a game, and press Enter or A.

| Keyboard | GamePad | Action |
|---|---|---|
| Arrows / WASD | D-pad / left stick | Move |
| Enter / Space | A | Open / play |
| Esc / Backspace | B | Back |
| PgUp / PgDn, Q / E | L / R, ZL / ZR | Change page |
| F2 | + | Show the GamePad QR code |
| F5 | − | Rescan games |
| F1 | – | Settings |
| F11 | – | Toggle fullscreen |
| Ctrl+Q | HOME → Close game | Quit the running game |

## Phone as GamePad

Press **+** or **F2** on the TV and scan the QR code. You can also open the URL shown there on a phone
connected to the same Wi-Fi and enter the 4-digit pairing code.

The page is laid out like a Wii U GamePad. It has two analog sticks (tap a stick for L3 or R3),
a D-pad with diagonals, ABXY, L/R/ZL/ZR, −, HOME and +. You can slide a finger from one button
to another. It vibrates on each press, keeps the screen awake, and can go fullscreen or be added
to the home screen.

The GamePad's screen shows your library, and tapping a game starts it on the TV. While a game
runs, it shows *Now Playing*, and HOME opens a menu with *Close game*. Up to 4 phones can connect,
and each one is assigned a player number (P1–P4).

**How input reaches emulators:** while a game runs, WII-UU types keyboard keys into the focused
emulator window. Set each emulator's keyboard controls to match. The defaults follow RetroArch,
and you can change them under *Settings → GamePad Keys*:

| Button | P1 key | P2 key |
|---|---|---|
| D-pad | Arrow keys | 8 / 5 / 4 / 6 |
| A B X Y | X Z S A | 3 2 9 1 |
| L R ZL ZR | Q W E R | 7 0 O P |
| + / − | Enter / Shift | N / M |
| Left stick | T F G H | 8 5 4 6 |
| Right stick | I J K L | – |
| L3 / R3 | C / V | B / Y |

Notes:

* **Linux Wayland:** keys reach X11/XWayland windows. Most emulators accept them, and Qt apps
  can be forced with `QT_QPA_PLATFORM=xcb`.
* **macOS:** allow Java under *Privacy & Security → Accessibility*.

## Settings file

Settings are stored in `~/.wiiuu/config.properties`. You can move this folder with `WIIUU_HOME` or `--home`.
Useful keys:

```properties
roms.base=/home/me/WiiUU/roms
system.wiiu.command="/opt/Cemu/Cemu" -f -g {rom}
system.ps2.romdir=/mnt/games/ps2
system.sms.hidden=true
server.port=8080
server.requireCode=true
server.code=1234            # fixed pairing code (random each start if unset)
keys.p1.A=X
ui.fullscreen=true
ui.minimizeOnLaunch=true
```

Command line: `wiiuu [--fullscreen|--windowed] [--port N] [--no-server] [--home DIR]`

## Build from source

```sh
./build.sh      # -> build/wiiuu.jar   (JDK 17+)
./package.sh    # -> dist/WII-UU-<version>.zip
java -jar build/wiiuu.jar
```

The code is in `src/wiiuu`:

* `core`: system catalogue, ROM scanner, config and emulator launcher
* `ui`: the Wii U–style Swing menu and the settings dialog
* `net`: the GamePad HTTP server (`com.sun.net.httpserver`) and a small QR encoder
* `input`: routes GamePad input to menu navigation, or to key presses via `java.awt.Robot`

The phone page is `resources/web/pad.html`.
