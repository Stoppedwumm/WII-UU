# Changelog

What changed in each WII-UU release, newest first. WII-UU shows these notes after an update,
in Settings > General > What's new, and before installing an update.

## 1.10.4 (2026-10-13)
- Videos (OpenBased, Reactions): **HOME stops the video** and goes back to the menu, also on a
  controller plugged into the computer. Before, there was no way out of mpv with just a controller.
  The position is saved as always, so it continues where you stopped. On a Mac, controllers now
  work in the video player too.

## 1.10.3 (2026-10-13)
- Controllers plugged into the PC keep working when unplugged and plugged in again. Before, a
  replugged controller could stop after a press or two: it comes back on the same device node, and
  each connection now has an id of its own, so the old connection ending can't take the new one with
  it. A controller is also picked up faster (twice a second), tried again when it can't be opened
  the moment it appears (while its permissions are still being set), and the menu's own controller
  reader leaves new ones to WII-UU's helper instead of racing it. On a Mac a replugged controller
  can't be mistaken for the one before it.

## 1.10.2 (2026-10-13)
- OpenBased: Sign out revokes WII-UU's token on the server when it was linked with a code (OpenBased's
  new DELETE /api/v1/tokens/current), so there's nothing left to clean up on the API tokens page.
  Servers from before that say so and point you there.

## 1.10.1 (2026-10-13)
- OpenBased: **Link with a code** (Settings > OpenBased, or on the phone). WII-UU shows a code, you
  approve it in OpenBased's web app (the phone's Approve on OpenBased button opens the page), and
  that's it: no more adding WII-UU to OpenBased's application.yml, and a new IP address doesn't
  break anything. WII-UU's token is called "WII-UU on (computer name)" on OpenBased's API tokens
  page, where it can be revoked. Older servers can still sign in with OpenBased's page.
- The cinema takes stories, backdrops, genres and your progress straight from OpenBased's lists
  when the server sends them, so browsing no longer asks for every title separately.
- A Recently added row in the cinema, newest first.

## 1.10.0 (2026-10-12)
Everything new since 1.9, in one place (the 1.9.x notes below have the details).

**Choccy Milk, WII-UU's own game player**
- Games play inside WII-UU's window with libretro cores: no second window, no RetroArch needed,
  and no Java of your own either (it downloads its cores, and Java 25 once, by itself).
  *Settings > Games > Play with Choccy Milk* switches it on for every console it has cores for.
- A program of its own too (`choccymilk`, or ChoccyMilk.jar from the website), with its own look
  (Cocoa in dark mode, Milk in light mode), a start animation and its own icon. WII-UU and Choccy
  Milk share one engine library, and their settings, games, saves and cores.
- N64 with the software renderer, fast enough for a Raspberry Pi 5; if the recompiler crashes it
  switches to the interpreter by itself and says how to fix it (kernel8.img). Native on Apple
  Silicon even when WII-UU runs through Rosetta.
- Sound at the right pitch (resampled to 48 kHz), frames skipped instead of crackling when the
  computer can't keep up, and the GamePad shows the game's own picture at up to 60 frames a second.
- Save states (F2 / F4, ten slots with F6 / F7, and buttons on the phone), and the game's own saves
  (like Super Mario 64's stars) written within a second of saving.
- RetroAchievements unlock while you play (softcore), with a trophy card on the TV and a notice on
  the phones; ROM versions RetroAchievements doesn't support are named as such.
- Upscaling per console (Scale2x / Scale3x for pixel art, Smooth, PlayStation 3D at 2x / 4x) and
  widescreen (PlayStation 3D games and the Mega Drive really widen, other consoles stretch).

**Controllers, players and several WII-UUs**
- USB and Bluetooth controllers are players of their own, like phones: on Linux and Raspberry Pi as
  virtual Xbox 360 controllers every emulator is set up for, on a Mac in the menu and in Choccy Milk.
  They steer the menu too, and can be remapped button for button.
- Pick your player number on the phone (tap P1), or set the order on the TV (*Player order*).
- Several WII-UUs on one network link up with a pairing code: send the controllers plugged into one
  to the other (a Raspberry Pi's USB pad playing on your MacBook), keep Choccy Milk's saves in sync,
  and copy the games one has and the other doesn't.
- The phone says when WII-UU isn't running (and why), and reconnects by itself; sign in to accounts
  by typing on the phone instead of the TV.

**Watching**
- OpenBased: your own movies and series from an OpenBased media server, signed in with OpenBased's
  own sign-in page, continuing where you stopped, with the GamePad as remote. Its channel is a
  cinema now: dim lights, the focused title's backdrop and story on top, rows of Continue
  watching, Series and Movies below, and seasons and episodes for each series.
- Reaction mode: the WII-UU-Reactions editor makes video packs where WII-UU reacts on a timeline,
  played in the Reactions channel with WII-UU saying each reaction on the GamePad.

**Achievements and covers**
- RetroAchievements progress on every game's tile, found by the ROM's hash; after playing, WII-UU
  says what you unlocked. RetroArch mode signs RetroArch in too, with an optional hardcore mode.
- Covers found online for games without one (libretro thumbnails, or ScreenScraper with an account).

**The menu**
- Settings drawn on the TV, for the phone, controllers, keyboard and mouse, with an on-screen
  keyboard and a file browser.
- Themes in WII-UU's own theme language (.wtheme files that redraw the menu as you save them), and
  seven that come with it: Sunset, Choccy Milk, Midnight Arcade, Game Boy, Famicom, Ocean and Paper.
- A setup guide for the first start, opened by an intro on its own beat, with a live controller
  test; WII-UU itself, a googly-eyed helper, walks you through it out loud and keeps you company in
  the menu.
- Menu music: more tunes, each also as a WII-UU, Future House and Color House remix, the non-stop
  Extended Mix, a music visualizer (V), and your own music files.
- When nobody plays, WII-UU does: Tetris and Pong (and cheats when it's losing, if you don't catch
  it), the free ROM website, online shopping, decorating the menu, fan mail, late-night reading and
  making music. Plus a hidden Developer tab, a boss fight and a secret code.
- On Linux and Raspberry Pi the menu is drawn with the graphics chip when there's a driver for one.

**Emulators**
- Split DS / 3DS screens: the top screen on the TV, the touch screen on the phone (Linux, and
  Windows with a virtual display).
- RetroArch: the phones' controller layout set up for it, the Snap version understood, borderless
  full screen, and nothing WII-UU sets for a game left behind in RetroArch's own settings; N64
  switches to the software renderer or the interpreter by itself where OpenGL or the recompiler fail.
- Mupen64Plus: the phone's buttons laid out right, GLideN64, OpenGL ES on the Raspberry Pi, and the
  picture filling the screen.
- Mac: the computer's sound and the menu music on the phone, the phone making the menu music
  itself; Windows: WII-UU's logo on its shortcuts and taskbar button.
- What's new: these notes in WII-UU after an update, in Settings and with `wiiuu --changelog`.

## 1.9.90 (2026-10-12)
- OpenBased is a cinema now: its home tile is dark with film-strip edges and a gold marquee name,
  and opening it dims the lights (the menu music fades) into a streaming-style screen. The focused
  title fills the top with its backdrop, story, year, length and genres, and Play or Resume with
  the time left; below are rows of Continue watching, Series, Movies and More. A series opens to
  its seasons and episodes, at the one you're on.
- Controllers plugged into a Mac are players of their own too, in the menu and in Choccy Milk games
  (Xbox, PlayStation, Switch Pro and MFi controllers, through Apple's GameController framework),
  with remapping, the player order and sending them to another WII-UU. Other emulators keep reading
  them themselves.
- Linked WII-UUs can copy each other's games: Copy its games (Settings > GamePad & players > Other
  WII-UUs > the WII-UU) copies the games the other one has and this one doesn't, resuming big
  copies where they stopped and pausing while a game runs. Nothing is replaced or deleted.

## 1.9.89 (2026-10-12)
- Choccy Milk upscaling, per console (Settings > Games > Choccy Milk picture; U in the Choccy Milk
  app): Scale2x / Scale3x pixel-art upscaling for the 2D consoles, Smooth for all, and for the
  PlayStation the 3D drawn at 2x / 4x by Beetle PSX itself.
- Widescreen (W in the app): PlayStation 3D games really widen (the widescreen hack), the Mega Drive
  shows more of each level (Genesis Plus GX Wide), and other consoles are stretched to 16:9.
- Choccy Milk no longer picks a bsnes core for the Super Nintendo (it would crash: its coroutine
  stacks are too small for Choccy Milk's calls); it gets Snes9x instead.

## 1.9.88 (2026-10-12)
- Remap a controller button for button: Settings > GamePad & players > Controller buttons. Pick a
  GamePad button and press the controller's button that should be it; the two swap, so nothing
  gets lost. Extra buttons (back paddles...) can be added too. The layout works everywhere the
  controller plays (menu, Choccy Milk, every emulator, another WII-UU it's sent to) and is kept for
  that kind of controller. Back to the standard layout undoes it.

## 1.9.87 (2026-10-12)
- Several WII-UUs on one network work together: Settings > GamePad & players > Other WII-UUs
  lists the ones nearby. Link two once with the other one's pairing code, then:
  - Send my controllers there: controllers plugged into this WII-UU play on the other one, each
    as a player of its own (a Raspberry Pi's USB controller playing Choccy Milk on your MacBook).
  - Sync saves: Choccy Milk's saves and save states stay the same on both. The newer copy wins,
    the one it replaces is kept in saves/.backup, and nothing syncs while a game runs.
- Linked WII-UUs use a key of their own for each other; the pairing code is only needed to link.
  WII-UUs find each other on UDP port 26780 (the Windows installer allows it on private networks).

## 1.9.86 (2026-10-12)
- RetroAchievements: a ROM that is a version of a game RetroAchievements doesn't support (Super
  Mario 64's European or Japanese release, for example: only the USA one works) no longer shows
  "Unsupported Game Version" as an achievement you unlocked after a few seconds. Choccy Milk now
  says "Achievements off: unsupported version of ..." when the game starts, and the game's tile in
  the menu says "Wrong version" instead of a 0/114 that could never go up. The game's Supported
  Game Files page on retroachievements.org lists the ROMs that work.
- RetroAchievements' other made-up warning achievements (like "Warning: Unknown Emulator", which
  is about hardcore mode) don't pop up as unlocks either.

## 1.9.85 (2026-10-12)
- Choccy Milk has its own look: Cocoa in dark mode and Milk in light mode (T switches, and it's
  remembered), with a chocolate band dripping across the top, the glass of chocolate milk as its
  logo, panels and chocolate "pills" for the chosen console and game, consoles as coloured drops,
  and rounded type when the computer has some.
- Choccy Milk starts with its own animation: a glass of milk drops in, chocolate syrup pours in and
  gets stirred, a straw lands and the name pops in, then it fades into the game list. Any key,
  click or controller button skips it; --no-intro or choccy.intro=false turns it off.
- Choccy Milk has its own icon (a glass of chocolate milk) for its window, the Dock / taskbar and,
  on Linux, its menu entry.
- WII-UU has a new theme to match: Settings > Look & sound > Theme > Choccy Milk.

## 1.9.84 (2026-10-12)
- Plug in a controller and play (Linux and Raspberry Pi): any USB or Bluetooth controller now
  becomes a player like a phone. It gets the next free player number, shows by name under
  Settings > GamePad & players > Player order (reorder phones and controllers together), and in
  games plays through that player's virtual Xbox 360 controller, the one every emulator is already
  set up for: RetroArch, Choccy Milk, Mupen64Plus, Dolphin, PCSX2, DuckStation and the rest need no
  setting up for it. WII-UU takes the controller over while it runs, so no game sees it twice or as
  the wrong player. Buttons go by position (bottom B, right A, as on the GamePad); triggers, both
  sticks, d-pad, Start / Select and the guide button all work. Settings > GamePad & players >
  Controllers are players turns it off.

## 1.9.83 (2026-10-12)
- Choccy Milk writes the game's own saves (battery saves; N64 EEPROM, SRAM, FlashRAM and Controller
  Pak, like Super Mario 64's stars) within a second of the game saving instead of every 30 seconds,
  and also when it's stopped hard (WII-UU giving up waiting, the system shutting down). A crash or
  a power cut right after saving no longer loses it. The .srm files are RetroArch's format.

## 1.9.82 (2026-10-12)
- Save states in Choccy Milk: F2 saves the game as it is, F4 loads it again, F6 / F7 pick one of 10
  slots (RetroArch's keys). On the phone, *Save state*, *Load state* and the slot are on the GamePad
  screen while a Choccy Milk game runs. The files are RetroArch's names (game.state, game.state1...)
  next to the in-game saves in ~/.wiiuu/saves/<console>. The Choccy Milk app has them too.

## 1.9.81 (2026-10-12)
- RetroAchievements in Choccy Milk: achievements unlock while you play, in WII-UU and in the Choccy
  Milk app. With your RetroAchievements password in Settings > Games > RetroAchievements account,
  Choccy Milk gets a sign-in token (only the token is kept), finds the game by its ROM, and shows how
  many achievements you have; when one unlocks, a trophy card shows on the TV and the phones get a
  notice. Softcore, for NES, SNES, Game Boy (Color / Advance), N64, DS, Master System and Mega Drive.
  Choccy Milk checks the achievements itself, the way rcheevos (RetroArch's achievement library) does.
- Choccy Milk app: F2 signs in to RetroAchievements.

## 1.9.80 (2026-10-12)
- Choccy Milk's sound has the right pitch: it plays at 48 kHz and resamples the core's sound itself.
  Cores use odd rates (32040.5 Hz, 32768 Hz...), which some sound systems rounded instead of
  converting, so everything sounded too high or too low.
- Pick your player number: tap P1 (P2...) at the top of the GamePad. A number another phone has is
  swapped with it. Games read player 1's controller first, so after player 1's phone leaves, the
  next phone can simply become player 1.
- Settings > GamePad & players > *Player order* changes the numbers on the TV (and *Number them 1,
  2, 3... again* closes the gaps). *Phones choose their player* turns the menu on the phone off.

## 1.9.79 (2026-10-12)
- Choccy Milk plays N64 much faster, especially on a Raspberry Pi:
  - the RSP runs high-level (ParaLLEl N64) or recompiled (Mupen64Plus-Next) instead of the slow
    cxd4 interpreter, and the software renderer explicitly uses every processor core;
  - the recompiler is used unless it crashed in Choccy Milk itself (RetroArch's crashes no longer
    count). If it crashes, Choccy Milk switches to the interpreter by itself and, on a Raspberry Pi 5
    with 16 KB memory pages, says how to fix that (kernel=kernel8.img in /boot/firmware/config.txt).
- Choccy Milk's sound: more room (100 ms), and when the game can't keep up, frames are skipped
  (not drawn) so the sound keeps going instead of crackling.
- Frames go to WII-UU on their own thread: a busy WII-UU no longer slows the game down.
- The log (logs/n64.log) says how fast the game runs every 10 s, and has the core's messages.

## 1.9.78 (2026-10-12)
- Choccy Milk on its own: `choccymilk` (or `java -jar ChoccyMilk.jar`) lists the games in WII-UU's ROM
  folders and plays them in its own window, without WII-UU. It shares WII-UU's settings, saves,
  cores and Java. Keyboard, mouse and controllers; F11 for full screen; `choccymilk GAME` plays a file.
- WII-UU's engine is a library now (engine/, build/wiiuu-engine.jar), which WII-UU and Choccy Milk
  both use: settings, consoles and games, controllers, cores, Choccy Milk and the game screen.
- Choccy Milk: E / R on the keyboard are ZL / ZR.

## 1.9.77 (2026-10-12)
- GamePad screen with Choccy Milk: the phones get the game's own picture instead of a screen capture.
  The exact picture (sharp pixels, the game's shape), up to 60 frames a second, less delay, less
  work for the computer, and no Screen Recording permission needed on a Mac. Settings:
  stream.directFps (60), stream.directQuality (85), stream.maxWidth (854).

## 1.9.76 (2026-10-12)
- Choccy Milk plays N64 (Mupen64Plus-Next, software renderer). It crashed at once before: the core
  logs without checking for a log interface, which Choccy Milk didn't give it (it gives log, perf and
  rumble interfaces now). Cores that run games on their own stacks (libco) no longer die at random
  with a StackOverflowError either.
- Whatever a core prints goes to its log instead of into the picture.
- On an Apple Silicon Mac where WII-UU runs through Rosetta (an Intel Java), Choccy Milk still runs
  natively: an ARM Java and ARM cores of its own (~/.wiiuu/choccy/cores/arm64). Much faster.

## 1.9.75 (2026-10-12)
- Building from source (install.sh in a git clone) no longer leaves out Choccy Milk when the default
  JDK is older than 22 ("This copy of WII-UU was built without Choccy Milk"): build.sh uses a newer
  JDK on the computer (Homebrew's, say), or else takes Choccy Milk from the release zip in docs/.

## 1.9.74 (2026-10-12)
- Choccy Milk needs neither RetroArch nor a Java of yours: when there's no Java 22+, the first game
  downloads one for it (Eclipse Temurin 25, about 50 MB, into ~/.wiiuu/choccy/jre) and then starts by
  itself; without RetroArch, cores download into ~/.wiiuu/choccy/cores.

## 1.9.73 (2026-10-12)
- Mac installer: Homebrew runs as ARM on Apple Silicon even from a terminal in Intel (Rosetta) mode,
  which it refused before ("Cannot install under Rosetta 2"), and an outdated Java gets upgraded.
- Choccy Milk finds Java in Homebrew's Cellar too, and only uses a Java built for the same processor
  as WII-UU's (an ARM core can't load into an Intel Java).

## 1.9.72 (2026-10-12)
- Choccy Milk is a switch of its own now: switched on, it plays every console it has cores for, not
  only in RetroArch mode (on a Mac, N64 went to mupen64plus instead). Left alone it follows RetroArch
  mode as before.
- Choccy Milk finds Homebrew's Java on a Mac (`brew install openjdk`; the installer offers it), and
  when it's on but there's no Java 22+, WII-UU says so instead of quietly using another emulator.

## 1.9.71 (2026-10-12)
- **Choccy Milk**: WII-UU's own player for RetroArch's cores. In RetroArch mode games now play inside
  WII-UU's window instead of in RetroArch: no second window, no fullscreen switching, nothing to
  freeze or lose focus. The phones' buttons and sticks go straight to the game; HOME saves and goes
  back to the menu. It uses RetroArch's cores (the Snap's too) and BIOS files and keeps its own saves.
  Cores that need OpenGL still play in RetroArch, by themselves. Choccy Milk needs Java 22 or newer
  beside WII-UU's (`sudo apt install openjdk-25-jre`); *Settings > Games > Play with Choccy Milk*
  turns it off.

## 1.9.70 (2026-10-11)
- Two switches for finding out why RetroArch doesn't show a game: `retroarch.fullscreen=false` starts
  it without `-f`, `retroarch.appendConfig=false` without WII-UU's settings for it.

## 1.9.69 (2026-10-11)
- RetroArch as a Snap (/snap/bin/retroarch) is understood now: WII-UU uses the Snap's own cores and
  settings (~/snap/retroarch/common/.config/retroarch) and puts the settings it gives RetroArch where
  the Snap can read them (~/snap/retroarch/common/wiiuu). Leftovers from earlier versions in the
  Snap's settings are undone too.
- RetroArch's full screen is a borderless window for games WII-UU starts, instead of switching the
  screen mode, which left the picture frozen (black, a notification stuck) on some X11 desktops.

## 1.9.68 (2026-10-11)
- Back to how games started in 1.9.65 (no window activation, no extra step before an N64 retry).
- RetroArch no longer keeps settings WII-UU gives it for one game: it used to save them into its own
  config when the game ended (WII-UU's N64 renderer options, the RetroAchievements sign-in), so they
  applied to every game afterwards. WII-UU now tells RetroArch not to save on exit for games it
  starts, and undoes what earlier versions left in RetroArch's config (without changing the file).

## 1.9.67 (2026-10-11)
- Games in RetroArch no longer freeze right at the start (a black or dark screen with a notification
  that stays): 1.9.66 activated the emulator's window from outside, which upset RetroArch on some
  desktops. That's off again (`launch.focusEmulator=true` turns it on for those who need it).

## 1.9.66 (2026-10-11)
- Buttons from the phone reach the game again when WII-UU types them as keys: on Linux (X11, with
  xdotool) WII-UU now brings the emulator's window to the front itself once it opens, instead of
  hoping the window manager does. That mattered most when WII-UU restarted an N64 game by itself
  (after switching to the software renderer or the interpreter): the new RetroArch window could
  open without getting the keyboard.

## 1.9.65 (2026-10-11)
- N64 in RetroArch mode: when the N64 core's recompiler crashes ("Init new dynarec", error 139: some
  ARM computers, like a Raspberry Pi 5), WII-UU switches to the core's interpreter (slower, but it
  works everywhere) and starts the game again. *Settings > Games > N64 processor* picks Automatic,
  Recompiler or Interpreter yourself.

## 1.9.64 (2026-10-11)
- N64 games in RetroArch mode no longer just crash with error 139 ("[GLX] Failed to create new
  context") on computers whose graphics driver can't give the N64 core the OpenGL it wants: WII-UU
  notices, switches N64 to the software renderer (Angrylion: no OpenGL needed, slower) and starts the
  game again. *Settings > Games > N64 renderer* picks Automatic, OpenGL or Software yourself.
  RetroArch's own settings aren't changed.

## 1.9.63 (2026-10-11)
- Sign in on your phone: *Enter on your phone* on the RetroAchievements (and ScreenScraper) account
  page shows the fields on every connected phone, to type with its keyboard instead of the TV's.
  WII-UU checks the sign-in and tells you on the phone and the TV whether it worked. Phones only see
  this while the TV asks, and never secrets that are already set.

## 1.9.62 (2026-10-11)
- RetroAchievements: enter your user name and Web API key under *Settings > Games > RetroAchievements
  account* and every game tile shows your progress (a trophy and "12/40", gold at 100%). Games are
  recognized by their ROM hash, like RetroAchievements does, or by title. After playing, WII-UU tells
  you what you unlocked. With your password too, RetroArch mode signs RetroArch in so achievements
  unlock while you play (with an optional hardcore mode).

## 1.9.61 (2026-10-08)
- A boss fight in Settings > Developer: you, a little GamePad, against WII-UU, which has a gun.
  Dodge its shots (five hearts, a tiny hitbox) and shoot back with A / Space. Three phases: a
  pistol ("Pew pew."), two pistols ("Twice the fun."), and A BIGGER GUN ("BRRRRRRT."). With its own
  driving music, angry eyebrows, and opinions ("Skill issue."). Esc or − quits; WII-UU is a good
  sport about it afterwards. Mostly.

## 1.9.60 (2026-10-08)
- WII-UU keeps you company outside the setup guide too. In the menu's bottom-left corner it walks
  in, says hello by the time of day, watches the selection with its googly eyes, now and then says
  something about the console, game or dock button you rest on ("No N64 games yet. Copy some into
  roms/n64 and press -."), reacts to the GamePad code ("Scan me! Well, the code.") and the power
  menu ("Noooo, stay a little!"), welcomes you back after a game, and has something to say when
  you click it. In Settings it stands under the categories with a line for each ("Careful in here.
  This is where the serious stuff lives.") and reacts to switches ("Ouch." when the music goes off).
  Only the setup guide gives it a voice. *Settings → Look & sound → WII-UU helper* turns it off.

## 1.9.59 (2026-10-08)
- The music-making idle activity now really has Super Mario Bros. in it: the lead of WII-UU's
  Future House track plays the opening of the overworld theme as a square wave, and the piano roll
  shows its notes. (This replaces 1.9.58's folder for your own MIDI file.)

## 1.9.58 (2026-10-08)
- Your own tune for the music-making idle activity: put a MIDI file into `~/.wiiuu/studio/` and
  WII-UU plays its melody as the lead of its Future House track (the highest of the file's busier
  tracks, fitted to 128 BPM), shows it in the piano roll, and drags in a file with its name.
  Without one it plays its own tune. Settings > Developer shows where the folder is.

## 1.9.57 (2026-10-08)
- WII-UU's Future House banger can be heard now: the menu music pauses for the music-making idle
  activity, and you hear the track come together part by part as it appears on screen (kick,
  clap, hats, sub bass, saw chords, vocal chops), stop for the piano roll, come back with the
  "Mario" lead, build up with a snare roll and riser, and drop. Then the door bangs, sirens, the
  KA-CHUNK of the stamp and one lonely note. All synthesized by WII-UU; the "Mario" lead is an
  original tune, not Nintendo's. The menu music comes back when the scene ends or you take over,
  and with menu music off the scene stays silent.

## 1.9.56 (2026-10-08)
- A hidden Developer tab in Settings: press *System → Check for updates* 7 times in a row ("You're
  3 steps away from being a developer."). It starts any idle activity right away (Tetris, Pong, the
  ROM website, amazin.shop, decorating the menu, fan mail, fanfic.com, the Future House banger),
  or all of them shuffled. *Hide the Developer tab* puts it away again.

## 1.9.55 (2026-10-08)
- A new idle activity: WII-UU makes music. In "FL Stoodio" (made up) it builds a Future House
  track bar by bar (kick, clap, hats, sub bass, saw lead, vocal chops, sidechain on everything),
  head-bangs along with its headphones on, and then has an idea: the Super Mario Bros. theme, but
  Future House. Build-up, DROP!!, export, upload... "Copyright claim. A team is on its way to
  you." Red and blue lights, BANG BANG, and the COPYRIGHT S.W.A.T. team bursts in. "It's a REMIX!
  Fair use!" A giant stamp says TAKEDOWN (KA-CHUNK!), and they leave with its MIDI keyboard. "New
  track. 100% original." One note. "Masterpiece." All drawn and silent: no melody is played.

## 1.9.54 (2026-10-08)
- WII-UU helps you through the setup guide: its googly-eyed card stands in the corner, watches what
  you pick, and talks you through every step out loud, with a speech bubble that types along. It
  reacts to your choices ("Ooh. Mysterious." for dark mode, "Ouch." when the music goes off),
  says hi to your phone when it connects and giggles when you try your controller. It only talks
  in the guide; the music ducks under it. *Settings → System → WII-UU talks in the guide* turns
  the voice off.
- A new idle activity, after bedtime: at 2:47 AM WII-UU sneaks onto its computer to read
  fanfic.com, which SlowNet Broadband (made up) has blocked. "Reason: reasons." Then "still
  reasons", then in secret mode "we can see you in there". A VPN to Antarctica (1 penguin online)
  gets it to chapter 47 of "The GamePad and Me", until SlowNet blocks it again ("Since when do you
  live in Antarctica?"). When the hallway light comes on, the screen goes off and WII-UU is very
  much asleep. Zzz. All drawn: nothing is visited.

## 1.9.53 (2026-10-08)
- A new idle activity: WII-UU reads its fan mail. Letters drop through the door, and it reads them
  one by one in its armchair: Timmy (9) thinks it's the best console ever ("Aww."), Mia drew it
  ("I look amazing. Framing this one."), someone asks whether it can run Crysis ("No. Next.", into
  the bin). The last one is made of cut-out magazine letters. WII-UU reads it in silence, stares,
  sweats a little: "Oh, that's not a fan letter, that's a death threat. Oh, welp. Another one to
  add to my collection." Then it files it in its overflowing THREATS (2) drawer. "Anyway! Great fan
  mail today."

## 1.9.52 (2026-10-08)
- Covers are found online: games without a cover get one from the libretro thumbnails collection
  (or from ScreenScraper, if you add a ScreenScraper account under *Settings → Games*). WII-UU looks
  after every scan; *Settings → Games → Find covers online* and `wiiuu --scrape` do it on demand.
  Covers you have are never replaced, and *Find covers by itself* turns the automatic search off.
- A new idle activity: WII-UU sits down at its computer to decorate the menu. It searches the web
  for box art ("Nope, that's fan art."), prints the pictures, sorts the pages alphabetically
  ("Obviously.") and pins them one by one onto a WII-UU menu on the wall. The pictures are your
  games' real covers, including any it just found.

## 1.9.51 (2026-10-08)
- Another idle sketch: WII-UU goes online shopping on amazin.shop (made up, and only drawn: nothing
  is visited or bought). It picks the sponsored GOLD HDMI cable ($899.99, "1,000,000x better
  picture"), gets talked into RGB lights (+500 FPS), is not jealous of "A REAL Wii U GamePad",
  stutters on the 1-Click button until it has 47 cables ($42,317.53), gets declined ("Card holder is
  a video game console"), tries 100 arcade tokens, sends the owner its wish list, and gets a free
  sample delivered: 1 cm of golden HDMI cable. "Best. Purchase. Ever."

## 1.9.50 (2026-10-08)
- A new idle sketch, between Tetris and Pong: WII-UU goes looking for free games on
  free-roms-totally-legit.biz (made up, and only drawn: nothing is visited or downloaded). It clicks
  the big fake DOWNLOAD buttons, closes pop-ups, downloads `Super_Mario_64_FULL_GAME_100%_REAL.zip.exe`
  at 3 KB/s and opens it: the screen glitches, pop-ups multiply and a VIRUS.EXE bug eats the page,
  until WII-UU flies in with a fly swatter and splats it. The GamePad comments all the way ("Wait,
  why does it end in .exe?", "CTRL+ALT+DELETE!", "That never happened.").
- The idle activities come in a shuffled order, never the same one twice in a row.

## 1.9.49 (2026-10-08)
- WII-UU comes with a reaction video to try: *Minecraft Door* is in the Reactions channel right
  away, with WII-UU's six reactions ("alright, minecraft content!" ... "bruh"). Your own packs sit
  next to it; `reactions.examples=false` hides it.
- Reactions wait for the video to really start before the first one shows (some videos, like AV1
  ones, take mpv a moment to open).

## 1.9.48 (2026-10-08)
- Reaction mode: **WII-UU-Reactions.jar**, a new editor (in the zip and on the website). Drag
  videos in, play them with sound, and type on a timeline what WII-UU says when, with a mood and a
  length; drag reactions around, use quick reactions, and see the caption and the GamePad's bubble
  in the preview. It exports a .zip pack with the videos, captions (.vtt) and thumbnails, or sends
  it straight to WII-UU.
- Packs in `~/.wiiuu/reactions` are the new **Reactions** channel on the home screen. Their videos
  play full screen with WII-UU's reactions as captions, while the GamePad shows the WII-UU logo
  saying each one, in sync with the video. Tap the phone to pause.

## 1.9.47 (2026-10-08)
- WII-UU is a sore loser: when it's losing badly at its idle games it looks around on the GamePad
  ("Nobody's looking, right?"), leaves the phone, and turns up on the TV with googly eyes to cheat.
  In Pong it scrubs its score off the scoreboard and writes 9999999 ("WII-UU WINS (LEGIT)"); in
  Tetris it pulls blocks off its pile, flings them away and fixes its score. Then it goes back:
  "What? I didn't do anything."
- While it's away the phone shows **Catch it cheating!**: catch it in the act and it's BUSTED (on
  the TV and the phone), everything is put back, the computer gets a penalty point, and WII-UU
  sulks back to the GamePad. Catching it doesn't stop it playing.

## 1.9.46 (2026-10-08)
- When nobody uses it for 3 minutes, WII-UU plays by itself on the TV: Tetris (good, but not
  perfect, and more careless as it speeds up) and Pong against the computer, taking turns. The
  GamePad shows the WII-UU logo commenting on its own game in a speech bubble ("Damn!", "TETRIS!!",
  "I lost to a computer. Wait, I AM a computer."), hopping when it's happy and slumping when it
  isn't. Any button or a tap on the phone takes over ("Oh, you're back! I was totally winning.").
  *Settings > Look & sound > Plays by itself when idle* sets the wait or turns it off.

## 1.9.45 (2026-10-08)
- On Linux, WII-UU now draws with the graphics chip (OpenGL) instead of the processor when there is
  a driver for one, as on a Raspberry Pi: much less work for the processor on big TVs. Without a
  graphics chip driver it keeps drawing as before (OpenGL done in software would be slower).
  *Settings > System > Draw with the graphics chip* switches it on or off for good
  (`ui.opengl`), in case a driver draws something wrong.

## 1.9.44 (2026-10-08)
- The GamePad (phone) says **WII-UU is not running** when WII-UU closes, and why: closed, restarting,
  updating, or the computer going to the desktop or shutting down. If WII-UU disappears without a
  word (a crash, the power going off), the phone notices within a few seconds. It connects again
  by itself, and says so, when WII-UU is back.

## 1.9.43 (2026-10-08)
- New Settings, drawn on the TV like the rest of the menu instead of in a window of the computer's:
  categories on the left (Look & sound, Games, GamePad & players, OpenBased, System), and every
  setting on the right. They work with the phone, controllers, the keyboard and the mouse, scroll
  smoothly (also with the mouse wheel), and every change applies and is saved at once.
- Text is typed on an on-screen keyboard steered with the GamePad (or a real keyboard, with
  Ctrl+V to paste); folders and emulator programs are picked in a built-in file browser.
- Each console has its own page (shown or hidden, command, emulator program, games folder), each
  player a page of keyboard keys (A, then press the key, or ◀ ▶ through them), and What's new and
  updates are part of Settings too.
- Choosing a theme or a music track in Settings applies it right away.

## 1.9.42 (2026-10-07)
- Themes, with their own language: small `.wtheme` text files that recolour the whole menu, with
  variables (`let`), colour functions (`mix`, `lighten`, `alpha`, `spin`, `gradient` …), console
  tile colours (one by one, or all at once from their own colours), fonts, and parts for light and
  dark mode. Pick one in Settings > General > Theme; *Make your own…* opens your themes folder with
  a theme to start from and the full reference. WII-UU redraws as soon as you save a theme, and
  says exactly where a mistake is (with "did you mean …?").
- Six themes come with WII-UU: Sunset, Midnight Arcade, Game Boy, Famicom, Ocean and Paper.
- `wiiuu --theme-check FILE` shows a theme's colours (as swatches in the terminal) or its mistake;
  `wiiuu --theme-reference` prints everything the language knows.

## 1.9.41 (2026-10-07)
- The setup guide's controller step is now a live controller test: a GamePad on screen lights up
  every button, shoulder, trigger and d-pad direction you press, its sticks lean the way you push
  them, and it says which controller or phone it came from. + (Start) goes on.
- Changing the look in the setup guide wipes the new theme in, in a circle growing from your choice.
- The first-boot intro has a new cut, a counter racing up to how many consoles WII-UU knows, and
  says how many games you have ("YOUR 37 GAMES").
- A secret: press ↑ ↑ ↓ ↓ ← → ← → B A in the menu.
- Controllers' top and left buttons count as X and Y (as on the GamePad).

## 1.9.40 (2026-10-04)
- The first-boot intro runs smoothly on a Raspberry Pi, also on a 4K TV: it took up to half a
  second per frame (2-4 frames per second). It is now drawn at 960 pixels wide or less and
  enlarged, and goes down to 640 if the computer still can't keep up.
- `wiiuu --fps` says which screen each line is about (start-up, intro, setup guide, menu), and
  at what size the intro is drawn.

## 1.9.39 (2026-10-04)
- `wiiuu --setup` starts WII-UU with the setup guide, after the first-boot intro, without
  having to go through Settings. Add `--no-intro` to go straight to the guide.
- After an update, the What's new notes wait until the setup guide is closed instead of covering it.

## 1.9.38 (2026-10-04)
- `wiiuu --fps` (or `wiiuu -Dwiiuu.fps=true`) prints the menu's frame rate and paint time every
  two seconds, to help track down stutter. Before, the `-D` setting was ignored when given
  after `wiiuu`; WII-UU now takes any `-Dname=value` setting there.

## 1.9.37 (2026-10-04)
- The start-up animation no longer stutters on the first boot (or a restart into the setup guide):
  getting the intro ready while it played was too much for a Raspberry Pi, especially on a 4K TV.
  Its pictures of the menu are now taken at a quarter of the size or less, one at a time with
  pauses, and its beat is made before the animation gets going. The intro looks the same.

## 1.9.36 (2026-10-04)
A first boot to remember, and a smooth setup guide.
- The first start (and Restart into the setup guide) now opens with an eight-second intro on its
  own beat: the logo slams in, then fast cuts through your real menu (the home screen, a console's
  games, the GamePad screen, your games), consoles flashing by in their colours, a tunnel of
  tiles, and the logo again before the setup guide. Any button skips it.
- The setup guide no longer lags: it used to redraw the whole screen, menu included, about 60
  times a second even when nothing moved. Now it only draws while something moves and reuses
  its background, card and QR code, using about 15 times less CPU on a still step.

## 1.9.35 (2026-10-04)
- Settings → General → Restart into the setup guide: WII-UU restarts and starts with the setup
  guide, start-up animation and all, like the very first time. Your settings and games are kept.

## 1.9.34 (2026-10-04)
A setup guide for the first start.
- On its first start WII-UU now welcomes you with a setup guide on the TV: pick light or dark,
  menu music and sounds, connect your phone as GamePad (it shows when the phone is connected),
  see where your games go and turn on RetroArch mode, learn about controllers, done.
- Animated like a console's own setup: the logo drops in, steps slide, switches glide, and the
  last step ends with a tick and confetti.
- Settings → General → Show the setup guide brings it back any time.

## 1.9.33 (2026-10-04)
Controllers in the menu.
- USB and Bluetooth controllers now work in WII-UU's own menu on Linux and Raspberry Pi, not only
  in games: stick or d-pad to move, the bottom button to open, the right one to go back, the
  shoulders and triggers to turn the page, Start for the GamePad info, Select to refresh.
  Controllers plugged in while WII-UU runs work too.
- Nintendo-style controllers: set input.padConfirm=east to open with the right button instead.

## 1.9.32 (2026-10-04)
- N64 (Mupen64Plus): the phone's buttons were mixed up (N64 A on the phone's B, nothing on A).
  WII-UU now gives Mupen64Plus the phone's layout: A and B as on the phone, ZL = Z, L / R (and ZR)
  = L / R, + = Start, the right stick for the C buttons (X = C-up, Y = C-left too) and the left
  stick for the N64 stick, for every phone that's connected. With a real controller plugged in,
  Mupen64Plus sets it up itself as before.

## 1.9.31 (2026-10-04)
N64 games show a picture on the Raspberry Pi.
- Mupen64Plus built by wiiuu-emulators ran N64 games with sound but a black screen on a Raspberry
  Pi: it was built for desktop OpenGL, which the Pi only offers in an old version. On ARM boards it
  is now built for OpenGL ES, like RetroPie does.
- It now also comes with GLideN64, the better video plugin most N64 setups use, and WII-UU starts
  N64 games with it.
- Already built it? Run "wiiuu-emulators --only mupen64plus" once after updating.

## 1.9.30 (2026-10-04)
Phones work as controllers in RetroArch on Linux and Raspberry Pi.
- RetroArch kept saying "Microsoft X-Box 360 pad ... not configured" and ignored the phone: its
  package on Raspberry Pi OS, Debian and Ubuntu has no controller profiles. WII-UU now tells
  RetroArch the layout of the phone's virtual controller itself, and those messages are gone.
- With a real controller plugged in too, RetroArch's own profiles are used as before.

## 1.9.29 (2026-10-04)
- OpenBased: "Use a token instead" now links to OpenBased's new API tokens page and says which
  boxes to tick. The setup help covers OpenBased installed as a Linux service
  (/etc/openbased/application.yml).

## 1.9.28 (2026-10-04)
Sign in to OpenBased instead of pasting a token.
- Press Sign in with OpenBased (on the phone, or in Settings → OpenBased): OpenBased's own sign-in
  page opens, and after you sign in, WII-UU is connected. No token to copy.
- WII-UU then keeps its own access token in your OpenBased account for a year. Signing in again
  replaces it, and Sign out revokes it.
- OpenBased needs to know WII-UU once: WII-UU shows the lines to add to OpenBased's settings.

## 1.9.27 (2026-10-04)
OpenBased: watch your own movies and shows in WII-UU.
- Connect an OpenBased media server (Settings → OpenBased, or on the phone: Library, + OpenBased)
  and its movies and episodes appear as a channel on the home screen, with their posters, and play
  full screen on the TV, in Console Mode too.
- Videos you started come first, and with mpv they continue where you stopped; your progress is
  saved to OpenBased, so continue watching works in your other OpenBased apps.
- The GamePad controls the video: pause, seek, volume, subtitles. HOME closes it.
- Console Mode installs mpv for this.

## 1.9.26 (2026-10-03)
The phone makes the menu music itself.
- On the Mac, the GamePad page now makes WII-UU's menu music itself, with the same synthesizer as
  WII-UU (it sounds exactly the same), and plays the track WII-UU plays, in step with it. Only
  which track plays, where, and how loud travels over the network, so the music can't stutter or
  lag. The Extended Mix carries on from one track to the next on the phone too.
- A track takes a few seconds to make on the phone when it changes; the music fades in once it's
  ready.
- Your own music files, and the menu's sound effects, still come from WII-UU.

## 1.9.25 (2026-10-03)
- On the Mac, the menu music and sound effects now go to the phone straight from WII-UU's own
  mixer instead of being recorded from the speakers, so they arrive clean. The recording of
  other apps and games leaves WII-UU out, so nothing is heard twice.

## 1.9.24 (2026-10-03)
- On the Mac, WII-UU's own menu music now plays on the phone too, not only the sound of other
  apps and games.

## 1.9.23 (2026-10-03)
Sound on the Mac, take three.
- Mac sound is now recorded inside WII-UU itself instead of a separate helper, so it gets the
  same macOS permissions as the picture (and works from WII-UU.app, whose built-in Java can't
  start a second Java).
- ~/.wiiuu/logs/audio.log now notes how loud the recorded sound is every few seconds, to tell
  "macOS gives silence" apart from "macOS gives nothing".

## 1.9.22 (2026-10-03)
More reliable sound on the Mac.
- When macOS is slow to allow sound recording (for example while a permission dialog waits),
  WII-UU keeps waiting instead of giving up after 20 seconds, and the phone says what it's
  waiting for.
- On macOS 14.2 and newer, if Screen Recording doesn't give sound within 5 seconds, WII-UU
  records the system output another way. macOS may then ask you to allow
  "System Audio Recording".
- Each step is written to ~/.wiiuu/logs/audio.log, to find out what's wrong if there's still no
  sound.

## 1.9.21 (2026-10-03)
Sound on the Mac without the Xcode Command Line Tools.
- The phone's sound on a Mac now comes from a small library built into WII-UU (Objective-C,
  for Apple Silicon and Intel), so it no longer has to be compiled on your Mac first, and Apple's
  Command Line Tools updates can't break it any more.
- It still needs the Screen Recording permission. If it gives no sound, WII-UU falls back to the
  old way by itself.

## 1.9.20 (2026-10-02)
The WII-UU Extended Mix, and a music visualizer.
- Settings > General > Menu music > **WII-UU Extended Mix**: every Future and Color House
  remix non-stop at 128 BPM, like a DJ set. Each track gets 8 bars of drums and bass at its start
  and end, and the next one is beat-matched and blended in over them.
- **Music visualizer**: frequency bars move behind the menu while music plays. Press **V** for
  the full-screen visualizer, with a spectrum ring that pulses with the bass, the waveform and
  the name of the track. V, Esc or B goes back.

## 1.9.19 (2026-10-02)
Color House remixes.
- Every tune, WII-UU's own included, now also comes as a **Color House remix** at 126 BPM:
  bubbly, metallic chord plucks tuned to the chords, a vocal-like lead gliding through vowels,
  a talking bass bouncing around the kick, and crisp hats.

## 1.9.18 (2026-10-02)
Future House remixes.
- Every tune, WII-UU's own included, now also comes as a **Future House remix** at 128 BPM:
  a build-up with the filter opening on the chords, vocal chops, a snare roll and a riser, then
  a drop with kick and clap, sub and bouncing bass, a crisp saw lead and sidechain pumping.
- They play at the same volume as the other tracks.

## 1.9.17 (2026-10-02)
More menu music, each tune in two versions.
- New: Für Elise, the Turkish March, Greensleeves and Pachelbel's Canon.
- Every tune now also comes as a **WII-UU remix**: slower, lightly swung, in the menu's own
  sound with vibraphone and electric piano.
- Settings > General > Menu music, or All of them, taking turns.

## 1.9.16 (2026-10-02)
More menu music, and N64 games fill the screen.
- Settings > General > **Menu music**: besides WII-UU's own tune, 8-bit style versions of
  Korobeiniki (the Tetris theme), In the Hall of the Mountain King and Ode to Joy, or all of
  them taking turns.
- **Add your own…** opens a folder for your own WAV or AIFF music.
- Mupen64Plus games fill the screen instead of a small 640x480 picture.

## 1.9.15 (2026-09-30)
The WII-UU logo on Windows.
- The Start menu and Desktop shortcuts, and the taskbar button of WII-UU started from them,
  show WII-UU's logo instead of Java's. If you pinned WII-UU to the taskbar before, pin it again.
- The window's icon comes in every size, so it stays sharp on scaled displays.

## 1.9.14 (2026-09-30)
Release notes, in WII-UU itself.
- After an update WII-UU shows what's new since the version you had.
- Settings (F1) > General > **What's new** lists the changes in every version.
- Before an update installs, you see what it brings.
- `wiiuu --changelog` prints them in the terminal, and the website has a What's new section.

## 1.9.13 (2026-09-30)
Lighter split screens on Windows.
- The phone's touch screen costs far less: the capture helper shrinks the picture to the
  phone's size before handing it over.
- Finding RetroArch's window no longer starts PowerShell about once a second while you play.

## 1.9.12 (2026-09-30)
The phone's touch screen on Windows shows RetroArch's touch screen.
- It showed the bottom of the TV (the taskbar) when RetroArch's window appeared late; it
  now waits for the window.
- Taps land on the right spot, however each monitor is scaled.

## 1.9.11 (2026-09-30)
Smooth TV picture for split DS/3DS screens on Windows.
- RetroArch's window now reaches from the TV down onto the hidden display, so the TV shows
  the top screen directly instead of a copy, at RetroArch's own frame rate.

## 1.9.10 (2026-09-30)
- Split screens on Windows: the top screen is centred and no longer magnified on scaled
  monitors.

## 1.9.9 (2026-09-30)
- Split screens on Windows: the top screen fills the TV at its own shape.

## 1.9.8 (2026-09-30)
- Split screens on Windows: no more gray or black pictures from the virtual display.

## 1.9.7 (2026-09-30)
- Split screens work when the TV and the virtual display use different display scaling.

## 1.9.6 (2026-09-30)
- Windows: finding and moving RetroArch's window works reliably.

## 1.9.5 (2026-09-30)
- Split screens on Windows: correct TV window size, and RetroArch's window is found.

## 1.9.4 (2026-09-30)
- Split-screen diagnostics: every step goes to `logs/split.log`, plus a desktop picture
  (`logs/split.jpg`).

## 1.9.3 (2026-09-30)
- The virtual display also switches on through Windows' newer display settings.

## 1.9.2 (2026-09-30)
- The TV and the phone say why a DS game's screens aren't split.
- Settings > General > Install virtual display (Windows).
- `wiiuu --split-check` shows what WII-UU sees of your displays.

## 1.9.1 (2026-09-30)
- Split DS/3DS screens on Windows, with the free Virtual Display Driver
  (`install.ps1 -VirtualDisplay`).

## 1.9.0 (2026-09-30)
Split DS/3DS screens, like a DS game on a Wii U: the top screen big on the TV, the touch
screen on the phone.
- In RetroArch mode on Linux (X11); Settings > General > Split screens.

## 1.8.4 (2026-09-30)
- Windows with display scaling: the TV stream shows the whole screen, not its top-left
  corner, and taps land where they should.

## 1.8.3 (2026-09-30)
- The phone's second screen works in RetroArch mode (it waited for a melonDS window).

## 1.8.2 (2026-09-29)
- macOS: the helpers build again after Command Line Tools updates.
- Uses much less memory (about 370 MB instead of up to 1 GB).

## 1.8.1 (2026-09-29)
- macOS: the GamePad's buttons keep working after updates (the Accessibility permission
  stays valid).

## 1.8.0 (2026-09-29)
- Buzz! mode: phones become Buzz! buzzers for PS2 Buzz! quiz games.
- 8-player mode: up to 8 phones at once.

## 1.7.0 (2026-09-29)
- PCSX2 and DuckStation get the GamePad's controls for the game, and their settings come back
  unchanged afterwards.

## 1.6.0 (2026-09-29)
- Console Mode: turns a Linux PC or Raspberry Pi into a WII-UU console that boots straight
  into WII-UU, with a Power menu and a Desktop Mode.

## 1.5.0 (2026-09-29)
- RetroArch mode: runs every system that has a RetroArch core in RetroArch, downloading
  missing cores by itself.

## 1.4.0 (2026-09-29)
- Dark mode: Settings > General > Theme (Auto follows the system).

## 1.3.1 (2026-09-29)
- Smooth start-up animation.

## 1.3.0 (2026-09-29)
- Start-up animation with a chime, and background music in the menu.

## 1.2.0 (2026-09-28)
- The PC's sound on the phone: tap Sound.

## 1.1.6 (2026-09-28)
- Lower GamePad screen delay.

## 1.1.5 (2026-09-28)
- macOS: much faster GamePad screen (ScreenCaptureKit).

## 1.1.4 (2026-09-28)
- macOS: faster GamePad screen.

## 1.1.3 (2026-09-28)
- iPhone and Safari: no more blank TV and GamePad pictures.

## 1.1.2 (2026-09-28)
- macOS: no more black GamePad screen; Dolphin's controls work.

## 1.1.1 (2026-09-28)
- macOS: TV mirroring and closing games work; emulators get the right key layouts.

## 1.1.0 (2026-09-28)
- Smooth GamePad screen, real controllers, reliable launching and closing of games, phones
  stay paired, and updates from inside WII-UU.

## 1.0.0 (2026-09-27)
- First release: a Wii U style launcher for emulators, with your phone as the GamePad.
