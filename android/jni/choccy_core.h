/*
 * Choccy Milk's libretro host, in C: loads a core (a *_libretro.so), runs one game with it, and
 * hands its pictures and sound to whoever shows and plays them (Android's window and speakers, or
 * a test). The same thing the desktop Choccy Milk does in Java, for Android, which can't load
 * native libraries from Java without JNI.
 *
 * One game at a time (libretro's callbacks have no context pointer). All of it on one thread (the
 * game's), except cm_input, which may come from anywhere.
 */
#ifndef WIIUU_CHOCCY_CORE_H
#define WIIUU_CHOCCY_CORE_H

#include <stddef.h>
#include <stdint.h>

typedef struct cm_host {
    /** a frame, as 0x00RRGGBB pixels, w x h, rows w apart */
    void (*video)(void *user, const uint32_t *px, int w, int h);
    /** n stereo sound frames (left, right, left...) at the core's rate */
    void (*audio)(void *user, const int16_t *frames, size_t n);
    /** a line for the log */
    void (*log)(void *user, const char *line);
    /** something for the player to see ("State 1 saved", a core's own message) */
    void (*notice)(void *user, const char *text);
    void *user;
} cm_host;

typedef struct cm_av {
    double fps;
    double sample_rate;
    int width, height;          /* the picture's usual size */
    float aspect;               /* its shape on screen */
} cm_av;

/**
 * Loads core and the game (saves and save states go to save_dir as ROM.srm / ROM.state<N>; BIOS
 * files come from system_dir). options: "key=value" lines that override the core's defaults.
 * 0 when it plays, else -1 with why in err.
 */
int cm_load(const char *core, const char *rom, const char *save_dir, const char *system_dir,
            const char *options, const cm_host *host, char *err, size_t errlen);

/** The core's name and version ("FCEUmm 98.13"). */
const char *cm_core_name(void);

/** The picture and sound timing (it may change while playing). */
cm_av cm_get_av(void);

/** Runs one frame (the pictures and sound go to the host). */
void cm_run(void);

/**
 * A player's controls (RetroPad buttons as bits by libretro's ids, sticks -32768..32767). From
 * any thread; a press shorter than a frame still counts.
 */
void cm_input(int port, uint32_t buttons, int16_t lx, int16_t ly, int16_t rx, int16_t ry);

/** Writes the game's own save (ROM.srm) when it changed; 1 written, 0 nothing to write, -1 failed. */
int cm_save_sram(void);

/** Saves (save = 1) or loads a save state in slot 0..9; 0 when it worked. */
int cm_state(int save, int slot);

/** Saves, then lets go of the game and the core. */
void cm_unload(void);

#endif
