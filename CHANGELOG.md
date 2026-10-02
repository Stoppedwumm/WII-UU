# Changelog

What changed in each WII-UU release, newest first. WII-UU shows these notes after an update,
in Settings > General > What's new, and before installing an update.

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
