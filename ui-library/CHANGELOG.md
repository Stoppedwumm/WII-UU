# WII-UU UI Library changelog

## 1.1.0 (2026-11-19)

- Add `Ui`, `UiTheme` and `UiButton`: themed Swing controls, native event/model/keyboard support, rounded button states, form inputs, containers and live theme changes. Include a complete controls example and use the toolkit in Choccy Milk Settings.
- Move the existing `wiiuu.ui.ThemeScript` parser into the shared engine and standalone JAR, preserving its package and API.
- Extract `AnimatedMascot`: branded appearance, live theme and clock providers, speech bubbles, gaze tracking, walking, hops and hit testing. WII-UU-specific dialogue stays in the app.
- Extract `AudioVisualizer`: configurable sample rate, host-supplied mono PCM and nanosecond clock, FFT analysis, spectrum bars/ring and waveform. WII-UU audio capture stays in the app.
- Extract `MenuPainter` for menu backgrounds, soft shadows and GamePad/settings/refresh/power vector icons.
- Extract `UiFonts` for desktop font discovery and independent font caches, and `UiText` for width-aware ellipsis and wrapping. Both apps use the shared helpers.
- Export `MenuPalette` with WII-UU's light/dark colours and a convenience method for evaluating themes without console tiles.
- Move Choccy Milk's appearance into public `MilkTheme`: Cocoa/Milk colours, background painting, selection pills, shadows, glass artwork, font selection and window icons.
- Move Choccy Milk's intro into public `MilkIntro`, with custom title/tagline and an optional explicit start timestamp. Keep repaint scheduling and skip input in the host app.
- Preserve the caller's graphics state when painting the intro, and preserve clipping when painting a glass with a straw.
- Update WII-UU and Choccy Milk to consume the shared classes. The library still has no launcher, emulation, configuration or server dependencies.
- Add a standalone theme/animation example and library-only headless checks to CI. Existing renderer APIs remain available.

## 1.0.0

- Standalone Java 17 game screen renderer, display filters, desktop theme detection and GamePad button types.
