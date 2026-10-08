# Changelog

What changed in each WII-UU release, newest first. WII-UU shows these notes after an update,
in Settings > General > What's new, and before installing an update.

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
