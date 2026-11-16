# WII-UU web preview

From the repository root, run `python3 -m http.server 8000 --directory docs`
and open [localhost:8000/demo/](http://localhost:8000/demo/). The demo uses the
checked-in build. Deploy the complete `docs` directory on an HTTPS static host.
The player needs WebAssembly, module workers, and a secure context (HTTPS or
localhost) for browser storage identifiers and audio. No server API is needed.

Select Milk Run to play the included original Game Boy homebrew, or import an
uncompressed `.gb` or `.gbc` ROM. ROMs, battery RAM and states live in the
browser’s IndexedDB. Export save states to move them between browsers with the
same ROM and core. Keyboard, touch and standard browser gamepads are supported.
Use `/demo/?app=choccy` for the standalone player view.

The browser-specific Choccy Milk host in `src/choccy.cpp` implements the libretro
callbacks used by Gambatte. The host, core, and WII-UU menu navigation run in
WebAssembly. A module worker paces the emulator, Canvas presents video, and an
AudioWorklet plays stereo audio. `docs/demo/ui/library.mjs` ports the published
Java UI library's three types (`GameScreen`, `SystemTheme`, and `PadButton`).
The pixel-filter generator reads the Java algorithms to keep them in sync.
Browser callers can import the module directly and pass a Canvas2D context to
`GameScreen.paint`. The desktop menu views, animations, DS emulation and phone
GamePad pairing are outside the current preview.

Install and activate Emscripten 4.0.10 using emsdk, then run:

```sh
bash web/build.sh
node web/tests/runtime.mjs
```

The build uses Git, GNU Make, and Python 3. It fetches the pinned
[Gambatte revision](https://github.com/libretro/gambatte-libretro/tree/77f2567d718b8973d02caa2daa8dce4953faf811).
The generated module and launcher are stored in `docs/demo/cores/`.
That directory also contains the upstream GPL license. The corresponding
sources are the pinned Gambatte checkout, the browser host and build script in
this directory, and Emscripten 4.0.10. `make-demo.py` contains the full source of
the demo cartridge.
